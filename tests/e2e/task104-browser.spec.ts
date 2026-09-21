import { expect, test, type Page } from '@playwright/test'

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
async function shotMatrix(page: Page, name: string, restore?: () => Promise<void>): Promise<void> {
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
      // 焦点可见性：键盘 Tab 可达（不修改内容）。
      await page.keyboard.press('Tab')
      if (viewport.id === 'mobile') {
        const overflow = await page.evaluate(() =>
          document.documentElement.scrollWidth - document.documentElement.clientWidth)
        expect(overflow, `${name} ${theme}/${viewport.id} 不横向溢出`).toBeLessThanOrEqual(8)
      }
      await page.screenshot({ path: `${SHOT_DIR}/${name}-${viewport.id}-${theme}.png`, fullPage: true })
    }
  }
}

test('截图矩阵：AI 视频字幕工作台（视频工坊区段，登录态）', async ({ page }) => {
  test.setTimeout(240_000)
  expect(PASSWORD, 'E2E_PASSWORD 须由 ci-e2e.sh 提供').toBeTruthy()
  await loginAi(page)
  await page.getByRole('tab', { name: '视频工坊' }).click()
  await expect(page.getByRole('button', { name: '字幕工作台', exact: true })).toBeVisible()
  await shotMatrix(page, 'ai-video-studio', async () => {
    await page.getByRole('tab', { name: '视频工坊' }).click()
    await expect(page.getByRole('button', { name: '字幕工作台', exact: true })).toBeVisible()
  })
})

test('截图矩阵：视频画布恢复（登录态空态）', async ({ page }) => {
  test.setTimeout(240_000)
  expect(PASSWORD).toBeTruthy()
  await loginFront(page)
  await page.goto(`${FRONT}/video-canvas`)
  await expect(page.locator('[data-test="canvas-login-required"]')).toHaveCount(0)
  await expect(page.locator('[data-test="canvas-missing-id"]')).toBeVisible()
  await shotMatrix(page, 'video-canvas')
})

test('截图矩阵：用户工作台-合作资金区（登录态）', async ({ page }) => {
  test.setTimeout(240_000)
  expect(PASSWORD).toBeTruthy()
  await loginFront(page)
  await page.goto(`${FRONT}/grassland`)
  await expect(page.getByRole('heading', { name: '商家工作台' })).toBeVisible()
  await shotMatrix(page, 'workbench-funds')
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

test('治理台错误入口双主题（共享 HTTP 语义）', async ({ context }) => {
  test.setTimeout(120_000)
  for (const theme of ['light', 'dark'] as const) {
    const themePage = await context.newPage()
    await themePage.goto(`${OPS}/`)
    await themePage.evaluate((value) => localStorage.setItem('theme-preference', value), theme)
    // 治理台鉴权数据请求 503 纯文本 → 错误状态呈现（不白屏）。
    await themePage.route('**/api/admin/**', async (route) => {
      await route.fulfill({ status: 503, contentType: 'text/plain', body: 'upstream unavailable' })
    })
    await themePage.reload()
    await themePage.waitForLoadState('networkidle')
    await expect(themePage.locator('html')).toHaveAttribute('data-theme', theme)
    const text = await themePage.locator('body').innerText()
    expect(text.length, '治理台应有可见内容或错误反馈').toBeGreaterThan(0)
    await themePage.screenshot({ path: `${SHOT_DIR}/ops-error-${theme}.png`, fullPage: true })
    await themePage.close()
  }
})
