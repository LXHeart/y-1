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

/** 任务状态（GL-P1-TASK-001）：draft=草稿；published=大厅可见；closed=已关闭报名；cancelled=已取消。 */
export type TaskStatus = 'draft' | 'published' | 'closed' | 'cancelled'

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
}

export interface CreateTaskInput {
  organizationId: string
  title: string
  description?: string
  contentForm?: string
  platform?: string
  maxSlots?: number
  bountyCents?: number
  /** 报名截止时间（ISO）；可空 = 无时间截止。 */
  applicationDeadline?: string
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
}

/** 任务大厅 feed 查询（GL-P1-TASK-001 Stage 2）。 */
export interface TaskFeedQuery {
  platform?: string
  contentForm?: string
  minBountyCents?: number
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

// ---------- 推荐官画像（identity）+ 声誉（marketplace）----------

/**
 * 自报的社交账号（PRD 六「社交平台」）。
 *
 * ⚠️ `followers` 是**推荐官自己填的**，平台没有核验过——UI 必须标明「自报」，
 * 否则商家会当成平台数据来决策。真核验属 PRD 九自动核实引擎，未做。
 */
export interface SocialAccount {
  platform: string
  handle: string | null
  followers: number | null
}

/**
 * 推荐官画像（identity 域）。没填过资料时后端返回**空画像而非 404**——
 * 「这人没填」本身就是商家要的事实。
 */
export interface RecommenderProfile {
  accountId: string
  displayName: string | null
  bio: string | null
  contentTags: string[]
  domainTags: string[]
  socialAccounts: SocialAccount[]
  updatedAt: string | null
}

/** PUT 整份覆盖：数组给空数组即清空；标签与社交账号收的是**数组**而非逗号串。 */
export interface UpdateRecommenderProfileInput {
  displayName?: string
  bio?: string
  contentTags: string[]
  domainTags: string[]
  socialAccounts: SocialAccount[]
}

/** 等级（PRD 五）。Lv5 是邀请制，后端策略永不自动授予。 */
export type RecommenderLevel = 'Lv1' | 'Lv2' | 'Lv3' | 'Lv4' | 'Lv5'

/**
 * 声誉指标（PRD 六「数据面板」，marketplace 从撮合事实实时派生）。
 *
 * ⚠️ `averageScore` / `averageResponseSeconds` **可能为 null**——分别表示「还没人评过」
 * 与「还没有接单→提交的样本」。不能显示成 0，那会被读成「评分极低 / 秒回」。
 * PRD 六的「平均曝光数据」后端明确不做（需平台数据采集）。
 */
export interface RecommenderReputation {
  accountId: string
  level: RecommenderLevel
  levelTitle: string
  acceptedCount: number
  completedCount: number
  /** 0–1 的小数（完成/已接单）；无接单时为 0。 */
  completionRate: number
  ratingCount: number
  averageScore: number | null
  averageResponseSeconds: number | null
}

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

// ---------- trust（争议 / 审判）----------

/** 争议状态机（5 态）。非 final 均阻塞结算。 */
export type DisputeStatus = 'open' | 'voting' | 'decided' | 'appealed' | 'final'
export type DisputeKind = 'standard' | 'merchant_rejection'

export interface DisputeCase {
  id: string
  engagementRef: string
  organizationId: string
  openedByAccountId: string
  openedByRole: string
  status: DisputeStatus
  kind: DisputeKind
  reason: string | null
  decision: string | null
  decidedAt: string | null
  round: number
  version: number
  appealState: string | null
  finalDecision: string | null
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

/** 已开通的身份档案；GET /api/me/identities 的响应项（请求字段仍是 {@link IdentityType} 的 `type`）。 */
export interface IdentityProfile {
  id: string
  identityType: IdentityType
  organizationId: string | null
  status: string
}

// ---------- identity：组织成员 / 门店 / 门店成员（Slice 2F/2G/2J）----------

/**
 * 组织成员角色。owner 只能在建组织时产生——
 * `POST /memberships` 显式拒绝授予 owner（「cannot grant owner role via this endpoint」）。
 */
export type MembershipRole = 'owner' | 'admin' | 'member'

/** 门店成员角色。org 的 OWNER/ADMIN 隐式视为门店 MANAGER（超管）。 */
export type StoreRole = 'manager' | 'staff'

export interface Membership {
  id: string
  organizationId: string
  accountId: string
  role: MembershipRole
  createdAt: string | null
}

export interface Store {
  id: string
  organizationId: string
  name: string
  status: string
  createdAt: string | null
}

// ---------- marketplace：履约交付物 ----------

/** 交付物状态。submitted=待商家核验；accepted=商家确认履约时置；rejected=被退回，可修改重交。 */
export type SubmissionStatus = 'submitted' | 'accepted' | 'rejected'

/**
 * 履约交付物（推荐官提交的凭证）。
 *
 * ⚠️ 列表端点返回的是 `{ submissions: [...] }` 而**不是**裸数组——与其它列表端点不同，别直接当数组用。
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
  lastCheckedAt: string | null
}

// ---------- intelligence：media 直传（三步上传）----------

/** 附件用途。履约附件是唯一允许 marketplace 跨账号读的 purpose（服务间断点的放行条件）。 */
export type MediaPurpose = 'engagement_attachment' | 'article_image' | 'avatar'

/**
 * 上传凭据（第一步 `POST /api/media/upload-tickets` 的响应）。
 *
 * ⚠️ `uploadUrl` 指向 **MinIO/S3 而非本站**（默认 `http://localhost:9002` 的 nginx CORS 反代，
 * 见 Slice 11 Stage 3）。往它 PUT 时**绝不能带 cookie**——`credentials: 'include'` 会让浏览器
 * 要求 `Access-Control-Allow-Credentials`，且多余 header 会破坏 SigV4 签名 → 403。
 *
 * `headers` 是签名时锁定的必须请求头（通常仅 `Content-Type`）；照原样回放，别增删。
 */
export interface MediaUploadTicket {
  /** media_reference id；第三步 confirm 与提交交付物时都用它。 */
  id: string
  /** 临时对象 key（诊断用；最终 key 由服务端 confirm 时写入，从不暴露 PUT 权限）。 */
  objectKey: string
  uploadUrl: string
  /** 恒为 `PUT`；照后端返回值用，不要写死。 */
  method: string
  headers: Record<string, string>
  /** presigned URL 过期时间（默认 15 分钟）。 */
  expiresAt: string | null
}

/** 申请上传凭据的入参。`sizeBytes` 必填且必须等于真实字节数——confirm 时按 HEAD 逐字节校验，不符即失败。 */
export interface CreateMediaUploadTicketInput {
  contentType: string
  purpose: MediaPurpose
  sizeBytes: number
  domainType?: string
  domainId?: string
  /** 资产 TTL（秒）；省略则按后端默认（履约附件不设过期）。 */
  ttlSeconds?: number
}

/**
 * confirm（第三步）的响应 = media 完整元数据。
 *
 * `status` 走到 `active` 才算正式资产；此前是 `pending`（临时对象，会被清理任务回收）。
 */
export interface MediaMetadata {
  id: string
  ownerAccountId: string
  organizationId: string | null
  purpose: string
  domainType: string | null
  domainId: string | null
  mimeType: string | null
  sizeBytes: number
  checksum: string | null
  source: string
  status: 'pending' | 'finalizing' | 'active' | 'deleting' | 'deleted'
  createdAt: string | null
  expiresAt: string | null
  deletedAt: string | null
}

/** 附件下载 URL（marketplace 经服务断言中转 intelligence 签发）。⚠️ `expiresAt` 是**资产 TTL 而非 URL 过期时间**。 */
export interface AttachmentDownload {
  downloadUrl: string
  expiresAt: string | null
}

// ---------- finance：推荐官钱包 ----------

/** 钱包流水类型。金额符号由类型决定：入账为正，提现/冲正为负。 */
export type WalletEntryType = 'task_payout' | 'withdrawal' | 'clawback'

/**
 * 钱包流水行。
 *
 * ⚠️ `amountCents` **带符号**（提现/冲正是负数），展示时不要再自己加负号。
 * `feeCents` 是该笔入账被平台抽走的部分，毛额 = amountCents + feeCents。
 */
export interface WalletEntry {
  id: string
  entryType: WalletEntryType
  amountCents: number
  feeCents: number
  engagementRef: string | null
  memo: string | null
  createdAt: string | null
}

/** 推荐官钱包（账号级）。从未入过账时后端返回余额 0 而非 404。 */
export interface Wallet {
  accountId: string
  balanceCents: number
  updatedAt: string | null
  entries: WalletEntry[]
}

// ---------- identity：多设备登录会话（Slice 2I / HLD D-08）----------

/**
 * 一台已登录设备（`GET /api/me/sessions`）。
 *
 * 来源是**登录会话**表（登录即有行）左连 `identity_session`，所以没切换过身份的设备也在列表里，
 * 只是 `activeIdentityType` / `deviceId` / `ipAddress` 等为 null（右表无行）。
 */
export interface LoginSession {
  /** 该设备的 session id；撤销时作为路径参数。 */
  sessionToken: string
  /** 该设备当前的活动身份；null = 消费者。活动身份按设备隔离。 */
  activeIdentityType: IdentityType | null
  /** sha256(User-Agent) 前 16 位，用于区分设备。 */
  deviceId: string | null
  /** 客户端自报的 `X-Device-Label`，通常为 null。 */
  deviceLabel: string | null
  ipAddress: string | null
  lastSeenAt: string | null
  /** 是否就是当前这台设备——撤销它等于把自己登出。 */
  current: boolean
}

// ---------- identity：按邮箱邀请成员 ----------

/** 邀请状态。pending 是唯一非终态；过期不是状态（由 `expired` 字段按 expiresAt 算出）。 */
export type InvitationStatus = 'pending' | 'accepted' | 'revoked' | 'declined'

/**
 * 组织侧看到的邀请（`GET/POST /api/organizations/{orgId}/invitations`）。
 *
 * ⚠️ 与被邀请人侧的 {@link MyInvitation} **字段不对称**：这边有 email/status，
 * 那边有 organizationName 而没有 email/status。别把两个类型混用。
 */
export interface OrgInvitation {
  id: string
  organizationId: string
  email: string
  role: Exclude<MembershipRole, 'owner'>
  status: InvitationStatus
  expiresAt: string | null
  createdAt: string | null
  /** 后端按 expiresAt 现算：pending 且已过期才为 true。 */
  expired: boolean
  /** **仅创建响应带**——列表读不出「当时是否发出了邮件」，故列表项无此字段。 */
  emailSent?: boolean
}

/** 被邀请人侧看到的待接受邀请（`GET /api/me/invitations`）。只列未过期的 pending。 */
export interface MyInvitation {
  id: string
  organizationId: string
  organizationName: string
  role: Exclude<MembershipRole, 'owner'>
  expiresAt: string | null
  createdAt: string | null
}

/** 接受邀请的结果。alreadyMember=true 表示本就是成员（幂等成功，非报错）。 */
export interface InvitationAcceptResult {
  organizationId: string
  role: Exclude<MembershipRole, 'owner'>
  alreadyMember: boolean
}

/** 门店成员。注意归属字段是 `storeId`（不是 organizationId）。 */
export interface StoreMembership {
  id: string
  storeId: string
  accountId: string
  role: StoreRole
  createdAt: string | null
}

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

// ---------- 运营处置台（GL-P1-OPS-001）----------

/** 处置单来源。`dlt_message` 的 sourceRef 是 Kafka 位点 `topic:partition:offset`。 */
export type OpsCaseSourceKind = 'settlement_blocked' | 'settlement_held' | 'dlt_message'

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

/**
 * 「待判定」核验：自动核验 inconclusive 且交付物仍 submitted。
 *
 * **不是处置单** —— inconclusive 永不阻断结算，运营台只提供可见性，决策权仍在商家的 confirm/reject。
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

// ---------- GL-P3-MERCHANT-001：商家 KYB 资料 ----------

/** 商家资料状态。 */
export type MerchantProfileStatus = 'draft' | 'pending' | 'under_review' | 'approved' | 'rejected'

/** 商家主体详细资料。 */
export interface MerchantProfile {
  organizationId: string
  legalName: string | null
  unifiedSocialCreditCode: string | null
  businessType: string | null
  legalPersonName: string | null
  legalPersonIdNumber: string | null
  registeredCapitalCents: number | null
  establishmentDate: string | null // YYYY-MM-DD
  businessAddress: string | null // JSON: {province,city,district,address,longitude,latitude}
  contactPhone: string | null
  contactEmail: string | null
  status: MerchantProfileStatus
  submittedAt: string | null
  reviewedAt: string | null
  reviewerAccountId: string | null
  reviewNote: string | null
  createdAt: string | null
}

/** 创建/更新商家资料请求。 */
export interface CreateMerchantProfileInput {
  legalName?: string
  unifiedSocialCreditCode?: string
  businessType?: string
  legalPersonName?: string
  legalPersonIdNumber?: string
  registeredCapitalCents?: number
  establishmentDate?: string // YYYY-MM-DD
  businessAddress?: string // JSON string
  contactPhone?: string
  contactEmail?: string
}

/** 商家附件类型。 */
export type MerchantAttachmentType =
  | 'business_license'
  | 'legal_person_id_front'
  | 'legal_person_id_back'
  | 'store_photo'
  | 'other'

/** 商家附件。 */
export interface MerchantAttachment {
  id: string
  organizationId: string
  attachmentType: MerchantAttachmentType
  mediaReferenceId: string
  mimeType: string | null
  sizeBytes: number | null
  uploadedAt: string | null
}

/** 创建附件请求。 */
export interface CreateMerchantAttachmentInput {
  attachmentType: MerchantAttachmentType
  mediaReferenceId: string
  mimeType: string
  sizeBytes: number
}

/** 收款账户类型。 */
export type WithdrawalAccountType = 'bank_card' | 'alipay' | 'wechat'

/** 收款账户状态。 */
export type WithdrawalAccountStatus = 'pending' | 'under_review' | 'approved' | 'rejected'

/** 收款账户。 */
export interface WithdrawalAccount {
  id: string
  organizationId: string
  accountType: WithdrawalAccountType
  accountName: string
  accountNumberEncrypted: string // 加密显示，如 ****1234
  bankName: string | null
  branchName: string | null
  isDefault: boolean
  status: WithdrawalAccountStatus
  submittedAt: string | null
  reviewedAt: string | null
  reviewerAccountId: string | null
  reviewNote: string | null
  createdAt: string | null
}

/** 创建收款账户请求。 */
export interface CreateWithdrawalAccountInput {
  accountType: WithdrawalAccountType
  accountName: string
  accountNumberEncrypted: string
  bankName?: string
  branchName?: string
}

/** KYB 审核类型。 */
export type KybVerificationType = 'merchant_profile' | 'store_profile' | 'withdrawal_account'

/** KYB 审核状态。 */
export type KybVerificationStatus = 'pending' | 'under_review' | 'approved' | 'rejected'

/** KYB 审核申请。 */
export interface KybVerificationRequest {
  id: string
  organizationId: string
  requesterAccountId: string
  verificationType: KybVerificationType
  targetId: string | null
  materials: string | null // JSON: 附件 ID 列表
  status: KybVerificationStatus
  reviewerAccountId: string | null
  reviewNote: string | null
  reviewDeadline: string | null
  createdAt: string | null
}

/** 门店详细资料。 */
export interface StoreProfile {
  storeId: string
  address: string | null // JSON: {province,city,district,address,longitude,latitude}
  phone: string | null
  businessHours: string | null // JSON: [{dayOfWeek,openTime,closeTime}]
  description: string | null
  status: string
  createdAt: string | null
}

/** 创建/更新门店资料请求。 */
export interface CreateStoreProfileInput {
  address?: string // JSON string
  phone?: string
  businessHours?: string // JSON string
  description?: string
}

