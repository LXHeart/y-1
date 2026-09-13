import { onScopeDispose, ref } from 'vue'
import type { VisualJob, VisualQuote } from '../../../types/creation-studio'
import type { useVisualPlan } from './useVisualPlan'

/**
 * 任务书 #101 C101-11（TC101-052~055）：视觉任务 API 客户端。
 *
 * 先确认计划和 quote 再发 job（同键幂等）；轮询 2s、60s 后 5s，隐藏/失活暂停、激活立即读取，
 * 30min 后停止并提供手动刷新（§5.6）；迟到响应用 epoch 丢弃（账号切换/卸载后旧响应不落地）。
 * 候选只 emit item/artifact 引用——「已采用」判定属 12 卡，本 composable 不假报保存成功。
 */

const POLL_FAST_MS = 2000
const POLL_SLOW_MS = 5000
const SLOW_AFTER_MS = 60_000
const POLL_LIMIT_MS = 30 * 60_000

export function useVisualJob(options: {
  plan: () => ReturnType<typeof useVisualPlan>['current']['value']
  onError?: (message: string) => void
}) {
  const current = ref<VisualJob | null>(null)
  const quote = ref<VisualQuote | null>(null)
  const quoting = ref(false)
  const creating = ref(false)
  const cancelling = ref(false)
  const error = ref('')
  const polling = ref(false)
  const pollTimedOut = ref(false)
  /** 已选候选（预选态——父层确认成功前不显示已采用）。 */
  const selectedCandidate = ref<{ itemId: string; artifactId: string } | null>(null)

  let epoch = 0
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let pollElapsedMs = 0
  let pendingCreateKey: string | null = null
  let pendingCreateRequestId: string | null = null

  onScopeDispose(() => {
    epoch += 1
    stopPolling()
  })

  async function request<T>(url: string, init: RequestInit): Promise<T | null> {
    const response = await fetch(url, init)
    const body = await response?.json?.().catch(() => null) as { success?: boolean; data?: T; error?: string } | null
    if (!response?.ok || !body?.success) {
      error.value = body?.error || '请求失败，请重试'
      return null
    }
    return body.data ?? null
  }

  function isTerminal(state: string | undefined): boolean {
    return state === 'succeeded' || state === 'partial' || state === 'failed' || state === 'cancelled'
      || state === 'unknown'
  }

  // ---- 估算（API101-12，经 useVisualPlan 的 plan 上下文） ----

  async function estimate(selectedItemIds: string[], consistencyMode: 'prompt-only' | 'reference-image' = 'prompt-only'): Promise<VisualQuote | null> {
    const plan = options.plan()
    if (!plan || quoting.value) return null
    quoting.value = true
    error.value = ''
    try {
      const data = await request<VisualQuote>(`/api/creation-studio/visual-plans/${plan.id}/estimate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          requestId: crypto.randomUUID(),
          expectedRevision: plan.revision,
          selectedItemIds,
          consistencyMode,
        }),
      })
      if (data) quote.value = data
      return data
    } finally {
      quoting.value = false
    }
  }

  // ---- 创建（API101-13，同键幂等） ----

  async function create(input: {
    selectedItemIds: string[]
    consistencyMode?: 'prompt-only' | 'reference-image'
    quoteId: string
    acknowledgedUnknownAttemptIds?: string[]
  }): Promise<VisualJob | null> {
    const plan = options.plan()
    if (!plan || creating.value) return null
    const requestEpoch = ++epoch
    stopPolling()
    creating.value = true
    error.value = ''
    const key = JSON.stringify([plan.id, plan.revision, input.quoteId, input.selectedItemIds,
      input.consistencyMode ?? 'prompt-only', input.acknowledgedUnknownAttemptIds ?? []])
    if (pendingCreateKey !== key) {
      pendingCreateKey = key
      pendingCreateRequestId = crypto.randomUUID()
    }
    try {
      const job = await request<VisualJob>('/api/creation-studio/visual-jobs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          requestId: pendingCreateRequestId,
          plan: { id: plan.id, revision: plan.revision },
          quoteId: input.quoteId,
          selectedItemIds: input.selectedItemIds,
          consistencyMode: input.consistencyMode ?? 'prompt-only',
          ...(input.acknowledgedUnknownAttemptIds?.length
            ? { acknowledgedUnknownAttemptIds: input.acknowledgedUnknownAttemptIds } : {}),
        }),
      })
      if (requestEpoch !== epoch) return null
      if (!job) {
        // 失败不清同键：无响应重试沿用同 requestId（§6.1 安全重放）；换参数自然换新键。
        return null
      }
      current.value = job
      startPolling()
      return job
    } finally {
      if (requestEpoch === epoch) creating.value = false
    }
  }

  // ---- 轮询（§5.6：2s→5s；隐藏暂停；30min 停止） ----

  function startPolling(): void {
    stopPolling()
    if (!current.value || isTerminal(current.value.state)) return
    polling.value = true
    pollElapsedMs = 0
    scheduleNext(POLL_FAST_MS)
  }

  function scheduleNext(intervalMs: number): void {
    pollTimer = setTimeout(() => { void tick() }, intervalMs)
  }

  async function tick(): Promise<void> {
    if (!current.value) return
    const jobId = current.value.id
    const jobEpoch = epoch
    const interval = pollElapsedMs >= SLOW_AFTER_MS ? POLL_SLOW_MS : POLL_FAST_MS
    pollElapsedMs += interval
    if (pollElapsedMs > POLL_LIMIT_MS) {
      polling.value = false
      pollTimedOut.value = true
      return
    }
    if (typeof document !== 'undefined' && document.hidden) {
      // 隐藏：暂停轮询（标志同步落下），恢复可见 resume() 立即读取并续轮询
      pollTimer = null
      polling.value = false
      return
    }
    const job = await request<VisualJob>(`/api/creation-studio/visual-jobs/${jobId}`, { method: 'GET' })
    if (jobEpoch !== epoch) return
    if (job) current.value = job
    if (!current.value || isTerminal(current.value.state)) {
      polling.value = false
      return
    }
    scheduleNext(interval)
  }

  /** 页面重新可见/组件激活：立即读取并继续轮询（§4.4 KeepAlive 失活恢复）。 */
  function resume(): void {
    if (!current.value || isTerminal(current.value.state) || polling.value) return
    polling.value = true
    pollElapsedMs = 0
    pollTimedOut.value = false
    void tick()
  }

  /** 离开页面/失活：停止轮询与 UI 更新（后台任务按已接受操作继续）。 */
  function pause(): void {
    stopPolling()
  }

  function stopPolling(): void {
    if (pollTimer != null) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
    polling.value = false
  }

  async function refresh(jobId?: string): Promise<void> {
    const id = jobId ?? current.value?.id
    if (!id) return
    const requestEpoch = ++epoch
    stopPolling()
    const job = await request<VisualJob>(`/api/creation-studio/visual-jobs/${id}`, { method: 'GET' })
    if (requestEpoch !== epoch || !job) return
    current.value = job
    if (!isTerminal(job.state)) startPolling()
  }

  /** 恢复历史任务（刷新后按 studio.activeVisualJobId 读回）。 */
  async function restore(jobId: string): Promise<void> {
    await refresh(jobId)
  }

  // ---- 取消（API101-16） ----

  async function cancel(): Promise<boolean> {
    if (!current.value || cancelling.value) return false
    cancelling.value = true
    error.value = ''
    try {
      const job = await request<VisualJob>(`/api/creation-studio/visual-jobs/${current.value.id}/cancel`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ requestId: crypto.randomUUID(), expectedVersion: current.value.version }),
      })
      if (!job) return false
      current.value = job
      return true
    } finally {
      cancelling.value = false
    }
  }

  /** 用户主动重做：新 quote + 新 key + 显式范围（未确认的 unknown 旧 attempt 需 ack）。 */
  async function redo(selectedItemIds: string[], acknowledgedUnknownAttemptIds: string[] = []): Promise<VisualJob | null> {
    const fresh = await estimate(selectedItemIds)
    if (!fresh) return null
    pendingCreateKey = null
    return create({ selectedItemIds, quoteId: fresh.id, acknowledgedUnknownAttemptIds })
  }

  function selectCandidate(itemId: string, artifactId: string): void {
    selectedCandidate.value = { itemId, artifactId }
  }

  function dismiss(): void {
    epoch += 1
    stopPolling()
    current.value = null
    quote.value = null
    error.value = ''
    selectedCandidate.value = null
    pendingCreateKey = null
    pollTimedOut.value = false
  }

  return {
    current, quote, quoting, creating, cancelling, error, polling, pollTimedOut, selectedCandidate,
    estimate, create, refresh, restore, cancel, redo, resume, pause, selectCandidate, dismiss,
  }
}

export type VisualJobController = ReturnType<typeof useVisualJob>
