// ---------- 运营处置台（GL-P1-OPS-001）----------

/** 处置单来源。`dlt_message` 的 sourceRef 是 Kafka 位点 `topic:partition:offset`。 */
export type OpsCaseSourceKind = 'settlement_blocked' | 'settlement_held' | 'dlt_message' | 'merchant_rejection'

/** 状态机：open→in_review→approved|rejected→resolved。rejected 是终态（不处置）。 */
export type OpsCaseStatus = 'open' | 'in_review' | 'approved' | 'rejected' | 'resolved'

/**
 * 运营处置单。`version` 是乐观锁，所有流转请求都必须回传当前值 —— 不符 409。
 *
 * 双人审批：`approvedBy` 不能等于 `submittedBy`（后端仓储 WHERE 排除 + DB CHECK 双保险）。
 */
export interface OpsCase {
  id: string
  sourceKind: OpsCaseSourceKind
  sourceRef: string
  organizationId: string | null
  applicationId: string | null
  reason: string
  severity: 'high' | 'normal'
  status: OpsCaseStatus
  version: number
  submittedBy: string | null
  submittedAt: string | null
  submitNote: string | null
  approvedBy: string | null
  approvedAt: string | null
  approveNote: string | null
  resolvedAt: string | null
  resolution: string | null
  createdAt: string | null
  updatedAt: string | null
}

/** 不可变审计流水（后端只 append + list，无 update/delete）。 */
export interface OpsCaseAudit {
  id: string
  action: string
  /** 系统登记（`registered`）时为 null，actorRole 为 `system`。 */
  actorAccountId: string | null
  actorRole: string
  fromStatus: string | null
  toStatus: string
  note: string | null
  createdAt: string | null
}

/** 处置动作类型。资金侧只复用 finance 既有原语，刻意无 capture。 */
export type OpsActionKind = 'retry_reconciliation' | 'release_funds' | 'dlt_replay' | 'dlt_discard'

/**
 * 处置动作台账。`operationId` 是调用方生成的幂等键 —— 同一 key 重放不会重复打下游。
 *
 * `status='failed'` 时 HTTP 仍是 200：动作确实执行过且失败了，`error` 是原因。
 */
export interface OpsCaseAction {
  id: string
  caseId: string
  operationId: string
  action: OpsActionKind
  status: 'pending' | 'succeeded' | 'failed'
  requestedBy: string
  outcome: string | null
  error: string | null
  createdAt: string | null
  completedAt: string | null
}

export interface OpsCaseDetail {
  case: OpsCase
  audits: OpsCaseAudit[]
  actions: OpsCaseAction[]
}

/** 死信消息。弃置只改 status，payload 与行都保留（死信是审计对象）。 */
export interface OpsDltMessage {
  id: string
  topic: string
  partition: number
  offset: number
  originalTopic: string
  messageKey: string | null
  payload: string
  errorSummary: string | null
  status: 'pending' | 'replayed' | 'discarded'
  replayedAt: string | null
  discardedAt: string | null
  createdAt: string | null
}

export interface RecommenderVerificationRequest {
  id: string
  accountId: string
  status: 'pending' | 'approved' | 'rejected'
  /** 提交材料 JSON 字符串（社交账号/作品链接等）。 */
  materials?: string
  reviewerAccountId?: string
  reviewNote?: string
  reviewDeadline?: string | null
  createdAt?: string | null
}

/**
 * 待判定核验（GL-P1-OPS-001 + GL-P2-ADMIN-004）。
 * 自动核验 inconclusive 且尚未人工改判；人工结论写 verification_override。
 */
export interface OpsPendingVerification {
  verificationId: string
  submissionId: string
  applicationId: string
  taskId: string
  taskTitle: string
  organizationId: string
  recommenderAccountId: string
  contentUrl: string
  /** 各项 check 明细的 JSON 字符串（同 PermissionRequest.materials 的坑：需先 parse）。 */
  checks: string
  lastCheckedAt: string | null
  submittedAt: string | null
}

/**
 * 履约自由文本人工复核队列（缺口清偿之九遗留清偿 + 履约硬门槛）：词库 advisory（low/medium）命中的
 * 评论（comment）/备注（note）。运营复核 confirmed=无问题 / violation=违规（经交付物列表 commentFlagged
 * 透出给商家）。
 */
export interface OpsCommentReview {
  submissionId: string
  /** 命中字段：comment=评论文本 / note=提交备注。 */
  field: 'comment' | 'note'
  commentText: string
  /** 词库 advisory 明细快照。 */
  findings: Array<{ category: string; severity: string; advice?: string }>
  status: 'open' | 'confirmed' | 'violation'
  taskId: string
  taskTitle: string
  platform: string | null
  recommenderAccountId: string
  submissionStatus: string
  submittedAt: string | null
  createdAt: string | null
  reviewerAccountId?: string | null
  reviewNote?: string | null
  reviewedAt?: string | null
}

/** 核验明细单项（`OpsPendingVerification.checks` parse 后的元素）。 */
/** 字段名对齐 marketplace `ApplicationController.checksToMaps`（camelCase，非 snake_case）。 */
export interface OpsVerificationCheck {
  type: string
  status: string
  detail?: string | null
  checkedAt?: string | null
}

/** 安全 parse：坏 JSON 不炸 UI，返回空数组。 */
export function parseVerificationChecks(raw: string | null | undefined): OpsVerificationCheck[] {
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw) as unknown
    return Array.isArray(parsed) ? parsed as OpsVerificationCheck[] : []
  } catch {
    return []
  }
}
