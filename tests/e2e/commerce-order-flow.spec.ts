import { expect, request as playwrightRequest, test, type APIRequestContext, type APIResponse, type Page } from '@playwright/test'

/**
 * 消费者下单支付主流程 e2e（盘点缺口：commerce 此前无 e2e）。
 *
 * 覆盖：商家发布套餐（API 造数）→ 推荐官分享落地页（/?view=commerce&package&recommender）→
 * Sandbox 支付下单 → 核销码生成与订单「待核销」（UI）→ 商家输码核销（API）→
 * 消费者订单转「已核销」（UI）。支付通道是 Sandbox（D-01 门禁），订单/核销状态机为真实实现。
 *
 * 前置：`npm run e2e:seed`；消费者/归因推荐官复用 e2e-judge1（注册用户即可下单）。
 */
const baseURL = process.env.BASE_URL || 'http://127.0.0.1:18080'
const password = process.env.E2E_PASSWORD || 'test-password-2026'
const merchantEmail = 'e2e-merchant@test.local'
const consumerEmail = 'e2e-judge1@test.local'

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

test.describe('消费者下单支付主流程', () => {
  test('推荐官落地页 → Sandbox 支付 → 核销码 → 商家核销 → 已核销', async ({ browser }) => {
    test.setTimeout(120_000)

    // ---- 造数：商家发布套餐（版本化库存走 v2 commerce 契约）----
    const merchant = await loginApi(merchantEmail)
    await activateIdentity(merchant, 'merchant')
    const [org] = await data<{ id: string }[]>(await merchant.get('/api/organizations'))
    const pkg = await data<{ id: string }>(await merchant.post('/api/v2/merchant/packages', {
      data: {
        organizationId: org.id,
        title: `e2e 套餐 ${Date.now()}`,
        description: 'e2e 下单冒烟套餐',
        priceCents: 100,
        totalStock: 5,
        recommenderShareBps: 1000,
        platformFeeBps: 500,
        validDaysAfterPurchase: 30,
      },
    }))
    await data(await merchant.post(`/api/v2/merchant/packages/${pkg.id}/publish`, { data: {} }))

    // 归因推荐官 = 消费者账号自身不合适（自己给自己归因），用商家之外的第二账号：
    // 种子里审判官都有 recommender 身份，取 judge2 作归因方。
    const judge2 = await loginApi('e2e-judge2@test.local')
    const judge2Me = await data<{ user: { id: string } }>(await judge2.get('/api/auth/me'))
    const recommenderId = judge2Me.user.id

    // ---- 消费者：推荐官分享落地页下单（UI）----
    const consumerContext = await browser.newContext({ baseURL })
    const consumerPage = await consumerContext.newPage()
    await uiLogin(consumerPage, consumerEmail)
    await consumerPage.goto(`/?view=commerce&package=${pkg.id}&recommender=${recommenderId}`)

    // 30s：文案只依赖 URL query 同步渲染，超时根因是慢 runner 上 webkit 的 JS 挂载
    // 偶发超全局 expect 10s（round 32423929586 首跑+retry 两点实测）。
    await expect(consumerPage.getByText('推荐归因已锁定').first()).toBeVisible({ timeout: 30_000 })
    await consumerPage.getByRole('button', { name: /Sandbox 支付下单/ }).click()

    // 支付成功：核销码 + 订单「待核销」。
    await expect(consumerPage.getByText('Sandbox 支付成功，核销码已生成。')).toBeVisible()
    await expect(consumerPage.getByText('到店出示核销码').first()).toBeVisible()
    await expect(consumerPage.getByText('待核销', { exact: true }).first()).toBeVisible()

    // ---- 商家：输码核销（API；UI 核销面板由 vitest 覆盖，e2e 锁状态机）----
    const redeemCode = await consumerPage.locator('code').filter({ hasText: /^GL-[A-Z0-9_-]+$/ }).first()
      .textContent()
    expect(redeemCode).toBeTruthy()
    const redeemed = await data<{ id: string; status: string }>(await merchant.post('/api/v2/merchant/redemptions', {
      data: { code: redeemCode?.trim() },
    }))
    expect(redeemed.status).toBe('redeemed')

    // ---- 消费者：订单转已核销（UI 刷新）----
    await consumerPage.reload()
    await consumerPage.goto(`/?view=commerce&package=${pkg.id}`)
    // 30s：reload 后订单列表拉取在同轮慢负载下同口径偶发超 10s。
    await expect(consumerPage.getByText('已核销', { exact: true }).first()).toBeVisible({ timeout: 30_000 })

    await consumerContext.close()
  })
})
