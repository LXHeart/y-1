/**
 * 草场（Grassland）Java 微服务域的前端类型。
 *
 * 字段与各服务 controller 的 `toBody` 一一对应（marketplace TaskController/ApplicationController、
 * trust DisputeController/AdjudicationController、finance EscrowController）。
 * 时间字段均为 ISO-8601 字符串（Java `Instant.toString()`），可空。
 *
 * 请求经 vite proxy → edge-bff（:8081）→ 对应 Java 服务；BFF 按 RouteManifest 分流并签发内部身份断言。
 */

/** 统一响应信封（与 legacy Express 一致：{success, data} / {success:false, error}）。 */
export interface GrasslandResponse<T> {
  success: boolean
  data?: T
  error?: string
}

// ---------- marketplace ----------

/** 任务状态：published=大厅可见；closed=已关闭（不占活跃额度）。 */
export type TaskStatus = 'published' | 'closed'

export interface Task {
  id: string
  ownerAccountId: string
  organizationId: string
  title: string
  description: string | null
  status: TaskStatus
  contentForm: string | null
  platform: string | null
  /** 名额上限；null = 不限。 */
  maxSlots: number | null
  /** 赏金（分）；null/0 = 非资金型任务（accept 走直连，不经资金 Saga）。 */
  bountyCents: number | null
  createdAt: string | null
}

export interface CreateTaskInput {
  organizationId: string
  title: string
  description?: string
  contentForm?: string
  platform?: string
  maxSlots?: number
  bountyCents?: number
}

/**
 * 报名状态。`reserving` 是资金型任务 accept 后的中间态——
 * 商家点接受返回 202，资金预留 Saga 异步执行，需轮询 reservation 端点确认最终结果。
 */
export type ApplicationStatus = 'pending' | 'reserving' | 'accepted' | 'rejected' | 'withdrawn'

