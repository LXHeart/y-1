// ---------- marketplace：履约交付物 + 核验 + 评分 ----------

// ---------- marketplace：履约交付物 ----------

/** 交付物状态。submitted=待商家核验；accepted=商家确认履约时置；rejected=被退回，可修改重交。 */
export type SubmissionStatus = 'submitted' | 'accepted' | 'rejected'

/**
 * 履约交付物（推荐官提交的凭证）。
 *
 * ⚠️ 列表端点返回的是 `{ submissions: [...] }` 而**不是**裸数组——与其它列表端点不同，别直接当数组用.
 */
export interface EngagementSubmission {
  id: string
  applicationId: string
  recommenderAccountId: string
  /** 发布链接，核实的主证据。后端要求 http(s)，非链接会 400。 */
  contentUrl: string
  note: string | null
  status: SubmissionStatus
  /** 商家退回原因。 */
  reviewNote: string | null
  reviewedAt: string | null
  createdAt: string | null
  /**
   * 附件（Slice 11 履约附件）。
   *
   * ⚠️ 后端**总是**带这个字段（创建响应取挂接时的入参快照、列表响应取 DB 行快照），
   * 但两条路径都可能是空数组；旧数据（Slice 11 之前的交付物）也走同一序列化，故为空数组而非缺字段。
   * 标可选是为了让 legacy 测试夹具不必逐个补 `attachments: []`。
   */
  attachments?: EngagementSubmissionAttachment[]
  /**
   * 履约核验记录（Slice 11 Verification v1）。商家触发核验后由后端在 listSubmissions 内联带出；
   * 未核验时缺省。tri-state：passed=核验通过 / failed=核验未过 / inconclusive=核验存疑。
   */
  verification?: EngagementVerification | null
}

/**
 * 交付物附件。`mimeType`/`sizeBytes` 是**挂接那一刻的快照**（存在 marketplace 侧），
 * 不会随 intelligence 侧 media_reference 变化——附件被删后这里仍有行，下载才 404。
 *
 * ⚠️ 字段名是 `mediaId` 而非 `id`：它是 intelligence 域的 media_reference id，
 * marketplace 侧无 FK（镜像 V5 recommender_account_id 的跨服务弱引用）。
 */
export interface EngagementSubmissionAttachment {
  mediaId: string
  mimeType: string | null
  sizeBytes: number | null
}

// ---------- 履约核验（Slice 11 Verification v1）----------

/** 履约核验聚合态（failed > inconclusive > passed；无 check → inconclusive）。 */
export type VerificationStatus = 'passed' | 'failed' | 'inconclusive'

/** 单项核验明细。 */
export interface VerificationCheck {
  /** link_reachability=链接可达；ai_visual=AI 视觉核验附件截图。 */
  type: string
  status: VerificationStatus
  detail: string | null
  checkedAt: string | null
}

/**
 * 履约核验记录。商家触发核验（链接可达性 + AI 视觉）后落库，并内联回 listSubmissions。
 * confirm 闸门仅阻断 failed；absent/passed/inconclusive 照常确认。
 */
export interface EngagementVerification {
  submissionId: string
  status: VerificationStatus
  checks: VerificationCheck[]
  /** Verification v2 immutable decision run and frozen business context. */
  runId?: string | null
  engineVersion?: string
  taskContext?: Record<string, unknown> | null
  evidenceSnapshot?: { mediaIds?: string[] } | null
  lastCheckedAt: string | null
}

/** Frozen at application acceptance; safe to pass into task-mode AI creation. */
export interface TaskContextSnapshot {
  taskId: string
  taskVersion: number
  title: string
  description: string | null
  contentForm: string | null
  platform: string | null
  storeId: string | null
  applicationId: string
  recommenderAccountId: string
  bountyCents: number
  acceptedAt: string | null
  requirements: Record<string, unknown>
  backfilled?: boolean
}

export interface EngagementVerificationRun {
  id: string
  runNumber: number
  engineVersion: string
  status: VerificationStatus
  taskContext: Record<string, unknown>
  evidenceSnapshot: { mediaIds?: string[] }
  checks: VerificationCheck[]
  triggeredBy: string | null
  createdAt: string | null
}

// ---------- 商家对一次履约的评分 ----------

/** 商家对一次履约的评分（1-5 星）。一次履约只能评一次，重复评价后端 409。 */
export interface EngagementRating {
  id: string
  applicationId: string
  taskId: string
  recommenderAccountId: string
  ratedByAccountId: string
  score: number
  comment: string | null
  createdAt: string | null
}
