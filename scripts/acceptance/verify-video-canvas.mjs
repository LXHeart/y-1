import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium } from 'playwright'

/**
 * 任务书 #100 C100-08/C100-19：画布双入口浏览器验收辅助（§8.4 / V-UI / TC-020 视觉与键盘面）。
 *
 * 两入口（草场 / AI 应用）专业模式 × 共享快速模式，3 视口 × 2 主题逐组合截图 +
 * 键盘可达性检查（Tab 到画布节点、focus-visible 样式存在）。浏览器本地 fixtures：
 * 全部 /api/** 由路由桩回放（与 qa-ops-layout.mjs 同款约定），业务写操作仅允许
 * 画布绑定 POST（真实链路的采用/合成由 e2e spec 与 VideoCanvasMilestoneIT 承担）。
 *
 * C100-19 扩展：方案页签切换（A↔B 深链互切）与 AI 助手面板状态机（错误/ready/applied）
 * 交互矩阵——全部走路由桩（UI 状态机与布局层；真实计划链路在 IT）。
 *
 * 用法：node scripts/acceptance/verify-video-canvas.mjs   # 需隔离栈前端可达
 *   BASE_URL（缺省 http://127.0.0.1:18080）/ AI_BASE_URL（缺省 http://127.0.0.1:18082）
 *   截图落 test-artifacts/task-100/C100-08/
 */
const baseUrl = process.env.BASE_URL || 'http://127.0.0.1:18080'
const aiBaseUrl = process.env.AI_BASE_URL || 'http://127.0.0.1:18082'
const output = resolve('test-artifacts/task-100/C100-08')

const STORYBOARD_ID = '11111100-0000-4000-8000-000000000001'
const DRAFT_ID = '11111100-0000-4000-8000-000000000002'
const TASK_ID = '11111100-0000-4000-8000-000000000003'
const FINAL_URL = 'https://media.example.test/signed'
/** C100-19：派生方案 B（3 镜独立副本；真实派生链在 e2e/IT，此处只供切换桩）。 */
const VARIANT_STORYBOARD_ID = '11111100-0000-4000-8000-0000000000b1'
const VARIANT_DRAFT_ID = '11111100-0000-4000-8000-0000000000b2'
const PLAN_ID = '11111100-0000-4000-8000-0000000000p1'

const shots = Array.from({ length: 5 }, (_, index) => ({
  id: `22222200-0000-4000-8000-0000000000${index + 1}`,
  seq: index + 1,
  visual: `第${index + 1}镜画面`,
  narration: `第${index + 1}镜旁白`,
  plannedSeconds: 5,
  cameraMove: '固定机位',
  anchorImageIndex: 1,
  prompt: `第${index + 1}镜提示词`,
  status: 'ready',
  anchorUrl: null,
  grouping: null,
  takes: [1, 2].map(takeNo => ({
    id: `33333300-0000-4000-8000-000000000${String(index * 2 + takeNo).padStart(2, '0')}`,
    takeNo,
    status: 'succeeded',
    attempts: 1,
    provider: 'sandbox',
    model: 'sandbox-video-v1',
    mediaId: `44444400-0000-4000-8000-000000000${String(index * 2 + takeNo).padStart(2, '0')}`,
    durationMs: 2000,
    errorCode: null,
    errorMessage: null,
    selectable: true,
    score: takeNo === 1 ? 88 : 76,
    scoreLabels: [],
    url: takeNo === 1 ? FINAL_URL : null,
  })),
}))

const variantShots = shots.slice(0, 3).map((shot, index) => ({
  ...shot,
  id: `22222200-0000-4000-8000-000000000b${index + 1}`,
  seq: index + 1,
  takes: [],
}))

const task = {
  id: TASK_ID,
  storyboardId: STORYBOARD_ID,
  mode: 'video',
  phase: 'succeeded',
  progress: 100,
  targetDurationSeconds: 25,
  provider: 'sandbox',
  model: 'sandbox-video-v1',
  unitPriceCents: 1,
  estimatedCostCents: 25,
  actualCostCents: 10,
  actualDurationSeconds: 10,
  errorCode: null,
  errorMessage: null,
  selectionVersion: 5,
  selection: Object.fromEntries(shots.map(shot => [shot.id, shot.takes[1].id])),
  recommended: Object.fromEntries(shots.map(shot => [shot.id, shot.takes[0].id])),
  finalUrl: FINAL_URL,
  subtitleUrl: FINAL_URL,
  shots,
}

