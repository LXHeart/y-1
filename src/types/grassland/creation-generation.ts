export type CreationGenerationKind = 'video_adaptation' | 'asset_image' | 'scene_image'
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
