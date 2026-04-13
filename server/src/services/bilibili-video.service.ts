import { buildBilibiliDownloadFilename } from '../lib/bilibili-filename.js'
import { createBilibiliProxyToken } from './bilibili-proxy.service.js'
import type { BilibiliPlaybackMode } from './bilibili.types.js'
import { extractBilibiliEntryUrl, resolveBilibiliSource } from './bilibili-resolve.service.js'

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

export async function extractBilibiliVideo(input: string): Promise<ExtractedBilibiliVideoPayload> {
  const entryUrl = extractBilibiliEntryUrl(input)
  const source = await resolveBilibiliSource(entryUrl)
  const filename = buildBilibiliDownloadFilename({
    title: source.title,
    author: source.author,
    videoId: source.videoId,
  })

  const token = source.playbackMode === 'progressive'
    ? createBilibiliProxyToken({
        kind: 'progressive',
        playableVideoUrl: source.playableVideoUrl,
        requestHeaders: source.requestHeaders,
        filename,
        durationSeconds: source.durationSeconds,
      })
    : createBilibiliProxyToken({
        kind: 'dash',
        videoTrackUrl: source.videoTrackUrl,
        audioTrackUrl: source.audioTrackUrl,
        requestHeaders: source.requestHeaders,
        filename,
        durationSeconds: source.durationSeconds,
      })

  return {
    sourceUrl: source.sourceUrl,
    platform: 'bilibili',
    videoId: source.videoId,
    author: source.author,
    title: source.title,
    coverUrl: source.coverUrl,
    durationSeconds: source.durationSeconds,
    proxyVideoUrl: `/api/bilibili/proxy/${encodeURIComponent(token)}`,
    downloadVideoUrl: `/api/bilibili/download/${encodeURIComponent(token)}`,
    playbackMode: source.playbackMode,
  }
}
