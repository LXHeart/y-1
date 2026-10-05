/**
 * 数字人 105-fix-2 恢复场景夹具（任务书 105-fix-2 C-05 步骤3 / TC-C05-001～003，W31）。
 *
 * F-E（§9.3 唯一 fresh 栈 + 真实浏览器与 Python runtime）专用：
 * - 两合成个人账号（dh-fix2-a-/dh-fix2-b- + 测试 runId）+ 一个合成管理员（ci-e2e 已种），
 *   A/B 不共享账号（避免 owner 唯一约束掩盖全局容量行为）；
 * - catalog/后端合成种子（max=1 排队矩阵；真实 ADMIN API + 合成条目 DB 直插，lifecycle spec 同款）；
 * - 作用域 DB 查询/清理：只结束本 runId 账号会话并关闭 context，不 SQL 置状态、不全库清理；
 * - 视频帧真实断言 helper：videoWidth>0 且 readyState≥2，入站帧计数在两个采样点增长；
 * - 页面网络统计：收集浏览器自动发出的 webrtc/offer（与 API 直调测试请求区分）。
 *
 * 纪律（§12.2）：不 route.fulfill 冒充业务成功；禁止条件 skip；所有断言打真实 API/DB/UI。
 */
import { expect, request as playwrightRequest, type APIRequestContext, type Page } from '@playwright/test'
import { accountIdOf, query, registerAndLogin as registerTask103 } from './task-104'

export { accountIdOf, query }

export const PASSWORD = 'DhFix2!e2e-105F2'

export interface Fix2Run {
  runId: string
  emailA: string
  emailB: string
}

/** runId 作用域（dh-fix2- 固定测试前缀；§12.2 共享 fixture 口径）。 */
export function newFix2Run(): Fix2Run {
  const stamp = new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14)
  const rand = Math.random().toString(36).slice(2, 8)
  const runId = `dh-fix2-${stamp}-${rand}`
  return { runId, emailA: `${runId}-a@example.invalid`, emailB: `${runId}-b@example.invalid` }
}

/** 合成个人账号（真实注册 API + DB 口令落库，task-103 同款；返回带登录态的 API context）。 */
export function registerAndLogin(baseURL: string, email: string, runId: string, tag: string)
  : Promise<APIRequestContext> {
  return registerTask103(baseURL,
    { email, role: 'consumer', displayName: `T105F2 ${tag} ${runId}` }, PASSWORD, process.env.E2E_DATABASE_URL ?? '')
}

export interface CatalogConfig {
  version: number; enabled: boolean; newSessionsAllowed: boolean
  recordingEnabled: boolean; customAvatarEnabled: boolean
  maxSessionsGlobal: number; maxQueuedGlobal: number
  allowedBackendIds: string[]
  presetAvatarStates: Array<{ id: string; enabled: boolean }>
  voiceStates: Array<{ id: string; enabled: boolean }>
  billingNoticeVersion: string
}

interface Envelope<T> { success: boolean; data: T; code?: string }

export async function envelopeData<T>(response: { status(): number; json(): Promise<unknown>; text(): Promise<string> },
  allowed: number[] = [200, 201, 202], label = 'API'): Promise<T> {
  const status = response.status()
  const body = await response.json().catch(() => null) as (Envelope<T> & { error?: string }) | null
  if (!allowed.includes(status) || !body?.success) {
    throw new Error(`${label} HTTP ${status}: ${JSON.stringify(body ?? await response.text()).slice(0, 300)}`)
  }
  return body.data
}

/** 合成管理员 API 会话（ci-e2e 已种的隔离栈管理员；§9.3 fixture 口径）。 */
export async function adminApi(baseURL: string, adminEmail: string, adminPassword: string)
  : Promise<APIRequestContext> {
  const context = await playwrightRequest.newContext({
    baseURL, extraHTTPHeaders: { Origin: baseURL },
  })
  await envelopeData(await context.post('/api/auth/login', { data: { email: adminEmail, password: adminPassword } }),
    [200], 'admin login')
  return context
}

// ---------- catalog/后端合成种子（lifecycle spec 同款控制面链；max=1 排队矩阵） ----------

export const SYNTHETIC_AVATAR = '11111111-1111-4111-8111-111111111111' // K13.6 合成 fixture
export const SYNTHETIC_VOICE = 'preset-zh-natural-01'

