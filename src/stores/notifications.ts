import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { request } from '../composables/grassland-http'
import {
  NOTIFICATION_CATEGORY_ORDER,
  NOTIFICATION_LINK_TARGETS,
  type Notification,
  type NotificationCategory,
  type NotificationLinkTarget,
  type NotificationPage,
  type NotificationPayload,
  type NotificationQuery,
} from '../types/notification'

/**
 * 通知中心（草场 Slice 12 Stage 4）。经 edge-bff 打 identity `/api/me/notifications`。
 *
 * 与 `useGrassland` 同款约定：cookie 会话（`credentials: 'include'`）、`{success,data}` 信封、
 * 失败返回 null 并把消息写进 `error`（调用方不需 try-catch）。
 *
 * **单例状态**：未读数与列表是账号级的，顶栏铃铛和面板是同一份数据。
 */

/** 未读数轮询间隔。通知不是实时业务，60s 足够，且避免登录期间高频打后端。 */
const POLL_INTERVAL_MS = 60_000

/** 每页条数。与后端默认页大小无关——显式传，翻页判定才稳。 */
const PAGE_SIZE = 20

/** keyset 游标必须成对下发，缺一个后端 400；故这里统一拼参数，不在调用点手拼 query。 */
function buildListUrl(query: NotificationQuery): string {
  const params = new URLSearchParams()
  if (query.unreadOnly) params.set('unreadOnly', 'true')
  params.set('limit', String(query.limit ?? PAGE_SIZE))
  if (query.before && query.beforeId) {
    params.set('before', query.before)
    params.set('beforeId', query.beforeId)
  }
  return `/api/me/notifications?${params.toString()}`
}

/**
 * 后端 `linkPath` → 应用内落点。未登记的 path 返回 null（**不猜、不拼 URL**）：
 * 通知照样能标已读，只是点了不跳。后端加新 linkPath 时前端表补一行即可。
 */
export function resolveLinkTarget(
  linkPath: string | null, payload: NotificationPayload = {},
): NotificationLinkTarget | null {
  if (!linkPath) return null
  if (linkPath === '/me/task-invitations') {
    const taskId = payload.taskId
    return typeof taskId === 'string' && taskId
      ? { view: 'grassland', anchor: 'gl-task-hall', side: 'recommender', taskId }
      : null
  }
  if (linkPath === '/me/task-review') {
    const taskId = payload.taskId
    return typeof taskId === 'string' && taskId
      ? { view: 'grassland', anchor: 'gl-engagements', side: 'merchant', taskId }
      : null
  }
  if (linkPath === '/me/disputes') {
    const disputeId = payload.disputeId
    return typeof disputeId === 'string' && disputeId
      ? { view: 'grassland', anchor: 'gl-disputes', disputeId }
      : NOTIFICATION_LINK_TARGETS[linkPath]
  }
  return NOTIFICATION_LINK_TARGETS[linkPath] ?? null
}

