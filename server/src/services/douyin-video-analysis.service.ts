import { AppError } from '../lib/errors.js'
import { buildPublicDouyinProxyUrl, parseDouyinProxyToken } from './douyin-proxy.service.js'
import { analyzeVideoContent, type VideoAnalysisResult } from './video-analysis.service.js'

function extractTokenFromProxyUrl(proxyVideoUrl: string): string {
  let parsedUrl: URL

  try {
    parsedUrl = new URL(proxyVideoUrl, 'http://localhost')
  } catch {
    throw new AppError('视频代理地址无效', 400)
  }

  const match = parsedUrl.pathname.match(/\/api\/douyin\/proxy\/([^/]+)$/)
  if (!match?.[1]) {
    throw new AppError('视频代理地址无效', 400)
  }

  return decodeURIComponent(match[1])
}

export async function analyzeDouyinVideoByProxyUrl(proxyVideoUrl: string): Promise<VideoAnalysisResult> {
  const token = extractTokenFromProxyUrl(proxyVideoUrl)
  parseDouyinProxyToken(token)

  return analyzeVideoContent(buildPublicDouyinProxyUrl(token))
}