async function ensureRenderBackend(admin: APIRequestContext): Promise<string> {
  const origin = process.env.PLATFORM_AI_E2E_ORIGIN ?? 'https://qwen-e2e.invalid'
  const baseUrl = process.env.PLATFORM_AI_E2E_BASE_URL ?? 'https://qwen-e2e.invalid/v1'
  const apiKey = process.env.PLATFORM_AI_E2E_API_KEY ?? 'e2e-placeholder-key'
  const originRes = await admin.post('/api/admin/ai/trusted-origins', { data: { origin, label: 'DH fix2 e2e 测试端点' } })
  if (![201, 409].includes(originRes.status())) throw new Error(`trusted-origin HTTP ${originRes.status()}`)
  const credRes = await admin.post('/api/admin/ai/credentials', {
    data: { name: 'dh-fix2-e2e-render', provider: 'openai-completions', baseUrl, apiKey } })
  let credentialId: string | undefined
  if (credRes.status() === 201) credentialId = ((await credRes.json()) as { id?: string }).id
  else {
    const list = await (await admin.get('/api/admin/ai/credentials')).json() as Array<{
      id: string; name?: string; provider?: string; baseUrl?: string }>
    credentialId = (Array.isArray(list) ? list : []).find((row) =>
      row.provider === 'openai-completions' && row.baseUrl === baseUrl)?.id
      ?? (Array.isArray(list) ? list : []).find((row) => row.name === 'dh-fix2-e2e-render')?.id
  }
  if (!credentialId) throw new Error(`credential 未取得（HTTP ${credRes.status()}）`)
  await admin.post('/api/admin/ai/models', {
    data: { capability: 'digital_human_render', modelRole: 'primary', credentialId, model: 'dh-fix2-e2e-render-model' } })
  const models = await (await admin.get('/api/admin/ai/models')).json() as Array<{
    id: string; capability: string; model: string }>
  const row = (Array.isArray(models) ? models : [])
    .find((item) => item.capability === 'digital_human_render' && item.model === 'dh-fix2-e2e-render-model')
  if (!row) throw new Error('digital_human_render 模型行未建成')
  return row.id
}

async function currentCredentialId(admin: APIRequestContext): Promise<string> {
  const list = await (await admin.get('/api/admin/ai/credentials')).json() as Array<{
    id: string; provider?: string; baseUrl?: string }>
  const baseUrl = process.env.PLATFORM_AI_E2E_BASE_URL ?? 'https://qwen-e2e.invalid/v1'
  const found = (Array.isArray(list) ? list : []).find((row) =>
    row.provider === 'openai-completions' && row.baseUrl === baseUrl)
  if (!found) throw new Error('凭据行不存在（应先经 ensureRenderBackend 建立）')
  return found.id
}

async function ensureCapabilityModels(admin: APIRequestContext, credentialId: string)
  : Promise<{ stt: string; tts: string }> {
  const stt = 'dh-fix2-e2e-stt'
  const tts = 'dh-fix2-e2e-tts'
  for (const [capability, model] of [['voice', stt], ['video_tts', tts]] as const) {
    const res = await admin.post('/api/admin/ai/models', {
      data: { capability, modelRole: 'primary', credentialId, model } })
    if (![201, 409].includes(res.status())) throw new Error(`capability ${capability} 模型行创建失败 HTTP ${res.status()}`)
  }
  return { stt, tts }
}

async function ensurePricedModels(admin: APIRequestContext, runId: string, renderModel: string,
  sttModel: string, ttsModel: string): Promise<void> {
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
  const label = `dh-fix2-e2e-${runId}`
  const draftRes = await admin.post('/api/admin/ai/price-tables', {
    data: { label, note: '105-fix-2 recovery 四能力定价', copyFromVersionId: active.id } })
  if (![201, 409].includes(draftRes.status())) throw new Error(`价表 draft 创建失败 HTTP ${draftRes.status()}`)
  const versionsAfter = await (await admin.get('/api/admin/ai/price-tables')).json() as Array<{
    id: string; label: string; status: string }>
  const draft = (Array.isArray(versionsAfter) ? versionsAfter : [])
    .find((row) => row.label === label && row.status === 'draft')
    ?? (Array.isArray(versionsAfter) ? versionsAfter : []).find((row) => row.label === label)
  if (!draft) throw new Error('价表 draft 未找到')
  const putModels = await admin.put(`/api/admin/ai/price-tables/${draft.id}/models`,
    { data: { models: [...merged.values()] } })
  if (putModels.status() !== 200) throw new Error(`价表 models 覆盖失败 HTTP ${putModels.status()}`)
  const activate = await admin.post(`/api/admin/ai/price-tables/${draft.id}/activate`)
  if (![200, 201].includes(activate.status())) throw new Error(`价表激活失败 HTTP ${activate.status()}`)
}

