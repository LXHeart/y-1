/**
 * 历史域 composable（任务书 #105G C105G-01 / 共享契约 K03 API09、API38、API39）。
 *
 * - 列表：owner 由服务端裁定；本层负责请求代次——过滤变更/换号取消旧读并清分页游标，
 *   快切筛选的迟到回包不得覆盖新列表（TC105G-01-04）。
 * - 删除：API38 返回 Operation 后轮询 API39 至终态（有界次数；running/succeeded/failed 如实展示，
 *   不把 running 提前当成功）；换号中止轮询并清状态。
 * - 越界过滤（时间窗 >90 天等）由服务端 422 裁定；本层保留旧数据只置错误，不静默清空。
 */
import { computed, onScopeDispose, ref, type Ref } from 'vue'
import type { DigitalHumanApi } from '../../../composables/useDigitalHumanApi'
import { GrasslandHttpError } from '../../../composables/grassland-http'
import type { AccountSessionPort } from '../../../stores/account-session'
import type { Operation, SessionState, SessionSummary } from '../../../types/digital-human'

/** K01 时间窗上限（客户端预检；服务端仍是最终权威）。 */
export const HISTORY_WINDOW_MAX_DAYS = 90

/** 删除 operation 轮询：间隔与总次数有界，防无限轮询。 */
export const DELETE_POLL_INTERVAL_MS = 800
export const DELETE_POLL_MAX_ATTEMPTS = 20

export interface HistoryFilters {
  profileId: string
  state: SessionState | ''
  /** UTC 日界（YYYY-MM-DD；提交时转 RFC3339 半开区间）。 */
  fromDate: string
  toDate: string
}

export interface DeleteOutcome {
  state: Operation['state']
  message: string
}

export function emptyHistoryFilters(): HistoryFilters {
  return { profileId: '', state: '', fromDate: '', toDate: '' }
}

function messageOf(error: unknown): string {
  if (error instanceof GrasslandHttpError && error.message) return error.message
  if (error instanceof Error && error.message) return error.message
  return '操作失败，请稍后重试。'
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms)
    signal?.addEventListener('abort', () => {
      clearTimeout(timer)
      reject(new DOMException('aborted', 'AbortError'))
    }, { once: true })
  })
}

/** 过滤器 → API09 查询串形状（半开 [from,to)；from/to 为空则缺省）。 */
export function filtersToQuery(filters: HistoryFilters): {
  profileId?: string; state?: SessionState; from?: string; to?: string
} {
  const query: { profileId?: string; state?: SessionState; from?: string; to?: string } = {}
  if (filters.profileId) query.profileId = filters.profileId
  if (filters.state) query.state = filters.state
  if (filters.fromDate) query.from = `${filters.fromDate}T00:00:00Z`
  if (filters.toDate) query.to = `${filters.toDate}T00:00:00Z`
  return query
}

/** 客户端预检：from ≥ to 或跨度 >90 天直接给错误（服务端 422 仍是权威）。 */
export function validateHistoryFilters(filters: HistoryFilters): string | null {
  if (!filters.fromDate || !filters.toDate) return null
  const from = Date.parse(`${filters.fromDate}T00:00:00Z`)
  const to = Date.parse(`${filters.toDate}T00:00:00Z`)
  if (Number.isNaN(from) || Number.isNaN(to)) return '日期格式不正确。'
  if (from >= to) return '时间窗起点必须早于终点。'
  if (to - from > HISTORY_WINDOW_MAX_DAYS * 24 * 60 * 60 * 1000) {
    return `时间窗跨度不能超过 ${HISTORY_WINDOW_MAX_DAYS} 天。`
  }
  return null
}

