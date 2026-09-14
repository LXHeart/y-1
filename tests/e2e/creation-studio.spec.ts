import { expect, test, type Page, type TestInfo } from '@playwright/test'
import { mkdir, readFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import {
  ACCOUNT_ID, DRAFT_ID, SOURCE_TEXT, byTestId, newSession, seedCompleted, stubStudioApis, writeTheme,
} from './fixtures/creation-studio'

/** Browser HTTP fixtures exercise UI only. Java IT independently exercises actual services, DB and provider requests. */
// V-LIVE-IMAGE and V-LIVE-WECHAT remain NOT_RUN here; this suite never uses real accounts or model credentials.
const aiBaseURL = process.env.AI_BASE_URL || 'http://127.0.0.1:28082'
const clientBaseURL = process.env.BASE_URL || 'http://127.0.0.1:28080'
async function start(page: Page, platform = 'xiaohongshu', recipe = 'social-card-series'): Promise<void> {
  await page.goto(aiBaseURL + '/')
  await expect(byTestId(page, 'auth-pill')).toBeVisible()
  await page.locator('[data-platform-id="' + platform + '"]').click()
  await page.getByRole('group', { name: '内容形式', exact: true }).getByRole('button', { name: '图文', exact: true }).click()
  await page.getByRole('group', { name: '创作来源', exact: true }).getByRole('button', { name: /独立创作/ }).click()
  await page.locator('[data-recipe-id="' + recipe + '"]').click()
  await byTestId(page, 'source-text').fill(SOURCE_TEXT)
  await byTestId(page, 'source-import').click()
  await expect(page.getByRole('textbox', { name: '文章正文', exact: true })).toHaveValue(SOURCE_TEXT)
}
async function plan(page: Page): Promise<void> {
  await byTestId(page, 'studio-plan-launch').click()
  await expect(byTestId(page, 'plan-confirm')).toBeEnabled()
  await byTestId(page, 'plan-confirm').click()
  await expect(byTestId(page, 'visual-quote-start')).toBeEnabled()
}
async function generateAll(page: Page): Promise<void> {
  await page.getByRole('radio', { name: /生成整套/ }).check()
  await byTestId(page, 'visual-quote-start').click()
  await expect(byTestId(page, 'visual-cost-text')).toContainText('6 次图片生成')
  await byTestId(page, 'visual-cost-ok').click()
}
async function shot(page: Page, info: TestInfo, name: string): Promise<void> {
  await mkdir('test-artifacts/task-101/ui', { recursive: true })
  await page.screenshot({ path: 'test-artifacts/task-101/ui/' + info.project.name + '-' + name + '.png', fullPage: true })
}
function watchErrors(page: Page): string[] {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  return errors
}

test('M1 用户端任务草稿可由地址直接恢复并再次刷新', async ({ page }) => {
  const session = newSession(); seedCompleted(session, 'douyin')
  const errors = watchErrors(page)
  session.project!.sourceType = 'task'
  session.project!.taskId = '00000101-0000-4000-8000-000000000010'
  session.project!.taskVersion = 1
  const workspace = session.project!.workspace as Record<string, unknown>
  const inputs = workspace.inputs as Record<string, unknown>
  inputs.contextSnapshotId = '00000101-0000-4000-8000-000000000011'
  await stubStudioApis(page, session)
  await page.goto(clientBaseURL + '/article?draft=' + DRAFT_ID)
  await expect(byTestId(page, 'delivery-title')).toHaveValue('原稿图文验收')
  await page.reload()
  await expect(byTestId(page, 'delivery-title')).toHaveValue('原稿图文验收')
  expect(session.requests.filter(request => request.path === '/api/creation-drafts/' + DRAFT_ID && request.method === 'GET').length).toBeGreaterThanOrEqual(2)
  expect(errors).toEqual([])
})

test('M1 原稿、六图部分成功、单图重做、采用与刷新恢复', async ({ page }, info) => {
  const session = newSession(), errors = watchErrors(page)
  await stubStudioApis(page, session)
  await start(page)
  await plan(page)
  await generateAll(page)
  await expect(byTestId(page, 'visual-job-state')).toContainText('部分成功')
  await expect(page.locator('figure[data-test^="visual-candidate-"]')).toHaveCount(5)
  await byTestId(page, 'visual-redo-3').click()
  await byTestId(page, 'visual-cost-ok').click()
  await expect(page.locator('figure[data-test^="visual-candidate-"]')).toHaveCount(6)
  const creates = session.requests.filter(request => request.path.endsWith('/visual-jobs') && request.method === 'POST')
  expect(creates).toHaveLength(2)
  expect(creates[1].body.selectedItemIds).toHaveLength(1)
  await byTestId(page, 'visual-candidate-2').locator('[data-test="visual-candidate-select"]').click()
  await byTestId(page, 'visual-adopt').click()
  await expect(byTestId(page, 'visual-adopted-badge')).toBeVisible()
  await expect(page).toHaveURL(new RegExp('draft=' + DRAFT_ID))
  await shot(page, info, 'm1-adopted-light')
  await page.reload()
  await expect(byTestId(page, 'visual-candidate-2').locator('[data-test="visual-candidate-select"]')).toContainText('已采用')
  expect(errors).toEqual([])
})

test('M1 封面完成后可用真实封面参考生成剩余页', async ({ page }) => {
  const session = newSession(); session.failItem = null
  await stubStudioApis(page, session)
  await start(page); await plan(page)
  await byTestId(page, 'visual-quote-start').click()
  await expect(byTestId(page, 'visual-cost-text')).toContainText('1 次图片生成')
  await byTestId(page, 'visual-cost-ok').click()
  await expect(byTestId(page, 'visual-candidate-1')).toBeVisible()
  await byTestId(page, 'visual-candidate-1').locator('[data-test="visual-candidate-select"]').click()
  await byTestId(page, 'visual-consistency').selectOption('reference-image')
  await page.getByRole('radio', { name: /生成剩余/ }).check()
  await byTestId(page, 'visual-quote-start').click()
  await expect(byTestId(page, 'visual-cost-text')).toContainText('5 次图片生成')
  await byTestId(page, 'visual-cost-ok').click()
  await expect(page.locator('figure[data-test^="visual-candidate-"]')).toHaveCount(6)
  const creates = session.requests.filter(request => request.path.endsWith('/visual-jobs') && request.method === 'POST')
  expect(creates[0].body.selectedItemIds).toHaveLength(1)
  expect(creates[1].body.selectedItemIds).toHaveLength(5)
  expect(creates[1].body.anchorArtifactId).toBeTruthy()
})

test('M1 自动保存失败后刷新仍恢复已创建图片任务且不重复生成', async ({ page }) => {
  const session = newSession()
  await stubStudioApis(page, session)
  await start(page); await plan(page)
  let jobCreated = false
  page.on('request', request => {
    if (new URL(request.url()).pathname === '/api/creation-studio/visual-jobs' && request.method() === 'POST') jobCreated = true
  })
  await page.route('**/api/creation-drafts/' + DRAFT_ID, route => jobCreated && route.request().method() === 'PUT'
    ? route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ success: false, error: '模拟保存暂不可用' }) })
    : route.fallback())
  await generateAll(page)
  await expect(byTestId(page, 'visual-job-state')).toContainText('部分成功')
  await expect(page).toHaveURL(/studioJob=/)
  await page.reload()
  await expect(byTestId(page, 'visual-job-state')).toContainText('部分成功')
  await expect(page.locator('figure[data-test^="visual-candidate-"]')).toHaveCount(5)
  expect(session.requests.filter(request => request.path.endsWith('/visual-jobs') && request.method === 'POST')).toHaveLength(1)
})

