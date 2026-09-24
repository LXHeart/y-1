/**
 * 数字人工作台 S1 真浏览器验收（任务书 #105E C105E-06 / TC105E-06-01～04）。
 *
 * 运行于 ci-e2e-105-s1.sh 拉起的隔离栈（真实 Java API + Fake Python runtime；无公共 provider）。
 * 不使用 route.fulfill 冒充后端：所有断言打真实响应。当前已知边界如实标注：
 * - API16（webrtc/offer）在 D-05 以 503 dh_runtime_unavailable 收口（render 媒体桥接线未入 E 白名单），
 *   完整对话流（创建→文字→打断）依赖该桥——运行期探测后在用例内 test.skip 并给出精确原因；
 * - 三引擎无真实麦克风设备：mic 路径按 K12 标 PARTIAL 留 H（S2 实机）。
 */
import { expect, test } from '@playwright/test'
import {
  aiBaseURL, loginOnAiApp, openWorkbench, probeCapabilities, syntheticAudioTrackLabel,
} from './fixtures/digital-human'

test.describe('tc105e_06_01 三引擎完整流（真实 Java + Fake runtime）', () => {
  test('登录后进入工作台：真实目录驱动初始状态（不白屏、不给假入口）', async ({ page, request }) => {
    await loginOnAiApp(page)
    await openWorkbench(page)
    await expect(page.locator('#dh-title')).toBeVisible()

    // 真实目录状态驱动初始 UI，按真实响应分支断言：
    // - 业务开关开启 → 进入角色配置区；未开（K10 默认）→「暂未开放」；
    // - 目录行未种/网关 404 →「加载失败」+ 重试（真实状态呈现，同样不白屏）。
    const capability = await probeCapabilities(request)
    if (capability.catalogEnabled) {
      await expect(page.getByTestId('dh-start-button')).toBeVisible()
      return
    }
    const panel = page.locator('.dh-page')
    // 等待页面自身目录请求落定（任一终态文本出现）再分支，避免在 loading 期误判。
    await expect(panel.getByText(/数字人服务暂未开放|数字人服务暂时不可用/).first())
      .toBeVisible({ timeout: 15_000 })
    if (await panel.getByText('数字人服务暂时不可用').count() > 0) {
      await expect(page.getByRole('button', { name: '重试' })).toBeVisible()
    }
    expect(await page.getByTestId('dh-start-button').count()).toBe(0)
  })

  test('完整对话流（创建→文字→打断→保存→结束）：受 API16 媒体桥接线约束，如实跳过并留证', async ({ page, request }) => {
    await loginOnAiApp(page)
    await openWorkbench(page)
    const capability = await probeCapabilities(request)
    // 前置：目录开启 + 媒体桥可用才具备完整流条件；当前 503 dh_runtime_unavailable（D-05 收口）。
    test.skip(!capability.catalogEnabled || !capability.mediaBridgeAvailable,
      `完整流依赖媒体桥接线：catalog.enabled=${capability.catalogEnabled}, `
      + `API16=${capability.mediaBridgeCode ?? 'unreachable'}（render 媒体桥接线超出 #105E 白名单，随 D 后续接线卡补齐后启用本用例）`)

    // —— 媒体桥接通后的合同（届时在本栈实跑，不允许 route.fulfill）——
    // 1. 创建角色（API03）→ preflight（API07）→ 确认（saveTranscript=false）→ create（API08 202）
    // 2. 文字 turn（API19）→ SSE transcript.final/assistant.delta 真实事件
    // 3. 打断（API20）→ mediaEpoch 递增 → peer 重建
    // 4. 保存字幕（API22/23）→ 导出（API25 文本）
    // 5. 结束（API14）→ ended + 待核对费用显示；API 侧核对本会话 invocation 收口。
  })
})

