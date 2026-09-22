import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { Client } from 'pg'

/**
 * 任务书 #103 C103-24（V16）：同 runId 事实/资金/状态只读核对。
 *
 * 输入 --manifest（tests/e2e/fixtures/task-103.ts 生成的 run manifest），
 * 输出 --output JSON：每条核对只含 ID 摘要与金额，不写凭据、不打令牌。
 *
 * 副作用红线：只执行 SELECT（fixture 环境连网络都不连）；不修改任何业务结果；
 * 核对失败/证据缺失 → 非零退出，不把「没查到」当通过。
 */

export interface CheckResult {
  id: string
  domain: 'finance' | 'marketplace' | 'trust' | 'identity' | 'intelligence' | 'manifest'
  status: 'PASS' | 'FAIL' | 'NOT_CHECKED'
  detail: string
}

export function validateManifest(manifest: RunManifestInput): CheckResult[] {
  const results: CheckResult[] = []
  const ok = (id: string, domain: CheckResult['domain'], detail: string) =>
    results.push({ id, domain, status: 'PASS', detail })
  const fail = (id: string, domain: CheckResult['domain'], detail: string) =>
    results.push({ id, domain, status: 'FAIL', detail })
  const skip = (id: string, domain: CheckResult['domain'], detail: string) =>
    results.push({ id, domain, status: 'NOT_CHECKED', detail })

  if (!manifest || !['isolated-stack', 'fixture'].includes(manifest.environment ?? '')) {
    fail('manifest-environment', 'manifest', 'environment 必须明确为 isolated-stack 或 fixture')
    return results
  }
  const runId = manifest.runId ?? ''
  if (!/^t103-\d{14}-[a-z0-9]{6}$/.test(runId)) {
    fail('manifest-runId', 'manifest', `runId 非法：${runId || '(missing)'}`)
  } else {
    ok('manifest-runId', 'manifest', runId)
  }

  const accounts = manifest.accounts ?? []
  if (!Array.isArray(accounts) || accounts.length === 0) {
    fail('manifest-accounts', 'manifest', '账号为空：fixture 未创建任何 runId 作用域账号')
  } else if (accounts.some((account) => typeof account?.email !== 'string' || !account.email.endsWith('@example.invalid') || !account.email.startsWith(`${runId}-`))) {
    fail('manifest-accounts', 'manifest', '账号必须属于当前 runId 的 example.invalid 合成域')
  } else {
    ok('manifest-accounts', 'manifest', `${accounts.length} 个合成域账号`)
  }

  if (manifest.environment === 'fixture') {
    skip('fixture-offline', 'manifest', 'fixture 环境：只做 manifest 结构核对，不连任何服务')
  }
  return results
}

/** pg 的 bigint 默认返回字符串；统一用整数分比较，避免字典序/字符串拼接与精度损失。 */
function cents(value: unknown): bigint {
  if ((typeof value !== 'string' || !/^-?[0-9]+$/.test(value))
    && (typeof value !== 'number' || !Number.isSafeInteger(value))) {
    throw new Error('账务金额不是可核验的整数分')
  }
  return BigInt(value)
}

