import { expect, test } from '@playwright/test'

const email = process.env.E2E_EMAIL || 'e2e-ci@test.local'
const password = process.env.E2E_PASSWORD

async function login(page: import('@playwright/test').Page): Promise<void> {
  await page.goto('/')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: /登录草场/ })
  await dialog.locator('#login-email').fill(email)
  await dialog.locator('#login-password').fill(password as string)
  const response = page.waitForResponse((item) =>
    item.request().method() === 'POST' && item.url().endsWith('/api/auth/login'))
  await dialog.locator('button[type="submit"]').click()
  expect((await response).status()).toBe(200)
  await expect(page.getByText('已登录', { exact: true })).toBeVisible()
}

test('platform-first creation entry hands an independent topic to article workflow', async ({ page }) => {
  await page.goto('/')

  await page.getByRole('button', { name: '公众号' }).click()
  await page.getByRole('button', { name: '图文', exact: true }).click()
  await page.getByRole('button', { name: '独立创作' }).click()
  await page.getByRole('textbox', { name: '创作主题' }).fill('秋季新品发布')
  await page.getByRole('button', { name: '开始创作' }).click()

  await expect(page.getByRole('heading', { name: '先确定主题和发布平台' })).toBeVisible()
  await expect(page.getByPlaceholder(/输入你想创作的主题/)).toHaveValue('秋季新品发布')
  await expect(page.getByRole('button', { name: '微信公众号' })).toHaveClass(/platform-btn-active/)
})

test('douyin graphic independent creation lands on article view in douyin gallery mode', async ({ page }) => {
  await page.goto('/')

  await page.getByRole('button', { name: '抖音' }).click()
  await page.getByRole('button', { name: '图文', exact: true }).click()
  await page.getByRole('button', { name: '独立创作' }).click()
  await page.getByRole('textbox', { name: '创作主题' }).fill('秋季门店新品图集')
  await page.getByRole('button', { name: '开始创作' }).click()

  await expect(page.getByRole('heading', { name: '先确定主题和发布平台' })).toBeVisible()
  await expect(page.getByPlaceholder(/输入你想创作的主题/)).toHaveValue('秋季门店新品图集')
  await expect(page.getByRole('button', { name: '抖音' })).toHaveClass(/platform-btn-active/)
  await expect(page.getByText('抖音定位图集短文案')).toBeVisible()
})

test('unauthenticated task source opens login without loading task data', async ({ page }) => {
  const taskRequests: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/api/tasks')) taskRequests.push(request.url())
  })
  await page.goto('/')

  await page.getByRole('button', { name: '小红书' }).click()
  await page.getByRole('button', { name: '图文', exact: true }).click()
  await page.getByRole('button', { name: '从任务创作' }).click()

  await expect(page.getByRole('dialog')).toBeVisible()
  expect(taskRequests).toEqual([])
})

test('protected AI control-plane tabs require login without calling protected APIs', async ({ page }) => {
  const protectedRequests: string[] = []
  page.on('request', (request) => {
    if (/\/api\/ai\/(runs|keys)/.test(request.url())) protectedRequests.push(request.url())
  })
  await page.goto('/')

  await page.getByRole('tab', { name: '运行记录' }).click()
  await expect(page.getByRole('dialog', { name: /登录草场/ })).toBeVisible()
  await page.getByRole('button', { name: '关闭登录弹窗' }).click()
  await page.getByRole('tab', { name: '模型密钥' }).click()

  await expect(page.getByRole('dialog', { name: /登录草场/ })).toBeVisible()
  await expect(page.getByRole('tab', { name: '开始创作' })).toHaveAttribute('aria-selected', 'true')
  expect(protectedRequests).toEqual([])
})

test('authenticated user can inspect AI runs and personal BYOK metadata', async ({ page }) => {
  test.skip(!password, 'E2E_PASSWORD is required for the isolated seeded account')
  await page.route('**/api/ai/runs', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify([{
        runId: '11111111-1111-1111-1111-111111111111',
        capability: 'text', provider: 'qwen', model: 'qwen-plus', status: 'completed',
        actualCents: 3, startedAt: '2026-08-05T12:00:00Z', completedAt: '2026-08-05T12:00:01Z',
        taskContext: {
          runId: '11111111-1111-1111-1111-111111111111', capability: 'text',
          provider: 'qwen', model: 'qwen-plus', resolutionType: 'PLATFORM',
          priceTableVersion: '2026-08', platformModelVersion: 4,
          fallbackAuthorized: true, startedAt: '2026-08-05T12:00:00Z',
        },
        content: null, inputTokens: null, outputTokens: null,
      }]),
    })
  })
  await page.route('**/api/ai/keys', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify([{
        id: '22222222-2222-2222-2222-222222222222', organizationId: null,
        capability: 'text', provider: 'openai-compatible', baseUrl: 'https://api.example.com/v1',
        model: 'custom-model', maskedHint: 'sk-****-7890', enabled: true,
        createdAt: '2026-08-05T12:00:00Z', updatedAt: '2026-08-05T12:00:00Z',
      }]),
    })
  })

  await login(page)
  await page.getByRole('tab', { name: '运行记录' }).click()
  await expect(page.getByRole('heading', { name: '运行记录', exact: true })).toBeVisible()
  await expect(page.getByRole('row', { name: /已完成.*qwen-plus.*平台模型.*3 分/ })).toBeVisible()

  await page.getByRole('tab', { name: '模型密钥' }).click()
  await expect(page.getByRole('heading', { name: '个人模型密钥' })).toBeVisible()
  await expect(page.getByText('sk-****-7890')).toBeVisible()
  await expect(page.locator('body')).not.toContainText('plaintext-secret')
})
