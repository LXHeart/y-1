// hypit-fix3-reference.spec.ts — 107-fix-3 参考链 E2E（W39）：
// 真实浏览器驱动真实栈（Nginx/Edge/Java/broker/runner/PG 全真，只有外部商业
// 模型替换为受控文本 fixture——治理台正式凭据/路由/计费不变）。
//
// 本文件按责任卡分期落卡：
//   C107F3-10（TC-F3-10-04 页面按钮链）：上传→分析在途时再生成 409 前置文案
//     （UI-03「请先完成参考视频分析」）；分析 SUCCEEDED 后重新生成连点只发一个
//     author agent-jobs（请求体带 API-002 新标志 + baseRevision）；202 受理仅显示
//     「重新生成中…」（202 不展示成功）；服务端终态后才出现「方案已更新」且方案
//     可读（终态重读，非受理后马上 refresh）；按钮键盘可达（focus + Enter）。
//   C107F3-11（TC-F3-11-03 视觉/移动/键盘/会话失效 + Edge 变体 retry/cancel
//     穿透与受控输入差异）：在同一文件追加，不改 C10 既有用例。
//
// 运行前置（V-15 分层验收编排负责）：隔离栈 + 合成账号 seed + fixture 文本模型
// （seedFix2TextModel）+ 本 spec 直接可跑的 Playwright worker；本卡仅落代码，
// 实际执行在 V-15（未执行时如实记 NOT_RUN）。
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test, type Page } from '@playwright/test'

import {
  AI_BASE, JOURNEY_TIMEOUT_MS, OWNER_A, OWNER_B,
  createProject, login, makeReferenceMp4, openWorkspace, seedFix2TextModel,
} from './fixtures/hypit-fix2'

// 证据根可覆盖（分层验收编排注入 HYPIT_FIX3_EVIDENCE_ROOT 指向当前 run stage 目录）；
// 未注入时保持独立缺省路径，不写进 fix2 历史目录。
const EVIDENCE = process.env.HYPIT_FIX3_EVIDENCE_ROOT
  ? resolve(process.env.HYPIT_FIX3_EVIDENCE_ROOT, 'reference')
  : resolve('test-artifacts/task-107/fix3/reference')

interface AgentJobPost { url: string; requestId: string; body: Record<string, unknown> }

/** 收集页面发出的 POST /agent-jobs（ postData 为空时跳过——SSE/GET 不在此列）。 */
function collectAgentJobPosts(page: Page): AgentJobPost[] {
  const posts: AgentJobPost[] = []
  page.on('request', (request) => {
    if (request.method() !== 'POST' || !/\/api\/hypit\/projects\/[^/]+\/agent-jobs$/.test(request.url())) return
    const raw = request.postData()
    if (raw === null) return
    try {
      const body = JSON.parse(raw) as Record<string, unknown>
      posts.push({ url: request.url(), requestId: String(body.requestId ?? ''), body })
    } catch { /* 非 JSON 体不属本断言面 */ }
  })
  return posts
}

// ── C107F3-11（W39）公共夹具 ────────────────────────────────────────────────

function collectConsoleErrors(page: Page): string[] {
  const errors: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text())
  })
  page.on('pageerror', (error) => errors.push(String(error)))
  return errors
}

/** 已知合法 4xx 观察面（空态轮询/前置拒绝/登出过渡）——按 URL 精确登记，
 * console 侧与 journey consoleLeaks 同机制按状态码逐条配对豁免（console error
 * 行不携带 URL，只能按状态配对；每条豁免记录只豁免一行，多出的照样算泄漏）。 */
