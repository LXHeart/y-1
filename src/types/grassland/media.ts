// ---------- intelligence：media 直传 + 内容素材库 ----------

// ---------- intelligence：media 直传（三步上传）----------

/** 附件用途。履约附件是唯一允许 marketplace 跨账号读的 purpose（服务间断点的放行条件）。 */
export type MediaPurpose =
  'engagement_attachment' | 'merchant_kyb' | 'video_asset' | 'user_upload' | 'avatar'
  | 'content_asset' | 'speech_audio'

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

// ---------- 内容素材库（PRD §4.8）----------

/** 素材库类型（与后端 LibraryType 枚举对齐）。 */
export type ContentLibraryType = 'personal' | 'merchant' | 'public'

/** 素材分类（与后端 AssetCategory 枚举对齐）。 */
export type ContentAssetCategory = 'store' | 'product' | 'campaign' | 'scene' | 'brand' | 'copy' | 'other'

/** 素材状态（与后端 AssetStatus 枚举对齐）。 */
export type ContentAssetStatus = 'draft' | 'pending_review' | 'active' | 'rejected' | 'expired'

/** 素材条目（content_asset 表的响应投影）。mediaId 是 media_reference 的跨服务引用句柄。 */
export interface ContentAsset {
  id: string
  mediaId: string
  libraryType: ContentLibraryType
  category: ContentAssetCategory
  title: string
  tags: string[]
  status: ContentAssetStatus
  version: number
  mimeType?: string | null
  sizeBytes?: number | null
  validUntil?: string | null
  organizationId?: string | null
  storeId?: string | null
  source?: string | null
  licenseScope?: string | null
  createdAt: string | null
  updatedAt: string | null
}

/** 素材历史快照（content_asset_version，PRD §4.8「更新不覆盖历史快照」）。 */
export interface ContentAssetVersion {
  version: number
  title: string
  category: ContentAssetCategory
  tags: string[]
  mimeType?: string | null
  sizeBytes?: number | null
  validUntil?: string | null
  snapshottedAt: string | null
  snapshottedBy: string | null
  storeId?: string | null
}

/** 素材授权（content_asset_grant，商家指定推荐官可用）。 */
export interface ContentAssetGrant {
  grantType: string
  granteeAccountId: string
  grantedBy: string
  grantedAt: string | null
  leaseUntil?: string | null
  retainedUntil?: string | null
  releasedAt?: string | null
}

/** 智能素材推荐条目（PRD §4.8「按任务和平台智能推荐」）：素材 + 分数与可解释理由。 */
export interface RecommendedContentAsset extends ContentAsset {
  score: number
  /** 纯规则分（语义运行中 score=60/40 融合分或缺向量的规则份额）。 */
  ruleScore: number
  /** 语义分 0-100；仅语义运行且该素材有当前向量时存在。 */
  semanticScore?: number
  reasons: string[]
}

/** 推荐请求：任务模式（applicationId+taskId 成对，服务端拉权威任务上下文）或独立模式显式参数。 */
export interface ContentAssetRecommendationInput {
  applicationId?: string
  taskId?: string
  platform?: string
  contentForm?: string
  category?: ContentAssetCategory
  keywords?: string[]
  /** 自然语言语义查询（trim 后 1-500 字符；缺省=纯规则推荐或任务模式派生）。 */
  query?: string
  limit?: number
}

/** 语义运行元数据：not_requested=纯规则；applied=语义重排；fallback=降级回规则排序。 */
export interface SemanticRecommendationMetadata {
  status: 'not_requested' | 'applied' | 'fallback'
  provider?: string
  model?: string
  sandbox?: boolean
  message?: string
}

/** 推荐响应：排序条目 + 服务端实际采用的检索上下文（terms 为分词后的检索词）。 */
export interface ContentAssetRecommendationResult {
  items: RecommendedContentAsset[]
  query: {
    platform: string
    contentForm: string
    category: string
    terms: string[]
    semantic: SemanticRecommendationMetadata
  }
  sourceTitle?: string
}

/** 创建素材请求（POST /api/content-assets）。个人/商家库可省略 source/licenseScope。 */
export interface CreateContentAssetInput {
  libraryType: ContentLibraryType
  mediaId: string
  category: ContentAssetCategory
  title: string
  tags?: string[]
  validUntil?: string
  source?: string
  licenseScope?: string
  /** 门店经理不持有 merchant identity 时必须显式携带。 */
  organizationId?: string
  storeId?: string
}

/** 编辑素材请求（PUT /api/content-assets/{id}，乐观锁）。 */
export interface UpdateContentAssetInput {
  expectedVersion: number
  category: ContentAssetCategory
  title: string
  tags?: string[]
  validUntil?: string
}

/** 附件下载 URL（marketplace 经服务断言中转 intelligence 签发）。⚠️ `expiresAt` 是**资产 TTL 而非 URL 过期时间**。 */
export interface AttachmentDownload {
  downloadUrl: string
  expiresAt: string | null
}

// ---------- 语音转写（任务书 #33）----------

/** 转写语言：auto=自动检测，zh-CN / en-US。 */
export type SpeechLanguage = 'auto' | 'zh-CN' | 'en-US'

/** 语音转写记录（POST/GET /api/speech/transcriptions 的响应投影）。 */
export interface SpeechTranscription {
  id: string
  mediaId: string
  status: 'processing' | 'completed' | 'failed'
  text: string | null
  language: SpeechLanguage
  durationMs: number
  provider: string | null
  model: string | null
  modelVersion: number | null
  aiRunId: string | null
  sandbox: boolean
  createdAt: string | null
  completedAt: string | null
}
