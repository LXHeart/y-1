import { execFileSync } from 'node:child_process'
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'

/**
 * C107F2-39（W224）：分层验收门禁契约——禁止漏跑和假全绿（F35）。
 *
 * 四组 TC 在「隔离证据副本」上运行真实 stage all 脚本（FIX2_ART_BASE 指向临时
 * 目录；FIX2_ALL_SKIP_CONTRACT=1 防递归——本文件就是契约层本身，跳过时 G7 由
 * 脚本如实记 NOT_RUN）。伪造的 results.json 只用于门禁判定输入，不代表真实
 * 验收通过；本测试断言的是门禁的「拒绝能力」，不是产品结果。
 */

const ROOT = resolve(import.meta.dirname, '../..')
const ALL_STAGE = resolve(ROOT, 'scripts/acceptance/stages/107-fix-2-all.sh')
const TMP = resolve(ROOT, 'test-artifacts/task-107/fix2/C39/gates-fixture')

function layerResults(card: string, tcFound: string[], opts: {
  exitCode?: number
  executed?: number
  failed?: number
  skipped?: number
} = {}): string {
  const executed = opts.executed ?? tcFound.length
  const failed = opts.failed ?? 0
  return JSON.stringify({
    taskBook: '107-fix-2 v1.0.0', stage: 'gate-fixture', card,
    exitCode: opts.exitCode ?? 0, commit: 'fixture', startedAt: '', finishedAt: '',
    node: process.version, java: null, imageDigest: null,
    tests: { total: executed, passed: executed - failed, failed, skipped: opts.skipped ?? 0, executed },
    tc: { found: tcFound, failed: failed > 0 ? tcFound.slice(0, failed) : [] },
    notRun: [], notes: ['gate-fixture：仅门禁判定输入，非真实验收产物'],
  }, null, 2)
}

const GOOD_LOCAL = ['TC-F2-08-01', 'TC-F2-08-02', 'TC-F2-08-03', 'TC-F2-08-04']
const GOOD_E2E = ['TC-F2-36-01', 'TC-F2-36-02', 'TC-F2-36-03', 'TC-F2-36-04',
  'TC-F2-37-01', 'TC-F2-37-02', 'TC-F2-37-03', 'TC-F2-37-04']
const GOOD_RECOVERY = ['TC-F2-38-01', 'TC-F2-38-02', 'TC-F2-38-03', 'TC-F2-38-04']

function writeBase(mutate?: (base: string) => void): void {
  rmSync(TMP, { recursive: true, force: true })
  for (const [layer, card, tcs] of [
    ['local', 'C107F2-08', GOOD_LOCAL],
    ['e2e', 'C107F2-37', GOOD_E2E],
    ['recovery', 'C107F2-38', GOOD_RECOVERY],
  ] as const) {
    mkdirSync(resolve(TMP, layer), { recursive: true })
    writeFileSync(resolve(TMP, layer, 'results.json'), layerResults(card, [...tcs]))
  }
  mutate?.(TMP)
}

interface AllRun { code: number; stdout: string }

function runAllStage(): AllRun {
  try {
    const stdout = execFileSync('bash', [ALL_STAGE], {
      cwd: ROOT,
      encoding: 'utf8',
      env: { ...process.env, FIX2_ART_BASE: TMP, FIX2_ALL_SKIP_CONTRACT: '1' },
      timeout: 120_000,
    })
    return { code: 0, stdout }
  } catch (error) {
    const e = error as { status?: number; stdout?: string }
    return { code: e.status ?? -1, stdout: e.stdout ?? '' }
  }
}

beforeAll(() => { mkdirSync(TMP, { recursive: true }) })
afterAll(() => { rmSync(TMP, { recursive: true, force: true }) })

