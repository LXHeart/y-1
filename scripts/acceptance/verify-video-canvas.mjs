import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium } from 'playwright'

/**
 * 任务书 #100 C100-08：画布双入口浏览器验收辅助（§8.4 / V-UI / TC-020 视觉与键盘面）。
 *
 * 两入口（草场 / AI 应用）专业模式 × 共享快速模式，3 视口 × 2 主题逐组合截图 +
 * 键盘可达性检查（Tab 到画布节点、focus-visible 样式存在）。浏览器本地 fixtures：
 * 全部 /api/** 由路由桩回放（与 qa-ops-layout.mjs 同款约定），业务写操作仅允许
 * 画布绑定 POST（真实链路的采用/合成由 e2e spec 与 VideoCanvasMilestoneIT 承担）。
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
  if (method === 'GET' && path === `/api/video-production/tasks/${TASK_ID}`) {
    return task
  }
  if (method === 'GET' && path === `/api/creation-drafts/${DRAFT_ID}`) {
    return draft
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

/** 返回 violations 收集器；意外写操作记录后 500 回放（不炸进程，组合收尾判失败）。 */
async function mockCanvasApis(context, { allowBind, violations }) {
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
    if (method === 'POST' && url.pathname === `/api/video-production/storyboards/${STORYBOARD_ID}/workspace`) {
      if (!allowBind) {
        violations.push(`unexpected bind write: ${method} ${url.pathname}`)
        await route.fulfill({ status: 500, contentType: 'application/json',
          body: JSON.stringify({ success: false, error: 'not allowed in this pass' }) })
        return
      }
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          data: {
            project: draft,
            storyboardId: STORYBOARD_ID,
            productionTaskId: TASK_ID,
            editVersion: 2,
          },
        }),
      })
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

/** 整页横向溢出检查（TC-020：无整页横溢出）。 */
async function assertNoHorizontalOverflow(page, name) {
  const overflow = await page.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth)
  assert.ok(overflow <= 1, `${name}: 整页横向溢出 ${overflow}px`)
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