const draft = {
  id: DRAFT_ID,
  title: 'C100-08 验收草稿',
  status: 'in_progress',
  version: 3,
  platform: 'douyin',
  workspace: {
    schemaVersion: 1,
    capability: 'video',
    currentStep: 'compose',
    inputs: {
      video: { storyboardId: STORYBOARD_ID, productionTaskId: TASK_ID },
      videoCanvas: { schemaVersion: 1, scale: 1, panX: 0, panY: 0, positions: {} },
    },
    delivery: { version: 1, platform: 'douyin', contentForm: 'video', summary: '验收配文' },
  },
  resultAssetIds: [],
  runIds: [],
  updatedAt: new Date().toISOString(),
}

const variantDraft = {
  ...draft,
  id: VARIANT_DRAFT_ID,
  title: 'C100-19 方案B（验收桩）',
  version: 1,
  workspace: {
    ...draft.workspace,
    inputs: { video: { storyboardId: VARIANT_STORYBOARD_ID } },
  },
  delivery: undefined,
}

/** 独立方案谱系（API-12 形状：根 + 派生；比较字段来自明确行，不伪造评分）。 */
const variantsList = {
  items: [
    {
      draftId: DRAFT_ID, storyboardId: STORYBOARD_ID, parentStoryboardId: null,
      rootStoryboardId: STORYBOARD_ID, sourceEditVersion: null,
      title: 'C100-08 验收草稿', createdAt: '2026-09-12T00:00:00Z',
    },
    {
      draftId: VARIANT_DRAFT_ID, storyboardId: VARIANT_STORYBOARD_ID,
      parentStoryboardId: STORYBOARD_ID, rootStoryboardId: STORYBOARD_ID,
      sourceEditVersion: 2, title: 'C100-19 方案B（验收桩）', createdAt: '2026-09-12T01:00:00Z',
    },
  ],
}

/** AI 助手拦截层计划载荷（真实服务链在 CanvasWorkflowIntegrationIT；此处驱动 UI 状态机）。 */
function readyPlanFixture(shotId) {
  return {
    id: PLAN_ID, status: 'ready', draftId: DRAFT_ID, storyboardId: STORYBOARD_ID,
    baseDraftVersion: 3, baseEditVersion: 2, baseCanvasRevision: 1,
    summary: '把选中镜头画面改得更抓人', clarification: null,
    action: { kind: 'edit', actions: [{ kind: 'update-shot', patch: { shotId, visual: 'AI 改写的画面' } }] },
    runId: '11111100-0000-4000-8000-0000000000r1', errorCode: null,
    expiresAt: new Date(Date.now() + 30 * 60_000).toISOString(),
  }
}

const capabilities = {
  mode: 'video',
  video: { available: true, provider: 'sandbox', model: 'sandbox-video-v1', unitPriceCents: 1, reason: '' },
  tts: { available: true, model: 'sandbox-tts-v1', reason: '' },
  provider: 'sandbox',
  model: 'sandbox-video-v1',
  available: true,
  reason: '',
}

