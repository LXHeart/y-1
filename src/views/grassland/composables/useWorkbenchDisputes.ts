import { onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { useGrassland } from '../../../composables/useGrassland'
import type { DisputeChannel, TaskApplication } from '../../../types/grassland'

/**
 * 工作台争议域：deferred 客服案的 promotion 低频轮询 + 开争议交互。
 *
 * 2026-09-04 反馈 5：工作台底部「争议与平台治理」区撤除——开争议（即时案）与 promotion
 * 升格后统一跳 `/me/disputes/:id` 案件详情页（方案 α 的当事方主阵地）；requestId 与
 * disputeId 仍严格分离——deferred 案先落 durable request，客服案终局后才升格为普通争议。
 */
export function useWorkbenchDisputes(
  grassland: ReturnType<typeof useGrassland>,
  setNotice: (message: string) => void,
) {
  const router = useRouter()
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
        setNotice('普通争议已自动开启并进入七官审判流程')
        void router.push(`/me/disputes/${request.disputeId}`)
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

  /** #77 卡 D：参数放宽为 { id }——详情弹窗上下文只有 applicationId（my-applications 投影行）。 */
  function dispute(app: { id: string }): void {
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
      deferredDisputeRequestId.value = opened.request.requestId
      setNotice('异议已记录，客服案终局后自动开普通争议')
      scheduleDeferredPoll(opened.request.requestId)
      return
    }
    clearDeferredPoll()
    deferredDisputeRequestId.value = ''
    const channelNote = opened.dispute.channel === 'cs_direct'
      ? '客服直裁通道：平台客服将在 5 天内裁决'
      : '小法庭通道：双方有 48 小时举证质证期，随后自动开庭'
    setNotice(`争议已开启（${channelNote}），结算将被暂停`)
    void router.push(`/me/disputes/${opened.dispute.id}`)
  }

  /** 账号切换清空（原 resetAccountState 的争议字段）。 */
  function reset(): void {
    clearDeferredPoll()
    deferredDisputeRequestId.value = ''
  }

  return {
    deferredDisputeRequestId,
    disputePromptAppId, disputeChannel, dispute, cancelDispute, confirmDispute,
    reset,
  }
}
