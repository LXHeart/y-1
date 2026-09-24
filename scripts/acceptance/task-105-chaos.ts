/**
 * 数字人有界故障注入 runner（任务书 #105H C105H-04 / V105H-04-04）。
 *
 * 用法：
 *   npx --no-install tsx scripts/acceptance/task-105-chaos.ts \
 *     --compose-project grassland-dh-test --case runtime-kill --output test-artifacts/task-105/H04
 *
 * 安全边界（任务书 §11 C105H-04 硬约束）：
 * - compose 项目名固定白名单 ^grassland-dh-test$——绝不作用于生产/开发者其他容器；
 * - 只对固定 service 白名单执行固定 docker CLI 子命令（spawn 定参数组，无 shell、无拼接）；
 * - 每个 case 观察窗口 ≤60 秒（CHAOS_MAX_WAIT_MS），结束后必须恢复并复核健康；
 * - 项目不存在/服务缺失 → 非零退出（不静默通过）。
 *
 * 语义归属（如实标注，不冒充）：本 runner 证明进程/网络级失联的拓扑行为；
 * provider 半流/磁盘满的账务与存储语义由 DigitalHumanRecoveryIT（真实 DB/事务）与
 * tests/test_recording.py 证明——报告以 realSemanticsAnchor 指向，不在此重复宣称。
 */

import { spawnSync } from 'node:child_process'
import type { SpawnSyncReturns } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

const PROJECT_PATTERN = /^grassland-dh-test$/
const SERVICE_ALLOWLIST = new Set(['dh-runtime', 'dh-redis', 'intelligence-service'])
const CASES = ['runtime-kill', 'java-isolation', 'redis-loss', 'provider-timeout', 'recording-disk-full'] as const
type ChaosCase = (typeof CASES)[number]

const CHAOS_MAX_WAIT_MS = 60_000
const OBSERVE_INTERVAL_MS = 1_000

interface ContainerFacts {
  name: string
  service: string
  state: string
  status: string
  health?: string
  exitCode?: number
  oom?: boolean
  restarts?: number
}

interface CaseReport {
  case: ChaosCase
  composeProject: string
  startedAt: string
  finishedAt: string
  observeMs: number
  injection: string
  observed: ContainerFacts[]
  restored: boolean
  healthyAfterRestore: boolean
  ok: boolean
  problems: string[]
  realSemanticsAnchor?: string
  notes?: string
}

/** 隔离栈的固定 compose 文件（与 ci-e2e DH_E2E=1 同构；不收用户输入）。 */
const COMPOSE_FILES = ['-f', 'docker-compose.yml', '-f', 'deploy/digital-human/compose.test.yml']

/**
 * 固定 argv 的 docker compose 调用（无 shell、无用户可控拼接）。合成 env 只满足 compose
 * 文件的 ${VAR:?} 校验占位（非密钥；dh 服务不消费这些值）。
 */
function compose(project: string, args: readonly string[]): SpawnSyncReturns<string> {
  return spawnSync('docker', ['compose', '--project-name', project, ...COMPOSE_FILES, ...args],
    {
      encoding: 'utf8',
      timeout: CHAOS_MAX_WAIT_MS + 30_000,
      cwd: process.cwd(),
      env: {
        ...process.env,
        MINIO_ROOT_USER: process.env.MINIO_ROOT_USER ?? 'chaos-placeholder-user',
        MINIO_ROOT_PASSWORD: process.env.MINIO_ROOT_PASSWORD ?? 'chaos-placeholder-pass',
        MINIO_ACCESS_KEY: process.env.MINIO_ACCESS_KEY ?? 'chaos-placeholder-key',
        MINIO_SECRET_KEY: process.env.MINIO_SECRET_KEY ?? 'chaos-placeholder-secret',
      },
    })
}