async function seedCatalogEntries(databaseUrl: string, backendId: string): Promise<void> {
  const avatar = {
    id: SYNTHETIC_AVATAR, revision: 1, name: '合成测试形象', source: 'preset', state: 'ready',
    compatibleBackendIds: [backendId],
  }
  const voice = {
    id: SYNTHETIC_VOICE, name: '合成测试音色', enabled: true, providerModelRef: 'preset-zh-natural-01',
    compatibleBackendIds: [backendId],
  }
  await query(databaseUrl, `UPDATE dh_catalog SET config_json = jsonb_set(jsonb_set(config_json,
      '{avatars}', to_jsonb(CAST($1 AS jsonb)), true),
      '{voices}', to_jsonb(CAST($2 AS jsonb)), true) WHERE singleton_id = 1`,
    [JSON.stringify([avatar]), JSON.stringify([voice])])
}

/** 控制面种子：目录开启 + max=1（TC-C05-002 排队矩阵）+ 合成形象/音色；真实 ADMIN API + DB 条目。 */
export async function enableCatalogMaxOne(admin: APIRequestContext, runId: string): Promise<CatalogConfig> {
  const backendId = await ensureRenderBackend(admin)
  const { stt, tts } = await ensureCapabilityModels(admin, await currentCredentialId(admin))
  await ensurePricedModels(admin, runId, 'dh-fix2-e2e-render-model', stt, tts)
  const before = await envelopeData<CatalogConfig>(await admin.get('/api/admin/digital-human/config'),
    [200], 'config get')
  const put = await admin.put('/api/admin/digital-human/config', { data: {
    expectedVersion: before.version,
    requestId: crypto.randomUUID(),
    reason: `e2e 105-fix-2 recovery：开启目录 max=1（${runId}）`,
    enabled: true,
    newSessionsAllowed: true,
    recordingEnabled: false,
    customAvatarEnabled: false,
    maxSessionsGlobal: 1,
    maxQueuedGlobal: 20,
    allowedBackendIds: [backendId],
    presetAvatarStates: [{ id: SYNTHETIC_AVATAR, enabled: true }],
    voiceStates: [{ id: SYNTHETIC_VOICE, enabled: true }],
    billingNoticeVersion: 'dh-fix2-e2e-billing-v1',
  } })
  const catalog = await envelopeData<CatalogConfig>(put, [200, 201], 'config put')
  await seedCatalogEntries(process.env.E2E_DATABASE_URL ?? '', backendId)
  return catalog
}

/** 收尾还原目录关闭态（引擎间 DB 重置亦兜底；失败不掩盖用例结论）。 */
export async function disableCatalog(admin: APIRequestContext, runId: string): Promise<void> {
  const before = await envelopeData<CatalogConfig>(await admin.get('/api/admin/digital-human/config'),
    [200], 'config get')
  await admin.put('/api/admin/digital-human/config', { data: {
    expectedVersion: before.version,
    requestId: crypto.randomUUID(),
    reason: `e2e 105-fix-2 recovery：还原关闭态（${runId}）`,
    enabled: false,
    newSessionsAllowed: false,
    recordingEnabled: false,
    customAvatarEnabled: false,
    maxSessionsGlobal: 10,
    maxQueuedGlobal: 20,
    allowedBackendIds: before.allowedBackendIds,
    presetAvatarStates: [],
    voiceStates: [],
    billingNoticeVersion: 'dh-fix2-e2e-billing-v1',
  } })
}

// ---------- 角色与会话（真实 API 种子；会话开始必须由 UI 按钮完成） ----------

export interface ProfileHandle { id: string; version: number }

/** API03 建角色（合成 fixture；TC-C05 的「选角色」在 UI 列表完成）。 */
export async function createProfileViaApi(context: APIRequestContext, runId: string, name: string,
  catalogVersion: number): Promise<ProfileHandle> {
  return envelopeData<ProfileHandle>(await context.post('/api/digital-human/profiles', {
    data: {
      name,
      persona: '用简洁中文帮助我整理口播思路。不要调用外部工具。',
      greeting: '你好，今天想创作什么内容？',
      tone: 'natural',
      avatarId: SYNTHETIC_AVATAR,
      voiceId: SYNTHETIC_VOICE,
      catalogVersion,
      requestId: crypto.randomUUID(),
    },
  }), [200, 201], 'create profile')
}

