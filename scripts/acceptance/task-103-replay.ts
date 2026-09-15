#!/usr/bin/env npx tsx
/**
 * 任务书 #103 C103-18：受控重放执行器（§12.4 固定接口）。
 *
 * 用法（缺任一参数默认拒绝写）：
 *   npx tsx scripts/acceptance/task-103-replay.ts --plan <plan.json> --plan-sha256 <值> \
 *       --environment fixture --apply
 *
 * 行为约束：
 * - 仅支持 --environment fixture（合成环境）；生产模式不存在——生产只交付 plan。
 * - 执行前校验 plan sha256 与重读每项 beforeVersion：版本变化（他人已处理）即拒绝该项。
 * - manual_review 项永不执行（输出原因清单）。
 * - 幂等：同 plan 重复 apply，已执行项按执行日志（输出目录 executed.json）跳过，零重复副作用。
 * - 失败保留清单：部分失败不得报全完成（退出非零并列 failed 项）。
 * - 不 UPDATE 账本、不自动发历史邮件、不选店长、不猜测补偿金额（本卡禁止）。
 */

import { createHash } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

interface PlanItem {
  caseId: string
  category: string
  economicKey: string
  action: 'fact_backfill' | 'notification_replay' | 'manual_review'
  originalOperationId: string | null
  beforeVersion: number
  beforeHash: string
  riskLevel: 'low' | 'needs_review'
  postconditions: string[]
  reason: string
}

interface Plan {
  runId: string
  sourceHead: string
  generatedAt: string
  mode: string
  summary: { total: number; byAction: Record<string, number>; byCategory: Record<string, number> }
  items: PlanItem[]
}

export interface ReplayResult {
  runId: string
  planSha256: string
  environment: string
  executed: string[]
  skippedVersionChanged: string[]
  skippedManualReview: string[]
  failed: Array<{ caseId: string; error: string }>
}

/** fixture 环境的当前版本读取：从 fixture 的 versionChangedCase 推导（真实环境从服务 facts 读）。 */
function currentVersionOf(caseId: string, versionChanged: { caseId: string; currentVersion: number } | null): number | null {
  if (versionChanged && versionChanged.caseId === caseId) {
    return versionChanged.currentVersion
  }
  return null
}

export async function replay(options: {
  plan: Plan
  planSha256: string
  environment: string
  apply: boolean
  versionChanged: { caseId: string; currentVersion: number } | null
  previousExecuted: string[]
}): Promise<ReplayResult> {
  const result: ReplayResult = {
    runId: options.plan.runId,
    planSha256: options.planSha256,
    environment: options.environment,
    executed: [...options.previousExecuted],
    skippedVersionChanged: [],
    skippedManualReview: [],
    failed: [],
  }
  if (!options.apply) {
    return result
  }
  for (const item of options.plan.items) {
    if (item.action === 'manual_review') {
      result.skippedManualReview.push(item.caseId)
      continue
    }
    if (result.executed.includes(item.caseId)) {
      continue
    }
    const currentVersion = currentVersionOf(item.caseId, options.versionChanged)
    if (currentVersion !== null && currentVersion !== item.beforeVersion) {
      result.skippedVersionChanged.push(item.caseId)
      continue
    }
    // fixture 环境的合成执行：真实环境的 fact_backfill 走已修服务原键、notification_replay
    // 走 identity 显式事件 ID 补发——这里只登记执行事实（可核对后置条件由各服务测试覆盖）。
    result.executed.push(item.caseId)
  }
  return result
}

async function main(): Promise<number> {
  const args = process.argv.slice(2)
  const flag = (name: string): string | null => {
    const index = args.indexOf(`--${name}`)
    return index >= 0 && index + 1 < args.length ? args[index + 1] : null
  }
  const planPath = flag('plan')
  const planSha256Arg = flag('plan-sha256')
  const environment = flag('environment')
  const apply = args.includes('--apply')
  if (!planPath || !planSha256Arg || !environment) {
    console.error('拒绝执行：--plan/--plan-sha256/--environment 缺一不可（默认只读，不写任何状态）')
    return 2
  }
  if (environment !== 'fixture') {
    console.error('拒绝执行：仅支持 --environment fixture；生产只交付 plan，不带执行入口')
    return 2
  }
  const planRaw = await readFile(planPath, 'utf8')
  const planSha256 = createHash('sha256').update(planRaw).digest('hex')
  if (planSha256 !== planSha256Arg) {
    console.error('拒绝执行：plan sha256 不匹配（计划被改动或传错指纹）')
    return 2
  }
  const plan = JSON.parse(planRaw) as Plan
  const fixtureRaw = await readFile(flag('fixture') ?? 'tests/fixtures/task-103/history-cases.json', 'utf8').catch(() => '{}')
  const fixture = JSON.parse(fixtureRaw) as { versionChangedCase?: { caseId: string; currentVersion: number } }
  const statePath = path.join(path.dirname(planPath), 'executed.json')
  const previous = await readFile(statePath, 'utf8')
    .then(raw => JSON.parse(raw) as { executed?: string[] }).catch(() => ({ executed: [] as string[] }))
  const result = await replay({
    plan,
    planSha256,
    environment,
    apply,
    versionChanged: fixture.versionChangedCase ?? null,
    previousExecuted: previous.executed ?? [],
  })
  await mkdir(path.dirname(statePath), { recursive: true })
  await writeFile(statePath, `${JSON.stringify({ executed: result.executed }, null, 2)}\n`, 'utf8')
  const failedCount = result.failed.length
  console.log(`重放完成：执行 ${result.executed.length}、版本变化跳过 ${result.skippedVersionChanged.length}、`
    + `人工审核跳过 ${result.skippedManualReview.length}、失败 ${failedCount}`)
  return failedCount > 0 ? 1 : 0
}

if (process.argv[1] && process.argv[1].endsWith('task-103-replay.ts')) {
  process.exit(await main())
}
