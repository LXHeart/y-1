import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { request } from './grassland-http'
import { normalizeAccountId, useAccountSessionStore, type AccountTicket } from '../stores/account-session'
import { useAuthStore } from '../stores/auth'
import type { DouyinSessionState } from '../types/douyin'

/**
 * 抖音绑定会话（模块级单例）。原有保护只有 request-id 乱序丢弃与组件卸载 bump；
 * 任务书 #82 C82-01 补账号 owner 边界：账号变化同步清绑定态/二维码/错误并停扫码轮询，
 * 所有异步写入（成功/失败/finally/轮询回调）先验账号票据，旧账号的迟到响应一律静默丢弃。
 * endpoint、2000ms 轮询间隔与公开签名保持不变。
 */
const state = ref<DouyinSessionState | null>(null)
const loading = ref(false)
const polling = ref(false)
const error = ref('')
/** 当前会话态归属账号的镜像：null = 匿名（无任何绑定态）。 */
const ownerAccountId = ref<string | null>(null)

let pollTimer: number | undefined
let latestRequestId = 0

function normalizeSessionState(value: unknown): DouyinSessionState | null {
  if (typeof value !== 'object' || value === null) {
    return null
  }

  const record = value as Record<string, unknown>
  if (typeof record.status !== 'string' || typeof record.hasPersistedSession !== 'boolean') {
    return null
  }

  const allowedStatuses = new Set(['missing', 'launching', 'qr_ready', 'waiting_for_confirm', 'authenticated', 'expired', 'error'])
  if (!allowedStatuses.has(record.status)) {
    return null
  }

  return {
    status: record.status as DouyinSessionState['status'],
    hasPersistedSession: record.hasPersistedSession,
    qrImageUrl: typeof record.qrImageUrl === 'string' ? record.qrImageUrl : undefined,
    detailCode: typeof record.detailCode === 'string' ? record.detailCode as DouyinSessionState['detailCode'] : undefined,
    message: typeof record.message === 'string' ? record.message : undefined,
    lastAuthenticatedAt: typeof record.lastAuthenticatedAt === 'string' ? record.lastAuthenticatedAt : undefined,
    lastUsedAt: typeof record.lastUsedAt === 'string' ? record.lastUsedAt : undefined,
  }
}

async function requestSession(path: string, method: 'GET' | 'POST', requestId: number, ticket: AccountTicket): Promise<DouyinSessionState> {
  const data = await request<unknown>(path, {
    method,
    headers: {
      'Content-Type': 'application/json',
    },
  }, { fallbackError: '抖音登录服务暂不可用，请稍后重试' })

  const normalized = normalizeSessionState(data)
  if (!normalized) {
    throw new Error('抖音登录服务暂不可用，请稍后重试')
  }

  // 双重守卫：局部 request-id（卸载/新一轮作废）+ 账号票据（换号作废）。
  if (requestId === latestRequestId && useAccountSessionStore().isCurrent(ticket)) {
    state.value = normalized
    error.value = ''
  }

  return normalized
}

function createRequestId(): number {
  latestRequestId += 1
  return latestRequestId
}

function isLatestRequest(requestId: number): boolean {
  return requestId === latestRequestId
}

function isTicketCurrent(ticket: AccountTicket): boolean {
  return useAccountSessionStore().isCurrent(ticket)
}

function stopPolling(): void {
  if (pollTimer) {
    window.clearTimeout(pollTimer)
    pollTimer = undefined
  }
  polling.value = false
}

function shouldKeepPolling(session: DouyinSessionState | null): boolean {
  return session?.status === 'launching' || session?.status === 'qr_ready' || session?.status === 'waiting_for_confirm'
}

/** 扫码轮询链：每轮回调先验发起时的账号票据，换号后链条立即终止。 */
function schedulePoll(ticket: AccountTicket): void {
  stopPolling()
  if (!shouldKeepPolling(state.value)) {
    return
  }

  polling.value = true
  pollTimer = window.setTimeout(async () => {
    if (!isTicketCurrent(ticket)) {
      stopPolling()
      return
    }
    const requestId = createRequestId()

    try {
      const nextState = await requestSession('/api/douyin/session/poll', 'GET', requestId, ticket)
      if (!isLatestRequest(requestId) || !isTicketCurrent(ticket)) {
        return
      }

      if (shouldKeepPolling(nextState)) {
        schedulePoll(ticket)
        return
      }
      stopPolling()
    } catch (requestError: unknown) {
      if (!isLatestRequest(requestId) || !isTicketCurrent(ticket)) {
        return
      }

      error.value = requestError instanceof Error ? requestError.message : '轮询抖音登录状态失败'
      stopPolling()
    }
  }, 2000)
}

