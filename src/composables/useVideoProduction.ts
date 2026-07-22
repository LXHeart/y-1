import { ref, computed } from 'vue'
import type { VideoProductionStage, VideoProductionImage, VideoProductionForm } from '../types/video-production'
import { compressImageToFile } from './compress-image'

function generateId(): string {
  return `img-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

export function useVideoProduction() {
  const stage = ref<VideoProductionStage>('upload')
  const images = ref<VideoProductionImage[]>([])
  const form = ref<VideoProductionForm>({
    shopName: '',
    industryType: '餐饮',
    shopAddress: '',
    shopDescription: '',
    videoStyle: '烟火纪实',
    customPrompt: '',
  })
  const script = ref('')
  const scriptLoading = ref(false)
  const videoUrl = ref('')
  const videoLoading = ref(false)
  const videoProgress = ref(0)
  const error = ref('')

  let scriptController: AbortController | null = null
  let videoController: AbortController | null = null

  const MAX_IMAGES = 9
  const MAX_IMAGE_SIZE = 1024 * 1024 // 1MB per image after compression

  const canProceedToScript = computed(() => {
    return images.value.length >= 1
      && form.value.shopName.trim().length > 0
  })

  async function addImages(files: File[]): Promise<void> {
    const remaining = MAX_IMAGES - images.value.length
    if (remaining <= 0) return

    const toProcess = files.slice(0, remaining)

    for (const file of toProcess) {
      try {
        const compressed = await compressImageToFile(file, MAX_IMAGE_SIZE)
        const dataUrl = await fileToDataUrl(compressed)
        images.value = [...images.value, {
          id: generateId(),
          dataUrl,
          name: file.name,
        }]
      } catch {
        // skip files that fail to compress
      }
    }
  }

  function removeImage(id: string): void {
    images.value = images.value.filter((img) => img.id !== id)
  }

  function reorderImage(fromIndex: number, toIndex: number): void {
    const updated = [...images.value]
    const [moved] = updated.splice(fromIndex, 1)
    updated.splice(toIndex, 0, moved)
    images.value = updated
  }

  async function generateScript(): Promise<void> {
    if (!canProceedToScript.value) {
      error.value = '请至少上传 1 张图片并填写店铺名称'
      return
    }

    scriptController?.abort()
    const controller = new AbortController()
    scriptController = controller

    scriptLoading.value = true
    error.value = ''
    script.value = ''
    stage.value = 'script'

    try {
      const imageBase64List = images.value.map((img) => {
        const base64 = img.dataUrl.split(',')[1]
        return base64 || img.dataUrl
      })

      const response = await fetch('/api/video-production/generate-script', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          images: imageBase64List,
          shopName: form.value.shopName.trim(),
          industryType: form.value.industryType,
          shopAddress: form.value.shopAddress.trim() || undefined,
          shopDescription: form.value.shopDescription.trim() || undefined,
          videoStyle: form.value.videoStyle,
          customPrompt: form.value.customPrompt.trim() || undefined,
        }),
        signal: controller.signal,
      })

      if (!response.ok) {
        const body = await response.json() as { error?: string }
        throw new Error(body.error || '脚本生成失败')
      }

      await consumeSSEStream(response, (chunk) => {
        script.value += chunk
      }, controller.signal)
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      error.value = err instanceof Error ? err.message : '脚本生成失败，请稍后重试'
    } finally {
      scriptLoading.value = false
      if (scriptController === controller) scriptController = null
    }
  }

  async function startVideoGeneration(): Promise<void> {
    if (!script.value.trim()) {
      error.value = '脚本内容不能为空'
      return
    }

    videoController?.abort()
    const controller = new AbortController()
    videoController = controller

    videoLoading.value = true
    error.value = ''
    videoProgress.value = 0
    stage.value = 'generate'

    const progressInterval = simulateProgress()

    try {
      const imageBase64List = images.value.map((img) => {
        const base64 = img.dataUrl.split(',')[1]
        return base64 || img.dataUrl
      })

      const response = await fetch('/api/video-production/generate-video', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          script: script.value.trim(),
          images: imageBase64List,
          videoStyle: form.value.videoStyle,
          shopName: form.value.shopName.trim(),
          shopAddress: form.value.shopAddress.trim() || undefined,
        }),
        signal: controller.signal,
      })

      const body = await response.json() as { success?: boolean; data?: { videoUrl: string }; error?: string }

      if (!response.ok || !body.success) {
        throw new Error(body.error || '视频生成失败')
      }

      videoUrl.value = body.data?.videoUrl ?? ''
      videoProgress.value = 100
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      error.value = err instanceof Error ? err.message : '视频生成失败，请稍后重试'
    } finally {
      clearInterval(progressInterval)
      videoLoading.value = false
      if (videoController === controller) videoController = null
    }
  }

  function goBackToUpload(): void {
    scriptController?.abort()
    script.value = ''
    scriptLoading.value = false
    error.value = ''
    stage.value = 'upload'
  }

  function goBackToScript(): void {
    videoController?.abort()
    videoUrl.value = ''
    videoLoading.value = false
    videoProgress.value = 0
    error.value = ''
    stage.value = 'script'
  }

  function reset(): void {
    scriptController?.abort()
    videoController?.abort()
    scriptController = null
    videoController = null

    stage.value = 'upload'
    images.value = []
    form.value = {
      shopName: '',
      industryType: '餐饮',
      shopAddress: '',
      shopDescription: '',
      videoStyle: '烟火纪实',
      customPrompt: '',
    }
    script.value = ''
    scriptLoading.value = false
    videoUrl.value = ''
    videoLoading.value = false
    videoProgress.value = 0
    error.value = ''
  }

  async function consumeSSEStream(
    response: Response,
    onChunk: (text: string) => void,
    signal?: AbortSignal,
  ): Promise<void> {
    const reader = response.body?.getReader()
    if (!reader) throw new Error('No response body')

    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      if (signal?.aborted) {
        reader.cancel()
        break
      }

      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() ?? ''

      for (const line of lines) {
        if (!line.startsWith('data: ')) continue
        const payload = line.slice(6).trim()
        if (payload === '[DONE]') return

        try {
          const parsed = JSON.parse(payload) as { content?: string; error?: string }
          if (parsed.error) throw new Error(parsed.error)
          if (parsed.content) onChunk(parsed.content)
        } catch (err: unknown) {
          if (err instanceof Error && err.message !== 'Unexpected end of JSON input') {
            throw err
          }
        }
      }
    }
  }

  function simulateProgress(): ReturnType<typeof setInterval> {
    let progress = 0
    return setInterval(() => {
      progress = Math.min(progress + Math.random() * 8, 90)
      videoProgress.value = Math.round(progress)
    }, 500)
  }

  return {
    stage, images, form, script, videoUrl,
    scriptLoading, videoLoading, videoProgress, error,
    canProceedToScript,
    addImages, removeImage, reorderImage,
    generateScript, startVideoGeneration,
    goBackToUpload, goBackToScript,
    reset,
  }
}

function fileToDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as string)
    reader.onerror = () => reject(new Error('文件读取失败'))
    reader.readAsDataURL(file)
  })
}