function collectBenignResponses(page: Page): { status: number }[] {
  const benign: { status: number }[] = []
  page.on('response', (res) => {
    const url = res.url()
    const status = res.status()
    if (status === 404 && /\/api\/hypit\/projects\/[0-9a-f-]{36}/.test(url)) benign.push({ status })
    else if (status === 409 && /\/agent-jobs$/.test(url)) benign.push({ status })
    else if (status === 409 && /\/changesets\/[^/]+\/apply$/.test(url)) benign.push({ status })
    else if (status === 401 && /\/api\//.test(url)) benign.push({ status })
  })
  return benign
}

/** 与 journey 同款：每条豁免记录配对一行携带同状态码的 Failed to load resource。 */

/** C107F3-11（W39）：Firefox 会把 nginx 部署面观察性 CSP **Report-Only** 策略
 * （nginx.conf $csp_policy_report_only——CSP_MODE 缺省姿态，Studio srcdoc 内联
 * 脚本被上报而不阻断）逐条打成 console error，文案为「Content-Security-Policy:
 * (Report-Only policy) …」（smoke40 firefox 实证）；WebKit 同面文案为
 * 「[Report Only] Refused to execute …」（smoke43 webkit 实证：三 Studio 用例
 * 泄漏行 44 条全部是该面，零真泄漏）；chromium 不上报。只豁免带 Report-Only
 * 标记的行——enforced 策略的阻断告警（无该标记、WebKit 无前缀）仍按泄漏失败；
 * 私有内容扫描同样排除该面（Firefox 建议文本「… or a nonce」含单词 nonce、
 * WebKit 文案含 hash/nonce 字样，会误中 ticket=/nonce/assertion 模式）。 */
const CSP_REPORT_ONLY_NOISE = /(Content-Security-Policy: \(Report-Only policy\))|(^\[Report Only\])/i

function consoleLeaks(consoleErrors: string[], benign: { status: number }[]): string[] {
  // favicon/DevTools：浏览器自身噪声；CSP Report-Only 面见上方常量注释。
  let rest = consoleErrors.filter((line) => !/favicon|DevTools/i.test(line) && !CSP_REPORT_ONLY_NOISE.test(line))
  for (const { status } of benign) {
    const marker = `status of ${status}`
    const hit = rest.find((line) => line.includes('Failed to load resource') && line.includes(marker))
    if (hit) rest = rest.filter((line) => line !== hit)
  }
  return rest
}

/**
 * 受控输入差异的参考素材：12s 纯色无声 MP4（lavfi color，320x240@10fps）。
 * 严格 fixture（C107F3-08）按真实解码帧的主色切段——纯红/纯蓝输入产生可区分的
 * 分段文案（主色 red/blue），宿主产物不 mock（ffmpeg 生成，libx264 缺失退 mpeg4）。
 */
function makeSolidColorMp4(color: string, name: string): string {
  const path = resolve(EVIDENCE, name)
  const args = (codec: string) => ['-y', '-f', 'lavfi', '-i', `color=c=${color}:duration=12:size=320x240:rate=10`,
    '-pix_fmt', 'yuv420p', '-vcodec', codec, path]
  try {
    execFileSync('ffmpeg', args('libx264'), { stdio: 'pipe' })
  } catch {
    execFileSync('ffmpeg', args('mpeg4'), { stdio: 'pipe' })
  }
  return path
}

/** 12s 红(6s)+蓝(6s)拼接参考：严格 fixture 六中点取样（1/3/5 红，7/9/11 蓝）按解码
 * 主色游程切段 → 2 段，与纯红 1 段构成内容驱动的分段结构差异。 */
function makeSplitColorMp4(name: string): string {
  const path = resolve(EVIDENCE, name)
  const args = (codec: string) => ['-y',
    '-f', 'lavfi', '-i', 'color=c=red:duration=6:size=320x240:rate=10',
    '-f', 'lavfi', '-i', 'color=c=blue:duration=6:size=320x240:rate=10',
    '-filter_complex', '[0:v][1:v]concat=n=2:v=1:a=0[v]',
    '-map', '[v]', '-pix_fmt', 'yuv420p', '-vcodec', codec, path]
  try {
    execFileSync('ffmpeg', args('libx264'), { stdio: 'pipe' })
  } catch {
    execFileSync('ffmpeg', args('mpeg4'), { stdio: 'pipe' })
  }
  return path
}

/** 真实主题切换（header 的 theme-toggle 按钮循环 light→dark→system；html[data-theme]
 * 恒为解析后的 light/dark）。切到目标主题为止，最多 4 次点击。 */
async function setTheme(page: Page, mode: 'dark' | 'light'): Promise<void> {
  for (let i = 0; i < 4; i += 1) {
    const current = await page.locator('html').evaluate((el) => el.dataset.theme ?? 'dark')
    if (current === mode) return
    await page.locator('button.theme-toggle').click()
    await page.waitForTimeout(120)
  }
  throw new Error(`真实点击未能切到 ${mode} 主题`)
}

interface VisionShot {
  readonly scenario: string
  readonly theme: 'dark' | 'light'
  readonly viewport: 'desktop' | 'mobile'
  readonly file: string
  readonly overflowX: number
}

/**
 * §8.8 截图与实际查看：真实设定主题+视口后截图，并逐项检查——主题属性确已应用、
 * 视口尺寸确已生效、移动视口无横向溢出。检查不通过直接失败（不以截图文件存在
 * 作通过）；返回记录进证据清单（视口/场景/溢出实数）。
 */
async function visionShot(page: Page, scenario: string, theme: 'dark' | 'light',
  viewport: 'desktop' | 'mobile'): Promise<VisionShot> {
  await setTheme(page, theme)
  await page.setViewportSize(viewport === 'desktop' ? { width: 1440, height: 900 } : { width: 390, height: 844 })
  await page.waitForTimeout(180)
  const applied = await page.locator('html').evaluate((el) => el.dataset.theme ?? '')
  expect(applied, `${scenario} 截图前 html[data-theme] 必须是 ${theme}（实际 ${applied}）`).toBe(theme)
  const { width, height, overflowX } = await page.evaluate(() => ({
    width: window.innerWidth,
    height: window.innerHeight,
    overflowX: document.documentElement.scrollWidth - document.documentElement.clientWidth,
  }))
  expect(width, `${scenario} 视口宽`).toBe(viewport === 'desktop' ? 1440 : 390)
  expect(height, `${scenario} 视口高`).toBe(viewport === 'desktop' ? 900 : 844)
  expect(overflowX, `${scenario}（${theme}/${viewport}）横向溢出 ${overflowX}px`).toBeLessThanOrEqual(1)
  const file = resolve(EVIDENCE, `TC-F3-11-03-${scenario}-${theme}-${viewport}.png`)
  await page.screenshot({ path: file })
  return { scenario, theme, viewport, file, overflowX }
}

/**
 * 观察面：GET /assets 按本地文件 sha 精确匹配素材，轮询到 ready（上传→probe
 * 收敛需要数秒；ready 前「全片分析」按钮不存在）。返回内容 sha 供分析回读键。
 */
async function assetSha(page: Page, projectId: string, localFile: string): Promise<string> {
  const expected = createHash('sha256').update(readFileSync(localFile)).digest('hex')
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const response = await page.request.get(`${AI_BASE}/api/hypit/projects/${projectId}/assets`)
    if (response.status() === 200) {
      const payload = await response.json() as { data?: { items?: Array<{ sha256?: string; status?: string }> } }
      const mine = (payload.data?.items ?? []).find((item) => item.sha256 === expected)
      if (mine?.status === 'ready') return expected
    }
    await page.waitForTimeout(3_000)
  }
  throw new Error(`上传素材未在观察面收敛为 ready（sha ${expected}）`)
}

