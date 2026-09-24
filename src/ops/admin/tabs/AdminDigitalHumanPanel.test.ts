// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import AdminDigitalHumanPanel from './AdminDigitalHumanPanel.vue'
import { DEFAULT_TAB_ROLES, TAB_REGISTRY, TAB_ROLES, type AdminSection } from '../adminTabs'
import { useAuthStore } from '../../../stores/auth'
import type { AuthUser } from '../../../types/auth'
import type { DhAdminConfig, DhAdminSessionRow, DhInvocationRow } from '../composables/useDigitalHumanAdmin'

/**
 * 任务书 #105G C105G-04：数字人治理面板——入口权限（tc105g_04_01）与核对确认
 * （tc105g_04_03）。真实 Pinia + 真实账号票据；只 mock 最外层 provider（fetch）。
 * 版本冲突/失活换号/串行轮询在 useDigitalHumanAdmin.test.ts（tc105g_04_02/04）。
 */

const adminUser: AuthUser = { id: 'admin-1', email: 'ops@qa.invalid', displayName: '管', role: 'user', roles: ['platform_admin'] }

const configFixture: DhAdminConfig = {
  version: 3, enabled: true, newSessionsAllowed: true, recordingEnabled: true, customAvatarEnabled: false,
  maxSessionsGlobal: 10, maxQueuedGlobal: 20, allowedBackendIds: ['backend-rt'],
  presetAvatarStates: [{ id: 'avatar-1', enabled: true }], voiceStates: [{ id: 'voice-1', enabled: true }],
  billingNoticeVersion: 'dh-billing-v1',
}

const sessionFixture: DhAdminSessionRow = {
  id: '11111111-1111-4111-8111-111111111111', profileId: '22222222-2222-4222-8222-222222222222',
  profileNameAtCreation: '门店迎宾', state: 'listening', createdAt: '2026-09-24T08:00:00Z', endedAt: null,
  hasSavedTranscript: true, recordingCount: 1, savedAssetCount: 2,
  billing: { confirmedCents: 120, platformCostCents: 60, subsidizedCents: 0, pendingCount: 1, priceTableVersion: 1 },
  workerId: 'worker-1', errorCode: null, cleanupPending: false,
  phaseMetrics: [{ phase: 'tts', lastDurationMs: 210, p50Ms: 180, p95Ms: 420, sampleCount: 9 }],
}

const unknownFixture: DhInvocationRow = {
  id: '33333333-3333-4333-8333-333333333333', sessionId: sessionFixture.id, stage: 'llm', state: 'unknown',
  settlementState: 'pending', version: 2, providerModelLabel: 'it-dh-g3-cred', createdAt: '2026-09-24T08:05:00Z',
  deadlineAt: null, usage: null, confirmedCents: null, errorCode: null,
}

function ok(data: unknown): Response {
  return { ok: true, status: 200, json: async () => ({ success: true, data }) } as unknown as Response
}

interface Deferred { promise: Promise<Response>; resolve: (value: Response) => void }

function deferredResponse(): Deferred {
  let resolve!: (value: Response) => void
  const promise = new Promise<Response>((settle) => { resolve = settle })
  return { promise, resolve }
}

beforeEach(() => {
  setActivePinia(createPinia())
  useAuthStore().currentUser = adminUser
})

afterEach(() => {
  vi.unstubAllGlobals()
})

enableAutoUnmount(afterEach)

/** 面板加载态 mock：三读回 fixture；reconcile POST 挂起由用例控制。 */
function stubPanelFetch(options: { reconcile?: Deferred; reconcileResult?: DhInvocationRow } = {}): {
  calls: { url: string; method: string; body: Record<string, unknown> | null }[]
  reconcile: Deferred
} {
  const calls: { url: string; method: string; body: Record<string, unknown> | null }[] = []
  const reconcile = options.reconcile ?? deferredResponse()
  const reconcileResult = options.reconcileResult
    ?? { ...unknownFixture, state: 'succeeded' as const, settlementState: 'settled' as const, version: 3 }
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit): Promise<Response> => {
    const method = init?.method ?? 'GET'
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) as Record<string, unknown> : null
    calls.push({ url, method, body })
    if (url === '/api/admin/digital-human/config' && method === 'GET') return ok(configFixture)
    if (url === '/api/admin/digital-human/config' && method === 'PUT') {
      return { ok: false, status: 409, text: async () => JSON.stringify({ success: false, error: '配置版本已变化，当前版本：4。', code: 'dh_version_conflict' }), json: async () => ({}) } as unknown as Response
    }
    if (url.startsWith('/api/admin/digital-human/sessions/') && url.endsWith('/terminate')) {
      return ok({ id: sessionFixture.id, state: 'ended' })
    }
    if (url.startsWith('/api/admin/digital-human/sessions')) return ok({ items: [sessionFixture], nextCursor: null })
    if (url.startsWith('/api/admin/digital-human/invocations/') && url.endsWith('/reconcile')) {
      return options.reconcile ? reconcile.promise : ok(reconcileResult)
    }
    if (url.startsWith('/api/admin/digital-human/invocations')) return ok({ items: [unknownFixture], nextCursor: null })
    throw new Error(`unexpected request: ${url}`)
  }))
  return { calls, reconcile }
}

