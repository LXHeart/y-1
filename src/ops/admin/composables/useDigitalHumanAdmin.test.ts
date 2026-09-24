// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { useDigitalHumanAdmin, type DhAdminConfig, type DhInvocationRow, type DhAdminSessionRow } from './useDigitalHumanAdmin'
import { useAuthStore } from '../../../stores/auth'
import { useAccountSessionStore } from '../../../stores/account-session'
import type { AuthUser } from '../../../types/auth'

/**
 * 任务书 #105G C105G-04：数字人治理台状态源——串行轮询/请求代次/版本冲突/失活换号。
 * 真实 effectScope + 真实 auth/account-session store（账号票据真实实现）；只 mock 最外层
 * provider（fetch），假时钟推进 5s 轮询节拍，不 sleep 换绿灯。
 */

type AdminState = ReturnType<typeof useDigitalHumanAdmin>

const userA: AuthUser = { id: 'admin-a', email: 'a@qa.invalid', displayName: '甲', role: 'user' }
const userB: AuthUser = { id: 'admin-b', email: 'b@qa.invalid', displayName: '乙', role: 'user' }

function serverConfig(version: number, enabled: boolean): DhAdminConfig {
  return {
    version, enabled, newSessionsAllowed: enabled, recordingEnabled: false, customAvatarEnabled: false,
    maxSessionsGlobal: 10, maxQueuedGlobal: 20, allowedBackendIds: ['backend-rt'],
    presetAvatarStates: [{ id: 'avatar-1', enabled: true }], voiceStates: [{ id: 'voice-1', enabled: true }],
    billingNoticeVersion: 'dh-billing-v1',
  }
}

const sessionRow: DhAdminSessionRow = {
  id: '11111111-1111-4111-8111-111111111111', profileId: '22222222-2222-4222-8222-222222222222',
  profileNameAtCreation: '测试形象', state: 'ready', createdAt: '2026-09-24T08:00:00Z', endedAt: null,
  hasSavedTranscript: false, recordingCount: 0, savedAssetCount: 0,
  billing: { confirmedCents: 0, platformCostCents: 0, subsidizedCents: 0, pendingCount: 1, priceTableVersion: 1 },
  workerId: 'worker-1', errorCode: null, cleanupPending: false, phaseMetrics: [],
}

const unknownInvocation: DhInvocationRow = {
  id: '33333333-3333-4333-8333-333333333333', sessionId: sessionRow.id, stage: 'llm', state: 'unknown',
  settlementState: 'pending', version: 2, providerModelLabel: 'it-dh-g3-cred', createdAt: '2026-09-24T08:05:00Z',
  deadlineAt: null, usage: null, confirmedCents: null, errorCode: null,
}

function ok(data: unknown): Response {
  return { ok: true, status: 200, json: async () => ({ success: true, data }) } as unknown as Response
}

function fail(status: number, error: string, code?: string): Response {
  const body = { success: false, error, ...(code ? { code } : {}) }
  return {
    ok: false, status,
    text: async () => JSON.stringify(body),
    json: async () => body,
  } as unknown as Response
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((settle) => { resolve = settle })
  return { promise, resolve }
}

interface CallLog { url: string; method: string; body: Record<string, unknown> | null; signal: AbortSignal | undefined }

