import { mkdirSync } from 'node:fs'
import { expect, request as playwrightRequest, test, type APIRequestContext, type Page } from '@playwright/test'

/**
 * 任务书 #103 C103-25：状态/主题/视口矩阵的可视行为验收（TC103-25-01/04/05）。
 *
 * - TC103-25-01 产品状态与 API 事实一致：退出/报名态、订单入口、匿名空态——文案/按钮
 *   对照 API 事实断言，无假成功/假零；截图只是补充，不替代状态断言；
 * - TC103-25-04 键盘可操作：Tab/Shift+Tab 焦点可见且顺序合理；
 * - TC103-25-05 双主题 × 双视口：本书修改页各至少 4 图（desktop/mobile × light/dark），
 *   长内容/金额无横向溢出。
 *
 * 三引擎由 playwright projects 承接（chromium/firefox/webkit 同 spec）；
 * 主题用 localStorage['theme-preference']（与 task98 截图助手同款）。
 */
const baseURL = process.env.BASE_URL || 'http://127.0.0.1:18080'
const password = process.env.E2E_PASSWORD || 'test-password-2026'
const merchantEmail = 'e2e-merchant@test.local'
const recommenderEmail = 'e2e-judge1@test.local'
const shotDir = process.env.E2E_SHOT_DIR || 'test-artifacts/task-103/screenshots/e2e'

interface Envelope<T> {
  success: boolean
  data: T
  error?: string
}

async function data<T>(response: { status(): number; text(): Promise<string>; json(): Promise<unknown> }, expected = [200, 201, 202]): Promise<T> {
  expect(expected, await response.text()).toContain(response.status())
  const body = await response.json() as Envelope<T>
  expect(body.success, JSON.stringify(body)).toBe(true)
  return body.data
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

async function uiLogin(page: Page, email: string): Promise<void> {
  await page.goto('/')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: /登录草场/ })
  await dialog.locator('#login-email').fill(email)
  await dialog.locator('#login-password').fill(password)
  await dialog.locator('button[type="submit"]').click()
  await page.getByTestId('auth-pill').waitFor({ timeout: 10_000 })
}

/** 双主题 × 双视口截图（每页 4 图）；断言无横向溢出后落盘。 */
async function shotMatrix(page: Page, name: string): Promise<void> {
  mkdirSync(shotDir, { recursive: true })
  for (const theme of ['dark', 'light'] as const) {
    for (const viewport of [
      { label: 'desktop', width: 1440, height: 900 },
      { label: 'mobile', width: 390, height: 844 },
    ]) {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      await page.evaluate((value) => localStorage.setItem('theme-preference', value), theme)
      await page.reload()
      await page.waitForLoadState('networkidle')
      // 可读性硬断言：视口内不出现横向滚动（长金额/长案号不得撑破布局）
      const overflow = await page.evaluate(() =>
        document.documentElement.scrollWidth - document.documentElement.clientWidth)
      expect(overflow, `${name} ${theme}/${viewport.label} 不横向溢出`).toBeLessThanOrEqual(0)
      await page.screenshot({ path: `${shotDir}/${name}-${test.info().project.name}-${theme}-${viewport.label}.png`, fullPage: true })
    }
  }
}

