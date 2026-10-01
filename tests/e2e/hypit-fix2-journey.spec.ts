// hypit-fix2-journey.spec.ts — 107-fix-2 C107F2-37（§12.5 G2–G5）：真实浏览器贯通
// 参考素材到成片、Studio 与工程包。
//
// 真实链路承诺（步骤 1/2）：全部被验收动作经真实 UI 按钮触发（HTTP 走真实
// Nginx/Edge/Java/broker/runner）；只有外部商业模型被替换为受控文本 fixture
// （治理台正式凭据/路由/计费不变，见 fixtures/hypit-fix2.ts seedFix2TextModel）；
// 原生编译、渲染、PG、文件、浏览器零 mock。
//
// TC-F2-37-01（G2） A 上传 12s 参考素材 → UI 分析 → 方案 → 源码编辑(validated) →
//                    生成 → 归档 → 下载 MP4 → ffprobe 时长/尺寸/可解码。
// TC-F2-37-02（G3） Studio 打开（iframe 真实加载，无鉴权/CSP console 错误）→
//                    关闭 → 重开。
// TC-F2-37-03（G4） 两变体批量构建收敛；变体定向失败注入后 UI 重试收敛（成功项不重做）。
// TC-F2-37-04（G5） A 导出 zip 下载；B 登录 UI 导入 → 新 owner 工程 ready。
import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

import {
  EVIDENCE, JOURNEY_TIMEOUT_MS, OWNER_A, OWNER_B,
  createProject, ffprobe, login, makeReferenceMp4, openWorkspace, seedFix2TextModel,
} from './fixtures/hypit-fix2'

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
 * 动线，测试侧已按该动线重存一次。
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
  })
  return expected
}