function apiFixture(method, url) {
  const path = url.pathname
  if (method === 'GET' && path === '/api/auth/me') {
    return { user: { id: 'c100-08-visual', email: 'c100-08@test.invalid', displayName: '验收账号', role: 'user' } }
  }
  // 壳层身份/通知（形状敏感：数组/对象/数字各异，坏形状会炸 useActiveIdentity）
  if (method === 'GET' && path === '/api/me/identities') {
    return [{ id: '55555500-0000-4000-8000-000000000001', identityType: 'recommender',
      organizationId: null, status: 'active' }]
  }
  if (method === 'GET' && path === '/api/me/store-scopes') {
    return []
  }
  if (method === 'GET' && path === '/api/me/notifications/unread-count') {
    return { unreadCount: 0 }
  }
  if (method === 'GET' && path === `/api/video-production/storyboards/${STORYBOARD_ID}`) {
    return { id: STORYBOARD_ID, targetDurationSeconds: 25, resolution: '1080x1920', editVersion: 2,
      status: 'draft', grouping: null, shots, groupingBranches: [] }
  }
  if (method === 'GET' && path === `/api/video-production/storyboards/${VARIANT_STORYBOARD_ID}`) {
    return { id: VARIANT_STORYBOARD_ID, targetDurationSeconds: 15, resolution: '1080x1920',
      editVersion: 1, status: 'draft', grouping: null, shots: variantShots, groupingBranches: [] }
  }
  if (method === 'GET' && path === `/api/video-production/storyboards/${STORYBOARD_ID}/variants`) {
    return variantsList
  }
  if (method === 'GET' && path === `/api/video-production/storyboards/${VARIANT_STORYBOARD_ID}/variants`) {
    return variantsList
  }
  if (method === 'GET' && path === `/api/video-production/tasks/${TASK_ID}`) {
    return task
  }
  if (method === 'GET' && path === `/api/creation-drafts/${DRAFT_ID}`) {
    return draft
  }
  if (method === 'GET' && path === `/api/creation-drafts/${VARIANT_DRAFT_ID}`) {
    return variantDraft
  }
  // 独立画布文档（真实 API 无文档=200 data:null；升级 PUT 由允许清单放行并回放回显）
  if (method === 'GET' && /^\/api\/creation-drafts\/[^/]+\/canvas$/.test(path)) {
    return null
  }
  if (method === 'GET' && path === '/api/video-production/capabilities') {
    return capabilities
  }
  if (method === 'GET' && path === '/api/video-production/tasks') {
    return []
  }
  // 其余 GET（最近项目、预算、组织等壳层接口）：空集合兜底，保证页面可渲染。
  if (method === 'GET') {
    return { items: [] }
  }
  throw new Error(`Unexpected non-GET business call: ${method} ${path}`)
}

/** 返回 violations 收集器；意外写操作记录后 500 回放（不炸进程，组合收尾判失败）。
 *  planMode='ready'：AI 计划首次提交回 504（错误态）、重交返回 ready 计划并放行 apply
 *  ——驱动助手面板完整状态机（UI 层；真实计划链在 CanvasWorkflowIntegrationIT）。 */
async function mockCanvasApis(context, { allowBind, violations, planMode }) {
  let planSubmissions = 0
  await context.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const method = request.method()
    if (method === 'POST' && url.pathname === '/api/me/active-identity') {
      // 壳层身份激活（登录后的缺省动作）：非画布业务写，放行
      await route.fulfill({ contentType: 'application/json',
        body: JSON.stringify({ success: true, data: { identityType: 'recommender' } }) })
      return
    }
    if (planMode === 'ready' && method === 'POST' && url.pathname === '/api/creation-assistant/canvas/plans') {
      planSubmissions += 1
      if (planSubmissions === 1) {
        await route.fulfill({ status: 504, contentType: 'application/json',
          body: JSON.stringify({ success: false, code: 'CANVAS_AGENT_TIMEOUT',
            error: '计划生成超时（验收桩）' }) })
        return
      }
      await route.fulfill({ contentType: 'application/json',
        body: JSON.stringify({ success: true, data: readyPlanFixture(shots[0].id) }) })
      return
    }
    if (planMode === 'ready' && method === 'POST'
        && url.pathname === `/api/creation-assistant/canvas/plans/${PLAN_ID}/apply`) {
      await route.fulfill({ contentType: 'application/json',
        body: JSON.stringify({ success: true, data: {
          planId: PLAN_ID, storyboardId: STORYBOARD_ID, draftId: DRAFT_ID,
          editVersion: 3, affectedShotIds: [shots[0].id], variant: null, preparedGeneration: null,
        } }) })
      return
    }
    if (method === 'POST' && /^\/api\/video-production\/storyboards\/[^/]+\/workspace$/.test(url.pathname)) {
      if (!allowBind) {
        violations.push(`unexpected bind write: ${method} ${url.pathname}`)
        await route.fulfill({ status: 500, contentType: 'application/json',
          body: JSON.stringify({ success: false, error: 'not allowed in this pass' }) })
        return
      }
      const isVariant = url.pathname.includes(VARIANT_STORYBOARD_ID)
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          data: {
            project: isVariant ? variantDraft : draft,
            storyboardId: isVariant ? VARIANT_STORYBOARD_ID : STORYBOARD_ID,
            productionTaskId: isVariant ? null : TASK_ID,
            editVersion: isVariant ? 1 : 2,
          },
        }),
      })
      return
    }
    if (method === 'PUT' && /^\/api\/creation-drafts\/[^/]+\/canvas$/.test(url.pathname)) {
      // 画布文档创建/CAS（升级与图编辑的合法写）：回放请求文档 + revision 1
      const body = route.request().postDataJSON()
      await route.fulfill({ contentType: 'application/json',
        body: JSON.stringify({ success: true, data: { revision: 1, document: body?.document ?? null } }) })
      return
    }
    if (method === 'PUT' && /^\/api\/creation-drafts\/[^/]+$/.test(url.pathname)) {
      // 草稿工作区保存（切换方案前 flush 布局的合法写）：成功即可
      await route.fulfill({ contentType: 'application/json',
        body: JSON.stringify({ success: true, data: { version: 4 } }) })
      return
    }
    if (method !== 'GET') {
      violations.push(`unexpected business write: ${method} ${url.pathname}`)
      await route.fulfill({ status: 500, contentType: 'application/json',
        body: JSON.stringify({ success: false, error: 'write not allowed in visual pass' }) })
      return
    }
    try {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({ success: true, data: apiFixture(method, url) }),
      })
    } catch (error) {
      violations.push(error.message)
      await route.fulfill({ status: 500, contentType: 'application/json',
        body: JSON.stringify({ success: false, error: error.message }) })
    }
  })
}

