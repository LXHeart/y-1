// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { applySessionEvent, useDigitalHumanSession } from './useDigitalHumanSession'
import type { DigitalHumanApi } from '../../../composables/useDigitalHumanApi'
import { GrasslandHttpError } from '../../../composables/grassland-http'
import type { AccountSessionPort, AccountTicket } from '../../../stores/account-session'
import type { Preflight, Session } from '../../../types/digital-human'

/** TC105E-03-02 A→B→A 迟到 / TC105E-03-03 隐藏与 KeepAlive（心跳门禁）。 */

const SESSION: Session = {
  id: '44444444-4444-4444-8444-444444444444',
  profileId: '33333333-3333-4333-8333-333333333333',
  profileRevision: 1,
  state: 'ready',
  leaseEpoch: 1,
  mediaEpoch: 1,
  controllerId: '55555555-5555-4555-8555-555555555555',
  serverNow: '2026-09-23T00:00:00Z',
  createdAt: '2026-09-23T00:00:00Z',
  readyAt: null,
  expiresAt: null,
  pausedUntil: null,
  leaseExpiresAt: '2026-09-23T00:00:30Z',
  lastSeq: 0,
  saveTranscript: false,
  transcriptVersion: 1,
  contentEpoch: 1,
  billing: { confirmedCents: 0, platformCostCents: 0, subsidizedCents: 0, pendingCount: 0, priceTableVersion: 'pt-v1' },
  errorCode: null,
}

const PREFLIGHT = { id: '55555555-5555-4555-8555-555555555556' } as Preflight

function makeAccount(initial = 'account-a') {
  const state = { accountId: initial, epoch: 0 }
  let controller = new AbortController()
  const port: AccountSessionPort = {
    capture: (): AccountTicket => ({ accountId: state.accountId, epoch: state.epoch, signal: controller.signal }),
    isCurrent: (ticket) => ticket.accountId === state.accountId && ticket.epoch === state.epoch,
  }
  return {
    port,
    switchAccount(id: string) {
      state.accountId = id
      state.epoch += 1
      controller.abort()
      controller = new AbortController()
    },
  }
}

function makeApi() {
  return {
    createSession: vi.fn(async (_input: { preflightId: string; requestId: string; saveTranscript: boolean }) => ({ ...SESSION })),
    pauseSession: vi.fn(async () => ({ ...SESSION, state: 'paused' as const, pausedUntil: '2026-09-23T00:00:30Z' })),
    resumeSession: vi.fn(async () => ({ ...SESSION, state: 'connecting' as const, leaseEpoch: 2 })),
    endSession: vi.fn(async (): Promise<Session> => ({ ...SESSION, state: 'ended' as const })),
    heartbeat: vi.fn(async () => ({ serverNow: '2026-09-23T00:00:10Z', leaseExpiresAt: '2026-09-23T00:00:40Z', pausedUntil: null })),
  }
}