/** 把「Failed to load resource: … 404/409」行与豁免清单比对后剩下的真泄漏。 */
function consoleLeaks(consoleErrors: string[], benign: { status: number }[]): string[] {
  let rest = consoleErrors.filter((line) => !/favicon|DevTools/i.test(line))
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
    const reference = makeReferenceMp4()
    await page.setInputFiles('[data-testid="clone-upload-input"]', {
      name: 'reference-12s.mp4', mimeType: 'video/mp4', buffer: readFileSync(reference),
    })
    await page.getByTestId('clone-upload-submit').click()
    await page.getByTestId('clone-analyze').first().waitFor({ timeout: 180_000 })

    // ② 全片分析（intent=analyze：真实 probe/frames + 受控综合模型）。分析接受后
    // UI 切到生成与编辑步骤；分析完成前方案面板是 waiting——轮询 重新方案 直到
    // 生成按钮可用（分析+方案两条 agent job 串联的真实收敛）。
    await page.getByTestId('clone-analyze').first().click()
    await page.getByTestId('clone-material-panel').waitFor({ timeout: 60_000 })
    await page.getByRole('button', { name: '复刻方案' }).click()
    const regenerate = page.getByTestId('clone-plan-regenerate')
    // 分析 agent job（broker probe/frames + fixture 模型）在本机满栈并跑下不止 60s
    // （V-09 chromium 实录 60s 仍 busy 判负）；与下方收敛循环的 JOURNEY 预算对齐
    // 到 180s——busy 清零即真收敛，非放宽断言本身。
    await expect(regenerate).toBeEnabled({ timeout: 180_000 })
    // 分析终态后再点方案（避免在分析未完成时 409 抢占——面板会如实 waiting）。
    for (let attempt = 0; attempt < 30; attempt += 1) {
      const waitingVisible = await page.getByTestId('clone-job-waiting').isVisible().catch(() => false)
      const emptyVisible = await page.getByTestId('clone-plan-empty').isVisible().catch(() => false)
      if (waitingVisible || emptyVisible) {
        await regenerate.click()
        await page.waitForTimeout(20_000)
      }
      if (await page.getByTestId('clone-generate').isEnabled().catch(() => false)) break
    }
    await expect(page.getByTestId('clone-generate')).toBeEnabled({ timeout: JOURNEY_TIMEOUT_MS })
    await expect(page.getByTestId('clone-plan-gaps')).toBeHidden()

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
    await page.getByTestId('clone-generate').click()
    // 面板行渲染 lifecycleLabels：finished → 「已完成」；outcome 原样 complete。
    await expect(page.getByTestId('clone-material-panel').getByText(/已完成/).first())
      .toBeVisible({ timeout: JOURNEY_TIMEOUT_MS })
    await expect(page.getByTestId('clone-material-panel').getByText(/complete/i).first())
      .toBeVisible({ timeout: 180_000 })

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

  test('TC-F2-37-02 Studio 打开/重开：iframe 真实加载，无鉴权/CSP console 错误', async ({ page, request }) => {
    test.setTimeout(JOURNEY_TIMEOUT_MS)
    await seedModel(request)
    await login(page, OWNER_A)
    // 同 TC-01：登出态 boot 鉴权探测的 401 是预期行为，收集从登录后开始。
    const consoleErrors = collectConsoleErrors(page)
    await openWorkspace(page)
    await createProject(page, 'journey-studio 工程')
    await page.getByRole('button', { name: '生成与编辑' }).click()

    await page.getByTestId('clone-open-studio').click()
    // Studio iframe 真实加载（Companion 经 studio 代理 + 一次性票据）。
    const frame = page.frameLocator('iframe.clone-studio-frame')
    await frame.locator('body').waitFor({ timeout: 180_000 })
    const authErrors = consoleErrors.filter((line) =>
      /401|403|Content Security Policy|Refused to display|Refused to frame/i.test(line))
    expect(authErrors, `鉴权/CSP 错误: ${authErrors.join(' | ')}`).toEqual([])

    // 关闭 → 重开（一次性票据过期语义：重开走新会话，仍可加载）。关闭后
    // StudioPanel 整体卸载（session/loading/error 全空），重开入口回到工程头部
    // 的「专业编辑器」按钮（clone-open-studio），面板内「打开编辑器」只在
    // loading/error 残留时短暂存在。
    await page.getByRole('button', { name: '关闭编辑器' }).click()
    await expect(page.locator('iframe.clone-studio-frame')).toBeHidden({ timeout: 60_000 })
    await page.getByTestId('clone-open-studio').click()
    await frame.locator('body').waitFor({ timeout: 180_000 })
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

  test('TC-F2-37-04 A 导出 zip → B UI 导入 → 新 owner 工程 ready', async ({ page, request }) => {
    test.setTimeout(JOURNEY_TIMEOUT_MS + 120_000)
    await seedModel(request)
    await login(page, OWNER_A)
    await openWorkspace(page)
    await createProject(page, 'journey-移植工程')

    // A：导出（工程包弹窗）→ 下载 zip。
    await page.getByTestId('clone-export').click()
    await page.getByTestId('clone-package-dialog').waitFor()
    await page.getByTestId('clone-package-download').waitFor({ timeout: JOURNEY_TIMEOUT_MS })
    const exportDownload = page.waitForEvent('download', { timeout: 120_000 })
    await page.getByTestId('clone-package-download').click()
    const exported = await exportDownload
    const zipPath = resolve(EVIDENCE, 'TC-F2-37-04-export.zip')
    await exported.saveAs(zipPath)
    const zipBytes = readFileSync(zipPath)
    const zipSha = createHash('sha256').update(zipBytes).digest('hex')
    expect(zipSha).toMatch(/^[0-9a-f]{64}$/)
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
    // 新 owner 工程 ready：URL 已切到导入新建的工程（服务端造新 id，非 A 的源工程），
    // 头部为导入工程的默认标题（owner 归属由服务端闸保证，C30 IT 锁定）。
    await expect(page.getByTestId('clone-project-header')).toContainText('导入工程', { timeout: 120_000 })
  })
})
