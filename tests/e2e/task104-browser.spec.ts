import { appendFileSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { expect, request as playwrightRequest, test, type Page } from '@playwright/test'

/**
 * 任务书 #104 C104-10（TC104-10-03/04）：三入口登录态状态/视觉矩阵 + 共享 HTTP 错误跨入口。
 *
 * 12 张主矩阵：受影响三页面（AI 视频字幕工作台=创作中心「视频工坊」区段 / 视频画布恢复 /
 * 用户工作台）× desktop 1440×900 / mobile 390×844 × light/dark；另有治理台错误入口双主题 2 张。
 *
 * 全部登录态访问（task103-ui 同款种子账号与登录弹窗流程）。首轮未登录版本实测暴露两个
 * 无区分度问题：三个应用均为 createWebHistory（非 hash 路由），`/#/video-canvas` 等
 * URL 全部落回首页（三页截图字节相同）、主题键误用 'theme'（真实键 theme-preference）
 * 致明暗成对字节相同——故改为真实路径 + 登录态 + 断言 data-theme 真实生效 + 页面锚点。
 * 空态即代表状态，不伪造业务数据。AI 视频字幕工作台的入口是创作中心导航「视频工坊」
 * 区段（区段是组件态、reload 后需重进；/video 是视频分析页，非本卡受影响入口）。
 * 交互断言（非仅截图）：键盘 Tab 可达、mobile 无横向溢出、画布页数据 API 中断呈现有界
 * 失败态（R07 浏览器层）；R03/R04 真实双页/在途卸载浏览器层由 C104-04 真实双页探针覆盖
 * （构建资产无源模块 URL 可导入，BR-11 如实边界）。
 */

const FRONT = process.env.BASE_URL ?? 'http://localhost:18081'
const AI = process.env.AI_BASE_URL ?? FRONT
const OPS = process.env.OPS_BASE_URL ?? 'http://localhost:18082'
const SHOT_DIR = process.env.E2E_SHOT_DIR ?? 'test-artifacts/task-104/screenshots/e2e'
const PASSWORD = process.env.E2E_PASSWORD ?? ''
const FRONT_EMAIL = 'e2e-merchant@test.local'
const AI_EMAIL = 'e2e-ci@test.local'

async function loginFront(page: Page): Promise<void> {
  await page.goto(`${FRONT}/`)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: /登录草场/ })
  await dialog.locator('#login-email').fill(FRONT_EMAIL)
  await dialog.locator('#login-password').fill(PASSWORD)
  await dialog.locator('button[type="submit"]').click()
  await page.getByTestId('auth-pill').waitFor({ timeout: 30_000 })
}

