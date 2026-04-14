export interface ApiResponse<T> {
  success: boolean
  data?: T
  error?: string
}

export interface VideoAnalysisRequestConfig {
  baseUrl?: string
  apiToken?: string
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

export interface BilibiliVideoAnalysisResult {
  videoCaptions?: string
  videoScript?: string
  charactersDescription?: string
  voiceDescription?: string
  propsDescription?: string
  sceneDescription?: string
  runId?: string
  segmented?: boolean
  clipCount?: number
  runIds?: string[]
}
