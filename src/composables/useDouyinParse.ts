import { ref } from 'vue'
import type { ApiResponse, DouyinFetchStage, ExtractedDouyinVideoPayload } from '../types/douyin'

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isDouyinFetchStage(value: unknown): value is DouyinFetchStage {
  return value === 'page_json' || value === 'browser_json' || value === 'browser_network'
}

function readOptionalDurationSeconds(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? Math.ceil(value) : undefined
}

function normalizeExtractedDouyinVideoPayload(value: unknown): ExtractedDouyinVideoPayload | null {
  if (!isPlainObject(value)) {
    return null
  }

  if (
    typeof value.sourceUrl !== 'string'
    || typeof value.proxyVideoUrl !== 'string'
    || typeof value.downloadVideoUrl !== 'string'
    || typeof value.downloadAudioUrl !== 'string'
    || typeof value.usedSession !== 'boolean'
    || !isDouyinFetchStage(value.fetchStage)
  ) {
    return null
  }

  return {
    sourceUrl: value.sourceUrl,
    platform: 'douyin',
    videoId: typeof value.videoId === 'string' ? value.videoId : undefined,
    author: typeof value.author === 'string' ? value.author : undefined,
    title: typeof value.title === 'string' ? value.title : undefined,
    coverUrl: typeof value.coverUrl === 'string' ? value.coverUrl : undefined,
    durationSeconds: readOptionalDurationSeconds(value.durationSeconds),
    proxyVideoUrl: value.proxyVideoUrl,
    downloadVideoUrl: value.downloadVideoUrl,
    downloadAudioUrl: value.downloadAudioUrl,
    usedSession: value.usedSession,
    fetchStage: value.fetchStage,
  }
}

export function useDouyinParse() {
  const extractedVideo = ref<ExtractedDouyinVideoPayload | null>(null)
  const loading = ref(false)
  const error = ref('')

  async function extractVideo(input: string): Promise<ExtractedDouyinVideoPayload | null> {
    const normalizedInput = input.trim()

    if (!normalizedInput) {
      extractedVideo.value = null
      error.value = '请输入抖音分享文本或链接'
      return null
    }

    loading.value = true
    error.value = ''
    extractedVideo.value = null

    try {
      const response = await fetch('/api/douyin/extract-video', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ input: normalizedInput }),
      })

      const contentType = response.headers.get('content-type') || ''
      if (!contentType.includes('application/json')) {
        const fallbackText = await response.text()
        throw new Error(fallbackText || '提取视频失败，请稍后重试')
      }

      const body = await response.json() as ApiResponse<unknown>
      const normalizedData = normalizeExtractedDouyinVideoPayload(body.data)

      if (!response.ok || !body.success || !normalizedData) {
        throw new Error(body.error || '提取视频失败，请稍后重试')
      }

      extractedVideo.value = normalizedData
      return normalizedData
    } catch (requestError: unknown) {
      error.value = requestError instanceof Error ? requestError.message : '提取视频失败，请稍后重试'
      return null
    } finally {
      loading.value = false
    }
  }

  function reset(): void {
    extractedVideo.value = null
    loading.value = false
    error.value = ''
  }

  return {
    extractedVideo,
    loading,
    error,
    extractVideo,
    reset,
  }
}