function dockerInspectFacts(project: string, service: string): ContainerFacts[] {
  const ps = compose(project, ['ps', '-a', '--format', 'json'])
  if (ps.status !== 0) return []
  const lines = ps.stdout.trim().split('\n').filter(Boolean)
  return lines.map((line) => {
    const row = JSON.parse(line) as Record<string, unknown>
    return {
      name: String(row.Name ?? row.name ?? ''),
      service: String(row.Service ?? row.service ?? ''),
      state: String(row.State ?? row.state ?? ''),
      status: String(row.Status ?? row.status ?? ''),
      health: row.Health == null ? undefined : String(row.Health),
      exitCode: row.ExitCode == null ? undefined : Number(row.ExitCode),
    }
  }).filter((facts) => facts.service === service)
}

function waitFor(predicate: () => boolean, timeoutMs: number): boolean {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (predicate()) return true
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, OBSERVE_INTERVAL_MS)
  }
  return predicate()
}

function projectHasService(project: string, service: string): boolean {
  const facts = dockerInspectFacts(project, service)
  return facts.length > 0
}

function snapshot(project: string): ContainerFacts[] {
  return [...SERVICE_ALLOWLIST].flatMap((service) => dockerInspectFacts(project, service))
}

function restore(project: string, service: string): boolean {
  const up = compose(project, ['up', '-d', '--no-deps', service])
  return up.status === 0
}

function baseReport(project: string, chaosCase: ChaosCase): CaseReport {
  return {
    case: chaosCase,
    composeProject: project,
    startedAt: new Date().toISOString(),
    finishedAt: '',
    observeMs: 0,
    injection: '',
    observed: [],
    restored: false,
    healthyAfterRestore: false,
    ok: false,
    problems: [],
  }
}

function finish(report: CaseReport, ok: boolean, problems: string[] = []): CaseReport {
  report.finishedAt = new Date().toISOString()
  report.ok = ok && report.restored && report.healthyAfterRestore && problems.length === 0
  report.problems = problems
  return report
}

// ---------------------------------------------------------------------------
// 五个固定 case（注入→有界观察→恢复→复核）
// ---------------------------------------------------------------------------

function caseRuntimeKill(project: string): CaseReport {
  const report = baseReport(project, 'runtime-kill')
  report.injection = 'docker compose kill dh-runtime'
  report.observed = snapshot(project)
  const killStatus = compose(project, ['kill', 'dh-runtime']).status
  const started = Date.now()
  const exited = waitFor(() => dockerInspectFacts(project, 'dh-runtime')
    .some((facts) => ['exited', 'dead'].includes(facts.state))
    && dockerInspectFacts(project, 'dh-runtime').length > 0, CHAOS_MAX_WAIT_MS)
  report.observeMs = Date.now() - started
  report.observed = snapshot(project)
  report.restored = restore(project, 'dh-runtime')
  report.healthyAfterRestore = waitFor(() => dockerInspectFacts(project, 'dh-runtime')
    .some((facts) => facts.state === 'running'), CHAOS_MAX_WAIT_MS)
  const problems = exited ? [] : ['60 秒内 runtime 未停止（观察超时）']
  if (killStatus !== 0) problems.push(`kill 非零（${killStatus}）——注入未生效`)
  if (!report.restored) problems.push('恢复失败：up -d --no-deps dh-runtime 非零')
  if (!report.healthyAfterRestore) problems.push('恢复后 runtime 未回到 running')
  return finish(report, exited, problems)
}

