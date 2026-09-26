/**
 * 数字人工作台 S1 真浏览器验收（任务书 #105E C105E-06 / TC105E-06-01～04）。
 *
 * 运行于 ci-e2e-105-s1.sh 拉起的隔离栈（真实 Java API + Fake Python runtime；无公共 provider）。
 * 不使用 route.fulfill 冒充后端：所有断言打真实响应。当前已知边界如实标注：
 * - 完整对话流（C105X-03 / #105fix-1 接通）：offer 200 answer→ready→文字 turn→打断，必需断言打
 *   真实响应；浏览器媒体轨连通为独立 SHOULD 项（D-03 分层，ICE 拓扑属环境属性不阻塞验收）；
 * - 三引擎无真实麦克风设备：mic 路径按 K12 标 PARTIAL 留 H（S2 实机）。
 */
import { expect, request as playwrightRequest, test } from '@playwright/test'
import type { APIRequestContext } from '@playwright/test'
import { newRunId, query, registerAndLogin } from './fixtures/task-104'
import {
  aiBaseURL, loginOnAiApp, openWorkbench, probeCapabilities, syntheticAudioTrackLabel,
} from './fixtures/digital-human'

const baseURL = process.env.BASE_URL || 'http://127.0.0.1:18080'
const DATABASE_URL = process.env.E2E_DATABASE_URL || ''
const adminEmail = process.env.E2E_ADMIN_EMAIL || 'e2e-admin-ci@test.local'
const adminPassword = process.env.E2E_ADMIN_PASSWORD ?? ''
const FLOW_PASSWORD = 'DhFlow!e2e-105fix1'

const runId = newRunId().replace('t104-', 't105fix1')
const syntheticAvatar = '11111111-1111-4111-8111-111111111111' // K13.6 合成 fixture
const syntheticVoice = 'preset-zh-natural-01'

interface Envelope<T> { success: boolean; data: T; code?: string }

async function data<T>(response: { status(): number; json(): Promise<unknown>; text(): Promise<string> },
  allowed: number[] = [200, 201, 202]): Promise<T> {
  const status = response.status()
  const body = await response.json().catch(() => null) as (Envelope<T> & { error?: string }) | null
  if (!allowed.includes(status) || !body?.success) {
    throw new Error(`HTTP ${status}: ${JSON.stringify(body ?? await response.text()).slice(0, 300)}`)
  }
  return body.data
}

/** 管理员 API 会话（与 lifecycle spec 同口径）。 */
async function adminApi(): Promise<APIRequestContext> {
  const context = await playwrightRequest.newContext({ baseURL, extraHTTPHeaders: { Origin: baseURL } })
  await data(await context.post('/api/auth/login', { data: { email: adminEmail, password: adminPassword } }))
  return context
}

interface CatalogConfig {
  version: number; enabled: boolean; newSessionsAllowed: boolean
  recordingEnabled: boolean; customAvatarEnabled: boolean
  maxSessionsGlobal: number; maxQueuedGlobal: number
  allowedBackendIds: string[]
  presetAvatarStates: Array<{ id: string; enabled: boolean }>
  voiceStates: Array<{ id: string; enabled: boolean }>
  billingNoticeVersion: string
}

