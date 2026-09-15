import { ref } from 'vue'
import type { Ref } from 'vue'
import { GrasslandHttpError, request } from '../../../composables/grassland-http'
import type { AdjudicationSnapshot } from '../../../types/grassland/dispute'
import type { DisputeActionContext, DisputeCaseSession } from './useDisputeCaseSession'

/**
 * 争议写动作（任务书 #103 C103-12 / §6.7）。
 *
 * - 只接收 {@link DisputeCaseSession.captureAction} 捕获的不可变上下文：URL 在 await 前
 *   由 `context.disputeId` 冻结，await 之后不重取 route ID；
 * - 提交前校验上下文（切案/换号/失活 → 本地拒绝，不发请求）；结果更新再次校验，
 *   迟到回包不得写进新案件；
 * - 结果未知（网络层失败，非 HTTP 错误响应）进入核实模式：回读原案事实，已接受则收口，
 *   未能证明显示「待核实」——绝不自动重发无幂等写；
 * - HTTP 409/4xx 为确定失败：展示服务端人话文案，同案可恢复输入。
 */
export type DisputeWriteOutcome = 'accepted' | 'rejected_local' | 'failed' | 'unverified'

export type DisputeWriteStatus = 'idle' | 'submitting' | 'verifying'

export type DisputeEvidenceActionKind = 'answer' | 'rebuttal' | 'done' | 'start'

export interface DisputeEvidenceItemInput {
  kind: 'text' | 'screenshot' | 'link'
  contentRef: string
  caption?: string
}

export interface DisputeEvidenceActions {
  /** 提交中 / 核实中 / 空闲（结果态由单个动作调用的返回值驱动视图）。 */
  readonly status: Ref<DisputeWriteStatus>
  /** 最近一次确定失败（HTTP/本地拒绝）的服务端/本地文案；空串表示无待处理失败。 */
  readonly error: Ref<string>
  /** 当前动作绑定的案件 ID（上下文冻结值；与视图目标不一致时提示区不显示）。 */
  readonly lastTargetDisputeId: Ref<string | null>
  /** 当前动作种类（提示区与表单联动用）。 */
  readonly lastAction: Ref<DisputeEvidenceActionKind | null>
  submitEvidence: (context: DisputeActionContext, phase: 'answer' | 'rebuttal', items: DisputeEvidenceItemInput[]) => Promise<DisputeWriteOutcome>
  markEvidenceDone: (context: DisputeActionContext) => Promise<DisputeWriteOutcome>
  startAdjudication: (context: DisputeActionContext) => Promise<DisputeWriteOutcome>
  /** 清除失败提示（重开表单/切案时）。 */
  clearError: () => void
}

export function useDisputeEvidenceActions(session: DisputeCaseSession): DisputeEvidenceActions {
  const status = ref<DisputeWriteStatus>('idle')
  const error = ref('')
  const lastTargetDisputeId = ref<string | null>(null)
  const lastAction = ref<DisputeEvidenceActionKind | null>(null)

  function clearError(): void {
    error.value = ''
  }

  /** 回读原案事实判断写是否已被服务端接受（answer/done/start 可证；rebuttal 无案面字段不可证）。 */
  function provenAccepted(kind: DisputeEvidenceActionKind): boolean {
    const dispute = session.dispute.value
    if (!dispute || dispute.id !== lastTargetDisputeId.value) return false
    if (kind === 'answer') return dispute.respondentAnswered === true
    if (kind === 'done') {
      return dispute.viewerRole === 'claimant'
        ? dispute.claimantDoneAt != null
        : dispute.respondentDoneAt != null
    }
    if (kind === 'start') {
      return dispute.status === 'voting' || dispute.status === 'decided' || dispute.status === 'appealed' || dispute.status === 'final'
    }
    return false
  }

  /**
   * 结果未知（网络层失败）时的核实流程：回读原案 → 已接受收口；未能证明待核实。
   * 上下文已失效（切案/换号）时不再展示核实结论——回读仍会刷新当前目标的事实。
   */
  async function verifyAfterUnknownFailure(context: DisputeActionContext, kind: DisputeEvidenceActionKind): Promise<DisputeWriteOutcome> {
    status.value = 'verifying'
    try {
      await session.refresh()
    } catch {
      // 回读失败：保持待核实，不自动重发
    }
    status.value = 'idle'
    if (!context.isCurrent()) return 'unverified'
    return provenAccepted(kind) ? 'accepted' : 'unverified'
  }

  async function write<T>(
    context: DisputeActionContext,
    kind: DisputeEvidenceActionKind,
    url: string,
    init: RequestInit,
    onAccepted: () => Promise<void> | void,
  ): Promise<DisputeWriteOutcome> {
    // 提交前校验：上下文失效（切案/换号/失活）本地拒绝，绝不发请求。
    if (!context.isCurrent()) {
      lastTargetDisputeId.value = context.disputeId
      lastAction.value = kind
      error.value = '案件目标已变化，操作未发送'
      return 'rejected_local'
    }
    lastTargetDisputeId.value = context.disputeId
    lastAction.value = kind
    error.value = ''
    status.value = 'submitting'
    // URL 已由调用方从 context.disputeId 冻结 —— await 之后不再重取 route ID。
    try {
      await request<T>(url, init)
      // 结果更新再次校验：迟到回包不得写进新案件（服务端事实已成立，读取面由 refresh 收敛）。
      if (context.isCurrent()) await onAccepted()
      status.value = 'idle'
      return 'accepted'
    } catch (caught: unknown) {
      status.value = 'idle'
      if (!context.isCurrent()) return 'unverified'
      if (caught instanceof GrasslandHttpError) {
        // 确定失败：409 业务冲突/4xx 校验等，展示服务端人话文案；同案可恢复输入。
        error.value = caught.message
        return 'failed'
      }
      // 网络层失败（结果未知）：核实模式——回读原案，不自动重发。
      return verifyAfterUnknownFailure(context, kind)
    }
  }

  async function submitEvidence(
    context: DisputeActionContext,
    phase: 'answer' | 'rebuttal',
    items: DisputeEvidenceItemInput[],
  ): Promise<DisputeWriteOutcome> {
    // URL/phase/items 在 await 前全部冻结。
    const url = `/api/trust/disputes/${encodeURIComponent(context.disputeId)}/evidence`
    const body = JSON.stringify({ items: items.map((item) => ({ ...item })), phase })
    return write<{ submitted: number }>(context, phase, url, { method: 'POST', body }, async () => {
      await session.refresh()
    })
  }

  async function markEvidenceDone(context: DisputeActionContext): Promise<DisputeWriteOutcome> {
    const url = `/api/trust/disputes/${encodeURIComponent(context.disputeId)}/evidence-done`
    return write<{ claimantDoneAt: string | null; respondentDoneAt: string | null; bothDone: boolean }>(
      context, 'done', url, { method: 'POST' }, async () => {
        await session.refresh()
      },
    )
  }

  async function startAdjudication(context: DisputeActionContext): Promise<DisputeWriteOutcome> {
    const url = `/api/trust/disputes/${encodeURIComponent(context.disputeId)}/adjudicate`
    return write<AdjudicationSnapshot>(context, 'start', url, { method: 'POST' }, async () => {
      await session.refresh()
    })
  }

  return {
    status,
    error,
    lastTargetDisputeId,
    lastAction,
    submitEvidence,
    markEvidenceDone,
    startAdjudication,
    clearError,
  }
}
