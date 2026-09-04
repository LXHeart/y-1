// ---------- trust（争议 / 审判 + 任务书 #74 小法庭）----------

/** 争议状态机（任务书 #74 卡 B 起 6 态：open 为存量兼容/直裁受理态，evidence=举证质证期）。 */
export type DisputeStatus = 'open' | 'evidence' | 'voting' | 'decided' | 'appealed' | 'final'
export type DisputeKind = 'standard' | 'merchant_rejection'
/** 任务书 #74 卡 A（D6）：争议通道——court=小法庭 / cs_direct=客服直裁（提交后不可改）。 */
export type DisputeChannel = 'court' | 'cs_direct'

export interface DisputeCase {
  id: string
  engagementRef: string
  organizationId: string
  openedByAccountId: string
  openedByRole: string
  status: DisputeStatus
  kind: DisputeKind
  channel: DisputeChannel
  reason: string | null
  decision: string | null
  decidedAt: string | null
  round: number
  version: number
  appealState: string | null
  finalDecision: string | null
  csDueAt: string | null
  evidenceDeadline: string | null
  respondentAnswered: boolean
  claimantDoneAt: string | null
  respondentDoneAt: string | null
  taskPlatform: string | null
  createdAt: string | null
}

/**
 * merchant_rejection 活跃期间推荐官异议的显式状态。pending 不暴露客服案 id；promoted 后
 * disputeId 指向自动创建的 standard successor，workflowId 固定为 `adjudicate-<disputeId>`。
 */
export interface DeferredDisputeRequest {
  status: 'pending' | 'promoted'
  requestId: string
  engagementRef: string
  reason: string
  disputeId: string
  workflowId: string
}

/** POST /api/trust/disputes 的判别联合；requestId 绝不能当作 dispute id。 */
export type OpenDisputeResult =
  | { kind: 'dispute'; dispute: DisputeCase }
  | { kind: 'deferred'; request: DeferredDisputeRequest }

/** 审判快照（脱敏：不含审判官 account_id / 个票 rationale）。 */
export interface AdjudicationSnapshot {
  id: string
  status: DisputeStatus
  round: number
  decision: string | null
  appealState: string | null
  finalDecision: string | null
  decidedAt: string | null
  /** 任务书 #74 卡 A/B：通道与质证期元数据。 */
  channel?: DisputeChannel
  csDueAt?: string | null
  evidenceDeadline?: string | null
  claimantDoneAt?: string | null
  respondentDoneAt?: string | null
  respondentAnswered?: boolean
  /** 卡 B（D1）：被诉方缺席仅标注不判负。 */
  respondentAbsent?: boolean
  /** 卡 D/E：硬配额达成率与见习席计数（无身份信息）。 */
  matchedPlatformCount?: number
  probationCount?: number
  panel: { size: number; voted: number }
  tallies: {
    forMerchant: number
    forRecommender: number
    abstain: number
    panelSize: number
    /** 过半方；平票/票数不足时为 null。 */
    majority: 'for_merchant' | 'for_recommender' | null
  }
  /**
   * 当前阶段的时间窗（可观测性）。`phase`: vote=投票窗口 / appeal=上诉窗口 /
   * none=无固定窗口（未开庭、等客服、已终局）。
   *
   * `remainingSeconds` 是**估算展示值**——真正到期由 Temporal Timer 驱动，
   * 二者可能有秒级偏差，不可作判定依据。
   */
  window: {
    phase: 'vote' | 'appeal' | 'none'
    durationSeconds: number
    startedAt: string | null
    deadline: string | null
    remainingSeconds: number | null
  }
  /** 仅 adjudicate 端点返回。 */
  workflowId?: string
}
