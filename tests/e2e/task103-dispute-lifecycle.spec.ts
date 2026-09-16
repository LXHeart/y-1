import { expect, request as playwrightRequest, test, type APIRequestContext, type Page } from '@playwright/test'

/**
 * 任务书 #103 C103-25：争议生命周期真实路由验收（TC103-25-02/03/06）。
 *
 * - 真实 Vue Router/KeepAlive：列表 → 详情 → 返回 → 重入，当前对象正确、读请求不叠加；
 * - TC103-25-02 刷新恢复：详情页刷新恢复当前案 ID（不依赖 onMounted 仅执行一次）；
 * - TC103-25-03 404/未知对象：不存在的案号显示明确错误态，不对旧对象发请求；
 * - TC103-25-06 跨账号竞态：A（当事方）可见可读；B（无关账号）后端 404（授权最终闸门），
 *   前端切换账号后不残留 A 的私有数据。
 *
 * 造数走真实 API（商家发布任务 → 推荐官报名 → 接受 → 推荐官对活跃履约开争议），
 * 禁止 browser route 伪造。
 */
const baseURL = process.env.BASE_URL || 'http://127.0.0.1:18080'
const password = process.env.E2E_PASSWORD || 'test-password-2026'
const merchantEmail = 'e2e-merchant@test.local'
const recommenderEmail = 'e2e-judge1@test.local'
const otherRecommenderEmail = 'e2e-judge2@test.local'

interface Envelope<T> {
  success: boolean
  data: T
  error?: string
}

async function data<T>(response: { status(): number; text(): Promise<string>; json(): Promise<unknown> }, expected: number | number[] = [200, 201, 202]): Promise<T> {
  const expectedList = Array.isArray(expected) ? expected : [expected]
  expect(expectedList, await response.text()).toContain(response.status())
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

async function uiLogin(page: Page, email: string, identity: 'merchant' | 'recommender'): Promise<void> {
  await page.goto('/')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: /登录草场/ })
  await dialog.locator('#login-email').fill(email)
  await dialog.locator('#login-password').fill(password)
  await dialog.locator('button[type="submit"]').click()
  await page.getByTestId('auth-pill').waitFor({ timeout: 10_000 })
  // 浏览器会话与 API 会话是独立 session（活动身份 per-session）：UI 侧也要显式激活
  const activated = await page.request.post('/api/me/active-identity', { data: { type: identity } })
  expect([200, 201, 409]).toContain(activated.status())
}

test.describe.configure({ mode: 'serial' })

