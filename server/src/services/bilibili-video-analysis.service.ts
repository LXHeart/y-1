import { AppError } from '../lib/errors.js'
import {
  buildPublicBilibiliAnalysisMediaUrl,
  createBilibiliAnalysisMediaSession,
} from './bilibili-analysis-media.service.js'
import { prepareBilibiliMediaFile } from './bilibili-media.service.js'
import { buildPublicBilibiliProxyUrl, parseBilibiliProxyToken } from './bilibili-proxy.service.js'
import { analyzeVideoContent, type VideoAnalysisResult } from './video-analysis.service.js'

function extractTokenFromProxyUrl(proxyVideoUrl: string): string {
  let parsedUrl: URL

  try {
    parsedUrl = new URL(proxyVideoUrl, 'http://localhost')
  } catch {
    throw new AppError('视频代理地址无效', 400)
  }

  const match = parsedUrl.pathname.match(/\/api\/bilibili\/proxy\/([^/]+)$/)
  if (!match?.[1]) {
    throw new AppError('视频代理地址无效', 400)
  }

  return decodeURIComponent(match[1])
}

export async function analyzeBilibiliVideoByProxyUrl(proxyVideoUrl: string): Promise<VideoAnalysisResult> {
  const token = extractTokenFromProxyUrl(proxyVideoUrl)
  const target = parseBilibiliProxyToken(token)

  if (target.kind === 'progressive') {
    return analyzeVideoContent(buildPublicBilibiliProxyUrl(token))
  }

  const mediaFile = await prepareBilibiliMediaFile(target)
  const session = await createBilibiliAnalysisMediaSession({
    filePath: mediaFile.filePath,
    fileSize: mediaFile.fileSize,
    filename: mediaFile.filename,
    mimeType: mediaFile.mimeType,
  })

  return analyzeVideoContent(buildPublicBilibiliAnalysisMediaUrl(session.id))
}
