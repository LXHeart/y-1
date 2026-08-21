export type CreationGenerationKind =
  | 'video_adaptation'
  | 'asset_image'
  | 'scene_image'
  | 'article'
  | 'moments_copy'
  | 'comedy_script'
  | 'assistant_guide'
export type CreationGenerationMode = 'independent' | 'task'
export type CreationGenerationResolution = 'platform' | 'byok'

export interface CreationGenerationSummary {
  id: string
  kind: CreationGenerationKind
  mode: CreationGenerationMode
  provider: string
  model: string | null
  resultTitle: string
  createdAt: string
}

export interface CreationGenerationResultMediaItem {
  mediaId: string
  imageUrl: string | null
  available: boolean
}

export interface CreationGenerationDetail extends CreationGenerationSummary {
  contextSnapshotId: string | null
  aiRunId: string | null
  resolution: CreationGenerationResolution
  platformModelVersion: number | null
  upstreamRunId: string | null
  promptText: string
  inputSummary: Record<string, unknown>
  result: Record<string, unknown>
  resultMedia: CreationGenerationResultMediaItem[]
}

export interface CreationGenerationPage {
  items: CreationGenerationSummary[]
  nextBefore: string | null
}

/** 组织审计视图摘要（任务书 #44 登记）：带 ownerAccountId（审计需要「谁」）。 */
export interface OrgCreationGenerationSummary extends CreationGenerationSummary {
  ownerAccountId: string
}

export const CREATION_GENERATION_KIND_LABELS: Record<CreationGenerationKind, string> = {
  video_adaptation: '视频改编',
  asset_image: '素材图',
  scene_image: '分镜图',
  article: '文章正文',
  moments_copy: '朋友圈文案',
  comedy_script: '喜剧脚本',
  assistant_guide: '创作引导',
}
