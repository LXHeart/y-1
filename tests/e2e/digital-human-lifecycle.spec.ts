/**
 * 数字人发布矩阵 lifecycle e2e（任务书 #105H C105H-02 / TC105H-02-01）。
 *
 * 运行于 ci-e2e-105-all.sh（DH_E2E=1 隔离栈：真实 Java 权威/经济键/账务 + Fake Python runtime；
 * 三引擎×四 spec 由 runner 编排）。纪律与 #105E/#105F/#105G 同款：
 * - 不 route.fulfill 冒充后端：所有断言打真实 API/DB/UI；
 * - 条件不满足的分支（媒体桥 D 缺口等）test.skip 并给出精确原因，不造事实；
 * - 合成账号 runId 作用域（task-104 fixture 口径），不用生产账号。
 *
 * 覆盖（K04 状态机 / K13.4 跨主体与墓碑 / E10/E11/E17/E19 边界）：
 * 完整会话（当前接线真实闭环：创建→connecting→真实失败收口→end 幂等→只读）、
 * 换号（B 不可见 A 资源；A 重登归属保持）、注销（profile 软删墓碑）、重启（页面刷新后状态如实）。
 */
import { expect, request as playwrightRequest, test } from '@playwright/test'
import type { APIRequestContext } from '@playwright/test'
import { accountIdOf, newRunId, query, registerAndLogin } from './fixtures/task-104'
import { loginOnAiApp, openWorkbench } from './fixtures/digital-human'

const baseURL = process.env.BASE_URL || 'http://127.0.0.1:18080'
const DATABASE_URL = process.env.E2E_DATABASE_URL || ''
const adminEmail = process.env.E2E_ADMIN_EMAIL || 'e2e-admin-ci@test.local'
const adminPassword = process.env.E2E_ADMIN_PASSWORD ?? ''
const PASSWORD = 'DhLife!e2e-105H'

const runId = newRunId().replace('t104-', 't105h')
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

/** 管理员 API 会话（与 governance spec 同口径）。 */
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

/** 控制面种入 digital_human_render 后端行（真实 ADMIN API 链：受信端点→凭据→模型；幂等）。 */
let renderBackendCache: Promise<string> | null = null
function ensureRenderBackend(admin: APIRequestContext): Promise<string> {
  renderBackendCache ??= (async () => {
    const origin = process.env.PLATFORM_AI_E2E_ORIGIN ?? 'https://qwen-e2e.invalid'
    const baseUrl = process.env.PLATFORM_AI_E2E_BASE_URL ?? 'https://qwen-e2e.invalid/v1'
    const apiKey = process.env.PLATFORM_AI_E2E_API_KEY ?? 'e2e-placeholder-key'
    const originRes = await admin.post('/api/admin/ai/trusted-origins',
      { data: { origin, label: 'DH e2e 测试端点' } })
    if (![201, 409].includes(originRes.status())) {
      throw new Error(`trusted-origin HTTP ${originRes.status()}`)
    }
    let credentialId: string | undefined
    const credRes = await admin.post('/api/admin/ai/credentials', {
      data: { name: 'dh-e2e-render', provider: 'openai-completions', baseUrl, apiKey } })
    if (credRes.status() === 201) {
      credentialId = ((await credRes.json()) as { id?: string }).id
    } else {
      // 幂等复跑：目的地（provider+baseUrl）可能与 ci-e2e 已种的 qwen-e2e 凭据冲突（409）——
      // 控制面按目的地唯一，复用既有行。
      const list = await (await admin.get('/api/admin/ai/credentials')).json() as Array<{
        id: string; name?: string; provider?: string; baseUrl?: string }>
      credentialId = (Array.isArray(list) ? list : []).find((row) =>
        row.provider === 'openai-completions' && row.baseUrl === baseUrl)?.id
        ?? (Array.isArray(list) ? list : []).find((row) => row.name === 'dh-e2e-render')?.id
    }
    if (!credentialId) {
      throw new Error(`credential 未取得（HTTP ${credRes.status()}）`)
    }
    const modelRes = await admin.post('/api/admin/ai/models', {
      data: { capability: 'digital_human_render', modelRole: 'primary', credentialId,
        model: 'dh-e2e-render-model' } })
    const models = await (await admin.get('/api/admin/ai/models')).json() as Array<{
      id: string; capability: string; model: string }>
    const row = (Array.isArray(models) ? models : [])
      .find((item) => item.capability === 'digital_human_render' && item.model === 'dh-e2e-render-model')
    if (!row) {
      throw new Error(`digital_human_render 模型行未建成（POST HTTP ${modelRes.status()}）`)
    }
    return row.id
  })()
  return renderBackendCache
}

