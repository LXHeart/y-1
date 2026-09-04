import { expect, request as playwrightRequest, test, type Page } from '@playwright/test'

/**
 * AI 创作中心 e2e（任务书 #76 改锚）：AI 应用是独立 origin（AI_BASE_URL，CI 由
 * ci-e2e.sh 注入；治理台同款 OPS_BASE_URL 模式）。自由创作/运行记录/模型密钥锚 AI 应用，
 * 任务来源门禁锚草场内嵌创作面 /creation（platform 模式），另锁跨应用免登与门店深链链路。
 */
const aiBaseURL = process.env.AI_BASE_URL || 'http://127.0.0.1:18082'
const email = process.env.E2E_EMAIL || 'e2e-ci@test.local'
const merchantEmail = 'e2e-merchant@test.local'
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

test('AI app guests land on the creation board with trial panel and no identity badges', async ({ page }) => {
  await page.goto(aiBaseURL + '/')

  await expect(page.getByRole('tab', { name: '开始创作' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByText('免费体验').first()).toBeVisible()
  // D6：纯个人心智——无商家/推荐官身份徽标
  await expect(page.getByTestId('auth-pill')).toHaveCount(0)
})

test('platform-first creation entry hands an independent topic to article workflow', async ({ page }) => {
  await page.goto(aiBaseURL + '/')

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
  await page.goto(aiBaseURL + '/')

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

test('unauthenticated task source on the grassland surface opens login without loading task data', async ({ page }) => {
  const taskRequests: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/api/tasks')) taskRequests.push(request.url())
  })
  // 任务创作留在草场（任务书 #76 卡 D）：/creation 是 platform 模式内嵌创作面
  await page.goto('/creation')

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
  await page.goto(aiBaseURL + '/')

  await page.getByRole('tab', { name: '运行记录' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByRole('button', { name: '关闭登录弹窗' }).click()
  await page.getByRole('tab', { name: '模型密钥' }).click()

  await expect(page.getByRole('dialog')).toBeVisible()
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

  await loginOnAiApp(page)
  await page.getByRole('tab', { name: '运行记录' }).click()
  await expect(page.getByRole('heading', { name: '运行记录', exact: true })).toBeVisible()
  await expect(page.getByRole('row', { name: /已完成.*qwen-plus.*平台模型.*3 分/ })).toBeVisible()

  await page.getByRole('tab', { name: '模型密钥' }).click()
  await expect(page.getByRole('heading', { name: '个人模型密钥' })).toBeVisible()
  await expect(page.getByText('sk-****-7890')).toBeVisible()
  await expect(page.locator('body')).not.toContainText('plaintext-secret')
})

test.describe('跨应用免登与门店深链（任务书 #76 卡 A/C）', () => {
  test('grassland 主导航外链 → 一次性 token 免登落 AI 应用，URL 无 xat 残留', async ({ page }) => {
    test.skip(!password, 'E2E_PASSWORD is required for the isolated seeded account')
    await page.goto('/')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    const dialog = page.getByRole('dialog')
    await dialog.locator('#login-email').fill(email)
    await dialog.locator('#login-password').fill(password as string)
    await dialog.locator('button[type=submit]').click()
    await page.getByTestId('auth-pill').waitFor({ timeout: 10_000 })

    await page.getByRole('button', { name: 'AI 内容创作中心' }).click()
    await page.waitForURL(new RegExp(aiBaseURL.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')), { timeout: 30_000 })
    await page.getByTestId('auth-pill').waitFor({ timeout: 10_000 })
    expect(page.url()).not.toContain('xat=')
    await expect(page.getByRole('tab', { name: '开始创作' })).toHaveAttribute('aria-selected', 'true')
  })

  test('AI 应用「打开草场」→ 反向免登回草场', async ({ page }) => {
    test.skip(!password, 'E2E_PASSWORD is required for the isolated seeded account')
    await loginOnAiApp(page)

    await page.getByRole('button', { name: '打开草场' }).click()
    await page.waitForURL((url) => !url.href.startsWith(aiBaseURL), { timeout: 30_000 })
    await page.getByTestId('auth-pill').waitFor({ timeout: 10_000 })
    expect(page.url()).not.toContain('xat=')
  })

  test('门店深链锁定：商家会话经 xat 进入 AI 应用，锁定门店上下文且不可改', async ({ page }) => {
    test.skip(!password, 'E2E_PASSWORD is required for the isolated seeded account')
    test.setTimeout(90_000)
    // 商家在草场 UI 登录（浏览器 cookie jar 建立会话）
    await page.goto('/')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    const dialog = page.getByRole('dialog')
    await dialog.locator('#login-email').fill(merchantEmail)
    await dialog.locator('#login-password').fill(password as string)
    await dialog.locator('button[type=submit]').click()
    await page.getByTestId('auth-pill').waitFor({ timeout: 10_000 })

    // API 造数路径同 grassland-task-flow spec：商家组织与门店（种子保证至少一店）
    const api = await playwrightRequest.newContext({
      baseURL: process.env.BASE_URL || 'http://127.0.0.1:18080',
      extraHTTPHeaders: { Origin: process.env.BASE_URL || 'http://127.0.0.1:18080' },
    })
    const loginResponse = await api.post('/api/auth/login', { data: { email: merchantEmail, password: password as string } })
    expect(loginResponse.ok(), await loginResponse.text()).toBeTruthy()
    const orgs = (await (await api.get('/api/organizations')).json()).data as Array<{ id: string }>
    expect(orgs.length).toBeGreaterThan(0)
    const stores = (await (await api.get(`/api/organizations/${orgs[0].id}/stores`)).json()).data as Array<{ id: string }>
    expect(stores.length).toBeGreaterThan(0)

    // page.request 与浏览器共享 cookie：用草场会话签发一次性 token，拼门店深链进入 AI 应用
    const issued = await page.request.post('/api/auth/cross-app-tokens')
    expect(issued.ok(), await issued.text()).toBeTruthy()
    const token = ((await issued.json()).data as { token: string }).token
    await page.goto(`${aiBaseURL}/?entry=store&org=${orgs[0].id}&store=${stores[0].id}&xat=${token}`)

    await page.getByTestId('auth-pill').waitFor({ timeout: 10_000 })
    await expect(page.getByText('门店上下文 · 来自草场工作台')).toBeVisible()
    // 锁定态：组织/门店选择器不渲染，深链参数已清
    await expect(page.locator('select[name="organization"]')).toHaveCount(0)
    expect(page.url()).not.toContain('xat=')
    expect(page.url()).not.toContain('entry=store')
  })

  test('游客/错 token 的门店深链不白屏：登录提示接住', async ({ page }) => {
    await page.goto(`${aiBaseURL}/?entry=store&org=not-accessible&store=none&xat=forged-token-0123456789abcdef0123456789ab`)

    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 15_000 })
    await expect(page.getByRole('button', { name: '登录 / 注册' })).toBeVisible()
  })
})