async function loginAi(page: Page): Promise<void> {
  await page.goto(`${AI}/`)
  await page.getByRole('button', { name: '登录 / 注册' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('#login-email').fill(AI_EMAIL)
  await dialog.locator('#login-password').fill(PASSWORD)
  await dialog.locator('button[type="submit"]').click()
  await page.getByTestId('auth-pill').waitFor({ timeout: 30_000 })
}

/** 双主题 × 双视口截图（每页 4 图）：data-theme 真实生效 + mobile 无横向溢出。
 * restore 用于 reload 后重进非 URL 驱动的区段（如创作中心「视频工坊」）。 */
async function shotMatrix(page: Page, name: string, restore?: () => Promise<void>, engine = 'local'): Promise<void> {
  for (const theme of ['light', 'dark'] as const) {
    for (const viewport of [{ id: 'desktop', width: 1440, height: 900 },
      { id: 'mobile', width: 390, height: 844 }] as const) {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      await page.evaluate((value) => localStorage.setItem('theme-preference', value), theme)
      await page.reload()
      await page.waitForLoadState('networkidle')
      await restore?.()
      // 主题键真实生效（首轮教训：错误键使明暗截图字节相同）。
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
      if (name === 'ai-video-studio') {
        const selectedTab = page.getByRole('button', { name: '字幕工作台', exact: true })
        await selectedTab.hover()
        await expect.poll(() => selectedTab.evaluate(el => {
          const style = getComputedStyle(el)
          const probe = document.createElement('span')
          probe.style.backgroundColor = 'var(--color-primary-active)'
          el.append(probe)
          const expected = getComputedStyle(probe).backgroundColor
          probe.remove()
          return style.backgroundColor === expected
        })).toBe(true)
      }
      // 焦点可见性：键盘 Tab 可达（不修改内容）。
      const focus = page.locator(':focus')
      let focusReady = false
      for (let tab = 0; tab < 12; tab += 1) {
        await page.keyboard.press('Tab')
        focusReady = await focus.evaluate(el => {
          const style = getComputedStyle(el)
          return Boolean((el as HTMLElement).offsetWidth || (el as HTMLElement).offsetHeight)
            && el.matches('button, a[href], input, select, textarea, [tabindex]')
            && ((style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0) || style.boxShadow !== 'none')
        }).catch(() => false)
        if (focusReady) break
      }
      await expect(focus).toBeVisible()
      expect(focusReady).toBe(true)
      if (viewport.id === 'mobile') {
        const overflow = await page.evaluate(() =>
          document.documentElement.scrollWidth - document.documentElement.clientWidth)
        expect(overflow, `${name} ${theme}/${viewport.id} 不横向溢出`).toBeLessThanOrEqual(8)
      }
      const shot = `${SHOT_DIR}/${engine}/${name}-${viewport.id}-${theme}.png`
      mkdirSync(path.dirname(shot), { recursive: true })
      if (name === 'workbench-funds-error') {
        await expect(page.getByRole('status').filter({ hasText: '正在加载报名与结算状态' })).toHaveCount(0, { timeout: 30_000 })
        const funds = page.getByTestId('exit-funds-result').first()
        await expect(funds.getByTestId('exit-funds-error')).toContainText('upstream unavailable')
        await funds.scrollIntoViewIfNeeded()
        const detail = `${SHOT_DIR}/${engine}/${name}-${viewport.id}-${theme}-detail.png`
        await funds.screenshot({ path: detail })
        appendManifest(detail, { engine, page: name, viewport: viewport.id, theme, state: 'funds-error-detail', role: '商家' })
      }
      await page.screenshot({ path: shot, fullPage: true })
      appendManifest(shot, { engine, page: name, viewport: viewport.id, theme, state: 'matrix', role: '登录用户' })
    }
  }
}

/** #106 D07：逐图元数据（引擎/入口/视口/主题/状态/角色）落 manifest，供实际打开查看留档。 */
function appendManifest(shot: string, meta: Record<string, string>): void {
  mkdirSync(path.dirname(shot), { recursive: true })
  appendFileSync(`${SHOT_DIR}/${meta.engine ?? 'local'}/manifest.jsonl`,
    `${JSON.stringify({ shot: path.basename(shot), ...meta, recordedAt: new Date().toISOString() })}\n`)
}

test('截图矩阵：AI 视频字幕工作台（视频工坊区段，登录态）', async ({ page }, testInfo) => {
  test.setTimeout(240_000)
  expect(PASSWORD, 'E2E_PASSWORD 须由 ci-e2e.sh 提供').toBeTruthy()
  await loginAi(page)
  await page.getByRole('tab', { name: '视频工坊' }).click()
  await page.getByRole('button', { name: '字幕工作台', exact: true }).click()
  await expect(page.getByText('选择字幕来源：')).toBeVisible()
  await shotMatrix(page, 'ai-video-studio', async () => {
    await page.getByRole('tab', { name: '视频工坊' }).click()
    await page.getByRole('button', { name: '字幕工作台', exact: true }).click()
    await expect(page.getByText('选择字幕来源：')).toBeVisible()
  }, testInfo.project.name)
})

test('截图矩阵：视频画布恢复（登录态空态）', async ({ page }, testInfo) => {
  test.setTimeout(240_000)
  expect(PASSWORD).toBeTruthy()
  await loginFront(page)
  await page.goto(`${FRONT}/video-canvas`)
  await expect(page.locator('[data-test="canvas-login-required"]')).toHaveCount(0)
  await expect(page.locator('[data-test="canvas-missing-id"]')).toBeVisible()
  await shotMatrix(page, 'video-canvas', undefined, testInfo.project.name)
})

test('截图矩阵：用户工作台首页（登录态基线，不冒充资金区）', async ({ page }, testInfo) => {
  test.setTimeout(240_000)
  expect(PASSWORD).toBeTruthy()
  await loginFront(page)
  await page.goto(`${FRONT}/grassland`)
  await expect(page.getByRole('heading', { name: '商家工作台' })).toBeVisible()
  await shotMatrix(page, 'workbench-home', undefined, testInfo.project.name)
})

test('交互断言：画布页数据 API 中断 → 有界失败态（R07 浏览器层）', async ({ page }) => {
  test.setTimeout(120_000)
  expect(PASSWORD).toBeTruthy()
  await loginFront(page)
  await page.route('**/api/**', async (route) => {
    // storyboard 同时覆盖单数（绑定）/复数（分镜与镜头）端点。
    if (route.request().url().includes('storyboard') || route.request().url().includes('creation-drafts')) {
      await route.abort('failed')
      return
    }
    await route.continue()
  })
  // 带合成 storyboard key 才会真实发起绑定请求；被中断后必须呈现有界失败态（不白屏、不裸抛）。
  await page.goto(`${FRONT}/video-canvas?storyboard=00000000-0000-4000-8000-000000000000`)
  await expect(page.locator('[data-test="canvas-binding-failed"], [data-test="canvas-error"]')).toBeVisible({ timeout: 30_000 })
  const body = await page.locator('body').innerText()
  expect(body.length).toBeGreaterThan(0)
})

// ---------- 任务书 #106 C106-07（TC106-07-01～04 / D07）：真实错误反馈与状态矩阵 ----------

const OPS_ADMIN_EMAIL = process.env.E2E_ADMIN_EMAIL || 'e2e-admin-ci@test.local'

/** 治理台真实合成管理员登录（凭据由 E2E 环境提供；缺失失败而非 skip——D07）。 */
async function loginOps(page: Page): Promise<void> {
  const adminPassword = process.env.E2E_ADMIN_PASSWORD
  expect(adminPassword, 'E2E_ADMIN_PASSWORD 须由 E2E 环境提供（缺失即失败，不静默跳过）').toBeTruthy()
  await page.goto(`${OPS}/`)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: /登录草场/ })
  await dialog.locator('#login-email').fill(OPS_ADMIN_EMAIL)
  await dialog.locator('#login-password').fill(adminPassword as string)
  await dialog.locator('button[type="submit"]').click()
  await page.locator('.ops-user').waitFor({ timeout: 30_000 })
}

