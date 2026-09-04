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
  | 'pending_payment' | 'paid' | 'redeeming' | 'redeemed'
  | 'refund_pending' | 'partially_refunded' | 'refunded' | 'after_sales_disputed' | 'payment_failed' | 'cancelled'

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
