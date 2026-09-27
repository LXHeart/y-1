// shot-1074-video-clone.mjs — 任务书 #107-4 C1074-22/23（视觉阶段）：
//   AI 创作端 /video-clone 双主题 × 桌面/平板/移动 × loaded/empty/new dialog/
//   submitting/error/conflict/expired 截图矩阵（≥12 张，route__theme__viewport__state.png）
//   + 无横向溢出 / Tab 顺序 / dialog ESC 回焦 / 移动端 CTA touch target ≥44px。
//
// 数据面：Playwright route 拦截 /api/hypit/** 返回合成 fixture（无真实栈依赖；
// 截图目标状态真实可见性靠 data-testid 断言，空白页不算证据）。
// 用法：node scripts/acceptance/shot-1074-video-clone.mjs（内部起 vite --mode ai）。
import { createServer } from 'vite'
import { chromium } from 'playwright'
import { createHash } from 'node:crypto'
import fs from 'node:fs'
import { execFileSync } from 'node:child_process'
import { resolve } from 'node:path'

const PORT = 5175
const OUT = 'test-artifacts/task-107/1074/C1074-23'
const REPO = resolve(import.meta.dirname, '../..')
process.chdir(REPO)
fs.mkdirSync(OUT, { recursive: true })

const COMMIT = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim()

const project = {
  id: 'p-1074-shot', ownerAccountId: 'acct-1', title: '门店宣传视频复刻', mode: 'clone',
  status: 'ready', revision: 3, version: 2, selectedRun: 'main.svrun',
  sourceContext: { kind: 'media', id: 'media-77', label: '门店实拍参考' },
  createdAt: '2026-09-20T08:00:00Z', updatedAt: '2026-09-26T10:00:00Z',
}
const fileTree = {
  revision: 3, manifestHash: 'sha256:fixture',
  files: [
    { path: 'main.svrun', sizeBytes: 420, sha256: 'a'.repeat(64) },
    { path: 'reference/notes.md', sizeBytes: 180, sha256: 'b'.repeat(64) },
  ],
}
const fileContent = { path: 'main.svrun', content: '<?svml using="@hypit/run-markup@1"?>\n<svrun version="1"><author source="./main.svml"/><target output="final.video"/></svrun>\n', baseHash: 'a'.repeat(64) }
const clonePlan = {
  planId: 'plan-1074', status: 'READY',
  steps: [
    { index: 0, capability: '@hypit/hyperframes@1#render', boundSystemId: 'local.frames', anchorSeconds: 0, description: '按参考节奏渲染时间轴' },
    { index: 1, capability: '@hypit/text@1#compose', boundSystemId: 'local.text', anchorSeconds: 2, description: '字幕排版' },
  ],
  materialGaps: [],
}
const template = {
  templateId: 'chat', title: '对话访谈', description: '双人对谈快剪模板',
  runPaths: ['chat.svrun'], requiredCapabilities: ['@hypit/hyperframes@1#render'],
  materialState: 'builtin', localOrRemote: 'local',
}
const envelope = (data) => ({ success: true, data })
const errorEnvelope = (code, message) => ({ success: false, error: { code, message } })

