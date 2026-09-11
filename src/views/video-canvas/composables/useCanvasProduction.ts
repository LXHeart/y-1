import { computed, ref } from 'vue'
import type { Ref } from 'vue'
import { request } from '../../../composables/grassland-http'
import { useVideoTaskSession, isSelectionComplete } from '../../../composables/useVideoTaskSession'
import type { VideoTaskSessionHost } from '../../../composables/useVideoTaskSession'
import type { VideoTask } from '../../../types/video-production'

/**
 * 任务书 #100 C100-05/C100-07：画布侧任务会话适配与制作主行动。
 *
 * 专业模式消费共享任务会话（§6.8）——与快速模式同账号同 task 只有一条事件通道；
 * 画布失活自动释放消费者，最后一个消费者离开才停浏览器连接，服务端任务不受影响。
 * 发起制作（API-05）复用 `web-{storyboardId}` 既有幂等键：快速/专业同键重放同一任务，
 * 重入只恢复不重复计费；提交前先清空未保存输入（编辑器/布局 flush），失败停留画布。
 */
export interface UseCanvasProductionOptions {
  /** 发起制作前清空未保存输入（编辑器/布局 flush）；返回 false 中止提交并停留当前页。 */
  flushBeforeCreate?: () => Promise<boolean> | boolean
}

/** 阶段展示词（§4：不把 202 受理当成功，只有服务端 phase/progress 是真相）。 */
const PHASE_LABELS: Record<string, string> = {
  queued: '排队中',
  generating: '生成中',
  voicing: '配音中',
  composing: '合成中',
  succeeded: '已完成',
  failed: '失败',
  cancelled: '已取消',
}

export function useCanvasProduction(taskId: Ref<string>, options: UseCanvasProductionOptions = {}) {
  const session: VideoTaskSessionHost = useVideoTaskSession(taskId)
  const createError = ref('')
  const creating = ref(false)

  const task = computed(() => session.task.value)
  /** 合成期（成片混流中）——运行栏展示「合成中」。 */
  const composing = computed(() => task.value?.phase === 'composing')
  /** 终态（含 succeeded）——运行栏展示结果与后续动作。 */
  const terminal = computed(() =>
    ['succeeded', 'failed', 'cancelled'].includes(task.value?.phase ?? ''))
  const phaseLabel = computed(() => PHASE_LABELS[task.value?.phase ?? ''] ?? '')
  /** 存在未确认选片时禁用合成（§4.3：未确认选择不能合成）。 */
  const composeBlocked = computed(() => session.pendingSelectionCount.value > 0)
  const selectionComplete = computed(() => isSelectionComplete(task.value))

  /**
   * 专业模式发起制作（API-05）：同初始制作与快速模式共用 `web-{storyboardId}` 幂等键——
   * 服务端按账号+操作键重放返回同一任务，不因模式切换换键、不产生第二份费用。
   */
  async function beginProduction(storyboardId: string): Promise<boolean> {
    if (!storyboardId || creating.value) return false
    // 重放状态：已有同分镜任务只恢复通道（重入不重复请求）
    const existing = task.value
    if (existing && existing.storyboardId === storyboardId) {
      session.resumeChannel()
      return true
    }
    if (options.flushBeforeCreate) {
      const flushed = await options.flushBeforeCreate()
      if (!flushed) {
        createError.value = '有未保存的修改，已停留在画布——请先保存再发起制作'
        return false
      }
    }
    creating.value = true
    createError.value = ''
    try {
      const created = await request<{ id: string }>('/api/video-production/tasks', {
        method: 'POST',
        body: JSON.stringify({ storyboardId, operationId: `web-${storyboardId}` }),
      }, { fallbackError: '成片任务创建失败' })
      if (!created?.id) throw new Error('成片任务创建失败')
      taskId.value = created.id
      await session.refreshTask()
      return true
    } catch (err: unknown) {
      createError.value = err instanceof Error ? err.message : '成片任务创建失败'
      return false
    } finally {
      creating.value = false
    }
  }

  return {
    session,
    taskId,
    task,
    composing,
    terminal,
    phaseLabel,
    composeBlocked,
    selectionComplete,
    createError,
    creating,
    beginProduction,
    /** 会话透传（宿主扩展面，画布内部消费）。 */
    eventsDegraded: session.eventsDegraded,
    composeSubmitting: session.composeSubmitting,
    pendingSelectionCount: session.pendingSelectionCount,
  }
}

export type CanvasProduction = ReturnType<typeof useCanvasProduction>

/** 任务终态判定（画布交付面板复用）。 */
export function isTaskTerminal(task: VideoTask | null): boolean {
  return !!task && ['succeeded', 'failed', 'cancelled'].includes(task.phase)
}