/** 键盘状态（TC-020）：Tab 可达画布节点且焦点样式可见。 */
async function assertKeyboardReachable(page, name) {
  await page.locator('[data-test="canvas-node-1"]').focus().catch(() => {})
  for (let step = 0; step < 40 && !(await page.evaluate(() =>
    document.activeElement?.closest('[data-test="canvas-node-1"], [data-test="canvas-runbar"]'))); step += 1) {
    await page.keyboard.press('Tab')
  }
  const focused = await page.evaluate(() => {
    const element = document.activeElement
    if (!element) return { ok: false }
    const style = getComputedStyle(element)
    return {
      ok: true,
      test: element.getAttribute('data-test') ?? element.closest('[data-test]')?.getAttribute('data-test') ?? '',
      outline: style.outlineStyle,
      outlineWidth: style.outlineWidth,
    }
  })
  assert.ok(focused.ok, `${name}: Tab 应聚焦到可操作元素`)
  assert.match(focused.test, /^canvas-/, `${name}: 焦点应落在画布操作面（实际 ${focused.test}）`)
  assert.notEqual(focused.outline, 'none', `${name}: 焦点样式应可见（outlineStyle=${focused.outline}）`)
}

/** 整页横向溢出检查（TC-020：无整页横溢出）。加载瞬态（字体/布局落位）下首测可能
 *  假阳性——间隔 300ms 复测一次，以稳定值为准（仍为真实检查，非跳过）。 */
async function assertNoHorizontalOverflow(page, name) {
  const measure = () => page.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth)
  let overflow = await measure()
  if (overflow > 1) {
    await page.waitForTimeout(300)
    overflow = await measure()
  }
  assert.ok(overflow <= 1, `${name}: 整页横向溢出 ${overflow}px`)
}

/** 可见性断言（带场景名标注，失败信息可定位到矩阵组合）。 */
async function expectVisible(page, selector, name) {
  await page.locator(selector).waitFor({ timeout: 20_000 })
  assert.ok(await page.locator(selector).isVisible(), `${name}: ${selector} 应可见`)
}

await mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true })
const viewports = [
  { name: 'desktop', width: 1440, height: 1000 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'mobile', width: 390, height: 844 },
]
const failures = []
const plan = []

