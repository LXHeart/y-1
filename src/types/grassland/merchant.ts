import type { Industry } from './organization'

// ---------- GL-P3-MERCHANT-001：商家 KYB 资料 ----------

/** 商家资料状态。 */
export type MerchantProfileStatus = 'draft' | 'pending' | 'under_review' | 'approved' | 'rejected'

/** 商家经营地址请求结构；响应中的 jsonb 暂仍由 Java 序列化为字符串。 */
export interface BusinessAddress {
  province?: string
  city?: string
  district?: string
  address: string
}

/** 商家主体详细资料。 */
export interface MerchantProfile {
  organizationId: string
  legalName: string | null
  unifiedSocialCreditCode: string | null
  /** 组织准入行业；由 KYB 表单维护，响应来自 organization.industry。 */
  industry: Industry | string | null
  businessType: string | null
  legalPersonName: string | null
  legalPersonIdNumberMasked: string | null
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
  industry?: Industry
  businessType?: string
  legalPersonName?: string
  legalPersonIdNumber?: string
  registeredCapitalCents?: number
  establishmentDate?: string // YYYY-MM-DD
  businessAddress?: BusinessAddress
  contactPhone?: string
  contactEmail?: string
}

/** 商家附件类型。 */
export type MerchantAttachmentType =
  | 'business_license'
  | 'legal_person_id_front'
  | 'legal_person_id_back'
  | 'industry_license'
  | 'financial_qualification'
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
  ocrStatus?: 'not_applicable' | 'pending' | 'processing' | 'passed' | 'needs_review' | 'failed'
  ocrAnalyzedAt?: string | null
  ocrFailureCode?: string | null
  uploadedAt: string | null
}

/** 创建附件请求。 */
export interface CreateMerchantAttachmentInput {
  attachmentType: MerchantAttachmentType
  mediaReferenceId: string
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
  accountNumberMasked: string
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
  accountNumber: string
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

export interface KybReviewAttachment {
  id: string
  attachmentType: MerchantAttachmentType
  mimeType: string | null
  sizeBytes: number | null
  uploadedAt: string | null
}

export interface MerchantProfileReviewSubject {
  type: 'merchant_profile'
  organizationId: string
  legalName: string | null
  unifiedSocialCreditCode: string | null
  industry: Industry | string | null
  businessType: string | null
  legalPersonName: string | null
  legalPersonIdNumberMasked: string | null
  registeredCapitalCents: number | null
  establishmentDate: string | null
  businessAddress: string | null
  contactPhone: string | null
  contactEmail: string | null
  status: MerchantProfileStatus
}

export interface WithdrawalAccountReviewSubject {
  type: 'withdrawal_account'
  id: string
  organizationId: string
  accountType: WithdrawalAccountType
  accountName: string
  accountNumberMasked: string
  bankName: string | null
  branchName: string | null
  status: WithdrawalAccountStatus
}

export interface StoreProfileReviewSubject {
  type: 'store_profile'
  storeId: string
  address: string | null
  phone: string | null
  businessHours: string | null
  description: string | null
  status: StoreProfileStatus
}

export type KybReviewSubject =
  | MerchantProfileReviewSubject
  | WithdrawalAccountReviewSubject
  | StoreProfileReviewSubject

export interface KybVerificationDetail {
  request: KybVerificationRequest
  subject: KybReviewSubject
  attachments: KybReviewAttachment[]
}

export interface KybAttachmentDownload {
  downloadUrl: string
  expiresAt: string | null
}

/** 门店详细资料。 */
export type StoreProfileStatus = 'draft' | 'pending' | 'under_review' | 'approved' | 'rejected' | 'inactive'

export interface StoreProfile {
  storeId: string
  address: string | null // JSON: {province,city,district,address,longitude,latitude}
  phone: string | null
  businessHours: string | null // JSON: [{dayOfWeek,openTime,closeTime}]
  description: string | null
  /** 任务书 #24：PRD §2.1 营销字段（列表类后端返回真数组）。 */
  categories: string[]
  signatureItems: string[]
  sellingPoints: string[]
  mustEmphasize: string[]
  forbiddenPhrases: string[]
  allowedTags: string[]
  brandTone: string | null
  priceRange: string | null
  averageSpendCents: number | null
  visitNotes: string | null
  status: StoreProfileStatus
  submittedAt: string | null
  reviewedAt: string | null
  reviewerAccountId: string | null
  reviewNote: string | null
  createdAt: string | null
}

/** 创建/更新门店资料请求。营销字段整份覆盖：空数组与不传等价（清空语义）。 */
export interface CreateStoreProfileInput {
  address?: string // JSON string
  phone?: string
  businessHours?: string // JSON string
  description?: string
  categories?: string[]
  signatureItems?: string[]
  sellingPoints?: string[]
  mustEmphasize?: string[]
  forbiddenPhrases?: string[]
  allowedTags?: string[]
  brandTone?: string
  priceRange?: string
  averageSpendCents?: number
  visitNotes?: string
}

/**
 * 门店公开资料白名单（任务书 #24：GET /api/stores/{storeId}/public-profile）。
 * 不含 KYB 审核列/组织内部字段；字段与后端白名单一一对齐。
 */
export interface StorePublicProfile {
  storeId: string
  storeName: string
  address: string | null // JSON: {province,city,district,address,longitude,latitude}
  phone: string | null
  businessHours: string | null // JSON: [{dayOfWeek,openTime,closeTime}]
  description: string | null
  categories: string[]
  signatureItems: string[]
  priceRange: string | null
  averageSpendCents: number | null
  visitNotes: string | null
  sellingPoints: string[]
  brandTone: string | null
  mustEmphasize: string[]
  forbiddenPhrases: string[]
  allowedTags: string[]
}

/** AI 商家上下文：任务创作快照里冻结的门店品牌块（无门店任务无此块）。 */
export interface StoreBranding {
  storeName: string | null
  brandTone: string | null
  mustEmphasize: string[]
  forbiddenPhrases: string[]
  allowedTags: string[]
  sellingPoints: string[]
  categories: string[]
  signatureItems: string[]
}