/** 数据库只读核对（每条查询都是 SELECT；金额守恒/状态一致来自服务写入的真实事实）。 */
export async function checkDatabaseFacts(
  manifest: RunManifestInput,
  databaseUrl: string,
): Promise<CheckResult[]> {
  const results: CheckResult[] = []
  const client = new Client({ connectionString: databaseUrl })
  await client.connect()
  try {
    const orderRef = manifest.flows?.order?.orderRef
    if (!orderRef) {
      results.push({ id: 'order', domain: 'finance', status: 'NOT_CHECKED', detail: 'manifest 未登记订单流程' })
    } else {
      const payment = await client.query(
        'SELECT amount_cents, refunded_amount_cents, status FROM consumer_payment WHERE order_ref = $1', [orderRef])
      if (payment.rowCount === 0) {
        results.push({ id: 'order-payment', domain: 'finance', status: 'FAIL', detail: `consumer_payment 无 order_ref=${orderRef}` })
      } else {
        const row = payment.rows[0]
        results.push({
          id: 'order-payment',
          domain: 'finance',
          status: cents(row.refunded_amount_cents) >= 0n && cents(row.refunded_amount_cents) <= cents(row.amount_cents) ? 'PASS' : 'FAIL',
          detail: `amount=${row.amount_cents} refunded=${row.refunded_amount_cents} status=${row.status}`,
        })
        const split = await client.query(
          'SELECT recommender_amount_cents, merchant_amount_cents, platform_fee_cents, status'
          + ' FROM consumer_payment_split WHERE order_ref = $1', [orderRef])
        if (split.rowCount === 0) {
          results.push({
            id: 'order-split',
            domain: 'finance',
            status: 'NOT_CHECKED',
            detail: '无分账行（未到分账阶段或部分退款未触发分账）',
          })
        } else {
          const leg = split.rows[0]
          const total = cents(leg.recommender_amount_cents) + cents(leg.merchant_amount_cents) + cents(leg.platform_fee_cents)
          const expected = cents(row.amount_cents) - cents(row.refunded_amount_cents)
          results.push({
            id: 'order-split',
            domain: 'finance',
            status: total === expected && leg.status === 'completed' ? 'PASS' : 'FAIL',
            detail: `legs=${total} expected(net)=${expected} status=${leg.status}`
              + ` (recommender=${leg.recommender_amount_cents} merchant=${leg.merchant_amount_cents} platform=${leg.platform_fee_cents})`,
          })
        }
        const fact = await client.query(
          'SELECT net_total_cents, merchant_cents, platform_cents, recommender_total_cents'
          + ' FROM commerce_settlement_fact WHERE order_id = $1::uuid',
          [orderRef])
        if (fact.rowCount === 0) {
          results.push({ id: 'settlement-fact', domain: 'marketplace', status: 'NOT_CHECKED', detail: '无经确认分账事实（分账未完成）' })
        } else {
          const f = fact.rows[0]
          const parts = cents(f.merchant_cents) + cents(f.platform_cents) + cents(f.recommender_total_cents)
          results.push({
            id: 'settlement-fact',
            domain: 'marketplace',
            status: parts === cents(f.net_total_cents) && parts === cents(row.amount_cents) - cents(row.refunded_amount_cents) ? 'PASS' : 'FAIL',
            detail: `net=${f.net_total_cents} parts=${parts} (merchant=${f.merchant_cents} platform=${f.platform_cents} recommender=${f.recommender_total_cents})`,
          })
        }
      }
    }

    const exitApp = manifest.flows?.exit?.applicationId
    if (!exitApp) {
      results.push({ id: 'exit-operation', domain: 'marketplace', status: 'NOT_CHECKED', detail: 'manifest 未登记退出流程' })
    } else {
      const exit = await client.query(
        'SELECT kind, state, application_id FROM engagement_exit_operation WHERE application_id = $1', [exitApp])
      if (exit.rowCount === 0) {
        results.push({ id: 'exit-operation', domain: 'marketplace', status: 'FAIL', detail: `无退出操作行 application_id=${exitApp}` })
      } else {
        const op = exit.rows[0]
        results.push({
          id: 'exit-operation',
          domain: 'marketplace',
          status: op.state === 'succeeded' ? 'PASS' : 'FAIL',
          detail: `kind=${op.kind} state=${op.state}`,
        })
        const legs = await client.query(
          'SELECT leg_kind, amount_cents, state FROM engagement_exit_fund_leg'
          + ' WHERE operation_id = (SELECT id FROM engagement_exit_operation WHERE application_id = $1)', [exitApp])
        const duplicated = legs.rows.length !== new Set(legs.rows.map((leg: { leg_kind: string }) => leg.leg_kind)).size
        results.push({
          id: 'exit-fund-legs',
          domain: 'finance',
          status: duplicated || legs.rows.some((leg) => !['succeeded', 'not_required'].includes(leg.state)) ? 'FAIL' : 'PASS',
          detail: legs.rows.length === 0
            ? '无资金腿（无责退出且无冻结资金时合法）'
            : legs.rows.map((leg: { leg_kind: string; amount_cents: number; state: string }) =>
                `${leg.leg_kind}=${leg.amount_cents}:${leg.state}`).join(', '),
        })
      }
    }

    const closure = manifest.flows?.closure?.closureRequestId
    if (!closure) {
      results.push({ id: 'closure', domain: 'identity', status: 'NOT_CHECKED', detail: 'manifest 未登记注销流程' })
    } else {
      const request = await client.query(
        'SELECT status, retention_until FROM account_closure_request WHERE id = $1', [closure])
      if (request.rowCount === 0) {
        results.push({ id: 'closure-request', domain: 'identity', status: 'FAIL', detail: `无注销请求 id=${closure}` })
      } else {
        results.push({
          id: 'closure-request',
          domain: 'identity',
          status: ['retention', 'erasing', 'completed'].includes(request.rows[0].status) ? 'PASS' : 'FAIL',
          detail: `status=${request.rows[0].status}`,
        })
        const steps = await client.query(
          'SELECT domain, step, state FROM account_closure_step WHERE closure_request_id = $1 ORDER BY step', [closure])
        results.push({
          id: 'closure-steps',
          domain: 'identity',
          status: steps.rows.some((step) => step.domain === 'intelligence' && step.step === 'prepare' && step.state === 'succeeded')
            && !steps.rows.some((step) => ['retry_wait', 'needs_review'].includes(step.state)) ? 'PASS' : 'FAIL',
          detail: steps.rows.map((step: { step: string; state: string }) => `${step.step}:${step.state}`).join(', ') || '无步骤行',
        })
      }
    }

    const notificationIds = manifest.flows?.notification?.notificationIds ?? []
    if (notificationIds.length === 0) {
      results.push({ id: 'notifications', domain: 'identity', status: 'NOT_CHECKED', detail: 'manifest 未登记通知流程' })
    } else {
      const rows = await client.query(
        'SELECT id, link_path FROM notification WHERE id = ANY($1::uuid[])', [notificationIds])
      const missing = notificationIds.filter((id) => !rows.rows.some((row: { id: string }) => row.id === id))
      const noLink = rows.rows.filter((row: { link_path: string }) => !row.link_path)
      results.push({
        id: 'notifications',
        domain: 'identity',
        status: missing.length === 0 && noLink.length === 0 ? 'PASS' : 'FAIL',
        detail: `登记=${notificationIds.length} 命中=${rows.rows.length} 缺失=${missing.length} 无深链=${noLink.length}`,
      })
    }
  } finally {
    await client.end()
  }
  return results
}