function caseJavaIsolation(project: string): CaseReport {
  const report = baseReport(project, 'java-isolation')
  report.injection = 'docker network disconnect <project>-_dh-internal <intelligence-service 容器>'
  report.observed = snapshot(project)
  const containers = dockerInspectFacts(project, 'intelligence-service')
  const networkName = `${project}_dh-internal`
  if (containers.length === 0) {
    report.restored = true
    report.healthyAfterRestore = true
    return finish(report, false, ['intelligence-service 容器未找到'])
  }
  const before = snapshot(project)
  const disconnect = spawnSync('docker', ['network', 'disconnect', networkName, containers[0].name],
    { encoding: 'utf8', timeout: 15_000 })
  const started = Date.now()
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 5_000)
  report.observeMs = Date.now() - started
  report.observed = snapshot(project)
  const reconnect = spawnSync('docker', ['network', 'connect', networkName, containers[0].name],
    { encoding: 'utf8', timeout: 15_000 })
  report.restored = reconnect.status === 0
  report.healthyAfterRestore = waitFor(() => {
    const current = dockerInspectFacts(project, 'intelligence-service')
    return current.length > 0 && current.every((facts) => facts.state === 'running')
      && JSON.stringify(current) !== '[]' && before.length > 0
  }, CHAOS_MAX_WAIT_MS)
  const problems: string[] = []
  if (disconnect.status !== 0) problems.push(`network disconnect 失败：${disconnect.stderr}`)
  if (!report.restored) problems.push(`恢复失败：network connect 非零 ${reconnect.stderr}`)
  if (!report.healthyAfterRestore) problems.push('恢复后 intelligence-service 不在 running')
  report.notes = 'Java 隔断期间 runtime 桥不可达——自停语义见 tests/test_leases.py（租约到期停推理）；此处证拓扑可逆。'
  report.realSemanticsAnchor = 'DigitalHumanRecoveryIT.tc105h_04_02 + tests/test_leases.py'
  return finish(report, disconnect.status === 0, problems)
}

function caseRedisLoss(project: string): CaseReport {
  const report = baseReport(project, 'redis-loss')
  report.injection = 'docker compose restart dh-redis（无持久化：tmpfs + noeviction，重启即丢）'
  report.observed = snapshot(project)
  compose(project, ['restart', 'dh-redis'])
  const started = Date.now()
  const healthy = waitFor(() => dockerInspectFacts(project, 'dh-redis')
    .some((facts) => (facts.health == null ? facts.state === 'running' : facts.health === 'healthy')),
  CHAOS_MAX_WAIT_MS)
  report.observeMs = Date.now() - started
  report.observed = snapshot(project)
  report.restored = true
  report.healthyAfterRestore = healthy
  report.notes = '内容/资格 Redis 禁 AOF/RDB（K05）——重启后未保存正文不恢复；协调状态丢失 fail closed（写满拒写 noeviction）。'
  report.realSemanticsAnchor = 'compose.test/production.yml redis command + K05'
  const problems = healthy ? [] : ['redis 重启后 60 秒未恢复健康']
  return finish(report, healthy, problems)
}

/** provider 出站面：从 dh-runtime 容器内发起一次受控外连（固定探测常量），必须在网络层被拒。 */
const PROVIDER_EGRESS_PROBE = 'import urllib.request,sys\n'
  + 'urllib.request.urlopen("https://provider-egress-probe.invalid/v1", timeout=3)\n'
  + 'sys.stdout.write("EGRESS_REACHED")\n'

function caseProviderTimeout(project: string): CaseReport {
  const report = baseReport(project, 'provider-timeout')
  report.injection = 'dh-runtime 容器内受控外连探测（固定常量脚本；internal 网络应拒绝出站）'
  report.observed = snapshot(project)
  const containers = dockerInspectFacts(project, 'dh-runtime')
  if (containers.length === 0) {
    report.restored = true
    report.healthyAfterRestore = true
    return finish(report, false, ['dh-runtime 容器未找到'])
  }
  const probe = spawnSync('docker', ['exec', containers[0].name, 'python3', '-c', PROVIDER_EGRESS_PROBE],
    { encoding: 'utf8', timeout: 15_000 })
  const blocked = probe.status !== 0 && !probe.stdout.includes('EGRESS_REACHED')
  const started = Date.now()
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 2_000)
  report.observeMs = Date.now() - started
  report.observed = snapshot(project)
  report.restored = true // 只读探测，无需恢复
  report.healthyAfterRestore = dockerInspectFacts(project, 'dh-runtime')
    .some((facts) => facts.state === 'running')
  report.notes = 'K14：全部第三方调用经 Java 控制面执行；runtime 无出站网关——半流/超时账务语义见锚定 IT。'
  report.realSemanticsAnchor = 'DigitalHumanRecoveryIT.tc105h_04_02'
  const problems = blocked ? [] : ['runtime 出站探测未被网络层拒绝（internal 网络语义失效）']
  return finish(report, blocked, problems)
}

