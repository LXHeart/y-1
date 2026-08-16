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
