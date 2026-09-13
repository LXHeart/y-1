import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium, firefox, webkit } from 'playwright'

/** #102 C14: UI fixtures verify interaction and geometry; C15 owns the real Edge/provider/media closure. */
const baseUrl = process.env.BASE_URL || 'http://127.0.0.1:18080'
const aiBaseUrl = process.env.AI_BASE_URL || 'http://127.0.0.1:18082'
const output = resolve(process.env.CANVAS_SHOT_DIR || 'test-artifacts/task-102/shots/c102-14')
const SB = '11111100-0000-4000-8000-000000000001'
const DRAFT = '11111100-0000-4000-8000-000000000002'
const TASK = '11111100-0000-4000-8000-000000000003'
const PLAN = '11111100-0000-4000-8000-000000000004'
const CHILD = '11111100-0000-4000-8000-000000000005'
const CHILD_DRAFT = '11111100-0000-4000-8000-000000000006'
const MEDIA = '11111100-0000-4000-8000-000000000007'
const FINAL = '11111100-0000-4000-8000-000000000008'
const shotRows = id => [1, 2, 3].map(seq => ({ id: `${id.replace('11111100', '22222200').slice(0, -2)}${id === SB ? 'a' : 'b'}${seq}`, seq,
  visual: ['门店外景与招牌', '制作过程的细节', '邀请顾客到店体验'][seq - 1], narration: '保留品牌名称和门店特点',
  plannedSeconds: 5, cameraMove: '固定机位', anchorImageIndex: 0, status: 'draft', takes: [], source: { kind: 'generated' } }))
const makeProject = (id, storyboardId, title) => ({ id, title, sourceType: 'independent', capability: 'video', status: 'draft', version: 3,
  platform: 'douyin', resultAssetIds: [], runIds: [], updatedAt: '2026-09-13T00:00:00Z',
  workspace: { schemaVersion: 1, capability: 'video', currentStep: 'storyboard', inputs: { video: { storyboardId } }, delivery: { version: 1, platform: 'douyin', contentForm: 'video' } } })

