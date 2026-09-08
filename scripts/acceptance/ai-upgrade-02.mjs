// AI内容中心改造-02/03 浏览器验收：交付面板与导出、视频 inputMode 分支、图卡工作区恢复。
// 全 API 走 Playwright mock，不触真实模型/后端/外部写；导航全程在 ai.html 应用内完成。
import { chromium, expect } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'

const base = process.env.AI_UPGRADE_BASE || 'http://localhost:5173'
const out = 'test-artifacts/ai-upgrade-02'
await mkdir(out, { recursive: true })
const browser = await chromium.launch()
const shots = []

function mediaRef(position) {
  return { refType: 'media', id: randomUUID(), role: 'card', cardId: randomUUID(), position }
}

async function makeContext(theme, projects) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  await context.addInitScript(mode => localStorage.setItem('theme-preference', mode), theme)
  await context.route('**/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const send = (data, status = 200) => route.fulfill({ status, contentType: 'application/json',
      body: JSON.stringify(status >= 400 ? { success: false, error: data } : { success: true, data }) })
    if (path === '/api/auth/me') return send({ user: { id: 'upgrade2-fixture', email: 'fixture@example.test', displayName: '验收用户', role: 'user' } })
    if (path.startsWith('/api/credits')) return send({ balance: 100 })
    if (path.includes('style-skills')) return send({ TITLE_FORMULA: [], GENRE: [], STYLE: [] })
    if (path === '/api/creation-drafts' && request.method() === 'POST') {
      const body = request.postDataJSON()
      const draft = { ...body, id: randomUUID(), status: 'draft', version: 1, resultAssetIds: [], runIds: [],
        createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() }
      projects.set(draft.id, draft)
      return send(draft)
    }
    if (path === '/api/creation-drafts') return send({ items: [...projects.values()], nextCursor: null })
    if (path.startsWith('/api/creation-drafts/') && path.endsWith('/exports') && request.method() === 'POST') {
      const id = path.split('/')[3]
      const draft = projects.get(id)
      if (!draft) return send('草稿不存在', 404)
      const refs = draft.workspace?.resultRefs ?? []
      return send({ draftId: id, version: draft.version, expiresAt: new Date(Date.now() + 9e5).toISOString(),
        manifest: { title: draft.articleTitle ?? draft.title, content: draft.content, delivery: draft.workspace?.delivery },
        downloads: refs.map((ref, index) => index === 0
          ? { ...ref, url: 'https://storage.test/signed/m1', contentType: 'image/png' }
          : { ...ref, unavailable: 'expired' }) })
    }
    if (path.startsWith('/api/creation-drafts/')) {
      const id = path.split('/')[3]
      const draft = projects.get(id)
      if (!draft) return send('草稿不存在', 404)
      if (request.method() === 'PUT') {
        const body = request.postDataJSON()
        if (body.expectedVersion !== draft.version) return send('版本冲突', 409)
        Object.assign(draft, body, { version: draft.version + 1 })
      }
      return send(draft)
    }
    if (path === '/api/video-production/storyboard') {
      const frames = [
        { type: 'meta', storyboardId: randomUUID(), targetDurationSeconds: 30 },
        { type: 'shot', shot: { id: randomUUID(), seq: 1, visual: '开场说明问题', narration: '旁白一', plannedSeconds: 5, cameraMove: '固定机位', anchorImageIndex: 0, prompt: 'p' } },
        { type: 'shot', shot: { id: randomUUID(), seq: 2, visual: '演示步骤', narration: '旁白二', plannedSeconds: 5, cameraMove: '跟随运镜', anchorImageIndex: 0, prompt: 'p' } },
        { type: 'safety', safety: { status: 'pass', findings: [] } },
      ]
      const body = frames.map(frame => `data: ${JSON.stringify(frame)}`).join('\n\n') + '\n\ndata: [DONE]\n\n'
      return route.fulfill({ status: 200, contentType: 'text/event-stream', body })
    }
    if (path === '/api/media' || path.startsWith('/api/content-assets')) return send({ items: [] })
    return send({})
  })
  return context
}

