import { expect, request as playwrightRequest, test, type APIRequestContext, type APIResponse, type Page } from '@playwright/test'

/**
 * 草场任务主流程 e2e（盘点缺口：核心流程此前无 e2e 覆盖）。
 *
 * 覆盖用户主路径：商家发布任务（API 造数）→ 内容审核过审（API）→ 推荐官任务大厅报名（UI）→
 * 商家接受报名（UI）→ 推荐官「我的履约」看到已接受（UI）。
 * 资金结算/争议等深链路由 marketplace/trust IT 与 ops e2e 覆盖，本 spec 只锁主路径的用户可见状态。
 *
 * 前置：`npm run e2e:seed`（幂等）——商家 e2e-merchant（finance_transaction tier）、
 * 推荐官复用 e2e-judge1（种子为审判官建的 recommender 身份）、管理员 e2e-admin（审核门禁）。
 */
const baseURL = process.env.BASE_URL || 'http://127.0.0.1:18080'
const password = process.env.E2E_PASSWORD || 'test-password-2026'
const merchantEmail = 'e2e-merchant@test.local'
const recommenderEmail = 'e2e-judge1@test.local'
const adminEmail = 'e2e-admin@test.local'

interface Envelope<T> {
  success: boolean
  data: T
  error?: string
}

async function data<T>(response: APIResponse, expectedStatus: number | number[] = [200, 201]): Promise<T> {
  const expected = Array.isArray(expectedStatus) ? expectedStatus : [expectedStatus]
  expect(expected, await response.text()).toContain(response.status())
  const body = await response.json() as Envelope<T>
  expect(body.success).toBe(true)
  return body.data
}

async function loginApi(email: string): Promise<APIRequestContext> {
  const context = await playwrightRequest.newContext({
    baseURL,
    extraHTTPHeaders: { Origin: baseURL },
  })
  await data(await context.post('/api/auth/login', { data: { email, password } }))
  return context
}

async function activateIdentity(context: APIRequestContext, type: 'merchant' | 'recommender'): Promise<void> {
  await data(await context.post('/api/me/active-identity', { data: { type } }))
}

async function uiLogin(page: Page, email: string): Promise<void> {
  await page.goto('/')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: /登录后管理/ })
  await dialog.locator('#login-email').fill(email)
  await dialog.locator('#login-password').fill(password)
  await dialog.locator('button[type="submit"]').click()
  await expect(page.getByText('已登录', { exact: true })).toBeVisible()
}

async function openGrassland(page: Page): Promise<void> {
  await page.getByRole('button', { name: '草场', exact: true }).first().click()
  await expect(page.locator('#gl-task-hall, .gl-card').first()).toBeVisible()
}

test.describe('草场任务主流程', () => {
  test('商家发布（过审）→ 推荐官报名 → 商家接受 → 双方可见已接受', async ({ browser }) => {
    test.setTimeout(120_000)
    const taskTitle = `e2e 主流程任务 ${Date.now()}`

    // ---- 造数：商家发布非资金型任务（bounty 缺省，无需资金闸门），管理员过审 ----
    const merchant = await loginApi(merchantEmail)
    await activateIdentity(merchant, 'merchant')
    const [org] = await data<{ id: string }[]>(await merchant.get('/api/organizations'))
    expect(org?.id).toBeTruthy()
    const task = await data<{ id: string; status: string }>(await merchant.post('/api/tasks', {
      data: {
        organizationId: org.id,
        title: taskTitle,
        description: 'e2e 主流程冒烟任务',
        contentForm: 'image',
        platform: 'xiaohongshu',
        maxSlots: 3,
      },
    }))
    // 审核策略按商家历史决定：全新商家全审（pending_review，需管理员过审）；
    // 有大量已发布历史的商家（如种子灌过声誉任务）免审直接 published。两种路径都走通。
    if (task.status === 'pending_review') {
      const admin = await loginApi(adminEmail)
      await data(await admin.post(`/api/admin/tasks/${task.id}/review/approve`, {
        data: { expectedVersion: 1 },
      }))
    }
    expect(['pending_review', 'published']).toContain(task.status)

    // ---- 推荐官：大厅报名（UI）----
    const recommenderContext = await browser.newContext({ baseURL })
    const recommenderPage = await recommenderContext.newPage()
    await uiLogin(recommenderPage, recommenderEmail)
    // UI 登录后的会话 cookie 与 page.request 共享，直接激活 recommender 活动身份。
    await recommenderPage.request.post('/api/me/active-identity', { data: { type: 'recommender' } })
    await openGrassland(recommenderPage)

    const hall = recommenderPage.locator('#gl-task-hall')
    const taskRow = hall.getByRole('button', { name: taskTitle, exact: true })
    await expect(taskRow).toBeVisible()
    await taskRow.click()
    // 行级定位：大厅可能残留此前运行发布的任务（多个「报名」按钮触发 strict 冲突）；
    // 按钮在选中/加载期间会被短暂禁用，等它可用再点。
    const applyButton = hall.getByRole('row', { name: new RegExp(taskTitle) })
      .getByRole('button', { name: '报名', exact: true })
    await expect(applyButton).toBeEnabled()
    await applyButton.click()
    await expect(recommenderPage.getByText('报名已提交，等待商家处理')).toBeVisible()

    // 「我的履约与争议」按选中任务加载报名列表，报名后重选任务刷新即可见。
    await taskRow.click()
    const engagement = recommenderPage.locator('article', { hasText: '我的履约与争议' })
    await expect(engagement.getByText('待处理').first()).toBeVisible({ timeout: 15_000 })

    // ---- 商家：报名列表接受（UI；非资金型 accept 直接 200，无 Saga 轮询窗口）----
    const merchantContext = await browser.newContext({ baseURL })
    const merchantPage = await merchantContext.newPage()
    await uiLogin(merchantPage, merchantEmail)
    await merchantPage.request.post('/api/me/active-identity', { data: { type: 'merchant' } })
    await openGrassland(merchantPage)

    // 组织/任务链在含大量种子任务的栈上加载较慢；重挂载一次并放宽断言超时，
    // 避开「initForAccount 中途某环失败导致列表未刷」的偶发竞态。
    await merchantPage.reload()
    await merchantPage.getByRole('button', { name: '草场', exact: true }).first().click()
    const merchantTaskRow = merchantPage.getByRole('button', { name: taskTitle, exact: true }).first()
    await expect(merchantTaskRow).toBeVisible({ timeout: 20_000 })
    await merchantTaskRow.click()
    const acceptButton = merchantPage.getByRole('button', { name: '接受', exact: true })
    await expect(acceptButton).toBeVisible()
    await acceptButton.click()
    await expect(merchantPage.getByText('已接受').first()).toBeVisible()

    // ---- 推荐官侧刷新后看到已接受 ----
    await recommenderPage.reload()
    await openGrassland(recommenderPage)
    await expect(
      recommenderPage.locator('article', { hasText: '我的履约与争议' }).getByText('已接受').first(),
    ).toBeVisible()

    await recommenderContext.close()
    await merchantContext.close()
  })
})
