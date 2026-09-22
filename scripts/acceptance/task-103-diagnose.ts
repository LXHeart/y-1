#!/usr/bin/env npx tsx
/**
 * 任务书 #103 C103-18：历史一致性只读诊断 → 可审阅重放计划（§12.4 固定接口）。
 *
 * 用法（离线模式，默认）：
 *   npx tsx scripts/acceptance/task-103-diagnose.ts --fixture tests/fixtures/task-103/history-cases.json \
 *       --output test-artifacts/task-103/diagnostics/plan.json
 *
 * 行为约束（BR-18/本卡禁止）：
 * - 默认只读：不写业务库、不发通知、不修改任何历史事实；输出只有计划 JSON 与计数。
 * - 无充分历史证据（exit_funds_inconsistent / legacy_closure_residue / legacy_multi_manager /
 *   legacy_post_split_refund）一律 manual_review，不倒推金额、不自动选店长。
 * - 可自动化的只有两类：missing_settlement_fact → fact_backfill（按原 operation 键补投影）、
 *   missed_notification → notification_replay（显式事件 ID，过截止/终态不重发）。
 * - 退出码：解析/结构错误非零；正常诊断（含空 case）零。
 */

import { createHash } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

interface HistoryCase {
  caseId: string
  category: string
  economicKey: string
  originalOperationId: string | null
  beforeVersion: number
  beforeHash: string
  evidence: { source: string; ref: string }
  expectedAction?: string
  note?: string
}

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
  mode: 'read-only-diagnosis'
  summary: { total: number; byAction: Record<string, number>; byCategory: Record<string, number> }
  items: PlanItem[]
}

/** §7.5 驱动的分类策略（纯函数）：哪些类别可自动、哪些必须人工。 */
const AUTO_ACTIONS: Record<string, { action: PlanItem['action']; postconditions: string[]; reason: string }> = {
  missing_settlement_fact: {
    action: 'fact_backfill',
    postconditions: [
      'commerce_settlement_fact 存在该 order 行且 source_hash 与 Finance facts 一致',
      '同 operation 键重放零新增（幂等）',
      'dataCompleteness 恢复 complete',
    ],
    reason: '已分账订单缺事实投影：按原 operation 键从 Finance facts 补投影，不改金额',
  },
  missed_notification: {
    action: 'notification_replay',
    postconditions: [
      '仅补发显式事件 ID；inbox (consumer,event_id) 幂等吸收重复',
      '过截止或对象已终态不重发行动提醒（E09）',
    ],
    reason: '事件已持久但通知缺失：显式事件 ID 补发，经既有幂等闸',
  },
}

function classify(input: HistoryCase): PlanItem {
  const auto = AUTO_ACTIONS[input.category]
  if (auto) {
    return {
      caseId: input.caseId,
      category: input.category,
      economicKey: input.economicKey,
      action: auto.action,
      originalOperationId: input.originalOperationId,
      beforeVersion: input.beforeVersion,
      beforeHash: input.beforeHash,
      riskLevel: 'low',
      postconditions: auto.postconditions,
      reason: auto.reason,
    }
  }
  return {
    caseId: input.caseId,
    category: input.category,
    economicKey: input.economicKey,
    action: 'manual_review',
    originalOperationId: input.originalOperationId,
    beforeVersion: input.beforeVersion,
    beforeHash: input.beforeHash,
    riskLevel: 'needs_review',
    postconditions: ['人工核对后按具体授权另行处理；本计划不执行任何写动作'],
    reason: input.note ?? '§7.5：无充分历史证据，不倒推金额/不自动选店长/不回溯删除',
  }
}

