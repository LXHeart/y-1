/**
 * 智能创作助手类型（草场 PRD §4.9 / Slice 15 Stage 5）。
 *
 * 后端契约见 intelligence `CreationAssistantController`（SSE 帧）与 `CreationDraftController`（REST）。
 * SSE 帧的判别字段是 `type`，但 `/suggest` 是纯流式**不带 type**（只有 `content`），两者解析路径不同。
 */

export const DRAFT_STATUSES = ['draft', 'in_progress', 'completed', 'archived'] as const
export type DraftStatus = (typeof DRAFT_STATUSES)[number]
export type DraftSourceType = 'independent' | 'task' | 'store' | 'hot-topic' | 'reference'

/** 草稿实体，镜像后端 `CreationDraftController.toResponse`（可空字段后端会省略而非发 null）。 */
export interface CreationDraft {
  id: string
  title: string
  sourceType: DraftSourceType
  status: DraftStatus
  version: number
  createdAt: string
  updatedAt: string
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

export interface CreateDraftInput {
  title?: string
  sourceType: DraftSourceType
  taskId?: string
  taskVersion?: number
  storeId?: string
  platform?: string
  contentForm?: string
  topic?: string
}

/** 自动保存入参。`expectedVersion` 是乐观锁，冲突后端返 409。 */
export interface SaveDraftInput {
  expectedVersion: number
  title?: string
  topic?: string
  articleTitle?: string
  outline?: string
  content?: string
  platform?: string
  contentForm?: string
  status?: DraftStatus
}

/** 自动保存状态机，驱动 UI 上的「已保存 / 保存中 / 冲突」提示。 */
export type AutosaveState = 'idle' | 'pending' | 'saving' | 'saved' | 'conflict' | 'error'

/** 单维度评分（§4.9.6，五维度：标题吸引力/关键词/结构/互动引导/平台规范）。 */
export interface ScoreDimension {
  dimension: string
  score: number
  advice: string
}

export interface ContentScore {
  dimensions: ScoreDimension[]
  overall: number
}

/** 引导问题（AI 决定继续问）。 */
export interface GuideAsk {
  type: 'ask'
  question: string
}

/**
 * 创作简报（AI 认为信息已足够）。
 *
 * `inferredFields` 后端以逗号分隔字符串下发，composable 解析成数组 —— §4.9.2 要求「明确标记推测内容」，
 * UI 据此给对应字段加推测标记。
 */
export interface GuideBrief {
  type: 'brief'
  angle: string
  audience: string
  structure: string
  inferredFields: string[]
}

export type GuideResult = GuideAsk | GuideBrief

/** 对话消息（chat UI 用；assistant 消息可能携带 brief）。 */
export interface GuideMessage {
  role: 'user' | 'assistant'
  text: string
  brief?: GuideBrief
}

/** 单条任务要求的覆盖情况（§4.9.3）。 */
export interface CoverageGap {
  requirement: string
  status: string
  hint: string
}

export interface TaskCoverage {
  gaps: CoverageGap[]
  covered: boolean
}

/**
 * 热点结构化选题（§4.9.5）。后端 `entryPoints` 以「；」分隔下发，composable 解析成数组。
 */
export interface StructuredTopic {
  topic: string
  angle: string
  thesis: string
  audience: string
  entryPoints: string[]
}
