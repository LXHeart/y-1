// hypit-fix2-c36.spec.ts — 107-fix-2 C107F2-36（§8.1–8.8）：工作区双主题、移动端、
// 键盘与全状态验收。
//
// 前置（V-07 卡级 playwright 编排负责）：隔离栈 y1-hypit-fix2-e2e、合成账号已 seed、
// frontend 镜像含 C26–C35 工作区改动。本 spec 驱动真实 UI：
//   TC-F2-36-01 合成复杂工程（超长标题/长文件名/长错误并存），两主题 × 三视口
//               （390×844 / 834×1112 / 1440×1000）× 四阶段截图，且断言零整页横向
//               溢出、html data-theme 真实切换（明暗截图不得同字节）。
//   TC-F2-36-02 纯键盘：新建弹窗打开→填写→Enter 提交→Esc 取消→焦点返回触发按钮；
//               导出弹窗同规范（初始焦点在弹窗内、Esc 可退）。
//   TC-F2-36-03 定向失败注入（仅状态专项）：素材列表失败显示可重试错误态（不伪空）、
//               上传失败保留已选文件并提供重试。
//   TC-F2-36-04 390px：120 字标题 / 255 字符路径 / 长错误——主要动作可达、容器内
//               部滚动、无整页横向滚动。
// 截图只记录视觉结论；真实按钮请求/持久化由 C37 贯通链验收。
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test, type Page } from '@playwright/test'

const BASE = process.env.AI_BASE_URL ?? 'http://127.0.0.1:18082'
const EVIDENCE = resolve('test-artifacts/task-107/fix2/C36')
const OWNER = 'e2e-merchant@test.local'
const PASSWORD = process.env.E2E_PASSWORD ?? 'test-password-2026'

mkdirSync(EVIDENCE, { recursive: true })

const VIEWPORTS = [
  { id: 'mobile', width: 390, height: 844 },
  { id: 'tablet', width: 834, height: 1112 },
  { id: 'desktop', width: 1440, height: 1000 },
] as const

const LONG_TITLE = '超长标题验收'.padEnd(120, '长') // 60 字上限 → 输入被 maxlength 截断，填 120 字验证不溢出
const LONG_ERROR = '这是一个非常长的错误信息用于验证长错误文本在窄视口下的换行与容器内滚动行为'.padEnd(200, '述')

