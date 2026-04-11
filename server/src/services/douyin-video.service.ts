import {
  buildDouyinAudioPath,
  buildDouyinDownloadPath,
  buildDouyinProxyPath,
  buildDownloadFilename,
  createDouyinProxyToken,
} from './douyin-proxy.service.js'
import { extractDouyinEntryUrl, resolveDouyinSource, resolveDouyinVideoAsset } from './douyin-resolve.service.js'

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

export async function extractDouyinVideo(input: string): Promise<ExtractedDouyinVideoPayload> {
  const entryUrl = extractDouyinEntryUrl(input)
  const source = await resolveDouyinSource(entryUrl)
  const videoAsset = resolveDouyinVideoAsset(source)
  const filename = buildDownloadFilename({
    title: source.title,
    author: source.author,
    videoId: source.videoId,
  })

  const token = createDouyinProxyToken({
    playableVideoUrl: videoAsset.playableVideoUrl,
    requestHeaders: videoAsset.requestHeaders,
    filename,
  })

  return {
    sourceUrl: source.sourceUrl,
    platform: 'douyin',
    videoId: source.videoId,
    author: source.author,
    title: source.title,
    coverUrl: source.coverUrl,
    proxyVideoUrl: buildDouyinProxyPath(token),
    downloadVideoUrl: buildDouyinDownloadPath(token),
    downloadAudioUrl: buildDouyinAudioPath(token),
    usedSession: videoAsset.usedSession,
    fetchStage: videoAsset.fetchStage,
  }
}
