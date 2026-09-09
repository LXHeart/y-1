import { expect, request as playwrightRequest, test, type APIRequestContext, type APIResponse, type Page } from '@playwright/test'

/**
 * 任务书 #98 C98-06：真实栈整栈集成验收（收 #96 C96-06 / #93 整栈回归两笔验收债）。
 *
 * 覆盖（AC-98-25~30）：
 *  1. 资金型任务全链状态机：发布（审稿/期限）→ 报名（UI）→ 接受（escrow 冻结）→
 *     草稿送审（自动核验）→ 商家验收（confirm）→ 观察期（争议窗）→ 结算（settled），无人工改库；
 *  2. rlid 归因链端到端：套餐+推广任务 → 服务端发放 rlid → 消费者经链接触达下单 → 归因解释可查；
 *  3. 退款边界两口径：结算前可退（200/refunded）、#97 已结算拒（409 + blockedReason 口径）；
 *  4. 三端工作台待办回归：推荐官（报名/履约中/已完成）、商家（已接受/任务进度）、消费者（订单状态）；
 *  5. 金额守恒对账（任务/订单/钱包/声誉四域，对齐 #93 AC-38）：
 *     商家组织余额 Δ=-bounty（escrow）｜推荐官钱包入账=bounty+声誉加成｜订单三方分配=实付｜
 *     commerce 分账后组织 Δ=merchantAmount、推荐官 Δ=recommenderAmount｜声誉 completedCount 递增。
 *
 * 环境前置（CI 变体 scripts/acceptance/ci-e2e-98-only.sh 负责）：
 *  - MARKETPLACE_SETTLEMENT_DISPUTE_WINDOW_SECONDS=0（观察期即刻过）
 *  - CONFIRMATION_WINDOW_SECONDS=5
 *  - MARKETPLACE_COMMERCE_SPLIT_COOLDOWN_SECONDS_OVERRIDE=15（commerce 冷静期缩到秒级）
 *  - `npm run e2e:seed`（幂等；e2e-merchant 为 finance_transaction tier、e2e-judge1/2 有推荐官身份）
 * 本地 macOS 跑不了完整栈：本 spec 只在 CI/长跑环境执行（§12.2 V98-06）。
 */
const baseURL = process.env.BASE_URL || 'http://127.0.0.1:18080'
const password = process.env.E2E_PASSWORD || 'test-password-2026'
const merchantEmail = 'e2e-merchant@test.local'
const recommenderEmail = 'e2e-judge1@test.local'
// 种子无独立消费者账号——下单侧复用 judge1（与 commerce-order-flow 同款；test 1 用 judge1 作
// 推荐官、test 2 用 judge2，互不撞角色）。
const consumerEmail = 'e2e-judge1@test.local'

interface Envelope<T> {
  success: boolean
  data: T
  error?: string
  blockedReason?: string
}

async function data<T>(response: APIResponse, expectedStatus: number | number[] = [200, 201]): Promise<T> {
  const expected = Array.isArray(expectedStatus) ? expectedStatus : [expectedStatus]
  expect(expected, await response.text()).toContain(response.status())
  const body = await response.json() as Envelope<T>
  expect(body.success, JSON.stringify(body)).toBe(true)
  return body.data
}

async function raw<T>(response: APIResponse): Promise<Envelope<T>> {
  return await response.json() as Envelope<T>
}

