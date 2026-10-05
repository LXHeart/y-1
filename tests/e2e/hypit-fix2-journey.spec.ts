// hypit-fix2-journey.spec.ts — 107-fix-2 C107F2-37（§12.5 G2–G5）真实浏览器贯通
// 参考素材到成片、Studio 与工程包；107-fix-3 C107F3-11（W38）把 G3/G5 从
// 「只打开 / 只 ready」补成任务书原要求：Studio 真实 iframe 控件编辑保存重开
// 与 A 导出→B 导入→再编辑→构建→下载全解码。
//
// 真实链路承诺：全部被验收动作经真实 UI 按钮触发（HTTP 走真实
// Nginx/Edge/Java/broker/runner）；只有外部商业模型被替换为受控文本 fixture
// （治理台正式凭据/路由/计费不变，见 fixtures/hypit-fix2.ts seedFix2TextModel）；
// 原生编译、渲染、PG、文件、浏览器零 mock。Studio 的运行时 URL 前缀化
// （部署态 /studio/<sid>/ 挂载 base）由补丁 0005（W73/W74）提供，本文件
// 验证的就是该形态下的真实控件链。
//
// TC-F2-37-01（G2） A 上传 12s 参考素材 → UI 分析 → 方案 → 源码编辑(validated) →
//                    生成 → 归档 → 下载 MP4 → ffprobe 时长/尺寸/可解码。
// TC-F2-37-02（G3）＝TC-F3-11-01
//                    Studio 真实控件编辑 OLD→NEW→保存（捕获既有 writeback 请求、
//                    PG revision/hash）→ 关闭重开仍 NEW → 工作台另一编辑面推进
//                    head 后 Studio 旧会话保存 409+草稿保留 → 重开恢复保存 →
//                    readOnly 会话真实控件保存被 403 拒+输入保留。
// TC-F2-37-03（G4） 两变体批量构建收敛；变体定向失败注入后 UI 重试收敛（成功项不重做）。
// TC-F2-37-04（G5）＝TC-F3-11-02
//                    A 全链成片（记录源/成片 sha）→ 导出 zip；B 登录 UI 上传导入 →
//                    参考分析→方案（作者任务重写源后）→ Studio 真实编辑 NEW-B →
//                    保存 → 构建 → 下载 → ffprobe+FFmpeg 全解码；A 历史源/成片
//                    sha 不变；A 打不开 B 的工程（越权 404）。
import { execFileSync } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test, type APIRequestContext, type Locator, type Page, type Response } from '@playwright/test'

import {
  AI_BASE, JOURNEY_TIMEOUT_MS, OWNER_A, OWNER_B,
  createProject, ffprobe, login, makeReferenceMp4, openWorkspace, seedFix2TextModel,
} from './fixtures/hypit-fix2'

// 证据根可覆盖（分层验收编排注入 HYPIT_FIX3_EVIDENCE_ROOT 指向当前 run stage 目录）；
// 未注入时保持独立缺省路径，不写进 fix2 历史目录（与 W39 同款约定）。
const EVIDENCE = process.env.HYPIT_FIX3_EVIDENCE_ROOT
  ? resolve(process.env.HYPIT_FIX3_EVIDENCE_ROOT, 'journey')
  : resolve('test-artifacts/task-107/fix3/journey')

/** 与 fixture 作者产物同源的可编译 3s 双色源（编辑步骤的输入）。 */
const AUTHORED_SVML = `<?svml using="@hypit/markup@1"?>
<svml>
  <import as="time" from="@hypit/timeline-author@1"/>
  <import as="spatial" from="@hypit/spatial@1"/>
  <import as="film" from="@hypit/film@1"/>
  <import as="render" from="@hypit/render-hyperframes@1"/>
  <import as="overlay" from="@hypit/screen-overlay@1"/>
  <import as="recipes" source="./style.svs"/>

  <time:Clock id="clock" frame-rate="10"/>
  <time:Timeline id="animation" clock={clock} end="3s"/>
  <spatial:Canvas id="canvas" width="320" height="240"/>
  <overlay:Track id="overlay" canvas={canvas} timeline={animation.timeline}>
    <overlay:DirectionalMatte id="redWall" z="1" start="0s" end="3s" angle="0" coverage="0.4" feather="0.2" color="#c03030" opacity="1" from="-0.6" to="0.9"/>
    <overlay:DirectionalMatte id="blueWall" z="2" start="0s" end="3s" angle="0" coverage="0.4" feather="0.2" color="#3050c0" opacity="1" from="1.6" to="0.1"/>
  </overlay:Track>
  <film:Film id="main" canvas={canvas} timeline={animation.timeline} appearance={recipes.film.main}>
    <film:Track source={overlay.track}/>
  </film:Film>
  <render:Video id="final" composition={main.composition} timeline={animation.timeline}/>
</svml>
`

const AUTHORED_SVS = `<?svml using="@hypit/svs@1"?>
<sheet version="1">
  /* journey 编辑产物：黑底衬托色墙运动（D-05 样式入口）。 */
  film.main { background: #000000; }
</sheet>
`

// ── TC-F3-11-01 固定标识链（§12.2「正文含OLD→OLD改NEW」） ──────────────────
// 新工程 clone/brief 骨架（fixtures/blank，main.svml 的 TODO(author) 注释）出现且
// 只出现 1 次——测试先断言锚点唯一再替换，替换不改变 SVML 合法性（都在注释里）。
// 标记互不为彼此子串（冒号位置不同），链式替换不会误伤下游标记。
const OLD_MARKER = 'TODO(author):'
const MARKER_NEW = 'C107F3-11:'
const MARKER_OOB = 'C107F3-11-OOB:'
const MARKER_CONFLICT = 'C107F3-11-CONFLICT:'
const MARKER_RECOVERED = 'C107F3-11-RECOVERED:'
const MARKER_READONLY = 'C107F3-11-READONLY:'
/** 跨 owner 移植的 B 侧编辑标记：fixture 作者源 redWall 的颜色值（出现且仅出现 1 次）。 */
const B_EDIT_FROM = '#c03030'
const B_EDIT_TO = '#c13031'

/** 侧栏源文件按钮（label = `main SVML`，locale 无关——stem+语言大写）。 */
const MAIN_SVML_ITEM = 'button.sidebar-panel-item[aria-label="main SVML"]'

function collectConsoleErrors(page: Page): string[] {
  const errors: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text())
  })
  page.on('pageerror', (error) => errors.push(String(error)))
  return errors
}

