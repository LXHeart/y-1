/**
 * AI 工作室 composable（任务书 #43）。
 *
 * 图片编辑台 + 视频工坊共用 API 入口：抠图、BGM 建议、转写列表。
 * 图片上传复用草场三步上传（purpose=user_upload），抠图走 /api/image-studio/matting。
 */
import { ref } from 'vue'
import { request, putToPresignedUrl } from './grassland-http'
import type {
  MattingResult,
  BgmAdviceInput,
  BgmAdviceResult,
  SpeechTranscriptionItem,
} from '../types/grassland/ai-studio'
import type { MediaUploadTicket, MediaMetadata } from '../types/grassland'

// 与后端 /api/image-studio/matting 的图片白名单一致（抠图只认这三种，gif 会在抠图时 404）
const IMAGE_MIME_WHITELIST = ['image/jpeg', 'image/png', 'image/webp']

export function useAiStudio() {
  const loading = ref(false)
  const error = ref('')

  function clearError() { error.value = '' }

  /** 上传图片素材（三步），返回 mediaId。purpose=user_upload，MIME 白名单。 */
  async function uploadImageFile(file: File): Promise<string | null> {
    if (!IMAGE_MIME_WHITELIST.includes(file.type)) {
      error.value = '仅支持 JPG / PNG / WebP 格式'
      return null
    }
    loading.value = true
    error.value = ''
    try {
      const ticket = await request<MediaUploadTicket>('/api/media/upload-tickets', {
        method: 'POST',
        body: JSON.stringify({
          contentType: file.type,
          purpose: 'user_upload',
          sizeBytes: file.size,
        }),
      })
      await putToPresignedUrl(ticket, file)
      const confirmed = await request<MediaMetadata>(
        `/api/media/${ticket.id}/confirm`, { method: 'POST' })
      return confirmed.id
    } catch (caught: unknown) {
      error.value = caught instanceof Error ? caught.message : '上传失败'
      return null
    } finally {
      loading.value = false
    }
  }

  /** AI 抠图：传 mediaId → 返回带 alpha 的 PNG URL（30min TTL）。 */
  async function mattingImage(mediaId: string): Promise<MattingResult | null> {
    loading.value = true
    error.value = ''
    try {
      return await request<MattingResult>('/api/image-studio/matting', {
        method: 'POST',
        body: JSON.stringify({ mediaId }),
      })
    } catch (caught: unknown) {
      error.value = caught instanceof Error ? caught.message : '抠图失败'
      return null
    } finally {
      loading.value = false
    }
  }

  /** 拉取抠图结果 PNG（GET 端点，30min TTL 内有效）。 */
  async function getMattingResult(imageUrl: string): Promise<Blob | null> {
    try {
      const response = await fetch(imageUrl, { credentials: 'include' })
      if (!response.ok) return null
      return await response.blob()
    } catch {
      return null
    }
  }

  /** BGM 情绪节奏建议（扣 1 积分）。 */
  async function bgmAdvice(input: BgmAdviceInput): Promise<BgmAdviceResult | null> {
    loading.value = true
    error.value = ''
    try {
      return await request<BgmAdviceResult>('/api/video-studio/bgm-advice', {
        method: 'POST',
        body: JSON.stringify(input),
      })
    } catch (caught: unknown) {
      error.value = caught instanceof Error ? caught.message : 'BGM 建议获取失败'
      return null
    } finally {
      loading.value = false
    }
  }

  /** 最近转写列表（owner 最近 20 条）。 */
  async function listSpeechTranscriptions(): Promise<SpeechTranscriptionItem[]> {
    loading.value = true
    error.value = ''
    try {
      const result = await request<{ items: SpeechTranscriptionItem[] }>('/api/speech/transcriptions')
      return result.items
    } catch (caught: unknown) {
      error.value = caught instanceof Error ? caught.message : '获取转写列表失败'
      return []
    } finally {
      loading.value = false
    }
  }

  return {
    loading,
    error,
    clearError,
    uploadImageFile,
    mattingImage,
    getMattingResult,
    bgmAdvice,
    listSpeechTranscriptions,
  }
}
