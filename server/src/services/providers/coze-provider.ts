import { logger } from '../../lib/logger.js'
import { AppError } from '../../lib/errors.js'
import { providerFetch } from '../../lib/fetch.js'
import type { VideoAnalysisResult, ResolvedProviderConfig, ProviderCallOptions } from './types.js'
import { normalizeVideoAnalysisResult, buildClientAbortedError, describeAnalysisVideoUrlType } from './types.js'

const PROVIDER_FETCH_REDIRECT_POLICY: RequestRedirect = 'error'

function summarizeEndpointForLog(endpoint: string): string {
  try {
    const parsedUrl = new URL(endpoint)
    return parsedUrl.origin + parsedUrl.pathname
  } catch {
    return 'invalid-endpoint'
  }
}

export const cozeProvider = {
  id: 'coze',
  label: 'Coze',
  supportsModelListing: false,

  async analyze(
    videoUrl: string,
    config: ResolvedProviderConfig,
    options: ProviderCallOptions,
  ): Promise<VideoAnalysisResult> {
    const endpoint = config.baseUrl
    const endpointSummary = summarizeEndpointForLog(endpoint)
    const controller = new AbortController()
    const startedAt = Date.now()
    const videoUrlType = describeAnalysisVideoUrlType(videoUrl)
    let abortReason: 'timeout' | 'client_disconnect' | null = null
    let isClientDisconnected = false

    const timeout = setTimeout(() => {
      abortReason = 'timeout'
      controller.abort()
    }, options.timeoutMs)

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
        endpoint: endpointSummary,
        videoUrlType,
        durationMs: 0,
      }, 'Video analysis request aborted before upstream fetch started')
      clearTimeout(timeout)
      throw buildClientAbortedError()
    }

    try {
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
      }

      if (config.apiKey) {
        headers.Authorization = `Bearer ${config.apiKey}`
      }

      const response = await providerFetch(endpoint, {
        method: 'POST',
        headers,
        body: JSON.stringify({
          video_file: {
            url: videoUrl,
            file_type: 'video',
          },
        }),
        signal: controller.signal,
        redirect: PROVIDER_FETCH_REDIRECT_POLICY,
        dispatcher: config.dispatcher,
      })

      if (!response.ok) {
        const responseText = await response.text()

        logger.error({
          endpoint: endpointSummary,
          status: response.status,
          hasToken: Boolean(config.apiKey),
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
            endpoint: endpointSummary,
            videoUrlType,
            durationMs: Date.now() - startedAt,
          }, 'Video analysis request aborted after client disconnected')
          throw buildClientAbortedError()
        }

        logger.warn({
          endpoint: endpointSummary,
          videoUrlType,
          durationMs: Date.now() - startedAt,
          abortReason,
        }, 'Video analysis request timed out')
        throw new AppError('视频内容提取超时，请稍后重试', 504)
      }

      logger.error({
        err: error,
        endpoint: endpointSummary,
        hasToken: Boolean(config.apiKey),
        videoUrlType,
        durationMs: Date.now() - startedAt,
        abortReason,
      }, 'Video analysis request failed')

      throw new AppError('视频内容提取失败，请稍后重试', 502)
    } finally {
      clearTimeout(timeout)
      options.signal?.removeEventListener('abort', abortFromCaller)
    }
  },
} satisfies import('./types.js').VideoAnalysisProvider
