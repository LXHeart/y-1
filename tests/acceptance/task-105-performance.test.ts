import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  generateFakeRaw, percentile, reduce,
  type CostRow, type RawSamples,
} from '../../scripts/acceptance/task-105-performance'

/**
 * 数字人性能归约契约（任务书 #105H C105H-03 / TC105H-03-01、TC105H-03-03、TC105H-03-04）。
 *
 * 自动可证部分：nearest-rank 分位数数学、失败连接不剔除分母、缺值 null 不填 0、
 * 成本对账（unknown 保留/经济键查重/四项费用齐备）、real 门禁缺样不可 PASS。
 * 实机与真实 provider 测量属 H03 条件门禁（本文件不冒充，fake 设备矩阵恒 NOT_RUN）。
 */

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')
const FIXTURE = 'tests/fixtures/digital-human/performance-cases.json'

interface FixtureSpec {
  seed: number
  trials: number
  coldWarm: { cold: number; warm: number }
  failures: { connect: number }
  syncPointsPerTrial: number
  fps: { nominal: number; samplesPerMinute: number; minutes: number }
  devices: Array<{ device: string; os: string; browser: string; cases: string[] }>
  costCases: Array<{ stage: CostRow['stage']; kind: CostRow['kind']; chargeTo: string }>
  audioSecondsRange: [number, number]
}

function fixture(): FixtureSpec {
  return JSON.parse(readFileSync(resolve(REPOSITORY_ROOT, FIXTURE), 'utf8'))
}

/** 构造一份「完整实测形状」的 raw（real）：100 连接（含失败）+100 同步点+四项费用+实机全过。 */
function completeRealRaw(): RawSamples {
  const connections = []
  for (let trial = 1; trial <= 100; trial += 1) {
    if (trial === 7) {
      connections.push({ trial, ok: false, errorStage: 'connect', connectMs: 9500 })
      continue
    }
    connections.push({
      trial, ok: true, cold: trial <= 20,
      connectMs: 1000 + trial * 8,
      firstAudioMs: 2500 + trial * 5,
      textFirstAudioMs: 2000 + trial * 5,
      warmReadyMs: trial <= 20 ? undefined : 6000 + trial * 4,
      interruptLocalMs: 100 + trial,
      interruptServerMs: 500 + trial * 3,
    })
  }
  const syncPoints = Array.from({ length: 120 }, (_, index) => ({ ms: 100 + index }))
  const fps = Array.from({ length: 5 }, (_, index) => ({ trial: 1, minute: index + 1, averageFps: 22 + index * 0.4 }))
  const devices = [
    { device: 'iPhone（Safari）', os: 'iOS', browser: 'safari', cases: [
      { name: 'mic-deny-to-text-to-grant', status: 'PASS' as const, evidence: 'real-device-evidence-a' },
      { name: 'autoplay-unlock', status: 'PASS' as const, evidence: 'real-device-evidence-a' }] },
    { device: 'Android（Chrome）', os: 'Android', browser: 'chromium', cases: [
      { name: 'mic-deny-to-text-to-grant', status: 'PASS' as const, evidence: 'real-device-evidence-b' }] },
  ]
  const cost: CostRow[] = [
    { stage: 'llm', kind: 'normal', economicKey: 'k-llm-1', cents: 120, accountingMatched: true },
    { stage: 'stt', kind: 'normal', economicKey: 'k-stt-1', cents: 40, accountingMatched: true },
    { stage: 'tts', kind: 'normal', economicKey: 'k-tts-1', cents: 65, accountingMatched: true },
    { stage: 'render', kind: 'normal', economicKey: 'k-render-1', cents: 210, accountingMatched: true },
    { stage: 'tts', kind: 'interrupted', economicKey: 'k-tts-2', cents: 20, accountingMatched: true },
    { stage: 'render', kind: 'unknown', economicKey: 'k-render-2', cents: null, accountingMatched: true },
  ]
  return { mode: 'real', generatedAt: '2026-09-24T00:00:00Z', connections, syncPoints, fps, devices, cost,
    capacity: { maxSessionsGlobal: 1, measuredConcurrent: 1 } }
}