export interface SessionHandle { id: string; state: string; leaseEpoch: number; mediaEpoch: number }

/** API07+API08 直建会话（仅 TC-C05-002 的 B 直调闸/工具用途；主流程开始必须走 UI 按钮）。 */
export async function createSessionViaApi(context: APIRequestContext, profile: ProfileHandle)
  : Promise<SessionHandle> {
  const preflight = await envelopeData<{ id: string }>(await context.post('/api/digital-human/preflights', {
    data: { profileId: profile.id, profileVersion: profile.version, inputMode: 'text', controllerId: crypto.randomUUID() },
  }), [200, 201], 'preflight')
  return envelopeData<SessionHandle>(await context.post('/api/digital-human/sessions', {
    data: { preflightId: preflight.id, requestId: crypto.randomUUID(), saveTranscript: false },
  }), [200, 201, 202], 'create session')
}

// ---------- 作用域 DB 查询 ----------

export interface SessionRow {
  id: string; owner_account_id: string; state: string; ended_at: string | null
  worker_id: string | null; lease_epoch: number; media_epoch: number
  state_entered_at: string; last_browser_heartbeat_at: string | null
}

export async function sessionRowById(databaseUrl: string, sessionId: string): Promise<SessionRow | null> {
  const rows = await query<SessionRow>(databaseUrl,
    'SELECT id::text AS id, owner_account_id, state, ended_at, worker_id, lease_epoch, media_epoch,'
    + ' state_entered_at, last_browser_heartbeat_at FROM dh_session WHERE id = CAST($1 AS uuid)', [sessionId])
  return rows[0] ?? null
}

/** 轮询 DB 到期望状态（真实状态机推进；不 sleep 换绿灯、不 SQL 置状态）。 */
export async function pollSessionState(databaseUrl: string, sessionId: string, states: string[],
  timeoutMs: number): Promise<SessionRow> {
  const deadline = Date.now() + timeoutMs
  let last: SessionRow | null = null
  while (Date.now() < deadline) {
    last = await sessionRowById(databaseUrl, sessionId)
    if (last && states.includes(last.state)) return last
    await new Promise((resolve) => setTimeout(resolve, 1_000))
  }
  throw new Error(`轮询超时（${timeoutMs}ms）：session ${sessionId} 未进入 [${states.join(',')}]，最后 state=${last?.state ?? 'unknown'}`)
}

/** owner 的全部会话（作用域清理/断言用）。 */
export async function sessionsOfOwner(databaseUrl: string, accountId: string): Promise<SessionRow[]> {
  return query<SessionRow>(databaseUrl,
    'SELECT id::text AS id, owner_account_id, state, ended_at, worker_id, lease_epoch, media_epoch,'
    + ' state_entered_at, last_browser_heartbeat_at FROM dh_session WHERE owner_account_id = $1'
    + ' ORDER BY created_at, id', [accountId])
}

/** 本 runId 账号的未决 promotion marker（收敛断言：收尾时应为 0）。 */
export async function pendingPromotionMarkers(databaseUrl: string, ownerAccountIds: string[]): Promise<number> {
  const rows = await query<{ n: number }>(databaseUrl,
    "SELECT count(*)::int AS n FROM dh_session WHERE owner_account_id = ANY($1)"
    + " AND worker_id LIKE 'promotion:%' AND state NOT IN ('ended','failed')", [ownerAccountIds])
  return Number(rows[0]?.n ?? 0)
}

/** dh_operation 计数（经济键；TC-C05-002 断言排队晋升不新增）。 */
export async function operationCount(databaseUrl: string, accountId: string): Promise<number> {
  const rows = await query<{ n: number }>(databaseUrl,
    'SELECT count(*)::int AS n FROM dh_operation WHERE owner_account_id = $1', [accountId])
  return Number(rows[0]?.n ?? 0)
}

// ---------- 浏览器观测（真实页面；不 mock 业务响应） ----------

export interface OfferObservation { url: string; at: number }

/**
 * 收集页面真实发出的 webrtc/offer 请求（自动流程）；与 API context 直调的测试请求天然
 * 区分（直调不经页面）。abort 类拦截不在此列（TC-C05-003 的 SSE 切断用 events 路径）。
 */