async function opsShot(page: Page, name: string, theme: string, state: string, engine: string, viewport = 'desktop'): Promise<void> {
  const shot = `${SHOT_DIR}/${engine}/${name}-${theme}-${state}.png`
  mkdirSync(path.dirname(shot), { recursive: true })
  await page.screenshot({ path: shot, fullPage: true })
  appendManifest(shot, { engine, page: name, viewport, theme, state, role: '治理管理员' })
}

test('TC106-07-01/D07-F09：治理台真实登录后 admin 数据端点 503——route 实际命中、503 可见、具体错误反馈、非登录页、无未处理异常', async ({ page }, testInfo) => {
  test.setTimeout(180_000)
  await loginOps(page)
  await expect(page.locator('.ops-user')).toBeVisible()
  let hits = 0
  const adminStatuses: number[] = []
  const pageErrors: string[] = []
  page.on('pageerror', error => pageErrors.push(String(error)))
  page.on('response', response => {
    if (response.url().includes('/api/admin/')) adminStatuses.push(response.status())
  })
  await page.route('**/api/admin/**', async (route) => {
    hits += 1
    await route.fulfill({ status: 503, contentType: 'text/plain', body: 'upstream unavailable' })
  })
  await page.reload()
  await page.waitForLoadState('networkidle')
  // 拦截必须实际命中，且页面收到的就是 503（不以 body 非空替代命中证明）。
  expect(hits, '503 route 必须实际命中至少一次').toBeGreaterThan(0)
  expect(adminStatuses).toContain(503)
  // 具体错误反馈：审核队列面板的 role=alert 呈现 503 文本（grassland-http 纯文本透出）。
  await expect(page.locator('.error-msg[role="alert"]').first()).toContainText('upstream unavailable', { timeout: 20_000 })
  // 非登录页：仍在已登录治理台（不把登录页/静态标题当成功）。
  await expect(page.locator('.ops-user')).toBeVisible()
  expect(pageErrors, '不得有未处理页面异常').toEqual([])
  // 双主题截图（真实命中状态；每引擎分目录）。
  for (const theme of ['light', 'dark'] as const) {
    await page.evaluate((value) => localStorage.setItem('theme-preference', value), theme)
    await page.reload()
    await page.waitForLoadState('networkidle')
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
    await expect(page.locator('.error-msg[role="alert"]').first()).toContainText('upstream unavailable')
    await opsShot(page, 'ops-error', theme, '503-hit', testInfo.project.name)
  }
})

