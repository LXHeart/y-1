import { ref, computed, onMounted } from 'vue'
import type {
  StoryboardShot,
  VideoCapabilities,
  VideoProductionForm,
  VideoProductionImage,
  VideoProductionStage,
} from '../types/video-production'
import {
  SHOT_COUNT_MAX,
  SHOT_SECONDS_MAX,
  SHOT_SECONDS_MIN,
  TARGET_DURATION_DEFAULT,
} from '../types/video-production'
import { compressImageToFile } from './compress-image'
import { parseSafetyFrame } from './useContentSafety'
import type { SafetyReport } from './useContentSafety'
import { fetchApi } from './grassland-http'

function generateId(): string {
  return `img-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

interface StoryboardMetaFrame {
  type: 'meta'
  storyboardId?: string
  targetDurationSeconds?: number
}

interface StoryboardShotFrame {
  type: 'shot'
  shot?: Partial<StoryboardShot>
}

type StoryboardStreamFrame = StoryboardMetaFrame | StoryboardShotFrame | Record<string, unknown>

function defaultForm(): VideoProductionForm {
  return {
    shopName: '',
    industryType: '餐饮',
    targetPlatform: '',
    shopAddress: '',
    shopDescription: '',
    videoStyle: '烟火纪实',
    customPrompt: '',
    targetDurationSeconds: TARGET_DURATION_DEFAULT,
  }
}

/**
 * 视频制作四步向导（任务书 #64 卡4 重构）：上传素材 → 编辑分镜 → 生成与挑选 → 合成成片。
 * 分镜经 POST /api/video-production/storyboard SSE（meta、逐个 shot、safety、[DONE]）逐镜接收；
 * capabilities 消费卡2 新契约（mode=video|slideshow + tts 可用性，slideshow 不锁死）。
 */
export function useVideoProduction() {
  const stage = ref<VideoProductionStage>('upload')
  const images = ref<VideoProductionImage[]>([])
  const form = ref<VideoProductionForm>(defaultForm())
  const shots = ref<StoryboardShot[]>([])
  const storyboardId = ref('')
  const safetyReport = ref<SafetyReport | null>(null)
  const storyboardLoading = ref(false)
  const error = ref('')
  const taskMode = ref(false)
  const contextSnapshotId = ref<string | null>(null)

  // capabilities 拉取失败按 slideshow 降级展示（P6：图文成片不锁死，误显降级提示无害）
  const capabilities = ref<VideoCapabilities | null>(null)
  const isSlideshowMode = computed(() =>
    capabilities.value === null || capabilities.value.mode === 'slideshow')
  const ttsUnavailable = computed(() =>
    capabilities.value !== null && !capabilities.value.tts.available)

  let storyboardController: AbortController | null = null

  const MAX_IMAGES = 9
  const MAX_IMAGE_SIZE = 1024 * 1024 // 1MB per image after compression

  const canProceedToStoryboard = computed(() => {
    return images.value.length >= 1
      && form.value.shopName.trim().length > 0
      && form.value.targetPlatform.length > 0
  })

  const canAddShot = computed(() => shots.value.length < SHOT_COUNT_MAX)
  const totalPlannedSeconds = computed(() =>
    shots.value.reduce((sum, shot) => sum + shot.plannedSeconds, 0))
  /** 安全面板定位用：全部旁白拼接（镜头正文是旁白，prompt 不进用户可见正文）。 */
  const narrationText = computed(() =>
    shots.value.map((shot) => shot.narration).filter(Boolean).join('\n'))

  function executionContext() {
    return taskMode.value
      ? { taskMode: true, contextSnapshotId: contextSnapshotId.value }
      : {}
  }

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

  async function generateStoryboard(): Promise<void> {
    if (!canProceedToStoryboard.value) {
      error.value = '请至少上传 1 张图片并填写店铺名称'
      return
    }

    storyboardController?.abort()
    const controller = new AbortController()
    storyboardController = controller

    storyboardLoading.value = true
    error.value = ''
    shots.value = []
    storyboardId.value = ''
    safetyReport.value = null
    stage.value = 'storyboard'

    try {
      const imageBase64List = images.value.map((img) => {
        const base64 = img.dataUrl.split(',')[1]
        return base64 || img.dataUrl
      })

      const response = await fetchApi('/api/video-production/storyboard', {
        method: 'POST',
        body: JSON.stringify({
          images: imageBase64List,
          shopName: form.value.shopName.trim(),
          industryType: form.value.industryType,
          targetPlatform: form.value.targetPlatform,
          shopAddress: form.value.shopAddress.trim() || undefined,
          shopDescription: form.value.shopDescription.trim() || undefined,
          videoStyle: form.value.videoStyle,
          customPrompt: form.value.customPrompt.trim() || undefined,
          targetDurationSeconds: form.value.targetDurationSeconds,
          ...executionContext(),
        }),
        signal: controller.signal,
      })

      if (!response.ok) {
        const body = await response.json() as { error?: string }
        throw new Error(body.error || '分镜生成失败')
      }

      await consumeStoryboardStream(response, (frame) => {
        if (frame.type === 'meta') {
          storyboardId.value = (frame as StoryboardMetaFrame).storyboardId || ''
        } else if (frame.type === 'shot' && (frame as StoryboardShotFrame).shot) {
          const incoming = normalizeShot((frame as StoryboardShotFrame).shot!, shots.value.length + 1)
          shots.value = [...shots.value, incoming]
        }
      }, (report) => {
        safetyReport.value = report
      }, controller.signal)
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      error.value = err instanceof Error ? err.message : '分镜生成失败，请稍后重试'
    } finally {
      storyboardLoading.value = false
      if (storyboardController === controller) storyboardController = null
    }
  }

  function updateShot(index: number, patch: Partial<StoryboardShot>): void {
    const current = shots.value[index]
    if (!current) return
    shots.value = shots.value.map((shot, position) => position === index
      ? normalizeShot({ ...shot, ...patch }, shot.seq)
      : shot)
  }

  function removeShot(index: number): void {
    shots.value = shots.value
      .filter((_, position) => position !== index)
      .map((shot, position) => ({ ...shot, seq: position + 1 }))
  }

  function addShot(): void {
    if (!canAddShot.value) return
    shots.value = [...shots.value, {
      seq: shots.value.length + 1,
      visual: '',
      narration: '',
      plannedSeconds: 5,
      cameraMove: '固定机位',
      anchorImageIndex: 0,
      prompt: '',
    }]
  }

  function goBackToUpload(): void {
    storyboardController?.abort()
    shots.value = []
    storyboardId.value = ''
    safetyReport.value = null
    storyboardLoading.value = false
    error.value = ''
    stage.value = 'upload'
  }

  /** 进入生成与挑选步（任务创建在卡6 接线）。 */
  function beginGeneration(): void {
    if (storyboardLoading.value || shots.value.length === 0) return
    error.value = ''
    stage.value = 'generate'
  }

  function goBackToStoryboard(): void {
    error.value = ''
    stage.value = 'storyboard'
  }

  function reset(): void {
    storyboardController?.abort()
    storyboardController = null

    stage.value = 'upload'
    images.value = []
    form.value = defaultForm()
    shots.value = []
    storyboardId.value = ''
    safetyReport.value = null
    storyboardLoading.value = false
    error.value = ''
  }

  function bindCreationContext(isTaskMode: boolean, snapshotId?: string): void {
    taskMode.value = isTaskMode
    contextSnapshotId.value = snapshotId || null
  }

  async function loadCapabilities(): Promise<void> {
    try {
      const response = await fetchApi('/api/video-production/capabilities')
      if (!response.ok) return

      const body = await response.json() as VideoCapabilities
      if (!body || typeof body.mode !== 'string' || !body.video || !body.tts) return
      capabilities.value = {
        mode: body.mode,
        video: {
          available: body.video.available === true,
          provider: body.video.provider ?? null,
          model: body.video.model ?? null,
          unitPriceCents: body.video.unitPriceCents ?? null,
          reason: body.video.reason || '',
        },
        tts: {
          available: body.tts.available === true,
          model: body.tts.model ?? null,
          reason: body.tts.reason || '',
        },
      }
    } catch {
      // fail-closed：capabilities 保持 null（isSlideshowMode 按降级展示）
    }
  }

  onMounted(loadCapabilities)

  /** SSE 帧载荷校验 + 钳制：时长 4-6、锚定图 [0, 图片数]。 */
  function normalizeShot(raw: Partial<StoryboardShot>, seq: number): StoryboardShot {
    const planned = Number(raw.plannedSeconds)
    const anchor = Number(raw.anchorImageIndex)
    return {
      seq,
      visual: String(raw.visual ?? ''),
      narration: String(raw.narration ?? ''),
      plannedSeconds: Number.isFinite(planned)
        ? Math.min(SHOT_SECONDS_MAX, Math.max(SHOT_SECONDS_MIN, Math.round(planned)))
        : 5,
      cameraMove: String(raw.cameraMove ?? '固定机位'),
      anchorImageIndex: Number.isFinite(anchor)
        ? Math.min(images.value.length, Math.max(0, Math.round(anchor)))
        : 0,
      prompt: String(raw.prompt ?? ''),
    }
  }

  async function consumeStoryboardStream(
    response: Response,
    onFrame: (frame: StoryboardStreamFrame & { type?: string }) => void,
    onSafety?: (report: SafetyReport) => void,
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
          const parsed = JSON.parse(payload) as Record<string, unknown> & {
            type?: string; error?: string; message?: string
          }
          if (parsed.error) throw new Error(String(parsed.error))
          const report = parseSafetyFrame(parsed)
          if (report) {
            onSafety?.(report)
            continue
          }
          if (parsed.type === 'error' && typeof parsed.message === 'string') {
            throw new Error(parsed.message)
          }
          onFrame(parsed as StoryboardStreamFrame)
        } catch (err: unknown) {
          if (err instanceof Error && err.message !== 'Unexpected end of JSON input') {
            throw err
          }
        }
      }
    }
  }

  return {
    stage, images, form, shots, storyboardId, safetyReport,
    storyboardLoading, error,
    canProceedToStoryboard, canAddShot, totalPlannedSeconds, narrationText,
    capabilities, isSlideshowMode, ttsUnavailable,
    addImages, removeImage, reorderImage,
    generateStoryboard, updateShot, removeShot, addShot,
    goBackToUpload, beginGeneration, goBackToStoryboard,
    reset, bindCreationContext, loadCapabilities,
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
