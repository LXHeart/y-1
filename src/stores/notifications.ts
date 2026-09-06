import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { request } from '../composables/grassland-http'
import { normalizeAccountId, useAccountSessionStore, type AccountTicket } from './account-session'
import { useAuthStore } from './auth'
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
      // 无 disputeId 的兜底：落履约区（那里有「我的争议」入口；gl-disputes 区已于 2026-09-04 撤除）
      : { view: 'grassland', anchor: 'gl-engagements' }
  }
  return NOTIFICATION_LINK_TARGETS[linkPath] ?? null
}

export const useNotificationStore = defineStore('notification', () => {
  const session = useAccountSessionStore()
  const auth = useAuthStore()
  const unreadCount = ref(0)
  const items = ref<Notification[]>([])
  const nextBefore = ref<string | null>(null)
  const nextBeforeId = ref<string | null>(null)
  const unreadOnly = ref(false)
  const loading = ref(false)
  const error = ref('')
  /** 当前数据归属账号的镜像：null = 匿名（未加载任何私有数据）。 */
  const ownerAccountId = ref<string | null>(null)

  /** 轮询句柄。重复 start 不叠加 timer。 */
  let pollTimer: ReturnType<typeof setInterval> | null = null
  /** 同 owner 首页/未读数并发去重（任务书 #82 C82-01 E01）：换号后旧 pending 一律作废。 */
  let pendingFirstPage: Promise<NotificationPage | null> | null = null
  let pendingFirstPageTicket: AccountTicket | null = null
  let pendingUnread: Promise<number | null> | null = null
  let pendingUnreadTicket: AccountTicket | null = null

  function clearError(): void {
    error.value = ''
  }

  async function run<T>(operation: () => Promise<T>): Promise<T | null> {
    const ticket = session.capture()
    loading.value = true
    error.value = ''
    try {
      const result = await operation()
      // 旧票静默终止（§6.5）：迟到结果不返回给调用方，也不写任何状态。
      if (!session.isCurrent(ticket)) return null
      return result
    } catch (caught: unknown) {
      if (!session.isCurrent(ticket)) return null
      error.value = caught instanceof Error ? caught.message : '未知错误'
      return null
    } finally {
      // loading 释放只归当前票：旧票不得清掉新账号的 loading（reset 已把 loading 归 false）。
      if (session.isCurrent(ticket)) loading.value = false
    }
  }

  /**
   * 只拉未读数（顶栏轮询用）。**刻意不经 {@link run}**：轮询失败（离线、会话过期）不该在界面上
   * 弹红条打断用户，也不该把面板的 loading 点亮。静默失败，下一轮自然恢复。
   * 同 owner 并发（轮询与面板打开叠加）共用一个 pending；旧票迟到结果静默丢弃。
   */
  function refreshUnreadCount(): Promise<number | null> {
    if (pendingUnread && pendingUnreadTicket && session.isCurrent(pendingUnreadTicket)) {
      return pendingUnread
    }
    const ticket = session.capture()
    const attempt = (async () => {
      try {
        const data = await request<{ unreadCount: number }>('/api/me/notifications/unread-count')
        if (!session.isCurrent(ticket)) return null
        unreadCount.value = data.unreadCount
        return data.unreadCount
      } catch {
        return null
      }
    })().finally(() => {
      if (pendingUnread === attempt) {
        pendingUnread = null
        pendingUnreadTicket = null
      }
    })
    pendingUnread = attempt
    pendingUnreadTicket = ticket
    return attempt
  }

  /**
   * 拉第一页（重置游标与列表）。切换「只看未读」也走这里。
   * 同 owner 并发去重：换号后旧 pending 作废（resetForAccount 已清引用），B 重新发起。
   */
  function loadFirstPage(): Promise<NotificationPage | null> {
    if (pendingFirstPage && pendingFirstPageTicket && session.isCurrent(pendingFirstPageTicket)) {
      return pendingFirstPage
    }
    const attempt = (async () => {
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
    })().finally(() => {
      if (pendingFirstPage === attempt) {
        pendingFirstPage = null
        pendingFirstPageTicket = null
      }
    })
    pendingFirstPage = attempt
    pendingFirstPageTicket = session.capture()
    return attempt
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

  /**
   * 账号边界（任务书 #82 C82-01）：清私有状态（列表/游标/错误/loading/未读数）、
   * 停旧轮询并作废在途 pending。换代后由消费方（NotificationBell/面板）启动新加载。
   */
  function clearPrivateState(): void {
    pendingFirstPage = null
    pendingFirstPageTicket = null
    pendingUnread = null
    pendingUnreadTicket = null
    stopPolling()
    items.value = []
    unreadCount.value = 0
    nextBefore.value = null
    nextBeforeId.value = null
    unreadOnly.value = false
    loading.value = false
    error.value = ''
  }

  /** 换 owner 时先清后记；同 owner 重复调用直通（幂等，不误清当前账号数据）。 */
  function resetForAccount(accountId: string | null): void {
    if (ownerAccountId.value === accountId) return
    ownerAccountId.value = accountId
    clearPrivateState()
  }

  watch(
    () => normalizeAccountId(auth.currentUser?.id),
    (accountId) => { resetForAccount(accountId) },
    { flush: 'sync', immediate: true },
  )

  /** 登出/手工清理：清空一切私有态；owner 镜像保持当前归属（账号变化由上面的 watch 负责）。 */
  function reset(): void {
    clearPrivateState()
  }

  /** 按 category 分组，顺序取自 {@link NOTIFICATION_CATEGORY_ORDER}；空组不出现。 */
  const grouped = computed<{ category: NotificationCategory; items: Notification[] }[]>(() =>
    NOTIFICATION_CATEGORY_ORDER
      .map((category) => ({ category, items: items.value.filter((n) => n.category === category) }))
      .filter((group) => group.items.length > 0))

  const hasMore = computed(() => Boolean(nextBefore.value && nextBeforeId.value))

  return {
    ownerAccountId,
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
    resetForAccount,
    resolveLinkTarget,
  }
})
