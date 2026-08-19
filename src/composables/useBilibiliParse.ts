import { ref } from 'vue'
import { request } from './grassland-http'
import type { ExtractedBilibiliVideoPayload } from '../types/bilibili'

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function readOptionalDurationSeconds(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? Math.ceil(value) : undefined
}

function normalizeExtractedBilibiliVideoPayload(value: unknown): ExtractedBilibiliVideoPayload | null {
  if (!isPlainObject(value)) {
    return null
  }

  if (
    typeof value.sourceUrl !== 'string'
    || typeof value.proxyVideoUrl !== 'string'
    || typeof value.downloadVideoUrl !== 'string'
    || (value.playbackMode !== 'progressive' && value.playbackMode !== 'dash')
  ) {
    return null
  }

  return {
    sourceUrl: value.sourceUrl,
    platform: 'bilibili',
    videoId: typeof value.videoId === 'string' ? value.videoId : undefined,
    author: typeof value.author === 'string' ? value.author : undefined,
    title: typeof value.title === 'string' ? value.title : undefined,
    coverUrl: typeof value.coverUrl === 'string' ? value.coverUrl : undefined,
    durationSeconds: readOptionalDurationSeconds(value.durationSeconds),
    proxyVideoUrl: value.proxyVideoUrl,
    downloadVideoUrl: value.downloadVideoUrl,
    playbackMode: value.playbackMode,
  }
}

export function useBilibiliParse() {
  const extractedVideo = ref<ExtractedBilibiliVideoPayload | null>(null)
  const loading = ref(false)
  const error = ref('')

  async function extractVideo(input: string): Promise<ExtractedBilibiliVideoPayload | null> {
    const normalizedInput = input.trim()

    if (!normalizedInput) {
      extractedVideo.value = null
      error.value = '请输入 B 站分享文本或链接'
      return null
    }

    loading.value = true
    error.value = ''
    extractedVideo.value = null

    try {
      const data = await request<unknown>('/api/bilibili/extract-video', {
        method: 'POST',
        body: JSON.stringify({ input: normalizedInput }),
      }, { fallbackError: '提取视频失败，请稍后重试' })

      const normalizedData = normalizeExtractedBilibiliVideoPayload(data)

      if (!normalizedData) {
        throw new Error('提取视频失败，请稍后重试')
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
