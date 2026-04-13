import { logger } from '../lib/logger.js'
import { env } from '../lib/env.js'
import { AppError } from '../lib/errors.js'

interface AnalyzeVideoContentOptions {
  signal?: AbortSignal
}

export interface VideoAnalysisResult {
  videoCaptions?: string
  videoScript?: string
  charactersDescription?: string
  voiceDescription?: string
  propsDescription?: string
  sceneDescription?: string
  runId?: string
}

interface VideoAnalysisApiResponse {
  video_captions?: unknown
  video_script?: unknown
  characters_description?: unknown
  voice_description?: unknown
  props_description?: unknown
  scene_description?: unknown
  run_id?: unknown
}

function readOptionalString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

function normalizeVideoAnalysisResult(value: unknown): VideoAnalysisResult {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new AppError('视频内容提取服务返回了无效数据', 502)
  }

  const record = value as VideoAnalysisApiResponse

  return {
    videoCaptions: readOptionalString(record.video_captions),
    videoScript: readOptionalString(record.video_script),
    charactersDescription: readOptionalString(record.characters_description),
    voiceDescription: readOptionalString(record.voice_description),
    propsDescription: readOptionalString(record.props_description),
    sceneDescription: readOptionalString(record.scene_description),
    runId: readOptionalString(record.run_id),
  }
}

function buildVideoAnalysisHeaders(): Record<string, string> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  }

  if (env.VIDEO_ANALYSIS_API_TOKEN) {
    headers.Authorization = `Bearer ${env.VIDEO_ANALYSIS_API_TOKEN}`
  }

  return headers
}

function describeAnalysisVideoUrlType(videoUrl: string): 'proxy' | 'analysis-media' | 'other' {
  if (videoUrl.includes('/api/bilibili/proxy/') || videoUrl.includes('/api/douyin/proxy/')) {
    return 'proxy'
  }

  if (videoUrl.includes('/api/bilibili/analysis-media/')) {
    return 'analysis-media'
  }

  return 'other'
}

function buildClientAbortedError(): AppError {
  return new AppError('分析请求已取消', 499)
}

export async function analyzeVideoContent(
  videoUrl: string,
  options: AnalyzeVideoContentOptions = {},
): Promise<VideoAnalysisResult> {
  const endpoint = env.VIDEO_ANALYSIS_API_BASE_URL.trim()
  const controller = new AbortController()
  const startedAt = Date.now()
  const videoUrlType = describeAnalysisVideoUrlType(videoUrl)
  let abortReason: 'timeout' | 'client_disconnect' | null = null
  let isClientDisconnected = false
  const timeout = setTimeout(() => {
    abortReason = 'timeout'
    controller.abort()
  }, env.VIDEO_ANALYSIS_API_TIMEOUT_MS)

  const abortFromCaller = (): void => {
    isClientDisconnected = true
    abortReason = 'client_disconnect'
    controller.abort()
  }

  if (options.signal?.aborted) {
    abortFromCaller()
  } else {
    options.signal?.addEventListener('abort', abortFromCaller, { once: true })
  }

  if (isClientDisconnected) {
    logger.warn({
      endpoint,
      videoUrlType,
      durationMs: 0,
    }, 'Video analysis request aborted before upstream fetch started')
    clearTimeout(timeout)
    throw buildClientAbortedError()
  }

  try {
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: buildVideoAnalysisHeaders(),
      body: JSON.stringify({
        video_file: {
          url: videoUrl,
          file_type: 'video',
        },
      }),
      signal: controller.signal,
    })

    if (!response.ok) {
      const responseText = await response.text()

      logger.error({
        endpoint,
        status: response.status,
        hasToken: Boolean(env.VIDEO_ANALYSIS_API_TOKEN),
        videoUrlType,
        durationMs: Date.now() - startedAt,
        responseTextLength: responseText.length,
      }, 'Video analysis upstream returned non-ok response')

      throw new AppError(`视频内容提取失败（状态码 ${response.status}）`, response.status >= 500 ? 502 : 400)
    }

    const result = await response.json() as unknown
    return normalizeVideoAnalysisResult(result)
  } catch (error: unknown) {
    if (error instanceof AppError) {
      throw error
    }

    if (error instanceof DOMException && error.name === 'AbortError') {
      if (isClientDisconnected) {
        logger.warn({
          endpoint,
          videoUrlType,
          durationMs: Date.now() - startedAt,
        }, 'Video analysis request aborted after client disconnected')
        throw buildClientAbortedError()
      }

      logger.warn({
        endpoint,
        videoUrlType,
        durationMs: Date.now() - startedAt,
        abortReason,
      }, 'Video analysis request timed out')
      throw new AppError('视频内容提取超时，请稍后重试', 504)
    }

    logger.error({
      err: error,
      endpoint,
      hasToken: Boolean(env.VIDEO_ANALYSIS_API_TOKEN),
      videoUrlType,
      durationMs: Date.now() - startedAt,
      abortReason,
    }, 'Video analysis request failed')

    throw new AppError('视频内容提取失败，请稍后重试', 502)
  } finally {
    clearTimeout(timeout)
    options.signal?.removeEventListener('abort', abortFromCaller)
  }
}
