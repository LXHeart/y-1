export type AiPlatformId =
  | 'xiaohongshu'
  | 'douyin'
  | 'dianping'
  | 'kuaishou'
  | 'wechat-channels'
  | 'bilibili'
  | 'wechat-official'
  | 'zhihu'
  | 'moments'

export type AiContentFormId = 'graphic' | 'video' | 'image-text' | 'video-text'
export type CreationSourceType = 'independent' | 'task' | 'store' | 'hot-topic' | 'reference'
export type CreationWorkflowId = 'longform' | 'review-copy' | 'video-script' | 'reference-analyze'
export type CreationTargetView = 'article' | 'image' | 'video-production' | 'video'

export type CreationSource =
  | { type: 'independent' }
  | { type: 'task'; taskId: string; applicationId?: string; taskVersion?: number }
  | { type: 'store'; organizationId: string; storeId: string }
  | { type: 'hot-topic'; title: string; topicId?: string }
  | { type: 'reference'; sourceUrl?: string }

/**
 * 仅用于改善编辑体验的非权威预填值。它不能参与任务核实、资金处理或权限判断。
 */
export interface CreationDraftPrefill {
  topic?: string
  instructions?: string
  storeName?: string
  address?: string
  storeDescription?: string
}

/** App/工作台进入创作中心时传递的选择引用。revision 防止 KeepAlive 重复覆盖用户编辑。 */
export interface CreationEntry {
  revision: number
  platformId: AiPlatformId | null
  contentFormId: AiContentFormId | null
  source: CreationSource
  prefill?: CreationDraftPrefill
}

/** 创作中心完成合法性解析后交给现有工作流的 handoff。 */
export interface CreationHandoff extends CreationEntry {
  platformId: AiPlatformId
  contentFormId: AiContentFormId
  workflowId: CreationWorkflowId
  targetView: CreationTargetView
}

export interface CreationWorkflowResolution {
  status: 'available' | 'planned' | 'unsupported'
  workflowId: CreationWorkflowId | null
  targetView: CreationTargetView | null
}
