export interface CommercePackage {
  id: string
  organizationId: string
  storeId?: string
  taskId?: string
  status: 'draft' | 'published' | 'off_sale'
  version: number
  title: string
  description: string
  priceCents: number
  totalStock: number
  remainingStock: number
  inventorySlots?: InventorySlot[]
  fixedRedeemDeadline?: string
  validDaysAfterPurchase?: number
  recommenderShareBps: number
  platformFeeBps: number
  merchantShareBps: number
  /** 任务书 #75 D2：固定佣（分/单）；非空 = 固定佣形态（后端仅在非空时回键）。 */
  recommenderFixedCents?: number
  policyVersion: string
  promotionPath: string
  createdAt: string
  updatedAt: string
}
export type ConsumerOrderStatus =
  | 'pending_payment' | 'paid' | 'redeeming' | 'redeemed' | 'splitting'
  | 'refund_pending' | 'partially_refunded' | 'refunded' | 'after_sales_disputed' | 'payment_failed' | 'cancelled'

/** 任务书 #98 D98-01：服务端发放的不透明推广链接（rlid）；过期为读时判定（effectiveStatus）。 */
export interface ReferralLink {
  referralLinkId: string
  shortCode?: string
  taskId: string
  packageId?: string
  /** 站内相对路径（/?view=commerce&package=X&rlid=Y）；前端拼接 origin 后展示/复制。 */
  url: string
  status: 'active' | 'ended' | 'expired'
  /** manual=本人终止；过期无 reason（status 自明）。 */
  endedReason?: string | null
  createdAt: string
  expiresAt: string
  policyVersion: string
}

/** 任务书 #98 D98-02：归因解释（消费者/推荐官/治理台三端同构读模型）。 */
export interface AttributionExplain {
  orderId: string
  attributed: boolean
  recommenderAccountId?: string
  referralLinkId?: string
  shortCode?: string
  touchedAt?: string
  windowDays: number
  policyVersion: string
  /** last_touch=窗口内末次触达；order_time=订单时触达（全程未登录链路）；not_attributed=自然流量。 */
  basis: string
  reason?: string | null
}

/** 治理台链接生命周期（按 rlid 查）：发放 + 触达 + 归因订单 + 失效原因。 */
export interface ReferralLifecycle {
  link: ReferralLink
  touchCount: number
  recentTouches: Array<{ touchedAt: string; consumerAccountId?: string | null; context: string }>
  orders: Array<{ orderId: string; status: string; priceCents: number; recommenderAmountCents: number; createdAt: string }>
}

/** 任务书 #98 C98-05：经营看板指标（带数据来源与统计窗口标注，D98-06）。 */
export interface OpsDashboardMetric {
  key: string
  label: string
  valueCents: number
  source: string
  window: string
  note?: string | null
}

export interface OpsDashboard {
  windowDays: number
  from?: string
  to?: string
  asOf?: string
  timezone?: string
  metrics: OpsDashboardMetric[]
  computedAt: string
}

/** 任务书 #98 D98-05：异常订单暂扣队列行（flagged 未确认不碰钱；held=人工确认挂起）。 */
export interface OpsOrderHold {
  id: string
  orderId: string
  rule: 'referral_refund_rate' | 'appeal_burst' | 'rlid_order_burst'
  reason: string
  status: 'flagged' | 'held' | 'released' | 'dismissed'
  flaggedAt: string
  confirmedAt?: string | null
  holdDeadlineAt?: string | null
  releasedAt?: string | null
  releasedReason?: string | null
}

export interface ConsumerOrder {
  id: string
  consumerAccountId: string
  organizationId: string
  storeId?: string
  packageId: string
  packageVersion: number
  packageTitle: string
  recommenderAccountId?: string
  priceCents: number
  recommenderAmountCents: number
  merchantAmountCents: number
  platformFeeCents: number
  refundedAmountCents?: number
  refundRequestedAmountCents?: number
  refundReason?: string
  inventorySlotId?: string
  slotStart?: string
  slotEnd?: string
  attributionAllocations?: AttributionAllocation[]
  status: ConsumerOrderStatus
  redeemDeadline: string
  /** 任务书 #41：支付截止（下单时快照）；超时未支付订单会被关单并释放库存。终态/历史行可能为 null。 */
  paymentDeadline?: string | null
  /** 任务书 #75：订单归属的推广任务快照（套餐推广任务期间下单才有值）。 */
  taskId?: string
  /** 任务书 #75 D3：分账冷静期到期时刻（核销时快照）；到期由 dispatcher 触发分账。 */
  splitEligibleAt?: string
  /** 任务书 #75 D3：分账完成时刻（完成前佣金处于待结算）。 */
  splitCompletedAt?: string
  /** 任务书 #97：服务端驱动的退款禁用原因（settled_no_refund=已结算不支持退款）；前端只读不推断。 */
  refundBlockedReason?: string
  redeemCode?: string
  providerRef?: string
  lastError?: string
  createdAt: string
  paidAt?: string
  redeemedAt?: string
  refundedAt?: string
}

export interface CommercePackageInput {
  organizationId: string
  storeId?: string
  taskId?: string
  title: string
  description?: string
  priceCents: number
  totalStock: number
  fixedRedeemDeadline?: string
  validDaysAfterPurchase?: number
  recommenderShareBps: number
  platformFeeBps: number
  /** 任务书 #75 D2：固定佣（分/单）；非空 = 固定佣形态（与 recommenderShareBps>0 互斥，后端 400）。 */
  recommenderFixedCents?: number
  policyVersion?: string
  inventorySlots?: InventorySlotInput[]
}

export interface InventorySlot {
  id: string
  packageVersionId: string
  storeId?: string
  slotStart: string
  slotEnd: string
  totalStock: number
  remainingStock: number
}

export interface InventorySlotInput {
  storeId?: string
  slotStart: string
  slotEnd: string
  totalStock: number
}

export interface AttributionAllocation {
  recommenderAccountId: string
  shareBps: number
  amountCents?: number
}

/** 归因申诉（2026-09-07 业务审查 C01）：买家只主张推荐官，分成由订单冻结规则计算。 */
export interface AttributionAppeal {
  id: string
  orderId: string
  consumerAccountId: string
  claimedRecommenderAccountId: string
  reason: string
  status: 'open' | 'applied' | 'rejected'
  resolutionNote?: string
  reviewedBy?: string
  reviewedAt?: string
  createdAt: string
}

export interface AfterSalesDispute {
  id: string
  orderId: string
  consumerAccountId: string
  reason: string
  status: 'open' | 'resolved' | 'rejected'
  resolution?: 'refund' | 'reject'
  resolutionAmountCents?: number
  resolutionReason?: string
  refundOperationId?: string
  resolutionActorAccountId?: string
  createdAt: string
  resolvedAt?: string
}

export interface ConsumerReview {
  id: string
  orderId: string
  consumerAccountId: string
  rating: number
  comment?: string
  createdAt: string
}