async function fixture(context) {
  const state = { failPlan: true, failCanvas: false, failContent: false, failDraft: false, completed: false, plan: null, requests: [], violations: [], plans: [] }
  const projects = { [SB]: makeProject(DRAFT, SB, '门店短片 · 周末到店体验'), [CHILD]: makeProject(CHILD_DRAFT, CHILD, '方案 B · 制作细节') }
  const boards = Object.fromEntries([SB, CHILD].map(id => [id, { id, status: 'draft', editVersion: 2, targetDurationSeconds: 15, resolution: '1080x1920', grouping: null, shots: shotRows(id) }]))
  const canvases = {}
  const variants = [{ draftId: DRAFT, storyboardId: SB, title: projects[SB].title, parentStoryboardId: null, rootStoryboardId: SB, sourceEditVersion: null, createdAt: '2026-09-13T00:00:00Z' },
    { draftId: CHILD_DRAFT, storyboardId: CHILD, title: projects[CHILD].title, parentStoryboardId: SB, rootStoryboardId: SB, sourceEditVersion: 2, createdAt: '2026-09-13T00:00:00Z' }]
  await context.route('**/api/**', async route => {
    const request = route.request(); const path = new URL(request.url()).pathname; const method = request.method()
    const body = request.postData() ? request.postDataJSON() : null
    state.requests.push({ path, method, body })
    const reply = (data, status = 200, code) => route.fulfill({ status, json: status < 400 ? { success: true, data } : { success: false, error: data, code } })
    if (path === '/api/auth/me') return reply({ user: { id: 'canvas-visual-user', email: 'canvas@test.invalid', displayName: '验收账号', role: 'user' } })
    if (path === '/api/me/identities') return reply([{ id: 'identity', identityType: 'recommender', organizationId: null, status: 'active' }])
    if (path === '/api/me/store-scopes') return reply([])
    if (path === '/api/me/active-identity') return reply({ identityType: 'recommender' })
    if (path === '/api/me/notifications/unread-count') return reply({ unreadCount: 0 })
    if (path === '/api/content-assets') return reply({ items: [{ id: MEDIA, mediaId: MEDIA, title: '门店参考海报', mimeType: 'image/png', status: 'active', validUntil: null }] })
    if (path === `/api/media/${MEDIA}` || path.endsWith('/download-url')) return reply({ id: MEDIA, downloadUrl: '/fixture/canvas-image.png', mimeType: 'image/png' })
    if (path === '/api/video-production/capabilities') return reply({ mode: 'video', available: true, provider: 'sandbox', model: 'sandbox-video', unitPriceCents: 1,
      video: { available: true, provider: 'sandbox', model: 'sandbox-video', unitPriceCents: 1 }, tts: { available: true, model: 'sandbox-tts' } })
    if (path === '/api/video-production/tasks') return reply([])
    if (path === `/api/video-production/tasks/${TASK}`) return reply({ id: TASK, storyboardId: SB, phase: 'succeeded', mode: 'video', progress: 100,
      targetDurationSeconds: 15, estimatedCostCents: 15, actualCostCents: 15, actualDurationSeconds: 15, unitPriceCents: 1,
      selection: {}, recommended: {}, shots: [], recomposeSeq: 2, finalMediaId: FINAL, srtMediaId: null, finalUrl: '/fixture/canvas-video.mp4', subtitleUrl: null })
    if (path.startsWith('/api/video-production/storyboards/')) {
      const id = path.split('/')[4]; const board = boards[id]; if (!board) return reply('分镜不存在', 404)
      if (path.endsWith('/workspace')) {
        if (state.completed && id === SB) { board.status = 'committed'; projects[id].workspace.inputs.video.productionTaskId = TASK }
        return reply({ project: projects[id], storyboardId: id, productionTaskId: state.completed && id === SB ? TASK : null, editVersion: board.editVersion })
      }
      if (path.endsWith('/variants')) return reply({ items: variants })
      if (path.endsWith('/sources')) {
        for (const update of body.sources) board.shots.find(shot => shot.id === update.shotId).source = update.source
        return reply({ storyboardId: id, editVersion: ++board.editVersion, sources: body.sources })
      }
      return reply(board)
    }
    if (/\/shots\/[^/]+\/content$/.test(path)) {
      if (state.failContent) return reply('镜头保存失败，请重试', 500)
      const shotId = path.split('/')[4]; const board = Object.values(boards).find(board => board.shots.some(shot => shot.id === shotId))
      Object.assign(board.shots.find(shot => shot.id === shotId), body)
      return reply({ editVersion: ++board.editVersion, updatedShotIds: [shotId] })
    }
    if (path.startsWith('/api/creation-drafts/')) {
      const id = path.split('/')[3]; const project = Object.values(projects).find(item => item.id === id)
      if (path.endsWith('/canvas')) {
        if (method === 'PUT') {
          if (state.failCanvas) return reply('画布保存失败，已保留当前编辑', 500)
          assert.equal(body.expectedRevision, canvases[id]?.revision ?? 0)
          canvases[id] = { id: `canvas-${id}`, draftId: id, document: body.document, revision: (canvases[id]?.revision ?? 0) + 1, updatedAt: '2026-09-13T00:00:00Z' }
        }
        return reply(canvases[id] ?? null)
      }
      if (path.endsWith('/exports')) {
        assert.equal(body.version, project.version)
        return reply({ draftId: id, version: project.version, manifest: { delivery: project.workspace.delivery, resultRefs: project.workspace.resultRefs }, downloads: [] })
      }
      if (method === 'PUT') {
        if (state.failDraft) return reply('交付保存失败，请重试', 500)
        Object.assign(project, body, { version: project.version + 1 })
      }
      return reply(project)
    }
    if (path === '/api/creation-assistant/canvas/plans' && method === 'POST') {
      state.plans.push(body)
      if (!state.plan) state.plan = { id: PLAN, status: 'ready', draftId: DRAFT, storyboardId: SB, baseDraftVersion: projects[SB].version,
        baseEditVersion: body.expectedEditVersion, baseCanvasRevision: body.expectedCanvasRevision, summary: '补充所选镜头的画面细节',
        action: { kind: 'edit', actions: [{ kind: 'update-shot', patch: { shotId: body.selectedNodeIds[0].slice(5), visual: '新的门店外景与清晰招牌' } }] },
        clarification: null, errorCode: null, runId: 'fixture-run', expiresAt: new Date(Date.now() + 1800000).toISOString() }
      if (state.failPlan) { state.failPlan = false; return reply('网络暂不可用，请恢复上次请求', 502, 'EDGE_BAD_GATEWAY') }
      return reply(state.plan)
    }
    if (path === `/api/creation-assistant/canvas/plans/${PLAN}/apply`) {
      const shotId = state.plan.action.actions[0].patch.shotId
      boards[SB].shots.find(shot => shot.id === shotId).visual = '新的门店外景与清晰招牌'
      state.plan.status = 'applied'
      return reply({ planId: PLAN, draftId: DRAFT, storyboardId: SB, editVersion: ++boards[SB].editVersion, affectedShotIds: [shotId], variant: null, preparedGeneration: null })
    }
    if (path === `/api/creation-assistant/canvas/plans/${PLAN}`) return reply(state.plan)
    if (method === 'GET') return reply({ items: [] })
    state.violations.push(`${method} ${path}`); return reply('未允许的业务请求', 500)
  })
  return { state, boards, projects, canvases }
}