/** K10 服务端仿真：config 单行乐观锁（PUT expectedVersion）；一次性 PUT 故障；可武装挂起下一轮。 */
function makeServerHarness(initial: DhAdminConfig): {
  log: CallLog[]
  setConfig: (next: DhAdminConfig) => void
  failNextPut: () => void
  armHold: () => { release: () => void }
} {
  const log: CallLog[] = []
  let current = initial
  const sessions = [sessionRow]
  const invocations: DhInvocationRow[] = [unknownInvocation]
  let putFailure: Response | null = null
  let holdArmed = false
  const held: { resolve: (value: Response) => void }[] = []

  const fetchMock = vi.fn(async (url: string, init?: RequestInit): Promise<Response> => {
    const method = init?.method ?? 'GET'
    const parsed = typeof init?.body === 'string' ? JSON.parse(init.body) as Record<string, unknown> : null
    log.push({ url, method, body: parsed, signal: (init as { signal?: AbortSignal } | undefined)?.signal })

    // 武装挂起：仅挂起 GET /config（该轮三读 Promise.all 整体在途）。
    if (holdArmed && url === '/api/admin/digital-human/config' && method === 'GET') {
      holdArmed = false
      const hold = deferred<Response>()
      held.push(hold)
      return hold.promise
    }
    if (url === '/api/admin/digital-human/config' && method === 'GET') return ok(current)
    if (url === '/api/admin/digital-human/config' && method === 'PUT') {
      if (putFailure) {
        const failure = putFailure
        putFailure = null
        return failure
      }
      const expected = parsed?.expectedVersion as number
      if (expected !== current.version) {
        return fail(409, `配置版本已变化，当前版本：${current.version}。`, 'dh_version_conflict')
      }
      current = { ...current, version: current.version + 1, enabled: parsed!.enabled as boolean }
      return ok(current)
    }
    if (url.startsWith('/api/admin/digital-human/sessions/') && url.endsWith('/terminate')) {
      return ok({ id: sessionRow.id, state: 'ended' })
    }
    if (url.startsWith('/api/admin/digital-human/sessions')) return ok({ items: sessions, nextCursor: null })
    if (url.startsWith('/api/admin/digital-human/invocations/') && url.endsWith('/reconcile')) {
      return ok({ ...unknownInvocation, state: 'succeeded', settlementState: 'settled', version: unknownInvocation.version + 1 })
    }
    if (url.startsWith('/api/admin/digital-human/invocations')) return ok({ items: invocations, nextCursor: null })
    return fail(500, `unexpected request: ${url}`)
  })

  vi.stubGlobal('fetch', fetchMock)
  return {
    log,
    setConfig: (next) => { current = next },
    failNextPut: () => { putFailure = fail(502, '上游暂时不可用，请重试。') },
    armHold: () => {
      holdArmed = true
      return {
        release: () => {
          for (const hold of held.splice(0)) hold.resolve(ok(current))
        },
      }
    },
  }
}

interface MountedAdmin { api: AdminState; stop: () => void }

function mountAdmin(): MountedAdmin {
  const scope = effectScope()
  let api!: AdminState
  scope.run(() => {
    api = useDigitalHumanAdmin(useAccountSessionStore())
  })
  return { api, stop: () => scope.stop() }
}

beforeEach(() => {
  vi.useFakeTimers()
  setActivePinia(createPinia())
  useAuthStore().currentUser = userA
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

async function flush(): Promise<void> {
  await vi.advanceTimersByTimeAsync(0)
  await nextTick()
}

describe('轮询基础（串行 5s，前次完成后再计时）', () => {
  it('激活立即一轮三读；完成后 5s 才下一轮（不叠加）', async () => {
    const harness = makeServerHarness(serverConfig(3, false))
    const { api, stop } = mountAdmin()

    api.notifyActivated()
    await flush()
    expect(harness.log.map((call) => call.url)).toEqual([
      '/api/admin/digital-human/config',
      '/api/admin/digital-human/sessions?limit=20',
      '/api/admin/digital-human/invocations?limit=20',
    ])
    expect(api.config.value?.version).toBe(3)
    expect(api.sessions.value).toHaveLength(1)
    expect(api.pendingReconcileCount.value).toBe(1)
    expect(api.configDraft.value?.enabled).toBe(false)
    expect(api.draftBaseVersion.value).toBe(3)

    await vi.advanceTimersByTimeAsync(4_999)
    expect(harness.log).toHaveLength(3)
    await vi.advanceTimersByTimeAsync(1)
    expect(harness.log).toHaveLength(6)
    stop()
  })

  it('轮询失败保留既有数据、显示错误并停排；手动刷新恢复', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => fail(403, '需要平台管理员权限。', 'dh_admin_required')))
    const { api, stop } = mountAdmin()
    api.notifyActivated()
    await flush()
    expect(api.error.value).toContain('平台管理员')
    expect(vi.getTimerCount()).toBe(0)
    stop()

    const harness = makeServerHarness(serverConfig(3, false))
    const second = mountAdmin()
    second.api.notifyActivated()
    await flush()
    await second.api.refresh()
    expect(harness.log.filter((call) => call.method === 'GET').length).toBe(6)
    expect(second.api.error.value).toBeNull()
    second.stop()
  })
})

