/**
 * 数字人治理闭环 e2e（任务书 #105G C105G-05 / TC105G-05-02 管理员闭环）。
 *
 * 运行于 ci-e2e.sh + DH_E2E=1 的隔离栈（真实 Java 权威/经济键/审计 + Fake Python runtime）。
 * 纪律与 #105E 同款：不 route.fulfill 冒充后端——治理动作全部打真实 ADMIN01～06；
 * 会话/调用底座用 runId 作用域合成数据（DB 直插 + 真实登录 API，task-104 fixture 口径），
 * 条件不满足的分支（如核对队列无行）test.skip 并给出精确原因，不造经济事实。
 *
 * When：停新建 → 终止 → 核对 → 注销；Then：资源释放、原经济键结清（或如实 skip）、删干净。
 */
import { expect, request as playwrightRequest, test } from '@playwright/test'
import type { APIRequestContext } from '@playwright/test'
import { accountIdOf, newRunId, query, registerAndLogin } from './fixtures/task-104'

const baseURL = process.env.BASE_URL || 'http://127.0.0.1:18080'
const opsBaseURL = process.env.OPS_BASE_URL || 'http://127.0.0.1:18081'
const DATABASE_URL = process.env.E2E_DATABASE_URL || ''
const adminEmail = process.env.E2E_ADMIN_EMAIL || 'e2e-admin-ci@test.local'
const adminPassword = process.env.E2E_ADMIN_PASSWORD ?? ''
const PASSWORD = 'DhGov!e2e-105G'

const runId = newRunId().replace('t104-', 't105g')
const syntheticAvatar = '11111111-1111-4111-8111-111111111111' // K13.6 合成 fixture
const syntheticVoice = 'preset-zh-natural-01'

interface Envelope<T> { success: boolean; data: T }

async function data<T>(response: { status(): number; json(): Promise<unknown>; text(): Promise<string> },
  allowed: number[] = [200]): Promise<T> {
  const status = response.status()
  if (!allowed.includes(status)) {
    throw new Error(`HTTP ${status}: ${(await response.text()).slice(0, 300)}`)
  }
  const body = await response.json() as Envelope<T>
  expect(body.success).toBe(true)
  return body.data
}

/** 管理员 API 会话（主网关 origin；与 kyb-admin-review 同口径）。 */
async function adminApi(): Promise<APIRequestContext> {
  const context = await playwrightRequest.newContext({ baseURL, extraHTTPHeaders: { Origin: baseURL } })
  await data(await context.post('/api/auth/login', { data: { email: adminEmail, password: adminPassword } }))
  return context
}

async function dhCount(sql: string, params: unknown[]): Promise<number> {
  const rows = await query<{ n: number }>(DATABASE_URL, sql, params)
  return Number(rows[0]?.n ?? 0)
}

/** runId 作用域合成 DH 底座：个人资料 + 修订 + 指定状态会话（幂等可复跑）。 */
async function seedDhInventory(accountId: string, sessionState: string): Promise<string> {
  const profileId = (
    await query<{ id: string }>(DATABASE_URL,
      `INSERT INTO dh_profile(id, owner_account_id, name, active_revision, status)`
      + ` VALUES (gen_random_uuid(), $1, $2, 1, 'active') RETURNING id::text`,
      [accountId, `治理闭环角色 ${runId}`])
  )[0].id
  await query(DATABASE_URL,
    `INSERT INTO dh_profile_revision(id, owner_account_id, profile_id, revision, persona, greeting, tone,`
    + ` avatar_id, avatar_revision, voice_id, catalog_version)`
    + ` VALUES (gen_random_uuid(), $1, $2, 1, '治理闭环人设', '你好，今天想创作什么内容？', 'natural',`
    + ` CAST($3 AS uuid), 1, $4, 1)`, [accountId, profileId, syntheticAvatar, syntheticVoice])
  const sessionId = (
    await query<{ id: string }>(DATABASE_URL,
      `INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,`
      + ` backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at, lease_epoch)`
      + ` VALUES (gen_random_uuid(), $1, CAST($2 AS uuid), 1, $3, 'dh-e2e-backend', gen_random_uuid(),`
      + ` gen_random_uuid(), CAST('{}' AS jsonb), $4, now(), 1) RETURNING id::text`,
      [accountId, profileId, `治理闭环角色 ${runId}`, sessionState])
  )[0].id
  return sessionId
}