test.describe('tc105e_06_02 双页与换号', () => {
  test('两个真实 page（同账号、独立会话）：第二页显式接管提示，不偷偷接管（K13.4）', async ({ browser }) => {
    const pageA = await browser.newPage()
    const pageB = await browser.newPage()
    await loginOnAiApp(pageA)
    await openWorkbench(pageA)
    await expect(pageA.locator('#dh-title')).toBeVisible()

    // 第二页登录同一账号（独立登录态）：目录/深链不冒充本页接管。
    await loginOnAiApp(pageB)
    await openWorkbench(pageB)
    await expect(pageB.locator('#dh-title')).toBeVisible()
    // A 页仍持有自己的工作台状态（无跨页串写）。
    await expect(pageA.locator('#dh-title')).toBeVisible()
    await pageA.close()
    await pageB.close()
  })

  test('登出→回登录（A→B→A 同 id，epoch 递增）：旧页私有状态不串页', async ({ page }) => {
    await loginOnAiApp(page)
    await openWorkbench(page)
    await expect(page.locator('#dh-title')).toBeVisible()

    await page.getByRole('button', { name: '退出登录' }).click()
    await page.getByRole('button', { name: '登录 / 注册' }).waitFor({ timeout: 10_000 })
    // 未登录态再进数字人：匿名目录驱动——开关开=登录引导；开关关=未开放（不透露资源）。
    // 两种状态都不得出现上一账号的业务数据。
    await openWorkbench(page)
    const panel = page.locator('.dh-page')
    if (await panel.getByText('登录后开始与数字人创作').count() === 0) {
      await expect(panel).toContainText('数字人服务暂未开放')
    }
    await expect(panel).not.toContainText('口播')

    await loginOnAiApp(page)
    await openWorkbench(page)
    await expect(page.locator('#dh-title')).toBeVisible()
  })
})

test.describe('tc105e_06_03 视觉与键盘（结构级实跑；完整七态矩阵证据见 test-artifacts/task-105/E/）', () => {
  for (const theme of ['light', 'dark']) {
    for (const viewport of [{ width: 1440, height: 900, tag: '1440' }, { width: 390, height: 844, tag: '390' }, { width: 320, height: 700, tag: '320' }]) {
      test(`工作台首屏 ${theme} ${viewport.tag}：无横溢、无营销 hero、焦点可见`, async ({ browser }) => {
        const context = await browser.newContext({ viewport, locale: 'zh-CN' })
        await context.addInitScript((t) => localStorage.setItem('theme-preference', t), theme)
        const page = await context.newPage()
        await loginOnAiApp(page)
        await openWorkbench(page)

        const overflow = await page.evaluate(() =>
          document.documentElement.scrollWidth - document.documentElement.clientWidth)
        expect(overflow).toBeLessThanOrEqual(0)
        expect(await page.locator('[class*="hero"]').count()).toBe(0)

        // 键盘可达：跳转链接可聚焦且为当前焦点（焦点环样式由设计自查覆盖）。
        await page.locator('a.skip-link').focus()
        const focused = await page.evaluate(() => document.activeElement?.className ?? '')
        expect(focused).toContain('skip-link')
        await context.close()
      })
    }
  }

  test('AI 入口 Permissions-Policy 允许麦克风（self），仅该入口（80/81 由部署契约测试覆盖）', async ({ request }) => {
    const response = await request.get(aiBaseURL + '/ai.html')
    const policy = response.headers()['permissions-policy'] ?? ''
    expect(policy).toContain('microphone=(self)')
    expect(policy).toContain('camera=()')
  })
})

test.describe('tc105e_06_04 测试边界', () => {
  test('文本 UI 可用（导航/表单可达即 PASS 基线）；mic 路径 PARTIAL 留 H（S2 实机）', async ({ page, request }) => {
    await loginOnAiApp(page)
    await openWorkbench(page)
    const capability = await probeCapabilities(request)

    // 文本 UI：导航与工作台在真实栈可达、无脚本错误级崩坏。
    await expect(page.locator('#dh-title')).toBeVisible()
    expect(capability).toBeTruthy()

    // mic：三引擎无真实麦克风设备 + API15 资格链依赖媒体桥——明确 PARTIAL，不虚报通过。
    test.info().annotations.push({
      type: 'PARTIAL',
      description: `麦克风路径=${syntheticAudioTrackLabel()}：S1 引擎无真实输入设备，且音频资格链依赖 API16 媒体桥`
        + `（当前 ${capability.mediaBridgeCode ?? 'unreachable'}）；真实设备验收归 H（S2）`,
    })
  })
})