describe('tc105g_04_01 · 入口权限（四治理角色）', () => {
  /** 镜像 useAdminUrlState.canSeeTab / auth.hasBackendRole 的角色判定。 */
  function canSee(roles: string[], key: AdminSection): boolean {
    if (roles.includes('platform_admin')) return true
    return (TAB_ROLES[key] ?? DEFAULT_TAB_ROLES).some((role) => roles.includes(role))
  }

  test('registry：digital-human 挂内容与 AI 组，仅 platform_admin 可见（逐角色参数化）', () => {
    const tab = TAB_REGISTRY.find((entry) => entry.key === 'digital-human')
    expect(tab).toBeDefined()
    expect(tab?.label).toBe('数字人')
    expect(tab?.group).toBe('content-ai')
    expect(tab?.roles).toBeUndefined() // 未列 roles = 默认 platform_admin 专属（DEFAULT_TAB_ROLES）

    // 四治理角色逐项：只有 platform_admin 显示，其余三角色无扩权。
    expect(canSee(['platform_admin'], 'digital-human')).toBe(true)
    expect(canSee(['customer_service'], 'digital-human')).toBe(false)
    expect(canSee(['risk'], 'digital-human')).toBe(false)
    expect(canSee(['content_reviewer'], 'digital-human')).toBe(false)
    // 组合角色也不经由其他角色旁路进入。
    expect(canSee(['customer_service', 'risk', 'content_reviewer', 'finance', 'merchant_reviewer'], 'digital-human')).toBe(false)
  })

  test('platform_admin 挂载即读三个治理端点；403 dh_admin_required 显示权限错误不扩权', async () => {
    const { calls } = stubPanelFetch()
    const wrapper = mount(AdminDigitalHumanPanel)
    await flushPromises()

    const gets = calls.filter((call) => call.method === 'GET').map((call) => call.url)
    expect(gets).toContain('/api/admin/digital-human/config')
    expect(gets).toContain('/api/admin/digital-human/sessions?limit=20')
    expect(gets).toContain('/api/admin/digital-human/invocations?limit=20')
    wrapper.unmount()

    // 角色不足（服务端 403 dh_admin_required）：面板如实显示错误，不吞掉、不本地放行。
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: false, status: 403,
      text: async () => JSON.stringify({ success: false, error: '需要平台管理员权限。', code: 'dh_admin_required' }),
      json: async () => ({}),
    }) as unknown as Response))
    const denied = mount(AdminDigitalHumanPanel)
    await flushPromises()
    expect(denied.text()).toContain('平台管理员')
    expect(denied.find('[data-test="dh-config-form"]').exists()).toBe(true) // 结构仍在，但无数据无写入口放行
  })
})