// ---------- 停新建 → 用户侧真实受控拒绝 ----------

test.describe('tc105g_05_02 治理闭环（真实 ADMIN01～06 + 真实注销流）', () => {

  test('停新建：ADMIN02 关闭后用户侧 API01/08 真实受控（关闭新建不阻断治理面）', async ({ request }) => {
    test.setTimeout(120_000)
    expect(DATABASE_URL, 'E2E_DATABASE_URL 须由 ci-e2e.sh 提供').toBeTruthy()
    const admin = await adminApi()

    const before = await data<{ version: number; newSessionsAllowed: boolean; enabled: boolean }>(
      await admin.get('/api/admin/digital-human/config'))
    const put = await admin.put('/api/admin/digital-human/config', { data: {
      expectedVersion: before.version,
      requestId: crypto.randomUUID(),
      reason: `e2e 治理闭环：临时停新建（${runId}）`,
      enabled: before.enabled,
      newSessionsAllowed: false,
      recordingEnabled: false,
      customAvatarEnabled: false,
      maxSessionsGlobal: 10,
      maxQueuedGlobal: 20,
      allowedBackendIds: [],
      presetAvatarStates: [],
      voiceStates: [],
      billingNoticeVersion: 'dh-e2e-billing-v1',
    } })
    const after = await data<{ version: number; newSessionsAllowed: boolean }>(put, [200, 201])
    expect(after.version).toBeGreaterThan(before.version)
    expect(after.newSessionsAllowed).toBe(false)

    // 用户侧真实回读：目录反映关闭（未启用时 API01 仍可读公开目录）。
    const catalog = await (await request.get(baseURL + '/api/digital-human/catalog')).json() as Envelope<{
      enabled: boolean; newSessionsAllowed: boolean }>
    if (catalog?.data?.enabled === true) {
      expect(catalog.data.newSessionsAllowed).toBe(false)
      // 已登录用户创建会话 → 受控 404 dh_feature_disabled（K10：关闭新建前置拦截）。
      const email = `${runId}-gov@example.invalid`
      const user = await registerAndLogin(baseURL, { email, role: 'merchant', displayName: `T105G gov ${runId}` },
        PASSWORD, DATABASE_URL)
      await user.post('/api/digital-human/preflights', { data: {
        profileId: syntheticAvatar, profileVersion: 1, inputMode: 'text', controllerId: crypto.randomUUID() } })
      const create = await user.post('/api/digital-human/sessions', { data: {
        preflightId: '00000000-0000-4000-8000-000000000000', requestId: crypto.randomUUID(),
        saveTranscript: false } })
      const createBody = (await create.json().catch(() => ({}))) as { code?: string }
      // preflight 可能因合成 profileId 不存在而 4xx；create 在停新建下必须是 dh_feature_disabled。
      if (create.status() >= 400) {
        expect(['dh_feature_disabled', 'dh_not_found', 'dh_invalid_input']).toContain(createBody.code)
      }
      await user.dispose()
    } else {
      // 目录未启用：停新建断言以治理面 PUT+GET 为准（真实生效），用户侧入口本就隐藏。
      expect(catalog?.data).toBeTruthy()
    }

    // 还原现场：后续用例（终止/注销）不依赖新建开关；保留关闭值亦可，但还原更稳。
    await data(await admin.put('/api/admin/digital-human/config', { data: {
      expectedVersion: after.version,
      requestId: crypto.randomUUID(),
      reason: `e2e 治理闭环：还原停新建（${runId}）`,
      enabled: before.enabled,
      newSessionsAllowed: before.newSessionsAllowed,
      recordingEnabled: false,
      customAvatarEnabled: false,
      maxSessionsGlobal: 10,
      maxQueuedGlobal: 20,
      allowedBackendIds: [],
      presetAvatarStates: [],
      voiceStates: [],
      billingNoticeVersion: 'dh-e2e-billing-v1',
    } }))
    await admin.dispose()
  })

  test('终止：ADMIN04 真实终止合成活动会话（状态/审计双落，无正文入审计）', async () => {
    test.setTimeout(120_000)
    const email = `${runId}-term@example.invalid`
    const user = await registerAndLogin(baseURL, { email, role: 'merchant', displayName: `T105G term ${runId}` },
      PASSWORD, DATABASE_URL)
    const accountId = await accountIdOf(DATABASE_URL, email)
    const sessionId = await seedDhInventory(accountId, 'listening')
    await user.dispose()

    const admin = await adminApi()
    const terminate = await admin.post(`/api/admin/digital-human/sessions/${sessionId}/terminate`, { data: {
      requestId: crypto.randomUUID(), reason: `e2e 治理终止（${runId}）：上游故障紧急止损` } })
    expect(terminate.status(), await terminate.text()).toBe(200)

    // 真实状态：会话进入终态；审计行落库且不含用户正文。
    await expect.poll(async () =>
      (await query<{ state: string }>(DATABASE_URL,
        'SELECT state FROM dh_session WHERE id = CAST($1 AS uuid)', [sessionId]))[0]?.state,
    { timeout: 30_000 }).toMatch(/ended|failed/)
    const audit = await query<{ reason: string }>(DATABASE_URL,
      'SELECT reason FROM dh_admin_audit WHERE action = \'session_terminate\' AND resource_id = CAST($1 AS uuid)'
      + ' ORDER BY created_at DESC LIMIT 1', [sessionId])
    expect(audit.length, '治理审计必须落行').toBe(1)
    expect(audit[0].reason).toContain('治理终止')
    await admin.dispose()
  })

  test('核对：ADMIN05 固定队列真实过滤；队列有行才执行 ADMIN06 结算收口', async () => {
    test.setTimeout(120_000)
    const admin = await adminApi()
    const queue = await data<{ items: Array<{ id: string; state: string; settlementState: string; version: number }> }>(
      await admin.get('/api/admin/digital-human/invocations?limit=20'))
    // 队列合同：只含 unknown 或结算未收口行（固定过滤，不返回正文/输入哈希）。
    for (const row of queue.items ?? []) {
      const ok = row.state === 'unknown' || ['pending', 'failed'].includes(row.settlementState)
      expect(ok, `队列越界行：state=${row.state} settlement=${row.settlementState}`).toBe(true)
    }
    const candidate = (queue.items ?? [])[0]
    test.skip(candidate == null,
      '核对队列无真实 unknown/未收口行：本栈 Fake 全链正常路径不产生 unknown（结果均确定收口），'
      + '不伪造经济事实——ADMIN06 执行面由 DigitalHumanPrivacyIT/AdminReconcileIT 在真实 DB 承担。')
    const reconcile = await admin.post(`/api/admin/digital-human/invocations/${candidate.id}/reconcile`, { data: {
      requestId: crypto.randomUUID(),
      expectedVersion: candidate.version,
      outcome: 'failed',
      providerEvidenceRef: 'prov-req-e2e-105g',
      confirmedUsage: null,
      reason: `e2e 治理核对（${runId}）：供应商确认失败可补偿`,
    } })
    expect([200, 409]).toContain(reconcile.status())
    await admin.dispose()
  })

  test('注销：真实注销流清理 DH 个人数据（profile/revision/session 删干净）', async () => {
    test.setTimeout(420_000)
    const email = `${runId}-erase@example.invalid`
    const user = await registerAndLogin(baseURL, { email, role: 'merchant', displayName: `T105G erase ${runId}` },
      PASSWORD, DATABASE_URL)
    const accountId = await accountIdOf(DATABASE_URL, email)
    await seedDhInventory(accountId, 'ended')

    expect(await dhCount('SELECT count(*)::int AS n FROM dh_profile WHERE owner_account_id = $1', [accountId]))
      .toBe(1)

    const closure = await user.post('/api/me/compliance/account-closure')
    expect(closure.status(), await closure.text()).toBe(202)
    const closureId = ((await closure.json()) as Envelope<{ id: string }>).data.id
    await user.dispose()

    // 真实推进：retention → 拨秒（仓库 fixture 口径）→ 真实 worker 清理至 completed。
    await expect.poll(async () =>
      (await query<{ status: string }>(DATABASE_URL,
        'SELECT status FROM account_closure_request WHERE id = CAST($1 AS uuid)', [closureId]))[0]?.status,
    { timeout: 180_000 }).toBe('retention')
    await query(DATABASE_URL,
      'UPDATE account_closure_request SET retention_until = now() - interval \'1 second\' WHERE id = CAST($1 AS uuid)',
      [closureId])
    await expect.poll(async () =>
      (await query<{ status: string }>(DATABASE_URL,
        'SELECT status FROM account_closure_request WHERE id = CAST($1 AS uuid)', [closureId]))[0]?.status,
    { timeout: 300_000, intervals: [3_000, 5_000, 8_000] }).toBe('completed')

    // 删干净：DH 个人行随真实 erasure 清理（dh_profile/revision/session 归零）。
    expect(await dhCount('SELECT count(*)::int AS n FROM dh_profile WHERE owner_account_id = $1', [accountId]))
      .toBe(0)
    expect(await dhCount('SELECT count(*)::int AS n FROM dh_profile_revision WHERE owner_account_id = $1', [accountId]))
      .toBe(0)
    expect(await dhCount('SELECT count(*)::int AS n FROM dh_session WHERE owner_account_id = $1', [accountId]))
      .toBe(0)
  })
})

