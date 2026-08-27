/**
 * 商家主体屏的**摘要**契约（纯 UI 聚合，无后端端点）。
 *
 * 「商家主体与门店」原先是 5 张全宽卡竖着堆，认证状态、额度余量这些最该一眼看到的
 * 信息埋在第 2、第 5 张卡内部。现在改为「身份条 + 概览 + 分节」，概览需要各子卡
 * 已经拉到手的数据——于是每张子卡在自己的加载完成点 emit 一份摘要向上冒泡，
 * 父组件只做聚合与呈现。
 *
 * 刻意**不新增请求**：这里的每个字段都来自子卡本来就要发的那几个请求，
 * 父组件不再重复拉一遍（避免同一份数据两个真相源）。
 */
import type { MerchantProfileStatus } from './merchant'
import type { PermissionTier } from './organization'

/** 成员与门店摘要（OrgTeamCard）。 */
export interface OrgTeamSummary {
  memberCount: number
  storeCount: number
  /** 待接受的邀请数。 */
  pendingInvitationCount: number
  /** 状态为 pending_review 的成员数（店长代建待主体审核）。 */
  pendingReviewCount: number
}

/** 品牌资料完整度摘要（OrganizationBrandCard）。 */
export interface OrgBrandSummary {
  hasBrandName: boolean
  hasLogo: boolean
  hasDescription: boolean
  hasIndustry: boolean
}

/** 认证资料摘要（MerchantKybCard）。 */
export interface OrgKybSummary {
  /** 商家主体资料状态；null = 尚未创建资料行。独立门店模式下恒为 null。 */
  merchantStatus: MerchantProfileStatus | null
  /** 已通过审核的收款账户数。 */
  approvedWithdrawalCount: number
}

/** 权限与额度摘要（MerchantPermissionCard）。 */
export interface OrgPermissionSummary {
  tier: PermissionTier
  /** 活跃任务余量；null = 用量不可见（非绑定 org，marketplace 403 降级）。 */
  remainingActiveTasks: number | null
  maxActiveTasks: number
  /** 是否有待审核的升级申请。 */
  hasPendingRequest: boolean
}
