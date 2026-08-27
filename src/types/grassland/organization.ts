// ---------- finance + identity（组织 / 权限 / 门店 / 成员）----------

import type { IdentityType } from './common'

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

// ---------- intelligence：组织 AI 预算（任务书 #37）----------

export interface AiOrgBudgetUsage {
  measured: boolean
  dailyTokens: number | null
  dailyCents: number | null
  monthlyTokens: number | null
  monthlyCents: number | null
}

export interface AiOrgBudgetLimits {
  maxTokensPerRun: number | null
  maxTokensDaily: number | null
  maxTokensMonthly: number | null
  maxCentsPerRun: number | null
  maxCentsDaily: number | null
  maxCentsMonthly: number | null
}

export interface AiOrgBudget extends AiOrgBudgetLimits {
  configured: boolean
  version: number
  usage: AiOrgBudgetUsage
  overCurrentUsage: boolean
  updatedAt: string | null
}

export interface UpdateAiOrgBudgetInput extends AiOrgBudgetLimits {
  expectedVersion: number
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
 * identity 的 `/quota` 给策略上限，marketplace `/tasks/usage` 给实时用量，
 * 前端把二者合并展示为「已用 N / 上限 M」。
 */
export interface TaskUsage {
  organizationId: string
  /** 活跃任务数（当前按 published 计入发布限额）。 */
  activeTasks: number
  /** 本月新建任务数（按 DB 时区 date_trunc('month')，跨月自动重置）。 */
  monthlyTasks: number
  maxActiveTasks: number
  remainingActiveTasks: number
  maxMonthlyTasks: number
  remainingMonthlyTasks: number
  maxTxAmountCents: number
}

// ---------- identity：商家权限升级审核流（D-05）----------

/** 申请状态。approved/rejected 为终态；appeal 会新建一条 pending 引用原申请。 */
export type PermissionRequestStatus = 'pending' | 'under_review' | 'approved' | 'rejected'

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
export type Industry =
  | 'catering' | 'retail' | 'beauty' | 'education' | 'e_commerce'
  | 'healthcare' | 'finance' | 'real_estate' | 'travel' | 'children'
  | 'gambling' | 'adult' | 'other'

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
  version: number
  reviewStartedAt: string | null
  slaBreachedAt: string | null
  autoReviewStatus: 'not_run' | 'pending' | 'passed' | 'failed' | 'needs_review'
  autoReviewResult: string | null
  reviewMode: 'manual' | 'auto_recommendation'
  riskLevel: 'standard' | 'elevated' | 'high'
  attachmentIds: string | null
  decisionAt: string | null
  appealCount: number
}

/** 提交升级申请。⚠️ 字段名是 `requestedTier`/`materials`/`industry`（后端 `CreatePermissionRequest`）。 */
export interface CreatePermissionRequestInput {
  requestedTier: PermissionTier
  materials: Record<string, string>
  industry?: string
  attachmentIds?: string[]
}