describe('C107F2-39 分层验收门禁（TC-F2-39-01～04）', () => {
  it('TC-F2-39-01 删掉 e2e/recovery 证据 → 非零且逐项列缺项，不宣称全任务 VERIFIED', () => {
    writeBase((base) => { rmSync(resolve(base, 'e2e', 'results.json')) })
    const run = runAllStage()
    expect(run.code).not.toBe(0)
    expect(run.stdout).toContain('缺项')
    expect(run.stdout).toContain('e2e')
    // 失败路径绝无正向放行表述（免责声明「不宣称…」除外）。
    expect(run.stdout).not.toMatch(/PASS stage all/)
  })

  it('TC-F2-39-02 四种故障注入分别被门禁捕获（缺生成POST TC/坏outputs计数/恢复层零执行/缺数据库恢复证据）', () => {
    // ① 去掉生成 POST：e2e 证据缺 TC-F2-37-01（journey 生成链 TC 被过滤/未发现）。
    writeBase((base) => {
      writeFileSync(resolve(base, 'e2e', 'results.json'),
        layerResults('C107F2-37', GOOD_E2E.filter((tc) => tc !== 'TC-F2-37-01')))
    })
    let run = runAllStage()
    expect(run.code, '缺生成链TC必须非零').not.toBe(0)
    expect(run.stdout).toContain('TC-F2-37-01')

    // ② 破坏 outputs 字段：e2e 计数字段损坏（executed 缺失 + failed>0）。
    writeBase((base) => {
      const broken = JSON.parse(layerResults('C107F2-37', [...GOOD_E2E]))
      broken.tests = { total: 8, passed: 6, failed: 2, skipped: 0, executed: 0 }
      writeFileSync(resolve(base, 'e2e', 'results.json'), JSON.stringify(broken))
    })
    run = runAllStage()
    expect(run.code, 'executed=0 必须非零').not.toBe(0)
    expect(run.stdout).toContain('executed=0')

    // ③ 恢复 utf8 导入故障模拟：recovery 层 executed=0（用例全部被过滤）。
    writeBase((base) => {
      writeFileSync(resolve(base, 'recovery', 'results.json'),
        layerResults('C107F2-38', [...GOOD_RECOVERY], { executed: 0 }))
    })
    run = runAllStage()
    expect(run.code, 'recovery 零执行必须非零').not.toBe(0)
    expect(run.stdout).toContain('recovery')

    // ④ 跳数据库恢复：recovery 证据整个缺失（缺文件）。
    writeBase((base) => { rmSync(resolve(base, 'recovery', 'results.json')) })
    run = runAllStage()
    expect(run.code, '缺恢复证据必须非零').not.toBe(0)
    expect(run.stdout).toContain('recovery')
  })

  it('TC-F2-39-03 必需 spec 含 skip → 门禁失败，不能靠退出0放行', () => {
    writeBase((base) => {
      writeFileSync(resolve(base, 'e2e', 'results.json'),
        layerResults('C107F2-37', [...GOOD_E2E], { skipped: 1, executed: GOOD_E2E.length + 1 }))
    })
    const run = runAllStage()
    expect(run.code).not.toBe(0)
    expect(run.stdout).toContain('skipped')
  })

  it('TC-F2-39-04 本地全绿汇总：LOCAL_PASS/LIVE_NOT_RUN 表述，绝无全平台全量通过字样', () => {
    writeBase()
    const run = runAllStage()
    expect(run.code, `基线应通过：${run.stdout}`).toBe(0)
    const summary = JSON.parse(readFileSync(resolve(TMP, 'all', 'summary.json'), 'utf8')) as {
      verdict: string
      live: string
      gates: Record<string, string>
    }
    expect(summary.verdict).toContain('LOCAL_PASS')
    expect(summary.live).toContain('NOT_RUN')
    // G7 在防递归跳过下如实 NOT_RUN，不得伪绿。
    expect(summary.gates.G7).toBe('NOT_RUN')
    // verdict 是正向判定语，绝无越权表述（live 里的「不得表述为…」是免责声明，
    // 不在禁止之列）。
    expect(summary.verdict).not.toMatch(/全平台|全部通过|生产已上线|商用验证/)
    expect(summary.live).toContain('不得表述为全平台全量通过')
  })
})
