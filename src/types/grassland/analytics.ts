export interface AttributionSummary {
  exposures: number
  interactions: number
  conversions: number
  attributedRevenueCents: number
  attributedRefundCents: number
  dataQuality: string
  status: string
  roi: number | null
}

export interface BusinessAnalyticsReport {
  organizationId: string
  storeId: string | null
  orders: number
  paidOrders: number
  redeemedOrders: number
  refundedOrders: number
  grossGmvCents: number
  refundedGmvCents: number
  netGmvCents: number
  merchantRevenueCents: number
  platformFeeCents: number
  recommenderRevenueCents: number
  settledBountyCents: number
  attribution: AttributionSummary
}

export interface RecommenderAnalyticsReport {
  recommenderAccountId: string
  conversions: number
  attributedRevenueCents: number
  recommenderRevenueCents: number
}

export interface MerchantAnalyticsDashboard {
  organizationId: string
  storeId: string | null
  taskCount: number
  publishedTaskCount: number
  totalApplications: number
  acceptedApplications: number
  confirmedDeliverables: number
  settledEngagements: number
  reservedBountyCents: number
  settledBountyCents: number
  applicationAcceptanceRate: number
  averageRating: number | null
  marketingMetrics: {
    exposureCollected: boolean
    interactionCollected: boolean
    conversionCollected: boolean
    exposures: number
    interactions: number
    conversions: number
    attributedRevenueCents: number
    attributedRefundCents: number
    dataQuality: string
    status: string
    roi: number | 'unavailable'
    roiFormula: string
  }
  businessMetrics: Omit<BusinessAnalyticsReport, 'organizationId' | 'storeId' | 'settledBountyCents' | 'attribution'>
}

export interface AnalyticsQuery {
  organizationId: string
  storeId?: string
  from?: string
  to?: string
}
