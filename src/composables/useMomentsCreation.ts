import { computed, ref } from 'vue'

/**
 * 朋友圈「图片+文字」创作（PRD §4.4）。
 *
 * 一次多模态 SSE 调用产出 {copy, imageOrder[], captions[]}（判别联合帧：progress/result/error），
 * 帧消费镜像 useCreationAssistant（整帧对象交给处理函数，错误帧转异常）。
 * 积分在服务端扣（moments_generation），失败退款；用户 abort 不退（内容可能已流出）。
 */

export const MOMENTS_STYLES = [
  { id: 'lifestyle', label: '生活化' },
  { id: 'event', label: '活动通知' },
  { id: 'store-visit', label: '到店体验' },
  { id: 'friends-share', label: '朋友分享' },
] as const

export type MomentsStyleId = (typeof MOMENTS_STYLES)[number]['id']

export interface MomentsImage {
  id: string
  name: string
  dataUrl: string
}

export interface MomentsOrderSuggestion {
  index: number
  reason: string
}

export interface MomentsCaption {
  index: number
  text: string
}

export interface MomentsResult {
  copy: string
  imageOrder: MomentsOrderSuggestion[]
  captions: MomentsCaption[]
}

interface MomentsFrame {
  type?: string
  message?: string
  copy?: string
  imageOrder?: MomentsOrderSuggestion[]
  captions?: MomentsCaption[]
  error?: string
}

const MAX_IMAGES = 9
const MAX_FILE_BYTES = 5 * 1024 * 1024
const ALLOWED_MIME = ['image/jpeg', 'image/png', 'image/webp']

let imageSequence = 0

function fileToDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as string)
    reader.onerror = () => reject(new Error('文件读取失败'))
    reader.readAsDataURL(file)
  })
}

export function useMomentsCreation() {
  const topic = ref('')
  const style = ref<MomentsStyleId | ''>('')
  const feelings = ref('')
  const images = ref<MomentsImage[]>([])
  const result = ref<MomentsResult | null>(null)
  const generating = ref(false)
  const progressMessage = ref('')
  const error = ref('')

  const taskMode = ref(false)
  const contextSnapshotId = ref<string | null>(null)

  let controller: AbortController | null = null

  const canGenerate = computed(() => topic.value.trim() !== '' && style.value !== '' && !generating.value)

  function bindCreationContext(enabled: boolean, snapshotId: string | null | undefined): void {
    taskMode.value = enabled
    contextSnapshotId.value = enabled ? (snapshotId ?? null) : null
  }

  async function addImages(files: File[]): Promise<void> {
    if (!files.length) return
    if (images.value.length + files.length > MAX_IMAGES) {
      error.value = '最多上传 9 张图片'
      return
    }
    for (const file of files) {
      if (!ALLOWED_MIME.includes(file.type)) {
        error.value = '仅支持 JPG、PNG、WebP 图片'
        return
      }
      if (file.size > MAX_FILE_BYTES) {
        error.value = '单张图片不能超过 5 MB'
        return
      }
    }
    try {
      const loaded: MomentsImage[] = []
      for (const file of files) {
        loaded.push({ id: `img-${Date.now()}-${imageSequence++}`, name: file.name, dataUrl: await fileToDataUrl(file) })
      }
      images.value = [...images.value, ...loaded]
      error.value = ''
    } catch {
      error.value = '图片读取失败，请重试'
    }
  }

  function removeImage(id: string): void {
    images.value = images.value.filter((image) => image.id !== id)
  }

  async function generate(): Promise<void> {
    if (generating.value) return
    if (!topic.value.trim()) {
      error.value = '请先填写主题'
      return
    }
    if (style.value === '') {
      error.value = '请先选择朋友圈风格'
      return
    }
    controller?.abort()
    controller = new AbortController()

    generating.value = true
    error.value = ''
    progressMessage.value = ''
    result.value = null

    try {
      const response = await fetch('/api/moments-generation/generate', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          topic: topic.value.trim(),
          style: style.value,
          feelings: feelings.value.trim() || undefined,
          images: images.value.map((image) => image.dataUrl),
          ...(taskMode.value && contextSnapshotId.value
            ? { taskMode: true, contextSnapshotId: contextSnapshotId.value }
            : {}),
        }),
        signal: controller.signal,
      })

      if (!response.ok) {
        let message = `请求失败（${response.status}）`
        try {
          const parsed = await response.json() as { error?: string }
          if (parsed.error) message = parsed.error
        } catch {
          // 非 JSON 错误体保留默认文案
        }
        throw new Error(message)
      }

      await consumeFrames(response)
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      error.value = err instanceof Error ? err.message : '朋友圈内容生成失败，请稍后重试'
    } finally {
      generating.value = false
      if (progressMessage.value) progressMessage.value = ''
      controller = null
    }
  }

  async function consumeFrames(response: Response): Promise<void> {
    const reader = response.body?.getReader()
    if (!reader) throw new Error('响应没有可读流')

    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() ?? ''

      for (const line of lines) {
        if (!line.startsWith('data: ')) continue
        const payload = line.slice(6).trim()
        if (payload === '[DONE]') return
        if (!payload) continue

        let frame: MomentsFrame
        try {
          frame = JSON.parse(payload) as MomentsFrame
        } catch {
          throw new Error('SSE 响应格式错误')
        }
        if (frame.type === 'error' || frame.error) {
          throw new Error(frame.error || '朋友圈内容生成失败')
        }
        if (frame.type === 'progress') {
          progressMessage.value = frame.message || '正在生成朋友圈内容…'
          continue
        }
        if (frame.type === 'result') {
          result.value = {
            copy: frame.copy || '',
            imageOrder: Array.isArray(frame.imageOrder) ? frame.imageOrder : [],
            captions: Array.isArray(frame.captions) ? frame.captions : [],
          }
        }
      }
    }
    throw new Error('SSE 响应流意外中断：未收到 [DONE]')
  }

  function cancel(): void {
    controller?.abort()
    controller = null
    generating.value = false
  }

  function reset(): void {
    controller?.abort()
    controller = null
    topic.value = ''
    style.value = ''
    feelings.value = ''
    images.value = []
    result.value = null
    generating.value = false
    progressMessage.value = ''
    error.value = ''
    taskMode.value = false
    contextSnapshotId.value = null
  }

  return {
    topic, style, feelings, images, result, generating, progressMessage, error, canGenerate,
    bindCreationContext, addImages, removeImage, generate, cancel, reset,
  }
}
