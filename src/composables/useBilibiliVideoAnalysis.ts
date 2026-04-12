import { ref } from 'vue'
import type { ApiResponse, BilibiliVideoAnalysisResult } from '../types/bilibili'

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function readOptionalString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

function normalizeBilibiliVideoAnalysisResult(value: unknown): BilibiliVideoAnalysisResult | null {
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
  }
}

export function useBilibiliVideoAnalysis() {
  const analysis = ref<BilibiliVideoAnalysisResult | null>(null)
  const loading = ref(false)
  const error = ref('')

  async function analyzeVideo(proxyVideoUrl: string): Promise<BilibiliVideoAnalysisResult | null> {
    const normalizedProxyVideoUrl = proxyVideoUrl.trim()

    if (!normalizedProxyVideoUrl) {
      analysis.value = null
      error.value = '缺少可分析的视频地址'
      return null
    }

    loading.value = true
    error.value = ''
    analysis.value = null

    try {
      const response = await fetch('/api/bilibili/analyze-video', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ proxyVideoUrl: normalizedProxyVideoUrl }),
      })

      const contentType = response.headers.get('content-type') || ''
      if (!contentType.includes('application/json')) {
        const fallbackText = await response.text()
        throw new Error(fallbackText || '视频内容提取失败，请稍后重试')
      }

      const body = await response.json() as ApiResponse<unknown>
      const normalizedData = normalizeBilibiliVideoAnalysisResult(body.data)

      if (!response.ok || !body.success || !normalizedData) {
        throw new Error(body.error || '视频内容提取失败，请稍后重试')
      }

      analysis.value = normalizedData
      return normalizedData
    } catch (requestError: unknown) {
      error.value = requestError instanceof Error ? requestError.message : '视频内容提取失败，请稍后重试'
      return null
    } finally {
      loading.value = false
    }
  }

  function reset(): void {
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
