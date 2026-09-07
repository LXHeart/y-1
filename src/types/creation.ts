/**
 * 创作工作区类型（任务书 #92 C-02/C-03）。对齐 intelligence CreationDraftController 的
 * 工作区扩展契约：草稿行 + capability/workspace/resultAssetIds/runIds。
 */

/** 工作区能力（§5.1）：四条创作工作流的统一标识。 */
export type CreationProjectCapability = 'article' | 'image' | 'video' | 'moments'

/** 草稿状态（沿用后端既有小写值，§4.3）。 */
export type CreationProjectStatus = 'draft' | 'in_progress' | 'completed' | 'archived'

/** 最近一次运行状态（存于 workspace_json，不扩展既有 status 枚举）。 */
export type CreationRunState = 'idle' | 'running' | 'succeeded' | 'failed'

/** 工作区恢复态（D-04：只保存可恢复数据——步骤/表单文本/来源 ID/运行状态）。 */
export interface CreationWorkspacePayload {
  capability?: CreationProjectCapability
  runState?: CreationRunState
  currentStep?: string
  sourceLabel?: string
  inputs?: Record<string, unknown>
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
  taskId?: string
  taskVersion?: number
  storeId?: string
}
