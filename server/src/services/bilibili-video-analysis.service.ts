import { AppError } from '../lib/errors.js'
import { buildPublicBilibiliProxyUrl, parseBilibiliProxyToken } from './bilibili-proxy.service.js'
import { analyzeVideoContent, type VideoAnalysisResult } from './video-analysis.service.js'

const UNSUPPORTED_DASH_ANALYSIS_MESSAGE = '当前 B 站 DASH 音视频分离样本暂不支持视频分析，请换一个单流样本后再试'

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

  if (target.kind === 'dash') {
    throw new AppError(UNSUPPORTED_DASH_ANALYSIS_MESSAGE, 400)
  }

  return analyzeVideoContent(buildPublicBilibiliProxyUrl(token))
}