test('M1 未知结果重做需确认风险和费用；准备中计划刷新可恢复', async ({ page }, info) => {
  const session = newSession(); session.failItem = null; session.unknownItem = 2; session.preparePending = true
  await stubStudioApis(page, session)
  await start(page)
  await byTestId(page, 'studio-plan-launch').click()
  await expect(byTestId(page, 'plan-preparing')).toBeVisible()
  await expect(page).toHaveURL(/studioPlan=/)
  await page.reload()
  await expect(byTestId(page, 'plan-confirm')).toBeEnabled()
  await byTestId(page, 'plan-confirm').click()
  await generateAll(page)
  const before = session.requests.filter(request => request.path.endsWith('/visual-jobs') && request.method === 'POST').length
  await byTestId(page, 'visual-redo-unknown-2').click()
  await byTestId(page, 'visual-unknown-cancel').click()
  expect(session.requests.filter(request => request.path.endsWith('/visual-jobs') && request.method === 'POST')).toHaveLength(before)
  await byTestId(page, 'visual-redo-unknown-2').click()
  await byTestId(page, 'visual-unknown-ok').click()
  await expect(byTestId(page, 'visual-cost-ok')).toBeVisible()
  expect(session.requests.filter(request => request.path.endsWith('/visual-jobs') && request.method === 'POST')).toHaveLength(before)
  await shot(page, info, 'm1-unknown-confirm')
  await byTestId(page, 'visual-cost-ok').click()
  await expect(page.locator('figure[data-test^="visual-candidate-"]')).toHaveCount(6)
})