/**
 * 控制台卫生断言的豁免清单（按响应 URL 精确豁免，不做全文模糊过滤）：
 * - clone-plan 404：面板首挂「尚无方案」的正式契约（空态轮询），非泄漏；
 * - changeset apply 409：面板保存与 author 任务写回（plan 行 READY 早于
 *   mutation.apply 完成）的既有 CAS 竞态，产品有「冲突→刷新基线重提」恢复
 *   动线，测试侧已按该动线重存一次；
 * - agent-jobs 409（C107F3-11/W38）：author 再生成在分析未完成窗口的服务端
 *   前置拒绝（UI-03 文案）——收敛循环首点可落入该窗口，预期拒绝面；
 * - C107F3-11：Studio 写回面 PUT /__studio/source 的 409（旧基线被拒）与
 *   403（readOnly 会话拒写）——这正是 TC-F3-11-01 的目标保护断言面，浏览器
 *   会如实打一行 Failed to load resource，按实际响应精确豁免（只豁免被本
 *   测试断言过的状态码+URL 面，不改其他 409/403 的判读）；
 * - C107F3-11（W38）：换号移植链（TC-F3-11-02）登出后，A 会话残留的 API
 *   轮询按会话失效如实 401（smoke17 实证 4 条，全部 /api/ 面）——预期登出
 *   语义而非泄漏；与 reference spec 的登出 401 豁免同款，按 URL 含 /api/
 *   精确登记。
 */
