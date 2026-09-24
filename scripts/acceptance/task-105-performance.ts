/**
 * 数字人性能测量归约 CLI（任务书 #105H C105H-03 / V105H-03-03）。
 *
 * 用法：
 *   npx tsx scripts/acceptance/task-105-performance.ts --mode fake \
 *     --fixture tests/fixtures/digital-human/performance-cases.json \
 *     --samples test-artifacts/task-105/H03/raw.json --output test-artifacts/task-105/H03/metrics.json
 *   npx tsx scripts/acceptance/task-105-performance.ts --mode real \
 *     --samples test-artifacts/task-105/H03/raw.json --output test-artifacts/task-105/H03/metrics.json
 *
 * 契约（K12 / 任务书 §11 C105H-03）：
 * - percentile 采用 nearest-rank：P95 = 排序后 sorted[ceil(0.95*n)-1]；
 * - 成功率分母含失败连接——不得剔除失败后报告成功率；
 * - 缺失样本输出 null，不得填 0；real 模式缺必备样本（<100 连接/<100 同步点/设备无实测）
 *   记 problems 并非零退出——不把 Fake 或部分测量当 REAL_QUALIFIED。
 */

import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

export interface ConnectionTrial {
  trial: number
  ok: boolean
  /** 失败阶段：connect|first_audio|mid_stream（失败连接仍留在分母）。 */
  errorStage?: string
  connectMs?: number
  /** 音频（≤10 秒短句）结束→首音。 */
  firstAudioMs?: number
  /** 文字提交→首音。 */
  textFirstAudioMs?: number
  /** 热启动（已建连后新一轮）到 ready。 */
  warmReadyMs?: number
  interruptLocalMs?: number
  interruptServerMs?: number
  cold?: boolean
}

export interface SyncPoint { ms: number }

export interface FpsWindow { trial: number; minute: number; averageFps: number }

export interface DeviceRecord {
  device: string
  os: string
  browser: string
  cases: Array<{ name: string; status: 'PASS' | 'NOT_RUN' | 'PARTIAL'; evidence?: string }>
}

export interface CostRow {
  stage: 'llm' | 'stt' | 'tts' | 'render'
  /** normal=有完整 usage；interrupted=打断已结；unknown=结果不明（cents 必须 null）。 */
  kind: 'normal' | 'interrupted' | 'unknown'
  /** 稳定经济键（requestId/operation 维度），用于查重。 */
  economicKey: string
  providerRequestId?: string
  cents: number | null
  accountingMatched: boolean
}

export interface RawSamples {
  mode: 'fake' | 'real'
  generatedAt: string
  synthetic?: boolean
  connections: ConnectionTrial[]
  syncPoints: SyncPoint[]
  fps: FpsWindow[]
  devices?: DeviceRecord[]
  cost?: CostRow[]
  capacity?: { maxSessionsGlobal: number; measuredConcurrent: number }
}

export interface MetricsReport {
  mode: 'fake' | 'real'
  synthetic: boolean
  generatedAt: string
  counts: {
    connections: number
    ok: number
    failed: number
    /** ok/connections——失败连接计入分母。 */
    successRate: number
    syncPoints: number
  }
  latencyMs: Record<'connect' | 'firstAudio' | 'textFirstAudio' | 'warmReady' | 'interruptLocal'
    | 'interruptServer' | 'sync', { p50: number | null; p95: number | null; samples: number }>
  fps: { windows: number; renderAverageFps5Min: number | null }
  devices: Array<{ device: string; passed: number; notRun: number }>
  cost: {
    perStageCents: Record<string, number | null>
    unknownCount: number
    interruptedCount: number
    duplicateEconomicKeys: string[]
    accountingMismatched: string[]
  }
  capacity: RawSamples['capacity'] | null
  problems: string[]
}

/** nearest-rank 分位数：P95 = sorted[ceil(0.95*n)-1]（n 个样本；空数组 → null，不填 0）。 */
export function percentile(values: readonly number[], q: number): number | null {
  if (values.length === 0) return null
  if (values.length === 1) return values[0]
  const rank = Math.ceil(q * values.length)
  const index = Math.min(Math.max(rank, 1), values.length) - 1
  const sorted = [...values].sort((left, right) => left - right)
  return sorted[index]
}

function summarize(values: readonly number[]): { p50: number | null; p95: number | null; samples: number } {
  return { p50: percentile(values, 0.5), p95: percentile(values, 0.95), samples: values.length }
}