/** 传给 composable 时按接口收窄（保留 mock 断言能力）。 */
function asApi(mocks: ReturnType<typeof makeApi>): DigitalHumanApi {
  return mocks as unknown as DigitalHumanApi
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('TC105E-03-02 A→B→A 迟到', () => {
  test('create 回包迟到且账号已切走：session 不落旧账号数据', async () => {
    const api = makeApi()
    let resolveCreate!: (value: Session) => void
    api.createSession.mockImplementation(() => new Promise((resolve) => { resolveCreate = resolve }))
    const { port, switchAccount } = makeAccount()
    const state = useDigitalHumanSession(asApi(api), port, { heartbeatIntervalMs: 10_000 })

    const pending = state.start(PREFLIGHT, false)
    switchAccount('account-b')
    resolveCreate({ ...SESSION })
    const created = await pending

    expect(created).toBeNull()
    expect(state.session.value).toBeNull()
    // 同键重试语义由服务端 receipt 保证；此处证明客户端不把旧账号会话当新账号事实。
  })

  test('切回 A（epoch 已变）：旧账号会话状态不被旧事件回写复活', () => {
    const event = {
      type: 'session.state',
      sessionId: SESSION.id,
      seq: 3,
      eventId: 'e-3',
      leaseEpoch: 1,
      mediaEpoch: 1,
      turnId: null,
      turnEpoch: null,
      occurredAt: '2026-09-23T00:00:00Z',
      v: 1 as const,
      payload: { state: 'responding' },
    }
    // applySessionEvent 只投影本会话事件；会话本身已不在（null）→ 旧事件无处落。
    expect(applySessionEvent(null, event)).toBeNull()
    const other = applySessionEvent({ ...SESSION, id: '99999999-9999-4999-8999-999999999999' }, event)
    expect(other?.state).toBe('ready') // 不同 session 的事件不动本地会话
    const own = applySessionEvent(SESSION, event)
    expect(own?.state).toBe('responding')
    expect(own?.leaseEpoch).toBe(1)
  })

  test('start 幂等键：同一 preflight+save 失败重试复用 requestId；换 preflight 换新键', async () => {
    const api = makeApi()
    api.createSession.mockRejectedValueOnce(new Error('网络中断'))
    const { port } = makeAccount()
    const state = useDigitalHumanSession(asApi(api), port)

    const first = await state.start(PREFLIGHT, false)
    expect(first).toBeNull()
    const retry = await state.start(PREFLIGHT, false)
    expect(retry?.id).toBe(SESSION.id)
    const keys = api.createSession.mock.calls.map((call) => call[0].requestId)
    expect(keys[0]).toBe(keys[1])

    await state.start({ ...PREFLIGHT, id: '66666666-6666-4666-8666-666666666666' }, false)
    expect(api.createSession.mock.calls[2][0].requestId).not.toBe(keys[0])
  })
})

describe('TC105E-03-03 隐藏与 KeepAlive', () => {
  test('活动页面每 10 秒心跳；隐藏即停止并 pause(reason=hidden)', async () => {
    const api = makeApi()
    const { port } = makeAccount()
    const state = useDigitalHumanSession(asApi(api), port, { heartbeatIntervalMs: 10_000 })
    const created = await state.start(PREFLIGHT, false)
    expect(created?.id).toBe(SESSION.id)

    // 活动页面：两个心跳周期 → 2 次。
    await vi.advanceTimersByTimeAsync(20_000)
    expect(api.heartbeat).toHaveBeenCalledTimes(2)

    // 隐藏：pause + 不再心跳。
    state.notifyVisibility(false)
    expect(api.pauseSession).toHaveBeenCalledWith(SESSION.id, expect.objectContaining({ reason: 'hidden' }))
    await vi.advanceTimersByTimeAsync(30_000)
    expect(api.heartbeat).toHaveBeenCalledTimes(2)
  })

  test('deactivated 后 visible 事件不恢复心跳/会话（恢复必须显式 resume）', async () => {
    const api = makeApi()
    const { port } = makeAccount()
    const state = useDigitalHumanSession(asApi(api), port, { heartbeatIntervalMs: 10_000 })
    await state.start(PREFLIGHT, false)

    state.notifyDeactivated()
    expect(api.pauseSession).toHaveBeenCalledTimes(1)
    // 停用后页面重新可见（visible 事件）——keepAliveActive 仍 false。
    state.notifyVisibility(true)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(api.heartbeat).not.toHaveBeenCalled()
    expect(api.resumeSession).not.toHaveBeenCalled()

    // 返回本页（KeepAlive 重新激活）+ 显式 resume 才恢复心跳。
    state.notifyActivated()
    await state.resume(true)
    expect(api.resumeSession).toHaveBeenCalledWith(SESSION.id, expect.objectContaining({ takeover: true }))
    await vi.advanceTimersByTimeAsync(10_000)
    expect(api.heartbeat).toHaveBeenCalledTimes(1)
  })

  test('心跳 409 旧租约：置 leaseStale 并停心跳（不无限重试）', async () => {
    const api = makeApi()
    api.heartbeat.mockRejectedValue(new GrasslandHttpError(409, '此会话已由另一页面接管，请重新获取会话状态。', 'dh_lease_stale'))
    const { port } = makeAccount()
    const state = useDigitalHumanSession(asApi(api), port, { heartbeatIntervalMs: 10_000 })
    await state.start(PREFLIGHT, false)

    await vi.advanceTimersByTimeAsync(10_000)
    expect(state.leaseStale.value).toBe(true)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(api.heartbeat).toHaveBeenCalledTimes(1)
  })

  test('end 始终可调用且幂等（在途不重入；结束后不再心跳）', async () => {
    const api = makeApi()
    let resolveEnd!: (value: Session) => void
    api.endSession.mockImplementation(() => new Promise<Session>((resolve) => { resolveEnd = resolve }))
    const { port } = makeAccount()
    const state = useDigitalHumanSession(asApi(api), port, { heartbeatIntervalMs: 10_000 })
    await state.start(PREFLIGHT, false)

    const first = state.end()
    const second = state.end() // 在途重入被挡
    resolveEnd({ ...SESSION, state: 'ended' as const })
    await Promise.all([first, second])
    expect(api.endSession).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(api.heartbeat).not.toHaveBeenCalled()
    expect(state.session.value?.state).toBe('ended')
  })
})
