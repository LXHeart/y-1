import { expect, test, type Page } from '@playwright/test'
import {
  adoptedProject,
  jobSnapshot,
  newSession,
  SOURCE_TEXT,
  stubStudioApis,
} from './fixtures/creation-studio'

/**
 * 任务书 #101 C101-13：M1 图卡完整流程 e2e（TC101-061~064 / SC101-01~05）。
 *
 * 业务态（计划/任务/采用）由浏览器网络 fixture 模拟（§12：覆盖 6 张图的慢、错、
 * unknown 与交互状态）；草稿保存走真实后端（版本串联可查）。完整生产链证据在
 * 后端 IT（VisualJobIT/VisualExecutionIT/VisualAdoptionIT）——UI mock 不冒充。
 *
 * 真实模型验收按 V-LIVE-IMAGE 另记（未授权保持 NOT_RUN，不阻塞本卡）。
 */
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
    item.request().method() === 'POST' && item.url().endsWith('/api/auth/login'), { timeout: 30_000 })
  await dialog.locator('button[type="submit"]').click()
  expect((await response).status()).toBe(200)
  await page.getByTestId('auth-pill').waitFor({ timeout: 30_000 })
}

/** 从创作中心进入小红书图文（从已有内容开始 → adapt 原稿路径）。 */
async function startAdaptSession(page: Page): Promise<void> {
  await page.goto(aiBaseURL + '/')
  await page.getByRole('button', { name: '小红书' }).click()
  await page.getByRole('button', { name: '图文', exact: true }).click()
  await page.getByRole('button', { name: '从已有内容开始' }).click()
  await page.getByRole('button', { name: '开始创作' }).click()
}

async function importSource(page: Page): Promise<void> {
  const input = page.getByRole('textbox', { name: /原稿/ }).first()
  await input.fill(SOURCE_TEXT)
  await page.getByRole('button', { name: /导入并编辑/ }).click()
  await expect(page.getByTestId('studio-plan-launch')).toBeVisible({ timeout: 20_000 })
}