export function useDigitalHumanHistory(
  api: DigitalHumanApi,
  account: AccountSessionPort,
  options: { pollIntervalMs?: number; pollMaxAttempts?: number } = {},
): {
  items: Ref<SessionSummary[]>
  loading: Ref<boolean>
  error: Ref<string | null>
  filters: Ref<HistoryFilters>
  hasMore: Ref<boolean>
  deletingIds: Ref<ReadonlySet<string>>
  deleteOutcomes: Ref<Readonly<Record<string, DeleteOutcome>>>
  applyFilters: (next: Partial<HistoryFilters>) => void
  reload: () => Promise<void>
  loadMore: () => Promise<void>
  remove: (sessionId: string) => Promise<void>
  clear: () => void
} {
  const items = ref<SessionSummary[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const filters = ref<HistoryFilters>(emptyHistoryFilters())
  const nextCursor = ref<string | null>(null)
  const deletingIds = ref<ReadonlySet<string>>(new Set())
  const deleteOutcomes = ref<Record<string, DeleteOutcome>>({})

  const pollIntervalMs = options.pollIntervalMs ?? DELETE_POLL_INTERVAL_MS
  const pollMaxAttempts = options.pollMaxAttempts ?? DELETE_POLL_MAX_ATTEMPTS

  let listGeneration = 0
  let listAbort: AbortController | null = null

  const hasMore = computed(() => nextCursor.value != null)

  function clearListState(): void {
    items.value = []
    nextCursor.value = null
    error.value = null
  }

  /** 过滤变更：取消旧读、清游标、重置列表后重新拉首页（TC105G-01-04 快切筛选）。 */
  function applyFilters(next: Partial<HistoryFilters>): void {
    const merged = { ...filters.value, ...next }
    const invalid = validateHistoryFilters(merged)
    filters.value = merged
    listGeneration += 1
    listAbort?.abort()
    listAbort = null
    clearListState()
    if (invalid) {
      error.value = invalid
      return
    }
    void fetchFirstPage()
  }

  async function reload(): Promise<void> {
    const invalid = validateHistoryFilters(filters.value)
    if (invalid) {
      error.value = invalid
      return
    }
    listGeneration += 1
    listAbort?.abort()
    listAbort = null
    clearListState()
    await fetchFirstPage()
  }

  async function fetchFirstPage(): Promise<void> {
    const generation = ++listGeneration
    const ticket = account.capture()
    const controller = new AbortController()
    listAbort = controller
    loading.value = true
    error.value = null
    try {
      const page = await api.listSessions(
        { ...filtersToQuery(filters.value), limit: 20 },
        mergeSignals(ticket.signal, controller.signal),
      )
      if (!account.isCurrent(ticket) || generation !== listGeneration) return
      items.value = page.items
      nextCursor.value = page.nextCursor
    } catch (failure) {
      if (isAbort(failure) || !account.isCurrent(ticket) || generation !== listGeneration) return
      error.value = messageOf(failure)
    } finally {
      if (generation === listGeneration) loading.value = false
    }
  }

  async function loadMore(): Promise<void> {
    const cursor = nextCursor.value
    if (cursor == null || loading.value) return
    const generation = ++listGeneration
    const ticket = account.capture()
    const controller = new AbortController()
    listAbort = controller
    loading.value = true
    try {
      const page = await api.listSessions(
        { ...filtersToQuery(filters.value), cursor, limit: 20 },
        mergeSignals(ticket.signal, controller.signal),
      )
      if (!account.isCurrent(ticket) || generation !== listGeneration) return
      items.value = [...items.value, ...page.items]
      nextCursor.value = page.nextCursor
    } catch (failure) {
      if (isAbort(failure) || !account.isCurrent(ticket) || generation !== listGeneration) return
      error.value = messageOf(failure)
    } finally {
      if (generation === listGeneration) loading.value = false
    }
  }

  /** 删除：API38 → 有界轮询 API39 至终态；换号/代次变化即停，不提交迟到结果。 */
  async function remove(sessionId: string): Promise<void> {
    const ticket = account.capture()
    const requestId = crypto.randomUUID()
    deletingIds.value = new Set(deletingIds.value).add(sessionId)
    const outcomes = { ...deleteOutcomes.value }
    delete outcomes[sessionId]
    deleteOutcomes.value = outcomes
    try {
      let operation = await api.deleteSession(sessionId, requestId)
      for (let attempt = 0; attempt < pollMaxAttempts; attempt += 1) {
        if (!account.isCurrent(ticket)) return
        if (operation.state === 'succeeded' || operation.state === 'failed') break
        await sleep(pollIntervalMs, ticket.signal)
        if (!account.isCurrent(ticket)) return
        operation = await api.getOperation(operation.id, ticket.signal)
      }
      if (!account.isCurrent(ticket)) return
      publishOutcome(sessionId, operation)
    } catch (failure) {
      if (isAbort(failure) || !account.isCurrent(ticket)) return
      publishOutcome(sessionId, null, messageOf(failure))
    } finally {
      if (account.isCurrent(ticket)) {
        const next = new Set(deletingIds.value)
        next.delete(sessionId)
        deletingIds.value = next
      }
    }
  }

  function publishOutcome(sessionId: string, operation: Operation | null, failureMessage?: string): void {
    const outcomes = { ...deleteOutcomes.value }
    if (operation == null) {
      outcomes[sessionId] = { state: 'failed', message: failureMessage ?? '删除失败，请稍后重试。' }
    } else if (operation.state === 'succeeded') {
      outcomes[sessionId] = { state: 'succeeded', message: '已删除；已保存的视频/字幕与账务不受影响。' }
      // 成功后从列表移除（服务端已墓碑；本地立即反映，不复活）。
      items.value = items.value.filter((item) => item.id !== sessionId)
    } else if (operation.state === 'failed') {
      outcomes[sessionId] = { state: 'failed', message: '删除失败；可稍后重试。' }
    } else {
      outcomes[sessionId] = {
        state: operation.state,
        message: '清理仍在进行（会话已不可读）；可稍后刷新查看结果。',
      }
    }
    deleteOutcomes.value = outcomes
  }

  /** 换号/离开：清全部本地态（旧账号正文不残留）。 */
  function clear(): void {
    listGeneration += 1
    listAbort?.abort()
    listAbort = null
    deletingIds.value = new Set()
    deleteOutcomes.value = {}
    clearListState()
    loading.value = false
  }

  onScopeDispose(clear)

  return {
    items, loading, error, filters, hasMore, deletingIds, deleteOutcomes,
    applyFilters, reload, loadMore, remove, clear,
  }
}

/** 组合账号信号与代次取消信号（任一触发即中止在途读）。 */
function mergeSignals(...signals: Array<AbortSignal | undefined>): AbortSignal {
  const present = signals.filter((signal): signal is AbortSignal => signal != null)
  if (present.length === 0) return new AbortController().signal
  if (present.length === 1) return present[0]
  const controller = new AbortController()
  for (const signal of present) {
    if (signal.aborted) {
      controller.abort()
      break
    }
    signal.addEventListener('abort', () => controller.abort(), { once: true })
  }
  return controller.signal
}
