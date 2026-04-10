export interface ApiResponse<T> {
  success: boolean
  data?: T
  error?: string
}

export interface ExtractedDouyinVideoPayload {
  sourceUrl: string
  platform: 'douyin'
  videoId?: string
  author?: string
  title?: string
  coverUrl?: string
  proxyVideoUrl: string
  downloadVideoUrl: string
  downloadAudioUrl: string
  usedSession: boolean
  fetchStage: 'page_json' | 'browser_json' | 'browser_network'
}

export interface DouyinSessionState {
  status: 'missing' | 'launching' | 'qr_ready' | 'waiting_for_confirm' | 'authenticated' | 'expired' | 'error'
  hasPersistedSession: boolean
  qrImageUrl?: string
  detailCode?: 'missing' | 'launching' | 'qr_ready' | 'waiting_for_confirm' | 'authenticated' | 'timeout' | 'session_expired' | 'login_failed'
  message?: string
  lastAuthenticatedAt?: string
  lastUsedAt?: string
}