export function trackBrowserOffers(page: Page): { offers: OfferObservation[] } {
  const offers: OfferObservation[] = []
  page.on('request', (request) => {
    if (request.method() === 'POST' && /\/api\/digital-human\/sessions\/[^/]+\/webrtc\/offer$/.test(request.url())) {
      offers.push({ url: request.url(), at: Date.now() })
    }
  })
  return { offers }
}

/** 页面内 video 真实状态（dh-stage-video；入站帧计数来自 getVideoPlaybackQuality）。 */
export interface VideoSample {
  found: boolean
  videoWidth: number
  videoHeight: number
  readyState: number
  totalVideoFrames: number
  srcObjectKind: string | null
  paused: boolean
  muted: boolean
  trackStates: string[]
}

export function sampleVideo(page: Page): Promise<VideoSample> {
  return page.evaluate(() => {
    const video = document.querySelector('[data-testid="dh-stage-video"]') as HTMLVideoElement | null
    if (!video) {
      return { found: false, videoWidth: 0, videoHeight: 0, readyState: 0, totalVideoFrames: 0,
        srcObjectKind: null, paused: true, muted: true, trackStates: [] }
    }
    const stream = video.srcObject as MediaStream | null
    return {
      found: true,
      videoWidth: video.videoWidth,
      videoHeight: video.videoHeight,
      readyState: video.readyState,
      totalVideoFrames: video.getVideoPlaybackQuality?.().totalVideoFrames ?? 0,
      srcObjectKind: stream ? stream.constructor.name : null,
      paused: video.paused,
      muted: video.muted,
      trackStates: stream ? stream.getTracks().map((t) => `${t.kind}:${t.readyState}:${t.muted ? 'muted' : 'live'}`) : [],
    }
  })
}

/** 页面内全部 peer 的连接态摘要（诊断用）。 */
export function peerStates(page: Page): Promise<string[]> {
  return page.evaluate(async () => {
    const w = window as unknown as { __dhFix2Peers?: RTCPeerConnection[]; __dhFix2LastRemote?: object | null }
    const peers = w.__dhFix2Peers ?? []
    const remote = w.__dhFix2LastRemote ? JSON.stringify(w.__dhFix2LastRemote) : 'null'
    const states = await Promise.all(peers.map(async (peer, i) => {
      let localSummary = 'unavailable'
      try {
        const stats = await peer.getStats()
        const locals: string[] = []
        stats.forEach((r) => {
          if (r.type === 'local-candidate') {
            const c = r as unknown as { candidateType?: string; ip?: string; port?: number }
            locals.push(`${c.candidateType}@${c.ip}:${c.port}`)
          }
        })
        localSummary = locals.join(',')
      } catch { /* keep unavailable */ }
      return `pc${i}:conn=${peer.connectionState} ice=${peer.iceConnectionState}`
        + ` gathering=${peer.iceGatheringState} locals=[${localSummary}]`
    }))
    return [...states, `answerSdp=${remote}`]
  })
}

/**
 * 等待真实入站视频帧（TC-C05 帧纪律）：videoWidth>0 且 readyState≥2（HAVE_CURRENT_DATA 以上），
 * 且 totalVideoFrames 在两个采样点增长（静态 poster 冒充在这里必失败）。
 */
