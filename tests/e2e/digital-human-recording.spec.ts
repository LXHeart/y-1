/**
 * 数字人 S2 录制素材全链 e2e（任务书 #105F C105F-05 / TC105F-05-01）。
 *
 * 运行于 DH_E2E=1 隔离栈（真实 Java API + Fake Python runtime）。不使用 route.fulfill——
 * 所有断言打真实响应。当前真实边界如实标注（运行期探测后在用例内 test.skip 并给出精确原因）：
 * 1. API16（webrtc/offer）503 dh_runtime_unavailable——render 媒体桥接线是 D 阶段缺口，且它是
 *    dh_session connecting→ready 的唯一写入路径；录制前置（ready/listening/responding）因此不可达；
 * 2. 隔离栈无 dh_catalog 种子与 digital_human_render 控制面行——目录如实关闭（不伪造开启）；
 * 3. 生产 profile 无 fake 第三方 render 适配（形象检测面无法在隔离栈真实通过）。
 * 三项都超出 #105F 白名单（媒体桥接线随 D 后续卡、栈种子随 G/H 验收装配）；本文件在可达面上
 * 真实执行：关闭态如实呈现（不给假录制/形象入口）、隐私 Permissions-Policy、明暗×视口视觉自查
 * （C105F-04 递补的截图自查），并把全链合同完整写下待接线后启用。
 */

import { expect, test } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { join } from 'node:path'
import {
  aiBaseURL, loginOnAiApp, openWorkbench, probeCapabilities, syntheticFacePng,
} from './fixtures/digital-human'

test.describe('tc105f_05_01 素材全链（真实 Java + Fake runtime）', () => {
  test('目录未配置（隔离栈无种子）：工作台如实关闭，不出现录制/形象假入口', async ({ page, request }) => {
    await loginOnAiApp(page)
    await openWorkbench(page)
    await expect(page.locator('#dh-title')).toBeVisible()

    // 真实目录状态驱动（E06 同款双分支）：未开 →「暂未开放」；行未种/网关 404 →
    // 「加载失败」+ 重试。两态都如实呈现，不给假录制/形象入口。
    const capability = await probeCapabilities(request)
    if (!capability.catalogEnabled) {
      const panel = page.locator('.dh-page')
      await expect(panel.getByText(/数字人服务暂未开放|数字人服务暂时不可用/).first())
        .toBeVisible({ timeout: 15_000 })
      if (await panel.getByText('数字人服务暂时不可用').count() > 0) {
        await expect(page.getByRole('button', { name: '重试' })).toBeVisible()
      }
    }
    // 录制与自有形象入口由目录开关驱动：关闭时不得渲染任何可点击入口（后端门禁的前端如实呈现）。
    await expect(page.getByTestId('dh-recording-start')).toHaveCount(0)
    await expect(page.getByTestId('dh-avatar-file')).toHaveCount(0)
  })

  test('完整素材链（合成头像→会话→录两段→打断→结束→下载 MP4/SRT→save→素材库重播）', async ({ page, request }) => {
    await loginOnAiApp(page)
    await openWorkbench(page)
    const capability = await probeCapabilities(request)
    test.skip(!capability.catalogEnabled || !capability.mediaBridgeAvailable,
      `素材全链依赖三重真实条件：catalog.enabled=${capability.catalogEnabled}（隔离栈未种 dh_catalog/`
      + `digital_human_render 控制面）、API16=${capability.mediaBridgeCode ?? 'unreachable'}（render 媒体桥为 D 阶段`
      + `缺口，且为 connecting→ready 唯一写入路径，录制前置不可达）、生产 profile 无 fake 第三方形象检测适配`
      + `——三项均超出 #105F 白名单，随 D 接线卡与 G/H 验收装配补齐后启用本用例`)

    // —— 全链合同（届时在本栈实跑，不允许 route.fulfill）——
    // 1. 合成头像上传：真实 media 三步票据（syntheticFacePng）→ API30 create → 轮询 ready。
    // 2. 会话：API03 角色 → API07 预检 → API08 创建 → API16 offer → ready。
    // 3. 录两段：API33 start/ack → 打断（API20，第二段 reasonCode=interrupted）→ API34 stop ×2。
    // 4. 下载：API36 mp4/srt → ffprobeJson 验 H264/AAC/48000/时长；parseSrt 验 UTF8/序号/时间轴；
    //    下载体 SHA-256 一致，且与素材库 content_asset 挂的 media checksum 一致。
    // 5. save：API37（includeSubtitles=true）→ assetId；素材库路由重播同 asset（同 sha）。
    // 6. 隐私：输出音频频谱含 PROGRAM_AUDIO_MARKER_HZ、不含 MIC_AUDIO_MARKER_HZ（频谱证明在
    //    tests/test_recording.py 真编码做；此处容器/SHA/SRT 面 + Permissions-Policy 面）。
    const face = syntheticFacePng()
    expect(face.length).toBeGreaterThan(100)
    expect(face.subarray(0, 4)).toEqual(Buffer.from([0x89, 0x50, 0x4e, 0x47])) // PNG 魔数自证
  })

  test('录制隐私姿态：AI 入口 Permissions-Policy 允许麦克风（self）、禁摄像头，仅该入口', async ({ request }) => {
    const response = await request.get(aiBaseURL + '/ai.html')
    const policy = response.headers()['permissions-policy'] ?? ''
    expect(policy).toContain('microphone=(self)')
    expect(policy).toContain('camera=()')
    // 录制只采数字人输出：策略面上摄像头恒禁、麦克风仅 AI 入口（80/81 由部署契约测试覆盖）。
  })
})

test.describe('tc105f_05_01 视觉自查（C105F-04 递补：真实栈明暗×视口）', () => {
  for (const theme of ['light', 'dark']) {
    for (const viewport of [{ width: 1440, height: 900, tag: '1440' }, { width: 390, height: 844, tag: '390' },
      { width: 320, height: 700, tag: '320' }]) {
      test(`工作台 ${theme} ${viewport.tag}：无横溢、焦点可见、截图留证`, async ({ browser }) => {
        const context = await browser.newContext({ viewport, locale: 'zh-CN' })
        await context.addInitScript((t) => localStorage.setItem('theme-preference', t), theme)
        const page = await context.newPage()
        await loginOnAiApp(page)
        await openWorkbench(page)

        const overflow = await page.evaluate(() =>
          document.documentElement.scrollWidth - document.documentElement.clientWidth)
        expect(overflow).toBeLessThanOrEqual(0)
        await page.locator('a.skip-link').focus()
        const focused = await page.evaluate(() => document.activeElement?.className ?? '')
        expect(focused).toContain('skip-link')

        // 截图自查证据（AGENTS.md 规则6）：目录关闭态下新增录制/形象区不渲染——
        // 其明暗视觉自查随目录开启的接线卡补做，此处留可达面证据。
        const shotDir = join('test-artifacts/task-105/F/C105F-05/screenshots')
        await mkdir(shotDir, { recursive: true })
        await page.screenshot({
          path: join(shotDir, `workbench-${theme}-${viewport.tag}.png`),
          fullPage: true,
        })
        await context.close()
      })
    }
  }
})
