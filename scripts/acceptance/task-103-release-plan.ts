import { createHash } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * 任务书 #103 C103-26（V17）：升级与安全回退执行包——默认 plan-only。
 *
 * 输入旧/新构建标识、迁移清单、待办计数与证据路径，输出确定步骤（§12.6 发布顺序）与
 * 阻断原因；不部署、不写生产库、无 ssh/docker 副作用。缺证据/新操作存量/混旧 worker
 * 一律 BLOCKED：有未完成的新退出/清理操作时禁止让旧 worker 接流量（回退检查红线）。
 */

export interface UpgradeCaseInput {
  buildId: string
  previousBuildId: string
  /** 各服务待执行迁移版本；checksumsRegistered=false 表示已发布 SQL 出现 checksum 差异。 */
  migrations: Array<{ service: string; versions: string[]; checksumsRegistered?: boolean }>
  /** 待办计数（只读来源）：未完成的本书新增操作与未解释 needs_review。 */
  pendingOperations: {
    exitOperations?: number
    erasureManifests?: number
    needsReviewCount?: number
    unknownFunds?: number
  }
  /** 需要解释的 needs_review 明细（code + 说明）；解释齐全时不阻断发布观察。 */
  needsReviewExplanations?: Array<{ code: string; reason: string }>
  evidence: {
    apiCheck?: string
    e2e?: string
    upgradeReport?: string
    backupRestoreProof?: string
  }
}

export type BlockerCode =
  | 'EVIDENCE_MISSING'
  | 'PENDING_NEW_OPERATIONS'
  | 'NEEDS_REVIEW_UNEXPLAINED'
  | 'CHECKSUM_UNREGISTERED'
  | 'UNKNOWN_FUNDS'
  | 'INVALID_INPUT'

export interface PlanStep {
  order: number
  action: string
  gate: string
}

export interface ReleasePlanResult {
  buildId: string
  previousBuildId: string
  mode: 'plan-only'
  status: 'READY' | 'BLOCKED'
  inputHash: string
  steps: PlanStep[]
  blockers: Array<{ code: BlockerCode; detail: string }>
  rollbackNotes: string[]
}

/** §12.6 发布顺序（先停旧写者 → 迁移 → 兼容服务 → 新 worker → 观察）。 */
export const RELEASE_STEPS: PlanStep[] = [
  { order: 1, action: '阻止旧写者：维护窗口/停旧退出、退款、注销 worker，排空在飞任务', gate: '旧写者零流量' },
  { order: 2, action: '核对备份可恢复证据（未验证的备份不算备份）', gate: 'backupRestoreProof 有效' },
  { order: 3, action: 'release-migrator 按 bootstrap → Identity → Marketplace → Finance → Trust → Intelligence 执行迁移', gate: '迁移零失败零 repair' },
  { order: 4, action: '部署 Finance / Identity / Intelligence（新事实读取方），随后 Marketplace 守卫/恢复与 Edge 兼容层', gate: '兼容服务健康' },
  { order: 5, action: '开启新写入与新 worker（全新版本就位后才放行）', gate: '无未完成新操作存量' },
  { order: 6, action: '发布观察：退出资金最老年龄 / needs_review / 退款 409 原因 / 通知 DLT / 清理 pendingObjects', gate: '指标无异常' },
]

export const ROLLBACK_NOTES = [
  '应用回退保留新增表/事实/manifest，禁止 DROP 式回滚',
  '有新退出/清理操作存量时禁止旧 worker 接管（旧写路径缺陷会重新生效）',
  '降级客户端只读兼容不等于业务写回退安全：旧代码写路径保持关闭或路由到修复版',
  '迁移失败保持失败现场，测试环境修复后前向发布，不自动回滚财务记录',
]

export function inputChecksum(input: UpgradeCaseInput): string {
  return createHash('sha256').update(JSON.stringify(input)).digest('hex').slice(0, 16)
}

export function validateInput(input: unknown): asserts input is UpgradeCaseInput {
  if (typeof input !== 'object' || input === null) {
    throw new Error('INVALID_INPUT: fixture 须为对象')
  }
  const candidate = input as Partial<UpgradeCaseInput>
  if (!candidate.buildId || typeof candidate.buildId !== 'string') {
    throw new Error('INVALID_INPUT: buildId 必填')
  }
  if (!candidate.previousBuildId || typeof candidate.previousBuildId !== 'string') {
    throw new Error('INVALID_INPUT: previousBuildId 必填')
  }
  if (!Array.isArray(candidate.migrations)) {
    throw new Error('INVALID_INPUT: migrations 必填（数组，可为空表示零迁移）')
  }
  for (const migration of candidate.migrations) {
    if (!migration.service || !Array.isArray(migration.versions)) {
      throw new Error('INVALID_INPUT: 每个 migration 须含 service 与 versions')
    }
  }
  if (typeof candidate.pendingOperations !== 'object' || candidate.pendingOperations === null) {
    throw new Error('INVALID_INPUT: pendingOperations 必填（计数对象）')
  }
}

