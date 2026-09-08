/**
 * 创作工作区类型（任务书 #92 C-02/C-03）。对齐 intelligence CreationDraftController 的
 * 工作区扩展契约：草稿行 + capability/workspace/resultAssetIds/runIds。
 */

/** 工作区能力（§5.1）：四条创作工作流的统一标识。 */
export type CreationProjectCapability = 'article' | 'image' | 'video' | 'moments'

/** 三种加工方式与来源正交：来源描述资料从哪里来，方式描述允许怎样处理。 */
export type CreationProcessingMode = 'create' | 'adapt' | 'format'
export type CreationDeliveryIntent = 'text-only' | 'script-only' | 'complete-content'

export interface CreationSourceRef {
  id: string
  title?: string
  url?: string
  location?: string
  accessedAt?: string
}

export interface CreationFact {
  id?: string
  statement: string
  basis?: 'user-confirmed' | 'material-observed' | 'source-cited' | 'pending'
  confirmed?: boolean
  sourceRef?: string
}

/** 统一创作简报。只保存结构化文本和可定位引用，不保存密钥、签名地址或二进制。 */
export interface CreationBrief {
  processingMode: CreationProcessingMode
  contentSubtype?: string
  deliveryIntent?: CreationDeliveryIntent
  extraInstructions?: string
  objective?: string
  audience?: string
  authorRole?: string
  confirmedExperience?: string
  facts?: CreationFact[]
  sourceRefs?: (CreationSourceRef | string)[]
  materialRefs?: string[]
  commercialRelationship?: string
  aiUsage?: 'confirmed' | 'pending' | 'not-applicable'
}

export type CreationResultRefType = 'media' | 'content-asset'

export interface CreationResultRef {
  id: string
  refType: CreationResultRefType
  role?: 'cover' | 'body' | 'card' | 'subtitle' | 'video' | 'other'
  cardId?: string
  position?: number
  runId?: string
  storyboardId?: string
  productionTaskId?: string
  taskId?: string
}

export type CreationDeclarationState = 'confirmed' | 'pending' | 'not-applicable'
export interface CreationDeclarations {
  aiGenerated?: CreationDeclarationState
  commercial?: CreationDeclarationState
  original?: CreationDeclarationState
}

export interface CreationDeliveryContract {
  version: number
  platform: string
  contentForm: string
  titleOrOpening?: string
  bodyOrDescription?: string
  topics?: string[]
  /** 公众号摘要 / 短视频发布描述摘要（按平台选用）。 */
  summary?: string
  /** 视频号群分享、朋友圈等独立分享配文——修改配文不触发任何媒体重生成。 */
  shareCopy?: string
  coverRef?: CreationResultRef
  mediaRefs?: CreationResultRef[]
  sourceRefs?: (CreationSourceRef | string)[]
  declarations?: CreationDeclarations
  checks?: string[]
}

/** 草稿状态（沿用后端既有小写值，§4.3）。 */
export type CreationProjectStatus = 'draft' | 'in_progress' | 'completed' | 'archived'

/** 最近一次运行状态（存于 workspace_json，不扩展既有 status 枚举）。 */
export type CreationRunState = 'idle' | 'running' | 'succeeded' | 'failed'

/** 工作区恢复态（D-04：只保存可恢复数据——步骤/表单文本/来源 ID/运行状态）。 */
export interface CreationWorkspacePayload {
  schemaVersion?: number
  capability?: CreationProjectCapability
  runState?: CreationRunState
  currentStep?: string
  sourceLabel?: string
  inputs?: Record<string, unknown>
  /** Legacy reader only. New writers store Brief in inputs.brief. */
  brief?: CreationBrief
  resultRefs?: CreationResultRef[]
  delivery?: CreationDeliveryContract
  source?: { storeId?: string; taskId?: string }
  [key: string]: unknown
}

/** 最近项目条目 = 草稿行（正文等字段可选）+ 工作区扩展字段。 */
export interface CreationProject {
  id: string
  title: string
  capability: CreationProjectCapability
  status: CreationProjectStatus
  version: number
  workspace: CreationWorkspacePayload
  resultAssetIds: string[]
  runIds: string[]
  createdAt?: string
  updatedAt: string
  sourceType?: string
  topic?: string
  articleTitle?: string
  outline?: string
  content?: string
  platform?: string
  contentForm?: string
  contentMode?: 'article' | 'answer'
  questionText?: string
  questionRef?: string
  taskId?: string
  taskVersion?: number
  storeId?: string
}

export type CreationProjectFields = Pick<CreationProject,
  'topic' | 'platform' | 'contentForm' | 'contentMode' | 'questionText' | 'questionRef'
  | 'articleTitle' | 'outline' | 'content'>