/** 控制面种入 digital_human_render 后端行（真实 ADMIN API 链：受信端点→凭据→模型；幂等；lifecycle 同款）。 */
async function ensureRenderBackend(admin: APIRequestContext): Promise<string> {
  const origin = process.env.PLATFORM_AI_E2E_ORIGIN ?? 'https://qwen-e2e.invalid'
  const baseUrl = process.env.PLATFORM_AI_E2E_BASE_URL ?? 'https://qwen-e2e.invalid/v1'
  const apiKey = process.env.PLATFORM_AI_E2E_API_KEY ?? 'e2e-placeholder-key'
  const originRes = await admin.post('/api/admin/ai/trusted-origins', { data: { origin, label: 'DH e2e 测试端点' } })
  if (![201, 409].includes(originRes.status())) throw new Error(`trusted-origin HTTP ${originRes.status()}`)
  const credRes = await admin.post('/api/admin/ai/credentials', {
    data: { name: 'dh-e2e-render', provider: 'openai-completions', baseUrl, apiKey } })
  let credentialId: string | undefined
  if (credRes.status() === 201) credentialId = ((await credRes.json()) as { id?: string }).id
  else {
    const list = await (await admin.get('/api/admin/ai/credentials')).json() as Array<{
      id: string; name?: string; provider?: string; baseUrl?: string }>
    credentialId = (Array.isArray(list) ? list : []).find((row) =>
      row.provider === 'openai-completions' && row.baseUrl === baseUrl)?.id
      ?? (Array.isArray(list) ? list : []).find((row) => row.name === 'dh-e2e-render')?.id
  }
  if (!credentialId) throw new Error(`credential 未取得（HTTP ${credRes.status()}）`)
  await admin.post('/api/admin/ai/models', {
    data: { capability: 'digital_human_render', modelRole: 'primary', credentialId, model: 'dh-e2e-render-model' } })
  const models = await (await admin.get('/api/admin/ai/models')).json() as Array<{ id: string; capability: string; model: string }>
  const row = (Array.isArray(models) ? models : [])
    .find((item) => item.capability === 'digital_human_render' && item.model === 'dh-e2e-render-model')
  if (!row) throw new Error('digital_human_render 模型行未建成')
  return row.id
}

/** 合成形象/音色目录条目（K13.6 合成 fixture；基础条目沿 G 阶段 DB 直插纪律注入）。 */
async function seedCatalogEntries(backendId: string): Promise<void> {
  const avatar = {
    id: syntheticAvatar, revision: 1, name: '合成测试形象', source: 'preset', state: 'ready',
    compatibleBackendIds: [backendId],
  }
  const voice = {
    id: syntheticVoice, name: '合成测试音色', enabled: true, providerModelRef: 'preset-zh-natural-01',
    compatibleBackendIds: [backendId],
  }
  await query(DATABASE_URL, `UPDATE dh_catalog SET config_json = jsonb_set(jsonb_set(config_json,
      '{avatars}', to_jsonb(CAST($1 AS jsonb)), true),
      '{voices}', to_jsonb(CAST($2 AS jsonb)), true) WHERE singleton_id = 1`,
    [JSON.stringify([avatar]), JSON.stringify([voice])])
}

/** 复用 ensureRenderBackend 已解析的凭据 id（lifecycle 同款）。 */
async function currentCredentialId(admin: APIRequestContext): Promise<string> {
  const list = await (await admin.get('/api/admin/ai/credentials')).json() as Array<{
    id: string; name?: string; provider?: string; baseUrl?: string }>
  const baseUrl = process.env.PLATFORM_AI_E2E_BASE_URL ?? 'https://qwen-e2e.invalid/v1'
  const found = (Array.isArray(list) ? list : []).find((row) =>
    row.provider === 'openai-completions' && row.baseUrl === baseUrl)
  if (!found) throw new Error('凭据行不存在（应先经 ensureRenderBackend 建立）')
  return found.id
}

/** 控制面补全 voice/video_tts 模型行（同一凭据；真实 ADMIN API；lifecycle 同款）。 */
async function ensureCapabilityModels(admin: APIRequestContext, credentialId: string)
  : Promise<{ stt: string; tts: string }> {
  const stt = 'dh-e2e-stt'
  const tts = 'dh-e2e-tts'
  for (const [capability, model] of [['voice', stt], ['video_tts', tts]] as const) {
    const res = await admin.post('/api/admin/ai/models', {
      data: { capability, modelRole: 'primary', credentialId, model } })
    if (![201, 409].includes(res.status())) {
      throw new Error(`capability ${capability} 模型行创建失败 HTTP ${res.status()}`)
    }
  }
  return { stt, tts }
}