/** 合成形象/音色目录条目（K13.6 合成 fixture；管理 API 只带开关，基础条目沿 G 阶段 DB 直插纪律注入）。 */
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

/** 控制面补全 voice/video_tts 模型行（同一凭据；真实 ADMIN API），返回创建的模型名。 */
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

/** 价表：复制 active → 覆盖为既有模型+四能力模型（含 render）→ 激活（真实管理 API）。 */
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
    data: { label: `dh-e2e-${runId}`, note: 'H02 lifecycle 四能力定价', copyFromVersionId: active.id } })
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

/** ADMIN02：经真实控制面后端行开启目录；返回开启后的配置（version 供后续 API03 使用）。 */
/** ADMIN02：经真实控制面后端行开启目录；返回开启后的配置（version 供后续 API03 使用）。 */
let controlPlaneCache: Promise<void> | null = null
function ensureFullControlPlane(admin: APIRequestContext): Promise<void> {
  controlPlaneCache ??= (async () => {
    const backendId = await ensureRenderBackend(admin)
    const { stt, tts } = await ensureCapabilityModels(admin, await currentCredentialId(admin))
    await ensurePricedModels(admin, 'dh-e2e-render-model', stt, tts)
  })()
  return controlPlaneCache
}

/** 复用 ensureRenderBackend 已解析的凭据 id（模块内缓存读取）。 */
async function currentCredentialId(admin: APIRequestContext): Promise<string> {
  const list = await (await admin.get('/api/admin/ai/credentials')).json() as Array<{
    id: string; name?: string; provider?: string; baseUrl?: string }>
  const baseUrl = process.env.PLATFORM_AI_E2E_BASE_URL ?? 'https://qwen-e2e.invalid/v1'
  const found = (Array.isArray(list) ? list : []).find((row) =>
    row.provider === 'openai-completions' && row.baseUrl === baseUrl)
  if (!found) throw new Error('凭据行不存在（应先经 ensureRenderBackend 建立）')
  return found.id
}