for (const theme of ['light', 'dark'] as const) {
  test('M3 连接弹窗键盘循环、逐层关闭与密钥清理，' + theme + ' 主题', async ({ page }, info) => {
    const session = newSession(); seedCompleted(session)
    await stubStudioApis(page, session); await writeTheme(page, theme)
    await page.goto(aiBaseURL + '/article?draft=' + DRAFT_ID)
    await byTestId(page, 'delivery-wechat-accounts').click()
    await byTestId(page, 'wechat-bind-open').click()
    const dialog = page.getByRole('dialog', { name: '绑定公众号', exact: true })
    const close = dialog.getByRole('button', { name: '关闭弹窗' })
    await expect(close).toBeFocused()
    await byTestId(page, 'wechat-bind-secret').fill('FIXTURE-SECRET-NOT-A-CREDENTIAL')
    await byTestId(page, 'wechat-bind-submit').focus()
    await page.keyboard.press('Tab')
    await expect(close).toBeFocused()
    await page.keyboard.press('Shift+Tab')
    await expect(byTestId(page, 'wechat-bind-submit')).toBeFocused()
    await page.setViewportSize({ width: 390, height: 844 })
    await shot(page, info, 'SC101-09-bind-mobile-' + theme)
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await expect(page.getByRole('dialog', { name: '公众号连接管理', exact: true })).toBeVisible()
    await expect(byTestId(page, 'wechat-bind-open')).toBeFocused()
    await byTestId(page, 'wechat-bind-open').click()
    await expect(byTestId(page, 'wechat-bind-secret')).toHaveValue('')
    await page.keyboard.press('Escape')
    await page.keyboard.press('Escape')
    await expect(byTestId(page, 'delivery-wechat-accounts')).toBeFocused()
    expect(session.requests.some(request => request.path.endsWith('/accounts') && request.method === 'POST')).toBe(false)
  })

  test('M2 原稿排版不触发改写，' + theme + ' 主题与移动端', async ({ page }, info) => {
    const session = newSession(), errors = watchErrors(page)
    await stubStudioApis(page, session); await writeTheme(page, theme)
    await start(page, 'wechat-official', 'article-format')
    await byTestId(page, 'format-render').click()
    await expect(byTestId(page, 'format-preview-body')).toContainText('人均 68 元')
    expect(session.requests.some(request => /text-proposals|visual-plans|article-generation\/(titles|outline|content)/.test(request.path))).toBe(false)
    await shot(page, info, 'm2-format-' + theme)
    await page.setViewportSize({ width: 390, height: 844 })
    await shot(page, info, 'm2-format-mobile-' + theme)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    await byTestId(page, 'format-theme').focus()
    // macOS Safari uses Option+Tab for all controls when system full keyboard access is off.
    await page.keyboard.press(info.project.name === 'webkit' && process.platform === 'darwin' ? 'Alt+Tab' : 'Tab')
    await expect(byTestId(page, 'format-include-title')).toBeFocused()
    expect(await page.evaluate(() => getComputedStyle(document.activeElement!).outlineStyle)).toBe('solid')
    await page.getByRole('textbox', { name: '文章正文', exact: true }).focus()
    expect(await page.evaluate(() => getComputedStyle(document.activeElement!).outlineStyle)).toBe('solid')
    expect(errors).toEqual([])
  })
}