test.describe('M1 图卡完整流程', () => {
  test('M1 独立小红书稿：原稿→计划→部分成功→重做→采用→刷新恢复', async ({ page }) => {
    const session = newSession()
    // 任务快照队列：慢（running）→ 第3张失败 → 重做后全成功（GET 依序弹出）
    session.jobQueue = [
      jobSnapshot('job-e2e-1', 'running', [
        { itemId: 'item-1', state: 'dispatching' },
        { itemId: 'item-2', state: 'queued' },
        { itemId: 'item-3', state: 'queued' },
        { itemId: 'item-4', state: 'queued' },
        { itemId: 'item-5', state: 'queued' },
        { itemId: 'item-6', state: 'queued' },
      ]),
      jobSnapshot('job-e2e-1', 'partial', [
        { itemId: 'item-1', state: 'succeeded', artifactId: 'art-1', deliveryMediaId: 'media-del-item-1' },
        { itemId: 'item-2', state: 'succeeded', artifactId: 'art-2', deliveryMediaId: 'media-del-item-2' },
        { itemId: 'item-3', state: 'failed' },
        { itemId: 'item-4', state: 'succeeded', artifactId: 'art-4', deliveryMediaId: 'media-del-item-4' },
        { itemId: 'item-5', state: 'succeeded', artifactId: 'art-5', deliveryMediaId: 'media-del-item-5' },
        { itemId: 'item-6', state: 'succeeded', artifactId: 'art-6', deliveryMediaId: 'media-del-item-6' },
      ]),
      jobSnapshot('job-e2e-1', 'succeeded', [
        { itemId: 'item-1', state: 'succeeded', artifactId: 'art-1', deliveryMediaId: 'media-del-item-1' },
        { itemId: 'item-2', state: 'succeeded', artifactId: 'art-2', deliveryMediaId: 'media-del-item-2' },
        { itemId: 'item-3', state: 'succeeded', artifactId: 'art-3', deliveryMediaId: 'media-del-item-3' },
        { itemId: 'item-4', state: 'succeeded', artifactId: 'art-4', deliveryMediaId: 'media-del-item-4' },
        { itemId: 'item-5', state: 'succeeded', artifactId: 'art-5', deliveryMediaId: 'media-del-item-5' },
        { itemId: 'item-6', state: 'succeeded', artifactId: 'art-6', deliveryMediaId: 'media-del-item-6' },
      ]),
    ]
    await stubStudioApis(page, session)
    await loginOnAiApp(page)
    await startAdaptSession(page)
    await importSource(page)

    // 计划：发起 → 一次轮询落定 ready → 确认
    await page.getByTestId('studio-plan-launch').click()
    await expect(page.getByTestId('plan-confirm')).toBeVisible({ timeout: 30_000 })
    await page.getByTestId('plan-confirm').click()

    // 制作面板：费用确认 → 开始生成（慢态可见）
    await page.getByTestId('visual-quote-start').click()
    await expect(page.getByTestId('visual-cost-text')).toBeVisible()
    await expect(page.getByTestId('visual-cost-text')).toContainText('图片生成')
    await page.getByTestId('visual-cost-ok').click()
    await expect(page.getByTestId('visual-item-1')).toContainText('生成中', { timeout: 30_000 })

    // 部分成功：第3张失败可重做，其余候选保留（AC101-11/13：断线不丢成功项）
    await expect(page.getByTestId('visual-job-state')).toContainText('部分成功', { timeout: 60_000 })
    await expect(page.getByTestId('visual-item-3')).toContainText('失败')
    await expect(page.locator('[data-test^="visual-candidate-"]')).toHaveCount(5)
    // 重做第 3 张：新费用确认 → 只含该项的新任务（重放队列尾的全成功快照）
    await page.getByTestId('visual-redo-3').click()
    await page.getByTestId('visual-cost-ok').click({ timeout: 30_000 })
    await expect(page.getByTestId('visual-job-state')).toContainText('已成功', { timeout: 60_000 })
    await expect(page.locator('[data-test^="visual-candidate-"]')).toHaveCount(6)

    // 采用第 2 页候选（AC101-12）：选择→采用→已采用标记
    await page.getByTestId('visual-candidate-2').getByTestId('visual-candidate-select').click()
    await page.getByTestId('visual-adopt').click()
    await expect(page.getByTestId('visual-adopted-badge')).toBeVisible({ timeout: 30_000 })
    expect(session.adoptedSelections).toEqual([{ itemId: 'item-2', artifactId: 'art-2' }])

    // 刷新恢复：draft 深链带回已采用媒体（服务端写回经 fixture 呈现）
    const draftUrl = page.url()
    await page.route('**/api/creation-drafts/draft-e2e-1', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ success: true, data: adoptedProject(session.adoptedMediaIds) }),
        })
        return
      }
      await route.continue()
    })
    await page.goto(draftUrl)
    await page.reload()
    await expect(page.getByTestId('card-series-panel')).toBeVisible({ timeout: 30_000 })
    await expect(page.getByTestId('visual-candidate-2').getByTestId('visual-candidate-select'))
      .toContainText('已采用', { timeout: 30_000 })
  })

  test('M1 unknown 结果：逐项确认闸，未确认不发起重做', async ({ page }) => {
    const session = newSession()
    session.jobQueue = [
      jobSnapshot('job-e2e-1', 'unknown', [
        { itemId: 'item-1', state: 'succeeded', artifactId: 'art-1', deliveryMediaId: 'media-del-item-1' },
        { itemId: 'item-2', state: 'unknown' },
      ]),
    ]
    await stubStudioApis(page, session)
    await loginOnAiApp(page)
    await startAdaptSession(page)
    await importSource(page)
    await page.getByTestId('studio-plan-launch').click()
    await page.getByTestId('plan-confirm').click({ timeout: 30_000 })
    await page.getByTestId('visual-quote-start').click()
    await page.getByTestId('visual-cost-ok').click()
    await expect(page.getByTestId('visual-redo-unknown-2')).toBeVisible({ timeout: 60_000 })
    // 未确认：不产生任何 create/estimate 请求
    const created = session.jobQueue.length
    await page.getByTestId('visual-redo-unknown-2').click()
    await expect(page.getByTestId('visual-unknown-ok')).toBeVisible()
    await page.getByTestId('visual-unknown-cancel').click()
    expect(session.jobQueue.length).toBe(created)
    await expect(page.getByTestId('visual-unknown-ok')).toHaveCount(0)
  })

  test('M1 旧深链与旧图卡：?draft= 恢复存量 cards 只读可用', async ({ page }) => {
    const session = newSession()
    await stubStudioApis(page, session)
    await loginOnAiApp(page)
    await page.route('**/api/creation-drafts/legacy-draft-1', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ success: true, data: {
            id: 'legacy-draft-1', title: '旧图卡草稿', capability: 'article', status: 'in_progress',
            version: 4, platform: 'xiaohongshu', contentForm: 'graphic', topic: '旧主题',
            content: SOURCE_TEXT, resultAssetIds: ['media-old-1'], runIds: [],
            updatedAt: '2026-09-12T00:00:00Z',
            workspace: {
              schemaVersion: 1, capability: 'article', currentStep: 'content',
              inputs: {
                cards: {
                  cards: [{ cardId: 'old-1', position: 1, role: 'cover', title: '旧卡',
                    bullets: [], illustration: '旧画面', caption: '' }],
                  results: [],
                  persistedMediaIds: { 'old-1': 'media-old-1' },
                },
              },
              resultRefs: [
                { id: 'media-old-1', refType: 'media', role: 'card', cardId: 'old-1', position: 1 },
              ],
            },
          } }),
        })
        return
      }
      await route.continue()
    })
    await page.goto(`${aiBaseURL}/article?draft=legacy-draft-1`)
    await expect(page.getByTestId('card-series-panel')).toBeVisible({ timeout: 30_000 })
    await page.getByTestId('card-series-toggle').click()
    // 旧结果入口可见（新版分支不存在时旧面板直接可用）
    await expect(page.getByTestId('legacy-cards-toggle').or(page.getByTestId('card-series-plan'))).toBeVisible()
  })
})