test('TC106-07-02：治理台正常对照可达、无响应失败有界不误当正常、解除后恢复、键盘焦点可见', async ({ page }, testInfo) => {
  test.setTimeout(180_000)
  await loginOps(page)
  await page.waitForLoadState('networkidle')
  // 正常对照：审核队列数据端点真实 200，错误位不出现。
  await expect(page.locator('.error-msg')).toHaveCount(0, { timeout: 20_000 })
  await expect(page.locator('.ops-user')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'KYB 审核', exact: true })).toBeVisible()

  // 无响应失败（网络中断，非 503）：有界错误态，不白屏、不误当正常。
  await page.route('**/api/admin/**', async (route) => { await route.abort('failed') })
  await page.reload()
  await page.waitForLoadState('networkidle')
  await expect(page.locator('.error-msg[role="alert"]').first()).toBeVisible({ timeout: 20_000 })
  await expect(page.locator('.ops-user')).toBeVisible()
  // 键盘 Tab：焦点落在可操作元素且可见（不修改内容）。
  await page.keyboard.press('Tab')
  const focus = await page.evaluate(() => {
    const el = document.activeElement as HTMLElement | null
    return el ? { tag: el.tagName, visible: Boolean(el.offsetWidth || el.offsetHeight) } : null
  })
  expect(focus?.visible, 'Tab 后焦点应落在可见可操作元素').toBe(true)
  expect(['A', 'BUTTON', 'INPUT', 'SELECT', 'TEXTAREA']).toContain(focus?.tag)

  // 解除拦截恢复：数据回到可达（失败不冒充正常，恢复后错误消失）。
  await page.unroute('**/api/admin/**')
  await page.reload()
  await page.waitForLoadState('networkidle')
  await expect(page.locator('.error-msg')).toHaveCount(0, { timeout: 20_000 })
  await opsShot(page, 'ops-review', 'light', 'normal-control', testInfo.project.name)
})
/** 商家工作台 → 自己的任务 → 报名面板（grassland-task-flow 同款真实导航路径）。 */
async function openMerchantTaskApplicants(page: Page, title: string): Promise<void> {
  await page.goto(`${FRONT}/grassland`)
  await page.request.post('/api/me/active-identity', { data: { type: 'merchant' } })
  await page.reload()
  await page.waitForLoadState('networkidle')
  await page.locator('[data-testid="nav-workbench"]').click()
  const taskRow = page.getByRole('button', { name: title, exact: true }).first()
  await expect(taskRow, `任务行应可见：${title}`).toBeVisible({ timeout: 30_000 })
  await taskRow.click()
}