export async function awaitRealFrames(page: Page, timeoutMs: number, label: string): Promise<VideoSample> {
  const deadline = Date.now() + timeoutMs
  let last = await sampleVideo(page)
  let growing = false
  while (Date.now() < deadline) {
    if (!last.found) throw new Error(`[${label}] 页面无 dh-stage-video 元素`)
    // 非静音自动播放被浏览器策略拒绝时，产品 UI 提供「点击播放」手势（E-03 步骤2）：
    // 以真实用户动作点击后视频才开始消费（MediaStream 在 paused 时 readyState 恒为 0）。
    const playGesture = page.getByTestId('dh-stage-play')
    if (await playGesture.isVisible().catch(() => false)) {
      await playGesture.click().catch(() => {})
    }
    if (last.videoWidth > 0 && last.readyState >= 2 && last.totalVideoFrames > 0) {
      await new Promise((resolve) => setTimeout(resolve, 1_000))
      const second = await sampleVideo(page)
      if (second.totalVideoFrames > last.totalVideoFrames) { growing = true; last = second; break }
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
    last = await sampleVideo(page)
  }
  if (!growing) {
    const pcs = await peerStates(page).catch(() => ['peerStates 采样失败'])
    const pair = await selectedCandidatePair(page).catch(() => null)
    throw new Error(`[${label}] 帧未增长：videoWidth=${last.videoWidth} readyState=${last.readyState}`
      + ` totalVideoFrames=${last.totalVideoFrames} paused=${last.paused} muted=${last.muted}`
      + ` srcObject=${last.srcObjectKind ?? 'null'} tracks=[${last.trackStates.join(',')}]`
      + ` peers=[${pcs.join(' | ')}] nominatedPair=${pair ? JSON.stringify(pair) : 'null'}`
      + '（静态画面/黑屏冒充会被此断言拒绝）')
  }
  return last
}

/** ICE 选中候选观测：hook 页面 RTCPeerConnection 构造（只记录，不干预）。在页面导航前注入。 */
export async function attachPeerRecorder(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const OriginalPeer = window.RTCPeerConnection
    const peers: RTCPeerConnection[] = []
    Object.defineProperty(window, '__dhFix2Peers', { get: () => peers, configurable: true })
    window.RTCPeerConnection = function (this: RTCPeerConnection,
      config?: RTCConfiguration) {
      const peer = new OriginalPeer(config)
      peers.push(peer)
      const origSet = peer.setRemoteDescription.bind(peer)
      peer.setRemoteDescription = (desc: unknown) => origSet(desc as RTCSessionDescriptionInit).then(() => {
        const rd = peer.remoteDescription
        ;(window as unknown as { __dhFix2LastRemote?: object | null }).__dhFix2LastRemote = rd ? {
          mLines: rd.sdp.split('\n').filter((l) => l.startsWith('m=')).length,
          msid: (rd.sdp.match(/a=msid:[^\r\n]*/g) ?? []).slice(0, 3),
          dirs: (rd.sdp.match(/a=(?:sendrecv|sendonly|recvonly|inactive)/g) ?? []).slice(0, 4),
          hasRelayCandidate: rd.sdp.includes('typ relay'),
          length: rd.sdp.length,
        } : null
      })
      return peer
    } as unknown as typeof RTCPeerConnection
  })
}

export interface CandidatePairSummary {
  state: string | null
  localType: string | null
  remoteType: string | null
  nominated: boolean
}

/** 读取选中候选对（getStats succeeded/nominated pair；任务书「断言选中候选」）。 */
export function selectedCandidatePair(page: Page): Promise<CandidatePairSummary> {
  return page.evaluate(async () => {
    const peers = (window as unknown as { __dhFix2Peers?: RTCPeerConnection[] }).__dhFix2Peers ?? []
    for (const peer of [...peers].reverse()) {
      if (peer.connectionState === 'closed') continue
      const stats = await peer.getStats()
      let summary: CandidatePairSummary = { state: null, localType: null, remoteType: null, nominated: false }
      stats.forEach((report) => {
        if (report.type === 'candidate-pair' && (report as { nominated?: boolean }).nominated
          && (report as { state?: string }).state === 'succeeded') {
          const local = stats.get((report as { localCandidateId?: string }).localCandidateId ?? '') as
            { candidateType?: string } | undefined
          const remote = stats.get((report as { remoteCandidateId?: string }).remoteCandidateId ?? '') as
            { candidateType?: string } | undefined
          summary = {
            state: 'succeeded',
            localType: local?.candidateType ?? null,
            remoteType: remote?.candidateType ?? null,
            nominated: true,
          }
        }
      })
      if (summary.nominated) return summary
    }
    return { state: null, localType: null, remoteType: null, nominated: false }
  })
}

// ---------- 作用域清理（清理E：只结束本 runId 账号会话并关闭 context） ----------

/** 经真实 API end 结束账号全部非终态会话（不 SQL 置状态）；返回已处理 id。 */
export async function endAllSessionsViaApi(context: APIRequestContext, databaseUrl: string,
  accountId: string): Promise<string[]> {
  const rows = await sessionsOfOwner(databaseUrl, accountId)
  const handled: string[] = []
  for (const row of rows) {
    if (['ended', 'failed'].includes(row.state)) continue
    const res = await context.post(`/api/digital-human/sessions/${row.id}/end`, {
      data: { requestId: crypto.randomUUID(), reason: 'e2e-run-cleanup' },
    })
    if (res.status() < 300) handled.push(row.id)
  }
  return handled
}

/** 排队 UI 徽标（UI-01）：等待「排队中」状态徽标可见（真实 GET/SSE 投影）。 */
export async function expectQueuedBadge(page: Page): Promise<void> {
  await expect(page.getByTestId('dh-state-badge')).toHaveText(/排队中/, { timeout: 30_000 })
}
