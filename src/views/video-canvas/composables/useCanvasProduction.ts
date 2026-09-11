import { computed } from 'vue'
import type { Ref } from 'vue'
import { useVideoTaskSession } from '../../../composables/useVideoTaskSession'
import type { VideoTaskSessionHost } from '../../../composables/useVideoTaskSession'

/**
 * 任务书 #100 C100-05：画布侧任务会话适配。
 *
 * 专业模式消费共享任务会话（§6.8）——与快速模式同账号同 task 只有一条事件通道；
 * 画布失活自动释放消费者，最后一个消费者离开才停浏览器连接，服务端任务不受影响。
 * C100-06/07 的运行栏/候选比较经本适配取任务态与写方法；taskId 引用在装配期传入
 * （画布绑定 workspace 的 productionTaskId），禁止在按钮回调里重新获取。
 */
export function useCanvasProduction(taskId: Ref<string>) {
  const session: VideoTaskSessionHost = useVideoTaskSession(taskId)

  /** 合成期（成片混流中）——运行栏展示「合成中」。 */
  const composing = computed(() => session.task.value?.phase === 'composing')
  /** 终态（含 succeeded）——运行栏展示结果与后续动作。 */
  const terminal = computed(() =>
    ['succeeded', 'failed', 'cancelled'].includes(session.task.value?.phase ?? ''))
  /** 存在未确认选片时禁用合成（§4.3）。 */
  const composeBlocked = computed(() => session.pendingSelectionCount.value > 0)

  return {
    session,
    taskId,
    composing,
    terminal,
    composeBlocked,
    /** 会话透传（宿主扩展面，画布内部消费）。 */
    eventsDegraded: session.eventsDegraded,
    composeSubmitting: session.composeSubmitting,
    pendingSelectionCount: session.pendingSelectionCount,
  }
}