test('TC106-07-03：实际资金区状态矩阵（真实 API 造数 + 合成 pending/失败响应）与画布绑定重试幂等', async ({ page }, testInfo) => {
  test.setTimeout(300_000)
  expect(PASSWORD).toBeTruthy()
  const title = `t106 资金区验收 ${Date.now()}`
  // ---- 真实造数（任务书 §8：合成 pending 及失败响应，业务对象走真实 API）----
  const envelope = async (response: { status(): number; text(): Promise<string>; json(): Promise<unknown> }, okStatuses: number[] = [200, 201, 202]) => {
    expect(okStatuses, await response.text()).toContain(response.status())
    const body = await response.json() as { success: boolean; data: unknown }
    expect(body.success).toBe(true)
    return body.data as never
  }
  const api = await playwrightRequest.newContext({ baseURL: FRONT, extraHTTPHeaders: { Origin: FRONT }, timeout: 30_000 })
  await envelope(await api.post('/api/auth/login', { data: { email: FRONT_EMAIL, password: PASSWORD } }))
  await api.post('/api/me/active-identity', { data: { type: 'merchant' } })
  const orgs = await envelope(await api.get('/api/organizations')) as Array<{ id: string }>
  const storesRaw = await envelope(await api.get(`/api/organizations/${orgs[0]!.id}/stores`)) as unknown as Array<{ id: string; name: string }> | { items?: Array<{ id: string; name: string }> }
  const storeList = Array.isArray(storesRaw) ? storesRaw : storesRaw.items ?? []
  if (storeList.length === 0) {
    // 组织尚无门店：真实创建（与 grassland-task-flow 同款造数路径）。
    const created = await envelope(await api.post(`/api/organizations/${orgs[0]!.id}/stores`, { data: { name: `t106 门店 ${Date.now()}` } })) as { id: string; name: string }
    storeList.push(created)
  }
  const task = await envelope(await api.post('/api/tasks', {
    data: {
      organizationId: orgs[0]!.id,
      storeId: storeList[0]?.id,
      title,
      description: '任务书 #106 C106-07：资金区真实状态矩阵',
      contentForm: 'image',
      platform: 'xiaohongshu',
      maxSlots: 1,
      bountyCents: 5_000,
      applicationDeadline: new Date(Date.now() + 7 * 86_400_000).toISOString(),
    },
  })) as { id: string; status: string; version: number }
  if (task.status === 'pending_review') {
    const admin = await playwrightRequest.newContext({ baseURL: FRONT, extraHTTPHeaders: { Origin: FRONT }, timeout: 30_000 })
    await admin.post('/api/auth/login', { data: { email: OPS_ADMIN_EMAIL, password: process.env.E2E_ADMIN_PASSWORD } })
    await admin.post(`/api/admin/tasks/${task.id}/review/approve`, { data: { expectedVersion: task.version } })
    await admin.dispose()
  }
  const recommender = await playwrightRequest.newContext({ baseURL: FRONT, extraHTTPHeaders: { Origin: FRONT }, timeout: 30_000 })
  await recommender.post('/api/auth/login', { data: { email: 'e2e-judge1@test.local', password: PASSWORD } })
  await recommender.post('/api/me/active-identity', { data: { type: 'recommender' } })
  const application = await envelope(await recommender.post(`/api/tasks/${task.id}/applications`, { data: { note: 't106 funds matrix' } })) as { id: string }
  // pending → withdrawn（推荐官撤回）：商家侧报名面板即呈现资金区（exited=withdrawn）。
  await envelope(await recommender.post(`/api/tasks/${task.id}/applications/${application.id}/withdraw`, { data: {} }))
  await recommender.dispose()

  // ---- UI：商家工作台 → 该任务 → 报名面板 → withdrawn 行的资金区 ----
  await loginFront(page)
  const fundsArea = page.locator('[data-testid="exit-funds-result"]')
  await openMerchantTaskApplicants(page, title)
  await expect(fundsArea.first()).toBeVisible({ timeout: 30_000 })
  let fundsRequests = 0
  let failFunds = false
  let releaseFunds: (() => void) | undefined
  let fundsResponse = Promise.resolve()
  await page.route('**/api/tasks/*/applications/*/exit-funds', async (route) => {
    fundsRequests += 1
    await fundsResponse
    if (!failFunds) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: {
        operationId: 'op-t106', kind: 'no_fault', state: 'pending',
        amounts: { deposit_refundCents: 100, bounty_releaseCents: 500 },
        blockedReason: 'funds_pending', updatedAt: new Date().toISOString(),
      } }) })
    } else {
      await route.fulfill({ status: 503, contentType: 'text/plain', body: 'upstream unavailable' })
    }
  })
  await openMerchantTaskApplicants(page, title)
  await expect(fundsArea.first()).toBeVisible({ timeout: 30_000 })
  // 合成 pending：资金处理中可见；随后失败：错误呈现且快照保留（资金处理中文案仍在）。
  await expect(page.getByText('资金处理中').first()).toBeVisible({ timeout: 20_000 })
  const beforeFailure = fundsRequests
  failFunds = true
  fundsResponse = new Promise<void>(resolve => { releaseFunds = resolve })
  await fundsArea.first().locator('[data-action="refresh-exit-funds"]').click()
  await expect(fundsArea.first().locator('[data-action="refresh-exit-funds"]')).toBeDisabled()
  await expect(fundsArea.first()).toContainText('资金处理中')
  expect(fundsRequests).toBe(beforeFailure + 1)
  releaseFunds!()
  await expect(page.getByTestId('exit-funds-error').first()).toContainText('upstream unavailable')
  // 组件单测验证错误时保留快照；跨浏览器重进矩阵只验证错误态，不把重挂载后的
  // 初始“待查询”状态误判为丢失快照。
  expect(fundsRequests).toBe(beforeFailure + 1)
  const fundsShot = `${SHOT_DIR}/${testInfo.project.name}/workbench-funds-state-desktop-light.png`
  mkdirSync(path.dirname(fundsShot), { recursive: true })
  await page.setViewportSize({ width: 1440, height: 900 })
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
  await expect(fundsArea.first()).toBeVisible({ timeout: 30_000 })
  await page.screenshot({ path: fundsShot, fullPage: true })
  appendManifest(fundsShot, { engine: testInfo.project.name, page: 'workbench-funds', viewport: 'desktop', theme: 'light', state: 'matrix', role: '商家' })
  await shotMatrix(page, 'workbench-funds-error', async () => {
    await openMerchantTaskApplicants(page, title)
    await expect(fundsArea.first()).toBeVisible({ timeout: 30_000 })
    await expect(page.getByTestId('exit-funds-error').first()).toContainText('upstream unavailable')
  }, testInfo.project.name)
  await api.dispose()

  // ---- 画布绑定失败 → 重试幂等（同 operationId）----
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.evaluate(() => localStorage.setItem('theme-preference', 'light'))
  const bindBodies: string[] = []
  await page.unroute('**/api/tasks/*/applications/*/exit-funds')
  await page.route('**/api/video-production/storyboards/*/workspace', async (route) => {
    if (route.request().method() === 'POST') {
      bindBodies.push(route.request().postData() ?? '')
      await route.fulfill({ status: 503, contentType: 'text/plain', body: 'upstream unavailable' })
      return
    }
    await route.continue()
  })
  await page.goto(`${FRONT}/video-canvas?storyboard=00000000-0000-4000-8000-000000000000`)
  await expect(page.locator('[data-test="canvas-binding-failed"]')).toBeVisible({ timeout: 30_000 })
  await expect(page.locator('[data-test="canvas-binding-failed"]')).toContainText('upstream unavailable')
  await page.getByRole('button', { name: '重试连接' }).click()
  await expect(page.locator('[data-test="canvas-binding-failed"]')).toBeVisible({ timeout: 30_000 })
  expect(bindBodies.length, '重试应再次真实发起绑定 POST').toBeGreaterThanOrEqual(2)
  const operationIds = bindBodies.map(body => (JSON.parse(body) as { operationId?: string }).operationId)
  expect(operationIds[0]).toBeTruthy()
  expect(new Set(operationIds).size, '重试幂等：同目标复用同一 operationId').toBe(1)
  const canvasShot = `${SHOT_DIR}/${testInfo.project.name}/video-canvas-binding-failed-desktop-light.png`
  await page.screenshot({ path: canvasShot, fullPage: true })
  appendManifest(canvasShot, { engine: testInfo.project.name, page: 'video-canvas', viewport: 'desktop', theme: 'light', state: 'binding-failed+retry-idempotent', role: '商家' })
})

