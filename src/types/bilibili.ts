export interface ApiResponse<T> {
  success: boolean
  data?: T
  error?: string
}

export type BilibiliPlaybackMode = 'progressive' | 'dash'

export interface ExtractedBilibiliVideoPayload {
  sourceUrl: string
  platform: 'bilibili'
  videoId?: string
  author?: string
  title?: string
  coverUrl?: string
  proxyVideoUrl: string
  downloadVideoUrl: string
  playbackMode: BilibiliPlaybackMode
}
