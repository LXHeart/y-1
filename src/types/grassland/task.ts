// ---------- marketplace：任务 & 报名 ----------

/** 任务状态（GL-P1-TASK-001 + GL-P2-ADMIN-003 全审政策）：
 * draft=草稿；pending_review=待审核；published=大厅可见；closed=已关闭报名；cancelled=已取消。 */
export type TaskStatus = 'draft' | 'pending_review' | 'published' | 'closed' | 'cancelled'

export interface TaskRequirements {
  productServiceInfo?: string | null
  mustInclude: string[]
  forbiddenContent: string[]
  publishStartAt?: string | null
  publishEndAt?: string | null
  metricRequirements: string[]
  evidenceRequirements: string[]
}

/** 任务书 #24：feed/任务详情行携带的门店公开轻量块（组织级任务无此字段）。 */
export interface TaskStoreBlock {
  storeName: string | null
  city: string | null
  categories: string[]
}

export interface Task {
  id: string
  ownerAccountId: string
  organizationId: string
  /** Optional store scope; null/undefined means legacy organization-level task. */
  storeId?: string | null
  title: string
  description: string | null
  status: TaskStatus
  contentForm: string | null
  platform: string | null
  /** 名额上限；null = 不限。 */
  maxSlots: number | null
  /** 赏金（分）；null/0 = 非资金型任务（accept 走直连，不经资金 Saga）。 */
  bountyCents: number | null
  /** Minimum effective recommender level required to see and apply for this task. */
  minRecommenderLevel: number
  createdAt: string | null
  /** 乐观锁版本号（GL-P1-TASK-001 Stage 1）：draft 编辑 / publish / close / cancel 每次 +1。 */
  version: number
  /** 报名截止时间（ISO）；null = 无时间截止。apply 时判，过期则不接受新报名。 */
  applicationDeadline: string | null
  /** 进入 published 的时刻（ISO）；发布额度/月度统计按它计。draft/cancelled 为 null。 */
  publishedAt: string | null
  /** 取消时刻（ISO）；仅 cancelled 态非空。 */
  cancelledAt: string | null
  /**
   * 本次取消退款的「已接受未提交」履约数（D-03 §5）。**仅 cancel 响应带此字段**
   * （`cancelBody` = task body + refundedCount），list/get 不返回，故可选。
   */
  refundedCount?: number
  /** Present only in a feed response when distance filtering is active. */
  distanceKm?: number
  /** 任务书 #24：门店公开轻量块（storeName/city/categories），门店任务才有。 */
  store?: TaskStoreBlock
  /** PRD 4.12 structured contract frozen into task_version when published or revised. */
  requirements: TaskRequirements
  /** 任务书 #27：自动通过最低等级门槛（1–5）；null = 关闭。开启后对存量待处理报名生效。 */
  autoAcceptMinLevel: number | null
}

export interface CreateTaskInput {
  organizationId: string
  storeId?: string
  title: string
  description?: string
  contentForm?: string
  platform?: string
  maxSlots?: number
  bountyCents?: number
  /** 报名截止时间（ISO）；可空 = 无时间截止。 */
  applicationDeadline?: string
  minRecommenderLevel?: number
  requirements?: Partial<TaskRequirements>
  /** 任务书 #27：自动通过最低等级门槛（1–5）；null/undefined = 关闭。 */
  autoAcceptMinLevel?: number | null
}

/** 创建草稿请求（与 CreateTaskInput 同字段；草稿不占发布额度、不需资金权限）。 */
export type CreateDraftInput = CreateTaskInput

/** 编辑草稿请求（仅 draft 态；expectedVersion 乐观锁）。可空字段 null=清空。 */
export interface UpdateTaskInput {
  expectedVersion: number
  title: string
  description?: string
  contentForm?: string
  platform?: string
  maxSlots?: number
  bountyCents?: number
  applicationDeadline?: string
  minRecommenderLevel?: number
  requirements?: Partial<TaskRequirements>
  autoAcceptMinLevel?: number | null
}