export function buildReleasePlan(input: UpgradeCaseInput): ReleasePlanResult {
  validateInput(input)
  const blockers: ReleasePlanResult['blockers'] = []

  const evidence = input.evidence ?? {}
  for (const [key, path] of Object.entries(evidence)) {
    if (!path) {
      blockers.push({ code: 'EVIDENCE_MISSING', detail: `证据 ${key} 缺失：无该证据不得进入对应步骤` })
    }
  }
  if (!evidence.apiCheck) blockers.push({ code: 'EVIDENCE_MISSING', detail: 'apiCheck 事实核对报告缺失（V16）' })
  if (!evidence.upgradeReport) blockers.push({ code: 'EVIDENCE_MISSING', detail: '升级演练报告缺失（V14/C103-23）' })
  if (!evidence.backupRestoreProof) {
    blockers.push({ code: 'EVIDENCE_MISSING', detail: '备份恢复证据缺失：无恢复证明不发布' })
  }

  const pending = input.pendingOperations
  if ((pending.exitOperations ?? 0) > 0 || (pending.erasureManifests ?? 0) > 0) {
    blockers.push({
      code: 'PENDING_NEW_OPERATIONS',
      detail: `未完成的新操作存量：exitOperations=${pending.exitOperations ?? 0}`
        + ` erasureManifests=${pending.erasureManifests ?? 0}——禁止旧 worker 接管`,
    })
  }
  const explained = new Set((input.needsReviewExplanations ?? []).map((item) => item.code))
  const unexplained = (pending.needsReviewCount ?? 0) - explained.size
  if (unexplained > 0) {
    blockers.push({ code: 'NEEDS_REVIEW_UNEXPLAINED', detail: `${unexplained} 个 needs_review 未解释` })
  }
  if ((pending.unknownFunds ?? 0) > 0) {
    blockers.push({ code: 'UNKNOWN_FUNDS', detail: `unknown 资金操作 ${pending.unknownFunds} 笔未核实` })
  }
  for (const migration of input.migrations) {
    if (migration.checksumsRegistered === false) {
      blockers.push({
        code: 'CHECKSUM_UNREGISTERED',
        detail: `${migration.service} 已发布迁移 checksum 差异：禁止覆盖/repair，先修订登记`,
      })
    }
  }

  const blocked = blockers.length > 0
  // BLOCKED 时步骤截断到「停旧写者」：不允许生成继续迁移/部署的执行顺序。
  const steps = blocked ? RELEASE_STEPS.slice(0, 1) : RELEASE_STEPS
  return {
    buildId: input.buildId,
    previousBuildId: input.previousBuildId,
    mode: 'plan-only',
    status: blocked ? 'BLOCKED' : 'READY',
    inputHash: inputChecksum(input),
    steps,
    blockers,
    rollbackNotes: ROLLBACK_NOTES,
  }
}

function main(): void {
  const args = process.argv.slice(2)
  let fixturePath = ''
  let outputPath = ''
  for (let i = 0; i < args.length; i += 1) {
    if (args[i] === '--fixture' && args[i + 1]) {
      fixturePath = args[i + 1]
      i += 1
    } else if (args[i] === '--output' && args[i + 1]) {
      outputPath = args[i + 1]
      i += 1
    } else {
      throw new Error('usage: tsx scripts/acceptance/task-103-release-plan.ts --fixture <upgrade-cases.json> --output <release-plan.json>')
    }
  }
  if (!fixturePath || !outputPath) {
    throw new Error('usage: tsx scripts/acceptance/task-103-release-plan.ts --fixture <upgrade-cases.json> --output <release-plan.json>')
  }
  const content = readFileSync(resolve(fixturePath), 'utf8')
  if (content.trim().length === 0) {
    throw new Error('INVALID_INPUT: fixture 为空文件')
  }
  const cases = JSON.parse(content) as { cases?: UpgradeCaseInput[] } | UpgradeCaseInput[]
  const list = Array.isArray(cases) ? cases : cases.cases ?? []
  if (!Array.isArray(list) || list.length === 0) {
    throw new Error('INVALID_INPUT: fixture 缺少 cases 数组')
  }
  const plans = list.map((item) => buildReleasePlan(item))
  writeFileSync(resolve(outputPath), `${JSON.stringify({
    mode: 'plan-only',
    generatedFrom: fixturePath,
    plans,
  }, null, 2)}\n`, 'utf8')
  const blocked = plans.filter((plan) => plan.status === 'BLOCKED').length
  console.log(`plans=${plans.length} ready=${plans.length - blocked} blocked=${blocked}`)
  if (plans.some((plan) => plan.status === 'BLOCKED' && plan.blockers.some((b) => b.code === 'INVALID_INPUT'))) {
    process.exit(1)
  }
}

if (process.argv[1] && resolve(process.argv[1]).endsWith('task-103-release-plan.ts')) {
  main()
}
