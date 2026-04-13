export type BilibiliPlaybackMode = 'progressive' | 'dash'

interface BilibiliBaseMetadata {
  videoId?: string
  author?: string
  title?: string
  coverUrl?: string
  durationSeconds?: number
}

interface BilibiliRequestContext {
  requestHeaders?: Record<string, string>
  filename?: string
  durationSeconds?: number
}

export interface BilibiliProgressiveSourceMaterial extends BilibiliBaseMetadata {
  sourceUrl: string
  resolvedUrl: string
  playableVideoUrl: string
  requestHeaders: Record<string, string>
  playbackMode: 'progressive'
}

export interface BilibiliDashSourceMaterial extends BilibiliBaseMetadata {
  sourceUrl: string
  resolvedUrl: string
  videoTrackUrl: string
  audioTrackUrl: string
  requestHeaders: Record<string, string>
  playbackMode: 'dash'
}

export type BilibiliSourceMaterial = BilibiliProgressiveSourceMaterial | BilibiliDashSourceMaterial

export interface BilibiliProgressiveMediaTarget extends BilibiliRequestContext {
  kind: 'progressive'
  playableVideoUrl: string
}

export interface BilibiliDashMediaTarget extends BilibiliRequestContext {
  kind: 'dash'
  videoTrackUrl: string
  audioTrackUrl: string
}

export type BilibiliMediaTarget = BilibiliProgressiveMediaTarget | BilibiliDashMediaTarget