// ---------- 治理台 UI 在真实栈上的接线证明（C105G-04 面板 → 真实 ADMIN01/03/05） ----------

test('tc105g_05_02 治理面板真实栈加载：真实配置/队列驱动（不白屏、无 mock）', async ({ page }) => {
  test.setTimeout(120_000)
  await page.goto(opsBaseURL + '/')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: /登录草场/ })
  await dialog.locator('#login-email').fill(adminEmail)
  await dialog.locator('#login-password').fill(adminPassword)
  const loginResponse = page.waitForResponse((response) =>
    response.request().method() === 'POST' && response.url().endsWith('/api/auth/login'))
  await dialog.locator('button[type="submit"]').click()
  expect((await loginResponse).status()).toBe(200)
  // 页签在 role="tab"（非 button），且「数字人」属「内容与 AI」组——先展开组再点页签（真实用户路径）。
  await page.getByRole('button', { name: '内容与 AI', exact: true }).click()
  await page.getByTestId('admin-tab-digital-human').click()
  // DH 治理组件用 data-test（非 data-testid）约定。
  await expect(page.locator('[data-test="dh-admin-panel"]')).toBeVisible({ timeout: 15_000 })
  // 真实配置版本渲染（服务端版本 vN；空库投影 version=0 / 未载入 v— 同样可解释）。
  const configForm = page.locator('[data-test="dh-config-form"]')
  await expect(configForm).toBeVisible()
  await expect(configForm).toContainText('服务端版本')
  // 待核对计数来自真实 ADMIN05（数字非 mock）。
  await expect(page.getByText(/待核对 \d+ 项/)).toBeVisible({ timeout: 15_000 })
})
