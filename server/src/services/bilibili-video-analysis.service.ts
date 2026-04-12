import { AppError } from '../lib/errors.js'
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
  parseBilibiliProxyToken(token)

  return analyzeVideoContent(buildPublicBilibiliProxyUrl(token))
}