export function reduce(raw: RawSamples): MetricsReport {
  const connections = raw.connections ?? []
  const ok = connections.filter((trial) => trial.ok)
  const failed = connections.filter((trial) => !trial.ok)
  const sync = (raw.syncPoints ?? []).map((point) => point.ms)
  const fpsWindows = raw.fps ?? []
  const fpsAverage = fpsWindows.length > 0
    ? fpsWindows.reduce((sum, window) => sum + window.averageFps, 0) / fpsWindows.length
    : null

  const costRows = raw.cost ?? []
  const seenKeys = new Map<string, number>()
  const duplicateEconomicKeys: string[] = []
  const accountingMismatched: string[] = []
  const perStageCents: Record<string, number | null> = {}
  const stageTotals = new Map<string, number>()
  let unknownCount = 0
  let interruptedCount = 0
  for (const row of costRows) {
    const seen = seenKeys.get(row.economicKey) ?? 0
    if (seen > 0) duplicateEconomicKeys.push(row.economicKey)
    seenKeys.set(row.economicKey, seen + 1)
    if (!row.accountingMatched) accountingMismatched.push(row.economicKey)
    if (row.kind === 'unknown') {
      unknownCount += 1
      if (row.cents !== null) {
        // unknown 费用不得折算成 0/估计值——保留原样并记问题。
      }
    }
    if (row.kind === 'interrupted') interruptedCount += 1
    if (typeof row.cents === 'number') {
      stageTotals.set(row.stage, (stageTotals.get(row.stage) ?? 0) + row.cents)
    } else if (!(row.stage in perStageCents) || perStageCents[row.stage] === null) {
      perStageCents[row.stage] = null
    }
  }
  for (const [stage, total] of stageTotals) perStageCents[stage] = total

  const report: MetricsReport = {
    mode: raw.mode,
    synthetic: raw.synthetic === true || raw.mode === 'fake',
    generatedAt: new Date().toISOString(),
    counts: {
      connections: connections.length,
      ok: ok.length,
      failed: failed.length,
      successRate: connections.length === 0 ? 0 : ok.length / connections.length,
      syncPoints: sync.length,
    },
    latencyMs: {
      connect: summarize(connections.map((trial) => trial.connectMs).filter((ms): ms is number => ms != null)),
      firstAudio: summarize(ok.map((trial) => trial.firstAudioMs).filter((ms): ms is number => ms != null)),
      textFirstAudio: summarize(ok.map((trial) => trial.textFirstAudioMs).filter((ms): ms is number => ms != null)),
      warmReady: summarize(ok.filter((t) => !t.cold).map((trial) => trial.warmReadyMs)
        .filter((ms): ms is number => ms != null)),
      interruptLocal: summarize(ok.map((trial) => trial.interruptLocalMs).filter((ms): ms is number => ms != null)),
      interruptServer: summarize(ok.map((trial) => trial.interruptServerMs).filter((ms): ms is number => ms != null)),
      sync: summarize(sync),
    },
    fps: { windows: fpsWindows.length, renderAverageFps5Min: fpsAverage === null ? null : Number(fpsAverage.toFixed(2)) },
    devices: (raw.devices ?? []).map((device) => ({
      device: device.device,
      passed: device.cases.filter((entry) => entry.status === 'PASS').length,
      notRun: device.cases.filter((entry) => entry.status === 'NOT_RUN' || entry.status === 'PARTIAL').length,
    })),
    cost: { perStageCents, unknownCount, interruptedCount, duplicateEconomicKeys, accountingMismatched },
    capacity: raw.capacity ?? null,
    problems: [],
  }

  // unknown 费用折算成数值 = 虚构：逐行核对。
  for (const row of costRows) {
    if (row.kind === 'unknown' && row.cents !== null) {
      report.problems.push(`unknown 费用行 ${row.economicKey} 携带 cents=${row.cents}（unknown 必须 null）`)
    }
    if (row.kind === 'normal' && row.cents === null) {
      report.problems.push(`normal 费用行 ${row.economicKey} 缺 cents（缺失不得填 0，须补实测）`)
    }
  }
  if (duplicateEconomicKeys.length > 0) {
    report.problems.push(`重复经济键：${[...new Set(duplicateEconomicKeys)].join(', ')}`)
  }
  if (accountingMismatched.length > 0) {
    report.problems.push(`账务不匹配：${[...new Set(accountingMismatched)].join(', ')}`)
  }

  if (raw.mode === 'real') {
    if (report.counts.connections < 100) {
      report.problems.push(`真实采样连接数 ${report.counts.connections} < 100（含失败分母）`)
    }
    if (report.counts.syncPoints < 100) {
      report.problems.push(`同步点 ${report.counts.syncPoints} < 100`)
    }
    const missingDevice = (raw.devices ?? []).filter((device) =>
      device.cases.some((entry) => entry.status !== 'PASS'))
    if (missingDevice.length > 0) {
      report.problems.push(`实机用例未全过：${missingDevice.map((device) => device.device).join('；')}`)
    }
    for (const stage of ['llm', 'stt', 'tts', 'render'] as const) {
      if (!(stage in perStageCents)) {
        report.problems.push(`缺 ${stage} 成本样本（四项费用必须逐项实测）`)
      }
    }
  }
  return report
}

// ---------------------------------------------------------------------------
// fake 模式：由合成 fixture 确定性生成 raw（证明归约机械；不构成真实资格）
// ---------------------------------------------------------------------------

function seededRandom(seed: number): () => number {
  let state = seed >>> 0
  return () => {
    state = (state * 1664525 + 1013904223) >>> 0
    return state / 0x100000000
  }
}

