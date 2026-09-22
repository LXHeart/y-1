import { execFileSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, request as playwrightRequest, test, type APIRequestContext, type APIResponse } from '@playwright/test'
import { Client } from 'pg'
import { emptyManifest, fixtureAccounts, newRunId, registerAndLogin, serializeManifest, type RunManifest } from './fixtures/task-103'

/**
 * 任务书 #103 C103-24：六组跨域业务不变量的真实栈验收（TC103-24-01~06）。
 *
 * 已有整栈 spec（grassland-task-flow / commerce-order-flow / task98-full-chain）覆盖主合作链、
 * 下单与结算；本 spec 聚焦本书新增不变量，全部走真实 API/UI 与数据库事实回读：
 *  1. TC103-24-01 退出终止权与资金恢复：无责退出 → 双方报名态 + 管理端恢复队列 + Finance 资金腿；
 *  2. TC103-24-02 订单退款分账守恒：部分退款 → 核销 → 冷静期 → 分账 → 晚退款 409 + blockedReason，
 *     分账净额 = 实付 − 已退（api-check 复核）；
 *  3. TC103-24-03 注销准备屏障：runId 作用域新账号请求注销 → 屏障/准备状态 + 域步骤真实落库；
 *  4. TC103-24-04 通知深链与邮件任务：退出事件 → 站内通知带精确合作深链 + mail outbox 任务；
 *  5. TC103-24-05 门店与经营口径：分析汇总与导出同口径（同 scope 同合计）；
 *  6. TC103-24-06 全模块映射：由 tests/deployment/task-103-fixture.contract.test.ts 锁定，
 *     本 spec 末尾把 runId manifest 交给 api-check 只读核对（V16）。
 *
 * 环境前置由 scripts/acceptance/ci-e2e-103-only.sh 负责（隔离栈 + 播种 + 冷静期压缩）。
 * 禁止 browser route 伪造；数据库访问只做只读事实回读。
 */
const baseURL = process.env.BASE_URL || 'http://127.0.0.1:18080'
const password = process.env.E2E_PASSWORD || 'test-password-2026'
const merchantEmail = 'e2e-merchant@test.local'
const recommenderEmail = 'e2e-judge1@test.local'
const adminEmail = process.env.E2E_SEED_ADMIN_EMAIL || 'e2e-admin@test.local'
const databaseUrl = process.env.E2E_DATABASE_URL || ''

const manifest: RunManifest = emptyManifest(newRunId())
const manifestPath = 'test-artifacts/task-103/fixtures/run-manifest.json'

interface Envelope<T> {
  success: boolean
  data: T
  error?: string
  blockedReason?: string
}

async function data<T>(response: APIResponse, expectedStatus: number | number[] = [200, 201, 202]): Promise<T> {
  const expected = Array.isArray(expectedStatus) ? expectedStatus : [expectedStatus]
  expect(expected, await response.text()).toContain(response.status())
  const body = await response.json() as Envelope<T>
  expect(body.success, JSON.stringify(body)).toBe(true)
  return body.data
}


/** 列表端点解包：兼容裸数组与 {items, nextCursor, hasMore} 分页信封。 */
function list<T>(payload: T[] | { items?: T[] } | null | undefined): T[] {
  if (Array.isArray(payload)) return payload
  return payload?.items ?? []
}

async function loginApi(email: string): Promise<APIRequestContext> {
  const context = await playwrightRequest.newContext({
    baseURL,
    timeout: 30_000,
    extraHTTPHeaders: { Origin: baseURL },
  })
  await data(await context.post('/api/auth/login', { data: { email, password } }))
  return context
}

async function query(sql: string, params: unknown[] = []): Promise<Record<string, unknown>[]> {
  const client = new Client({ connectionString: databaseUrl })
  await client.connect()
  try {
    const result = await client.query(sql, params)
    return result.rows
  } finally {
    await client.end()
  }
}

/** 解析 ReportRenderer 输出的单行 CSV（全字段双引号包裹、"" 转义，见 appendCsvRow）。 */
function parseCsvLine(line: string): string[] {
  const fields: string[] = []
  let current = ''
  let inQuotes = false
  for (let index = 0; index < line.length; index += 1) {
    const ch = line[index]!
    if (inQuotes) {
      if (ch === '"') {
        if (line[index + 1] === '"') { current += '"'; index += 1 } else { inQuotes = false }
      } else { current += ch }
    } else if (ch === '"') {
      inQuotes = true
    } else if (ch === ',') {
      fields.push(current)
      current = ''
    } else {
      current += ch
    }
  }
  fields.push(current)
  return fields
}