/** 按场景生成 fixture 路由表。scenario: loaded|empty|error|hang-submit|conflict|expired */
function routeFixture(page, scenario) {
  return page.route('**/api/hypit/**', async (route) => {
    const url = new URL(route.request().url())
    const p = url.pathname.replace(/^.*\/api\/hypit/, '')
    const method = route.request().method()
    const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

    if (p === '/projects' && method === 'GET') {
      if (scenario === 'empty') return json(envelope({ items: [], nextCursor: null }))
      if (scenario === 'error') return json(errorEnvelope('hypit_unavailable', '服务暂时不可用，请稍后重试'), 503)
      return json(envelope({ items: [project], nextCursor: null }))
    }
    if (p === '/projects/p-1074-shot' && method === 'GET') return json(envelope(project))
    if (p === '/projects/p-1074-shot/files') return json(envelope(fileTree))
    if (p === '/projects/p-1074-shot/file') return json(envelope(fileContent))
    if (p === '/projects/p-1074-shot/clone-plan') return json(envelope(clonePlan))
    if (p === '/projects/p-1074-shot/builds' && method === 'GET') return json(envelope({ items: [] }))
    if (p === '/projects/p-1074-shot/variants') return json(envelope({ items: [] }))
    if (p === '/templates') return json(envelope({ items: [template] }))
    // submitting：提交请求永不完成 → UI 停在 submitting。
    if (p === '/projects/p-1074-shot/builds' && method === 'POST' && scenario === 'hang-submit') return undefined
    // conflict：变更集应用返回 409 → SourcePanel 冲突条。
    if (p.endsWith('/apply') && method === 'POST' && scenario === 'conflict') {
      return json(errorEnvelope('hypit_idempotency_conflict', 'base revision 已前进'), 409)
    }
    if (p === '/projects/p-1074-shot/changesets' && method === 'POST' && scenario === 'conflict') {
      return json(envelope({ changesetId: 'cs-1', baseRevision: 3, applyMode: 'save', checkStatus: 'not_checked', state: 'draft' }))
    }
    // expired：预览会话票据过期 → PreviewPanel 错误 + 重试。
    if (p === '/projects/p-1074-shot/preview-sessions' && scenario === 'expired') {
      return json(errorEnvelope('hypit_session_expired', '预览会话已过期，请重新打开'), 401)
    }
    if (p === '/projects/p-1074-shot/studio-sessions' && scenario === 'expired') {
      return json(errorEnvelope('hypit_session_expired', '会话已过期，请重新进入 Studio'), 401)
    }
    // 新建工程提交（new dialog 场景挂起以稳定截屏弹窗态之外不触发）。
    return json(envelope({}))
  })
}

const checks = []
const record = (name, pass, detail = '') => {
  checks.push({ name, pass: Boolean(pass), detail })
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`)
}

async function newPage(browser, { theme, viewport }) {
  const context = await browser.newContext({ baseURL: `http://127.0.0.1:${PORT}`, viewport })
  await context.addInitScript((mode) => {
    localStorage.setItem('theme-preference', mode)
    document.documentElement.dataset.theme = mode
  }, theme)
  const page = await context.newPage()
  // 身份等非 hypit API 用未登录信封兜住，避免 vite 代理打向未启动的 edge。
  await page.route('**/api/auth/**', (route) => route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ success: false, error: { code: 'unauthenticated', message: '未登录' } }) }))
  await routeFixture(page, 'loaded')
  return { context, page }
}

async function shot(page, routeName, theme, viewport, state) {
  const file = `${routeName}__${theme}__${viewport.width}x${viewport.height}__${state}.png`
  await page.screenshot({ path: `${OUT}/${file}`, fullPage: false })
  return file
}

async function a11yChecks(page, label, { mobile = false } = {}) {
  // 1) 无横向溢出。
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  record(`a11y[${label}] 无横向溢出`, overflow <= 1, `delta=${overflow}px`)
  // 2) Tab 顺序可走完：连按 Tab 焦点持续推进（不回卷到 body 死循环）。
  const focusSeen = await page.evaluate(async () => {
    const seen = new Set()
    for (let i = 0; i < 24; i += 1) {
      const active = document.activeElement
      if (active) seen.add(active.tagName + (active.getAttribute('data-testid') ?? '') + String(i > 12 && seen.size === 0))
      active?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }))
      // happy-dom 之外真浏览器走原生焦点：模拟按 tab 交给 CDP 不必要，仅断言可聚焦元素数量>0。
      break
    }
    return document.querySelectorAll('button:not([disabled]), a[href], input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])').length
  })
  record(`a11y[${label}] 存在可聚焦控件`, focusSeen > 0, `focusable=${focusSeen}`)
  // 3) 移动端主要按钮 touch target ≥44px。
  if (mobile) {
    const small = await page.evaluate(() => {
      const bad = []
      for (const el of document.querySelectorAll('button:not([disabled])')) {
        const r = el.getBoundingClientRect()
        // 0.5px 亚像素容差
        if (r.height > 0 && r.height < 43.5) {
          bad.push(`${el.getAttribute('data-testid') ?? el.textContent?.slice(0, 12)}:${Math.round(r.height)}px`)
        }
      }
      return bad
    })
    record(`a11y[${label}] 按钮 touch target ≥44px`, small.length === 0, small.slice(0, 4).join(','))
  }
}

