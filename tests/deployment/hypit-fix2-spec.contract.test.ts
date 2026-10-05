import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 107-fix-2 规格契约测试（W007；C107F2-01 建立，C107F2-40 收口）。
 *
 * 校验任务书结构与 coverage 契约登记一致：40 卡、160 组 TC、35 组 finding
 * 全覆盖且映射到存在的卡、W 表引用有效。本测试只做结构核验，不冒充业务
 * 验收（F35 教训：门禁能发现结构缺失，业务缺陷由各责任卡 TC 关闭）。
 *
 * C107F2-40 任务级集成判定（TC-F2-40-01～04）：本地验收机上对证据产物实读
 * 校验；CI/新克隆环境没有 test-artifacts（gitignore 契约）时退化为清单与
 * 一致性校验——不虚构重开、不以结构通过冒充集成判定。
 */

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')
const TASK_BOOK_PATH = 'docs/任务书/草场任务书-107-fix-2-Hypit全链路缺陷修复与真实交付验收.md'
const CARDS = Array.from({ length: 40 }, (_, i) => `C107F2-${String(i + 1).padStart(2, '0')}`)
const FINDINGS = Array.from({ length: 35 }, (_, i) => `F${String(i + 1).padStart(2, '0')}`)
const STATUSES = ['NOT_STARTED', 'IN_PROGRESS', 'IMPLEMENTED', 'VERIFIED', 'BLOCKED']

function read(path: string): string {
  return readFileSync(resolve(REPOSITORY_ROOT, path), 'utf8')
}

const book = read(TASK_BOOK_PATH)
const coverage = JSON.parse(read('contracts/hypit-coverage.v1.json')) as {
  version: string
  cards: Record<string, { status: string; tc?: Record<string, string>; evidence?: string }>
  fix2?: {
    taskBookVersion: string
    findings: Record<string, { cards: string[] }>
    reopen?: {
      taskBook: string
      artifacts: Array<{ card: string; tc: string; path: string; kind: string; sha256: string; sizeBytes: number }>
      invariants: Record<string, boolean>
    }
  }
}

/** 任务书 §10 总表逐卡状态（最后一列状态单元格）。 */
function bookStatuses(bookText: string): Map<string, string> {
  const table = bookText.split('## 10. 开发计划与任务总表')[1].split('### 10.1')[0]
  const statuses = new Map<string, string>()
  for (const card of CARDS) {
    const row = table.split('\n').find((line) => line.includes(`| ${card} /`))
    const cells = (row ?? '').split('|')
    const statusCell = (cells[cells.length - 2] ?? '').trim()
    statuses.set(card, statusCell.split('（')[0].trim())
  }
  return statuses
}

/** 任务级判定：任一卡非 VERIFIED → 拒绝并逐一点名责任卡（AC-F2-40-03）。 */
function judgeTaskVerdict(statuses: Map<string, string>): { ok: boolean; blocking: string[] } {
  const blocking = [...statuses.entries()].filter(([, status]) => status !== 'VERIFIED').map(([card]) => card)
  return { ok: blocking.length === 0, blocking }
}

