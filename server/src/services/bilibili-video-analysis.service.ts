import { AppError } from '../lib/errors.js'
import {
  buildPublicBilibiliAnalysisMediaUrl,
  createBilibiliAnalysisMediaSession,
  deleteBilibiliAnalysisMediaSession,
} from './bilibili-analysis-media.service.js'
import { cleanupBilibiliMediaFile, prepareBilibiliMediaFile } from './bilibili-media.service.js'
import { logger } from '../lib/logger.js'
import { buildPublicBilibiliProxyUrl, parseBilibiliProxyToken } from './bilibili-proxy.service.js'
import { analyzeVideoContent, type VideoAnalysisResult } from './video-analysis.service.js'

const maxAnalysisDurationSeconds = 5 * 60

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

function assertBilibiliAnalysisDuration(durationSeconds: number | undefined): void {
  if (!durationSeconds) {
    throw new AppError('未能识别视频时长，请重新提取后再分析', 422)
  }

  if (durationSeconds > maxAnalysisDurationSeconds) {
    throw new AppError('当前仅支持分析 5 分钟以内的 B 站视频，建议选择 30 秒到 2 分钟的视频', 422)
  }
}

function assertBilibiliAnalysisMediaPublicUrlAvailable(): void {
  buildPublicBilibiliAnalysisMediaUrl('bilibili-analysis-media-preflight')
}

export async function analyzeBilibiliVideoByProxyUrl(proxyVideoUrl: string): Promise<VideoAnalysisResult> {
  const token = extractTokenFromProxyUrl(proxyVideoUrl)
  const target = parseBilibiliProxyToken(token)

  assertBilibiliAnalysisDuration(target.durationSeconds)

  if (target.kind === 'progressive') {
    return analyzeVideoContent(buildPublicBilibiliProxyUrl(token))
  }

  assertBilibiliAnalysisMediaPublicUrlAvailable()

  const mediaFile = await prepareBilibiliMediaFile(target)
  let analysisMediaSessionId: string | undefined

  try {
    const session = await createBilibiliAnalysisMediaSession({
      filePath: mediaFile.filePath,
      fileSize: mediaFile.fileSize,
      filename: mediaFile.filename,
      mimeType: mediaFile.mimeType,
    })
    analysisMediaSessionId = session.id

    const analysisMediaUrl = buildPublicBilibiliAnalysisMediaUrl(session.id)
    return await analyzeVideoContent(analysisMediaUrl)
  } catch (error: unknown) {
    if (analysisMediaSessionId) {
      try {
        await deleteBilibiliAnalysisMediaSession(analysisMediaSessionId)
      } catch (cleanupError: unknown) {
        logger.warn({
          sessionId: analysisMediaSessionId,
          err: cleanupError instanceof Error
            ? { name: cleanupError.name, message: cleanupError.message }
            : { message: 'Unknown cleanup error' },
        }, 'Failed to clean up Bilibili analysis media session after analysis error')
      }
    } else {
      await cleanupBilibiliMediaFile(mediaFile.filePath)
    }

    throw error
  }
}