export interface TaskApplication {
  id: string
  taskId: string
  recommenderAccountId: string
  status: ApplicationStatus
  note: string | null
  reviewedByAccountId: string | null
  decidedAt: string | null
  createdAt: string | null
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

// ---------- trust（争议 / 审判）----------

/** 争议状态机（5 态）。非 final 均阻塞结算。 */
export type DisputeStatus = 'open' | 'voting' | 'decided' | 'appealed' | 'final'

export interface DisputeCase {
  id: string
  engagementRef: string
  organizationId: string
  openedByAccountId: string
  openedByRole: string
  status: DisputeStatus
  reason: string | null
  decision: string | null
  decidedAt: string | null
  round: number
  version: number
  appealState: string | null
  finalDecision: string | null
  createdAt: string | null
}

/** 审判快照（脱敏：不含审判官 account_id / 个票 rationale）。 */
export interface AdjudicationSnapshot {
  id: string
  status: DisputeStatus
  round: number
  decision: string | null
  appealState: string | null
  finalDecision: string | null
  decidedAt: string | null
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

// ---------- finance ----------

export interface FinanceAccount {
  id: string
  organizationId: string
  balanceCents: number
  currency: string
}

/** 预留状态：reserved→released（退还）/ captured（确认扣款）→ refunded（D-06 冲正）。 */
export type ReservationStatus = 'reserved' | 'released' | 'captured' | 'refunded'

export interface FundsReservation {
  id: string
  accountId: string
  organizationId: string
  engagementRef: string
  amountCents: number
  status: ReservationStatus
  createdAt: string | null
}

// ---------- identity（组织 / 活动身份）----------

/** 商家准入 tier：draft 不可发布；basic_publish 可发布不可交易；finance_transaction 全量。 */
export type PermissionTier = 'draft' | 'basic_publish' | 'finance_transaction'

export interface Organization {
  id: string
  name: string
  ownerAccountId: string
  permissionTier: PermissionTier
  industry: string | null
  createdAt: string | null
}

/**
 * 额度策略（identity 暴露**上限**；执行在 marketplace/finance）。
 *
 * ⚠️ 这是**拍平后**的形状。后端线上格式是嵌套的 `{tier, quota:{maxActiveTasks,...}}`
 * （见 `PermissionRequestController.quota`），由 `useGrassland.getQuota` 拍平后再给 UI。
 */
export interface OrganizationQuota {
  tier: PermissionTier
  maxActiveTasks: number
  maxMonthlyTasks: number
  /** 单笔交易上限（分）；0 = 该等级不可交易。 */
  maxTxAmountCents: number
}

/**
 * 发布用量（额度的「已用」侧，来自 marketplace）。
 *
 * identity 的 `/quota` 只给上限、不给用量（策略与用量分属两个服务），
 * 前端把二者合并展示为「已用 N / 上限 M」。
 */
export interface TaskUsage {
  organizationId: string
  /** 活跃任务数（status <> closed）。 */
  activeTasks: number
  /** 本月新建任务数（按 DB 时区 date_trunc('month')，跨月自动重置）。 */
  monthlyTasks: number
}

// ---------- identity：商家权限升级审核流（D-05）----------

/** 申请状态。approved/rejected 为终态；appeal 会新建一条 pending 引用原申请。 */
export type PermissionRequestStatus = 'pending' | 'approved' | 'rejected'

/**
 * 审核时效状态（后端按 `review_deadline` 实时计算，仅展示不自动批准）。
 * completed = 已终态；overdue = 已超时；at_risk = 临近截止。
 */
export type SlaStatus = 'within' | 'at_risk' | 'overdue' | 'completed'

/** 材料类型。必填集合由 tier + 行业决定（见 `PermissionMaterialPolicy`）。 */
export type MaterialType =
  | 'business_license'
  | 'legal_representative'
  | 'financial_qualification'
  | 'industry_license'
  | 'contact_info'

/** 行业。beauty/education 为受监管行业，额外要求行业许可证。 */
export type Industry = 'catering' | 'retail' | 'beauty' | 'education' | 'e_commerce' | 'other'

/** 商家权限升级申请。materials 为 {材料类型: 文本}。 */
export interface PermissionRequest {
  id: string
  organizationId: string
  requesterAccountId: string
  requestedTier: PermissionTier
  status: PermissionRequestStatus
  /** 提交时的行业快照（驱动材料要求）。 */
  industry: Industry | null
  /**
   * ⚠️ **响应里是 JSON 字符串，不是对象**——后端按 `materials::text` 取出原始 jsonb
   * （见 `MerchantPermissionRequestRepository` 的 SELECT_COLS）。
   *
   * 请求侧却收**对象**（`CreatePermissionRequest.materials` 是 `Map<String,String>`）——
   * 又一处请求/响应不对称，与 P0-1 的 `type`/`identityType` 同类。
   * 用 `parsePermissionMaterials()` 解析，别直接 `Object.entries`
   * （对字符串会逐字符展开，浏览器实测踩到过）。
   */
  materials: string | null
  reviewDeadline: string | null
  slaStatus: SlaStatus
  reviewerAccountId: string | null
  reviewNote: string | null
  /** 申诉件指向的原申请 id；null = 首次申请。 */
  originalRequestId: string | null
  appealNote: string | null
  createdAt: string | null
}

/** 提交升级申请。⚠️ 字段名是 `requestedTier`/`materials`/`industry`（后端 `CreatePermissionRequest`）。 */
export interface CreatePermissionRequestInput {
  requestedTier: PermissionTier
  materials: Record<string, string>
  industry?: string
}

/** 审核决定。仅 approve/reject 两值（后端 compact constructor 校验，其它值 400）。 */
export type ReviewDecision = 'approve' | 'reject'

/**
 * 解析 {@link PermissionRequest.materials}（JSON 字符串 → 材料表）。
 *
 * 坏 JSON / null 一律返回空对象——审核界面不该因为一条脏数据整块炸掉。
 */
export function parsePermissionMaterials(raw: string | null): Record<string, string> {
  if (!raw) return {}
  try {
    const parsed = JSON.parse(raw) as unknown
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {}
    return parsed as Record<string, string>
  } catch {
    return {}
  }
}

/** 活动身份：merchant/recommender；null = 消费者。按 session 隔离（多设备互不影响）。 */
export type IdentityType = 'merchant' | 'recommender'

// ---------- trust：审判官池 + 投票 ----------

/** 审判官入池记录。active=false 为已退池（软删，保留历史面板/投票完整性）。 */
export interface Judge {
  id: string
  accountId: string
  /** 归属组织；null = 平台级审判官。抽签时排除与争议同组织者。 */
  organizationId: string | null
  /** 资格等级；声誉模块未建，现固定 1（配置阈值占位）。 */
  eligibilityTier: number
  active: boolean
  createdAt: string | null
}

/** 投票选择。abstain 不计入任一方多数。 */
export type VoteChoice = 'for_merchant' | 'for_recommender' | 'abstain'

export interface JudgeVote {
  disputeId: string
  round: number
  vote: VoteChoice
  rationale: string | null
  votedAt: string | null
  tallies: AdjudicationSnapshot['tallies']
}
