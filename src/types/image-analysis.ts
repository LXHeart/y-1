export type ReviewPlatform = 'taobao' | 'dianping'

export interface ImageAnalysisResult {
  review: string
  title?: string
  tags?: string[]
  runId?: string
  imageCount: number
}

export type ImageAnalysisProgressStage = 'prepare' | 'draft' | 'optimize' | 'style-refine' | 'complete'

export interface ImageAnalysisProgressEvent {
  stage: ImageAnalysisProgressStage
  message: string
  attempt?: number
  totalAttempts?: number
  startedAt?: string
  completedAt?: string
  durationMs?: number
}

export interface ImageAnalysisApiResponse {
  success: boolean
  data?: ImageAnalysisResult
  error?: string
}

export interface ImageAnalysisStreamProgressEvent extends ImageAnalysisProgressEvent {
  type: 'progress'
}

export interface ImageAnalysisStreamResultEvent {
  type: 'result'
  data: ImageAnalysisResult
}

export interface ImageAnalysisStreamErrorEvent {
  type: 'error'
  error: string
}

export type ImageAnalysisStreamEvent = ImageAnalysisStreamProgressEvent | ImageAnalysisStreamResultEvent | ImageAnalysisStreamErrorEvent

export interface FeishuExportResponse {
  success: boolean
  data?: {
    documentId: string
    documentUrl: string
  }
  error?: string
}

export interface ImageReviewStylePreferences {
  preferences: string[]
  updatedAt?: string
}

export interface SaveStyleMemoryResponse {
  success: boolean
  data?: ImageReviewStylePreferences
  error?: string
}

export type GenerationStage = 'idle' | 'drafting' | 'draft-review' | 'optimizing' | 'optimize-review' | 'style-refining' | 'complete'

export interface StepReviewRequest {
  review: string
  title?: string
  tags?: string[]
  reviewLength: number
  feelings?: string
  platform?: ReviewPlatform
}

export interface UpdateStylePreferencesResponse {
  success: boolean
  data?: { preferences: string[] }
  error?: string
}
