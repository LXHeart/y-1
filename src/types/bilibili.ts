export interface ApiResponse<T> {
  success: boolean
  data?: T
  error?: string
}

export interface VideoAnalysisRequestConfig {
  provider?: 'coze' | 'qwen'
  baseUrl?: string
  apiToken?: string
  apiKey?: string
  model?: string
}

export type BilibiliPlaybackMode = 'progressive' | 'dash'

export interface ExtractedBilibiliVideoPayload {
  sourceUrl: string
  platform: 'bilibili'
  videoId?: string
  author?: string
  title?: string
  coverUrl?: string
  durationSeconds?: number
  proxyVideoUrl: string
  downloadVideoUrl: string
  playbackMode: BilibiliPlaybackMode
}

export type BilibiliVideoAnalysisResult = import('./video-recreation').VideoAnalysisResult