async function waitFor(predicate, label) {
  for (let count = 0; count < 100; count++) { if (await predicate()) return; await new Promise(resolve => setTimeout(resolve, 50)) }
  assert.ok(await predicate(), label)
}
async function closeDrawer(page) {
  if (!await page.locator('[role="dialog"]').count()) return
  await page.locator('[data-action="close-modal"]').click()
  await waitFor(async () => !await page.locator('[role="dialog"]').count(), 'drawer should close after saved edits')
}
async function openDetail(page, mode, mobile) {
  const dialog = page.locator('[role="dialog"]')
  if (mobile && await dialog.count()) {
    await dialog.locator('.canvas-detail-switch').getByRole('button', { name: { shot: '镜头', assistant: 'AI 助手', variants: '方案', delivery: '交付' }[mode], exact: true }).click()
  } else if (mode === 'variants' && !mobile) await page.locator('[data-test="director-tab-variants"]').click()
  else await page.locator(`[data-test="${{ shot: 'canvas-toggle-detail', assistant: 'canvas-toggle-assistant', variants: 'canvas-toggle-variants', delivery: 'canvas-toggle-delivery' }[mode]}"]`).click()
}
async function geometry(page, viewport, label) {
  const result = await page.evaluate(() => {
    const board = document.querySelector('[data-test="canvas-board"]')
    const main = document.querySelector('.canvas-primary')
    const visible = selector => [...document.querySelectorAll(selector)].filter(element => element.getBoundingClientRect().width > 0)
    return { overflow: document.documentElement.scrollWidth - window.innerWidth, board: board?.getBoundingClientRect().width,
      main: main?.getBoundingClientRect().width, rail: parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--layout-rail')),
      details: visible('[data-test="canvas-detail-panel"]').length, docks: visible('.canvas-docked-panel').length }
  })
  assert.ok(result.overflow <= 1, `${label}: whole-page overflow ${result.overflow}`)
  assert.ok(result.details <= 1, `${label}: duplicate detail panes`)
  if (viewport.width >= 1024) assert.ok(result.main >= result.rail, `${label}: canvas width ${result.main}`)
  else assert.equal(result.docks, 0, `${label}: fixed rail squeezes the workspace`)
}
async function focusAndTargets(page) {
  const problems = await page.evaluate(() => {
    const roots = [...document.querySelectorAll('.video-canvas,.canvas-responsive-content')]
    const buttons = [...new Set(roots.flatMap(root => [...root.querySelectorAll('button, select')]))]
    return buttons.filter(button => {
      const rect = button.getBoundingClientRect()
      return rect.width > 0 && rect.height > 0 && !button.closest('[inert]') && (rect.width < 43.5 || rect.height < 43.5)
    }).map(button => `${button.textContent?.trim()} ${button.getBoundingClientRect().width}x${button.getBoundingClientRect().height}`)
  })
  assert.deepEqual(problems, [], 'visible buttons and selects must have separate 44px targets')
  const dialog = page.locator('[role="dialog"]')
  if (await dialog.count()) {
    const close = dialog.locator('[data-action="close-modal"]'); await close.focus(); await page.keyboard.press('Shift+Tab')
    assert.ok(await dialog.evaluate(element => element.contains(document.activeElement)), 'backward Tab left drawer')
    await page.keyboard.press('Tab'); assert.ok(await close.evaluate(element => element === document.activeElement), 'forward Tab failed to wrap')
  }
}