function validateCase(input: unknown, index: number): HistoryCase {
  if (typeof input !== 'object' || input === null) {
    throw new Error(`cases[${index}] 必须是对象`)
  }
  const row = input as Record<string, unknown>
  for (const field of ['caseId', 'category', 'economicKey', 'beforeHash'] as const) {
    if (typeof row[field] !== 'string' || !row[field].trim()) {
      throw new Error(`cases[${index}].${field} 必须非空`)
    }
  }
  const evidence = row.evidence as Partial<HistoryCase['evidence']> | null
  if (!evidence || typeof evidence.source !== 'string' || !evidence.source.trim()
    || typeof evidence.ref !== 'string' || !evidence.ref.trim()) {
    throw new Error(`cases[${index}].evidence 必须含非空 source/ref`)
  }
  if (Object.prototype.hasOwnProperty.call(AUTO_ACTIONS, String(row.category))
    && (typeof row.originalOperationId !== 'string' || !row.originalOperationId.trim())) {
    throw new Error(`cases[${index}].originalOperationId 缺失，不能生成自动修复计划`)
  }
  if (typeof row.beforeVersion !== 'number' || !Number.isInteger(row.beforeVersion) || row.beforeVersion < 1) {
    throw new Error(`cases[${index}].beforeVersion 必须是 >=1 整数`)
  }
  if (typeof row.beforeHash !== 'string' || !/^[0-9a-f]{8,128}$/i.test(row.beforeHash)) {
    throw new Error(`cases[${index}].beforeHash 必须是十六进制指纹`)
  }
  return input as HistoryCase
}

export function buildPlan(cases: HistoryCase[], sourceHead: string): Plan {
  // 结构校验不依赖入口（库调用与 CLI 同一闸）：缺字段/非法指纹在此拒绝。
  cases.forEach((row, index) => validateCase(row, index))
  if (new Set(cases.map((row) => row.caseId)).size !== cases.length) {
    throw new Error('caseId 重复，无法安全追踪重放')
  }
  const items = cases.map(classify)
  const byAction: Record<string, number> = {}
  const byCategory: Record<string, number> = {}
  for (const item of items) {
    byAction[item.action] = (byAction[item.action] ?? 0) + 1
    byCategory[item.category] = (byCategory[item.category] ?? 0) + 1
  }
  return {
    runId: `diag-${createHash('sha256').update(sourceHead + items.length + Date.now()).digest('hex').slice(0, 16)}`,
    sourceHead,
    generatedAt: new Date().toISOString(),
    mode: 'read-only-diagnosis',
    summary: { total: items.length, byAction, byCategory },
    items,
  }
}

async function main(): Promise<void> {
  const args = process.argv.slice(2)
  const flag = (name: string): string | null => {
    const index = args.indexOf(`--${name}`)
    return index >= 0 && index + 1 < args.length ? args[index + 1] : null
  }
  const fixturePath = flag('fixture')
  const outputPath = flag('output')
  if (!fixturePath || !outputPath) {
    console.error('用法：task-103-diagnose.ts --fixture <文件> --output <文件>')
    process.exit(2)
  }
  const fixtureRaw = await readFile(fixturePath, 'utf8')
  let fixture: { cases?: unknown[] }
  try {
    fixture = JSON.parse(fixtureRaw) as { cases?: unknown[] }
  } catch (error) {
    console.error(`fixture 不是合法 JSON：${(error as Error).message}`)
    process.exit(2)
  }
  if (!Array.isArray(fixture?.cases)) throw new Error('fixture.cases 必须为数组')
  const cases = fixture.cases.map(validateCase)
  const sourceHead = process.env.TASK103_SOURCE_HEAD ?? 'fixture-environment'
  const plan = buildPlan(cases, sourceHead)
  const dir = path.dirname(outputPath)
  await mkdir(dir, { recursive: true })
  const planBytes = `${JSON.stringify(plan, null, 2)}\n`
  await writeFile(outputPath, planBytes, 'utf8')
  // 指纹口径 = 写盘字节（replay 端对文件内容取哈希，两端一致）
  const planSha256 = createHash('sha256').update(planBytes).digest('hex')
  await writeFile(`${outputPath}.sha256`, `${planSha256}\n`, 'utf8')
  console.log(`诊断完成（只读）：${plan.summary.total} 例 ` +
    `(${Object.entries(plan.summary.byAction).map(([k, v]) => `${k}=${v}`).join(', ')})`)
  console.log(`计划已写入 ${outputPath}（sha256=${planSha256.slice(0, 16)}…）——生产补发/回填需另行授权后经 task-103-replay.ts 执行`)
}

// 直接执行（import 时不跑 main）
if (process.argv[1] && path.resolve(process.argv[1]).endsWith('task-103-diagnose.ts')) {
  await main()
}