test.describe('C107F3-10 可信再生成（页面按钮链）', () => {
  test('TC-F3-10-04 前置失败文案、新标志透传、连点1请求、202处理中、终态重读与键盘可达', async ({ page, request }) => {
    test.setTimeout(JOURNEY_TIMEOUT_MS + 180_000)
    await seedFix2TextModel(request)
    mkdirSync(EVIDENCE, { recursive: true })

    await login(page, OWNER_A)
    await openWorkspace(page)
    await createProject(page, 'fix3-c10-可信再生成工程')

    // ① 上传 12s 参考素材（真实 XHR 上传 → 素材行可分析）。
    const reference = makeReferenceMp4()
    await page.setInputFiles('[data-testid="clone-upload-input"]', {
      name: 'reference-12s.mp4', mimeType: 'video/mp4', buffer: readFileSync(reference),
    })
    await page.getByTestId('clone-upload-submit').click()
    await page.getByTestId('clone-analyze').first().waitFor({ timeout: 180_000 })

    // ② 全片分析（真实 probe/frames + 受控综合模型）。分析在途时回方案页点
    // 重新生成：服务端 409（无 SUCCEEDED 可信分析）→ UI-03 第一句可行动文案。
    // 受理切页签（C107F2-37 缺陷 S：analyze 受理自动切「生成与编辑」看进度）是
    // 已验收动线——切回方案页必须等受理回调落定：先点分析再立刻点「复刻方案」
    // 会与迟到的 onJobAccepted 竞态，页面被再切走、regenerate 按钮 detach（W79
    // 放行后按钮可点，click 落定前被卸载，10s 重试窗口内永不回 plan 页）。以
    // generate body 的特征 heading「生成任务」出现为受理落定判据再回方案页；
    // 分析满栈实录 60s+，此刻仍在途，409 前置拒绝面语义不变。
    const posts = collectAgentJobPosts(page)
    await page.getByTestId('clone-analyze').first().click()
    await page.getByRole('heading', { name: '生成任务' }).waitFor({ timeout: 60_000 })
    await page.getByRole('button', { name: '复刻方案' }).click()
    const regenerate = page.getByTestId('clone-plan-regenerate')
    await expect(regenerate).toBeEnabled({ timeout: 60_000 })
    await regenerate.click()
    // 前置失败：保留空态与可行动原因（UI-03「无完整分析」→ 请先完成参考视频分析）。
    // 分析在本机满栈实录 60s+，409 到达窗口充足；若分析已先收敛（快机），该前置
    // 断言跳过由下方终态链兜底——不以等待时长代替判据。
    const preconditionError = page.getByTestId('clone-plan-regenerate-error')
    const conflictShown = await preconditionError.waitFor({ timeout: 30_000 }).then(() => true, () => false)
    if (conflictShown) {
      await expect(preconditionError).toHaveText('请先完成参考视频分析')
      await expect(page.getByTestId('clone-plan-empty')).toBeVisible() // 失败保留：不伪造成功
      await page.screenshot({ path: resolve(EVIDENCE, 'TC-F3-10-04-precondition-conflict.png'), fullPage: true })
    }

    // ③ 等分析收敛后的正式链：连点两击（第二击用原生事件直达，模拟双击的第二个
    // 事件到达已被禁用的按钮——W25 在途合并必须只发一个）。
    // 收敛判据=再生成 POST 的**结果落定**，不是 toBeEnabled 也不是点击瞬间的
    // disabled——status=running 在 POST 发出前同步置位，任何点击后头 ~50ms 按钮
    // 都是禁用的，409 拒绝要等回包（~40ms）才翻回 enabled（smoke37 实证：isDisabled
    // 首 poll 14ms 即见 disabled 误判受理，④ 10s 超时）；而 W79 门在分析期本就放行
    // 再生成（②的 409 前置面依赖可点），toBeEnabled 秒过即点击必被拒。故每轮：
    // 点击+第二击立即落在 POST 在途窗口（合并面），2s 后看结果——仍在 running
    // （禁用）＝202 受理；翻回 enabled+错误提示＝409 拒绝，退避重试（与 37-01
    // journey 收敛循环同构）。author 任务本栈实录 ~20s，2s 判定窗不影响 ④ 观测。
    let accepted = false
    for (let attempt = 0; attempt < 40 && !accepted; attempt += 1) {
      posts.length = 0
      await regenerate.click()
      await regenerate.dispatchEvent('click')
      await page.waitForTimeout(2_000)
      const stillRunning = await regenerate.isDisabled().catch(() => false)
      const rejected = await preconditionError.isVisible().catch(() => false)
      if (stillRunning && !rejected) {
        accepted = true
        break
      }
      await page.waitForTimeout(8_000) // 分析仍在途（UI-03 预期拒绝面）：退避等收敛
    }
    expect(accepted, '分析收敛后再生成必须被受理（202→running 维持）').toBe(true)

    const authorPosts = posts.filter((post) => post.body.intent === 'author')
    expect(authorPosts, '连点只发一个 author 请求').toHaveLength(1)
    // API-002 新模式完整请求形态：新标志 + 空 assetIds + baseRevision 数字（=head）。
    expect(authorPosts[0].body.regenerateFromLatestAnalysis).toBe(true)
    expect(authorPosts[0].body.assetIds).toEqual([])
    expect(typeof authorPosts[0].body.baseRevision).toBe('number')

    // ④ 202 受理只显示处理中（UI-01：不展示成功），按钮禁用 + 文案。
    await expect(regenerate).toBeDisabled()
    await expect(regenerate).toHaveText('重新生成中…')
    await expect(page.getByTestId('clone-plan-updated')).toBeHidden()
    await page.screenshot({ path: resolve(EVIDENCE, 'TC-F3-10-04-running.png'), fullPage: true })

    // ⑤ 服务端终态后才重读（UI-02：「方案已更新」出现，方案可继续生成）。
    await expect(page.getByTestId('clone-plan-updated')).toBeVisible({ timeout: JOURNEY_TIMEOUT_MS })
    await expect(page.getByTestId('clone-generate')).toBeEnabled({ timeout: 60_000 })
    await page.screenshot({ path: resolve(EVIDENCE, 'TC-F3-10-04-updated.png'), fullPage: true })

    // ⑥ 键盘可达（§8）：Tab 聚焦 + Enter 触发下一轮再生成（新 requestId）。
    await regenerate.focus()
    const idsBefore = new Set(posts.map((post) => post.requestId))
    await regenerate.press('Enter')
    await expect(regenerate).toBeDisabled()
    await page.waitForTimeout(2_000)
    const newPosts = posts.filter((post) => !idsBefore.has(post.requestId))
    expect(newPosts.filter((post) => post.body.intent === 'author')).toHaveLength(1)
  })
})