try {
  for (const entry of [
    { name: 'grassland', base: baseUrl, path: `/video-canvas?storyboard=${STORYBOARD_ID}&draft=${DRAFT_ID}` },
    { name: 'ai-app', base: aiBaseUrl, path: `/video-canvas?storyboard=${STORYBOARD_ID}&draft=${DRAFT_ID}` },
  ]) {
    for (const viewport of viewports) {
      for (const theme of ['dark', 'light']) {
        const label = `${entry.name}-${viewport.name}-${theme}`
        const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height } })
        await context.addInitScript(mode => localStorage.setItem('theme-preference', mode), theme)
        const violations = []
        await mockCanvasApis(context, { allowBind: true, violations })
        const page = await context.newPage()
        const errors = []
        page.on('pageerror', error => errors.push(error.message))
        try {
          await page.goto(`${entry.base}${entry.path}`, { waitUntil: 'domcontentloaded' })
          await page.locator('[data-test="canvas-node-5"]').waitFor({ timeout: 20_000 })
          await page.locator('[data-test="canvas-delivery"]').waitFor({ timeout: 20_000 })
          await page.locator('[data-test="canvas-run-phase"]').waitFor({ timeout: 20_000 })
          await assertNoHorizontalOverflow(page, label)
          if (viewport.name === 'desktop') {
            await assertKeyboardReachable(page, label)
          }
          await page.screenshot({ path: resolve(output, `C100-08-${label}.png`), fullPage: true })
          plan.push(label)
        } catch (error) {
          failures.push(`${label}: ${error.message}${errors.length ? `；pageerror: ${errors.join(' | ')}` : ''}`)
        } finally {
          failures.push(...violations.map(violation => `${label}: ${violation}`))
          await context.close()
        }
      }
    }
  }

  // 共享快速模式（?storyboard= 恢复到分镜步，#69 卡C）：只读恢复，无业务写。
  for (const viewport of viewports) {
    for (const theme of ['dark', 'light']) {
      const label = `quick-mode-${viewport.name}-${theme}`
      const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height } })
      await context.addInitScript(mode => localStorage.setItem('theme-preference', mode), theme)
      const violations = []
      await mockCanvasApis(context, { allowBind: false, violations })
      const page = await context.newPage()
      const errors = []
      page.on('pageerror', error => errors.push(error.message))
      try {
        await page.goto(`${baseUrl}/video-production?storyboard=${STORYBOARD_ID}`, { waitUntil: 'domcontentloaded' })
        await page.getByText(/分镜/).first().waitFor({ timeout: 20_000 })
        await assertNoHorizontalOverflow(page, label)
        await page.screenshot({ path: resolve(output, `C100-08-${label}.png`), fullPage: true })
        plan.push(label)
      } catch (error) {
        failures.push(`${label}: ${error.message}${errors.length ? `；pageerror: ${errors.join(' | ')}` : ''}`)
      } finally {
        failures.push(...violations.map(violation => `${label}: ${violation}`))
        await context.close()
      }
    }
  }

  // ---- C100-19 交互矩阵（桌面双主题）：方案页签切换 + AI 助手状态机（路由桩层） ----
  for (const theme of ['dark', 'light']) {
    // 场景一：方案 A→B→A（切换 = 深链替换；B 无任务显示发起制作，节点数随方案变化）
    {
      const label = `variants-switch-desktop-${theme}`
      const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
      await context.addInitScript(mode => localStorage.setItem('theme-preference', mode), theme)
      const violations = []
      await mockCanvasApis(context, { allowBind: true, violations })
      const page = await context.newPage()
      const errors = []
      page.on('pageerror', error => errors.push(error.message))
      try {
        await page.goto(`${baseUrl}/video-canvas?storyboard=${STORYBOARD_ID}&draft=${DRAFT_ID}`, { waitUntil: 'domcontentloaded' })
        await page.locator('[data-test="canvas-node-5"]').waitFor({ timeout: 20_000 })
        await page.locator('[data-test="director-tab-variants"]').click()
        await expectVisible(page, `[data-test="canvas-variants-panel"]`, label)
        await page.locator(`[data-test="canvas-variant-switch-${VARIANT_STORYBOARD_ID}"]`).click()
        await page.waitForURL(new RegExp(`storyboard=${VARIANT_STORYBOARD_ID}`), { timeout: 20_000 })
        await page.locator('[data-test="canvas-node-3"]').waitFor({ timeout: 20_000 })
        await expectVisible(page, '[data-test="canvas-run-begin"]', `${label}-variant-no-task`)
        await page.locator('[data-test="director-tab-variants"]').click()
        await page.locator(`[data-test="canvas-variant-switch-${STORYBOARD_ID}"]`).click()
        await page.waitForURL(new RegExp(`storyboard=${STORYBOARD_ID}`), { timeout: 20_000 })
        await page.locator('[data-test="canvas-node-5"]').waitFor({ timeout: 20_000 })
        // 页签随整页态切换重置——重开方案页签再截「根方案=当前」的谱系面板
        await page.locator('[data-test="director-tab-variants"]').click()
        await expectVisible(page, `[data-test="canvas-variant-current-${STORYBOARD_ID}"]`, `${label}-root-current`)
        await assertNoHorizontalOverflow(page, label)
        await page.screenshot({ path: resolve(output, `C100-19-${label}.png`), fullPage: true })
        plan.push(`c100-19-${label}`)
      } catch (error) {
        failures.push(`${label}: ${error.message}${errors.length ? `；pageerror: ${errors.join(' | ')}` : ''}`)
      } finally {
        failures.push(...violations.map(violation => `${label}: ${violation}`))
        await context.close()
      }
    }

    // 场景二：AI 助手错误态（504 桩）→ 重交 → ready 预览 → 应用 → applied（UI 状态机）
    {
      const label = `assistant-states-desktop-${theme}`
      const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
      await context.addInitScript(mode => localStorage.setItem('theme-preference', mode), theme)
      const violations = []
      await mockCanvasApis(context, { allowBind: true, violations, planMode: 'ready' })
      const page = await context.newPage()
      const errors = []
      page.on('pageerror', error => errors.push(error.message))
      try {
        await page.goto(`${baseUrl}/video-canvas?storyboard=${STORYBOARD_ID}&draft=${DRAFT_ID}`, { waitUntil: 'domcontentloaded' })
        await page.locator('[data-test="canvas-node-5"]').waitFor({ timeout: 20_000 })
        await page.locator('[data-test="canvas-toggle-assistant"]').click()
        await page.locator('[data-test="canvas-node-1"]').click()
        await page.locator('[data-test="canvas-assistant-instruction"]').fill('把第一镜改得更抓人')
        await page.locator('[data-test="canvas-assistant-submit"]').click()
        await expectVisible(page, '[data-test="canvas-assistant-error"]', `${label}-timeout`)
        await page.screenshot({ path: resolve(output, `C100-19-${label}-error.png`), fullPage: true })
        await page.locator('[data-test="canvas-assistant-submit"]').click()
        await expectVisible(page, '[data-test="canvas-assistant-status-ready"]', `${label}-ready`)
        await expectVisible(page, '[data-test="canvas-plan-preview"]', `${label}-preview`)
        await page.locator('[data-test="canvas-assistant-apply"]').click()
        await expectVisible(page, '[data-test="canvas-assistant-status-applied"]', `${label}-applied`)
        await assertNoHorizontalOverflow(page, label)
        await page.screenshot({ path: resolve(output, `C100-19-${label}-applied.png`), fullPage: true })
        plan.push(`c100-19-${label}`)
      } catch (error) {
        failures.push(`${label}: ${error.message}${errors.length ? `；pageerror: ${errors.join(' | ')}` : ''}`)
      } finally {
        failures.push(...violations.map(violation => `${label}: ${violation}`))
        await context.close()
      }
    }
  }
} finally {
  await browser.close()
}

await writeFile(resolve(output, 'verify-report.json'), JSON.stringify({
  passed: plan, failures, baseUrl, aiBaseUrl, generatedAt: new Date().toISOString(),
}, null, 2))

if (failures.length) {
  console.error(`C100-08 视觉验收失败 ${failures.length} 组合：`)
  for (const failure of failures) console.error(`  - ${failure}`)
  process.exit(1)
}
console.log(`C100-08 视觉验收通过：${plan.length} 组合截图落 ${output}`)
