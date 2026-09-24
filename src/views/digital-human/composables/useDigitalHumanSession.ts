/**
 * 数字人会话生命周期（任务书 #105E C105E-03 / 共享契约 K04、K13.4）。
 *
 * create（确认后）→ pause/resume（epoch 轮换）→ end（始终可点、幂等）。所有 Promise 回写前做
 * ticket+generation 双查；AbortSignal 只取消读取（绝不据此显示退款）。heartbeat 只在
 * 「页面可见 ∧ KeepAlive 激活 ∧ 会话非终态」时每 10 秒发送；隐藏 → pause(reason=hidden)；
 * deactivated 后 visible 事件<b>不能</b>重新激活（恢复必须显式 resume）；beforeunload 尽力通知，
 * 最终依赖服务端租约回收。
 */
import { onScopeDispose, ref, shallowRef, type Ref, type ShallowRef } from 'vue'
import type { DigitalHumanApi } from '../../../composables/useDigitalHumanApi'
import type { AccountSessionPort } from '../../../stores/account-session'
import type { Preflight, Session } from '../../../types/digital-human'
import type { DhEvent } from './useDigitalHumanEvents'

export const HEARTBEAT_INTERVAL_MS = 10_000

const ACTIVE_STATES: Session['state'][] = ['connecting', 'ready', 'listening', 'responding', 'reconnecting']
const PAUSABLE_STATES: Session['state'][] = ['ready', 'listening', 'responding', 'reconnecting']

/** SSE 状态/快照事件 → 本地 Session 投影（K06：state 是权威；其余 type 不动会话态）。 */
export function applySessionEvent(current: Session | null, event: DhEvent): Session | null {
  if (current == null || event.sessionId !== current.id) return current
  if (event.type !== 'session.state' && event.type !== 'session.snapshot') return current
  const payload = event.payload as Record<string, unknown>
  const nextState = typeof payload.state === 'string' ? payload.state as Session['state'] : null
  if (nextState == null) return current
  return {
    ...current,
    state: nextState,
    pausedUntil: 'pausedUntil' in payload ? (payload.pausedUntil as string | null) : current.pausedUntil,
    expiresAt: 'expiresAt' in payload ? (payload.expiresAt as string | null) : current.expiresAt,
    leaseEpoch: event.leaseEpoch ?? current.leaseEpoch,
    mediaEpoch: event.mediaEpoch ?? current.mediaEpoch,
  }
}