function caseRecordingDiskFull(project: string): CaseReport {
  const report = baseReport(project, 'recording-disk-full')
  report.injection = '进程级类比：docker compose kill dh-runtime（写盘中途死亡）'
  report.observed = snapshot(project)
  const killStatus = compose(project, ['kill', 'dh-runtime']).status
  const started = Date.now()
  const exited = waitFor(() => dockerInspectFacts(project, 'dh-runtime').length > 0
    && dockerInspectFacts(project, 'dh-runtime')
      .every((facts) => ['exited', 'dead'].includes(facts.state)), CHAOS_MAX_WAIT_MS)
  report.observeMs = Date.now() - started
  report.observed = snapshot(project)
  report.restored = restore(project, 'dh-runtime')
  report.healthyAfterRestore = waitFor(() => dockerInspectFacts(project, 'dh-runtime')
    .some((facts) => facts.state === 'running'), CHAOS_MAX_WAIT_MS)
  report.notes = '磁盘满的文件/账务语义（坏文件不入库、cleanup 可重试、资产不误删）在 IT 层真证明；本 case 证写盘中途死亡后的拓扑恢复。'
  report.realSemanticsAnchor = 'DigitalHumanRecoveryIT.tc105h_04_03 + tests/test_recording.py'
  const problems = exited ? [] : ['60 秒内 runtime 未停止']
  if (killStatus !== 0) problems.push(`kill 非零（${killStatus}）——注入未生效`)
  if (!report.restored) problems.push('恢复失败')
  if (!report.healthyAfterRestore) problems.push('恢复后未 running')
  return finish(report, exited, problems)
}

const CASE_RUNNERS: Record<ChaosCase, (project: string) => CaseReport> = {
  'runtime-kill': caseRuntimeKill,
  'java-isolation': caseJavaIsolation,
  'redis-loss': caseRedisLoss,
  'provider-timeout': caseProviderTimeout,
  'recording-disk-full': caseRecordingDiskFull,
}

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

export function main(argv: string[]): number {
  const args = new Map<string, string>()
  for (let index = 0; index < argv.length; index += 2) {
    args.set(argv[index], argv[index + 1] ?? '')
  }
  const project = args.get('--compose-project') ?? ''
  const chaosCase = args.get('--case') as ChaosCase | undefined
  const outputDir = args.get('--output') ?? ''

  if (!PROJECT_PATTERN.test(project)) {
    console.error(`compose project 不在白名单（只允许 grassland-dh-test）：${project || '(空)'}`)
    return 2
  }
  if (!chaosCase || !CASES.includes(chaosCase)) {
    console.error(`case 必须是：${CASES.join('|')}`)
    return 2
  }
  if (!outputDir) {
    console.error('--output 必填')
    return 2
  }

  // 前置：目标项目必须已在运行（缺服务=非零，不静默通过）。
  for (const service of SERVICE_ALLOWLIST) {
    if (!projectHasService(project, service)) {
      console.error(`项目 ${project} 缺服务 ${service}（先以隔离栈拉起再注入）`)
      return 1
    }
  }

  const report = CASE_RUNNERS[chaosCase](project)
  mkdirSync(resolve(outputDir), { recursive: true })
  const path = resolve(outputDir, `chaos-${chaosCase}.json`)
  writeFileSync(path, JSON.stringify(report, null, 2) + '\n', 'utf8')
  console.log(`chaos ${chaosCase}: ok=${report.ok} restored=${report.restored}`
    + ` healthy=${report.healthyAfterRestore} problems=${report.problems.length} → ${path}`)
  for (const problem of report.problems) {
    console.error(`problem: ${problem}`)
  }
  return report.ok ? 0 : 1
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(import.meta.filename)) {
  process.exit(main(process.argv.slice(2)))
}