await mkdir(output, { recursive: true })
const browserName = process.env.CANVAS_VISUAL_BROWSER || 'chromium'
const browserType = { chromium, firefox, webkit }[browserName]
assert.ok(browserType, 'CANVAS_VISUAL_BROWSER must name a supported browser')
const browser = await browserType.launch({ headless: true })
const passed = []; const failures = []
const views = [{ width: 1440, height: 900 }, { width: 820, height: 1180 }, { width: 390, height: 844 }]
try {
  for (const entry of ['grassland', 'ai', 'quick']) for (const viewport of views) for (const theme of ['light', 'dark']) {
    const label = `${entry}-${theme}-${viewport.width}`; const mobile = viewport.width < 1024
    if (process.env.VISUAL_FILTER && !label.includes(process.env.VISUAL_FILTER)) continue
    const context = await browser.newContext({ viewport, acceptDownloads: true, reducedMotion: 'reduce' })
    await context.addInitScript(value => localStorage.setItem('theme-preference', value), theme)
    const f = await fixture(context); const page = await context.newPage(); const errors = []
    page.on('pageerror', error => errors.push(error.message))
    if (entry === 'ai' && process.env.AI_HTML_ENTRY === '1') await context.route('**/video-canvas?*', async route => {
      if (route.request().resourceType() !== 'document') return route.fallback()
      const response = await route.fetch({ url: `${aiBaseUrl}/ai.html` }); await route.fulfill({ response })
    })
    try {
      const url = `${entry === 'ai' ? aiBaseUrl : baseUrl}/${entry === 'quick' ? 'video-production' : 'video-canvas'}?storyboard=${SB}&draft=${DRAFT}`
      await page.goto(url, { waitUntil: 'domcontentloaded' })
      if (entry === 'quick') {
        await page.locator('[data-test="open-canvas-mode"]').waitFor()
        await page.screenshot({ path: resolve(output, `C102-14-${label}-normal.png`), fullPage: !await page.locator('[role="dialog"]').count() })
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1))
        assert.deepEqual(errors, []); passed.push(label); continue
      }
      await page.locator('[data-test="canvas-project-header"]').waitFor()
      await page.locator(viewport.width < 768 ? '[data-test="canvas-list-edit-1"]' : '[data-test="canvas-node-1"]').waitFor()
      assert.equal(await page.locator('[data-test="canvas-shot-list"]').count(), viewport.width < 768 ? 1 : 0)
      await geometry(page, viewport, label)
      await page.screenshot({ path: resolve(output, `C102-14-${label}-normal.png`), fullPage: !await page.locator('[role="dialog"]').count() })
      const trigger = page.locator(viewport.width < 768 ? '[data-test="canvas-list-edit-1"]' : '[data-test="canvas-node-1"]')
      await trigger.focus(); await trigger.press('Enter')
      await page.locator('[data-test="director-visual"]').waitFor()
      await page.locator('[data-test="director-visual"]').fill('新的门店外景，保留品牌名称')
      await focusAndTargets(page)
      await page.screenshot({ path: resolve(output, `C102-14-${label}-properties.png`), fullPage: !await page.locator('[role="dialog"]').count() })
      await openDetail(page, 'assistant', mobile)
      await page.locator('[data-test="canvas-assistant-instruction"]').waitFor();assert.equal(await page.locator('[data-test="director-panel"]:visible').count(), 0)
      const instruction = page.locator('[data-test="canvas-assistant-instruction"]'); await instruction.fill('只修改当前镜头\n保留品牌名')
      await instruction.press('Control+Enter')
      await page.locator('[data-test="canvas-assistant-retry"]').waitFor()
      await page.screenshot({ path: resolve(output, `C102-14-${label}-network-error.png`), fullPage: !await page.locator('[role="dialog"]').count() })
      await page.locator('[data-test="canvas-assistant-retry"]').click(); await page.locator('[data-test="canvas-assistant-status-ready"]').waitFor()
      assert.deepEqual(f.state.plans[1], f.state.plans[0])
      await page.screenshot({ path: resolve(output, `C102-14-${label}-ready.png`), fullPage: !await page.locator('[role="dialog"]').count() })
      await page.locator('[data-test="canvas-assistant-apply"]').click(); await page.locator('[data-test="canvas-assistant-status-applied"]').waitFor()
      await geometry(page, viewport, label); await focusAndTargets(page)
      await page.screenshot({ path: resolve(output, `C102-14-${label}-applied.png`), fullPage: !await page.locator('[role="dialog"]').count() })
      await openDetail(page, 'shot', mobile); await openDetail(page, 'variants', mobile)
      await page.locator('[data-test="canvas-variant-compare"]').selectOption(CHILD)
      await page.screenshot({ path: resolve(output, `C102-14-${label}-variants.png`), fullPage: !await page.locator('[role="dialog"]').count() })
      await closeDrawer(page)
      await page.locator('[data-test="canvas-add-note"]').click(); await page.locator('[data-test="canvas-note-input"]').fill('外景先于内景，保留镜头节奏')
      await page.locator('[data-test="canvas-reference-target"]').selectOption(`shot:${f.boards[SB].shots[0].id}`)
      await page.locator('[data-test="canvas-reference-add-edge"]').click(); await closeDrawer(page)
      if (!await page.locator('[data-test="canvas-asset-add-' + MEDIA + '"]').isVisible()) await page.locator('[data-test="canvas-toggle-assets"]').click()
      await page.locator(`[data-test="canvas-asset-add-${MEDIA}"]`).click(); await page.locator('[data-test="canvas-reference-inspector"]').waitFor()
      await page.screenshot({ path: resolve(output, `C102-14-${label}-references.png`), fullPage: !await page.locator('[role="dialog"]').count() })
      await closeDrawer(page)
      f.state.completed = true; await page.reload(); await page.locator('[data-test="canvas-toggle-delivery"]').click()
      const title = page.locator('[data-test="delivery-title"]'); await title.fill('门店短片发布标题')
      const download = page.waitForEvent('download'); await page.locator('[data-test="delivery-export"]').click(); await (await download).saveAs(resolve(output, `${label}-manifest.json`))
      await geometry(page, viewport, label); await focusAndTargets(page)
      await page.screenshot({ path: resolve(output, `C102-14-${label}-delivery.png`), fullPage: !await page.locator('[role="dialog"]').count() })
      assert.deepEqual(f.state.violations, []); assert.deepEqual(errors, [])
      passed.push(label); console.log(`${label}: geometry, single pane, keyboard, states, references and delivery verified (UI fixtures)`)
    } catch (error) {
      failures.push(`${label}: ${error.stack || error.message}`)
      await page.screenshot({ path: resolve(output, `C102-14-${label}-failure.png`), fullPage: !await page.locator('[role="dialog"]').count() }).catch(() => {})
      console.error(failures[failures.length - 1])
    } finally { await context.close() }
  }
} finally { await browser.close() }
await writeFile(resolve(output, 'verify-report.json'), JSON.stringify({ passed, failures, browserName, baseUrl, aiBaseUrl, generatedAt: new Date().toISOString() }, null, 2))
assert.deepEqual(failures, [])
console.log(`C102-14: ${passed.length} entry/theme/viewport combinations verified; ${output}`)