interface PerformanceFixture {
  seed: number
  trials: number
  coldWarm: { cold: number; warm: number }
  failures: { connect: number }
  syncPointsPerTrial: number
  fps: { nominal: number; samplesPerMinute: number; minutes: number }
  devices: Array<{ device: string; os: string; browser: string; cases: string[] }>
  costCases: Array<{ stage: CostRow['stage']; kind: CostRow['kind']; chargeTo: string }>
}

export function generateFakeRaw(fixture: PerformanceFixture): RawSamples {
  const random = seededRandom(fixture.seed)
  const connections: ConnectionTrial[] = []
  const syncPoints: SyncPoint[] = []
  const fps: FpsWindow[] = []
  for (let trial = 1; trial <= fixture.trials; trial += 1) {
    const cold = trial <= fixture.coldWarm.cold
    const failed = trial <= fixture.failures.connect
    if (failed) {
      connections.push({ trial, ok: false, errorStage: 'connect', connectMs: 9200 + Math.round(random() * 600) })
      continue
    }
    connections.push({
      trial,
      ok: true,
      cold,
      connectMs: (cold ? 6200 : 900) + Math.round(random() * (cold ? 2500 : 1600)),
      firstAudioMs: 2600 + Math.round(random() * 1200),
      textFirstAudioMs: 1800 + Math.round(random() * 1000),
      warmReadyMs: cold ? undefined : 5200 + Math.round(random() * 3200),
      interruptLocalMs: 90 + Math.round(random() * 90),
      interruptServerMs: 420 + Math.round(random() * 420),
    })
    for (let point = 0; point < fixture.syncPointsPerTrial; point += 1) {
      syncPoints.push({ ms: 90 + Math.round(random() * 100) })
    }
    for (let minute = 1; minute <= fixture.fps.minutes; minute += 1) {
      fps.push({ trial, minute, averageFps: Number((fixture.fps.nominal - random() * 3).toFixed(1)) })
    }
  }
  const devices: DeviceRecord[] = fixture.devices.map((device) => ({
    device: device.device,
    os: device.os,
    browser: device.browser,
    // fake 模式实机一律 NOT_RUN（K12：Fake 证明协议，不证明设备行为）。
    cases: device.cases.map((name) => ({ name, status: 'NOT_RUN' as const })),
  }))
  const cost: CostRow[] = fixture.costCases.map((row, index) => ({
    stage: row.stage,
    kind: row.kind,
    economicKey: `fake-${row.stage}-${row.kind}-${index + 1}`,
    providerRequestId: `fake-provider-${index + 1}`,
    cents: row.kind === 'unknown' ? null : 5 + index * 3,
    accountingMatched: true,
  }))
  return {
    mode: 'fake',
    synthetic: true,
    generatedAt: new Date().toISOString(),
    connections,
    syncPoints,
    fps,
    devices,
    cost,
    capacity: { maxSessionsGlobal: 1, measuredConcurrent: 1 },
  }
}

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function main(argv: string[]): number {
  const args = new Map<string, string>()
  for (let index = 0; index < argv.length; index += 2) {
    args.set(argv[index], argv[index + 1] ?? '')
  }
  const mode = args.get('--mode')
  const samplesPath = args.get('--samples')
  const outputPath = args.get('--output')
  const fixturePath = args.get('--fixture')
  if (mode !== 'fake' && mode !== 'real') {
    console.error('--mode fake|real 必填')
    return 2
  }
  if (!samplesPath || !outputPath) {
    console.error('--samples 与 --output 必填')
    return 2
  }
  if (mode === 'fake' && !fixturePath) {
    console.error('fake 模式需要 --fixture（合成样本定义；real 模式只消费实测 raw）')
    return 2
  }

  let raw: RawSamples
  if (mode === 'fake') {
    const fixture = JSON.parse(readFileSync(resolve(fixturePath!), 'utf8')) as PerformanceFixture
    raw = generateFakeRaw(fixture)
    writeFileSync(resolve(samplesPath), JSON.stringify(raw, null, 2) + '\n', 'utf8')
  } else {
    raw = JSON.parse(readFileSync(resolve(samplesPath), 'utf8')) as RawSamples
    if (raw.mode !== 'real') {
      console.error('real 模式的 samples 文件必须 mode=real（不允许以 Fake 样本冒充实测）')
      return 1
    }
  }

  const report = reduce(raw)
  report.generatedAt = new Date().toISOString()
  writeFileSync(resolve(outputPath), JSON.stringify(report, null, 2) + '\n', 'utf8')
  console.log(`performance: mode=${report.mode} connections=${report.counts.connections}`
    + ` ok=${report.counts.ok} failed=${report.counts.failed}`
    + ` successRate=${report.counts.successRate.toFixed(4)} problems=${report.problems.length}`)
  for (const problem of report.problems) {
    console.error(`problem: ${problem}`)
  }
  return report.problems.length > 0 ? 1 : 0
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(import.meta.filename)) {
  process.exit(main(process.argv.slice(2)))
}