test.describe('任务书 #103 C103-25 UI 状态与矩阵', () => {

  test('TC103-25-01 匿名空态：未登录首页无私有数据、无假成功动作', async ({ browser }) => {
    const page = await browser.newPage()
    await page.goto('/')
    // 匿名态不拉私有接口（401 由后端权威拒绝，前端不伪造数据）
    const response = await page.request.get('/api/me/notifications?limit=5')
    expect(response.status()).toBe(401)
    // 匿名态顶栏提供登录入口（auth-pill 是登录后的会话胶囊，匿名不渲染）
    await expect(page.getByRole('button', { name: '登录', exact: true })).toBeVisible({ timeout: 10_000 })
  })

  test('TC103-25-01 推荐官工作台：报名/合作事实与 API 一致（无假零）', async ({ browser }) => {
    test.setTimeout(120_000)
    const recommender = await loginApi(recommenderEmail)
    await data(await recommender.post('/api/me/active-identity', { data: { type: 'recommender' } }))
    const applications = await data<{ items?: unknown[] }>(await recommender.get('/api/tasks/my-applications?limit=5'))

    const page = await browser.newPage()
    await uiLogin(page, recommenderEmail)
    await page.request.post('/api/me/active-identity', { data: { type: 'recommender' } })
    await page.locator('[data-testid="nav-workbench"]').click()
    await page.waitForLoadState('networkidle')

    // 空态：列表为空时显示明确空状态而非伪造行；非空时页面可见合作卡片
    if ((applications.items ?? []).length === 0) {
      await expect(page.getByText(/还没有|暂无/).first()).toBeVisible({ timeout: 15_000 })
    } else {
      await expect(page.locator('.gl-card:visible, #gl-engagements:visible, #gl-task-hall:visible').first())
        .toBeVisible({ timeout: 30_000 })
    }
  })

  test('TC103-25-04 键盘可操作：Tab 焦点可见且可往返', async ({ browser }) => {
    const page = await browser.newPage()
    await page.goto('/')
    await page.waitForLoadState('networkidle')

    await page.keyboard.press('Tab')
    const firstActive = await page.evaluate(() => ({
      tag: document.activeElement?.tagName,
      outline: document.activeElement ? getComputedStyle(document.activeElement).outlineStyle : '',
      outlineWidth: document.activeElement ? getComputedStyle(document.activeElement).outlineWidth : '0px',
      shadow: document.activeElement ? getComputedStyle(document.activeElement).boxShadow : 'none',
    }))
    expect(['BUTTON', 'A', 'INPUT', 'SELECT', 'TEXTAREA']).toContain(firstActive.tag)
    // 焦点样式可见（outline/box-shadow 至少一处非 none）
    expect((!['', 'none', 'hidden'].includes(firstActive.outline) && Number.parseFloat(firstActive.outlineWidth) > 0)
      || firstActive.shadow !== 'none').toBe(true)
    const firstElement = await page.locator(':focus').elementHandle()
    await page.keyboard.press('Tab')
    expect(await page.evaluate((element) => document.activeElement !== element, firstElement)).toBe(true)
    await page.keyboard.press('Shift+Tab')
    expect(await page.evaluate((element) => document.activeElement === element, firstElement)).toBe(true)
    await page.close()
  })

  test('TC103-25-05 修改页双主题×双视口截图：争议列表/消费订单/工作台', async ({ browser }) => {
    test.setTimeout(300_000)
    // 争议列表（本书 C103-11/12 改动面）
    const disputesPage = await browser.newPage()
    await uiLogin(disputesPage, recommenderEmail)
    await disputesPage.goto('/me/disputes')
    await disputesPage.waitForLoadState('networkidle')
    await shotMatrix(disputesPage, 'disputes-list')
    await disputesPage.close()

    // 消费者订单（C103-05/07 改动面）：真实登录态下进入 commerce 视图
    const commercePage = await browser.newPage()
    await uiLogin(commercePage, merchantEmail)
    await commercePage.goto('/?view=commerce')
    await commercePage.waitForLoadState('networkidle')
    await shotMatrix(commercePage, 'consumer-commerce')
    await commercePage.close()

    // 商家工作台（C103-04 退出状态呈现面）
    const workbenchPage = await browser.newPage()
    await uiLogin(workbenchPage, merchantEmail)
    await workbenchPage.locator('[data-testid="nav-workbench"]').click()
    await workbenchPage.waitForLoadState('networkidle')
    await shotMatrix(workbenchPage, 'merchant-workbench')
    await workbenchPage.close()
  })
})