describe('tc105g_04_02 · 版本冲突（两管理员同版本顺序提交）', () => {
  it('后者 409 保表单并提示重新加载；轮询真相不静默覆盖草稿；reloadDraft 才重抄；重抄后可保存', async () => {
    const harness = makeServerHarness(serverConfig(3, false))
    const { api, stop } = mountAdmin()
    api.notifyActivated()
    await flush()
    expect(api.config.value?.version).toBe(3)

    // 管理员 B（本面板）基于 v3 起草修改。
    api.configDraft.value!.enabled = true
    api.configDraft.value!.reason = '开启数字人服务'

    // 管理员 A 在别处保存 → 服务端已到 v4（enabled 仍 false）。
    harness.setConfig(serverConfig(4, false))
    await vi.advanceTimersByTimeAsync(5_000)
    expect(api.config.value?.version).toBe(4)
    // 轮询带回服务端真相，但草稿不被静默覆盖（仍保留 B 的编辑与基线 v3）。
    expect(api.configDraft.value?.enabled).toBe(true)
    expect(api.configDraft.value?.reason).toBe('开启数字人服务')
    expect(api.draftBaseVersion.value).toBe(3)

    // B 持 v3 提交 → 409 dh_version_conflict：不抛散、保表单、提示重新加载。
    await expect(api.updateConfig()).resolves.toBe(false)
    expect(api.updateConflict.value).toContain('重新加载')
    expect(api.updating.value).toBe(false)
    expect(api.configDraft.value?.enabled).toBe(true)
    expect(api.configDraft.value?.reason).toBe('开启数字人服务')

    // 显式重新加载：草稿按服务端 v4 重抄、基线对齐、冲突提示清空。
    api.reloadDraft()
    expect(api.configDraft.value?.enabled).toBe(false)
    expect(api.configDraft.value?.reason).toBe('')
    expect(api.draftBaseVersion.value).toBe(4)
    expect(api.updateConflict.value).toBeNull()

    // 重抄后按 v4 提交成功：PUT 携带 expectedVersion=4 + requestId，服务端版本+1。
    api.configDraft.value!.enabled = true
    api.configDraft.value!.reason = '基于 v4 重新开启'
    await expect(api.updateConfig()).resolves.toBe(true)
    const put = harness.log.filter((call) => call.method === 'PUT').pop()!
    expect(put.body?.expectedVersion).toBe(4)
    expect(typeof put.body?.requestId).toBe('string')
    expect(api.updateNotice.value).toContain('5')
    stop()
  })

  it('同一次提交失败后重试复用同 requestId（K01 幂等），成功后重置', async () => {
    const harness = makeServerHarness(serverConfig(3, false))
    const { api, stop } = mountAdmin()
    api.notifyActivated()
    await flush()
    api.configDraft.value!.reason = '首次尝试'

    // 第一次：上游 502 瞬时失败 → requestId 保留。
    harness.failNextPut()
    await expect(api.updateConfig()).resolves.toBe(false)
    expect(api.updateError.value).toContain('上游暂时不可用')
    const firstPut = harness.log.filter((call) => call.method === 'PUT').pop()!
    expect(firstPut.body?.requestId).toBeTruthy()

    // 重试（同草稿）：同 requestId；本次成功。
    await expect(api.updateConfig()).resolves.toBe(true)
    const retryPut = harness.log.filter((call) => call.method === 'PUT').pop()!
    expect(retryPut.body?.requestId).toBe(firstPut.body?.requestId)

    // 成功后重置：再次提交（新草稿）换新 requestId。
    api.configDraft.value!.reason = '第二次变更'
    await expect(api.updateConfig()).resolves.toBe(true)
    const thirdPut = harness.log.filter((call) => call.method === 'PUT').pop()!
    expect(thirdPut.body?.requestId).not.toBe(retryPut.body?.requestId)
    stop()
  })

  it('终止与核对成功后重读真实状态：unknown 不被 optimistic 标记', async () => {
    makeServerHarness(serverConfig(3, false)) // 装 fetch mock（本用例不查请求日志）
    const { api, stop } = mountAdmin()
    api.notifyActivated()
    await flush()

    await expect(api.terminateSession(sessionRow.id, '紧急止损')).resolves.toBe(true)
    expect(api.terminating.value).toBeNull()
    expect(api.terminateNotice.value).toContain('已终止')

    const form = {
      outcome: 'succeeded' as const,
      providerEvidenceRef: 'prov-req-777',
      confirmedUsage: { inputTokens: 120, outputTokens: 80, audioInputMs: null, audioOutputMs: null, textCodePoints: null, renderMs: null },
      reason: '供应商后台确认成功',
    }
    await expect(api.reconcile(unknownInvocation.id, unknownInvocation.version, form)).resolves.toBe(true)
    // 成功展示的是服务端回读（succeeded+settled），不是本地把 unknown 改写。
    expect(api.lastReconciled.value?.state).toBe('succeeded')
    expect(api.lastReconciled.value?.settlementState).toBe('settled')

    // 证据不齐：failed 却携带用量 → 客户端门禁拒绝（后端仍强校验）。
    const badForm = { ...form, outcome: 'failed' as const }
    await expect(api.reconcile(unknownInvocation.id, 2, badForm)).resolves.toBe(false)
    expect(api.reconcileError.value).toContain('证据')
    stop()
  })
})