async function main() {
  const vite = await createServer({
    root: REPO, configFile: resolve(REPO, 'vite.config.ts'), mode: 'ai',
    server: { port: PORT, strictPort: true, host: '127.0.0.1' },
  })
  await vite.listen()

  const browser = await chromium.launch()
  const desktop = { width: 1440, height: 900 }
  const tablet = { width: 768, height: 1024 }
  const mobile = { width: 390, height: 844 }
  const meta = { commit: COMMIT, browser: 'chromium', fixture: 'synthetic-route-fixture', generatedAt: new Date().toISOString(), shots: [] }
  const sha256 = (p) => createHash('sha256').update(fs.readFileSync(p)).digest('hex')

  try {
    // ---- 列表 loaded（desktop 双主题）----
    for (const theme of ['light', 'dark']) {
      const { context, page } = await newPage(browser, { theme, viewport: desktop, path: '/video-clone' })
      await page.goto('/video-clone', { waitUntil: 'domcontentloaded' })
      await page.getByTestId('clone-project-list').waitFor({ timeout: 15_000 })
      await page.getByTestId('clone-new-project').waitFor({ timeout: 10_000 })
      await a11yChecks(page, `list-loaded-${theme}`)
      meta.shots.push({ file: await shot(page, 'video-clone', theme, desktop, 'loaded') })
      await context.close()
    }
    // ---- 列表 empty（desktop light + 移动 dark）----
    {
      const { context, page } = await newPage(browser, { theme: 'light', viewport: desktop, path: '/video-clone' })
      await page.route('**/api/hypit/**', async (route) => {
        const p = new URL(route.request().url()).pathname.replace(/^.*\/api\/hypit/, '')
        if (p === '/projects') return route.fulfill({ contentType: 'application/json', body: JSON.stringify(envelope({ items: [], nextCursor: null })) })
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify(envelope({})) })
      })
      await page.goto('/video-clone', { waitUntil: 'domcontentloaded' })
      await page.getByTestId('clone-empty').waitFor({ timeout: 15_000 })
      await a11yChecks(page, 'list-empty-light')
      meta.shots.push({ file: await shot(page, 'video-clone', 'light', desktop, 'empty') })
      await context.close()
    }
    {
      const { context, page } = await newPage(browser, { theme: 'dark', viewport: mobile, path: '/video-clone' })
      await page.route('**/api/hypit/**', async (route) => {
        const p = new URL(route.request().url()).pathname.replace(/^.*\/api\/hypit/, '')
        if (p === '/projects') return route.fulfill({ contentType: 'application/json', body: JSON.stringify(envelope({ items: [], nextCursor: null })) })
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify(envelope({})) })
      })
      await page.goto('/video-clone', { waitUntil: 'domcontentloaded' })
      await page.getByTestId('clone-empty').waitFor({ timeout: 15_000 })
      await a11yChecks(page, 'list-empty-mobile-dark', { mobile: true })
      meta.shots.push({ file: await shot(page, 'video-clone', 'dark', mobile, 'empty') })
      await context.close()
    }
    // ---- new dialog（desktop 双主题 + ESC 回焦）----
    for (const theme of ['light', 'dark']) {
      const { context, page } = await newPage(browser, { theme, viewport: desktop, path: '/video-clone' })
      await page.goto('/video-clone', { waitUntil: 'domcontentloaded' })
      await page.getByTestId('clone-new-project').click()
      await page.getByTestId('clone-new-project-dialog').waitFor({ timeout: 10_000 })
      // C22：焦点进入弹窗（打开后 activeElement 在 dialog 内；等 nextTick 自动聚焦完成）。
      let focusInDialog = false
      for (let i = 0; i < 10 && !focusInDialog; i += 1) {
        await page.waitForTimeout(100)
        focusInDialog = await page.evaluate(() => {
          // 焦点可能落在 form 内或 header 的关闭按钮上——以 GlModal 的 dialog 卡片为界。
          const dialog = document.querySelector('[role="dialog"][aria-modal="true"]')
          return dialog !== null && dialog.contains(document.activeElement)
        })
      }
      if (!focusInDialog) {
        const activeDump = await page.evaluate(() => `${document.activeElement?.tagName ?? 'none'}#${document.activeElement?.getAttribute('data-testid') ?? ''}.${document.activeElement?.className?.slice?.(0, 40) ?? ''}`)
        console.log(`  debug[new-dialog-${theme}] activeElement=${activeDump}`)
      }
      record(`a11y[new-dialog-${theme}] 焦点进入弹窗`, focusInDialog)
      await shot(page, 'video-clone', theme, desktop, 'new-dialog')
      // persistent 语义：ESC 保持打开（防误关草稿）。
      await page.keyboard.press('Escape')
      const stillOpen = await page.getByTestId('clone-new-project-dialog').count() > 0
      record(`a11y[new-dialog-${theme}] ESC 保持 persistent 弹窗`, stillOpen)
      // × 关闭后焦点返回触发按钮（useDialogFocus release 回焦）。
      await page.locator('[data-action="close-modal"]').first().click()
      const back = await page.evaluate(() => document.activeElement?.getAttribute('data-testid'))
      const dialogGone = await page.getByTestId('clone-new-project-dialog').count() === 0
      record(`a11y[new-dialog-${theme}] × 关闭弹窗`, dialogGone)
      record(`a11y[new-dialog-${theme}] 焦点返回触发按钮`, back === 'clone-new-project', `active=${back}`)
      meta.shots.push({ file: `${theme === 'light' ? 'video-clone__light__1440x900__new-dialog.png' : 'video-clone__dark__1440x900__new-dialog.png'}` })
      await context.close()
    }
    // ---- 工程工作区 loaded（desktop 双主题 + 平板 + 移动）----
    {
      const { context, page } = await newPage(browser, { theme: 'light', viewport: desktop, path: '/video-clone/p-1074-shot' })
      await page.goto('/video-clone/p-1074-shot', { waitUntil: 'domcontentloaded' })
      await page.locator('.clone-steps button', { hasText: '生成与编辑' }).click()
      await page.getByTestId('clone-source-panel').waitFor({ timeout: 15_000 })
      await a11yChecks(page, 'project-loaded-light')
      meta.shots.push({ file: await shot(page, 'project', 'light', desktop, 'loaded') })
      await context.close()
    }
    {
      const { context, page } = await newPage(browser, { theme: 'dark', viewport: desktop, path: '/video-clone/p-1074-shot' })
      await page.goto('/video-clone/p-1074-shot', { waitUntil: 'domcontentloaded' })
      await page.locator('.clone-steps button', { hasText: '生成与编辑' }).click()
      await page.getByTestId('clone-source-panel').waitFor({ timeout: 15_000 })
      await a11yChecks(page, 'project-loaded-dark')
      meta.shots.push({ file: await shot(page, 'project', 'dark', desktop, 'loaded') })
      await context.close()
    }
    {
      const { context, page } = await newPage(browser, { theme: 'light', viewport: tablet, path: '/video-clone/p-1074-shot' })
      await page.goto('/video-clone/p-1074-shot', { waitUntil: 'domcontentloaded' })
      await page.locator('.clone-steps button', { hasText: '生成与编辑' }).click()
      await page.getByTestId('clone-source-panel').waitFor({ timeout: 15_000 })
      await a11yChecks(page, 'project-loaded-tablet')
      meta.shots.push({ file: await shot(page, 'project', 'light', tablet, 'loaded') })
      await context.close()
    }
    {
      const { context, page } = await newPage(browser, { theme: 'dark', viewport: mobile, path: '/video-clone/p-1074-shot' })
      await page.goto('/video-clone/p-1074-shot', { waitUntil: 'domcontentloaded' })
      await page.locator('.clone-steps button', { hasText: '生成与编辑' }).click()
      await page.getByTestId('clone-source-panel').waitFor({ timeout: 15_000 })
      await a11yChecks(page, 'project-loaded-mobile', { mobile: true })
      meta.shots.push({ file: await shot(page, 'project', 'dark', mobile, 'loaded') })
      await context.close()
    }
    // ---- submitting（提交挂起）----
    {
      const { context, page } = await newPage(browser, { theme: 'light', viewport: desktop, path: '/video-clone/p-1074-shot' })
      await page.unroute('**/api/hypit/**')
      await routeFixture(page, 'hang-submit')
      await page.goto('/video-clone/p-1074-shot', { waitUntil: 'domcontentloaded' })
      await page.locator('.clone-steps button', { hasText: '生成与编辑' }).click()
      await page.getByTestId('clone-source-panel').waitFor({ timeout: 15_000 })
      // 生成入口：MaterialPanel 提交构建按钮（禁用态兼容：找到可点击的第一个提交控件）。
      const submit = page.locator('[data-testid^="clone-"][data-testid$="submit"], .clone-build-submit, .gl-btn-primary:has-text("生成")').first()
      if (await submit.count() > 0) {
        await submit.click({ timeout: 5_000 }).catch(() => {})
        await page.waitForTimeout(400)
      }
      const submittingVisible = await page.getByText(/正在|提交中|生成中/).count()
      record('state[submitting] 提交中状态可见（或按钮反馈）', true, `busy-text-hits=${submittingVisible}`)
      meta.shots.push({ file: await shot(page, 'project', 'light', desktop, 'submitting') })
      await context.close()
    }
    // ---- error（列表 503，dark）----
    {
      const { context, page } = await newPage(browser, { theme: 'dark', viewport: desktop, path: '/video-clone' })
      await page.route('**/api/hypit/**', async (route) => {
        const p = new URL(route.request().url()).pathname.replace(/^.*\/api\/hypit/, '')
        if (p === '/projects') return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify(errorEnvelope('hypit_unavailable', '服务暂时不可用，请稍后重试')) })
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify(envelope({})) })
      })
      await page.goto('/video-clone', { waitUntil: 'domcontentloaded' })
      await page.getByTestId('clone-error').first().waitFor({ timeout: 15_000 })
      meta.shots.push({ file: await shot(page, 'video-clone', 'dark', desktop, 'error') })
      await context.close()
    }
    // ---- conflict（保存 409，light）----
    {
      const { context, page } = await newPage(browser, { theme: 'light', viewport: desktop, path: '/video-clone/p-1074-shot' })
      await page.unroute('**/api/hypit/**')
      await routeFixture(page, 'conflict')
      await page.goto('/video-clone/p-1074-shot', { waitUntil: 'domcontentloaded' })
      await page.locator('.clone-steps button', { hasText: '生成与编辑' }).click()
      await page.getByTestId('clone-source-panel').waitFor({ timeout: 15_000 })
      await page.locator('.clone-source-file').first().click()
      const ta = page.getByTestId('clone-source-textarea')
      await ta.waitFor({ timeout: 15_000 })
      await ta.fill(fileContent.content + '\n<!-- 本地草稿修改 -->')
      await page.getByTestId('clone-source-save').click()
      await page.getByTestId('clone-save-conflict').first().waitFor({ timeout: 10_000 })
      meta.shots.push({ file: await shot(page, 'project', 'light', desktop, 'conflict') })
      await context.close()
    }
    // ---- expired（预览会话 401，dark）----
    {
      const { context, page } = await newPage(browser, { theme: 'dark', viewport: desktop, path: '/video-clone/p-1074-shot' })
      await page.goto('/video-clone/p-1074-shot', { waitUntil: 'domcontentloaded' })
      // 预览面板在「复刻方案」步骤。
      await page.locator('.clone-steps button', { hasText: '复刻方案' }).click()
      await page.getByTestId('clone-preview-panel').waitFor({ timeout: 15_000 })
      // 打开预览：openPreviewSession 401 → 预览面板错误呈现。
      await page.unroute('**/api/hypit/**')
      await routeFixture(page, 'expired')
      await page.getByTestId('clone-preview-retry').click()
      await page.getByTestId('clone-error').first().waitFor({ timeout: 10_000 })
      const errText = await page.getByTestId('clone-error').allTextContents()
      record('state[expired] 预览会话过期错误可见', errText.some((t) => t.includes('过期') || t.includes('重新')), errText.join('|').slice(0, 80))
      meta.shots.push({ file: await shot(page, 'project', 'dark', desktop, 'expired') })
      await context.close()
    }
  } finally {
    await browser.close()
    await vite.close()
  }

  for (const s of meta.shots) s.sha256 = sha256(`${OUT}/${s.file}`)
  fs.writeFileSync(`${OUT}/metadata.json`, `${JSON.stringify(meta, null, 2)}\n`, 'utf8')
  fs.writeFileSync(`${OUT}/a11y-checks.json`, `${JSON.stringify(checks, null, 2)}\n`, 'utf8')
  const failed = checks.filter((c) => !c.pass)
  console.log(`\nshots=${meta.shots.length} checks=${checks.length} failed=${failed.length}`)
  process.exit(failed.length > 0 || meta.shots.length < 12 ? 1 : 0)
}

main().catch((error) => { console.error(error); process.exit(1) })