/** ai.html 内进入指定平台/形式的独立创作（与 ai-upgrade-01 同款导航）。 */
async function startCreation(page, platformLabel, formLabel) {
  await page.goto(`${base}/ai.html`)
  await page.getByTestId('auth-pill').waitFor()
  await page.getByRole('button', { name: platformLabel, exact: false }).first().click()
  await page.locator('[aria-label="内容形式"] button', { hasText: formLabel }).click()
  await page.locator('.source-option', { hasText: '独立创作' }).click()
  await page.locator('textarea[name="creation-topic"]').fill('验收主题')
  await page.locator('button.primary-command').click()
}

/** 经「最近项目」继续种子项目（应用内导航，保持 ai.html 文档）。 */
async function openRecent(page, project) {
  await page.getByRole('tab', { name: '最近项目' }).click()
  await page.getByTestId('recent-list').waitFor()
  await page.locator('[data-testid="recent-list"] li', { hasText: project.title })
    .getByTestId('recent-continue').click()
}

try {
  // ---- 场景 A：小红书完成页交付面板 + 导出 + 声明缺项（双主题） ----
  for (const theme of ['light', 'dark']) {
    const projects = new Map()
    const article = {
      id: randomUUID(), title: '验收图文', capability: 'article', status: 'completed', version: 2,
      sourceType: 'independent', platform: 'xiaohongshu', contentForm: 'graphic', contentMode: 'article',
      topic: '验收主题', articleTitle: '验收标题', outline: '', content: '正文第一段。\n\n#探店 #开业酬宾',
      resultAssetIds: [], runIds: [], updatedAt: new Date().toISOString(),
      workspace: { schemaVersion: 1, capability: 'article', currentStep: 'content',
        inputs: { article: { completed: true } },
        resultRefs: [mediaRef(1), mediaRef(2)],
        delivery: { version: 1, platform: 'xiaohongshu', contentForm: 'graphic' } },
    }
    projects.set(article.id, article)
    const context = await makeContext(theme, projects)
    const page = await context.newPage()
    await page.goto(`${base}/ai.html`)
    await page.getByTestId('auth-pill').waitFor()
    await openRecent(page, article)
    await expect(page.locator('[data-test="delivery-panel"]')).toBeVisible()
    // 话题由正文派生为 ready；声明默认 pending → 待补
    await expect(page.locator('[data-test="delivery-readiness"]')).toContainText('声明状态待补')
    await page.locator('[data-test="delivery-export"]').click()
    await expect(page.locator('[data-test="delivery-downloads"]')).toBeVisible()
    await expect(page.locator('[data-test="delivery-downloads"]')).toContainText('已失效，重新导出可按授权重取')
    const file = `${out}/article-delivery-${theme}.png`
    await page.screenshot({ path: file, fullPage: true })
    shots.push(file)
    // 编辑话题 → 自动保存进 delivery（等待 800ms 防抖 + 请求）
    await page.locator('[data-test="delivery-topics"]').fill('#探店 #新店打卡')
    await expect(async () => {
      expect(projects.get(article.id).workspace.delivery.topics).toEqual(['探店', '新店打卡'])
    }).toPass()
    await context.close()
  }

  // ---- 场景 B：视频 inputMode 三分支 + Brief 加工方式 + 脚本分镜（双主题） ----
  for (const theme of ['light', 'dark']) {
    const projects = new Map()
    const context = await makeContext(theme, projects)
    const page = await context.newPage()
    await startCreation(page, '视频号', '视频')
    await expect(page.locator('[data-test="vp-input-mode-store"]')).toBeChecked()
    await expect(page.locator('#vp-shop-name')).toBeVisible()
    // Brief 加工方式分段（改造-02 §2.3）
    await page.locator('.creation-brief summary').click()
    await expect(page.locator('[data-test="brief-mode-create"]')).toBeChecked()
    await page.locator('[data-test="brief-mode-adapt"]').check()
    await expect(page.locator('.brief-mode-hint')).toContainText('保留事实与来源')
    // 切脚本分支：店铺字段隐藏、脚本框出现；无店铺照片可直接分镜
    await page.locator('[data-test="vp-input-mode-script"]').check()
    await expect(page.locator('#vp-shop-name')).toBeHidden()
    await expect(page.locator('#vp-script')).toBeVisible()
    const file = `${out}/video-script-mode-${theme}.png`
    await page.screenshot({ path: file, fullPage: true })
    shots.push(file)
    await page.locator('#vp-script').fill('开场直接说明问题：新手第一次组装电脑最容易忽略的三件事。'
      + '第一，电源功率要按整机功耗留余量；第二，内存插槽按说明书双通道站位；第三，机箱风道前进后出。'
      + '最后给出预算分配建议。')
    await page.getByRole('button', { name: /分镜/ }).first().click()
    await expect(page.locator('.step-active .step-label')).toContainText('编辑分镜')
    // 分镜步出现交付面板（配文独立编辑，改配文不触发重生成）
    await expect(page.locator('[data-test="delivery-panel"]')).toBeVisible()
    const composeFile = `${out}/video-storyboard-delivery-${theme}.png`
    await page.screenshot({ path: composeFile, fullPage: true })
    shots.push(composeFile)
    await context.close()
  }

  // ---- 场景 C：图卡工作区级恢复（计划/结果/已保存状态刷新不丢） ----
  const projects = new Map()
  const cardId = '11111111-2222-3333-4444-555555555555'
  const cardId2 = '99999999-8888-7777-6666-555555555555'
  const article = {
    id: randomUUID(), title: '图卡恢复', capability: 'article', status: 'in_progress', version: 1,
    sourceType: 'independent', platform: 'xiaohongshu', contentForm: 'graphic', contentMode: 'article',
    topic: '图卡主题', articleTitle: '标题', outline: '',
    content: '正文'.repeat(40) + '\n\n#图卡',
    resultAssetIds: [], runIds: [], updatedAt: new Date().toISOString(),
    workspace: { schemaVersion: 1, capability: 'article', currentStep: 'content',
      inputs: { article: {}, cards: {
        styleId: 'cute-fresh', layoutId: 'balanced', paletteId: 'macaron', size: '1024x1792', cardCount: 2,
        cards: [
          { cardId, position: 1, role: 'cover', title: '封面卡', bullets: ['要点'], illustration: '画面', caption: '' },
          { cardId: cardId2, position: 2, role: 'content', title: '内容卡', bullets: [], illustration: '画面', caption: '' },
        ],
        results: [
          { index: 0, cardId, role: 'cover', title: '封面卡', ok: true, url: '/image/cover' },
          { index: 1, cardId: cardId2, role: 'content', title: '内容卡', ok: false, errorReason: 'provider down' },
        ],
        persistedMediaIds: { [cardId]: 'media-saved-1' },
      } } },
  }
  projects.set(article.id, article)
  const context = await makeContext('light', projects)
  const page = await context.newPage()
  await page.goto(`${base}/ai.html`)
  await page.getByTestId('auth-pill').waitFor()
  await openRecent(page, article)
  await page.locator('[data-test="card-series-toggle"]').click()
  await expect(page.locator('[data-test="card-series-panel"]')).toBeVisible()
  // 计划与结果直接恢复（不用重新生成）；失败卡可重试；已保存卡带「已保存」
  await expect(page.locator('[data-test="card-series-result"]').first()).toBeVisible()
  await expect(page.locator('[data-test="card-series-failed"]')).toContainText('provider down')
  await expect(page.locator('[data-test="card-series-panel"]')).toContainText('已保存')
  const file = `${out}/cards-restored-light.png`
  await page.screenshot({ path: file, fullPage: true })
  shots.push(file)
  await context.close()

  console.log(`PASS: ${shots.length} screenshots; delivery panel/export, video inputMode and card restoration verified.`)
} catch (error) {
  for (const shot of shots) console.log('shot:', shot)
  console.error('FAIL:', error)
  process.exitCode = 1
} finally {
  await browser.close()
}