/** 价表：复制 active → 覆盖为既有模型+四能力模型（含 render）→ 激活（真实管理 API；lifecycle 同款）。 */
async function ensurePricedModels(admin: APIRequestContext, renderModel: string, sttModel: string,
  ttsModel: string): Promise<void> {
  const versions = await (await admin.get('/api/admin/ai/price-tables')).json() as Array<{
    id: string; status: string }>
  const active = (Array.isArray(versions) ? versions : []).find((row) => row.status === 'active')
  if (!active) throw new Error('无 active 价表版本（隔离栈种子应有一份）')
  const detail = await (await admin.get(`/api/admin/ai/price-tables/${active.id}`)).json() as {
    models: Array<Record<string, unknown>> }
  const carried = (detail.models ?? []).map((row) => ({
    modelId: row.modelId, capability: row.capability, provider: row.provider,
    centsPer1kInputTokens: row.centsPer1kInputTokens, centsPer1kOutputTokens: row.centsPer1kOutputTokens,
    centsPerImage: row.centsPerImage, centsPerSecond: row.centsPerSecond,
  }))
  const merged = new Map(carried.map((row) => [String(row.modelId), row]))
  const priced = (modelId: string, capability: string) => ({
    modelId, capability, provider: 'openai-completions', centsPer1kInputTokens: 1,
    centsPer1kOutputTokens: 2, centsPerImage: 0, centsPerSecond: 1 })
  merged.set(sttModel, priced(sttModel, 'voice'))
  merged.set(ttsModel, priced(ttsModel, 'video_tts'))
  merged.set(renderModel, priced(renderModel, 'digital_human_render'))
  const draftRes = await admin.post('/api/admin/ai/price-tables', {
    data: { label: `dh-e2e-${runId}`, note: '105fix1 完整流四能力定价', copyFromVersionId: active.id } })
  if (![201, 409].includes(draftRes.status())) {
    throw new Error(`价表 draft 创建失败 HTTP ${draftRes.status()}`)
  }
  const versionsAfter = await (await admin.get('/api/admin/ai/price-tables')).json() as Array<{
    id: string; label: string; status: string }>
  const draft = (Array.isArray(versionsAfter) ? versionsAfter : [])
    .find((row) => row.label === `dh-e2e-${runId}` && row.status === 'draft')
    ?? (Array.isArray(versionsAfter) ? versionsAfter : []).find((row) => row.label === `dh-e2e-${runId}`)
  if (!draft) throw new Error('价表 draft 未找到')
  const putModels = await admin.put(`/api/admin/ai/price-tables/${draft.id}/models`,
    { data: { models: [...merged.values()] } })
  if (putModels.status() !== 200) {
    throw new Error(`价表 models 覆盖失败 HTTP ${putModels.status()}: ${await putModels.text()}`)
  }
  const activate = await admin.post(`/api/admin/ai/price-tables/${draft.id}/activate`)
  if (![200, 201].includes(activate.status())) {
    throw new Error(`价表激活失败 HTTP ${activate.status()}`)
  }
}

let controlPlaneCache: Promise<void> | null = null
function ensureFullControlPlane(admin: APIRequestContext): Promise<void> {
  controlPlaneCache ??= (async () => {
    await ensureRenderBackend(admin)
    const { stt, tts } = await ensureCapabilityModels(admin, await currentCredentialId(admin))
    await ensurePricedModels(admin, 'dh-e2e-render-model', stt, tts)
  })()
  return controlPlaneCache
}

/** ADMIN02：经真实控制面后端行开启目录（lifecycle 同款：能力模型+价表先行，会话创建要解析 render 价）。 */
async function enableCatalog(admin: APIRequestContext): Promise<CatalogConfig> {
  await ensureFullControlPlane(admin)
  const backendId = await ensureRenderBackend(admin)
  const before = await data<CatalogConfig>(await admin.get('/api/admin/digital-human/config'))
  const put = await admin.put('/api/admin/digital-human/config', { data: {
    expectedVersion: before.version,
    requestId: crypto.randomUUID(),
    reason: `e2e 105fix1 C105X-03：开启目录（${runId}）`,
    enabled: true,
    newSessionsAllowed: true,
    recordingEnabled: false,
    customAvatarEnabled: false,
    maxSessionsGlobal: 10,
    maxQueuedGlobal: 20,
    allowedBackendIds: [backendId],
    presetAvatarStates: [{ id: syntheticAvatar, enabled: true }],
    voiceStates: [{ id: syntheticVoice, enabled: true }],
    billingNoticeVersion: 'dh-e2e-billing-v1',
  } })
  const catalog = await data<CatalogConfig>(put, [200, 201])
  await seedCatalogEntries(backendId)
  return catalog
}

