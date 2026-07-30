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
