import { execFileSync, spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * C107F2-01 契约测试（W008）：固化审计反例、跨层契约样本与分层验证入口。
 *
 * - TC-F2-01-01 反例可重建：fixtures.mjs 固定 seed 两次生成 hash 逐字节一致；
 *   六类前端反例与五类后端探针均有「旧行为被判失败 + 目标正例通过」双断言。
 * - TC-F2-01-02 缺阶段拒绝：--stage e2e 未交付 → exit 2 / NOT_RUN / 无 ALL-GREEN。
 * - TC-F2-01-03 零用例拒绝：过滤器命中 0 实际执行 → 非零并指出零用例，不记 PASS。
 * - TC-F2-01-04 来源与映射：35 finding→40 卡→TC/V 引用全覆盖，fixture 边界明确。
 *
 * 本测试不启动隔离栈、不挂载 Vue 组件（那是责任卡 fix2-cXX.test.ts 的职责），
 * 也不调用 Provider：所有子进程都是本地 node/bash。
 */

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')
const FIXTURES_SCRIPT = 'scripts/acceptance/fixtures/hypit-fix2-fixtures.mjs'
const VERIFY_SCRIPT = 'scripts/acceptance/verify-107-fix-2.sh'
const SEED = '10702'

type Sample = {
  format: string
  provenance: { statusVocabulary?: string[] }
  fixtureBoundaries?: string
  layers?: Record<string, Array<{ id: string; status: string; source?: string; target?: unknown }>>
  counterExamples: {
    frontend: Array<{ id: string; finding: string; oldTrace?: Record<string, any>; targetRule?: string }>
    backend: Array<{ id: string; finding: string; observed?: Record<string, any> }>
  }
}

const samples = JSON.parse(readFileSync(resolve(REPOSITORY_ROOT, 'tests/fixtures/hypit-fix2/contract-samples.json'), 'utf8')) as Sample
const README = readFileSync(resolve(REPOSITORY_ROOT, 'tests/fixtures/hypit-fix2/README.md'), 'utf8')

// ── 目标规则验证器：返回「是否符合目标契约」。必须能同时判死旧样本、放行正例，
//    否则测试自身失败（防「拒绝一切」的假阳性验证器）。 ────────────────────────
const frontendRules: Record<string, { validate: (trace: Record<string, any>) => boolean; positive: Record<string, any> }> = {
  // FE-01 生成按钮必须产生 POST 构建请求。
  'FE-01': {
    validate: (t) => Array.isArray(t.observedRequests) && t.observedRequests.some((r: any) => r.method === 'POST' && /\/builds/.test(r.path)),
    positive: { observedRequests: [{ method: 'POST', path: '/api/hypit/projects/p1/builds' }] },
  },
  // FE-02 变体取消必须 POST cancel 端点。
  'FE-02': {
    validate: (t) => Array.isArray(t.observedRequests) && t.observedRequests.some((r: any) => r.method === 'POST' && /\/variants\/.+\/cancel$/.test(r.path)),
    positive: { observedRequests: [{ method: 'POST', path: '/api/hypit/projects/p1/variants/v1/cancel' }] },
  },
  // FE-03 outputs 信封必须同时有 items 与兼容别名 outputs 且内容一致。
  'FE-03': {
    validate: (t) => {
      const d = t.response ?? {}
      return Array.isArray(d.items) && Array.isArray(d.outputs) && d.items.length === d.outputs.length && typeof d.nextCursor !== 'undefined'
    },
    positive: { response: { items: [{ id: 'o1' }], outputs: [{ id: 'o1' }], nextCursor: null, build: {}, planSnapshot: {} } },
  },
  // FE-04 已存在文件的保存必须携带该文件当前 hash 作 baseHash。
  'FE-04': {
    validate: (t) => t.submittedChangeset?.changes?.[0]?.baseHash === t.fileGet?.hash,
    positive: { fileGet: { hash: 'actual-hash' }, submittedChangeset: { changes: [{ baseHash: 'actual-hash' }] } },
  },
  // FE-05 迟到响应不得改变当前工程（generation 隔离）：最终生效的必须是最新工程的写入。
  'FE-05': {
    validate: (t) => {
      const events: Array<{ projectId: string; generation: number }> = t.applyOrder ?? []
      if (events.length === 0) return false
      let currentGeneration = -1
      for (const e of events) {
        if (e.generation >= currentGeneration) currentGeneration = e.generation
        else return false // 旧 generation 的迟到应用 = 违规
      }
      return true
    },
    positive: { applyOrder: [{ projectId: 'A', generation: 1 }, { projectId: 'B', generation: 2 }] },
  },
  // FE-06 评论修改必须是真实源码编辑：有原文件 hash 作 baseHash 且内容不是单行注释替换。
  'FE-06': {
    validate: (t) => {
      const change = t.submittedChangeset?.changes?.[0]
      if (!change || change.baseHash !== t.originalFileHash) return false
      return typeof change.content === 'string' && change.content !== t.oneLineComment && change.content.length > (t.oneLineComment?.length ?? 0)
    },
    positive: { originalFileHash: 'h0', oneLineComment: '/* review: x */', submittedChangeset: { changes: [{ baseHash: 'h0', content: '<svml><!-- edited --></svml>' }] } },
  },
}
const backendRules: Record<string, { validate: (obs: Record<string, any>) => boolean; positive: Record<string, any> }> = {
  // BE-01 复用必须 revision/readOnly 全等且每张票独立核销。
  'BE-01': {
    validate: (o) => !(o.reused === true && (o.actualRevision !== o.requestedRevision || o.actualReadOnly !== o.requestedReadOnly)) && !o.ticketError,
    positive: { reused: true, requestedRevision: 2, actualRevision: 2, requestedReadOnly: true, actualReadOnly: true, ticketError: null },
  },
  // BE-02 二进制导入必须字节保真。
  'BE-02': {
    validate: (o) => o.binaryIdentical === true,
    positive: { binaryIdentical: true, beforeHex: '89504e47', afterHex: '89504e47' },
  },
  // BE-03 非法 Run 不得导入成功。
  'BE-03': {
    validate: (o) => o.importResult?.accepted !== true,
    positive: { importResult: { accepted: false, reason: 'run parse/check failed' } },
  },
  // BE-04 批次失败必须零部分写。
  'BE-04': {
    validate: (o) => !(o.error && o.afterCount !== o.beforeCount),
    positive: { error: 'batch rejected', beforeCount: 2, afterCount: 2 },
  },
  // BE-05 配置必须真实读取请求的 env 值。
  'BE-05': {
    validate: (o) => o.actualSocket === o.requestedSocket && o.actualSlot === o.requestedSlot,
    positive: { requestedSocket: '/sockets', actualSocket: '/sockets', requestedSlot: '/slots', actualSlot: '/slots' },
  },
}

function runFixtures(dest: string): void {
  execFileSync('node', [FIXTURES_SCRIPT, '--dest', dest, '--seed', SEED], { cwd: REPOSITORY_ROOT, stdio: 'pipe' })
}

describe('TC-F2-01-01 反例可重建（seed=10702，hash 可重现，六类旧行为均有失败断言，不调用 Provider）', () => {
  it('fixtures.mjs 两次运行产物 hash 逐字节一致且与实际文件相符', () => {
    const dirA = mkdtempSync(join(tmpdir(), 'fix2-c01-a-'))
    const dirB = mkdtempSync(join(tmpdir(), 'fix2-c01-b-'))
    runFixtures(dirA)
    runFixtures(dirB)
    const manifestA = JSON.parse(readFileSync(join(dirA, 'manifest.json'), 'utf8'))
    const manifestB = JSON.parse(readFileSync(join(dirB, 'manifest.json'), 'utf8'))
    expect(manifestA.seed).toBe(10702)
    expect(manifestA.providerCalls).toBe(0)
    expect(manifestA.networkAccess).toBe('none')
    // hash 清单两次一致（manifest 固定时间戳）。
    expect(manifestA.entries.map((e: any) => [e.path, e.sha256])).toEqual(manifestB.entries.map((e: any) => [e.path, e.sha256]))
    // 清单 hash 与实际字节逐一相符（防清单自说自话）。
    const kinds = new Map<string, string>()
    for (const entry of manifestA.entries) {
      if (entry.omitted) continue
      const bytes = readFileSync(join(dirA, entry.path))
      expect(createHash('sha256').update(bytes).digest('hex'), `${entry.path} hash 与字节不符`).toBe(entry.sha256)
      expect(entry.sha256).toMatch(/^[0-9a-f]{64}$/)
      kinds.set(entry.kind, entry.path)
    }
    // 覆盖类别：png/wav/mp4/zip/json（mp4 允许占位，但必须在 manifest 声明 encoder）。
    for (const kind of ['png', 'wav', 'zip', 'json']) expect(kinds.has(kind), `缺 ${kind} 产物`).toBe(true)
    const videoKind = kinds.has('mp4') ? 'mp4' : 'placeholder-video'
    expect(kinds.has(videoKind), '缺视频产物').toBe(true)
    if (videoKind === 'placeholder-video') expect(manifestA.videoEncoder).toContain('placeholder')
    else expect(manifestA.videoEncoder).toMatch(/ffmpeg/)
    // 账号元数据无口令。
    const accounts = JSON.parse(readFileSync(join(dirA, 'accounts/accounts.json'), 'utf8'))
    expect(accounts.accounts.map((a: any) => a.role).sort()).toEqual(['operator', 'owner-a', 'owner-b'])
    expect(JSON.stringify(accounts)).not.toMatch(/passw(or)?d"\s*:\s*"[^"]+/i)
  })

  it('六类前端旧行为全部被判失败，目标正例全部通过（验证器有正反双向判别力）', () => {
    const fe = samples.counterExamples.frontend
    expect(fe.map((c) => c.id)).toEqual(['FE-01', 'FE-02', 'FE-03', 'FE-04', 'FE-05', 'FE-06'])
    for (const item of fe) {
      const rule = frontendRules[item.id]
      expect(rule, `${item.id} 缺验证器`).toBeDefined()
      // 反例必须被判失败 —— 这就是「六类旧行为均有失败断言」。
      expect(rule.validate(item.oldTrace ?? {}), `${item.id} 旧行为未被目标规则判失败（反例不可发现）`).toBe(false)
      // 正例必须通过 —— 防止验证器「拒绝一切」的假阳性。
      expect(rule.validate(rule.positive), `${item.id} 验证器拒绝目标正例`).toBe(true)
      expect(item.targetRule, `${item.id} 缺 targetRule`).toBeTruthy()
      expect(item.finding).toMatch(/^F\d{2}$/)
    }
  })

  it('五类后端旧行为探针全部被判失败，目标正例全部通过', () => {
    const be = samples.counterExamples.backend
    expect(be.map((c) => c.id)).toEqual(['BE-01', 'BE-02', 'BE-03', 'BE-04', 'BE-05'])
    for (const item of be) {
      const rule = backendRules[item.id]
      expect(rule, `${item.id} 缺验证器`).toBeDefined()
      expect(rule.validate(item.observed ?? {}), `${item.id} 旧行为未被目标规则判失败`).toBe(false)
      expect(rule.validate(rule.positive), `${item.id} 验证器拒绝目标正例`).toBe(true)
    }
  })

  it('跨层契约样本带来源与状态标记，current-wrong 样本不被当作目标冻结', () => {
    expect(samples.provenance.statusVocabulary).toContain('current-wrong')
    expect(samples.provenance.statusVocabulary).toContain('target')
    const all = Object.values(samples.layers ?? {}).flat() as Array<{ id: string; status: string; source?: string; target?: unknown }>
    expect(all.length).toBeGreaterThanOrEqual(6)
    for (const s of all) {
      expect(['current-wrong', 'current-ok', 'target']).toContain(s.status)
      if (s.status === 'current-wrong') {
        expect(s.target, `${s.id} current-wrong 必须给出 target 修正方向`).toBeTruthy()
        expect(s.source, `${s.id} 必须有来源标记`).toBeTruthy()
      }
    }
  })
})

function runVerify(args: string[], env: Record<string, string> = {}, script: string = VERIFY_SCRIPT) {
  return spawnSync('bash', [script, ...args], {
    cwd: REPOSITORY_ROOT,
    encoding: 'utf8',
    env: { ...process.env, ...env },
  })
}

describe('TC-F2-01-02 缺阶段拒绝（--stage e2e 未交付）', () => {
  it('退出 2 / NOT_RUN，不打印 ALL-GREEN，results.json 不记 PASS', () => {
    const art = mkdtempSync(join(tmpdir(), 'fix2-c01-e2e-'))
    // C107F2-39 收口翻转 STAGE_E2E_IMPLEMENTED=1 后，「未交付」状态在临时副本上模拟
    // （flag 置 0），负例机制不变：缺阶段必须 exit 2 / NOT_RUN / 不记 PASS。副本同时
    // no-op 掉 local_stack_enter：本负例考察 stage 旗标门而非资源守卫，且真守卫会与
    // 并行执行的 c02（guard session --fresh）争 y1-hypit-fix2-e2e 锁（vitest 文件级
    // 并行，2026-10-01 实录互踩）。副本用毕即删。
    const undelivered = resolve(REPOSITORY_ROOT, 'scripts/acceptance/.verify-107-fix-2.c01-tmp.sh')
    writeFileSync(undelivered,
      readFileSync(resolve(REPOSITORY_ROOT, VERIFY_SCRIPT), 'utf8')
        .replace('STAGE_E2E_IMPLEMENTED=1', 'STAGE_E2E_IMPLEMENTED=0')
        .replace('source "$REPO_ROOT/scripts/lib/local-stack.sh"',
          'source "$REPO_ROOT/scripts/lib/local-stack.sh"; local_stack_enter() { :; }'))
    try {
      const r = runVerify(['--stage', 'e2e'], { FIX2_ART_BASE: art }, undelivered)
      expect(r.status, `期望 exit 2，实际 ${r.status}\nstdout:${r.stdout}\nstderr:${r.stderr}`).toBe(2)
      expect(r.stdout).toContain('NOT_RUN')
      expect(r.stdout + r.stderr).not.toContain('ALL-GREEN')
      const results = JSON.parse(readFileSync(join(art, 'e2e', 'results.json'), 'utf8'))
      expect(results.exitCode).toBe(2)
      expect(results.notRun.length).toBeGreaterThan(0)
    } finally {
      rmSync(undelivered, { force: true })
    }
  })
})

describe('TC-F2-01-03 零用例拒绝（过滤器指向不存在的用例）', () => {
  it('非零退出并指出零用例，results.json 不能记录 PASS', () => {
    const art = mkdtempSync(join(tmpdir(), 'fix2-c01-zero-'))
    const r = runVerify(['--stage', 'card', '--card', 'C107F2-01', '--test-filter', 'TC-F2-ZZ-99-NONEXISTENT'], { FIX2_ART_BASE: art })
    expect(r.status).toBe(3)
    expect(r.stdout).toContain('零用例')
    const results = JSON.parse(readFileSync(join(art, 'C107F2-01', 'results.json'), 'utf8'))
    expect(results.exitCode).toBe(3)
    expect(results.tests.executed).toBe(0)
    expect(JSON.stringify(results)).not.toMatch(/"PASS"/)
  })
})

describe('TC-F2-01-04 来源与映射（35 组审计 finding→卡→TC/V 引用全覆盖）', () => {
  const coverage = JSON.parse(readFileSync(resolve(REPOSITORY_ROOT, 'contracts/hypit-coverage.v1.json'), 'utf8'))

  it('coverage.fix2.findings 覆盖 F01…F35 且逐卡可解析', () => {
    const findings = coverage.fix2.findings
    expect(Object.keys(findings).sort()).toEqual(Array.from({ length: 35 }, (_, i) => `F${String(i + 1).padStart(2, '0')}`))
    for (const [finding, entry] of Object.entries(findings) as Array<[string, { cards: string[] }]>) {
      expect(entry.cards.length, `${finding} 无责任卡`).toBeGreaterThan(0)
      for (const card of entry.cards) expect(card).toMatch(/^C107F2-\d{2}$/)
    }
  })

  it('反例样本的 finding/ownerCard 与 coverage 映射一致（谁声明修复谁承接反例）', () => {
    const febe = [...samples.counterExamples.frontend, ...samples.counterExamples.backend]
    expect(febe.length).toBe(11)
    for (const item of febe as Array<{ id: string; finding: string; ownerCard: string }>) {
      const mapped = coverage.fix2.findings[item.finding]?.cards
      expect(mapped, `${item.id} finding ${item.finding} 未映射`).toBeDefined()
      expect(mapped, `${item.id} 责任卡 ${item.ownerCard} 不在 finding ${item.finding} 的映射内`).toContain(item.ownerCard)
    }
  })

  it('fixture 与实际边界在样本与 README 双处声明（不把审计观察当目标）', () => {
    expect(samples.fixtureBoundaries).toContain('不得把 observed 当目标冻结')
    expect(README).toContain('fixture 与实际的边界')
    expect(README).toContain('observed')
    for (const item of [...samples.counterExamples.frontend, ...samples.counterExamples.backend] as Array<{ id: string }>) {
      expect(README, `README 索引缺 ${item.id}`).toContain(item.id)
    }
    // 六类前端反例对应六种不同请求形态（不是同一形态复制六份）。
    const shapes = new Set((samples.counterExamples.frontend as any).map((c: any) => Object.keys(c.oldTrace ?? {}).sort().join(',')))
    expect(shapes.size).toBe(6)
  })

  it('验证入口脚本与 fixture 生成器真实在场可执行', () => {
    expect(existsSync(resolve(REPOSITORY_ROOT, VERIFY_SCRIPT))).toBe(true)
    expect(existsSync(resolve(REPOSITORY_ROOT, FIXTURES_SCRIPT))).toBe(true)
    expect(existsSync(resolve(REPOSITORY_ROOT, 'tests/fixtures/hypit-fix2/README.md'))).toBe(true)
    const help = runVerify(['--help'])
    expect(help.status).toBe(0)
    expect(help.stdout).toContain('--stage card')
    // 未知参数拒绝。
    expect(runVerify(['--stage', 'card', '--card', 'C107F2-01', '--nope']).status).toBe(2)
  })
})
