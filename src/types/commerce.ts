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
  | 'refund_pending' | 'refunded' | 'payment_failed' | 'cancelled'

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
}

export interface ConsumerReview {
  id: string
  orderId: string
  consumerAccountId: string
  rating: number
  comment?: string
  createdAt: string
}