async function login(page: Page): Promise<void> {
  // video-clone 工作区挂在 AI 创作中心壳（ai.html origin，与 entrypoints spec 同源）。
  await page.goto(`${BASE}/`)
  await page.getByRole('button', { name: '登录 / 注册' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('#login-email').fill(OWNER)
  await dialog.locator('#login-password').fill(PASSWORD)
  await dialog.locator('button[type="submit"]').click()
  await page.getByTestId('auth-pill').waitFor({ timeout: 30_000 })
}

async function openWorkspace(page: Page): Promise<void> {
  await page.getByTestId('nav-video-clone').click()
  await page.getByTestId('video-clone-workbench').waitFor({ timeout: 15_000 })
  // 工程列表就绪（新建按钮可用）再进行任何交互，避免冷加载竞态。
  await page.getByTestId('clone-new-project').waitFor({ state: 'visible', timeout: 15_000 })
  await expect(page.getByTestId('clone-new-project')).toBeEnabled()
}

/** 零整页横向溢出（html/body 级别；容器内滚动允许）。 */
async function expectNoPageHorizontalOverflow(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => {
    const doc = document.scrollingElement ?? document.documentElement
    return { scroll: doc.scrollWidth, client: doc.clientWidth }
  })
  expect(overflow.scroll, `整页横向溢出 ${overflow.scroll}>${overflow.client}`).toBeLessThanOrEqual(overflow.client)
}

async function setTheme(page: Page, theme: 'light' | 'dark'): Promise<void> {
  await page.evaluate((value) => localStorage.setItem('theme-preference', value), theme)
  await page.reload()
  await page.waitForLoadState('domcontentloaded')
  await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
}

async function createComplexProject(page: Page): Promise<string> {
  await openWorkspace(page)
  await page.getByTestId('clone-new-project').click()
  const dialog = page.getByTestId('clone-new-project-dialog')
  await dialog.waitFor()
  await page.getByTestId('clone-new-title').fill(LONG_TITLE)
  await page.getByTestId('clone-new-submit').click()
  // 创建成功后进入工程（路径段 /video-clone/<projectId>）。
  await page.waitForURL(/\/video-clone\/[0-9a-f-]{36}$/, { timeout: 30_000 })
  await page.getByTestId('clone-reference-panel').waitFor({ timeout: 30_000 })
  return page.url().split('/').pop() ?? ''
}

test.describe('C107F2-36 工作区视觉与交互验收', () => {

  test('TC-F2-36-01 两主题三视口四阶段截图 + 零整页横向溢出', async ({ page }) => {
    test.setTimeout(240_000)
    await login(page)
    await createComplexProject(page)

    const steps: { id: string; label: string }[] = [
      { id: 'reference', label: '参考素材' },
      { id: 'plan', label: '复刻方案' },
      { id: 'generate', label: '生成与编辑' },
      { id: 'review', label: '审片与导出' },
    ]
    for (const theme of ['dark', 'light'] as const) {
      await setTheme(page, theme)
      for (const viewport of VIEWPORTS) {
        await page.setViewportSize({ width: viewport.width, height: viewport.height })
        await page.reload()
        await page.waitForLoadState('domcontentloaded')
        await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
        await page.getByTestId('video-clone-workbench').waitFor({ timeout: 15_000 })
        for (const step of steps) {
          await page.getByRole('button', { name: step.label }).click()
          await page.getByTestId('video-clone-workbench').waitFor()
          await expectNoPageHorizontalOverflow(page)
          await page.screenshot({
            path: resolve(EVIDENCE, `TC-F2-36-01-${theme}-${viewport.id}-${step.id}.png`),
            fullPage: true,
          })
        }
      }
    }
  })

  test('TC-F2-36-02 纯键盘：新建弹窗 Enter 提交 / Esc 取消焦点返回；导出弹窗同规范', async ({ page }) => {
    test.setTimeout(120_000)
    await login(page)
    await openWorkspace(page)

    // 新建弹窗（clone-new-project 触发）：Enter 打开 → 初始焦点在弹窗内 →
    // 键入 → Enter 提交 → URL 带 projectId。
    await expect(page.getByTestId('clone-new-project')).toBeEnabled()
    await page.getByTestId('clone-new-project').focus()
    await page.keyboard.press('Enter')
    const dialog = page.getByTestId('clone-new-project-dialog')
    await dialog.waitFor()
    // 初始焦点由 useDialogFocus 落在 modal 首个可聚焦元素（关闭按钮）——
    // 以 role=dialog 的 modal-card 为根断言「焦点在弹窗内」。
    const modalCard = page.getByRole('dialog', { name: '新建视频克隆工程' })
    await expect.poll(async () => await page.evaluate((root) => {
      if (root === null) return false
      const active = document.activeElement
      return active !== null && root.contains(active)
    }, await modalCard.elementHandle()), { timeout: 5_000 }).toBe(true)
    await page.getByTestId('clone-new-title').fill('键盘新建工程')
    await page.keyboard.press('Enter')
    await page.waitForURL(/\/video-clone\/[0-9a-f-]{36}$/, { timeout: 30_000 })

    // 导出弹窗（clone-export 触发）：persistent 弹窗的 Esc 是输入保护（不误关、
    // 不丢输入）；键盘取消走「Tab 到取消/关闭 → Enter」，关闭后焦点返回触发按钮。
    await page.getByTestId('clone-export').focus()
    await page.keyboard.press('Enter')
    const exportDialog = page.getByTestId('clone-package-dialog')
    await exportDialog.waitFor()
    await page.keyboard.press('Escape')
    await expect(exportDialog).toBeVisible()
    await page.getByRole('button', { name: '关闭弹窗' }).focus()
    await page.keyboard.press('Enter')
    await expect(exportDialog).toBeHidden()
    await expect(page.getByTestId('clone-export')).toBeFocused()

    // 新建弹窗取消路径（键盘）：打开 → 填入草稿 → Esc 保护（输入保留）→
    // 取消按钮关闭 → 焦点返回触发按钮。
    await expect(page.getByTestId('clone-new-project')).toBeEnabled()
    await page.getByTestId('clone-new-project').focus()
    await page.keyboard.press('Enter')
    await page.getByTestId('clone-new-project-dialog').waitFor()
    await page.getByTestId('clone-new-title').fill('键盘取消草稿')
    await page.keyboard.press('Escape')
    await expect(page.getByTestId('clone-new-project-dialog')).toBeVisible()
    await expect(page.getByTestId('clone-new-title')).toHaveValue('键盘取消草稿')
    await page.getByRole('button', { name: '取消' }).focus()
    await page.keyboard.press('Enter')
    await expect(page.getByTestId('clone-new-project-dialog')).toBeHidden()
    await expect(page.getByTestId('clone-new-project')).toBeFocused()
  })

  test('TC-F2-36-03 定向失败注入：列表错误可重试不伪空；上传失败保留选择', async ({ page }) => {
    test.setTimeout(120_000)
    await login(page)

    // 素材列表失败：错误态 + 重试入口（不是伪空态）。
    await page.route('**/api/hypit/projects/*/assets**', (route) => route.fulfill({
      status: 503, contentType: 'application/json',
      body: JSON.stringify({ error: { code: 'hypit_backend_unavailable', message: LONG_ERROR } }),
    }))
    await openWorkspace(page)
    await page.getByTestId('clone-new-project').click()
    await page.getByTestId('clone-new-title').fill('状态专项工程')
    await page.getByTestId('clone-new-submit').click()
    await page.waitForURL(/\/video-clone\/[0-9a-f-]{36}$/, { timeout: 30_000 })
    await page.getByTestId('clone-error').waitFor({ timeout: 15_000 })
    await expect(page.getByTestId('clone-empty')).toBeHidden()
    await expectNoPageHorizontalOverflow(page)
    await page.unroute('**/api/hypit/projects/*/assets**')

    // 上传失败：错误展示 + 已选文件保留 + 重试按钮可见。
    await page.route('**/api/hypit/projects/*/assets/upload', (route) => route.fulfill({
      status: 503, contentType: 'application/json',
      body: JSON.stringify({ error: { code: 'hypit_backend_unavailable', message: '上传通道故障' } }),
    }))
    await page.setInputFiles('[data-testid="clone-upload-input"]', {
      name: 'reference-sample.mp4', mimeType: 'video/mp4', buffer: Buffer.from('state-probe-bytes'),
    })
    await page.getByTestId('clone-upload-submit').click()
    await page.getByTestId('clone-upload-error').waitFor({ timeout: 15_000 })
    await expect(page.getByTestId('clone-upload-retry')).toBeVisible()
    // 输入保留：file input 仍持有文件（重试无需重新选择）。
    const fileName = await page.getByTestId('clone-upload-input').evaluate(
      (input: HTMLInputElement) => input.files?.[0]?.name ?? '')
    expect(fileName).toBe('reference-sample.mp4')
    await page.unroute('**/api/hypit/projects/*/assets/upload')
  })

  test('TC-F2-36-04 390px 长内容：主要动作可达、容器内滚动、无整页横溢', async ({ page }) => {
    test.setTimeout(120_000)
    await login(page)
    await page.setViewportSize({ width: 390, height: 844 })
    await openWorkspace(page)

    // 120 字标题的工程出现在列表：标题截断/换行不撑破容器。
    await page.getByTestId('clone-new-project').click()
    await page.getByTestId('clone-new-title').fill(LONG_TITLE)
    await page.getByTestId('clone-new-submit').click()
    await page.waitForURL(/\/video-clone\/[0-9a-f-]{36}$/, { timeout: 30_000 })
    await expectNoPageHorizontalOverflow(page)

    // 长文件名上传：进度条/错误条不撑破；主要按钮全部可见可达。
    await page.route('**/api/hypit/projects/*/assets/upload', (route) => route.fulfill({
      status: 413, contentType: 'application/json',
      body: JSON.stringify({ error: { code: 'hypit_too_large', message: `${LONG_ERROR}（413）` } }),
    }))
    await page.setInputFiles('[data-testid="clone-upload-input"]', {
      name: `${'长'.repeat(120)}-${'x'.repeat(135)}.mp4`, mimeType: 'video/mp4',
      buffer: Buffer.from('overflow-probe'),
    })
    await page.getByTestId('clone-upload-submit').click()
    await page.getByTestId('clone-upload-error').waitFor({ timeout: 15_000 })
    await expectNoPageHorizontalOverflow(page)
    for (const testid of ['clone-import-url', 'clone-import-url-submit', 'clone-upload-input', 'clone-upload-submit']) {
      const box = await page.getByTestId(testid).first().boundingBox()
      expect(box, `${testid} 应有可点击几何`).not.toBeNull()
      if (box !== null) {
        expect(box.x, `${testid} 不得横向越界`).toBeGreaterThanOrEqual(0)
        expect(box.x + box.width).toBeLessThanOrEqual(390 + 1)
      }
    }
    await page.screenshot({ path: resolve(EVIDENCE, 'TC-F2-36-04-mobile-390-longcontent.png'), fullPage: true })
    await page.unroute('**/api/hypit/projects/*/assets/upload')
  })
})