describe('tc105g_04_03 · 核对确认（unknown 与缺证据表单）', () => {
  test('unknown 不显示为正常；无证据禁提交；有证据一次请求；待完成不显示成功；成功示服务端回读', async () => {
    const { calls, reconcile } = stubPanelFetch({ reconcile: deferredResponse() })
    const wrapper = mount(AdminDigitalHumanPanel)
    await flushPromises()

    // unknown 以警示呈现，不是正常状态标签。
    expect(wrapper.get('[data-test="dh-unknown-badge"]').text()).toContain('需人工核对')

    // 打开核对表单：证据为空 → 提交禁用。
    await wrapper.get(`[data-test="dh-reconcile-open-${unknownFixture.id}"]`).trigger('click')
    const submit = wrapper.get('[data-test="dh-reconcile-submit"]')
    expect(submit.attributes('disabled')).toBeDefined()
    // 缺原因同样禁用：只填证据。
    await wrapper.get('[data-test="dh-evidence"]').setValue('prov-req-889')
    expect(wrapper.get('[data-test="dh-reconcile-submit"]').attributes('disabled')).toBeDefined()
    // 填用量与原因后才可提交。
    await wrapper.get('[data-test="dh-usage-inputTokens"]').setValue('120')
    await wrapper.get('[data-test="dh-reconcile-reason"]').setValue('供应商后台确认成功')
    expect(wrapper.get('[data-test="dh-reconcile-submit"]').attributes('disabled')).toBeUndefined()

    // 重复提交：一次请求（提交锁同动作）；请求体不带任何金额字段。
    await wrapper.get('[data-test="dh-reconcile-form"]').trigger('submit')
    await wrapper.get('[data-test="dh-reconcile-submit"]').trigger('click')
    await wrapper.get('[data-test="dh-reconcile-submit"]').trigger('click')
    await flushPromises()
    const posts = calls.filter((call) => call.url.endsWith('/reconcile'))
    expect(posts).toHaveLength(1)
    expect(posts[0].body).toMatchObject({ outcome: 'succeeded', providerEvidenceRef: 'prov-req-889', expectedVersion: 2 })
    expect(Object.keys(posts[0].body!)).not.toContain('confirmedCents')
    expect(Object.keys(posts[0].body!).some((key) => /cents|amount/i.test(key))).toBe(false)

    // 待完成：显示核对中，不显示成功/服务端回读。
    expect(wrapper.text()).toContain('核对中')
    expect(wrapper.find('[data-test="dh-reconcile-result"]').exists()).toBe(false)

    // 服务端回读落地：展示真实 state/settlementState（非 optimistic）。
    reconcile.resolve(ok({ ...unknownFixture, state: 'succeeded', settlementState: 'settled', version: 3 }))
    await flushPromises()
    expect(wrapper.get('[data-test="dh-reconcile-result"]').text()).toContain('succeeded')
    expect(wrapper.get('[data-test="dh-reconcile-result"]').text()).toContain('settled')
    wrapper.unmount()
  })

  test('会话表仅脱敏元数据；终止两步确认（影响说明+必填原因）后一次请求、示服务端终态', async () => {
    const { calls } = stubPanelFetch()
    const wrapper = mount(AdminDigitalHumanPanel)
    await flushPromises()

    // 元数据可见：状态/时长/计数/计费。
    expect(wrapper.get('[data-test="dh-session-state"]').text()).toContain('listening')
    expect(wrapper.text()).toContain('门店迎宾')
    expect(wrapper.text()).toContain('1 / 2') // 录制/素材计数
    // 隐私红线：不渲染 prompt/字幕正文/头像原图/SDP/grant 相关内容或列头。
    const lower = wrapper.text().toLowerCase()
    for (const forbidden of ['sdp', 'grant', 'prompt', '字幕内容', '头像原图']) {
      expect(lower).not.toContain(forbidden.toLowerCase())
    }

    // 终止两步：先展开影响说明；原因必填；确认后恰一次 POST。
    await wrapper.get(`[data-test="dh-terminate-open-${sessionFixture.id}"]`).trigger('click')
    expect(wrapper.get('[data-test="dh-terminate-impact"]').text()).toContain('影响')
    expect(wrapper.get('[data-test="dh-terminate-submit"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-test="dh-terminate-reason"]').setValue('上游故障紧急止损')
    expect(wrapper.get('[data-test="dh-terminate-submit"]').attributes('disabled')).toBeUndefined()
    // 确认后恰一次 POST；确认区即时收起（在途重复确认无入口），行按钮进入终止中锁。
    await wrapper.get('[data-test="dh-terminate-submit"]').trigger('click')
    await flushPromises()
    const terminates = calls.filter((call) => call.url.endsWith('/terminate'))
    expect(terminates).toHaveLength(1)
    expect(wrapper.find('[data-test="dh-terminate-confirm"]').exists()).toBe(false)
    expect(terminates[0].body).toMatchObject({ reason: '上游故障紧急止损' })
    // 服务端终态（ended）才显示完成。
    expect(wrapper.get('[data-test="dh-terminate-notice"]').text()).toContain('已终止')
    wrapper.unmount()
  })
})