function expectedBenignResponses(page: Page): { status: number; urlPattern: RegExp }[] {
  const expected: { status: number; urlPattern: RegExp }[] = []
  page.on('response', (res) => {
    if (res.status() === 404 && /\/api\/hypit\/projects\/[^/]+\/clone-plan$/.test(res.url())) {
      expected.push({ status: 404, urlPattern: /clone-plan/ })
    }
    if (res.status() === 409 && /\/changesets\/[^/]+\/apply$/.test(res.url())) {
      expected.push({ status: 409, urlPattern: /changesets/ })
    }
    // C107F3-11（W38）：author 再生成在参考分析未完成窗口被服务端 409 前置拒绝
    // （UI-03 文案「请先完成参考视频分析」，HypitAuthorContextService）——收敛循环
    // 首点落在该窗口是预期动线（正文步骤③注释同源），浏览器如实打一行网络错误；
    // 与上面的 changeset 409 同理按 URL+状态精确豁免，不改其他 409 的判读。
    if (res.status() === 409 && /\/api\/hypit\/projects\/[^/]+\/agent-jobs$/.test(res.url())) {
      expected.push({ status: 409, urlPattern: /agent-jobs/ })
    }
    if ((res.status() === 409 || res.status() === 403)
      && res.request().method() === 'PUT' && /\/__studio\/source$/.test(res.url())) {
      expected.push({ status: res.status(), urlPattern: /\/__studio\/source$/ })
    }
    if (res.status() === 401 && /\/api\//.test(res.url())) {
      expected.push({ status: 401, urlPattern: /\/api\// })
    }
  })
  return expected
}

/** 把「Failed to load resource: … 404/409/403」行与豁免清单比对后剩下的真泄漏。 */
function consoleLeaks(consoleErrors: string[], benign: { status: number }[]): string[] {
  // favicon/DevTools：浏览器自身噪声。C107F3-11（W38）：Firefox 与 WebKit 会把
  // nginx 部署面的 CSP **Report-Only**（nginx.conf $csp_policy_report_only——
  // CSP_MODE 缺省的观察性姿态，Studio srcdoc 内联脚本被上报而不被阻断）逐条
  // 打成 console error，两引擎文案不同（Firefox「Content-Security-Policy:
  // (Report-Only policy) …」/ WebKit「[Report Only] Refused to execute …」），
  // chromium 不上报该面（smoke40 firefox / smoke43 webkit 实证：泄漏行全部为
  // 该面，零真泄漏）。只豁免带 Report-Only 标记的行——真被 enforced 策略阻断
  // 的告警（无该标记）仍按泄漏失败。
  let rest = consoleErrors.filter((line) => !/favicon|DevTools/i.test(line)
    && !/(Content-Security-Policy: \(Report-Only policy\))|(^\[Report Only\])/i.test(line))
  for (const { status } of benign) {
    const marker = `status of ${status}`
    const hit = rest.find((line) => line.includes('Failed to load resource') && line.includes(marker))
    if (hit) rest = rest.filter((line) => line !== hit)
  }
  return rest
}

async function seedModel(request: APIRequestContext): Promise<void> {
  await seedFix2TextModel(request)
}

/** 观察面：GET /projects/{id}/file（与工作台源码面板同一读面）取源码内容/hash/revision。
 * 只用于观测与取证——被测保存动作一律走真实控件，绝不用它代替保存按钮。 */
async function readSource(page: Page, projectId: string, path = 'main.svml'): Promise<{
  content: string; hash: string; revision: number
}> {
  const response = await page.request.get(
    `${AI_BASE}/api/hypit/projects/${projectId}/file?path=${encodeURIComponent(path)}`)
  expect(response.status(), `读 ${path} 观察面失败: ${await response.text().catch(() => '')}`).toBe(200)
  const payload = await response.json() as {
    success?: boolean; data?: { content?: string; hash?: string; revision?: number }
  }
  const data = payload.data
  expect(payload.success).toBe(true)
  expect(typeof data?.content).toBe('string')
  expect(typeof data?.hash).toBe('string')
  expect(typeof data?.revision).toBe('number')
  const content = data?.content
  const hash = data?.hash
  const revision = data?.revision
  if (content === undefined || hash === undefined || revision === undefined) {
    throw new Error(`文件响应缺 hash/revision 契约字段: ${JSON.stringify(payload).slice(0, 200)}`)
  }
  return { content, hash, revision }
}

/** 捕获 Studio 真实保存按钮链发出的 PUT /__studio/source（writeback 面，经
 * nginx auth_request→broker 桥拦截→Java changeset/apply）。 */
function waitForStudioSourcePut(page: Page): Promise<{ response: Response; body: string }> {
  return page.waitForResponse((res) =>
    res.request().method() === 'PUT' && /\/__studio\/source$/.test(res.url()))
    .then(async (response) => ({ response, body: await response.text().catch(() => '') }))
}

/** Studio 控件根作用域：page（ticketUrl 整页挂载）或 frameLocator（工作台 iframe 嵌入）。 */
type StudioScope = { locator(selector: string): Locator }

/** Studio 源码编辑器进入编辑态（幂等：已是编辑态不重复点击——重复点击会切回
 * 只读态导致 textarea 隐藏）。等待 main SVML 文件项出现＝快照已到、真渲染（不把
 * iframe body 存在当成功）。 */
async function enterStudioEditMode(scope: StudioScope, timeout = 180_000): Promise<void> {
  await scope.locator(MAIN_SVML_ITEM).waitFor({ timeout })
  await scope.locator(MAIN_SVML_ITEM).click()
  const editing = await scope.locator('section.code').evaluate((el) => el.classList.contains('is-editing'))
  if (!editing) await scope.locator('[data-mode]').click()
  await scope.locator('textarea[data-editor]').waitFor({ timeout: 60_000 })
}

function sha256(bytes: Buffer): string {
  return createHash('sha256').update(bytes).digest('hex')
}

/** FFmpeg 全解码（任务书 §12.2 TC-F3-11-02 断言项）：-v error 下任何坏帧/坏包
 * 都会写 stderr 并以非零退出；全解码通过＝静默 exit 0。 */
function ffmpegFullDecode(path: string): void {
  execFileSync('ffmpeg', ['-v', 'error', '-i', path, '-f', 'null', '-'], { stdio: 'pipe' })
}

/** A/B 共链：上传 12s 参考→分析（真实 probe/frames+受控综合模型）→ 再生成方案
 * （fix3 新模式 author 任务真实写回 fixture 作者源并持久方案）→ 终态后生成按钮
 * 可用。分析在本机满栈实录 60s+，收敛判据是 busy 清零（按钮可用），不是等待时长。 */
async function uploadAnalyzeAndPlan(page: Page): Promise<void> {
  const reference = makeReferenceMp4()
  await page.setInputFiles('[data-testid="clone-upload-input"]', {
    name: 'reference-12s.mp4', mimeType: 'video/mp4', buffer: readFileSync(reference),
  })
  await page.getByTestId('clone-upload-submit').click()
  await page.getByTestId('clone-analyze').first().waitFor({ timeout: 180_000 })
  await page.getByTestId('clone-analyze').first().click()
  await page.getByTestId('clone-material-panel').waitFor({ timeout: 60_000 })
  await page.getByRole('button', { name: '复刻方案' }).click()
  const regenerate = page.getByTestId('clone-plan-regenerate')
  await expect(regenerate).toBeEnabled({ timeout: 180_000 })
  // 分析在途时再生成会被服务端 409 前置拒绝（UI-03 文案）——该窗口内的失败是
  // 预期拒绝面；循环收敛到 author 任务被受理（202→重新生成中→终态方案已更新）。
  for (let attempt = 0; attempt < 30; attempt += 1) {
    const waitingVisible = await page.getByTestId('clone-job-waiting').isVisible().catch(() => false)
    const emptyVisible = await page.getByTestId('clone-plan-empty').isVisible().catch(() => false)
    if (waitingVisible || emptyVisible) {
      await regenerate.click()
      await page.waitForTimeout(20_000)
    }
    if (await page.getByTestId('clone-generate').isEnabled().catch(() => false)) break
  }
  // 终态重读：方案已更新出现且生成按钮可用（fix3 C10 的 UI-02 契约）。
  await expect(page.getByTestId('clone-generate')).toBeEnabled({ timeout: JOURNEY_TIMEOUT_MS })
  await expect(page.getByTestId('clone-plan-gaps')).toBeHidden()
}

/** 复刻方案页生成→等原生渲染收敛（面板行「已完成·complete」）。 */
async function generateAndWaitFinished(page: Page): Promise<void> {
  await page.getByTestId('clone-generate').click()
  await expect(page.getByTestId('clone-material-panel').getByText(/已完成/).first())
    .toBeVisible({ timeout: JOURNEY_TIMEOUT_MS })
  await expect(page.getByTestId('clone-material-panel').getByText(/complete/i).first())
    .toBeVisible({ timeout: 180_000 })
}

/** 审片与导出页：定位 final.video 成片行（final 行才是成片——列表首行
 * animation.timeline 是 composite JSON），已归档则直接等下载链接，未归档幂等
 * 点归档，再触发浏览器下载并落盘。返回 MP4 路径。
 * 已归档行的短时签名链接由结果面板加载期一次 enrich 生成（fail-soft：单项失败
 * 不重试、重开面板才重触发——useHypitResults.enrichDownloadUrls）；重查历史工程
 * 时该次 enrich 可能被装载期竞态吞掉，故链接未现时整页重载重新走面板装载。 */
async function downloadFinalVideo(page: Page, evidenceName: string): Promise<string> {
  // 签名面观测（enrich fail-soft 吞错，失败时无从得知原因）——/api/media/ 响应
  // 状态/体如实记录，链接最终未出现时随错误带出。
  const signResponses: Array<{ status: number; body: string }> = []
  page.on('response', (res) => {
    if (!/\/api\/media\//.test(res.url())) return
    void res.text().then((body) => signResponses.push({ status: res.status(), body: body.slice(0, 200) }))
      .catch(() => signResponses.push({ status: res.status(), body: '' }))
  })
  const downloadLink = () => page.locator('li.clone-output').filter({ hasText: 'final' })
    .getByRole('link', { name: '下载' })
  for (let attempt = 0; attempt < 2; attempt += 1) {
    if (attempt > 0) await page.reload()
    await page.getByRole('button', { name: '审片与导出' }).click()
    const videoRow = page.locator('li.clone-output').filter({ hasText: 'final' })
    await videoRow.waitFor({ timeout: 60_000 })
    const archive = videoRow.getByTestId('clone-archive')
    if (await archive.isVisible().catch(() => false)) {
      await archive.click()
    }
    try {
      await downloadLink().waitFor({ timeout: attempt === 0 ? 45_000 : 120_000 })
      break
    } catch (error) {
      if (attempt === 1) {
        // TS lib 不含 ErrorOptions（构造器 cause 参数编译不过），构造后赋值同效
        // 且满足 preserve-caught-error（保留原始等待错误的完整栈）。
        const wrapped = new Error(`下载链接未出现；/api/media/ 签名面观测: ${JSON.stringify(signResponses)}；原始等待错误: ${String(error)}`)
        ;(wrapped as Error & { cause?: unknown }).cause = error
        throw wrapped
      }
    }
  }
  const downloadPromise = page.waitForEvent('download', { timeout: 120_000 })
  await downloadLink().click()
  const download = await downloadPromise
  const mp4Path = resolve(EVIDENCE, evidenceName)
  await download.saveAs(mp4Path)
  return mp4Path
}

test.describe('C107F2-37 真实浏览器贯通（G2–G5）', () => {

  test('TC-F2-37-01 上传→分析→方案→编辑→生成→归档→下载 MP4（时长/尺寸可核验）', async ({ page, request }) => {
    test.setTimeout(JOURNEY_TIMEOUT_MS + 180_000)
    await seedModel(request)
    mkdirSync(EVIDENCE, { recursive: true })
    writeFileSync(resolve(EVIDENCE, 'authored-main.svml'), AUTHORED_SVML)
    writeFileSync(resolve(EVIDENCE, 'authored-style.svs'), AUTHORED_SVS)

    await login(page, OWNER_A)
    // console 收集从登录后开始：登出态的 boot 鉴权探测（auth 401）是应用预期
    // 行为，不属于被验收链路的泄漏。
    const consoleErrors = collectConsoleErrors(page)
    const benign = expectedBenignResponses(page)
    await openWorkspace(page)
    await createProject(page, 'journey-贯通工程')

    // ① 上传 12s 参考素材（真实 XHR 上传 → 素材行可分析）。
    await uploadAnalyzeAndPlan(page)

    // ④ 编辑（源码面板 validated 保存——真实 changeset + runner check + revision 冻结）。
    await page.getByRole('button', { name: '生成与编辑' }).click()
    await page.getByTestId('clone-source-panel').waitFor()
    // 先选中 main.svml（textarea 按选中文件渲染，工程刚开无 activePath）。
    await page.getByRole('button', { name: 'main.svml' }).click()
    await page.getByTestId('clone-source-textarea').waitFor()
    await page.getByTestId('clone-source-textarea').fill(AUTHORED_SVML)
    await page.getByTestId('clone-source-save-validated').click()
    // validated 保存返回（按钮恢复可用）——真实 changeset+check+冻结。plan 行 READY
    // 可能早于 author 任务 mutation.apply 完成（服务端两相推进），保存会 409 冲突：
    // 按产品「冲突→重开同文件刷新基线（保留草稿）→重存」恢复动线重试一次。
    await expect(page.getByTestId('clone-source-save-validated')).toBeEnabled({ timeout: 180_000 })
    const conflictVisible = await page.getByTestId('clone-source-conflict').isVisible().catch(() => false)
    if (conflictVisible) {
      await page.getByRole('button', { name: 'main.svml' }).click()
      await page.getByTestId('clone-source-textarea').waitFor()
      await page.getByTestId('clone-source-save-validated').click()
      await expect(page.getByTestId('clone-source-save-validated')).toBeEnabled({ timeout: 180_000 })
    }
    await expect(page.getByTestId('clone-source-conflict')).toBeHidden({ timeout: 10_000 })

    // ⑤ 生成（check→plan→pricing→本地 Need→submit→原生渲染）：build 行 lifecycle
    // 文本落「finished · complete」（结果就绪）。
    await page.getByRole('button', { name: '复刻方案' }).click()
    await generateAndWaitFinished(page)

    // ⑥ 归档 + 下载 + ffprobe（时长 3s±1、320x240、ftyp 可解码）。
    // 行级定位：final.video 才是成片——列表首行 animation.timeline 是 composite
    // JSON，.first() 归档/下载拿到的是元数据不是 MP4（C107F2-37 缺陷 AB 连带）。
    await page.getByRole('button', { name: '审片与导出' }).click()
    const videoRow = page.locator('li.clone-output').filter({ hasText: 'final' })
    await videoRow.getByTestId('clone-archive').click()
    await videoRow.getByRole('link', { name: '下载' }).waitFor({ timeout: 120_000 })
    const downloadPromise = page.waitForEvent('download', { timeout: 120_000 })
    await videoRow.getByRole('link', { name: '下载' }).click()
    const download = await downloadPromise
    const mp4Path = resolve(EVIDENCE, 'TC-F2-37-01-final.mp4')
    await download.saveAs(mp4Path)
    const probe = ffprobe(mp4Path)
    expect(probe.durationSeconds, `成片时长 ${probe.durationSeconds}`).toBeGreaterThan(2)
    expect(probe.durationSeconds).toBeLessThan(5)
    expect(probe.width).toBe(320)
    expect(probe.height).toBe(240)
    const bytes = readFileSync(mp4Path)
    expect(bytes.subarray(4, 8).toString('ascii'), 'MP4 ftyp 魔数（可解码）').toBe('ftyp')
    const leaks = consoleLeaks(consoleErrors, benign)
    expect(leaks, `console error 泄漏: ${consoleErrors.join(' | ')}`).toEqual([])
  })

  test('TC-F2-37-02/TC-F3-11-01 Studio真实控件编辑保存重开：OLD→NEW→writeback命中PG→CAS 409→readOnly 403（输入保留）', async ({ page, request }) => {
    test.setTimeout(JOURNEY_TIMEOUT_MS + 300_000)
    await seedModel(request)
    mkdirSync(EVIDENCE, { recursive: true })
    await login(page, OWNER_A)
    // 同 TC-01：登出态 boot 鉴权探测的 401 是预期行为，收集从登录后开始。
    const consoleErrors = collectConsoleErrors(page)
    const benign = expectedBenignResponses(page)
    await openWorkspace(page)
    const projectId = await createProject(page, 'journey-studio编辑保存工程')
    await page.getByRole('button', { name: '生成与编辑' }).click()
    await page.getByTestId('clone-source-panel').waitFor()

    // 观察基线：新工程 clone/brief 骨架源码（revision 1）。断言锚点唯一再替换。
    const baseline = await readSource(page, projectId)
    expect(baseline.content.split(OLD_MARKER), `锚点 ${OLD_MARKER} 必须恰好出现 1 次`).toHaveLength(2)
    expect(baseline.revision).toBeGreaterThan(0)

    // ① 打开 Studio（真实 iframe：nginx auth_request 票据核销→broker 代理面→
    // 子进程 vite base 挂载渲染，补丁 0005 前缀化运行时 URL）。
    await page.getByTestId('clone-open-studio').click()
    const frame = page.frameLocator('iframe.clone-studio-frame')
    // 真渲染证据（不把 body 存在当成功）：源码侧栏的 main SVML 文件项可见。
    await enterStudioEditMode(frame)

    // ② 真实控件编辑 OLD→NEW 并保存（文件项→编辑切换→textarea→Ctrl/Cmd+S）。
    const editor = frame.locator('textarea[data-editor]')
    const original = await editor.inputValue()
    expect(original, '骨架源码应含锚点').toContain(OLD_MARKER)
    const edited = original.replace(OLD_MARKER, MARKER_NEW)
    expect(edited).not.toBe(original)
    writeFileSync(resolve(EVIDENCE, 'TC-F3-11-01-studio-edited-main.svml'), edited)
    const putPromise = waitForStudioSourcePut(page)
    await editor.fill(edited)
    await editor.press('ControlOrMeta+s')
    const { response: put, body: putBody } = await putPromise
    // writeback 命中 Java changeset/apply：202 + 回执 revision（writeback 通道
    // 桥层契约由 W63 studio 组锁定，这里断言浏览器可见面）。
    expect(put.status(), `保存应 202（实际 ${put.status()} ${putBody}）`).toBe(202)
    const putReceipt = JSON.parse(putBody) as { revision?: number }
    expect(typeof putReceipt.revision).toBe('number')
    expect(putReceipt.revision, '保存后 revision 必须越过基线').toBeGreaterThan(baseline.revision)
    await expect(frame.locator('[data-save-state]'), '保存态应 saved').toHaveClass(/code-save-state saved/, { timeout: 30_000 })
    await page.screenshot({ path: resolve(EVIDENCE, 'TC-F3-11-01-studio-edit-saved.png'), fullPage: true })

    // 观察面同证：文件内容 NEW、hash 变化、revision 推进（PG 持久，非 DOM 假象）。
    const afterSave = await readSource(page, projectId)
    expect(afterSave.content).toContain(MARKER_NEW)
    expect(afterSave.content).not.toContain(OLD_MARKER)
    expect(afterSave.hash).not.toBe(baseline.hash)
    expect(afterSave.revision).toBeGreaterThan(baseline.revision)

    // ③ 关闭→重开（一次性票据过期语义：重开走新会话新票据）→ 仍 NEW（重开重读，
    // 不是旧 DOM）。重开断言走编辑态 textarea 的服务端回读值。
    await page.getByRole('button', { name: '关闭编辑器' }).click()
    await expect(page.locator('iframe.clone-studio-frame')).toBeHidden({ timeout: 60_000 })
    await page.getByTestId('clone-open-studio').click()
    await enterStudioEditMode(frame)
    // 编辑器值是快照到达后的异步赋值（textarea 先出现、value 后落）——以真实
    // 条件轮询（内容含 NEW）替代立即读，失败快照曾实证 DOM 稍后即有完整内容。
    await expect.poll(() => editor.inputValue(), { timeout: 30_000, message: '重开编辑器内容加载' })
      .toContain(MARKER_NEW)
    const reopened = await editor.inputValue()
    expect(reopened, '重开后源码必须仍是 NEW（服务端回读）').toContain(MARKER_NEW)
    expect(reopened).not.toContain(OLD_MARKER)

    // ④ CAS：工作台源码面板（另一编辑面，同屏）推进 head → Studio 旧会话（基线
    // 落在 out-of-band 之前）再编辑保存 → 409 + 草稿保留 + 服务端零污染。
    // 工作台面板先刷新基线（点击文件重读），草稿从当前服务端内容推导。
    const workbenchRow = page.getByTestId('clone-source-panel').getByRole('button', { name: 'main.svml' })
    await workbenchRow.click()
    const workbenchTextarea = page.getByTestId('clone-source-textarea')
    await workbenchTextarea.waitFor()
    // 面板 textarea 的值是点击文件后异步取回的——轮询到内容就绪再取（同 ③）。
    await expect.poll(() => workbenchTextarea.inputValue(), { timeout: 30_000, message: '工作台面板内容加载' })
      .toContain(MARKER_NEW)
    const workbenchCurrent = await workbenchTextarea.inputValue()
    expect(workbenchCurrent).toContain(MARKER_NEW)
    const outOfBand = workbenchCurrent.replace(MARKER_NEW, MARKER_OOB)
    expect(outOfBand).not.toBe(workbenchCurrent)
    await workbenchTextarea.fill(outOfBand)
    await page.getByTestId('clone-source-save-validated').click()
    await expect(page.getByTestId('clone-source-save-validated')).toBeEnabled({ timeout: 180_000 })
    // 与 TC-F2-37-01 同款恢复动线兜底：面板若与并发写回竞争出 409，按产品
    // 「冲突→重开同文件刷新基线（保留草稿）→重存」重试一次。
    if (await page.getByTestId('clone-source-conflict').isVisible().catch(() => false)) {
      await workbenchRow.click()
      await workbenchTextarea.waitFor()
      await workbenchTextarea.fill(outOfBand)
      await page.getByTestId('clone-source-save-validated').click()
      await expect(page.getByTestId('clone-source-save-validated')).toBeEnabled({ timeout: 180_000 })
    }
    await expect(page.getByTestId('clone-source-conflict')).toBeHidden({ timeout: 10_000 })
    const afterOutOfBand = await readSource(page, projectId)
    expect(afterOutOfBand.content).toContain(MARKER_OOB)
    expect(afterOutOfBand.revision).toBeGreaterThan(afterSave.revision)

    // Studio 仍打开的旧会话上再编辑（从编辑器当前值推导——若快照已把它刷新为
    // out-of-band 内容也如实替换；标记互不为子串，链式替换不误伤）。
    const studioCurrent = await editor.inputValue()
    const staleEdit = studioCurrent.replace(MARKER_NEW, MARKER_CONFLICT).replace(MARKER_OOB, MARKER_CONFLICT)
    expect(staleEdit).not.toBe(studioCurrent)
    expect(staleEdit).toContain(MARKER_CONFLICT)
    const conflictPromise = waitForStudioSourcePut(page)
    await editor.fill(staleEdit)
    await editor.press('ControlOrMeta+s')
    const { response: conflictPut, body: conflictBody } = await conflictPromise
    expect(conflictPut.status(), `旧基线保存必须 409（实际 ${conflictPut.status()} ${conflictBody}）`).toBe(409)
    await expect(frame.locator('[data-save-state]'), '409 后保存态应 error').toHaveClass(/code-save-state error/, { timeout: 30_000 })
    // 草稿保留：textarea 仍是本次尝试内容（未被服务端内容覆盖），且服务端未被污染。
    const preserved = await editor.inputValue()
    expect(preserved, '409 后编辑器草稿必须保留').toContain(MARKER_CONFLICT)
    expect(preserved).not.toContain(MARKER_OOB)
    const afterConflict = await readSource(page, projectId)
    expect(afterConflict.content, '409 后服务端内容必须保持 out-of-band 值').toContain(MARKER_OOB)
    expect(afterConflict.revision, '409 拒绝必须零 revision 推进').toBe(afterOutOfBand.revision)
    await page.screenshot({ path: resolve(EVIDENCE, 'TC-F3-11-01-studio-conflict-409.png'), fullPage: true })

    // ⑤ 重开恢复（按新 head 重开会话）→ 显示 out-of-band 值 → 再保存成功（恢复动线）。
    await page.getByRole('button', { name: '关闭编辑器' }).click()
    await expect(page.locator('iframe.clone-studio-frame')).toBeHidden({ timeout: 60_000 })
    await page.getByTestId('clone-open-studio').click()
    await enterStudioEditMode(frame)
    await expect.poll(() => editor.inputValue(), { timeout: 30_000, message: '重开编辑器内容加载' })
      .toContain(MARKER_OOB)
    const recoveredView = await editor.inputValue()
    expect(recoveredView, '按新 head 重开后必须显示 out-of-band 值').toContain(MARKER_OOB)
    const recoveredEdit = recoveredView.replace(MARKER_OOB, MARKER_RECOVERED)
    const recoverPromise = waitForStudioSourcePut(page)
    await editor.fill(recoveredEdit)
    await editor.press('ControlOrMeta+s')
    const { response: recoverPut, body: recoverBody } = await recoverPromise
    expect(recoverPut.status(), `恢复保存应 202（实际 ${recoverPut.status()} ${recoverBody}）`).toBe(202)
    await expect(frame.locator('[data-save-state]')).toHaveClass(/code-save-state saved/, { timeout: 30_000 })
    const afterRecovered = await readSource(page, projectId)
    expect(afterRecovered.content).toContain(MARKER_RECOVERED)
    expect(afterRecovered.content).not.toContain(MARKER_OOB)
    expect(afterRecovered.revision).toBeGreaterThan(afterOutOfBand.revision)
    await page.screenshot({ path: resolve(EVIDENCE, 'TC-F3-11-01-studio-recovered.png'), fullPage: true })

    // ⑥ readOnly 会话：API 只做会话 SETUP（被测保存仍走真实控件）；ticketUrl 整页
    // 挂载（同源 AI 入口）→ 真实控件编辑 MARKER_READONLY → Ctrl/Cmd+S → 403 拒写
    // + 输入保留（readOnly 会话 UI 无入口，这是唯一真实通道）。
    await page.getByRole('button', { name: '关闭编辑器' }).click()
    await expect(page.locator('iframe.clone-studio-frame')).toBeHidden({ timeout: 60_000 })
    const created = await page.request.post(`${AI_BASE}/api/hypit/projects/${projectId}/studio-sessions`, {
      data: { requestId: randomUUID(), runFile: 'main.svrun', readOnly: true },
    })
    expect(created.status(), `readOnly 会话创建应 202（实际 ${created.status()}）`).toBe(202)
    const createdPayload = await created.json() as { data?: { ticketUrl?: string; sessionId?: string; readOnly?: boolean } }
    const ticketUrl = createdPayload.data?.ticketUrl
    expect(ticketUrl, '会话回执必须带 ticketUrl').toBeTruthy()
    expect(createdPayload.data?.readOnly).toBe(true)
    await page.goto(`${AI_BASE}${ticketUrl}`)
    // 整页 Studio（非 iframe）：同一套真实控件选择器直接作用于顶层页面。
    await enterStudioEditMode(page)
    const roEditor = page.locator('textarea[data-editor]')
    await expect.poll(() => roEditor.inputValue(), { timeout: 30_000, message: 'readOnly 编辑器内容加载' })
      .toContain(MARKER_RECOVERED)
    const roOriginal = await roEditor.inputValue()
    expect(roOriginal).toContain(MARKER_RECOVERED)
    const roEdit = roOriginal.replace(MARKER_RECOVERED, MARKER_READONLY)
    expect(roEdit).not.toBe(roOriginal)
    const roPromise = waitForStudioSourcePut(page)
    await roEditor.fill(roEdit)
    await roEditor.press('ControlOrMeta+s')
    const { response: roPut, body: roBody } = await roPromise
    expect(roPut.status(), `readOnly 保存必须 403（实际 ${roPut.status()} ${roBody}）`).toBe(403)
    expect(roBody).toContain('read-only')
    await expect(page.locator('[data-save-state]')).toHaveClass(/code-save-state error/, { timeout: 30_000 })
    // 输入保留：编辑器仍持有本次尝试内容；服务端零污染（观察面复核）。
    const roPreserved = await roEditor.inputValue()
    expect(roPreserved, 'readOnly 403 后输入必须保留').toContain(MARKER_READONLY)
    const afterReadOnly = await readSource(page, projectId)
    expect(afterReadOnly.content, 'readOnly 拒写必须零服务端污染').not.toContain(MARKER_READONLY)
    expect(afterReadOnly.content).toContain(MARKER_RECOVERED)
    expect(afterReadOnly.revision).toBe(afterRecovered.revision)
    await page.screenshot({ path: resolve(EVIDENCE, 'TC-F3-11-01-studio-readonly-403.png'), fullPage: true })

    // 证据：writeback 观测链（浏览器可见 PUT 面 + 观察面 hash/revision 推进）。
    writeFileSync(resolve(EVIDENCE, 'TC-F3-11-01-writeback.json'), JSON.stringify({
      projectId,
      markers: { old: OLD_MARKER, new: MARKER_NEW, oob: MARKER_OOB, conflict: MARKER_CONFLICT, recovered: MARKER_RECOVERED, readonly: MARKER_READONLY },
      stages: {
        baseline: { hash: baseline.hash, revision: baseline.revision },
        studioSave: { putStatus: put.status(), receiptRevision: putReceipt.revision, hash: afterSave.hash, revision: afterSave.revision },
        outOfBand: { hash: afterOutOfBand.hash, revision: afterOutOfBand.revision },
        staleStudioSave: { putStatus: conflictPut.status(), serverRevisionAfter: afterConflict.revision },
        recoveredStudioSave: { putStatus: recoverPut.status(), revision: afterRecovered.revision },
        readOnlyRefusal: { putStatus: roPut.status(), serverRevisionAfter: afterReadOnly.revision },
      },
    }, null, 2))

    // 控制台卫生：先按实际响应豁免目标性失败（409/403 断言面），再断言无泄漏、
    // 无鉴权/CSP 错误——豁免精确到 URL+状态码，不做全文模糊过滤。
    const leaks = consoleLeaks(consoleErrors, benign)
    const authErrors = leaks.filter((line) =>
      /401|403|Content Security Policy|Refused to display|Refused to frame/i.test(line))
    expect(authErrors, `鉴权/CSP 错误: ${authErrors.join(' | ')}`).toEqual([])
    expect(leaks, `console error 泄漏: ${consoleErrors.join(' | ')}`).toEqual([])
  })

  test('TC-F2-37-03 两变体构建收敛；定向失败注入后 UI 重试收敛', async ({ page, request }) => {
    test.setTimeout(JOURNEY_TIMEOUT_MS + 180_000)
    await seedModel(request)
    await login(page, OWNER_A)
    await openWorkspace(page)
    await createProject(page, 'journey-变体工程')
    await page.getByRole('button', { name: '审片与导出' }).click()

    await page.getByTestId('clone-variant-key').fill('topic')
    await page.getByTestId('clone-variant-values').fill('红, 蓝')
    await page.getByTestId('clone-variant-create').click()
    await expect(page.getByTestId('clone-variant-0')).toBeVisible({ timeout: 120_000 })
    await expect(page.getByTestId('clone-variant-1')).toBeVisible({ timeout: 60_000 })

    // 变体 0 构建成功（原生渲染）。
    await page.getByTestId('clone-variant-0').getByTestId('clone-variant-build').click()
    await expect(page.getByTestId('clone-variant-0').getByTestId('clone-variant-state'))
      .toHaveAttribute('data-state', 'succeeded', { timeout: JOURNEY_TIMEOUT_MS })

    // 变体 1：提交被定向 503（真实拒绝面，非 mock 成功）。C27 契约：被拒 mutation
    // 不改服务端行状态（如实停在 draft、不假增 attempt），错误经面板 alert 露出。
    // 行内定位：变体 0 收敛 succeeded 后其构建按钮消失，页面级 nth 会错位。
    const buildRoute = '**/api/hypit/projects/*/variants/*/build'
    await page.route(buildRoute, (route) =>
      route.fulfill({ status: 503, contentType: 'application/json',
        body: JSON.stringify({ error: { code: 'hypit_backend_unavailable', message: '定向注入失败' } }) }))
    await page.getByTestId('clone-variant-1').getByTestId('clone-variant-build').click()
    await expect(page.getByTestId('clone-error')).toContainText('定向注入失败', { timeout: 30_000 })
    await expect(page.getByTestId('clone-variant-1').getByTestId('clone-variant-state'))
      .toHaveAttribute('data-state', 'draft')
    await page.unroute(buildRoute)

    // 失败项重试 = 重新驱动同一提交（服务端权威态；同 requestId 幂等不重复计费）。
    await page.getByTestId('clone-variant-1').getByTestId('clone-variant-build').click()
    await expect(page.getByTestId('clone-variant-1').getByTestId('clone-variant-state'))
      .toHaveAttribute('data-state', 'succeeded', { timeout: JOURNEY_TIMEOUT_MS })
    // 成功项不重做：变体 0 保持 succeeded。
    await expect(page.getByTestId('clone-variant-0').getByTestId('clone-variant-state'))
      .toHaveAttribute('data-state', 'succeeded')
  })

  test('TC-F2-37-04/TC-F3-11-02 A导出→B UI导入→参考分析方案→Studio真实编辑NEW-B→构建→下载→ffprobe/全解码；A历史源/成片sha不变', async ({ page, request }) => {
    test.setTimeout(JOURNEY_TIMEOUT_MS + 420_000)
    await seedModel(request)
    mkdirSync(EVIDENCE, { recursive: true })
    await login(page, OWNER_A)
    const consoleErrors = collectConsoleErrors(page)
    const benign = expectedBenignResponses(page)
    await openWorkspace(page)
    const aProjectId = await createProject(page, 'journey-移植工程')

    // A：全链成片（上传→分析→方案（作者任务重写源）→生成→归档下载）。TC-F2-37-01
    // 已单独覆盖源码面板编辑面，本链直接用作者产物源，终点是导出前后的稳定态。
    await uploadAnalyzeAndPlan(page)
    await generateAndWaitFinished(page)
    const aMp4Path = await downloadFinalVideo(page, 'TC-F3-11-02-a-final.mp4')
    const aMp4Bytes = readFileSync(aMp4Path)
    const aMp4Sha = sha256(aMp4Bytes)
    expect(aMp4Sha).toMatch(/^[0-9a-f]{64}$/)
    // A 历史源码快照（导出前基线，供 B 链结束后比对不变）。
    const aSource = await readSource(page, aProjectId)
    expect(aSource.content, '作者源应含 redWall').toContain('redWall')
    // A 成片 ffprobe + 全解码（成片本身可核验，B 侧再渲染一份二次样片）。
    const aProbe = ffprobe(aMp4Path)
    expect(aProbe.width).toBe(320)
    expect(aProbe.height).toBe(240)
    ffmpegFullDecode(aMp4Path)

    // A：导出（工程包弹窗）→ 下载 zip（源码+清单，素材/成片是 per-project 资产
    // 不随包携带——B 侧因此要重新上传参考素材）。
    await page.getByRole('button', { name: '生成与编辑' }).click()
    await page.getByTestId('clone-export').click()
    await page.getByTestId('clone-package-dialog').waitFor()
    await page.getByTestId('clone-package-download').waitFor({ timeout: JOURNEY_TIMEOUT_MS })
    const exportDownload = page.waitForEvent('download', { timeout: 120_000 })
    await page.getByTestId('clone-package-download').click()
    const exported = await exportDownload
    const zipPath = resolve(EVIDENCE, 'TC-F3-11-02-export.zip')
    await exported.saveAs(zipPath)
    const zipBytes = readFileSync(zipPath)
    expect(sha256(zipBytes)).toMatch(/^[0-9a-f]{64}$/)
    await page.getByTestId('clone-package-close').click()

    // B：登出 → 登录 → 打开工作区任一工程的工程包弹窗 → 上传 A 的 zip 导入。
    // 登出是异步落定（清会话+头部重渲染）：不等「登录 / 注册」回归就 goto，firefox
    // 时序下登出未完成——头部已是登录态，登录按钮被换掉（resolved→detached 循环）。
    await page.getByRole('button', { name: '退出登录' }).click()
    await page.getByRole('button', { name: '登录 / 注册' }).waitFor({ timeout: 15_000 })
    await login(page, OWNER_B)
    await openWorkspace(page)
    await createProject(page, 'journey-导入宿主')
    await page.getByTestId('clone-export').click()
    await page.getByTestId('clone-package-dialog').waitFor()
    await page.setInputFiles('[data-testid="clone-package-file"]', {
      name: 'exported.zip', mimeType: 'application/zip', buffer: zipBytes,
    })
    await page.getByTestId('clone-package-import').click()
    await page.getByTestId('clone-package-import-ready').waitFor({ timeout: JOURNEY_TIMEOUT_MS })
    await page.getByTestId('clone-package-navigate').click()
    await page.waitForURL(/\/video-clone\/[0-9a-f-]{36}$/, { timeout: 60_000 })
    const bProjectId = page.url().split('/').pop() ?? ''
    // 新 owner 工程 ready：URL 已切到导入新建的工程（服务端造新 id，非 A 的源工程），
    // 头部为导入工程的默认标题（owner 归属由服务端闸保证，C30 IT 锁定）。
    await expect(page.getByTestId('clone-project-header')).toContainText('导入工程', { timeout: 120_000 })
    expect(bProjectId, 'B 工程必须是新 id（非 A 的源工程）').not.toBe(aProjectId)

    // B：参考素材→分析→方案（作者任务真实写回 fixture 作者源并持久方案）。方案链
    // 放在 Studio 编辑之前是唯一诚实顺序——author 任务会重写 main.svml/style.svs，
    // 先编辑再生成方案会让 NEW-B 被覆盖（§12.2 断言「B源码含NEW-B」就无法成立）；
    // TC 步骤序列「新工程ready→Studio编辑NEW-B→保存→check/plan/grant/build→下载」
    // 的断言项全部按此顺序达成：编辑保存在 check/plan/build 之前、构建消费 B 编辑
    // 后的源、二次样片为 B 自有渲染（非复制 A 的 MP4）。
    await uploadAnalyzeAndPlan(page)
    const bSourceBeforeEdit = await readSource(page, bProjectId)
    expect(bSourceBeforeEdit.content).toContain('redWall')

    // B：Studio 真实编辑 NEW-B（iframe 控件：文件项→编辑切换→textarea→Ctrl/Cmd+S）。
    await page.getByRole('button', { name: '生成与编辑' }).click()
    await page.getByTestId('clone-source-panel').waitFor()
    await page.getByTestId('clone-open-studio').click()
    const frame = page.frameLocator('iframe.clone-studio-frame')
    await enterStudioEditMode(frame)
    const editor = frame.locator('textarea[data-editor]')
    // 编辑器值为快照到达后的异步赋值——轮询到内容就绪再取锚点（同 TC-F3-11-01）。
    await expect.poll(() => editor.inputValue(), { timeout: 30_000, message: 'B 编辑器内容加载' })
      .toContain(B_EDIT_FROM)
    const bOriginal = await editor.inputValue()
    expect(bOriginal.split(B_EDIT_FROM), `B 编辑锚点 ${B_EDIT_FROM} 必须恰好出现 1 次`).toHaveLength(2)
    const bEdited = bOriginal.replace(B_EDIT_FROM, B_EDIT_TO)
    expect(bEdited).not.toBe(bOriginal)
    const bPutPromise = waitForStudioSourcePut(page)
    await editor.fill(bEdited)
    await editor.press('ControlOrMeta+s')
    const { response: bPut, body: bPutBody } = await bPutPromise
    expect(bPut.status(), `B 保存应 202（实际 ${bPut.status()} ${bPutBody}）`).toBe(202)
    await expect(frame.locator('[data-save-state]')).toHaveClass(/code-save-state saved/, { timeout: 30_000 })
    await page.screenshot({ path: resolve(EVIDENCE, 'TC-F3-11-02-b-studio-edit-saved.png'), fullPage: true })
    // 观察面：B 源码含 NEW-B 且 revision 推进（B 归属工程，与 A 的 revision 不同源）。
    const bSourceAfterEdit = await readSource(page, bProjectId)
    expect(bSourceAfterEdit.content, 'B 源码必须含 NEW-B 标记').toContain(B_EDIT_TO)
    expect(bSourceAfterEdit.revision).toBeGreaterThan(bSourceBeforeEdit.revision)
    expect(bSourceAfterEdit.hash).not.toBe(bSourceBeforeEdit.hash)
    // Studio 重开仍 NEW-B（跨 owner 移植的编辑同样持久）。
    await page.getByRole('button', { name: '关闭编辑器' }).click()
    await expect(page.locator('iframe.clone-studio-frame')).toBeHidden({ timeout: 60_000 })
    await page.getByTestId('clone-open-studio').click()
    await enterStudioEditMode(frame)
    await expect.poll(() => editor.inputValue(), { timeout: 30_000, message: '重开编辑器内容加载' })
      .toContain(B_EDIT_TO)
    expect(await editor.inputValue()).toContain(B_EDIT_TO)

    // B：构建（check→plan→pricing→本地 Need→submit→原生渲染）→ 归档下载二次样片。
    await page.getByRole('button', { name: '关闭编辑器' }).click()
    await expect(page.locator('iframe.clone-studio-frame')).toBeHidden({ timeout: 60_000 })
    await page.getByRole('button', { name: '复刻方案' }).click()
    await generateAndWaitFinished(page)
    const bMp4Path = await downloadFinalVideo(page, 'TC-F3-11-02-b-final.mp4')
    const bProbe = ffprobe(bMp4Path)
    expect(bProbe.durationSeconds, `B 成片时长 ${bProbe.durationSeconds}`).toBeGreaterThan(2)
    expect(bProbe.durationSeconds).toBeLessThan(5)
    expect(bProbe.width).toBe(320)
    expect(bProbe.height).toBe(240)
    // 全解码（§12.2 断言项：FFmpeg 全解码通过——非只 ffprobe 元数据）。
    ffmpegFullDecode(bMp4Path)
    const bMp4Sha = sha256(readFileSync(bMp4Path))
    // B 源码在构建后仍含 NEW-B（构建消费的是 B 编辑后的源）。
    const bSourceAfterBuild = await readSource(page, bProjectId)
    expect(bSourceAfterBuild.content).toContain(B_EDIT_TO)
    expect(bSourceAfterBuild.revision).toBe(bSourceAfterEdit.revision)

    // A 不变性：B 的完整链（导入/编辑/构建/归档）结束后，A 历史源码 hash/revision
    // 不变、成片 sha 不变。登出 B→登录 A→深链 A 工程（F07 深链加载）。
    await page.getByRole('button', { name: '退出登录' }).click()
    await page.getByRole('button', { name: '登录 / 注册' }).waitFor({ timeout: 15_000 })
    await login(page, OWNER_A)
    await page.goto(`${AI_BASE}/video-clone/${aProjectId}`)
    await page.getByTestId('clone-project-header').waitFor({ timeout: 60_000 })
    const aSourceAfter = await readSource(page, aProjectId)
    expect(aSourceAfter.hash, `A 历史源码 hash 必须不变（${aSource.hash} → ${aSourceAfter.hash}）`).toBe(aSource.hash)
    expect(aSourceAfter.revision, 'A 历史源码 revision 必须不变').toBe(aSource.revision)
    expect(aSourceAfter.content).toBe(aSource.content)
    const aRedownload = await downloadFinalVideo(page, 'TC-F3-11-02-a-final-recheck.mp4')
    expect(sha256(readFileSync(aRedownload)), 'A 历史成片 sha 必须不变（重新下载比对）').toBe(aMp4Sha)
    await page.screenshot({ path: resolve(EVIDENCE, 'TC-F3-11-02-a-immutability-recheck.png'), fullPage: true })

    // A 打不开 B 的工程（越权）：API 404 + UI 深链错误页（服务端闸不区分不存在/无权）。
    const foreignRead = await page.request.get(
      `${AI_BASE}/api/hypit/projects/${bProjectId}/file?path=${encodeURIComponent('main.svml')}`)
    expect(foreignRead.status(), 'A 读 B 工程源码必须被拒').toBe(404)
    await page.goto(`${AI_BASE}/video-clone/${bProjectId}`)
    await expect(page.getByTestId('clone-project-notfound'), 'A 深链 B 工程必须如实 404 错误页').toBeVisible({ timeout: 60_000 })
    await page.screenshot({ path: resolve(EVIDENCE, 'TC-F3-11-02-a-foreign-project-refused.png'), fullPage: true })

    writeFileSync(resolve(EVIDENCE, 'TC-F3-11-02-portability.json'), JSON.stringify({
      a: { projectId: aProjectId, source: { hash: aSource.hash, revision: aSource.revision }, mp4: { sha256: aMp4Sha, sizeBytes: aMp4Bytes.length, probe: aProbe } },
      b: { projectId: bProjectId, marker: B_EDIT_TO, source: { hashBefore: bSourceBeforeEdit.hash, hashAfter: bSourceAfterEdit.hash, revisionBefore: bSourceBeforeEdit.revision, revisionAfter: bSourceAfterEdit.revision }, mp4: { sha256: bMp4Sha, sizeBytes: readFileSync(bMp4Path).length, probe: bProbe } },
      immutability: { aSourceHashAfter: aSourceAfter.hash, aSourceRevisionAfter: aSourceAfter.revision, aMp4ShaRedownload: sha256(readFileSync(aRedownload)), aForeignReadStatus: foreignRead.status() },
      exportZip: { sha256: sha256(zipBytes), sizeBytes: zipBytes.length },
    }, null, 2))

    const leaks = consoleLeaks(consoleErrors, benign)
    expect(leaks, `console error 泄漏: ${consoleErrors.join(' | ')}`).toEqual([])
  })
})