interface RunManifestInput {
  runId?: string
  environment?: string
  accounts?: Array<{ email?: string; role?: string; displayName?: string }>
  flows?: {
    exit?: { applicationId?: string }
    order?: { orderRef?: string }
    closure?: { closureRequestId?: string }
    notification?: { notificationIds?: string[] }
  }
}

function parseArgs(args: string[]): { manifestPath: string; outputPath: string } {
  let manifestPath = ''
  let outputPath = ''
  for (let i = 0; i < args.length; i += 1) {
    if (args[i] === '--manifest' && args[i + 1]) {
      manifestPath = args[i + 1]
      i += 1
    } else if (args[i] === '--output' && args[i + 1]) {
      outputPath = args[i + 1]
      i += 1
    } else {
      throw new Error('usage: tsx scripts/acceptance/task-103-api-check.ts --manifest <run-manifest.json> --output <fact-check.json>')
    }
  }
  if (!manifestPath || !outputPath) {
    throw new Error('usage: tsx scripts/acceptance/task-103-api-check.ts --manifest <run-manifest.json> --output <fact-check.json>')
  }
  return { manifestPath, outputPath }
}

async function main(): Promise<void> {
  const { manifestPath, outputPath } = parseArgs(process.argv.slice(2))
  const manifest = JSON.parse(readFileSync(resolve(manifestPath), 'utf8')) as RunManifestInput
  let results = validateManifest(manifest)
  if (manifest.environment === 'isolated-stack' && !results.some((result) => result.status === 'FAIL')) {
    const databaseUrl = process.env.E2E_DATABASE_URL
    if (!databaseUrl) {
      throw new Error('isolated-stack 环境需要 E2E_DATABASE_URL（只读核对）')
    }
    results = results.concat(await checkDatabaseFacts(manifest, databaseUrl))
  }
  const summary = {
    runId: manifest.runId ?? '(missing)',
    environment: manifest.environment ?? '(missing)',
    counts: {
      pass: results.filter((r) => r.status === 'PASS').length,
      fail: results.filter((r) => r.status === 'FAIL').length,
      notChecked: results.filter((r) => r.status === 'NOT_CHECKED').length,
    },
    results,
  }
  // 输出目录可能尚未创建（V16 首跑 / CI 顺序调用），writeFileSync 不自建父目录
  mkdirSync(resolve(outputPath, '..'), { recursive: true })
  writeFileSync(resolve(outputPath), `${JSON.stringify(summary, null, 2)}\n`, 'utf8')
  console.log(`runId=${summary.runId} pass=${summary.counts.pass} fail=${summary.counts.fail} notChecked=${summary.counts.notChecked}`)
  if (summary.counts.fail > 0) {
    process.exit(1)
  }
}

if (process.argv[1] && resolve(process.argv[1]).endsWith('task-103-api-check.ts')) {
  await main()
}