describe('task-105 performance metric math (task #105H C105H-03)', () => {
  it('tc105h_03_01 nearest-rank 分位数：P95=sorted[ceil(0.95n)-1]，缺值 null 不填 0', () => {
    // 参数化：构造已知序列逐项验算。
    expect(percentile([], 0.95)).toBeNull()
    expect(percentile([42], 0.95)).toBe(42)
    expect(percentile([10], 0.5)).toBe(10)
    const values = [5, 1, 9, 3, 7, 2, 8, 4, 6, 10]
    // n=10 → ceil(0.95*10)=10 → sorted[9]=10；P50 → ceil(5)=5 → sorted[4]=5。
    expect(percentile(values, 0.95)).toBe(10)
    expect(percentile(values, 0.5)).toBe(5)
    const odd = [3, 1, 2]
    // n=3 → ceil(2.85)=3 → sorted[2]=3。
    expect(percentile(odd, 0.95)).toBe(3)
    expect(percentile(odd, 0.5)).toBe(2)
  })

  it('tc105h_03_01 完整 real 采样：失败连接计入分母、分位数可复算、样本量非零', () => {
    const raw = completeRealRaw()
    const report = reduce(raw)

    expect(report.counts.connections).toBe(100)
    expect(report.counts.ok).toBe(99)
    expect(report.counts.failed).toBe(1)
    // 99/100：失败留在分母——不是 99/99=100%。
    expect(report.counts.successRate).toBeCloseTo(0.99, 10)

    // connect 分位数把失败连接的 connectMs 也纳入（它发生了，不能剔除）。
    const connectValues = raw.connections.map((trial) => trial.connectMs).filter((ms): ms is number => ms != null)
    expect(connectValues).toHaveLength(100)
    expect(report.latencyMs.connect.p95).toBe(percentile(connectValues, 0.95))

    // firstAudio 只对 ok 连接定义；样本量=99。
    expect(report.latencyMs.firstAudio.samples).toBe(99)
    expect(report.latencyMs.firstAudio.p95).toBeGreaterThan(0)
    // 冷启动 20 轮无 warmReady 样本 → warmReady samples=80、p 值来自 80 样本。
    expect(report.latencyMs.warmReady.samples).toBe(80)

    // fps 5 分钟窗口均值可复算。
    expect(report.fps.windows).toBe(5)
    expect(report.fps.renderAverageFps5Min).toBeCloseTo((22 + 22.4 + 22.8 + 23.2 + 23.6) / 5, 5)

    // 完整实测形状：real 门禁零 problems（这是唯一允许 problems=0 的形状）。
    expect(report.problems).toEqual([])
  })

  it('tc105h_03_01 缺样本与不足样本：null/低样本计数并记 problems，绝不 PASS 为完整', () => {
    const raw = completeRealRaw()
    raw.connections = raw.connections.slice(0, 60)
    raw.syncPoints = raw.syncPoints.slice(0, 40)
    delete raw.cost
    const report = reduce(raw)
    expect(report.counts.connections).toBe(60)
    expect(report.problems.join('\n')).toContain('< 100')
    expect(report.problems.join('\n')).toContain('同步点')
    for (const stage of ['llm', 'stt', 'tts', 'render']) {
      expect(report.problems.join('\n')).toContain(stage)
    }
    // 全空输入：分母 0 → successRate 0、p 值 null（不是 0）。
    const empty = reduce({ mode: 'real', generatedAt: '', connections: [], syncPoints: [], fps: [] })
    expect(empty.counts.successRate).toBe(0)
    expect(empty.latencyMs.connect.p50).toBeNull()
    expect(empty.latencyMs.connect.p95).toBeNull()
    expect(empty.fps.renderAverageFps5Min).toBeNull()
    expect(empty.problems.length).toBeGreaterThan(0)
  })

  it('tc105h_03_03 实机恢复：fake 模式设备矩阵恒 NOT_RUN，缺实测设备不可 REAL 完整', () => {
    const spec = fixture()
    const raw = generateFakeRaw(spec)
    const report = reduce(raw)

    expect(report.mode).toBe('fake')
    expect(report.synthetic).toBe(true)
    // fake 设备矩阵全部 NOT_RUN：Fake 证明协议机械，不证明设备行为（K12）。
    for (const device of report.devices) {
      expect(device.passed).toBe(0)
      expect(device.notRun).toBe(spec.devices[0].cases.length)
    }
    // fake 模式不触发 real 门禁 problems（它本来就不是 real），但也不产出实机结论。
    expect(report.devices.length).toBe(spec.devices.length)

    // real 模式但设备任一用例 NOT_RUN → problems 点名设备。
    const partialDevice = completeRealRaw()
    partialDevice.devices![0].cases[0].status = 'NOT_RUN'
    const partial = reduce(partialDevice)
    expect(partial.problems.join('\n')).toContain('实机用例未全过')
    expect(partial.problems.join('\n')).toContain('iPhone')
  })

  it('tc105h_03_04 成本对账：unknown 保留 null、重复经济键/账务不匹配被点名、四项分档求和', () => {
    const raw = completeRealRaw()
    const report = reduce(raw)
    // unknown 保留（不折算 0）且被计数；interrupted 单列。
    expect(report.cost.unknownCount).toBe(1)
    expect(report.cost.interruptedCount).toBe(1)
    // perStageCents 求和只含数值行：tts=65+20=85；render=210（unknown null 不进总和也不清零已有值）。
    expect(report.cost.perStageCents.tts).toBe(85)
    expect(report.cost.perStageCents.render).toBe(210)
    expect(report.cost.perStageCents.llm).toBe(120)
    expect(report.cost.perStageCents.stt).toBe(40)
    expect(report.problems).toEqual([])

    // 参数化负例：unknown 带 cents、normal 缺 cents、重复经济键、账务不匹配。
    const bad = completeRealRaw()
    bad.cost = [
      ...bad.cost!,
      { stage: 'render', kind: 'unknown', economicKey: 'k-render-2', cents: 999, accountingMatched: true },
      { stage: 'llm', kind: 'normal', economicKey: 'k-llm-bad', cents: null, accountingMatched: true },
      { stage: 'stt', kind: 'normal', economicKey: 'k-stt-1', cents: 5, accountingMatched: false },
    ]
    const badReport = reduce(bad)
    const problems = badReport.problems.join('\n')
    expect(problems).toContain('k-render-2')
    expect(problems).toContain('unknown 必须 null')
    expect(problems).toContain('k-llm-bad')
    expect(problems).toContain('缺失不得填 0')
    expect(problems).toContain('重复经济键')
    expect(problems).toContain('账务不匹配')
  })

  it('CLI：fake 生成与归约 exit 0；real 试图消费 fake 样本被拒绝（exit 1）', () => {
    const work = mkdtempSync(resolve(tmpdir(), 'dh-perf-'))
    try {
      const script = resolve(REPOSITORY_ROOT, 'scripts/acceptance/task-105-performance.ts')
      const samples = resolve(work, 'raw.json')
      const metrics = resolve(work, 'metrics.json')

      const fakeRun = execFileSync('npx', ['--no-install', 'tsx', script,
        '--mode', 'fake', '--fixture', resolve(REPOSITORY_ROOT, FIXTURE),
        '--samples', samples, '--output', metrics], { encoding: 'utf8', cwd: REPOSITORY_ROOT })
      expect(fakeRun).toContain('mode=fake')
      expect(fakeRun).toContain('problems=0')
      const rawFile = JSON.parse(readFileSync(samples, 'utf8')) as RawSamples
      expect(rawFile.connections).toHaveLength(100)
      expect(rawFile.syncPoints.length).toBeGreaterThanOrEqual(100)
      const metricsFile = JSON.parse(readFileSync(metrics, 'utf8'))
      expect(metricsFile.counts.failed).toBe(1)

      // real 模式消费 fake raw：拒绝（mode 不符），非零退出。
      let rejected = false
      try {
        execFileSync('npx', ['--no-install', 'tsx', script,
          '--mode', 'real', '--samples', samples, '--output', metrics],
          { encoding: 'utf8', cwd: REPOSITORY_ROOT, stdio: 'pipe' })
      } catch (error) {
        rejected = true
        expect((error as { status: number }).status).toBe(1)
      }
      expect(rejected, 'real 模式不得接受 fake 样本').toBe(true)
    } finally {
      rmSync(work, { recursive: true, force: true })
    }
  })
})

describe('fixture contract', () => {
  it('performance-cases.json：100 试验、中文短句、不真人录音、设备矩阵与成本样本齐备', () => {
    const spec = fixture()
    expect(spec.trials).toBe(100)
    expect(spec.coldWarm.cold + spec.coldWarm.warm).toBe(100)
    expect(spec.failures.connect).toBeGreaterThanOrEqual(1)
    expect(spec.devices).toHaveLength(2)
    for (const device of spec.devices) {
      expect(device.cases).toContain('mic-deny-to-text-to-grant')
      expect(device.cases).toContain('lockscreen-and-resume')
    }
    const stages = new Set(spec.costCases.map((row) => row.stage))
    expect([...stages].sort()).toEqual(['llm', 'render', 'stt', 'tts'])
    expect(spec.costCases.some((row) => row.kind === 'unknown')).toBe(true)
    expect(spec.audioSecondsRange[1]).toBeLessThanOrEqual(10)
  })
})