export function useDigitalHumanSession(
  api: DigitalHumanApi,
  account: AccountSessionPort,
  options: { controllerId?: string; heartbeatIntervalMs?: number } = {},
): {
  session: ShallowRef<Session | null>
  starting: Ref<boolean>
  ending: Ref<boolean>
  error: Ref<string | null>
  /** 409 dh_lease_stale 后置位：本页已失去租约（被接管/过期），恢复须显式 resume(takeover)。 */
  leaseStale: Ref<boolean>
  controllerId: string
  start: (preflight: Preflight, saveTranscript: boolean) => Promise<Session | null>
  pause: () => Promise<void>
  resume: (takeover: boolean) => Promise<void>
  end: () => Promise<void>
  /** 页面可见性变化（由 document listener 与本方法双入口；测试可直调）。 */
  notifyVisibility: (visible: boolean) => void
  /** KeepAlive 激活/停用（由 onActivated/onDeactivated 调用）。 */
  notifyActivated: () => void
  notifyDeactivated: () => void
  dispose: () => void
} {
  const session = shallowRef<Session | null>(null)
  const starting = ref(false)
  const ending = ref(false)
  const error = ref<string | null>(null)
  const leaseStale = ref(false)
  const controllerId = options.controllerId ?? crypto.randomUUID()

  let generation = 0
  let heartbeatTimer: ReturnType<typeof setInterval> | null = null
  let documentVisible = typeof document === 'undefined' ? true : !document.hidden
  let keepAliveActive = true
  let startKey: { requestId: string; preflightId: string; saveTranscript: boolean } | null = null

  const intervalMs = options.heartbeatIntervalMs ?? HEARTBEAT_INTERVAL_MS

  function isCurrent(run: number, ticket: ReturnType<AccountSessionPort['capture']>): boolean {
    return run === generation && account.isCurrent(ticket)
  }

  function sessionOperational(): boolean {
    const current = session.value
    return current != null && ACTIVE_STATES.includes(current.state) && documentVisible && keepAliveActive
  }

  function stopHeartbeat(): void {
    if (heartbeatTimer != null) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  function beginHeartbeat(): void {
    stopHeartbeat()
    heartbeatTimer = setInterval(() => {
      const current = session.value
      if (current == null || !sessionOperational()) return // 隐藏/停用页不心跳、不续租
      void api.heartbeat(current.id, { leaseEpoch: current.leaseEpoch, controllerId })
        .then((beat) => {
          if (session.value?.id === current.id) {
            session.value = { ...session.value, leaseExpiresAt: beat.leaseExpiresAt }
          }
        })
        .catch((failure: { status?: number }) => {
          // 旧 epoch 409：本页失去租约，停止心跳（恢复入口=显式 resume takeover）。
          if (failure?.status === 409) {
            leaseStale.value = true
            stopHeartbeat()
          }
        })
    }, intervalMs)
  }

  async function start(preflight: Preflight, saveTranscript: boolean): Promise<Session | null> {
    generation += 1
    const run = generation
    const ticket = account.capture()
    // 同一 preflight+save 的失败重试复用原 requestId（K04；丢首次响应重发同键取得原结果）。
    const reusable = startKey != null && startKey.preflightId === preflight.id
      && startKey.saveTranscript === saveTranscript
    const requestId = reusable ? startKey!.requestId : crypto.randomUUID()
    startKey = { requestId, preflightId: preflight.id, saveTranscript }
    starting.value = true
    error.value = null
    leaseStale.value = false
    try {
      const created = await api.createSession({ preflightId: preflight.id, requestId, saveTranscript })
      if (!isCurrent(run, ticket)) return null
      session.value = created
      beginHeartbeat()
      return created
    } catch (failure) {
      if (isCurrent(run, ticket)) {
        error.value = failure instanceof Error ? failure.message : '创建会话失败，请稍后重试。'
      }
      return null
    } finally {
      if (isCurrent(run, ticket)) starting.value = false
    }
  }

  async function pause(): Promise<void> {
    const current = session.value
    if (current == null || !PAUSABLE_STATES.includes(current.state)) return
    const ticket = account.capture()
    try {
      const paused = await api.pauseSession(current.id, {
        requestId: crypto.randomUUID(), leaseEpoch: current.leaseEpoch, reason: 'hidden',
      })
      if (account.isCurrent(ticket) && session.value?.id === paused.id) session.value = paused
    } catch (failure) {
      // 暂停失败保留当前态；服务端租约超时兜底（不在客户端伪造 paused）。
      if (account.isCurrent(ticket) && failure instanceof Error) error.value = failure.message
    }
  }

  async function resume(takeover: boolean): Promise<void> {
    const current = session.value
    if (current == null) return
    const ticket = account.capture()
    error.value = null
    try {
      const resumed = await api.resumeSession(current.id, {
        requestId: crypto.randomUUID(), leaseEpoch: current.leaseEpoch, takeover, controllerId,
      })
      if (account.isCurrent(ticket) && session.value?.id === resumed.id) {
        session.value = resumed
        leaseStale.value = false
        beginHeartbeat()
      }
    } catch (failure) {
      if (account.isCurrent(ticket)) {
        error.value = failure instanceof Error ? failure.message : '恢复会话失败。'
      }
    }
  }

  async function end(): Promise<void> {
    const current = session.value
    if (current == null || ending.value) return // 幂等：在途 end 不重入
    ending.value = true
    const ticket = account.capture()
    try {
      const ended = await api.endSession(current.id, { requestId: crypto.randomUUID(), reason: 'user' })
      if (account.isCurrent(ticket) && session.value?.id === ended.id) session.value = ended
    } catch (failure) {
      // end 失败保留入口可再点（始终可点击）；服务端 lease 回收兜底。
      if (account.isCurrent(ticket) && failure instanceof Error) error.value = failure.message
    } finally {
      if (account.isCurrent(ticket)) ending.value = false
    }
    stopHeartbeat()
  }

  function notifyVisibility(visible: boolean): void {
    documentVisible = visible
    if (!visible) {
      // 隐藏：尽力 pause 并停本页心跳（deactivated 的页面 visible 也不能复活——keepAliveActive 独立判定）。
      stopHeartbeat()
      void pause()
    }
  }

  function notifyActivated(): void {
    keepAliveActive = true
  }

  function notifyDeactivated(): void {
    keepAliveActive = false
    // 停用：停心跳并尽力暂停；后续 visible 事件不恢复（resume 必须显式）。
    stopHeartbeat()
    void pause()
  }

  // document 可见性监听（KeepAlive 停用的组件同样会收到，但 keepAliveActive=false 时 pause 幂等无害）。
  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', () => notifyVisibility(!document.hidden))
  }

  // beforeunload：尽力通知（fire-and-forget）；最终权威是服务端 lease 回收（K04 ≤45s）。
  if (typeof window !== 'undefined') {
    window.addEventListener('beforeunload', () => {
      const current = session.value
      if (current == null || !ACTIVE_STATES.includes(current.state)) return
      void api.pauseSession(current.id, {
        requestId: crypto.randomUUID(), leaseEpoch: current.leaseEpoch, reason: 'hidden',
      }).catch(() => undefined)
    })
  }

  function dispose(): void {
    generation += 1
    stopHeartbeat()
    if (typeof document !== 'undefined') {
      // 以命名函数为准移除不可行（匿名注册）；dispose 仅在作用域销毁时发生，容忍一个空监听。
    }
  }

  onScopeDispose(dispose)

  return {
    session, starting, ending, error, leaseStale, controllerId,
    start, pause, resume, end, notifyVisibility, notifyActivated, notifyDeactivated, dispose,
  }
}