test('TC106-07-04：字幕真实加载/空/错/恢复与治理登录后长错误', async ({ page }, testInfo) => {
  test.setTimeout(180_000)
  expect(PASSWORD).toBeTruthy()
  await loginAi(page)
  let speechHits = 0
  let mode: 'error' | 'empty' | 'success' = 'error'
  let releaseHistory: (() => void) | undefined
  let historyResponse = Promise.resolve()
  await page.route('**/api/speech/transcriptions', async route => {
    speechHits += 1
    await historyResponse
    if (mode === 'error') {
      await route.fulfill({ status: 503, contentType: 'text/plain', body: 'upstream unavailable' })
    } else {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: { items:
        mode === 'empty' ? [] : [{ id: 't106-subtitle', status: 'succeeded', transcriptText: '验收字幕内容',
          durationMs: 3000, createdAt: '2026-09-22T00:00:00Z' }],
      } }) })
    }
  })
  for (const theme of ['light', 'dark'] as const) {
    for (const viewport of [{ id: 'desktop', width: 1440, height: 900 }, { id: 'mobile', width: 390, height: 844 }]) {
      await page.setViewportSize(viewport)
      await page.evaluate(value => localStorage.setItem('theme-preference', value), theme)
      await page.reload()
      await page.getByTestId('auth-pill').waitFor({ timeout: 30_000 })
      await page.waitForLoadState('networkidle')
      await page.getByRole('tab', { name: '视频工坊' }).click()
      await page.getByRole('button', { name: '字幕工作台', exact: true }).click()
      const before = speechHits
      historyResponse = new Promise<void>(resolve => { releaseHistory = resolve })
      await page.getByRole('button', { name: '从历史记录选择' }).click()
      await expect.poll(() => speechHits).toBeGreaterThan(before)
      await expect(page.getByTestId('subtitle-history-loading')).toBeVisible()
      await expect(page.getByTestId('subtitle-history-retry')).toBeDisabled()
      const loadingShot = `${SHOT_DIR}/${testInfo.project.name}/subtitle-loading-${viewport.id}-${theme}.png`
      mkdirSync(path.dirname(loadingShot), { recursive: true })
      await page.screenshot({ path: loadingShot, fullPage: true })
      appendManifest(loadingShot, { engine: testInfo.project.name, page: 'subtitles', viewport: viewport.id, theme, state: 'loading', role: 'AI 用户' })
      releaseHistory!()
      await expect(page.getByTestId('subtitle-history-error')).toContainText('upstream unavailable')
      await expect(page.getByTestId('subtitle-history-loading')).toHaveCount(0)
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
      await page.getByTestId('subtitle-history-retry').focus()
      await expect(page.getByTestId('subtitle-history-retry')).toBeFocused()
      expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(8)
      const shot = `${SHOT_DIR}/${testInfo.project.name}/subtitle-error-${viewport.id}-${theme}.png`
      mkdirSync(path.dirname(shot), { recursive: true })
      await page.screenshot({ path: shot, fullPage: true })
      appendManifest(shot, { engine: testInfo.project.name, page: 'subtitles', viewport: viewport.id, theme, state: '503', role: 'AI 用户' })
    }
  }
  mode = 'empty'
  await page.getByTestId('subtitle-history-retry').click()
  await expect(page.getByTestId('subtitle-history-empty')).toBeVisible()
  mode = 'success'
  await page.getByTestId('subtitle-history-retry').click()
  await page.locator('.vs-history-item').click()
  await expect(page.locator('.vs-cue-row input[type="text"]').first()).toHaveValue('验收字幕内容')

  const ops = await page.context().browser()!.newContext()
  try {
    const opsPage = await ops.newPage()
    await loginOps(opsPage)
    let hits = 0
    const longError = `upstream unavailable — ${'区域网关持续失败，请稍后重试。'.repeat(24)}`
    await opsPage.route('**/api/admin/**', async route => {
      hits += 1
      await route.fulfill({ status: 503, contentType: 'text/plain', body: longError })
    })
    for (const viewport of [{ id: 'desktop', width: 1440, height: 900 }, { id: 'mobile', width: 390, height: 844 }]) {
      await opsPage.setViewportSize(viewport)
      await opsPage.evaluate(() => localStorage.setItem('theme-preference', 'light'))
      await opsPage.reload()
      await expect(opsPage.locator('.ops-user')).toBeVisible()
      await expect(opsPage.locator('.error-msg[role="alert"]').first()).toContainText('区域网关持续失败')
      expect(hits).toBeGreaterThan(0)
      expect(await opsPage.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(8)
      await opsShot(opsPage, `ops-error-long-${viewport.id}`, 'light', '503-hit', testInfo.project.name, viewport.id)
    }
  } finally {
    await ops.close()
  }
})