test.describe('C107F3-11 视觉、移动、键盘与会话失效（TC-F3-11-03）', () => {
  test('TC-F3-11-03 双主题×桌面/移动方案与Studio入口视觉、键盘Enter、Studio关闭后旧会话拒绝、生成中换号隔离', async ({ page, request }) => {
    test.setTimeout(JOURNEY_TIMEOUT_MS + 420_000)
    await seedFix2TextModel(request)
    mkdirSync(EVIDENCE, { recursive: true })
    await login(page, OWNER_A)
    const consoleErrors = collectConsoleErrors(page)
    const benign = collectBenignResponses(page)
    await openWorkspace(page)
    const projectId = await createProject(page, 'fix3-c11-视觉会话链工程')
    const shots: VisionShot[] = []

    // ① 方案面板空态（新工程无方案）：§8.8 亮/暗 × 桌面1440×900/移动390×844。
    await page.getByRole('button', { name: '复刻方案' }).click()
    await expect(page.getByTestId('clone-plan-empty')).toBeVisible()
    for (const theme of ['dark', 'light'] as const) {
      for (const viewport of ['desktop', 'mobile'] as const) {
        shots.push(await visionShot(page, 'plan-empty', theme, viewport))
      }
    }
    await expect(page.getByTestId('clone-plan-empty')).toBeVisible()

    // ② 键盘可达（§8 Tab/Enter）：focus + Enter 触发再生成 → 新工程无可信分析 →
    // 服务端 409 → UI-03 第一句可行动文案（错误态截图 ×4）。
    const regenerate = page.getByTestId('clone-plan-regenerate')
    await regenerate.focus()
    expect(await page.evaluate(() => document.activeElement?.getAttribute('data-testid')), 'Enter 前焦点在再生成按钮')
      .toBe('clone-plan-regenerate')
    await regenerate.press('Enter')
    await expect(page.getByTestId('clone-plan-regenerate-error')).toHaveText('请先完成参考视频分析', { timeout: 30_000 })
    // 焦点可见性说明：全局焦点环是 :focus-visible 语义（src/style.css:502-504），
    // 脚本 focus() 不匹配该伪类（浏览器正确行为，非产品缺陷），故不断言 computed
    // outline；键盘可达性以「可聚焦（activeElement 断言）+ Enter 激活动作（上面
    // 409 文案）」为完整判据。
    for (const theme of ['dark', 'light'] as const) {
      for (const viewport of ['desktop', 'mobile'] as const) {
        shots.push(await visionShot(page, 'plan-error-precondition', theme, viewport))
      }
    }

    // ③ 受控红参考：上传→真实「全片分析」→ 观察面轮询分析终态 → 再生成受理
    // （提交中态截图）→ 生成中退出登录换 B（UI-04：换号隔离）→ B 打不开 A 工程 →
    // 回 A 终态重读（成功态 ×4）。
    const redMp4 = makeSolidColorMp4('red', 'TC-F3-11-03-reference-red.mp4')
    await page.getByRole('button', { name: '参考素材' }).click()
    await page.setInputFiles('[data-testid="clone-upload-input"]', {
      name: 'reference-red.mp4', mimeType: 'video/mp4', buffer: readFileSync(redMp4),
    })
    await page.getByTestId('clone-upload-submit').click()
    const redSha = await assetSha(page, projectId, redMp4)
    // 拦截分析 POST 回执（仅观测拿 jobId，被测动作是下一行的真实按钮点击）。
    const redReceipt = page.waitForResponse((res) =>
      res.request().method() === 'POST' && /\/api\/hypit\/projects\/[^/]+\/agent-jobs$/.test(res.url()))
    await page.getByTestId('clone-analyze').first().click()
    const redJobId = (await (await redReceipt).json() as { data?: { jobId?: string } }).data?.jobId ?? ''
    // 分析收敛（观察面：analyze agent-job 到 succeeded——worker 在终态前持久分析并
    // 派生 clone.plan；观察动作已由真实按钮触发，这里只等终态，不用等待时长代替
    // 判据）。注：GET /reference-analysis 是 intelligence 内部观测端点（hypit-api
    // 契约与 edge 路由表均未登记），经 edge 恒 404，故 job 终态即持久化判据。
    await expect.poll(async () => {
      if (!redJobId) return 'no-receipt'
      const detail = await page.request.get(`${AI_BASE}/api/hypit/projects/${projectId}/jobs/${redJobId}`)
      if (detail.status() !== 200) return `http-${detail.status()}`
      return String((await detail.json() as { data?: { state?: string } }).data?.state ?? 'unknown')
    }, { timeout: 300_000, message: '红参考分析必须收敛出持久结果' }).toBe('succeeded')
    await page.getByRole('button', { name: '复刻方案' }).click()
    await expect(regenerate).toBeEnabled({ timeout: 60_000 })
    await regenerate.click()
    await expect(regenerate).toBeDisabled({ timeout: 30_000 })
    await expect(regenerate).toHaveText('重新生成中…')
    shots.push(await visionShot(page, 'plan-regenerating', 'dark', 'desktop'))
    shots.push(await visionShot(page, 'plan-regenerating', 'dark', 'mobile'))

    // 生成中（author 任务运行中）退出登录 → 换 B：B 无权打开 A 工程（服务端闸，
    // 不区分不存在/无权）；A 回来终态重读（旧回调不污染新页面）。
    await page.getByRole('button', { name: '退出登录' }).click()
    await page.getByRole('button', { name: '登录 / 注册' }).waitFor({ timeout: 15_000 })
    await login(page, OWNER_B)
    await page.goto(`${AI_BASE}/video-clone/${projectId}`)
    await expect(page.getByTestId('clone-project-notfound'), 'B 打开 A 工程必须被拒（换号隔离）')
      .toBeVisible({ timeout: 60_000 })
    shots.push(await visionShot(page, 'switched-account-refused', 'dark', 'desktop'))
    await page.getByRole('button', { name: '退出登录' }).click()
    await page.getByRole('button', { name: '登录 / 注册' }).waitFor({ timeout: 15_000 })
    await login(page, OWNER_A)
    await page.goto(`${AI_BASE}/video-clone/${projectId}`)
    await page.getByRole('button', { name: '复刻方案' }).click()
    // 终态重读（UI-02/UI-04）：换号往返后按服务端真相收敛——方案可读、生成可用，
    // 按钮回到空闲态（无跨账号残留「重新生成中…」）。
    await expect(page.getByTestId('clone-generate')).toBeEnabled({ timeout: JOURNEY_TIMEOUT_MS })
    await expect(regenerate).toHaveText('重新生成方案')
    await expect(page.locator('ol.clone-plan-steps li').first()).toBeVisible()
    for (const theme of ['dark', 'light'] as const) {
      for (const viewport of ['desktop', 'mobile'] as const) {
        shots.push(await visionShot(page, 'plan-success', theme, viewport))
      }
    }

    // ④ Studio 入口（真实 iframe 渲染，补丁 0005 部署态可用）：入口截图 ×4 →
    // 关闭（会话失效 UI 态）→ 旧会话面再调用必须被拒（非 2xx，绝无 studio 内容）。
    await page.getByRole('button', { name: '生成与编辑' }).click()
    await page.getByTestId('clone-open-studio').click()
    const frame = page.frameLocator('iframe.clone-studio-frame')
    // 真渲染证据（不把 body 存在当成功）：源码侧栏 main SVML 文件项可见。
    await frame.locator('button.sidebar-panel-item[aria-label="main SVML"]').waitFor({ timeout: 180_000 })
    const iframeSrc = await page.locator('iframe.clone-studio-frame').getAttribute('src')
    expect(iframeSrc, 'iframe 必须挂载会话票据 URL').toMatch(/\/studio\/[A-Za-z0-9-]+\//)
    const sid = /\/studio\/([A-Za-z0-9-]+)\//.exec(iframeSrc ?? '')?.[1] ?? ''
    for (const theme of ['dark', 'light'] as const) {
      for (const viewport of ['desktop', 'mobile'] as const) {
        shots.push(await visionShot(page, 'studio-entry', theme, viewport))
      }
    }
    await page.getByRole('button', { name: '关闭编辑器' }).click()
    await expect(page.locator('iframe.clone-studio-frame')).toBeHidden({ timeout: 60_000 })
    // 关闭后的产品契约：studio 会话清空 → 面板整体卸载（VideoCloneWorkbench.vue
    // 的 StudioPanel v-if 条件 session/loading/error 全空）——不渲染空态占位。
    await expect(page.getByTestId('clone-studio-panel')).toBeHidden({ timeout: 10_000 })
    shots.push(await visionShot(page, 'studio-closed-empty', 'dark', 'desktop'))
    // 旧会话面再调用：会话已 closed → Java access 拒绝（403/404/409 家族，经 nginx
    // auth_request 映射为 401/403/404/500 之一）——断言非 2xx 且响应不含 studio
    // 会话内容（旧会话绝不复活）。
    const staleCall = await page.request.get(`${AI_BASE}/studio/${sid}/__studio/session`)
    expect(staleCall.ok(), `关闭后的旧会话调用必须被拒（实际 ${staleCall.status()}）`).toBe(false)
    const staleBody = await staleCall.text().catch(() => '')
    expect(staleBody, '拒绝体不得携带 studio 会话快照').not.toContain('"revision"')

    // ⑤ 控制台卫生（§12.2：控制台无私有内容）：票据/nonce/断言绝不进 console；
    // 错误行按登记面（空态/分析轮询 404、agent-jobs 409 前置、登出过渡 401、
    // 换号探测 404）逐条配对豁免后无真泄漏；UI-03 前置 409 的应用错误行按
    // 精确文案豁免（该文案本身即本用例的断言对象）。
    const privateLeaks = consoleErrors
      .filter((line) => !CSP_REPORT_ONLY_NOISE.test(line))
      .filter((line) => /ticket=|nonce|assertion/i.test(line))
    expect(privateLeaks, `console 私有内容泄漏: ${privateLeaks.join(' | ')}`).toEqual([])
    const expectedAppErrors = consoleErrors.filter((line) =>
      line.includes('本工程没有可信的完整参考分析'))
    const leaks = consoleLeaks(consoleErrors, benign)
      .filter((line) => !expectedAppErrors.includes(line))
    expect(leaks, `console error 泄漏: ${consoleErrors.join(' | ')}`).toEqual([])

    writeFileSync(resolve(EVIDENCE, 'TC-F3-11-03-vision.json'), JSON.stringify({
      projectId,
      shots,
      redReferenceMediaHash: redSha,
      sessionInvalidation: { staleSessionId: sid, staleCallStatus: staleCall.status() },
      observations: [
        '双主题×桌面/移动截图各状态均断言 html[data-theme] 与视口尺寸真实生效、移动无横向溢出',
        '键盘 Enter 驱动再生成（UI-03 文案）；焦点环为 :focus-visible 语义，脚本 focus 不触发属浏览器正确行为',
        '生成中退出登录换 B：B 打不开 A 工程；回 A 终态重读且按钮无跨账号残留',
        'Studio 关闭后旧会话面调用被拒（非 2xx、无会话内容）',
        '分析收敛判据：analyze agent-job 终态 succeeded（GET /reference-analysis 是 intelligence 内部端点，edge 未代理）',
      ],
    }, null, 2))
  })
})

test.describe('C107F3-11 Edge 变体穿透与受控输入差异', () => {
  test('C107F3-11 变体构建取消/重试经真实浏览器穿透；红/蓝参考产生可区分分析分段', async ({ page, request }) => {
    test.setTimeout(JOURNEY_TIMEOUT_MS + 420_000)
    await seedFix2TextModel(request)
    mkdirSync(EVIDENCE, { recursive: true })
    await login(page, OWNER_A)
    const consoleErrors = collectConsoleErrors(page)
    const benign = collectBenignResponses(page)
    await openWorkspace(page)
    const projectId = await createProject(page, 'fix3-c11-变体与输入差异工程')

    // ① 受控输入差异：两个 12s 参考——纯红（六中点全红→1 段）与红/蓝各半拼接
    // （1-5s 红、7-11s 蓝→红蓝 2 段）→ 两次真实「全片分析」（按行定位新素材行，
    // 不用 first() 误点旧行）→ 经 edge 已路由的 GET jobs/{jobId} 轮询 analyze job
    // 终态，从 checkpoint 的 reference.analyze 观测提取真实分段数。严格 fixture 按
    // 真实解码主色游程切段：分段结构（1 vs 2）由输入内容驱动——这是经产品 API 面
    // 可观测的内容差异证据（GET /reference-analysis 是 intelligence 内部端点，
    // hypit-api 契约与 edge 路由表均未登记、经 edge 恒 404，不做观测面）。
    const redMp4 = makeSolidColorMp4('red', 'c11-input-red.mp4')
    const splitMp4 = makeSplitColorMp4('c11-input-red-blue-split.mp4')
    const analyses: Array<{ label: string; mediaHash: string; analysisId: string; segments: number; status: string }> = []
    for (const input of [
      { label: 'red-solid', file: redMp4, expectedSegments: 1 },
      { label: 'red-blue-split', file: splitMp4, expectedSegments: 2 },
    ]) {
      await page.getByRole('button', { name: '参考素材' }).click()
      await page.setInputFiles('[data-testid="clone-upload-input"]', {
        name: `reference-${input.label}.mp4`, mimeType: 'video/mp4', buffer: readFileSync(input.file),
      })
      await page.getByTestId('clone-upload-submit').click()
      const mediaHash = await assetSha(page, projectId, input.file)
      // 新素材行定位：观察面拿该 sha 的 mediaId/resourceHandle → 行内点「全片分析」。
      const rowName = await (async () => {
        const response = await page.request.get(`${AI_BASE}/api/hypit/projects/${projectId}/assets`)
        const payload = await response.json() as { data?: { items?: Array<{ sha256?: string; mediaId?: string | null; resourceHandle?: string }> } }
        const mine = (payload.data?.items ?? []).find((item) => item.sha256 === mediaHash)
        return mine?.mediaId ?? mine?.resourceHandle ?? ''
      })()
      expect(rowName, '素材行必须有可定位名称').toBeTruthy()
      // 拦截分析 POST 回执（仅观测拿 jobId，被测动作是下一行的真实按钮点击）。
      const receiptPromise = page.waitForResponse((res) =>
        res.request().method() === 'POST' && /\/api\/hypit\/projects\/[^/]+\/agent-jobs$/.test(res.url()))
      await page.locator('li.clone-asset').filter({ hasText: rowName }).getByTestId('clone-analyze').click()
      const receiptPayload = await receiptPromise.then((res) => res.json()).catch(() => null) as
        { data?: { jobId?: string } } | null
      const jobId = receiptPayload?.data?.jobId ?? ''
      expect(jobId, 'analyze job 回执必须带 jobId').toBeTruthy()
      // 分析收敛轮询（观察面；动作已由真实按钮触发）：job 终态 succeeded 即分析
      // 持久化（worker 终态前完成 persist + clone.plan 派生）；失败终态带出
      // checkpoint 真实原因，不静默。
      let detailText = ''
      await expect.poll(async () => {
        const detail = await page.request.get(`${AI_BASE}/api/hypit/projects/${projectId}/jobs/${jobId}`)
        detailText = await detail.text()
        if (detail.status() !== 200) return `http-${detail.status()}`
        return String((JSON.parse(detailText) as { data?: { state?: string } }).data?.state ?? 'unknown')
      }, { timeout: 300_000, message: `${input.label} 分析 job 必须收敛终态` }).toBe('succeeded')
      const checkpoint = JSON.parse((JSON.parse(detailText) as { data?: { checkpointSummary?: string } })
        .data?.checkpointSummary ?? '{}') as {
        observations?: Array<{ state?: string; result?: { tool?: string; analysisId?: string; status?: string; segments?: number } }>
      }
      const analyzeObs = (checkpoint.observations ?? []).find((obs) => obs.result?.tool === 'reference.analyze')
      expect(analyzeObs?.state, `${input.label} 分析观测必须 succeeded`).toBe('succeeded')
      expect(String(analyzeObs?.result?.status)).toBe('SUCCEEDED')
      expect(typeof analyzeObs?.result?.analysisId).toBe('string')
      expect(analyzeObs?.result?.segments, `${input.label} 分析分段数必须为 ${input.expectedSegments}（内容驱动）`)
        .toBe(input.expectedSegments)
      analyses.push({
        label: input.label, mediaHash, analysisId: String(analyzeObs?.result?.analysisId),
        segments: Number(analyzeObs?.result?.segments), status: String(analyzeObs?.result?.status),
      })
    }
    expect(analyses[0]!.segments, '受控输入差异：纯红与红蓝拼接的分段结构必须可区分').not.toBe(analyses[1]!.segments)
    expect(analyses[0]!.analysisId).not.toBe(analyses[1]!.analysisId)
    expect(analyses[0]!.mediaHash).not.toBe(analyses[1]!.mediaHash)

    // ② Edge 变体穿透：变体构建/取消/重试全部经真实浏览器 → nginx → edge →
    // intelligence（无任何路由拦截）；状态收敛由服务端权威行驱动。
    await page.getByRole('button', { name: '审片与导出' }).click()
    await page.getByTestId('clone-variant-key').fill('topic')
    await page.getByTestId('clone-variant-values').fill('红, 蓝')
    await page.getByTestId('clone-variant-create').click()
    await expect(page.getByTestId('clone-variant-0')).toBeVisible({ timeout: 120_000 })
    await expect(page.getByTestId('clone-variant-1')).toBeVisible({ timeout: 60_000 })

    await page.getByTestId('clone-variant-0').getByTestId('clone-variant-build').click()
    await expect(page.getByTestId('clone-variant-0').getByTestId('clone-variant-state'))
      .toHaveAttribute('data-state', 'succeeded', { timeout: JOURNEY_TIMEOUT_MS })

    // 变体 1：构建受理后趁 queued/running 真实取消（服务端终态 cancelled）。
    await page.getByTestId('clone-variant-1').getByTestId('clone-variant-build').click()
    const cancel = page.getByTestId('clone-variant-1').getByTestId('clone-variant-cancel')
    await cancel.waitFor({ timeout: 120_000 })
    await cancel.click()
    await expect(page.getByTestId('clone-variant-1').getByTestId('clone-variant-state'))
      .toHaveAttribute('data-state', 'cancelled', { timeout: JOURNEY_TIMEOUT_MS })

    // 取消项真实重试（failed/cancelled 态的重试入口）→ 重新收敛 succeeded。
    // 动作链观测：retry 续走 build 的 plan/pricing/build 响应状态与体如实记录，
    // 收敛失败时随错误带出（重试链跨三个请求，无观测无法定位卡点）。
    const actionResponses: Array<{ status: number; url: string; body: string }> = []
    page.on('response', (res) => {
      if (!/\/variants\/[0-9a-f-]{36}\/(build|retry|cancel)$|\/projects\/[0-9a-f-]{36}\/(plan|pricing)$/.test(res.url())) return
      void res.text().then((body) => actionResponses.push({ status: res.status(), url: res.url(), body: body.slice(0, 300) }))
        .catch(() => actionResponses.push({ status: res.status(), url: res.url(), body: '' }))
    })
    const retry = page.getByTestId('clone-variant-1').getByTestId('clone-variant-retry')
    await retry.waitFor({ timeout: 60_000 })
    await retry.click()
    try {
      await expect(page.getByTestId('clone-variant-1').getByTestId('clone-variant-state'))
        .toHaveAttribute('data-state', 'succeeded', { timeout: JOURNEY_TIMEOUT_MS })
    } catch (error) {
      // TS lib 不含 ErrorOptions（构造器 cause 参数编译不过），构造后赋值同效
      // 且满足 preserve-caught-error（保留原始断言错误的完整栈）。
      const wrapped = new Error(`变体 1 重试未收敛；动作链观测: ${JSON.stringify(actionResponses.slice(-10), null, 1)}；原始断言错误: ${String(error)}`)
      ;(wrapped as Error & { cause?: unknown }).cause = error
      throw wrapped
    }
    // 成功项不重做：变体 0 保持 succeeded。
    await expect(page.getByTestId('clone-variant-0').getByTestId('clone-variant-state'))
      .toHaveAttribute('data-state', 'succeeded')
    await page.screenshot({ path: resolve(EVIDENCE, 'C107F3-11-variants-converged.png'), fullPage: true })
    for (const theme of ['dark', 'light'] as const) {
      for (const viewport of ['desktop', 'mobile'] as const) {
        await visionShot(page, 'variants-converged', theme, viewport)
        const field = page.getByTestId('clone-variant-key')
        await field.focus()
        await expect(field).toBeFocused()
        await page.keyboard.press('Tab')
        await expect(page.getByTestId('clone-variant-values')).toBeFocused()
        await page.getByTestId('clone-variants-panel').screenshot({
          path: resolve(EVIDENCE, `variants-panel-${theme}-${viewport}.png`),
        })
      }
    }


    const leaks = consoleLeaks(consoleErrors, benign)
    expect(leaks, `console error 泄漏: ${consoleErrors.join(' | ')}`).toEqual([])

    writeFileSync(resolve(EVIDENCE, 'C107F3-11-variants-input-diff.json'), JSON.stringify({
      projectId,
      analyses: analyses.map((item) => ({
        label: item.label, mediaHash: item.mediaHash, analysisId: item.analysisId,
        segments: item.segments, status: item.status,
      })),
      variants: { cancelledThenRetried: 'variant-1', keptSucceeded: 'variant-0' },
    }, null, 2))
  })
})