test('M2 指定已保存版本下载真实 ZIP 并核验图片与内容', async ({ page }, info) => {
  const session = newSession(); seedCompleted(session)
  await stubStudioApis(page, session)
  await page.goto(aiBaseURL + '/article?draft=' + DRAFT_ID)
  await expect(byTestId(page, 'studio-export')).toBeEnabled()
  await byTestId(page, 'delivery-summary').fill('用户编辑后的摘要')
  const download = page.waitForEvent('download')
  await byTestId(page, 'studio-export').click()
  const result = await download, path = await result.path()
  expect(await result.failure()).toBeNull()
  const bytes = await readFile(path!)
  expect(bytes.readUInt32LE(0)).toBe(0x04034b50)
  expect(bytes.includes(Buffer.from('article.html'))).toBe(true)
  expect(bytes.includes(Buffer.from('images/01-cover.png'))).toBe(true)
  expect(bytes.includes(Buffer.from(SOURCE_TEXT))).toBe(true)
  const generated = [...session.exports.values()][0]
  expect(createHash('sha256').update(bytes).digest('hex')).toBe(createHash('sha256').update(generated).digest('hex'))
  await expect(byTestId(page, 'studio-export-done')).toContainText('已开始下载')
  await shot(page, info, 'm2-delivery-download')
})

test('M3 显式选择账号；搜索失败仍可手填草稿 ID 核实', async ({ page }, info) => {
  const session = newSession(); seedCompleted(session); session.syncUnknown = true; session.failCandidates = true
  const errors = watchErrors(page)
  await stubStudioApis(page, session)
  await page.goto(aiBaseURL + '/article?draft=' + DRAFT_ID)
  await byTestId(page, 'delivery-wechat-sync').click()
  await expect(byTestId(page, 'wechat-preview-account')).toHaveValue('')
  await expect(byTestId(page, 'wechat-preview-submit')).toBeDisabled()
  await byTestId(page, 'wechat-preview-account').selectOption(ACCOUNT_ID)
  await expect(byTestId(page, 'wechat-preview-submit')).toBeEnabled()
  await shot(page, info, 'm3-preview-light')
  await byTestId(page, 'wechat-preview-submit').click()
  await expect(byTestId(page, 'wechat-sync-unknown')).toBeVisible()
  await byTestId(page, 'wechat-sync-candidates').click()
  await expect(byTestId(page, 'wechat-sync-action-error')).toContainText('搜索超时')
  await byTestId(page, 'wechat-sync-manual-media-id').fill('FIXTURE-WX-ID')
  await byTestId(page, 'wechat-sync-manual-verify').click()
  await expect(byTestId(page, 'wechat-sync-state')).toHaveText('已存入草稿箱')
  expect(session.requests.filter(request => request.path.endsWith('/draft-syncs') && request.method === 'POST')).toHaveLength(1)
  await shot(page, info, 'm3-reconciled')
  expect(errors).toEqual([])
})

test('M1 导入错误保留输入、关闭新功能仍可恢复旧稿', async ({ page }, info) => {
  const session = newSession(); session.sourceFailure = true
  await stubStudioApis(page, session)
  await page.goto(aiBaseURL + '/')
  await page.locator('[data-platform-id="xiaohongshu"]').click()
  await page.getByRole('group', { name: '内容形式', exact: true }).getByRole('button', { name: '图文', exact: true }).click()
  await page.getByRole('group', { name: '创作来源', exact: true }).getByRole('button', { name: /独立创作/ }).click()
  await page.locator('[data-recipe-id="social-card-series"]').click()
  await byTestId(page, 'source-text').fill(SOURCE_TEXT)
  await byTestId(page, 'source-import').click()
  await expect(byTestId(page, 'source-error')).toBeVisible()
  await expect(byTestId(page, 'source-text')).toHaveValue(SOURCE_TEXT)
  await shot(page, info, 'm1-import-error')
  seedCompleted(session); session.writesEnabled = false
  await page.goto(aiBaseURL + '/article?draft=' + DRAFT_ID)
  await expect(byTestId(page, 'delivery-title')).toHaveValue('原稿图文验收')
  await expect(byTestId(page, 'studio-export')).toHaveCount(0)
})
