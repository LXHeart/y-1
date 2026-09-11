import { ref, watch } from 'vue'
import type { Ref } from 'vue'
import { request } from '../../../composables/grassland-http'
import type {
  ApplyCanvasPlanResult,
  CanvasPlanResult,
} from '../../../types/video-canvas'

/**
 * 任务书 #100 C100-18：画布 AI 计划会话（API-13/14/15）。
 *
 * - 提交：operationId 生成一次，同键重试（202 preparing 轮询；丢响应不换键）。
 * - 查询：preparing 每 2s 轮询，离页/切换停止（最多 120s）；账号/项目 epoch 变化丢弃旧响应。
 * - apply：成功才标记已应用并回调刷新；409 保留计划提示按新版本重新提问（不自动再收费）。
 */
export interface UseCanvasAgentOptions {
  draftId: Ref<string>
  storyboardId: Ref<string>
  /** epoch 变化即丢弃旧响应（账号切换/项目切换）。 */
  epoch: Ref<number | string>
  onApplied: () => Promise<void> | void
}

const POLL_INTERVAL_MS = 2000
const POLL_DEADLINE_MS = 120_000

export function useCanvasAgent(options: UseCanvasAgentOptions) {
  const { draftId, storyboardId, epoch, onApplied } = options

  const plan = ref<CanvasPlanResult | null>(null)
  const appliedResult = ref<ApplyCanvasPlanResult | null>(null)
  const error = ref('')
  const submitting = ref(false)
  const applying = ref(false)
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let pollDeadline = 0
  let pendingOperationId: string | null = null

  function stopPolling(): void {
    if (pollTimer != null) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  function reset(): void {
    stopPolling()
    plan.value = null
    appliedResult.value = null
    error.value = ''
    pendingOperationId = null
  }

  // epoch 变化：丢弃旧响应停止轮询（账号/项目切换）
  watch(() => epoch.value, () => reset(), { flush: 'sync' })

  /** 提交修改请求：有挂起键先原键重试；clarify（空选择）也是服务端固定引导。 */
  async function submit(input: {
    selectedNodeIds: string[]
    expectedEditVersion: number
    expectedCanvasRevision: number
    instruction: string
  }): Promise<void> {
    if (submitting.value) return
    submitting.value = true
    error.value = ''
    const operationId = pendingOperationId ?? crypto.randomUUID()
    pendingOperationId = operationId
    try {
      const result = await request<CanvasPlanResult>('/api/creation-assistant/canvas/plans', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          operationId,
          draftId: draftId.value,
          storyboardId: storyboardId.value,
          selectedNodeIds: input.selectedNodeIds,
          expectedEditVersion: input.expectedEditVersion,
          expectedCanvasRevision: input.expectedCanvasRevision,
          instruction: input.instruction,
        }),
      })
      plan.value = result
      if (result.status !== 'preparing') {
        pendingOperationId = null
      } else {
        pollDeadline = Date.now() + POLL_DEADLINE_MS
        schedulePoll(result.id)
      }
    } catch (err) {
      error.value = err instanceof Error ? err.message : '计划请求失败（可原键重试）'
    } finally {
      submitting.value = false
    }
  }

  function schedulePoll(planId: string): void {
    stopPolling()
    pollTimer = setTimeout(async () => {
      if (Date.now() >= pollDeadline) {
        error.value = '计划生成超时，保留运行追踪（不自动再次请求）'
        return
      }
      try {
        const result = await request<CanvasPlanResult>(
          `/api/creation-assistant/canvas/plans/${encodeURIComponent(planId)}`)
        plan.value = result
        if (result.status === 'preparing') {
          schedulePoll(planId)
        }
      } catch {
        error.value = '计划查询失败，已停止轮询'
      }
    }, POLL_INTERVAL_MS)
  }

  /** 应用计划：成功标记并刷新权威数据；409 保留计划提示重新提问。 */
  async function apply(): Promise<boolean> {
    if (!plan.value || plan.value.status !== 'ready' || applying.value) return false
    applying.value = true
    error.value = ''
    try {
      const result = await request<ApplyCanvasPlanResult>(
        `/api/creation-assistant/canvas/plans/${encodeURIComponent(plan.value.id)}/apply`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({}),
        })
      appliedResult.value = result
      plan.value = { ...plan.value, status: 'applied' }
      await onApplied()
      return true
    } catch (err) {
      const status = (err as { status?: number }).status
      if (status === 409) {
        error.value = '画布已变化——计划保留，请按新版本重新提问（不会自动再次收费）'
      } else {
        error.value = err instanceof Error ? err.message : '计划应用失败'
      }
      return false
    } finally {
      applying.value = false
    }
  }

  return {
    plan,
    appliedResult,
    error,
    submitting,
    applying,
    submit,
    apply,
    reset,
    stopPolling,
    retryPending: submit,
  }
}