describe('tc105g_04_04 · 失活和主题（在途 poll 后切 tab/账号）', () => {
  it('失活：abort 在途、清全部状态；迟到回包不回写、不恢复轮询', async () => {
    const harness = makeServerHarness(serverConfig(3, false))
    const hold = harness.armHold()
    const { api, stop } = mountAdmin()
    api.notifyActivated()
    await flush()
    expect(api.loading.value).toBe(true)
    expect(harness.log).toHaveLength(3)

    // 切 tab（失活）：在途只读被 abort，状态清空。
    const inFlightSignals = harness.log.map((call) => call.signal)
    api.notifyDeactivated()
    expect(inFlightSignals.every((signal) => signal?.aborted)).toBe(true)
    expect(api.config.value).toBeNull()
    expect(api.configDraft.value).toBeNull()
    expect(api.sessions.value).toEqual([])
    expect(api.invocations.value).toEqual([])
    expect(api.loading.value).toBe(false)
    expect(vi.getTimerCount()).toBe(0)

    // 迟到回包落地（mock fetch 不因 abort 提前拒绝）→ 不回写、不再轮询。
    hold.release()
    await vi.advanceTimersByTimeAsync(15_000)
    expect(api.config.value).toBeNull()
    expect(api.sessions.value).toEqual([])
    expect(harness.log).toHaveLength(3)
    expect(vi.getTimerCount()).toBe(0)
    stop()
  })

  it('换号：旧票全失效、状态全清；迟到回包不写新账号、不恢复轮询', async () => {
    const harness = makeServerHarness(serverConfig(3, false))
    const { api, stop } = mountAdmin()
    api.notifyActivated()
    await flush()
    expect(api.config.value?.version).toBe(3)

    // 下一轮在途时换号（A→B）：account-session 真实 store 递增 epoch 并 abort 旧只读。
    const hold = harness.armHold()
    await vi.advanceTimersByTimeAsync(5_000)
    expect(api.loading.value).toBe(true)
    useAuthStore().currentUser = userB
    await nextTick()
    expect(api.config.value).toBeNull()
    expect(api.invocations.value).toEqual([])
    expect(vi.getTimerCount()).toBe(0)

    hold.release()
    await vi.advanceTimersByTimeAsync(15_000)
    expect(api.config.value).toBeNull()
    expect(vi.getTimerCount()).toBe(0)
    stop()
  })

  it('隐藏停排并 abort 在途；恢复可见立即一轮并恢复串行续排', async () => {
    const harness = makeServerHarness(serverConfig(3, false))
    const hold = harness.armHold()
    const { api, stop } = mountAdmin()
    api.notifyActivated()
    await flush()
    expect(api.loading.value).toBe(true)

    api.notifyVisibility(false)
    expect(harness.log.every((call) => call.signal?.aborted)).toBe(true)
    hold.release()
    await vi.advanceTimersByTimeAsync(15_000)
    expect(harness.log).toHaveLength(3)

    // 恢复可见：立即一轮（不等 5s），并恢复 5s 串行续排。
    api.notifyVisibility(true)
    await flush()
    expect(harness.log).toHaveLength(6)
    await vi.advanceTimersByTimeAsync(5_000)
    expect(harness.log).toHaveLength(9)
    stop()
  })

  it('dispose 移除 document visibilitychange 监听，无监听遗留、无追加请求', async () => {
    const addSpy = vi.spyOn(document, 'addEventListener')
    const removeSpy = vi.spyOn(document, 'removeEventListener')
    const harness = makeServerHarness(serverConfig(3, false))
    const { api, stop } = mountAdmin()
    api.notifyActivated()
    await flush()
    expect(addSpy).toHaveBeenCalledWith('visibilitychange', expect.anything())

    stop()
    expect(removeSpy).toHaveBeenCalledWith('visibilitychange', expect.anything())
    const calls = harness.log.length
    await vi.advanceTimersByTimeAsync(15_000)
    expect(harness.log).toHaveLength(calls)
    expect(vi.getTimerCount()).toBe(0)
    // dispose 不可逆：再激活不请求。
    api.notifyActivated()
    await vi.advanceTimersByTimeAsync(5_000)
    expect(harness.log).toHaveLength(calls)
  })
})