/**
 * 修订已发布任务请求（仅 published 态；GL-P1-TASK-001：编辑出新版本）。
 *
 * 全字段可改——accept/结算已读 task_application.bounty_cents 快照（V14 snapshot-pinning），
 * 故修订 task 赏金/平台只影响新报名（新 app 冻新值），已 accept 的履约仍按其 accept 时快照结算。
 */
export interface ReviseTaskInput {
  expectedVersion: number
  title: string
  description?: string
  contentForm?: string
  platform?: string
  maxSlots?: number
  bountyCents?: number
  applicationDeadline?: string
  minRecommenderLevel?: number
  requirements?: Partial<TaskRequirements>
  autoAcceptMinLevel?: number | null
}

/** 任务书 #27：批量操作单项结果。 */
export interface BatchItemResult {
  applicationId: string
  outcome: 'accepted' | 'reserving' | 'rejected' | 'failed' | string
  commandId?: string
  workflowId?: string
  reason?: string
}

/** 任务书 #27：批量操作响应。 */
export interface BatchOperationResponse {
  results: BatchItemResult[]
}

/** 任务大厅 feed 查询（GL-P1-TASK-001 Stage 2）。 */
export interface TaskFeedQuery {
  platform?: string
  contentForm?: string
  minBountyCents?: number
  latitude?: number
  longitude?: number
  maxDistanceKm?: number
  cursor?: string
  limit?: number
}

/** 任务大厅 feed 分页响应。 */
export interface TaskFeedPage {
  items: Task[]
  nextCursor: string | null
  hasMore: boolean
}

/**
 * 报名状态。`reserving` 是资金型任务 accept 后的中间态——
 * 商家点接受返回 202，资金预留 Saga 异步执行，需轮询 reservation 端点确认最终结果。
 */
/** `refunded` = 商家取消任务且该履约未提交凭证 → 已全额退商家（D-03 §5），终态。 */
export type ApplicationStatus =
  'pending' | 'reserving' | 'accepted' | 'rejected' | 'withdrawn' | 'refunded'

export interface TaskApplication {
  id: string
  taskId: string
  recommenderAccountId: string
  status: ApplicationStatus
  note: string | null
  reviewedByAccountId: string | null
  decidedAt: string | null
  createdAt: string | null
  reputationLevelAtAccept?: number | null
  reputationPolicyVersionAtAccept?: number | null
  settlementDelayDaysAtAccept?: number | null
  commissionBonusBpsAtAccept?: number | null
  premiumSupportAtAccept?: boolean | null
  /** Present in the merchant-ranked application list. */
  reputationLevel?: number
  reputationTitle?: string
  taskPriorityWeight?: number
}

/** 商家拒绝系统核实通过履约后的客服争议状态。 */
export interface MerchantContestOutcome {
  applicationId: string
  status: 'contested'
  reason: string
  disputeId: string
}

/**
 * 资金预留轮询结果。compensated = 预留失败已补偿（reason 说明原因，如 insufficient_funds）。
 *
 * ⚠️ `pending` 是**真实可能的返回值**：accept 返回 202 后、Saga 的 `beginAcceptance`
 * （pending→reserving）尚未执行的窗口内，后端原样回 application 状态
 * （见 `ApplicationController.reservationOutcome` 的 `defaultIfEmpty`）。
 * 它与 `reserving` 同属「在途」，**不是结局**——轮询必须继续。
 */
export interface ReservationOutcome {
  status: 'accepted' | 'reserving' | 'compensated' | 'pending'
  reason?: string
}

/** 结算轮询结果。held = 存在未终局争议，资金暂不 capture（reason 如 open_dispute）。 */
export interface SettlementOutcome {
  status: 'settled' | 'settling' | 'held' | 'not_confirmed'
  reason?: string
}