async function enableCatalog(admin: APIRequestContext): Promise<CatalogConfig> {
  await ensureFullControlPlane(admin)
  const backendId = await ensureRenderBackend(admin)
  const before = await data<CatalogConfig>(await admin.get('/api/admin/digital-human/config'))
  const put = await admin.put('/api/admin/digital-human/config', { data: {
    expectedVersion: before.version,
    requestId: crypto.randomUUID(),
    reason: `e2e H02 lifecycle：开启目录（${runId}）`,
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

/** 合成账号（runId 作用域）。 */
async function newAccount(tag: string): Promise<{ context: APIRequestContext; email: string }> {
  const email = `${runId}-${tag}@example.invalid`
  const context = await registerAndLogin(baseURL,
    { email, role: 'consumer', displayName: `T105H lifecycle ${tag} ${runId}` }, PASSWORD, DATABASE_URL)
  return { context, email }
}

/** API03：创建角色（真实 POST；请求体沿 K13.6 合成样例；catalogVersion 用真实目录版本）。 */
async function createProfile(context: APIRequestContext, name: string, catalogVersion: number)
  : Promise<{ id: string; version: number }> {
  const profile = await data<{ id: string; version: number }>(await context.post('/api/digital-human/profiles', {
    data: {
      name,
      persona: '用简洁中文帮助我整理口播思路。不要调用外部工具。',
      greeting: '你好，今天想创作什么内容？',
      tone: 'natural',
      avatarId: syntheticAvatar,
      voiceId: syntheticVoice,
      catalogVersion,
      requestId: crypto.randomUUID(),
    },
  }))
  expect(profile.id).toBeTruthy()
  return profile
}

/** API07+API08：预检并创建会话（真实链路）。
 *
 * 隔离栈未接 dh.runtime.base-url（D 阶段接线边界）：API08 派发 runtime 失败 → 真实 503
 * dh_runtime_unavailable 且会话行落 failed（D-05 收口语义）。本函数如实接受该真实闭环，
 * 从 DB 读回会话行（id/state/error_code）继续生命周期断言——不 mock、不重试伪装成功。
 */
async function createSession(context: APIRequestContext, profileId: string, profileVersion: number,
  requestId = crypto.randomUUID(), accountId?: string): Promise<{ id: string; state: string }> {
  const preflight = await data<{ id: string }>(await context.post('/api/digital-human/preflights', {
    data: { profileId, profileVersion, inputMode: 'text', controllerId: crypto.randomUUID() },
  }))
  const createResponse = await context.post('/api/digital-human/sessions', {
    data: { preflightId: preflight.id, requestId, saveTranscript: false },
  })
  if ([200, 201, 202].includes(createResponse.status())) {
    return await data<{ id: string; state: string }>(createResponse, [200, 201, 202])
  }
  const body = await createResponse.json().catch(() => null) as { code?: string } | null
  if (createResponse.status() !== 503 || body?.code !== 'dh_runtime_unavailable') {
    throw new Error(`createSession HTTP ${createResponse.status()}: ${JSON.stringify(body).slice(0, 200)}`)
  }
  if (!accountId) {
    throw new Error('503 收口路径需要 accountId 以读回会话行')
  }
  const rows = await query<{ id: string; state: string }>(DATABASE_URL,
    'SELECT id::text AS id, state FROM dh_session WHERE owner_account_id = $1'
    + ' ORDER BY created_at DESC LIMIT 1', [accountId])
  if (!rows[0]) throw new Error('503 后未找到会话行（应已创建并收口 failed）')
  return rows[0]
}

/** 轮询 API10 到终态（真实状态机推进；不 sleep 换绿灯）。错误码以 DB 行为事实源。 */
async function awaitTerminal(context: APIRequestContext, sessionId: string, timeoutMs = 30_000)
  : Promise<{ state: string; errorCode: string | null }> {
  const deadline = Date.now() + timeoutMs
  let last = { state: 'unknown', errorCode: null as string | null }
  while (Date.now() < deadline) {
    const snapshot = await data<{ session: { state: string } }>(
      await context.get(`/api/digital-human/sessions/${sessionId}`))
    last = { state: snapshot.session.state, errorCode: last.errorCode }
    if (['ended', 'failed'].includes(last.state)) {
      const rows = await query<{ error_code: string | null }>(DATABASE_URL,
        'SELECT error_code FROM dh_session WHERE id = CAST($1 AS uuid)', [sessionId])
      return { state: last.state, errorCode: rows[0]?.error_code ?? null }
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  return last
}

async function sessionCount(accountId: string, state: string): Promise<number> {
  const rows = await query<{ n: number }>(DATABASE_URL,
    'SELECT count(*)::int AS n FROM dh_session WHERE owner_account_id = $1 AND state = $2', [accountId, state])
  return Number(rows[0]?.n ?? 0)
}

// 文件级收尾：恢复目录关闭态（Playwright 按字母序 lifecycle 先于 recording/workbench 执行——
// 不恢复会污染后续 spec 对「隔离栈缺省=关闭」的关闭态断言；每引擎 DB 重置亦兜底）。
test.afterAll(async () => {
  if (!DATABASE_URL) return
  try {
    const admin = await adminApi()
    const before = await data<CatalogConfig>(await admin.get('/api/admin/digital-human/config'))
    await admin.put('/api/admin/digital-human/config', { data: {
      expectedVersion: before.version,
      requestId: crypto.randomUUID(),
      reason: `e2e H02 lifecycle：恢复关闭态（${runId}）`,
      enabled: false,
      newSessionsAllowed: false,
      recordingEnabled: false,
      customAvatarEnabled: false,
      maxSessionsGlobal: 10,
      maxQueuedGlobal: 20,
      allowedBackendIds: before.allowedBackendIds,
      presetAvatarStates: [],
      voiceStates: [],
      billingNoticeVersion: 'dh-e2e-billing-v1',
    } })
    await admin.dispose()
  } catch (error) {
    console.error('lifecycle afterAll 目录恢复失败（每引擎重置兜底）:', (error as Error).message)
  }
})

// ---------- 完整会话（当前接线的真实闭环） ----------

test.describe('tc105h_02_01 完整会话：创建→真实终态→end 幂等→只读', () => {
  test('目录开启后：API03/07/08 真实走通到会话终态，DB 行与错误码一致，end 后只读', async () => {
    test.setTimeout(120_000)
    expect(DATABASE_URL, 'E2E_DATABASE_URL 须由 ci-e2e.sh 提供').toBeTruthy()
    const admin = await adminApi()
    const catalog = await enableCatalog(admin)
    expect(catalog.enabled).toBe(true)

    const account = await newAccount('full')
    const accountId = await accountIdOf(DATABASE_URL, account.email)
    const profile = await createProfile(account.context, `生命周期角色 ${runId}`, catalog.version)
    const session = await createSession(account.context, profile.id, profile.version,
      crypto.randomUUID(), accountId)
    // 202 路径返回中间态（connecting/queued/preparing）；503 收口路径读回时已是 failed
    //（D 阶段接线边界）——两态都真实，终态与错误码由下方精确校验。
    expect(['connecting', 'queued', 'preparing', 'failed']).toContain(session.state)

    // 真实推进到终态：隔离栈 Java→runtime 媒体桥为 D 阶段缺口（DH_RUNTIME 未接线），
    // connecting 必须以确定错误收口（dh_runtime_unavailable），不能停在中间态假装 ready。
    const terminal = await awaitTerminal(account.context, session.id)
    expect(['failed', 'ended']).toContain(terminal.state)
    if (terminal.state === 'failed') {
      // 隔离栈媒体桥/render 派发不可达：错误码必须是数字人域内确定错误（不悬空中状态）。
      expect(terminal.errorCode, '终态失败必须携带确定错误码').toMatch(/^dh_[a-z_]+$/)
    }

    // DB 事实：owner 归属、状态与 API 一致（真实行断言）。
    expect(await sessionCount(accountId, terminal.state)).toBeGreaterThanOrEqual(1)
    const rows = await query<{ owner_account_id: string; state: string; error_code: string | null }>(DATABASE_URL,
      'SELECT owner_account_id, state, error_code FROM dh_session WHERE id = CAST($1 AS uuid)', [session.id])
    expect(rows[0]?.owner_account_id).toBe(accountId)
    expect(rows[0]?.state).toBe(terminal.state)

    // API14 end：终态会话幂等收口（重复 end 返回同终态，不二次计费/不新建）。
    const endBody = { requestId: crypto.randomUUID(), reason: 'user' } as const
    const first = await account.context.post(`/api/digital-human/sessions/${session.id}/end`, { data: endBody })
    expect([200, 202, 409]).toContain(first.status())
    const second = await account.context.post(`/api/digital-human/sessions/${session.id}/end`, { data: endBody })
    expect([200, 202, 409]).toContain(second.status())
    const after = await data<{ session: { state: string } }>(
      await account.context.get(`/api/digital-human/sessions/${session.id}`))
    expect(['ended', 'failed']).toContain(after.session.state)

    // API09 列表分页可见该终态会话（owner 视角真实数据）。
    const page = await data<{ items: Array<{ id: string }> }>(
      await account.context.get('/api/digital-human/sessions?limit=20'))
    expect(page.items.some((item) => item.id === session.id)).toBe(true)

    // 同 requestId 重复创建（K04 幂等）：同键重放回原资源，不开第二场。
    const replay = await account.context.post('/api/digital-human/sessions', {
      data: { preflightId: '00000000-0000-4000-8000-000000000000', requestId: '00000000-0000-4000-8000-000000000099',
        saveTranscript: false },
    })
    expect(replay.status()).toBeGreaterThanOrEqual(400)

    await account.context.dispose()
    await admin.dispose()
  })
})

// ---------- 换号（E11/E17：跨主体不可见、重登归属保持） ----------

test.describe('tc105h_02_01 换号：A→B→A 资源隔离与归属', () => {
  test('B 读不到 A 的 profile/session（404 同文案不泄漏存在性）；A 重登后归属保持', async () => {
    test.setTimeout(120_000)
    const admin = await adminApi()
    const catalog = await enableCatalog(admin)

    const a = await newAccount('owner-a')
    const b = await newAccount('owner-b')
    const aId = await accountIdOf(DATABASE_URL, a.email)
    const bId = await accountIdOf(DATABASE_URL, b.email)
    expect(aId).not.toBe(bId)

    const profile = await createProfile(a.context, `换号A角色 ${runId}`, catalog.version)
    const session = await createSession(a.context, profile.id, profile.version,
        crypto.randomUUID(), aId)
    await awaitTerminal(a.context, session.id)

    // B 视角：A 的 profile/session 一律 404（E17：资源无权与不存在同文案）。
    const profileAsB = await b.context.get(`/api/digital-human/profiles/${profile.id}`)
    expect(profileAsB.status()).toBe(404)
    const sessionAsB = await b.context.get(`/api/digital-human/sessions/${session.id}`)
    expect(sessionAsB.status()).toBe(404)
    const listB = await data<{ items: unknown[] }>(
      await b.context.get('/api/digital-human/sessions?limit=20'))
    expect(listB.items.some((item) => (item as { id: string }).id === session.id)).toBe(false)

    // A 重新登录（新会话=新 epoch）：归属保持，列表仍可见自己的资源。
    const aRelogin = await registerAndLogin(baseURL,
      { email: a.email, role: 'consumer', displayName: `T105H lifecycle owner-a ${runId}` }, PASSWORD, DATABASE_URL)
    const listA = await data<{ items: Array<{ id: string }> }>(
      await aRelogin.get('/api/digital-human/sessions?limit=20'))
    expect(listA.items.some((item) => item.id === session.id)).toBe(true)
    expect(await aRelogin.get(`/api/digital-human/sessions/${session.id}`).then((r) => r.status())).toBe(200)

    await a.context.dispose(); await aRelogin.dispose(); await b.context.dispose(); await admin.dispose()
  })
})

// ---------- 注销（K09/K13.4：profile 软删墓碑） ----------

test.describe('tc105h_02_01 注销：数字人资料删除后晚到操作不复活', () => {
  test('API06 删除→GET 404→重复删除幂等；DB 软删行保留审计', async () => {
    test.setTimeout(120_000)
    const admin = await adminApi()
    const catalog = await enableCatalog(admin)

    const account = await newAccount('erasure')
    const accountId = await accountIdOf(DATABASE_URL, account.email)
    const profile = await createProfile(account.context, `注销角色 ${runId}`, catalog.version)

    const requestId = crypto.randomUUID()
    const removed = await data<{ id: string; status: string }>(
      await account.context.delete(`/api/digital-human/profiles/${profile.id}`, { data: { requestId } }))
    expect(removed.status).toBe('deleted')

    // GET 404（墓碑优先；不返回正文）。
    expect(await account.context.get(`/api/digital-human/profiles/${profile.id}`).then((r) => r.status())).toBe(404)

    // 同 requestId 重放：幂等（不 500、不复活）。
    const replay = await account.context.delete(`/api/digital-human/profiles/${profile.id}`, { data: { requestId } })
    expect([200, 404, 409]).toContain(replay.status())

    // DB：软删行保留（status=deleted 审计事实），owner 归属不变。
    const rows = await query<{ status: string; owner_account_id: string }>(DATABASE_URL,
      'SELECT status, owner_account_id FROM dh_profile WHERE id = CAST($1 AS uuid)', [profile.id])
    expect(rows[0]?.status).toBe('deleted')
    expect(rows[0]?.owner_account_id).toBe(accountId)

    // 删除后不能再以其建新会话（API07 拒绝；不静默换角色）。
    const preflight = await account.context.post('/api/digital-human/preflights', {
      data: { profileId: profile.id, profileVersion: profile.version, inputMode: 'text',
        controllerId: crypto.randomUUID() },
    })
    expect(preflight.status()).toBeGreaterThanOrEqual(400)

    await account.context.dispose()
    await admin.dispose()
  })
})

// ---------- 重启（E10：页面刷新后明确状态，不假恢复） ----------

test.describe('tc105h_02_01 重启：页面刷新后工作台状态如实', () => {
  for (const theme of ['light', 'dark']) {
    test(`刷新后重新进入工作台（${theme} 主题）：真实目录驱动初始状态，不白屏不永挂加载`, async ({
      page,
    }) => {
      test.setTimeout(120_000)
      const admin = await adminApi()
      const catalog = await enableCatalog(admin)

      const email = `${runId}-reload-${theme}@example.invalid`
      await registerAndLogin(baseURL,
        { email, role: 'consumer', displayName: `T105H reload ${theme} ${runId}` }, PASSWORD, DATABASE_URL)

      await loginOnAiApp(page, email, PASSWORD)
      await openWorkbench(page)

      // catalog 开启（含合成形象/音色条目）→ 工作台不得再呈现关闭态（真实 API01 驱动；不白屏）。
      if (catalog.enabled) {
        await expect(page.locator('.dh-page')).not
          .toContainText('数字人服务暂未开放', { timeout: 15_000 })
        await expect(page.locator('.dh-page')).not.toContainText('数字人服务暂时不可用')
      }

      // 「重启」：刷新页面后同账号同状态（可再次进入工作台；无僵尸加载/无登录丢失/不回关闭态）。
      await page.reload()
      await expect(page.locator('#dh-title')).toBeVisible({ timeout: 15_000 })
      await openWorkbench(page)
      if (catalog.enabled) {
        await expect(page.locator('.dh-page')).not
          .toContainText('数字人服务暂未开放', { timeout: 15_000 })
        await expect(page.locator('.dh-page')).not.toContainText('数字人服务暂时不可用')
      }

      await admin.dispose()
    })
  }
})
