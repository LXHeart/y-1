import { onBeforeUnmount, ref } from 'vue'
import type { useGrassland } from '../../../composables/useGrassland'
import type { DisputeChannel, TaskApplication } from '../../../types/grassland'

/**
 * 工作台争议域：审判看板挂载 + deferred 客服案的 promotion 低频轮询。
 *
 * 从 GrasslandWorkbench.vue 原样迁出（行为不变）；requestId 与 disputeId 严格分离——
 * deferred 案先落 durable request，客服案终局后才升格为普通争议并挂审判看板。
 */
export function useWorkbenchDisputes(
  grassland: ReturnType<typeof useGrassland>,
  setNotice: (message: string) => void,
) {
  /** 当前查看的争议 id——即时开案或 deferred promotion 后挂载审判看板。 */
  const activeDisputeId = ref('')
  /** 待客服案终局的推荐官异议 request；requestId 与 disputeId 严格分离。 */
  const deferredDisputeRequestId = ref('')
  let deferredPollTimer: ReturnType<typeof setTimeout> | null = null
  const DEFERRED_POLL_MS = 3000

  function clearDeferredPoll(): void {
    if (deferredPollTimer !== null) {
      clearTimeout(deferredPollTimer)
      deferredPollTimer = null
    }
  }

  function scheduleDeferredPoll(requestId: string): void {
    clearDeferredPoll()
    deferredPollTimer = setTimeout(async () => {
      deferredPollTimer = null
      // 账号/视角切换或新请求已替代旧请求时，不让过期回调继续更新 UI。
      if (deferredDisputeRequestId.value !== requestId) return
      const request = await grassland.getDisputeRequest(requestId)
      if (deferredDisputeRequestId.value !== requestId) return
      if (!request) {
        // 暂时失败保留 durable request，低频重试；error 同时给用户可见。
        scheduleDeferredPoll(requestId)
        return
      }
      if (request.status === 'promoted' && request.disputeId) {
        deferredDisputeRequestId.value = ''
        activeDisputeId.value = request.disputeId
        setNotice('普通争议已自动开启并进入七官审判流程')
        return
      }
      scheduleDeferredPoll(requestId)
    }, DEFERRED_POLL_MS)
  }

  onBeforeUnmount(clearDeferredPoll)

  /**
   * 任务书 #74 卡 A（D6）：通道由提异议方自选且提交后不可改。
   * dispute(app) 只展开通道选择；confirmDispute() 才真正提交。
   */
  const disputePromptAppId = ref('')
  const disputeChannel = ref<DisputeChannel>('court')

  function dispute(app: TaskApplication): void {
    disputeChannel.value = 'court'
    disputePromptAppId.value = app.id
  }

  function cancelDispute(): void {
    disputePromptAppId.value = ''
  }

  async function confirmDispute(): Promise<void> {
    const engagementRef = disputePromptAppId.value
    if (!engagementRef) return
    const opened = await grassland.openDispute(engagementRef, '履约存在争议', disputeChannel.value)
    disputePromptAppId.value = ''
    if (!opened) return
    if (opened.kind === 'deferred') {
      activeDisputeId.value = ''
      deferredDisputeRequestId.value = opened.request.requestId
      setNotice('异议已记录，客服案终局后自动开普通争议')
      scheduleDeferredPoll(opened.request.requestId)
      return
    }
    clearDeferredPoll()
    deferredDisputeRequestId.value = ''
    activeDisputeId.value = opened.dispute.id
    const channelNote = opened.dispute.channel === 'cs_direct'
      ? '客服直裁通道：平台客服将在 5 天内裁决'
      : '小法庭通道：双方有 48 小时举证质证期，随后自动开庭'
    setNotice(`争议已开启（${channelNote}），结算将被暂停`)
  }

  /** 账号切换清空（原 resetAccountState 的争议字段）。 */
  function reset(): void {
    clearDeferredPoll()
    deferredDisputeRequestId.value = ''
    activeDisputeId.value = ''
  }

  return {
    activeDisputeId, deferredDisputeRequestId,
    disputePromptAppId, disputeChannel, dispute, cancelDispute, confirmDispute,
    reset,
  }
}