export const useNotificationStore = defineStore('notification', () => {
  const unreadCount = ref(0)
  const items = ref<Notification[]>([])
  const nextBefore = ref<string | null>(null)
  const nextBeforeId = ref<string | null>(null)
  const unreadOnly = ref(false)
  const loading = ref(false)
  const error = ref('')

  /** 轮询句柄。重复 start 不叠加 timer。 */
  let pollTimer: ReturnType<typeof setInterval> | null = null

  function clearError(): void {
    error.value = ''
  }

  async function run<T>(operation: () => Promise<T>): Promise<T | null> {
    loading.value = true
    error.value = ''
    try {
      return await operation()
    } catch (caught: unknown) {
      error.value = caught instanceof Error ? caught.message : '未知错误'
      return null
    } finally {
      loading.value = false
    }
  }

  /**
   * 只拉未读数（顶栏轮询用）。**刻意不经 {@link run}**：轮询失败（离线、会话过期）不该在界面上
   * 弹红条打断用户，也不该把面板的 loading 点亮。静默失败，下一轮自然恢复。
   */
  async function refreshUnreadCount(): Promise<number | null> {
    try {
      const data = await request<{ unreadCount: number }>('/api/me/notifications/unread-count')
      unreadCount.value = data.unreadCount
      return data.unreadCount
    } catch {
      return null
    }
  }

  /** 拉第一页（重置游标与列表）。切换「只看未读」也走这里。 */
  async function loadFirstPage(): Promise<NotificationPage | null> {
    const page = await run(() => request<NotificationPage>(buildListUrl({
      unreadOnly: unreadOnly.value,
      limit: PAGE_SIZE,
    })))
    if (!page) return null
    items.value = page.items
    unreadCount.value = page.unreadCount
    nextBefore.value = page.nextBefore
    nextBeforeId.value = page.nextBeforeId
    return page
  }

  /** 追加下一页。已到末页（无游标）时直接返回 null，不发请求。 */
  async function loadMore(): Promise<NotificationPage | null> {
    if (!nextBefore.value || !nextBeforeId.value) return null
    const page = await run(() => request<NotificationPage>(buildListUrl({
      unreadOnly: unreadOnly.value,
      limit: PAGE_SIZE,
      before: nextBefore.value,
      beforeId: nextBeforeId.value,
    })))
    if (!page) return null
    items.value = [...items.value, ...page.items]
    unreadCount.value = page.unreadCount
    nextBefore.value = page.nextBefore
    nextBeforeId.value = page.nextBeforeId
    return page
  }

  function setUnreadOnly(next: boolean): Promise<NotificationPage | null> {
    unreadOnly.value = next
    return loadFirstPage()
  }

  /**
   * 标记已读。本地按不可变方式替换（不改原对象），未读数按**实际转变的条数**减，
   * 不按 ids 长度减——重复点同一条时后端 updated=0，若按长度减未读数会漂负。
   */
  async function markRead(ids: string[]): Promise<number | null> {
    const pending = ids.filter((id) => items.value.some((n) => n.id === id && !n.read))
    if (pending.length === 0) return 0
    const result = await run(() => request<{ updated: number }>('/api/me/notifications/read', {
      method: 'POST',
      body: JSON.stringify({ ids: pending }),
    }))
    if (!result) return null
    const marked = new Set(pending)
    items.value = items.value.map((n) => (marked.has(n.id) ? { ...n, read: true } : n))
    unreadCount.value = Math.max(0, unreadCount.value - result.updated)
    if (unreadOnly.value) await loadFirstPage()
    return result.updated
  }

  async function markAllRead(): Promise<number | null> {
    const result = await run(() => request<{ updated: number }>('/api/me/notifications/read-all', {
      method: 'POST',
    }))
    if (!result) return null
    items.value = items.value.map((n) => (n.read ? n : { ...n, read: true }))
    unreadCount.value = 0
    if (unreadOnly.value) await loadFirstPage()
    return result.updated
  }

  /** 登录后启动未读轮询。重复调用只保留一个 timer。 */
  function startPolling(): void {
    stopPolling()
    void refreshUnreadCount()
    pollTimer = setInterval(() => {
      void refreshUnreadCount()
    }, POLL_INTERVAL_MS)
  }

  function stopPolling(): void {
    if (pollTimer !== null) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  /** 登出时清空——上一个账号的通知不能留在界面上。 */
  function reset(): void {
    stopPolling()
    items.value = []
    unreadCount.value = 0
    nextBefore.value = null
    nextBeforeId.value = null
    unreadOnly.value = false
    error.value = ''
  }

  /** 按 category 分组，顺序取自 {@link NOTIFICATION_CATEGORY_ORDER}；空组不出现。 */
  const grouped = computed<{ category: NotificationCategory; items: Notification[] }[]>(() =>
    NOTIFICATION_CATEGORY_ORDER
      .map((category) => ({ category, items: items.value.filter((n) => n.category === category) }))
      .filter((group) => group.items.length > 0))

  const hasMore = computed(() => Boolean(nextBefore.value && nextBeforeId.value))

  return {
    unreadCount,
    items,
    grouped,
    hasMore,
    unreadOnly,
    loading,
    error,
    clearError,
    refreshUnreadCount,
    loadFirstPage,
    loadMore,
    setUnreadOnly,
    markRead,
    markAllRead,
    startPolling,
    stopPolling,
    reset,
    resolveLinkTarget,
  }
})