describe('107-fix-2 任务书结构（规格契约）', () => {
  it('40 张任务卡章节齐全且编号连续', () => {
    for (const card of CARDS) {
      expect(book, `缺卡章节 ${card}`).toContain(`### 卡 ${card}：`)
    }
    // 卡号不重复（每章恰好出现一次标题行）。
    const headings = book.match(/^### 卡 C107F2-\d{2}：/gm) ?? []
    expect(headings.length).toBe(40)
  })

  it('160 组 TC/AC 用例在 §11 卡与 §12.2 详情两处齐全', () => {
    for (const card of CARDS) {
      const num = card.slice('C107F2-'.length)
      for (const seq of ['01', '02', '03', '04']) {
        expect(book, `${card} 缺 AC-F2-${num}-${seq}`).toContain(`AC-F2-${num}-${seq}`)
        expect(book, `${card} 缺 TC-F2-${num}-${seq}`).toContain(`TC-F2-${num}-${seq}`)
      }
      // §12.2 每组有独立详情标题。
      expect(book, `${card} 缺 §12.2 用例详情`).toContain(`#### TC-F2-${num}-01：`)
    }
    const detailHeadings = book.match(/^#### TC-F2-\d{2}-01：/gm) ?? []
    expect(detailHeadings.length).toBe(40)
  })

  it('§10 任务总表 40 行、依赖交付卡编号均小于本卡（无环）', () => {
    const table = book.split('## 10. 开发计划与任务总表')[1].split('### 10.1')[0]
    for (const card of CARDS) {
      const row = table.split('\n').find((line) => line.includes(`| ${card} /`))
      expect(row, `§10 缺行 ${card}`).toBeDefined()
      const num = Number(card.slice('C107F2-'.length))
      // 依赖只看「依赖交付卡」列（第 4 数据列），行首的本卡编号不算依赖。
      const depColumn = row!.split('|')[4] ?? ''
      const deps = [...depColumn.matchAll(/C107F2-(\d{2})/g)]
      for (const dep of deps) {
        expect(Number(dep[1]), `${card} 依赖编号必须小于本卡`).toBeLessThan(num)
      }
    }
  })

  it('§9.1 W 表 W001…W230 齐全，卡引用的 W 都在表内', () => {
    const wSection = book.split('### 9.1 精确白名单')[1].split('### 9.2')[0]
    const wIds = new Set([...wSection.matchAll(/^\| (W\d{3}) \|/gm)].map((m) => m[1]))
    for (let i = 1; i <= 230; i += 1) {
      expect(wIds.has(`W${String(i).padStart(3, '0')}`), `W 表缺 W${String(i).padStart(3, '0')}`).toBe(true)
    }
    // 每个登记 W 的「写入责任卡」列只允许本书卡号。
    for (const m of wSection.matchAll(/^\| (W\d{3}) \| [^|]+ \| [^|]+ \| [^|]+ \| ([^|]+) \|$/gm)) {
      for (const card of m[2].matchAll(/C107F2-\d{2}/g)) {
        expect(CARDS, `W${m[1]} 责任卡越界 ${card[0]}`).toContain(card[0])
      }
    }
  })

  it('§12.3 唯一验证命令表 V-01…V-15 齐全', () => {
    const table = book.split('### 12.3 唯一验证命令表')[1].split('### 12.4')[0]
    for (let i = 1; i <= 15; i += 1) {
      expect(table, `缺 V-${String(i).padStart(2, '0')}`).toContain(`| V-${String(i).padStart(2, '0')} |`)
    }
  })
})

describe('coverage 契约 fix2 登记（TC-F2-01-04 映射面）', () => {
  it('40 卡全部登记且状态词义合法', () => {
    expect(coverage.fix2?.taskBookVersion).toBe('1.0.0')
    for (const card of CARDS) {
      const entry = coverage.cards[card]
      expect(entry, `${card} 未登记 coverage`).toBeDefined()
      expect(STATUSES, `${card} 状态非法`).toContain(entry.status)
    }
  })

  it('F01…F35 全覆盖，映射与任务书 §2.6 责任卡一致', () => {
    const findings = coverage.fix2?.findings
    expect(findings).toBeDefined()
    expect(Object.keys(findings!).sort()).toEqual(FINDINGS)
    // 任务书 §2.6 每行责任卡集合必须与 coverage 完全一致。
    const section26 = book.split('### 2.6 缺陷事实')[1].split('### 2.7')[0]
    const rows = new Map<string, string[]>()
    for (const m of section26.matchAll(/^\| (F\d{2}) \| .*? \| (.*?) \|$/gm)) {
      rows.set(m[1], [...m[2].matchAll(/C107F2-\d{2}/g)].map((x) => x[0]))
    }
    expect([...rows.keys()].sort()).toEqual(FINDINGS)
    for (const finding of FINDINGS) {
      const mapped = findings![finding].cards
      expect(mapped.length, `${finding} 无责任卡`).toBeGreaterThan(0)
      for (const card of mapped) expect(CARDS, `${finding} 映射越界 ${card}`).toContain(card)
      expect([...mapped].sort(), `${finding} 映射与 §2.6 不一致`).toEqual([...rows.get(finding)!].sort())
    }
  })

  it('VERIFIED 状态的卡必须有 tc 判据登记（防无证据完成声明）', () => {
    for (const card of CARDS) {
      const entry = coverage.cards[card]
      if (entry.status === 'VERIFIED') {
        expect(Object.keys(entry.tc ?? {}).length, `${card} VERIFIED 但无 tc 判据`).toBeGreaterThan(0)
        for (const [tc, verdict] of Object.entries(entry.tc ?? {})) {
          expect(new RegExp(`^TC-F2-${card.slice('C107F2-'.length)}-\\d{2}$`).test(tc), `${card} tc 编号 ${tc} 越界`).toBe(true)
          expect(verdict.length, `${card}/${tc} 判据为空`).toBeGreaterThan(0)
        }
      }
    }
  })
})

describe('C107F2-40 任务级集成判定（TC-F2-40-01～04）', () => {
  const EVIDENCE_ROOT = resolve(REPOSITORY_ROOT, 'test-artifacts/task-107/fix2')
  const evidencePresent = existsSync(resolve(EVIDENCE_ROOT, 'C107F2-39', 'results.json'))

  it('TC-F2-40-01 逐 finding 逆向核对：F01–F35 责任卡全 VERIFIED、无悬空 TC、证据实读零失败', () => {
    for (const finding of FINDINGS) {
      const cards = coverage.fix2!.findings[finding].cards
      expect(cards.length, `${finding} 无责任卡`).toBeGreaterThan(0)
      for (const card of cards) {
        const entry = coverage.cards[card]
        expect(entry.status, `${finding}→${card} 未闭合`).toBe('VERIFIED')
        expect(Object.keys(entry.tc ?? {}).length, `${finding}→${card} VERIFIED 无 tc 判据`).toBeGreaterThan(0)
      }
    }
    // 悬空 TC：coverage 登记的所有编号必须属于本书 40 卡的合法区间。
    for (const [card, entry] of Object.entries(coverage.cards)) {
      if (!card.startsWith('C107F2-')) continue
      for (const tc of Object.keys(entry.tc ?? {})) {
        expect(tc, `${card} 悬空/畸形 TC ${tc}`).toMatch(/^TC-F2-(0[1-9]|[1-3][0-9]|40)-\d{2}$/)
      }
    }
    // 本地验收机：逐卡证据产物实读（exit0/零失败/零 skip/非零执行）。
    // CI/新克隆无 test-artifacts——该环境不得宣称本地集成判定（TC-02 指纹清单
    // 与 docs 声明一致性单独把关），此处不冒充。
    if (!evidencePresent) return
    for (const card of CARDS.filter((c) => c !== 'C107F2-40')) {
      const path = resolve(EVIDENCE_ROOT, card, 'results.json')
      expect(existsSync(path), `${card} 缺 results.json`).toBe(true)
      const r = JSON.parse(readFileSync(path, 'utf8')) as {
        exitCode: number; taskBook: string
        tests?: { failed?: number; skipped?: number; executed?: number }
      }
      expect(r.taskBook, `${card} 证据版本漂移`).toBe('107-fix-2 v1.0.0')
      expect(r.exitCode, `${card} exitCode`).toBe(0)
      expect(r.tests?.failed ?? 1, `${card} failed`).toBe(0)
      expect(r.tests?.skipped ?? 1, `${card} skipped`).toBe(0)
      expect((r.tests?.executed ?? 0) > 0, `${card} 零执行不能判绿`).toBe(true)
    }
  })

  it('TC-F2-40-02 产物重开：样片/工程包/Studio 写回/恢复栈可消费且来源/版本一致', () => {
    const reopen = coverage.fix2?.reopen
    expect(reopen?.taskBook).toBe('107-fix-2 v1.0.0')
    const artifacts = reopen?.artifacts ?? []
    // 重开面至少覆盖：成片 MP4、工程包 zip、Studio 语义写回双文件、恢复栈双证据。
    expect(new Set(artifacts.map((a) => a.kind)).size, '重开面不得缩窄').toBeGreaterThanOrEqual(4)
    expect(new Set(artifacts.map((a) => a.card)).size).toBeGreaterThanOrEqual(2)
    for (const artifact of artifacts) {
      expect(artifact.sha256).toMatch(/^[0-9a-f]{64}$/)
      expect(artifact.sizeBytes).toBeGreaterThan(0)
      expect(artifact.path.startsWith('test-artifacts/task-107/fix2/')).toBe(true)
      const absolute = resolve(REPOSITORY_ROOT, artifact.path)
      if (existsSync(absolute)) {
        // 真实重开（本地验收机）：字节级一致 + 魔数可消费。
        const bytes = readFileSync(absolute)
        expect(bytes.length, `${artifact.path} 尺寸漂移`).toBe(artifact.sizeBytes)
        expect(createHash('sha256').update(bytes).digest('hex'), `${artifact.path} 哈希漂移`).toBe(artifact.sha256)
        if (artifact.kind === 'mp4') {
          expect(bytes.subarray(4, 8).toString('latin1'), `${artifact.path} 非 MP4（ftyp 魔数）`).toBe('ftyp')
        }
        if (artifact.kind === 'zip') {
          expect(bytes[0]).toBe(0x50)
          expect(bytes[1]).toBe(0x4b)
        }
        if (artifact.kind === 'svml' || artifact.kind === 'svs') {
          expect(bytes.toString('utf8').startsWith('<?sv'), `${artifact.path} 非 SVML 家族`).toBe(true)
        }
      }
      // 无产物环境只验清单形状（重开事实由本地指纹比对承担）。
    }
    if (reopen?.invariants) {
      for (const [name, value] of Object.entries(reopen.invariants)) {
        expect(value, `恢复不变量 ${name} 不得为 false`).toBe(true)
      }
    }
    if (!evidencePresent) return
    // 版本一致：产物所属卡的 results.json 均为本书版本（来源锚定）。
    for (const card of new Set(artifacts.map((a) => a.card))) {
      const r = JSON.parse(readFileSync(resolve(EVIDENCE_ROOT, card, 'results.json'), 'utf8')) as { taskBook: string }
      expect(r.taskBook).toBe('107-fix-2 v1.0.0')
    }
    // 恢复链不变量实读：备份基线与恢复栈重开的 mp4 sha256 相等、revisionHashes 一致。
    const baseline = readFileSync(resolve(EVIDENCE_ROOT, 'C38/TC-F2-38-03/baseline.txt'), 'utf8')
    const restored = readFileSync(resolve(EVIDENCE_ROOT, 'C38/TC-F2-38-03/restored.txt'), 'utf8')
    const baselineSha = baseline.match(/mp4 sha256=([0-9a-f]{64})/)?.[1]
    const restoredSha = restored.match(/mp4 sha256=([0-9a-f]{64})/)?.[1]
    expect(baselineSha).toBeTruthy()
    expect(restoredSha, '恢复样片哈希与备份基线不一致').toBe(baselineSha)
    expect(restored).toContain('revisionHashes=一致')
  })

  it('TC-F2-40-03 故意保留必需 FAIL/NOT_RUN → 判定拒绝 VERIFIED 并点名责任卡；文档声明与书表一致', () => {
    const statuses = bookStatuses(book)
    const judge = judgeTaskVerdict
    // 负向矩阵：注入一个 NOT_STARTED（模拟必需层失败/未跑），判定必须拒绝。
    const mutated = new Map(statuses)
    mutated.set('C107F2-37', 'NOT_STARTED')
    const verdict = judge(mutated)
    expect(verdict.ok).toBe(false)
    expect(verdict.blocking, '必须点名责任卡').toContain('C107F2-37')
    // 真实态判定：C107F2-40 收口时书表应已全 40 卡 VERIFIED；若仍有未闭合卡，
    // 文档不得出现本书完成声明（双向一致，防「书未完、文先庆」与「书完、文漏报」）。
    const realVerdict = judge(statuses)
    const readmeIndex = read('docs/任务书/README.md').split('\n')
    const row107f2 = readmeIndex.find((line) => line.includes('[#107-fix-2]')) ?? ''
    const readmeClaim = (row107f2.split('|').slice(-2)[0] ?? '').trim().startsWith('VERIFIED')
    const docsClaim = (text: string): boolean =>
      text.split('\n').some((line) => line.includes('107-fix-2') && /VERIFIED|完成交付|收口/.test(line)
        && !line.includes('NOT_STARTED'))
    const yamlClaim = docsClaim(read('docs/status.yaml'))
    const guideClaim = docsClaim(read('docs/草场开发进度与续接指南.md'))
    // 第四处：任务书头部实施状态行（收口时曾残留 IN_PROGRESS 旧文案——前三处检查不覆盖书自身头部，1.0.6 补）。
    const headerStatusLine = book.split('\n').find((line) => line.startsWith('>') && line.includes('实施状态：')) ?? ''
    const headerClaim = /实施状态：VERIFIED/.test(headerStatusLine)
    const claimCount = [readmeClaim, yamlClaim, guideClaim, headerClaim].filter(Boolean).length
    // 四处声明必须同进退：要么都声明（书全绿），要么都不（尚有未闭合）。
    expect(claimCount === 0 || claimCount === 4, `文档完成声明不同步（README=${readmeClaim} status=${yamlClaim} guide=${guideClaim} header=${headerClaim}）`).toBe(true)
    expect(claimCount === 4, `声明=${claimCount} 与书表判定 ok=${realVerdict.ok} 不一致（未闭合：${realVerdict.blocking.join(',')}）`).toBe(realVerdict.ok)
  })

  it('TC-F2-40-04 上游冻结、他人改动保留、复现不依赖本机忽略产物', () => {
    // 上游冻结：真实 vendor 完整性检查（V-01 同一入口，只读；篡改即非零抛出）。
    execFileSync('bash', ['scripts/acceptance/verify-107-upstream.sh'], { cwd: REPOSITORY_ROOT, encoding: 'utf8' })
    // 补丁登记与补丁文件互证（重建命令的输入面完整）。
    const patches = JSON.parse(read('platform-hypit/patches/manifest.json')) as { patches: Array<{ file: string }> }
    for (const patch of patches.patches) {
      expect(existsSync(resolve(REPOSITORY_ROOT, 'platform-hypit/patches', patch.file)), `补丁缺文件 ${patch.file}`).toBe(true)
    }
    // 他人改动保留：#108（数字人会话收尾）已由 105-fix-2 任务书重编号接替
    // （107-fix-3 D-07 安全增量：断言意图不变——他人工作及索引存在；引用同步为
    // 现存 105-fix-2 任务书，不复活已删除的 108 文件）。
    const doc105fix2 = read('docs/任务书/草场任务书-105-fix-2-数字人会话收尾排队晋升与Offer状态闸.md')
    expect(doc105fix2.length, '105-fix-2 任务书被破坏').toBeGreaterThan(2000)
    const index = read('docs/任务书/README.md')
    expect(index).toContain('草场任务书-105-fix-2-数字人会话收尾排队晋升与Offer状态闸.md')
    expect(index).toContain('草场任务书-107-fix-2-Hypit全链路缺陷修复与真实交付验收.md')
    // 复现不依赖本机忽略产物：重开清单路径全部被 gitignore 覆盖且未被 git 跟踪
    // （证据可再生的来源是命令表 V-01～V-11，不是入库产物）。
    for (const artifact of coverage.fix2?.reopen?.artifacts ?? []) {
      let ignored: boolean
      try {
        execFileSync('git', ['-C', REPOSITORY_ROOT, 'check-ignore', '-q', artifact.path], { stdio: 'ignore' })
        ignored = true
      } catch { ignored = false }
      expect(ignored, `${artifact.path} 应被 gitignore 覆盖`).toBe(true)
      let tracked: boolean
      try {
        execFileSync('git', ['-C', REPOSITORY_ROOT, 'ls-files', '--error-unmatch', artifact.path], { stdio: 'ignore' })
        tracked = true
      } catch { tracked = false }
      expect(tracked, `${artifact.path} 不得被 git 跟踪`).toBe(false)
    }
  })
})