/**
 * 账号边界（任务书 #82 C82-01）：清绑定态/二维码/错误/loading、停扫码轮询，
 * 并作废全部在途 request-id。同 owner 重复调用直通（幂等，多个消费方 watcher 并存时安全）。
 */
function resetForAccount(accountId: string | null): void {
  if (ownerAccountId.value === accountId) return
  ownerAccountId.value = accountId
  latestRequestId += 1
  stopPolling()
  state.value = null
  loading.value = false
  error.value = ''
}

/** 与账号会话对齐：换号后即使没有任何 watcher（消费方全卸载），下次触碰也会先归零。 */
function reconcileOwner(): void {
  resetForAccount(useAccountSessionStore().ownerAccountId)
}

export function useDouyinSession() {
  const session = useAccountSessionStore()
  const auth = useAuthStore()
  reconcileOwner()
  // setup 作用域内的幂等账号 watch：换号同步清态；组件卸载即释放，重挂载由 reconcileOwner 兜底。
  watch(
    () => normalizeAccountId(auth.currentUser?.id),
    (accountId) => { resetForAccount(accountId) },
    { flush: 'sync', immediate: true },
  )

  const hasActiveSession = computed(() => state.value?.status === 'authenticated' && state.value.hasPersistedSession)

  async function refresh(): Promise<DouyinSessionState | null> {
    const ticket = session.capture()
    const requestId = createRequestId()
    loading.value = true
    try {
      const nextState = await requestSession('/api/douyin/session', 'GET', requestId, ticket)
      if (!isLatestRequest(requestId) || !isTicketCurrent(ticket)) {
        return nextState
      }

      if (shouldKeepPolling(nextState)) {
        schedulePoll(ticket)
      } else {
        stopPolling()
      }
      return nextState
    } catch (requestError: unknown) {
      if (isLatestRequest(requestId) && isTicketCurrent(ticket)) {
        error.value = requestError instanceof Error ? requestError.message : '获取抖音登录状态失败'
      }
      return null
    } finally {
      if (isLatestRequest(requestId) && isTicketCurrent(ticket)) {
        loading.value = false
      }
    }
  }

  async function start(): Promise<DouyinSessionState | null> {
    const ticket = session.capture()
    const requestId = createRequestId()
    loading.value = true
    try {
      const nextState = await requestSession('/api/douyin/session/start', 'POST', requestId, ticket)
      if (isLatestRequest(requestId) && isTicketCurrent(ticket)) {
        schedulePoll(ticket)
      }
      return nextState
    } catch (requestError: unknown) {
      if (isLatestRequest(requestId) && isTicketCurrent(ticket)) {
        error.value = requestError instanceof Error ? requestError.message : '启动抖音扫码登录失败'
      }
      return null
    } finally {
      if (isLatestRequest(requestId) && isTicketCurrent(ticket)) {
        loading.value = false
      }
    }
  }

  async function logout(): Promise<DouyinSessionState | null> {
    const ticket = session.capture()
    const requestId = createRequestId()
    loading.value = true
    stopPolling()
    try {
      const nextState = await requestSession('/api/douyin/session/logout', 'POST', requestId, ticket)
      return nextState
    } catch (requestError: unknown) {
      if (isLatestRequest(requestId) && isTicketCurrent(ticket)) {
        error.value = requestError instanceof Error ? requestError.message : '断开抖音登录失败'
      }
      return null
    } finally {
      if (isLatestRequest(requestId) && isTicketCurrent(ticket)) {
        loading.value = false
      }
    }
  }

  onBeforeUnmount(() => {
    latestRequestId += 1
    stopPolling()
  })

  return {
    ownerAccountId,
    state,
    loading,
    polling,
    error,
    hasActiveSession,
    refresh,
    start,
    logout,
    stopPolling,
    resetForAccount,
  }
}