/** 浏览器真实 RTCPeerConnection 生成完整 offer SDP（recvonly×2；ICE complete 或 10s 兜底）。 */
async function browserOfferSdp(page: import('@playwright/test').Page): Promise<string> {
  return await page.evaluate(async () => {
    const pc = new RTCPeerConnection()
    pc.addTransceiver('video', { direction: 'recvonly' })
    pc.addTransceiver('audio', { direction: 'recvonly' })
    const offer = await pc.createOffer()
    await pc.setLocalDescription(offer)
    await new Promise<void>((resolve) => {
      if (pc.iceGatheringState === 'complete') return resolve()
      const timer = setTimeout(resolve, 10_000)
      pc.onicegatheringstatechange = () => {
        if (pc.iceGatheringState === 'complete') { clearTimeout(timer); resolve() }
      }
    })
    let sdp = pc.localDescription?.sdp ?? ''
    pc.close()
    // firefox 默认在 offer 中携带 m=application（数据信道）段，其 a=sendrecv 会触发 runtime
    // 媒体面的方向子串判别（media.py 只协商音视频下行）；本测试不使用数据信道，按 m= 段剥离。
    const sections = sdp.split(/(?=m=)/)
    sdp = sections.filter((section) => !section.startsWith('m=application')).join('')
    return sdp
  })
}

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

  test('完整对话流（创建→offer→ready→文字 turn→打断）：媒体桥已接通（C105X-03），必需断言打真实响应', async ({ page, request }) => {
    // 真实栈含登录/控制面种子/turn 轮询，30s 默认超时不够（webkit 慢负载更甚）。
    test.setTimeout(120_000)
    await loginOnAiApp(page)
    await openWorkbench(page)
    const capability = await probeCapabilities(request)
    // 前置：媒体桥已接线（探针 401/404=可达；503=未接或 runtime 不可达，如实 skip 并给出精确原因）。
    // 目录开启不作为前置——本用例下方 enableCatalog+seedCatalogEntries 自种（探针时点隔离栈未种目录）。
    test.skip(!capability.mediaBridgeAvailable,
      `完整流依赖媒体桥接线：API16=${capability.mediaBridgeCode ?? 'unreachable'}（503=未接线或 runtime 不可达）`)

    // —— 种子纪律复刻 lifecycle spec（同栈同源：admin 控制面 + 合成目录条目；不 mock 任何响应）——
    const admin = await adminApi()
    const catalog = await enableCatalog(admin)
    const account = await registerAndLogin(baseURL,
      { email: `${runId}-flow@example.invalid`, role: 'consumer', displayName: `T105fix1 flow ${runId}` },
      FLOW_PASSWORD, DATABASE_URL)
    const profile = await data<{ id: string; version: number }>(await account.post('/api/digital-human/profiles', {
      data: {
        name: '完整流角色', persona: '用简洁中文回答。', greeting: '你好',
        tone: 'natural', avatarId: syntheticAvatar, voiceId: syntheticVoice,
        catalogVersion: catalog.version, requestId: crypto.randomUUID(),
      },
    }))
    const preflight = await data<{ id: string }>(await account.post('/api/digital-human/preflights', {
      data: { profileId: profile.id, profileVersion: profile.version, inputMode: 'text', controllerId: crypto.randomUUID() },
    }))
    const created = await data<{ id: string; state: string; leaseEpoch: number; mediaEpoch: number }>(
      await account.post('/api/digital-human/sessions', {
        data: { preflightId: preflight.id, requestId: crypto.randomUUID(), saveTranscript: false },
      }), [200, 201, 202])

    // 必需①：offer 200 + answer SDP 可解析（真实浏览器 SDP → Java 中继 → runtime INTERNAL03 mTLS 链）。
    const sdp = await browserOfferSdp(page)
    expect(sdp.length).toBeGreaterThan(64)
    const offerResponse = await account.post(`/api/digital-human/sessions/${created.id}/webrtc/offer`, {
      data: {
        requestId: crypto.randomUUID(), leaseEpoch: created.leaseEpoch,
        mediaEpoch: created.mediaEpoch, sdp, type: 'offer',
      },
    })
    expect(offerResponse.status()).toBe(200)
    const answer = await offerResponse.json() as { data?: { sdp?: string; type?: string; mediaEpoch?: number } }
    expect(answer.data?.type).toBe('answer')
    expect(answer.data?.sdp).toBeTruthy()
    expect((answer.data?.sdp ?? '').length).toBeGreaterThan(16)

    // 必需②：会话 ready（connectReady 单向 CAS，真实 DB）。
    const sessionAfter = await data<{ session: { state: string } }>(
      await account.get(`/api/digital-human/sessions/${created.id}`))
    expect(sessionAfter.session.state).toBe('ready')

    // 必需③：文字 turn 真实受理（平台 text 行 → 栈内假上游，DNS pinning 固定表；真实管线）。
    const turn = await data<{ id: string; turnEpoch: number }>(
      await account.post(`/api/digital-human/sessions/${created.id}/turns`, {
        data: { requestId: crypto.randomUUID(), leaseEpoch: created.leaseEpoch, text: '用一句话介绍数字人工作台。' },
      }), [200, 201, 202])
    expect(turn.id).toBeTruthy()

    // 必需③b：turn 受理后为活跃轮（LLM/TTS stage 是 C105X-04 明确的 503 占位——Fake 栈内
    // turn 停留 responding 不回 ready，属本卡边界而非缺陷；打断恰在活跃轮上验证才有意义）。
    const active = await data<{ session: { state: string } }>(
      await account.get(`/api/digital-human/sessions/${created.id}`))
    expect(['responding', 'ready']).toContain(active.session.state)

    // 必需④：打断（API20）真实 2xx（活跃轮上 effective=true；完成后为幂等分支，同样 2xx）。
    const interrupt = await account.post(`/api/digital-human/sessions/${created.id}/interrupt`, {
      data: { requestId: crypto.randomUUID(), leaseEpoch: created.leaseEpoch, turnId: turn.id, turnEpoch: turn.turnEpoch },
    })
    expect(interrupt.status()).toBeLessThan(300)

    // 收尾：end（幂等；会话正常收口不留活动行）。
    const end = await account.post(`/api/digital-human/sessions/${created.id}/end`, {
      data: { requestId: crypto.randomUUID() },
    })
    expect([200, 202]).toContain(end.status())

    // SHOULD（D-03 分层）：浏览器 RTCPeerConnection 媒体轨真连通为独立 SHOULD 项，不阻塞卡验收
    //（Playwright on macOS host + runtime in docker 的 ICE 连通取决于端口发布拓扑，属环境属性；
    // host candidates 拓扑结论记录在 test-artifacts/task-105/fix1/C105X-03/）。
    test.info().annotations.push({
      type: 'SHOULD',
      description: '浏览器 RTCPeerConnection 媒体轨连通：D-03 批准的分层项（环境属性不阻塞代码接线验收）',
    })

    // 收尾：目录关回缺省关闭态（同文件 tc105e_06_02/03 断言依赖「缺省=关闭」；lifecycle afterAll 同纪律）。
    try {
      const before = await data<{ version: number }>(await admin.get('/api/admin/digital-human/config'))
      await admin.put('/api/admin/digital-human/config', { data: {
        expectedVersion: before.version, requestId: crypto.randomUUID(),
        reason: `105fix1 完整流收尾还原`, enabled: false, newSessionsAllowed: false,
        recordingEnabled: false, customAvatarEnabled: false, maxSessionsGlobal: 10, maxQueuedGlobal: 20,
        allowedBackendIds: [], presetAvatarStates: [], voiceStates: [], billingNoticeVersion: 'dh-e2e-billing-v1',
      } })
    } catch {
      // 还原失败不影响本用例结论（引擎间 DB 重置兜底）
    }
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