export interface PermissionRequestAudit {
  id: string
  actorAccountId: string | null
  actorKind: 'merchant' | 'admin' | 'system'
  action: string
  fromStatus: PermissionRequestStatus | null
  toStatus: PermissionRequestStatus | null
  details: string | null
  createdAt: string | null
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

/** 已开通的身份档案；GET /api/me/identities 的响应项（请求字段仍是 {@link IdentityType} 的 `type`）。 */
export interface IdentityProfile {
  id: string
  identityType: IdentityType
  organizationId: string | null
  status: string
}

/** 商家主体更名申请（V40：审核生效 + 30 天冷却）。 */
export interface OrganizationRenameRequest {
  id: string
  organizationId: string
  currentName: string
  requestedName: string
  status: 'pending' | 'approved' | 'rejected'
  requestedAt: string | null
  reviewedAt: string | null
  reviewNote: string | null
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

/** 任务书 #48：主体直建子账号的变更结果；initialPassword 是一次性明文，仅此一次。 */
export interface SubAccountMutationResult {
  account: {
    id: string
    email: string
    displayName?: string | null
    role?: string | null
    status: string
  }
  initialPassword?: string
  mustChangePassword?: boolean
}

/** 子账号在 app_users.status 的生命周期（任务书 #48 D4）。 */
export type SubAccountStatus = 'active' | 'suspended' | 'pending_review' | 'rejected'

export interface Store {
  id: string
  organizationId: string
  name: string
  status: string
  createdAt: string | null
}

/** 当前账号被显式授予的门店范围；不代表其拥有组织成员身份。 */
export interface StoreAccessScope {
  storeId: string
  storeName: string
  storeStatus: string
  organizationId: string
  organizationName: string
  organizationStatus: string
  permissionTier: PermissionTier
  role: StoreRole
}

/** 当前账号的组织范围与角色（/api/me/organization-scopes）；角色权威口径同 identity OrgAuthorization。 */
export interface OrganizationAccessScope {
  organizationId: string
  organizationName: string
  organizationStatus: string
  permissionTier: PermissionTier
  role: MembershipRole
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
  /** 登录会话过期时刻（DB 时区换算后的绝对时间）；legacy expire 列为无时区 timestamp，写读同 DB 时区。 */
  expiresAt: string | null
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
  /** 门店级邀请的目标门店；组织级邀请（缺省）为空。 */
  storeId?: string
  role: Exclude<MembershipRole, 'owner'> | StoreRole
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
  /** 门店级邀请的目标门店（含名称）；组织级邀请两个字段都缺省。 */
  storeId?: string
  storeName?: string
  role: Exclude<MembershipRole, 'owner'> | StoreRole
  expiresAt: string | null
  createdAt: string | null
}

/** 接受邀请的结果。alreadyMember=true 表示本就是成员（幂等成功，非报错）。 */
export interface InvitationAcceptResult {
  organizationId: string
  /** 门店级邀请的接受结果带目标门店。 */
  storeId?: string
  role: Exclude<MembershipRole, 'owner'> | StoreRole
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

// ---------- identity：组织品牌资料（#32）----------

/**
 * 组织品牌资料（`GET/PUT /api/organizations/{orgId}/brand-profile`）。
 *
 * 字段全可空——资料未填也是合法状态；**无行时后端回 version=0 的全空资料（不是 404）**，
 * 可直接绑到表单。与 merchant_profile 是两回事：品牌资料不受 KYB 审核门、可随时编辑（D1）。
 *
 * ⚠️ `logoUrl` 是 GET 时后端 fail-soft 解析出的**短时效** presigned 展示 URL（解析失败/无
 * Logo 置 null，资料本体仍可读，D7）——只用于即时预览，别持久化；权威引用是
 * `brandLogoMediaReferenceId`（跨服务逻辑引用，无 FK），保存时原样回传。
 * 响应里另有 `organizationId`，调用方本来就有，这里不重复建模。
 */
/**
 * 组织品牌资料公开消费视图（缺口清偿之六）：`GET /api/organizations/{orgId}/public-brand-profile`。
 * 大厅/任务详情公开品牌信息；白名单字段——无内部媒体 id 与乐观锁版本，全字段可空（未填资料）。
 */
export interface PublicBrandProfile {
  organizationId: string
  brandName: string | null
  description: string | null
  industry: Industry | null
  /** 短时 presigned 展示 URL；未配置 Logo 或解析失败为 null（fail-soft），仅供即时渲染不持久化。 */
  logoUrl: string | null
}

export interface BrandProfile {
  brandName: string | null
  brandLogoMediaReferenceId: string | null
  logoUrl: string | null
  description: string | null
  /** 经营分类，13 值 {@link Industry} 枚举之一；与 organization.industry（权限准入用）互不影响（D10）。 */
  industry: Industry | null
  /** 乐观锁版本；保存时原样回传 `expectedVersion`，无行首建须传 0。 */
  version: number
}

/**
 * 保存品牌资料（PUT **整份覆盖**：没带的字段等于清空，不是不改）。
 *
 * ⚠️ `expectedVersion` 必填且不可省——与当前版本不符（含无行时不为 0）后端 409
 * 「品牌资料已变更，请刷新后重试」，前端以 `GrasslandHttpError.status===409` 分支后重拉（D3）。
 * `industry` 只收 {@link Industry} 枚举值，自由文本会被后端 400 拒绝。
 */
export interface SaveBrandProfileInput {
  brandName?: string | null
  brandLogoMediaReferenceId?: string | null
  description?: string | null
  industry?: Industry | null
  expectedVersion: number
}

// ---------- finance：推荐官钱包 ----------

/** 钱包流水类型。金额符号由类型决定：入账为正，提现/冲正/押金预付为负。 */
export type WalletEntryType = 'task_payout' | 'commerce_commission' | 'withdrawal' | 'clawback'
  | 'freebie_reserve' | 'freebie_refund' | 'judge_commission'

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

// ---------- finance：收入统计 + 月度账单（任务书 #29+#30）----------

/**
 * 单月收入聚合。金额单位分；`netCents` = SUM(amount_cents) 带符号。
 * `grossCents` = 入账类的 amount+fee（毛额），`feeCents` 为平台抽成。
 */
export interface WalletMonthlyIncome {
  month: string
  taskPayoutCents: number
  commerceCommissionCents: number
  withdrawalCents: number
  clawbackCents: number
  judgeCommissionCents: number
  grossCents: number
  feeCents: number
  netCents: number
}

/** 按任务（engagement）聚合的收入明细，供前端 join my-applications 出任务标题。 */
export interface WalletEngagementIncome {
  engagementRef: string
  payoutCents: number
  feeCents: number
  count: number
  lastAt: string | null
}

/** 推荐官收入统计响应（from/to 为含端月份 YYYY-MM）。 */
export interface WalletStatistics {
  from: string
  to: string
  months: WalletMonthlyIncome[]
  byEngagement: WalletEngagementIncome[]
}

/** 商家月度账单的单类资金流水。`label` 为中文科目名（后端给出，前端直接渲染）。 */
export interface MonthlyBillFlow {
  type: string
  label: string
  amountCents: number
}

/** 商家月度账单响应。`netEscrowDeltaCents` = Σ flows（托管余额净变动）。 */
export interface MerchantMonthlyBill {
  month: string
  flows: MonthlyBillFlow[]
  platformFeeCents: number
  netEscrowDeltaCents: number
}