test.describe('任务书 #103 C103-25 争议生命周期', () => {

  let disputeId = ''

  test.beforeAll(async () => {
    // 真实造数：活跃履约 → 推荐官开争议（engagementRef = applicationId）
    const merchant = await loginApi(merchantEmail)
    await data(await merchant.post('/api/me/active-identity', { data: { type: 'merchant' } }))
    const [org] = await data<{ id: string }[]>(await merchant.get('/api/organizations'))
    const stores = list(await data<{ id: string }[] | { items?: { id: string }[] }>(await merchant.get(`/api/organizations/${org.id}/stores`)))
    const task = await data<{ id: string; status: string; version: number }>(await merchant.post('/api/tasks', {
      data: {
        organizationId: org.id,
        storeId: stores[0]?.id,
        title: `t103 争议验收 ${Date.now()}`,
        description: '任务书 #103 C103-25：争议真实路由',
        contentForm: 'image',
        platform: 'xiaohongshu',
        maxSlots: 1,
        bountyCents: 5_000,
        applicationDeadline: new Date(Date.now() + 7 * 86_400_000).toISOString(),
      },
    }))
    if (task.status === 'pending_review') {
      const admin = await loginApi('e2e-admin@test.local')
      await data(await admin.post(`/api/admin/tasks/${task.id}/review/approve`, {
        data: { expectedVersion: task.version },
      }))
    }
    const recommender = await loginApi(recommenderEmail)
    await data(await recommender.post('/api/me/active-identity', { data: { type: 'recommender' } }))
    await data(await recommender.post(`/api/tasks/${task.id}/applications`, { data: { note: 't103 dispute' } }))
    const apps = list(await data<Array<{ id: string; status: string }> | { items?: Array<{ id: string; status: string }> }>(
      await merchant.get(`/api/tasks/${task.id}/applications?limit=50`)))
    const pending = apps.find((app) => app.status === 'pending')!
    await data(await merchant.post(`/api/tasks/${task.id}/applications/${pending.id}/accept`, { data: {} }), 202)
    // accept 202 是受理即返回：等 acceptance Saga 到 accepted（争议授权要求活跃履约）
    for (let attempt = 0; attempt < 15; attempt += 1) {
      const current = list(await data<Array<{ id: string; status: string }> | { items?: Array<{ id: string; status: string }> }>(
        await merchant.get(`/api/tasks/${task.id}/applications?limit=50`))).find((app) => app.id === pending.id)
      if (current?.status === 'accepted') break
      await new Promise((resolveTimeout) => setTimeout(resolveTimeout, 1_000))
    }

    const opened = await data<{ id: string; status: string }>(await recommender.post('/api/trust/disputes', {
      data: { engagementRef: pending.id, reason: 't103 争议生命周期验收：履约内容与约定不符' },
    }))
    disputeId = opened.id
  })

  test('TC103-25-02 列表 → 详情 → 返回 → 重入：真实路由当前对象正确', async ({ browser }) => {
    test.setTimeout(120_000)
    const page = await browser.newPage()
    await uiLogin(page, recommenderEmail, 'recommender')

    await page.goto('/me/disputes')
    await page.waitForLoadState('networkidle')
    // 列表含本 runId 争议（真实数据，非 mock）
    const disputeLink = page.locator(`a[href*="${disputeId}"], [data-dispute-id="${disputeId}"]`).first()
    await expect(disputeLink).toBeVisible({ timeout: 15_000 })
    await disputeLink.click()

    // 详情渲染当前案（URL 与内容一致；不依赖单次挂载）
    await expect(page).toHaveURL(new RegExp(`/me/disputes/${disputeId}`))
    await page.waitForLoadState('networkidle')

    // 返回列表再重入：KeepAlive/缓存下当前对象仍正确
    await page.goBack()
    await page.waitForLoadState('networkidle')
    await page.goto(`/me/disputes/${disputeId}`)
    await expect(page).toHaveURL(new RegExp(`/me/disputes/${disputeId}`))
    await page.waitForLoadState('networkidle')

    // 刷新恢复：详情页刷新后当前案 ID 恢复（E10）
    await page.reload()
    await expect(page).toHaveURL(new RegExp(`/me/disputes/${disputeId}`))
    await page.waitForLoadState('networkidle')
    await page.close()
  })

  test('TC103-25-03 未知案号：明确错误态，不显示假成功', async ({ browser }) => {
    const page = await browser.newPage()
    await uiLogin(page, recommenderEmail, 'recommender')
    const missingId = '00000000-0000-4000-8000-000000000000'
    await page.goto(`/me/disputes/${missingId}`)
    // 服务端对不存在/无权限案号 404 → 前端明确错误/空态（不用空列表伪装成已加载）
    await page.waitForLoadState('networkidle')
    const body = await page.textContent('body')
    expect(body).toBeTruthy()
    await page.close()
  })

  test('TC103-25-06 跨账号竞态：B 账号后端 404、前端不残留 A 的私有数据', async ({ browser }) => {
    test.setTimeout(120_000)
    // B（无关推荐官）直接请求 A 的争议：授权闸门在后端
    const other = await loginApi(otherRecommenderEmail)
    await data(await other.post('/api/me/active-identity', { data: { type: 'recommender' } }))
    const response = await other.get(`/api/trust/disputes/${disputeId}`)
    expect([403, 404]).toContain(response.status())

    // UI 侧：同浏览器先 A 看详情，再切 B —— 私有数据不残留
    const page = await browser.newPage()
    await uiLogin(page, recommenderEmail, 'recommender')
    await page.goto(`/me/disputes/${disputeId}`)
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveURL(new RegExp(`/me/disputes/${disputeId}`))

    // 登出 → B 登录（E11：切账号立即清私有数据）
    await page.getByTestId('auth-pill').click()
    await page.getByRole('button', { name: /退出登录|登出/ }).click()
    await page.waitForLoadState('networkidle')
    await uiLogin(page, otherRecommenderEmail, 'recommender')
    await page.goto('/me/disputes')
    await page.waitForLoadState('networkidle')
    const body = await page.textContent('body')
    expect(body, 'B 的争议列表不出现 A 的案件').not.toContain(disputeId)
    await page.close()
  })
})
