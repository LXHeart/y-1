/**
 * 积分公共读契约 e2e（任务书 #87 C-04 / TC-C04-001）。
 * 契约真相源：任务书 §6.3 + CreditsControllerIT 契约用例——本 spec 在真实栈上同时压住
 * 「真实后端响应形态」与「真实前端解析渲染」（徽标渲染 data.balance）。
 * seed 账号未建积分户 → data.balance=0、徽标「0 次」，断言天然自洽。
 */
import { expect, test, type Page } from '@playwright/test'

const aiBaseURL = process.env.AI_BASE_URL || 'http://127.0.0.1:18082'
const email = process.env.E2E_EMAIL || 'e2e-ci@test.local'
const password = process.env.E2E_PASSWORD

async function loginOnAiApp(page: Page): Promise<void> {
  await page.goto(aiBaseURL + '/')
  await page.getByRole('button', { name: '登录 / 注册' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('#login-email').fill(email)
  await dialog.locator('#login-password').fill(password as string)
  const response = page.waitForResponse((item) =>
    item.request().method() === 'POST' && item.url().endsWith('/api/auth/login'))
  await dialog.locator('button[type="submit"]').click()
  expect((await response).status()).toBe(200)
  await page.getByTestId('auth-pill').waitFor({ timeout: 10_000 })
}

test('积分公共读契约：balance/history 统一 success/data 信封，徽标渲染 data.balance', async ({ page }) => {
  await loginOnAiApp(page)

  const balanceRes = await page.request.get(aiBaseURL + '/api/credits/balance')
  expect(balanceRes.status()).toBe(200)
  const balanceBody = await balanceRes.json()
  expect(balanceBody.success).toBe(true)
  expect(typeof balanceBody.data.balance).toBe('number')
  expect(typeof balanceBody.data.totalEarned).toBe('number')
  expect(typeof balanceBody.data.totalSpent).toBe('number')
  expect(balanceBody.balance).toBeUndefined() // 顶层不得残留裸字段

  await expect(page.locator('.credits-badge')).toContainText(`${balanceBody.data.balance} 次`)

  const historyRes = await page.request.get(aiBaseURL + '/api/credits/history')
  expect(historyRes.status()).toBe(200)
  const historyBody = await historyRes.json()
  expect(historyBody.success).toBe(true)
  expect(Array.isArray(historyBody.data.history)).toBe(true)
  expect(historyBody.data.history.length).toBeLessThanOrEqual(50)
  if (historyBody.data.history.length > 0) {
    const first = historyBody.data.history[0]
    for (const key of ['id', 'amount', 'balanceAfter', 'type', 'createdAt']) {
      expect(first).toHaveProperty(key)
    }
  }
})

test('未登录访问积分公共读返回 401', async ({ request }) => {
  const res = await request.get(aiBaseURL + '/api/credits/balance')
  expect(res.status()).toBe(401)
})