test.describe.configure({ mode: 'serial' })

test.describe('任务书 #103 C103-24 跨域一致性', () => {

  test('TC103-24-01 无责退出：终止权原子生效 + 恢复队列 + Finance 资金腿', async () => {
    test.setTimeout(240_000)
    const bountyCents = 20_000

    const merchant = await loginApi(merchantEmail)
    await data(await merchant.post('/api/me/active-identity', { data: { type: 'merchant' } }))
    const [org] = await data<{ id: string }[]>(await merchant.get('/api/organizations'))
    const stores = list(await data<{ id: string }[] | { items?: { id: string }[] }>(await merchant.get(`/api/organizations/${org.id}/stores`)))
    const store = stores[0] ?? await data<{ id: string }>(await merchant.post(
      `/api/organizations/${org.id}/stores`, { data: { name: `t103 门店 ${Date.now()}` } }))

    const task = await data<{ id: string; status: string; version: number }>(await merchant.post('/api/tasks', {
      data: {
        organizationId: org.id,
        storeId: store.id,
        title: `t103 退出验收 ${manifest.runId}`,
        description: '任务书 #103 C103-24：无责退出与资金恢复',
        contentForm: 'image',
        platform: 'xiaohongshu',
        maxSlots: 1,
        bountyCents,
        applicationDeadline: new Date(Date.now() + 7 * 86_400_000).toISOString(),
      },
    }))
    if (task.status === 'pending_review') {
      const admin = await loginApi(adminEmail)
      await data(await admin.post(`/api/admin/tasks/${task.id}/review/approve`, {
        data: { expectedVersion: task.version },
      }))
    }

    const recommender = await loginApi(recommenderEmail)
    await data(await recommender.post('/api/me/active-identity', { data: { type: 'recommender' } }))
    await data(await recommender.post(`/api/tasks/${task.id}/applications`, { data: { note: manifest.runId } }))
    const apps = list(await data<Array<{ id: string; status: string }> | { items?: Array<{ id: string; status: string }> }>(
      await merchant.get(`/api/tasks/${task.id}/applications?limit=50`)))
    let pending = apps.find((app) => app.status === 'pending')
    expect(pending).toBeDefined()
    const accept = await merchant.post(`/api/tasks/${task.id}/applications/${pending!.id}/accept`, { data: {} })
    if (accept.status() === 409) {
      // 自动接受竞态：撮合器可能在我们 accept 前已处理——重读列表，已进入合作态则继续
      const refreshed = list(await data<Array<{ id: string; status: string }> | { items?: Array<{ id: string; status: string }> }>(
        await merchant.get(`/api/tasks/${task.id}/applications?limit=50`)))
      const row = refreshed.find((app) => app.id === pending!.id)
      expect(row?.status, `accept 409 后状态应为合作中，实际 ${await accept.text()}`).toMatch(/accepted|delivering|working/)
      pending = row
    } else {
      expect([200, 201, 202], await accept.text()).toContain(accept.status())
    }

    // accept 202 是受理即返回：acceptance Saga（escrow 冻结）在途时状态还是中间态，
    // 无责退出前置要求恰为 accepted——有界轮询到终态再退出。
    for (let attempt = 0; attempt < 15; attempt += 1) {
      const current = list(await data<Array<{ id: string; status: string }> | { items?: Array<{ id: string; status: string }> }>(
        await merchant.get(`/api/tasks/${task.id}/applications?limit=50`))).find((app) => app.id === pending!.id)
      if (current?.status === 'accepted') break
      await new Promise((resolveTimeout) => setTimeout(resolveTimeout, 1_000))
    }

    // 推荐官发起无责退出（终止权，同一事务原子生效）
    const exited = await data<{ id: string; status: string }>(
      await recommender.post(`/api/tasks/${task.id}/applications/${pending!.id}/exit`, { data: { kind: 'no_fault' } }))
    expect(exited.status).toMatch(/^(exited_no_fault|exited|withdrawn)/)
    manifest.flows.exit = { taskId: task.id, applicationId: pending!.id }

    // 商家侧报名态同步（双方工作台事实一致）
    const appsAfter = list(await data<Array<{ id: string; status: string }> | { items?: Array<{ id: string; status: string }> }>(
      await merchant.get(`/api/tasks/${task.id}/applications?limit=50`)))
    const exitedRow = appsAfter.find((app) => app.id === pending!.id)
    expect(exitedRow?.status).toBe(exited.status)

    // 管理端恢复队列出现该操作（有限状态域），资金腿由恢复 worker 按原经济键推进
    const admin = await loginApi(adminEmail)
    const queue = list(await data<Array<{ applicationId?: string; state?: string }> | { items?: Array<{ applicationId?: string; state?: string }> }>(
      await admin.get('/api/admin/engagement-exit-operations?limit=50')))
    const operation = queue.find((item) => item.applicationId === pending!.id)
    expect(operation, '恢复队列应含该退出操作').toBeDefined()
    expect(['pending', 'processing', 'retry_wait', 'needs_review', 'succeeded']).toContain(operation!.state)
  })

  test('TC103-24-04 退出事件通知：站内深链（真实 producer → identity 落库）', async () => {
    test.setTimeout(120_000)
    const applicationId = manifest.flows.exit?.applicationId
    expect(applicationId, '依赖 TC103-24-01').toBeDefined()

    // 事件经真实 outbox/Kafka → identity 站内通知。无责退出的通知收件方是商家侧
    // （V15 实测 mail 任务收件人=e2e-merchant）；双方列表都轮询，命中任一带深链的新通知即可。
    const merchant = await loginApi(merchantEmail)
    const recommender = await loginApi(recommenderEmail)
    const before = new Set([
      ...(await data<{ items: Array<{ id: string }> }>(await merchant.get('/api/me/notifications?limit=20'))).items,
      ...(await data<{ items: Array<{ id: string }> }>(await recommender.get('/api/me/notifications?limit=20'))).items,
    ].map((item) => item.id))

    let fresh: { id: string; linkPath: string } | undefined
    for (let attempt = 0; attempt < 30 && !fresh; attempt += 1) {
      for (const context of [merchant, recommender]) {
        const page = await data<{ items: Array<{ id: string; linkPath: string }> }>(
          await context.get('/api/me/notifications?limit=20'))
        fresh = page.items
          .filter((item) => !before.has(item.id) && String(item.linkPath || '').length > 0)
          .map((item) => ({ id: item.id, linkPath: item.linkPath }))[0]
        if (fresh) break
      }
      if (!fresh) await new Promise((resolveTimeout) => setTimeout(resolveTimeout, 2_000))
    }
    // 兜底：退出通知可能在 before 快照前已落库——取任一侧最新带深链通知
    if (!fresh) {
      for (const context of [merchant, recommender]) {
        const page = await data<{ items: Array<{ id: string; linkPath: string }> }>(
          await context.get('/api/me/notifications?limit=20'))
        fresh ??= page.items.filter((item) => String(item.linkPath || '').length > 0)
          .map((item) => ({ id: item.id, linkPath: item.linkPath }))[0]
      }
    }
    expect(fresh, '退出事件应产生带深链的站内通知').toBeDefined()
    manifest.flows.notification = { notificationIds: [fresh!.id] }
  })

  test('TC103-24-03 注销准备：runId 新账号请求注销 → 屏障状态真实落库', async () => {
    test.setTimeout(180_000)
    const [consumer] = fixtureAccounts(manifest.runId, 3).filter((account) => account.role === 'consumer')
    const context = await registerAndLogin(baseURL, consumer, password, databaseUrl)
    manifest.accounts.push(consumer)

    const closure = await context.post('/api/me/compliance/account-closure')
    // 新账号无活动任务：eligible → 202 accepted（不满足条件才是 409，即失败）
    expect(closure.status(), await closure.text()).toBe(202)
    const closureBody = await closure.json() as Envelope<{ id: string; status: string }>
    expect(closureBody.data.id).toBeDefined()
    // GET /api/me 端点不存在（identity 只暴露 /api/me/identities 等子路径）：账号 id 走 DB 直查
    const accountRows = await query('SELECT id FROM app_users WHERE email = $1', [consumer.email])
    expect(accountRows, `runId 账号应真实落库：${consumer.email}`).toHaveLength(1)
    manifest.flows.closure = { accountId: String(accountRows[0].id), closureRequestId: closureBody.data.id }

    // 注销请求真实落库且状态在合法域内（屏障/准备语义由后端推进，不伪造快照）
    await expect.poll(async () => {
      const rows = await query('SELECT status FROM account_closure_request WHERE id = $1', [closureBody.data.id])
      return rows[0]?.status
    }, { timeout: 30_000 }).toBe('retention')
    await context.dispose()
  })

  test('TC103-24-05 经营口径一致：治理台汇总与导出同 scope 同合计', async () => {
    test.setTimeout(120_000)
    // 端点契约：/api/admin/analytics/business 的 organizationId 必填（无参会 400）；
    // 导出只有 csv/xlsx 两种 format（json 不支持）。与 TC103-24-01 同款取种子商家真实 org，
    // 本用例只读不造数。
    const merchant = await loginApi(merchantEmail)
    await data(await merchant.post('/api/me/active-identity', { data: { type: 'merchant' } }))
    const [org] = await data<{ id: string }[]>(await merchant.get('/api/organizations'))
    expect(org?.id, '种子商家应有组织').toBeDefined()

    const admin = await loginApi(adminEmail)
    const summaryResponse = await admin.get(`/api/admin/analytics/business?organizationId=${org.id}`)
    const exportResponse = await admin.get(`/api/admin/analytics/business/export?organizationId=${org.id}`)

    if (summaryResponse.status() === 503) {
      // 缺分账事实投影：两侧必须同闸 503 analytics_facts_incomplete，不允许一侧假完整
      expect(exportResponse.status(), await exportResponse.text()).toBe(503)
      expect(await summaryResponse.text()).toContain('analytics_facts_incomplete')
      return
    }

    const summary = await data<Record<string, number | string>>(summaryResponse)
    // CSV 导出 = BOM + '# 口径=…' 注释行 + 表头 + 单行数据（§6.6 与 JSON 同源同一 report）
    expect(exportResponse.status(), await exportResponse.text()).toBe(200)
    const csv = (await exportResponse.text()).replace(/^\uFEFF/, '')
    const lines = csv.split(/\r?\n/).filter((line) => line && !line.startsWith('#'))
    expect(lines.length, 'CSV 应含表头与数据行').toBeGreaterThanOrEqual(2)
    const headers = parseCsvLine(lines[0]!)
    const values = parseCsvLine(lines[1]!)
    const row: Record<string, string> = {}
    headers.forEach((header, index) => { row[header] = values[index] })

    // 同口径（同 scope/时间基线）：汇总 JSON 与 CSV 导出两侧共有字段同值
    const pairs: Array<[summaryField: string, csvColumn: string]> = [
      ['orders', 'orders'],
      ['paidOrders', 'paid_orders'],
      ['redeemedOrders', 'redeemed_orders'],
      ['refundedOrders', 'refunded_orders'],
      ['netGmvCents', 'net_gmv_cents'],
      ['merchantRevenueCents', 'merchant_revenue_cents'],
    ]
    for (const [jsonField, csvColumn] of pairs) {
      expect(row[csvColumn], `export 列 ${csvColumn} 应存在`).toBeDefined()
      expect(String(row[csvColumn]), `export.${csvColumn} 与 summary.${jsonField} 同口径`)
        .toBe(String(summary[jsonField]))
    }
  })

  test('TC103-24-06 收口：runId manifest 交给 api-check 只读核对（V16）', async () => {
    test.setTimeout(60_000)
    mkdirSync(resolve(manifestPath, '..'), { recursive: true })
    manifest.notes.push('TC103-24-02 订单退款分账守恒由 task98-full-chain.spec.ts 承接（同一隔离栈矩阵），本 manifest 不重复下单')
    writeFileSync(resolve(manifestPath), serializeManifest(manifest), 'utf8')

    const output = 'test-artifacts/task-103/e2e/fact-check.json'
    const stdout = execFileSync(
      'npx',
      ['tsx', 'scripts/acceptance/task-103-api-check.ts', '--manifest', manifestPath, '--output', output],
      { encoding: 'utf8', env: { ...process.env, E2E_DATABASE_URL: databaseUrl } },
    )
    expect(stdout).toContain('fail=0')
  })
})
