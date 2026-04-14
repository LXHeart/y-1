import { ref } from 'vue'
import type { ApiResponse, DouyinVideoAnalysisResult, VideoAnalysisRequestConfig } from '../types/douyin'

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function readOptionalString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

function readOptionalPositiveInteger(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) && value > 0 ? value : undefined
}

function readOptionalStringArray(value: unknown): string[] | undefined {
  if (!Array.isArray(value)) {
    return undefined
  }

  const normalizedValues = value.flatMap((item) => {
    const normalizedValue = readOptionalString(item)
    return normalizedValue ? [normalizedValue] : []
  })

  return normalizedValues.length ? normalizedValues : undefined
}

function normalizeDouyinVideoAnalysisResult(value: unknown): DouyinVideoAnalysisResult | null {
  if (!isPlainObject(value)) {
    return null
  }

  return {
    videoCaptions: readOptionalString(value.videoCaptions),
    videoScript: readOptionalString(value.videoScript),
    charactersDescription: readOptionalString(value.charactersDescription),
    voiceDescription: readOptionalString(value.voiceDescription),
    propsDescription: readOptionalString(value.propsDescription),
    sceneDescription: readOptionalString(value.sceneDescription),
    runId: readOptionalString(value.runId),
    segmented: value.segmented === true ? true : undefined,
    clipCount: readOptionalPositiveInteger(value.clipCount),
    runIds: readOptionalStringArray(value.runIds),
  }
}

function normalizeAnalysisConfig(config: VideoAnalysisRequestConfig | undefined): VideoAnalysisRequestConfig | undefined {
  if (!config) {
    return undefined
  }

  const baseUrl = readOptionalString(config.baseUrl)
  const apiToken = readOptionalString(config.apiToken)

  if (!baseUrl && !apiToken) {
    return undefined
  }

  return {
    baseUrl,
    apiToken,
  }
}

export function useDouyinVideoAnalysis() {
  const analysis = ref<DouyinVideoAnalysisResult | null>(null)
  const loading = ref(false)
  const error = ref('')
  let requestCounter = 0
  let currentController: AbortController | null = null

  async function analyzeVideo(
    proxyVideoUrl: string,
    analysisConfig?: VideoAnalysisRequestConfig,
  ): Promise<DouyinVideoAnalysisResult | null> {
    const normalizedProxyVideoUrl = proxyVideoUrl.trim()
    const normalizedAnalysisConfig = normalizeAnalysisConfig(analysisConfig)

    if (!normalizedProxyVideoUrl) {
      analysis.value = null
      error.value = '缺少可分析的视频地址'
      return null
    }

    currentController?.abort()
    const controller = new AbortController()
    currentController = controller
    const requestId = ++requestCounter

    loading.value = true
    error.value = ''
    analysis.value = null

    try {
      const response = await fetch('/api/douyin/analyze-video', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          proxyVideoUrl: normalizedProxyVideoUrl,
          analysisConfig: normalizedAnalysisConfig,
        }),
        signal: controller.signal,
      })

      const contentType = response.headers.get('content-type') || ''
      if (!contentType.includes('application/json')) {
        const fallbackText = await response.text()
        throw new Error(fallbackText || '视频内容提取失败，请稍后重试')
      }

      const body = await response.json() as ApiResponse<unknown>
      const normalizedData = normalizeDouyinVideoAnalysisResult(body.data)

      if (!response.ok || !body.success || !normalizedData) {
        throw new Error(body.error || '视频内容提取失败，请稍后重试')
      }

      if (requestId !== requestCounter) {
        return null
      }

      analysis.value = normalizedData
      return normalizedData
    } catch (requestError: unknown) {
      if (requestId !== requestCounter) {
        return null
      }

      if (requestError instanceof DOMException && requestError.name === 'AbortError') {
        return null
      }

      error.value = requestError instanceof Error ? requestError.message : '视频内容提取失败，请稍后重试'
      return null
    } finally {
      if (requestId === requestCounter) {
        loading.value = false
      }

      if (currentController === controller) {
        currentController = null
      }
    }
  }

  function reset(): void {
    currentController?.abort()
    currentController = null
    requestCounter += 1
    analysis.value = null
    loading.value = false
    error.value = ''
  }

  return {
    analysis,
    loading,
    error,
    analyzeVideo,
    reset,
  }
}
