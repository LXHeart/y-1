import { computed, onBeforeUnmount, ref } from 'vue'
import type { ApiResponse, DouyinSessionState } from '../types/douyin'

const state = ref<DouyinSessionState | null>(null)
const loading = ref(false)
const polling = ref(false)
const error = ref('')

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

async function requestSession(path: string, method: 'GET' | 'POST', requestId: number): Promise<DouyinSessionState> {
  const response = await fetch(path, {
    method,
    headers: {
      'Content-Type': 'application/json',
    },
  })

  const contentType = response.headers.get('content-type') || ''
  if (!contentType.includes('application/json')) {
    const fallbackText = await response.text()
    throw new Error(fallbackText || '抖音登录服务暂不可用，请稍后重试')
  }

  const body = await response.json() as ApiResponse<unknown>
  const normalized = normalizeSessionState(body.data)
  if (!response.ok || !body.success || !normalized) {
    throw new Error(body.error || '抖音登录服务暂不可用，请稍后重试')
  }

  if (requestId === latestRequestId) {
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

function schedulePoll(): void {
  stopPolling()
  if (!shouldKeepPolling(state.value)) {
    return
  }

  polling.value = true
  pollTimer = window.setTimeout(async () => {
    const requestId = createRequestId()

    try {
      const nextState = await requestSession('/api/douyin/session/poll', 'GET', requestId)
      if (!isLatestRequest(requestId)) {
        return
      }

      if (shouldKeepPolling(nextState)) {
        schedulePoll()
        return
      }
      stopPolling()
    } catch (requestError: unknown) {
      if (!isLatestRequest(requestId)) {
        return
      }

      error.value = requestError instanceof Error ? requestError.message : '轮询抖音登录状态失败'
      stopPolling()
    }
  }, 2000)
}

export function useDouyinSession() {
  const hasActiveSession = computed(() => state.value?.status === 'authenticated' && state.value.hasPersistedSession)

  async function refresh(): Promise<DouyinSessionState | null> {
    const requestId = createRequestId()
    loading.value = true
    try {
      const nextState = await requestSession('/api/douyin/session', 'GET', requestId)
      if (!isLatestRequest(requestId)) {
        return nextState
      }

      if (shouldKeepPolling(nextState)) {
        schedulePoll()
      } else {
        stopPolling()
      }
      return nextState
    } catch (requestError: unknown) {
      if (isLatestRequest(requestId)) {
        error.value = requestError instanceof Error ? requestError.message : '获取抖音登录状态失败'
      }
      return null
    } finally {
      if (isLatestRequest(requestId)) {
        loading.value = false
      }
    }
  }

  async function start(): Promise<DouyinSessionState | null> {
    const requestId = createRequestId()
    loading.value = true
    try {
      const nextState = await requestSession('/api/douyin/session/start', 'POST', requestId)
      if (isLatestRequest(requestId)) {
        schedulePoll()
      }
      return nextState
    } catch (requestError: unknown) {
      if (isLatestRequest(requestId)) {
        error.value = requestError instanceof Error ? requestError.message : '启动抖音扫码登录失败'
      }
      return null
    } finally {
      if (isLatestRequest(requestId)) {
        loading.value = false
      }
    }
  }

  async function logout(): Promise<DouyinSessionState | null> {
    const requestId = createRequestId()
    loading.value = true
    stopPolling()
    try {
      const nextState = await requestSession('/api/douyin/session/logout', 'POST', requestId)
      return nextState
    } catch (requestError: unknown) {
      if (isLatestRequest(requestId)) {
        error.value = requestError instanceof Error ? requestError.message : '断开抖音登录失败'
      }
      return null
    } finally {
      if (isLatestRequest(requestId)) {
        loading.value = false
      }
    }
  }

  onBeforeUnmount(() => {
    latestRequestId += 1
    stopPolling()
  })

  return {
    state,
    loading,
    polling,
    error,
    hasActiveSession,
    refresh,
    start,
    logout,
    stopPolling,
  }
}