async function loginApi(email: string): Promise<APIRequestContext> {
  const context = await playwrightRequest.newContext({
    baseURL,
    // 本地长跑环境任务创建会触发审核采样/AI 检查失败重试，10s 默认超时不够（CI 弱机同理）。
    timeout: 30_000,
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
  const dialog = page.getByRole('dialog', { name: /登录草场/ })
  await dialog.locator('#login-email').fill(email)
  await dialog.locator('#login-password').fill(password)
  await dialog.locator('button[type="submit"]').click()
  await page.getByTestId('auth-pill').waitFor({ timeout: 10_000 })
}

async function openGrassland(page: Page): Promise<void> {
  await page.locator('[data-testid="nav-workbench"]').click()
  await expect(page.locator('#gl-task-hall:visible, #gl-engagements:visible, .gl-card:visible').first())
    .toBeVisible({ timeout: 30_000 })
}

async function pollUntil<T>(description: string, read: () => Promise<T>, ok: (value: T) => boolean,
  timeoutMs = 120_000, intervalMs = 3_000): Promise<T> {
  const deadline = Date.now() + timeoutMs
  let last: T
  for (;;) {
    last = await read()
    if (ok(last)) return last
    if (Date.now() >= deadline) throw new Error(`pollUntil 超时（${description}）：${JSON.stringify(last)}`)
    await new Promise(resolve => setTimeout(resolve, intervalMs))
  }
}

interface MyApplication {
  id: string
  recommenderAccountId: string
  status: string
  commissionBonusBpsAtAccept: number | null
}

interface MyApplicationsItem {
  applicationId: string
  applicationStatus: string
  settledAt: string | null
}

/** 商家视角读指定任务的报名列表（分页信封 {items,...}），返回该推荐官的报名行。 */
async function findApplication(merchant: APIRequestContext, taskId: string,
  recommenderId: string): Promise<MyApplication | undefined> {
  const body = await raw<{ items?: MyApplication[] } | MyApplication[]>(
    await merchant.get(`/api/tasks/${taskId}/applications?limit=50`))
  expect(body.success, JSON.stringify(body)).toBe(true)
  const items = Array.isArray(body.data) ? body.data : (body.data.items || [])
  return items.find(app => app.recommenderAccountId === recommenderId)
}

/** 推荐官「我的任务」投影：settledAt 非空 = 结算完成（卡 D 口径）。 */
async function findMyApplication(recommender: APIRequestContext,
  applicationId: string): Promise<MyApplicationsItem | undefined> {
  const body = await raw<{ items?: MyApplicationsItem[] } | MyApplicationsItem[]>(
    await recommender.get('/api/tasks/my-applications?limit=50'))
  const items = Array.isArray(body.data) ? body.data : (body.data.items || [])
  return items.find(item => item.applicationId === applicationId)
}

/** AC-98-30：双主题关键页截图（E2E_SHOT_DIR 传入才落盘；缺省零开销，不影响断言时序）。 */
async function dualThemeShot(page: Page, name: string, ready: () => Promise<void>): Promise<void> {
  const dir = process.env.E2E_SHOT_DIR
  if (!dir) return
  await ready()
  await page.screenshot({ path: `${dir}/${name}-dark.png`, fullPage: true })
  await page.evaluate(() => localStorage.setItem('theme-preference', 'light'))
  await page.reload()
  await ready()
  await page.screenshot({ path: `${dir}/${name}-light.png`, fullPage: true })
  await page.evaluate(() => localStorage.setItem('theme-preference', 'dark'))
  await page.reload()
  await ready()
}

test.describe.configure({ mode: 'serial' })

test.describe('任务书 #98 整栈集成验收', () => {

  test('AC-98-25/28/29：资金型任务全链状态机 + 三端待办 + 四域金额守恒', async ({ browser }) => {
    test.setTimeout(420_000)
    const bountyCents = 20_000
    const taskTitle = `e2e98 全链任务 ${Date.now()}`

    // ---- 商家：发布资金型任务（bounty 需 finance_transaction tier；含期限字段）----
    const merchant = await loginApi(merchantEmail)
    await activateIdentity(merchant, 'merchant')
    const [org] = await data<{ id: string }[]>(await merchant.get('/api/organizations'))
    const stores = await data<{ id: string }[]>(await merchant.get(`/api/organizations/${org.id}/stores`))
    const store = stores[0] || await data<{ id: string }>(await merchant.post(
      `/api/organizations/${org.id}/stores`, { data: { name: `e2e98 门店 ${Date.now()}` } }))
    const task = await data<{ id: string; status: string; version: number; bountyCents: number }>(
      await merchant.post('/api/tasks', {
        data: {
          organizationId: org.id,
          storeId: store.id,
          title: taskTitle,
          description: '任务书 #98 整栈验收：发布→结算全链',
          contentForm: 'image',
          platform: 'xiaohongshu',
          maxSlots: 1,
          bountyCents,
          applicationDeadline: new Date(Date.now() + 7 * 86_400_000).toISOString(),
        },
      }))
    expect(task.bountyCents).toBe(bountyCents)
    if (task.status === 'pending_review') {
      const admin = await loginApi('e2e-admin@test.local')
      await data(await admin.post(`/api/admin/tasks/${task.id}/review/approve`, {
        data: { expectedVersion: task.version },
      }))
    }

    // ---- 钱包/声誉基线（守恒对账的 before 读数）----
    const recommender = await loginApi(recommenderEmail)
    await activateIdentity(recommender, 'recommender')
    const recMe = await data<{ user: { id: string } }>(await recommender.get('/api/auth/me'))
    const recommenderId = recMe.user.id
    const balanceBefore = await data<{ balanceCents: number }>(await merchant.get(`/api/finance/accounts/${org.id}`))
    const walletBefore = await data<{ balanceCents: number }>(await recommender.get('/api/finance/wallets/me'))
    const reputationBefore = await data<{ completedCount?: number }>(await recommender.get(`/api/reputation/${recommenderId}`))
    const completedBefore = reputationBefore.completedCount ?? 0

    // ---- 推荐官：任务大厅报名（UI，三端待办之一）----
    const recommenderContext = await browser.newContext({ baseURL })
    const recommenderPage = await recommenderContext.newPage()
    await uiLogin(recommenderPage, recommenderEmail)
    await recommenderPage.request.post('/api/me/active-identity', { data: { type: 'recommender' } })
    await openGrassland(recommenderPage)
    const taskRow = recommenderPage.locator('#gl-task-hall').getByRole('button', { name: taskTitle, exact: true })
    // CI 弱机/本地长跑：大厅 feed 含种子任务列表初始化链慢，放宽到 30s（既有 spec 同款）。
    await expect(taskRow).toBeVisible({ timeout: 30_000 })
    await taskRow.click()
    const detailModal = recommenderPage.getByRole('dialog', { name: taskTitle })
    await expect(detailModal).toBeVisible()
    await detailModal.getByRole('button', { name: '报名', exact: true }).click()
    await expect(recommenderPage.getByText('报名已提交，等待商家处理')).toBeVisible()
    await detailModal.getByRole('button', { name: '关闭弹窗' }).click()

    // ---- 商家：接受（API；资金型走 202 reserving→Saga escrow）----
    const pendingApp = await pollUntil('报名进列', async () =>
      findApplication(merchant, task.id, recommenderId), app => Boolean(app))
    await data(await merchant.post(`/api/tasks/${task.id}/applications/${pendingApp!.id}/accept`, { data: {} }), 202)
    const acceptedApp = await pollUntil('escrow 完成报名转 accepted', async () =>
      findApplication(merchant, task.id, recommenderId), app => app?.status === 'accepted') as MyApplication
    // escrow 冻结即时扣组织余额（原子条件扣）——任务域资金第一步。
    const balanceAfterEscrow = await data<{ balanceCents: number }>(
      await merchant.get(`/api/finance/accounts/${org.id}`))
    expect(balanceAfterEscrow.balanceCents - balanceBefore.balanceCents).toBe(-bountyCents)

    // 推荐官侧「我的任务」见履约中（UI 待办回归）。
    await recommenderPage.getByRole('tab', { name: '我的任务' }).click()
    const myTasks = recommenderPage.locator('article', { hasText: '我的任务' })
    await expect(myTasks.getByRole('row', { name: new RegExp(taskTitle) }).getByText('履约中').first())
      .toBeVisible({ timeout: 15_000 })

    // 商家侧任务行可见已接受口径（UI 待办回归）。
    const merchantContext = await browser.newContext({ baseURL })
    const merchantPage = await merchantContext.newPage()
    await uiLogin(merchantPage, merchantEmail)
    await merchantPage.request.post('/api/me/active-identity', { data: { type: 'merchant' } })
    await openGrassland(merchantPage)
    await expect(merchantPage.getByRole('button', { name: taskTitle, exact: true }).first())
      .toBeVisible({ timeout: 20_000 })

    // ---- 推荐官：草稿送审（发布凭证；自动核验并发跑）----
    // contentUrl 选用稳定 2xx 的公共 URL：xiaohongshu.com 对 HEAD 恒 404（实测）会以
    // link_reachability=failed 挂起结算；example.com 根路径 200（核验 passed），即便弱网超时
    // 也只判 inconclusive（capture 闸只挡 failed，inconclusive/passed/无记录放行）。
    await data(await recommender.post(
      `/api/tasks/${task.id}/applications/${acceptedApp.id}/submissions`, {
        data: {
          contentUrl: 'https://example.com/',
          note: 'e2e98 履约凭证：已发布图文',
        },
      }))

    // ---- 商家：验收（手动 confirm——只挡核验 failed，inconclusive/absent 放行）→ 结算窗口 ----
    await data(await merchant.post(`/api/tasks/${task.id}/applications/${acceptedApp.id}/confirm`, { data: {} }),
      [202, 200])

    // ---- 观察期（争议窗 0s）→ 结算：轮询 settledAt 落账（无人工改库；status 保持 accepted，终态以 settledAt 为准）----
    await pollUntil('engagement 结算（settledAt 落账）', async () =>
      findMyApplication(recommender, acceptedApp.id), item => item?.settledAt != null, 180_000)

    // ---- 钱包域守恒：入账 = bounty + 声誉加成（快照随行走）----
    const bonusBps = acceptedApp.commissionBonusBpsAtAccept ?? 0
    const expectedPayout = bountyCents + Math.floor(bountyCents * bonusBps / 10_000)
    const walletAfter = await pollUntil('推荐官钱包入账', async () =>
      data<{ balanceCents: number; entries?: Array<{ engagementRef?: string; amountCents?: number }> }>(
        await recommender.get('/api/finance/wallets/me')),
      wallet => wallet.balanceCents - walletBefore.balanceCents === expectedPayout)
    expect(walletAfter.balanceCents - walletBefore.balanceCents).toBe(expectedPayout)
    // escrow capture 只动预留资金：组织余额在 accept 后不再变化。
    const balanceAfterSettle = await data<{ balanceCents: number }>(
      await merchant.get(`/api/finance/accounts/${org.id}`))
    expect(balanceAfterSettle.balanceCents).toBe(balanceAfterEscrow.balanceCents)

    // ---- 声誉域守恒：结算驱动 completedCount 递增（事件链路，放宽轮询）----
    await pollUntil('声誉 completedCount 递增', async () =>
      data<{ completedCount?: number }>(await recommender.get(`/api/reputation/${recommenderId}`)),
      rep => (rep.completedCount ?? 0) > completedBefore, 120_000)

    // ---- 推荐官「我的任务」终态=已完成（settledAt 非空行徽标；UI 待办回归）----
    await recommenderPage.reload()
    await openGrassland(recommenderPage)
    await recommenderPage.getByRole('tab', { name: '我的任务' }).click()
    const myTasksAfter = recommenderPage.locator('article', { hasText: '我的任务' })
    const settledRow = myTasksAfter.getByRole('row', { name: new RegExp(taskTitle) }).getByText('已完成').first()
    await expect(settledRow).toBeVisible({ timeout: 30_000 })
    await dualThemeShot(recommenderPage, 'e2e98-recommender-settled', async () => {
      await expect(settledRow).toBeVisible({ timeout: 30_000 })
    })

    await recommenderContext.close()
    await merchantContext.close()
  })

  test('AC-98-26/27/29：rlid 归因链 + 退款边界两口径 + 订单域金额守恒', async ({ browser }) => {
    test.setTimeout(300_000)

    // ---- 造数：套餐 + 推广任务 + 接单推荐官（judge2）+ rlid（C98-01/02 链）----
    const merchant = await loginApi(merchantEmail)
    await activateIdentity(merchant, 'merchant')
    const [org] = await data<{ id: string }[]>(await merchant.get('/api/organizations'))
    const pkg = await data<{ id: string }>(await merchant.post('/api/v2/merchant/packages', {
      data: {
        organizationId: org.id,
        title: `e2e98 套餐 ${Date.now()}`,
        description: 'e2e98 rlid 归因与退款边界',
        priceCents: 10_000,
        totalStock: 10,
        recommenderShareBps: 1_000,
        platformFeeBps: 500,
        validDaysAfterPurchase: 30,
      },
    }))
    await data(await merchant.post(`/api/v2/merchant/packages/${pkg.id}/publish`, { data: {} }))
    const stores = await data<{ id: string }[]>(await merchant.get(`/api/organizations/${org.id}/stores`))
    const store = stores[0] || await data<{ id: string }>(await merchant.post(
      `/api/organizations/${org.id}/stores`, { data: { name: `e2e98 门店 ${Date.now()}` } }))
    const task = await data<{ id: string; status: string; version: number }>(await merchant.post('/api/tasks', {
      data: {
        organizationId: org.id,
        storeId: store.id,
        commercePackageId: pkg.id,
        title: `e2e98 推广任务 ${Date.now()}`,
        description: 'rlid 归因链验收',
        contentForm: 'image',
        platform: 'xiaohongshu',
        maxSlots: 3,
        applicationDeadline: new Date(Date.now() + 7 * 86_400_000).toISOString(),
      },
    }))
    if (task.status === 'pending_review') {
      const admin = await loginApi('e2e-admin@test.local')
      await data(await admin.post(`/api/admin/tasks/${task.id}/review/approve`, {
        data: { expectedVersion: task.version },
      }))
    }
    const judge2 = await loginApi('e2e-judge2@test.local')
    await activateIdentity(judge2, 'recommender')
    const judge2Me = await data<{ user: { id: string } }>(await judge2.get('/api/auth/me'))
    await data(await judge2.post(`/api/tasks/${task.id}/applications`, { data: { note: 'e2e98 rlid' } }))
    const appsBody = await raw<{ items?: Array<{ id: string; recommenderAccountId: string }> }>(
      await merchant.get(`/api/tasks/${task.id}/applications?limit=50`))
    const apps = appsBody.data.items || []
    const app = apps.find(row => row.recommenderAccountId === judge2Me.user.id)
    expect(app, JSON.stringify(appsBody)).toBeTruthy()
    await data(await merchant.post(`/api/tasks/${task.id}/applications/${app!.id}/accept`, { data: {} }))
    const referral = await data<{ referralLinkId: string }>(await judge2.post('/api/v2/promotion/links', {
      data: { taskId: task.id },
    }))
    expect(referral.referralLinkId).toBeTruthy()

    // 钱包/组织基线（订单域守恒 before）。
    const balanceBefore = await data<{ balanceCents: number }>(await merchant.get(`/api/finance/accounts/${org.id}`))
    const walletBefore = await data<{ balanceCents: number }>(await judge2.get('/api/finance/wallets/me'))

    // ---- 消费者：rlid 落地页触达（UI）→ 下单支付 → 待核销（UI 待办回归：消费者端）----
    const consumerContext = await browser.newContext({ baseURL })
    const consumerPage = await consumerContext.newPage()
    await uiLogin(consumerPage, consumerEmail)
    await consumerPage.goto(`/?view=commerce&package=${pkg.id}&rlid=${encodeURIComponent(referral.referralLinkId)}`)
    await expect(consumerPage.getByRole('button', { name: /支付|下单/ }).first()).toBeVisible({ timeout: 30_000 })
    await consumerPage.getByRole('button', { name: /支付|下单/ }).first().click()
    await expect(consumerPage.getByText('到店出示核销码').first()).toBeVisible({ timeout: 30_000 })
    await expect(consumerPage.getByText('待核销', { exact: true }).first()).toBeVisible()

    const orders = await data<Array<{
      id: string; packageId: string; status: string; priceCents: number
      recommenderAmountCents: number; merchantAmountCents: number; platformFeeCents: number
      refundedAmountCents: number; splitCompletedAt?: string; redeemCode: string
    }>>(await consumerPage.request.get('/api/v2/orders'))
    const mainOrder = orders.find(order => order.packageId === pkg.id && order.redeemCode)
    expect(mainOrder, JSON.stringify(orders)).toBeTruthy()

    // ---- AC-98-27：归因解释可查（触达时间/窗口口径/rlid 短码三端同构字段）----
    const explain = await data<{ attributed: boolean; referralLinkId: string; touchedAt: string; windowDays: number }>(
      await consumerPage.request.get(`/api/v2/orders/${mainOrder!.id}/attribution-explain`))
    expect(explain.attributed).toBe(true)
    expect(explain.referralLinkId).toBe(referral.referralLinkId)
    expect(explain.touchedAt).toBeTruthy()
    expect(explain.windowDays).toBe(7)

    // ---- AC-98-26 口径一：结算前退款放行（第二单，付款后未核销直接退）----
    const secondOrder = await data<{ id: string }>(await consumerPage.request.post('/api/v2/orders', {
      data: { packageId: pkg.id, referralLinkId: referral.referralLinkId },
    }))
    const refunded = await data<{ id: string; status: string; refundedAmountCents: number }>(
      await consumerPage.request.post(`/api/v2/orders/${secondOrder.id}/refund`, {
        data: { reason: 'e2e98 结算前退款边界' },
      }))
    expect(refunded.status).toBe('refunded')
    expect(refunded.refundedAmountCents).toBe(10_000)

    // ---- 商家核销主单 → 冷静期（override 秒级）→ 分账结算 ----
    const redeemCode = await consumerPage.locator('code').filter({ hasText: /^GL-[A-Z0-9_-]+$/ }).first()
      .textContent()
    await data(await merchant.post('/api/v2/merchant/redemptions', { data: { code: redeemCode } }))
    const settledOrder = await pollUntil('commerce 分账完成', async () =>
      data<typeof orders>(await consumerPage.request.get('/api/v2/orders')),
      rows => rows.find(order => order.id === mainOrder!.id)?.splitCompletedAt != null, 180_000)
    const finalMain = settledOrder.find(order => order.id === mainOrder!.id)!

    // ---- AC-98-29 订单域守恒：三方分配 = 实付金额 ----
    expect(finalMain.recommenderAmountCents + finalMain.merchantAmountCents + finalMain.platformFeeCents)
      .toBe(finalMain.priceCents)

    // ---- 钱包域守恒：分账入账（组织 + 推荐官钱包），退款单不产生分账 ----
    const balanceAfter = await data<{ balanceCents: number }>(await merchant.get(`/api/finance/accounts/${org.id}`))
    expect(balanceAfter.balanceCents - balanceBefore.balanceCents).toBe(finalMain.merchantAmountCents)
    const walletAfter = await pollUntil('推荐官佣金入账', async () =>
      data<{ balanceCents: number }>(await judge2.get('/api/finance/wallets/me')),
      wallet => wallet.balanceCents - walletBefore.balanceCents === finalMain.recommenderAmountCents)
    expect(walletAfter.balanceCents - walletBefore.balanceCents).toBe(finalMain.recommenderAmountCents)

    // ---- AC-98-26 口径二：#97 已结算退款闸门（409；结算事实由下方 splitCompletedAt 钉死）----
    // redeemed 态先撞状态守卫（「当前订单状态不可退款」），paid→partially_refunded 才会走到
    // 「已结算」文案守卫——两条都是结算后的正确拒绝，口径=409 拒退 + 机器可读禁退标识。
    const blocked = await consumerPage.request.post(`/api/v2/orders/${mainOrder!.id}/refund`, {
      data: { reason: 'e2e98 结算后退款边界' },
    })
    expect(blocked.status()).toBe(409)
    const blockedBody = await raw<unknown>(blocked)
    expect(JSON.stringify(blockedBody)).toMatch(/不可退款|已结算/)
    // 已结算订单回显机器可读禁退标识与分账事实（前端禁用态同源；亦排除「未结算即拒」的空过）。
    const rowsFinal = await data<Array<typeof orders[number] & { refundBlockedReason?: string }>>(
      await consumerPage.request.get('/api/v2/orders'))
    const finalRow = rowsFinal.find(order => order.id === mainOrder!.id)!
    expect(finalRow.splitCompletedAt).toBeTruthy()
    expect(finalRow.refundBlockedReason).toBe('settled_no_refund')
    // AC-98-30：消费者订单页（归因解释块 + 订单态）双主题截图。
    await consumerPage.goto(`/?view=commerce`)
    await dualThemeShot(consumerPage, 'e2e98-consumer-orders', async () => {
      await expect(consumerPage.getByText('我的消费订单')).toBeVisible({ timeout: 30_000 })
    })

    await consumerContext.close()
  })
})
