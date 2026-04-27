import { computed, getCurrentInstance, onBeforeUnmount, ref } from 'vue'
import type {
  FeishuExportResponse,
  GenerationStage,
  ImageAnalysisApiResponse,
  ImageAnalysisProgressEvent,
  ImageAnalysisResult,
  ImageAnalysisStreamEvent,
  ImageAnalysisStreamProgressEvent,
  ReviewPlatform,
  SaveStyleMemoryResponse,
  StepReviewRequest,
  UpdateStylePreferencesResponse,
} from '../types/image-analysis'
import { compressImageToFile } from './compress-image'

const MAX_IMAGES = 6
const MAX_FILE_SIZE = 5 * 1024 * 1024
const MAX_STYLE_PREFERENCES = 100
const DEFAULT_REVIEW_LENGTH = 0
const MIN_REVIEW_LENGTH = 15
const MAX_REVIEW_LENGTH = 300
const ALLOWED_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp'])
const PREFERENCE_PAGE_SIZE = 5

function getProgressEventKey(event: ImageAnalysisStreamProgressEvent): string {
  return `${event.stage}:${event.attempt ?? 0}`
}

function mergeProgressEvents(
  currentEvents: ImageAnalysisProgressEvent[],
  incomingEvent: ImageAnalysisStreamProgressEvent,
): ImageAnalysisProgressEvent[] {
  const eventKey = getProgressEventKey(incomingEvent)
  const existingIndex = currentEvents.findIndex((event) => {
    return getProgressEventKey(event as ImageAnalysisStreamProgressEvent) === eventKey
  })

  if (existingIndex === -1) {
    return [...currentEvents, incomingEvent]
  }

  return currentEvents.map((event, index) => {
    if (index !== existingIndex) return event
    return { ...event, ...incomingEvent }
  })
}

export interface SelectedImage {
  file: File
  preview: string
}

function readApiError(response: Response, fallback: string): Promise<string> {
  const contentType = response.headers.get('content-type') || ''
  if (contentType.includes('application/json')) {
    return response.json().then((body: ImageAnalysisApiResponse) => body.error || fallback)
  }
  return response.text().then((text) => text.trim() || fallback)
}

export async function consumeImageAnalysisStream(
  response: Response,
  onEvent: (event: ImageAnalysisStreamEvent) => void,
  signal?: AbortSignal,
): Promise<'completed' | 'aborted'> {
  const reader = response.body?.getReader()
  if (!reader) throw new Error('No response body')

  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    if (signal?.aborted) {
      await reader.cancel()
      return 'aborted'
    }

    const { done, value } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() ?? ''

    for (const line of lines) {
      if (!line.startsWith('data: ')) continue
      const payload = line.slice(6).trim()
      if (payload === '[DONE]') return 'completed'

      try {
        const parsed = JSON.parse(payload) as ImageAnalysisStreamEvent
        onEvent(parsed)
      } catch (err: unknown) {
        if (err instanceof Error && err.message !== 'Unexpected end of JSON input') {
          throw err
        }
      }
    }
  }

  return signal?.aborted ? 'aborted' : 'completed'
}

export function useImageAnalysis() {
  const images = ref<SelectedImage[]>([])
  const result = ref<ImageAnalysisResult | null>(null)
  const reviewLength = ref(DEFAULT_REVIEW_LENGTH)
  const feelings = ref('')
  const platform = ref<ReviewPlatform>('taobao')
  const generationStage = ref<GenerationStage>('idle')
  const error = ref('')
  const progressEvents = ref<ImageAnalysisProgressEvent[]>([])
  const currentProgress = ref<ImageAnalysisProgressEvent | null>(null)
  const exporting = ref(false)
  const exportError = ref('')
  const exportedDocUrl = ref('')
  const exportedDocTitle = ref('')

  const isEditing = ref(false)
  const editTitle = ref('')
  const editReview = ref('')
  const editTags = ref<string[]>([])
  const originalSnapshot = ref<ImageAnalysisResult | null>(null)
  const savingStyle = ref(false)
  const saveStyleError = ref('')
  const saveStyleSuccess = ref(false)
  const stylePreferences = ref<string[]>([])
  const loadingPreferences = ref(false)
  const showStylePreferences = ref(false)
  const oversizedFiles = ref<File[]>([])
  const showOversizedDialog = ref(false)
  const compressing = ref(false)

  const stepResults = ref<Record<string, ImageAnalysisResult>>({})
  const selectedStepResult = ref<{ stage: string; result: ImageAnalysisResult } | null>(null)

  const preferencePage = ref(1)
  const editingPreferenceIndex = ref<number | null>(null)
  const editingPreferenceValue = ref('')
  const savingPreference = ref(false)

  const optimizingPreferences = ref(false)
  const optimizedPreferences = ref<string[] | null>(null)
  const optimizeError = ref('')

  const loading = computed(() =>
    ['drafting', 'optimizing', 'style-refining'].includes(generationStage.value),
  )

  const hasStylePreferences = computed(() => stylePreferences.value.length > 0)
  const totalPreferencePages = computed(() => Math.max(1, Math.ceil(stylePreferences.value.length / PREFERENCE_PAGE_SIZE)))
  const paginatedPreferences = computed(() => {
    const start = (preferencePage.value - 1) * PREFERENCE_PAGE_SIZE
    return stylePreferences.value.slice(start, start + PREFERENCE_PAGE_SIZE)
  })
  const paginatedStartIndex = computed(() => (preferencePage.value - 1) * PREFERENCE_PAGE_SIZE)

  let currentController: AbortController | null = null

  function revokeAllPreviews(): void {
    for (const image of images.value) {
      URL.revokeObjectURL(image.preview)
    }
  }

  function addFiles(files: File[]): string | null {
    const remaining = MAX_IMAGES - images.value.length
    if (remaining <= 0) return '最多上传 6 张图片'

    const toAdd: SelectedImage[] = []
    const oversized: File[] = []
    for (const file of files.slice(0, remaining)) {
      if (!ALLOWED_TYPES.has(file.type)) return `不支持的图片类型：${file.name}`
      if (file.size > MAX_FILE_SIZE) oversized.push(file)
      else toAdd.push({ file, preview: URL.createObjectURL(file) })
    }

    if (toAdd.length > 0) images.value = [...images.value, ...toAdd]
    if (oversized.length > 0) {
      oversizedFiles.value = oversized
      showOversizedDialog.value = true
    }
    return null
  }

  function removeImage(index: number): void {
    const removed = images.value[index]
    if (removed) URL.revokeObjectURL(removed.preview)
    images.value = images.value.filter((_, i) => i !== index)
  }

  function cancel(): void {
    currentController?.abort()
    currentController = null
    generationStage.value = 'idle'
    error.value = ''
    progressEvents.value = []
    currentProgress.value = null
  }

  function reset(): void {
    cancel()
    revokeAllPreviews()
    images.value = []
    result.value = null
    reviewLength.value = DEFAULT_REVIEW_LENGTH
    feelings.value = ''
    platform.value = 'taobao'
    error.value = ''
    progressEvents.value = []
    currentProgress.value = null
    exportError.value = ''
    exportedDocUrl.value = ''
    exportedDocTitle.value = ''
    isEditing.value = false
    originalSnapshot.value = null
    saveStyleError.value = ''
    saveStyleSuccess.value = false
    oversizedFiles.value = []
    showOversizedDialog.value = false
    compressing.value = false
    stepResults.value = {}
    selectedStepResult.value = null
  }

  async function compressOversizedImages(): Promise<void> {
    if (oversizedFiles.value.length === 0) return
    compressing.value = true
    try {
      const compressed: SelectedImage[] = []
      for (const file of oversizedFiles.value) {
        const compressedFile = await compressImageToFile(file, MAX_FILE_SIZE)
        compressed.push({ file: compressedFile, preview: URL.createObjectURL(compressedFile) })
      }
      images.value = [...images.value, ...compressed]
    } finally {
      oversizedFiles.value = []
      showOversizedDialog.value = false
      compressing.value = false
    }
  }

  function removeOversizedImages(): void {
    oversizedFiles.value = []
    showOversizedDialog.value = false
  }

  function cancelOversizedImages(): void {
    oversizedFiles.value = []
    showOversizedDialog.value = false
  }

  function selectStepResult(stage: string): void {
    const result = stepResults.value[stage]
    if (result) {
      selectedStepResult.value = { stage, result: { ...result } }
    }
  }

  function clearStepResult(): void {
    selectedStepResult.value = null
  }

  function isCurrentRequest(controller: AbortController): boolean {
    return currentController === controller
  }

  function clearStepEditingState(): void {
    isEditing.value = false
    originalSnapshot.value = null
    saveStyleError.value = ''
    saveStyleSuccess.value = false
    editTitle.value = ''
    editReview.value = ''
    editTags.value = []
  }

  // --- Step-by-step generation ---

  async function startGeneration(): Promise<ImageAnalysisResult | null> {
    if (images.value.length === 0) {
      error.value = '请至少选择 1 张图片'
      return null
    }
    const length = reviewLength.value
    if (length !== 0 && (!Number.isInteger(length) || length < MIN_REVIEW_LENGTH || length > MAX_REVIEW_LENGTH)) {
      error.value = `评价字数请填写 ${MIN_REVIEW_LENGTH}-${MAX_REVIEW_LENGTH} 之间的整数，或填 0 不限制`
      return null
    }

    currentController?.abort()
    const controller = new AbortController()
    currentController = controller

    generationStage.value = 'drafting'
    error.value = ''
    result.value = null
    progressEvents.value = []
    currentProgress.value = null
    exportError.value = ''
    exportedDocUrl.value = ''
    exportedDocTitle.value = ''
    clearStepEditingState()
    stepResults.value = {}
    selectedStepResult.value = null

    const formData = new FormData()
    for (const image of images.value) formData.append('images', image.file)
    formData.append('reviewLength', String(reviewLength.value))
    formData.append('feelings', feelings.value)
    formData.append('platform', platform.value)

    try {
      const response = await fetch('/api/image-analysis/step/draft', {
        method: 'POST',
        body: formData,
        signal: controller.signal,
      })

      if (!isCurrentRequest(controller) || controller.signal.aborted) return null
      if (!response.ok) throw new Error(await readApiError(response, '初稿生成失败，请稍后重试'))

      const contentType = response.headers.get('content-type') || ''
      if (!contentType.includes('text/event-stream')) {
        throw new Error(await response.text() || '初稿生成失败，请稍后重试')
      }

      let finalResult: ImageAnalysisResult | null = null
      const streamState = await consumeImageAnalysisStream(response, (event) => {
        if (!isCurrentRequest(controller) || controller.signal.aborted) return
        if (event.type === 'progress') {
          currentProgress.value = event
          progressEvents.value = mergeProgressEvents(progressEvents.value, event)
          return
        }
        if (event.type === 'result') {
          finalResult = event.data
          result.value = event.data
          return
        }
        throw new Error(event.error || '初稿生成失败，请稍后重试')
      }, controller.signal)

      if (!isCurrentRequest(controller) || streamState === 'aborted' || controller.signal.aborted) return null
      if (!finalResult) throw new Error('初稿生成失败，请稍后重试')

      stepResults.value = { draft: { ...finalResult as ImageAnalysisResult } }
      generationStage.value = 'draft-review'
      return finalResult
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === 'AbortError') return null
      if (!isCurrentRequest(controller)) return null
      error.value = err instanceof Error ? err.message : '初稿生成失败，请稍后重试'
      generationStage.value = 'idle'
      return null
    } finally {
      if (currentController === controller) currentController = null
    }
  }

  function buildStepRequestBody(currentResult: ImageAnalysisResult): StepReviewRequest {
    return {
      review: currentResult.review,
      title: currentResult.title,
      tags: currentResult.tags,
      reviewLength: reviewLength.value,
      feelings: feelings.value || undefined,
      platform: platform.value,
    }
  }

  async function proceedToOptimize(): Promise<ImageAnalysisResult | null> {
    if (!result.value) return null
    generationStage.value = 'optimizing'
    error.value = ''
    clearStepEditingState()

    const startedAt = new Date().toISOString()

    try {
      const body = buildStepRequestBody(result.value)
      const response = await fetch('/api/image-analysis/step/optimize', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })

      const data = await response.json() as { success: boolean; data?: ImageAnalysisResult; error?: string }
      if (!response.ok || !data.success) {
        throw new Error(data.error || '润色优化失败，请稍后重试')
      }

      const completedAt = new Date().toISOString()
      progressEvents.value = mergeProgressEvents(progressEvents.value, {
        type: 'progress',
        stage: 'optimize',
        message: '润色优化完成',
        startedAt,
        completedAt,
        durationMs: Date.parse(completedAt) - Date.parse(startedAt),
      })

      result.value = data.data ?? null
      if (data.data) {
        stepResults.value = { ...stepResults.value, optimize: { ...data.data } }
      }
      generationStage.value = 'optimize-review'
      return data.data ?? null
    } catch (err: unknown) {
      error.value = err instanceof Error ? err.message : '润色优化失败，请稍后重试'
      generationStage.value = 'optimize-review'
      return null
    }
  }

  async function proceedToStyleRefine(): Promise<ImageAnalysisResult | null> {
    if (!result.value) return null
    generationStage.value = 'style-refining'
    error.value = ''
    clearStepEditingState()

    const startedAt = new Date().toISOString()

    try {
      const body = buildStepRequestBody(result.value)
      const response = await fetch('/api/image-analysis/step/style-refine', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })

      const data = await response.json() as { success: boolean; data?: ImageAnalysisResult; error?: string }
      if (!response.ok || !data.success) {
        throw new Error(data.error || '风格偏好优化失败，请稍后重试')
      }

      const completedAt = new Date().toISOString()
      progressEvents.value = mergeProgressEvents(progressEvents.value, {
        type: 'progress',
        stage: 'style-refine',
        message: '风格偏好优化完成',
        startedAt,
        completedAt,
        durationMs: Date.parse(completedAt) - Date.parse(startedAt),
      })

      result.value = data.data ?? null
      if (data.data) {
        stepResults.value = { ...stepResults.value, 'style-refine': { ...data.data } }
      }
      generationStage.value = 'complete'
      return data.data ?? null
    } catch (err: unknown) {
      error.value = err instanceof Error ? err.message : '风格偏好优化失败，请稍后重试'
      generationStage.value = 'complete'
      return null
    }
  }

  function completeWithoutStyleRefine(): void {
    progressEvents.value = mergeProgressEvents(progressEvents.value, {
      type: 'progress',
      stage: 'style-refine',
      message: '风格偏好优化（已跳过）',
    })
    generationStage.value = 'complete'
    clearStepEditingState()
  }

  // --- Editing (used between steps and after completion) ---

  function startEditing(): void {
    if (!result.value) return
    originalSnapshot.value = { ...result.value }
    editTitle.value = result.value.title || ''
    editReview.value = result.value.review
    editTags.value = result.value.tags ? [...result.value.tags] : []
    saveStyleError.value = ''
    saveStyleSuccess.value = false
    isEditing.value = true
  }

  function cancelEditing(): void {
    isEditing.value = false
    saveStyleError.value = ''
    saveStyleSuccess.value = false
  }

  function applyEditsLocally(): void {
    if (!result.value) return
    result.value = {
      ...result.value,
      review: editReview.value,
      title: editTitle.value || undefined,
      tags: editTags.value.length > 0 ? [...editTags.value] : undefined,
    }
    isEditing.value = false
    saveStyleError.value = ''
    saveStyleSuccess.value = false
  }

  async function saveStyleMemory(): Promise<boolean> {
    if (!result.value || !originalSnapshot.value) return false

    savingStyle.value = true
    saveStyleError.value = ''
    saveStyleSuccess.value = false

    try {
      const response = await fetch('/api/image-analysis/save-style-memory', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          original: {
            review: originalSnapshot.value.review,
            title: originalSnapshot.value.title,
            tags: originalSnapshot.value.tags,
          },
          edited: {
            review: editReview.value,
            title: editTitle.value || undefined,
            tags: editTags.value.length > 0 ? editTags.value : undefined,
          },
        }),
      })

      const body = await response.json() as SaveStyleMemoryResponse
      if (!response.ok || !body.success) {
        throw new Error(body.error || `保存风格偏好失败（${response.status}）`)
      }

      result.value = {
        ...result.value,
        review: editReview.value,
        title: editTitle.value || undefined,
        tags: editTags.value.length > 0 ? [...editTags.value] : undefined,
      }
      isEditing.value = false
      saveStyleSuccess.value = true
      return true
    } catch (err: unknown) {
      saveStyleError.value = err instanceof Error ? err.message : '保存风格偏好失败'
      return false
    } finally {
      savingStyle.value = false
    }
  }

  // --- Export ---

  async function exportToFeishu(): Promise<boolean> {
    if (!result.value) {
      exportError.value = '请先生成评价内容'
      return false
    }
    if (images.value.length === 0) {
      exportError.value = '没有可导出的图片'
      return false
    }

    exporting.value = true
    exportError.value = ''

    const formData = new FormData()
    for (const image of images.value) formData.append('images', image.file)
    formData.append('review', result.value.review)
    if (result.value.title) formData.append('title', result.value.title)
    if (result.value.tags?.length) formData.append('tags', JSON.stringify(result.value.tags))
    if (result.value.runId) formData.append('runId', result.value.runId)
    formData.append('platform', platform.value)
    if (reviewLength.value) formData.append('reviewLength', String(reviewLength.value))
    if (feelings.value) formData.append('feelings', feelings.value)

    try {
      const response = await fetch('/api/image-analysis/export-feishu', { method: 'POST', body: formData })
      const body = await response.json() as FeishuExportResponse
      if (!response.ok || !body.success) throw new Error(body.error || `导出失败（${response.status}）`)
      if (body.data) {
        exportedDocUrl.value = body.data.documentUrl
        exportedDocTitle.value = result.value.title || '图片评价导出'
      }
      return true
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === 'AbortError') return false
      exportError.value = err instanceof Error ? err.message : '导出到飞书失败'
      return false
    } finally {
      exporting.value = false
    }
  }

  // --- Style preferences ---

  async function loadStylePreferences(): Promise<void> {
    loadingPreferences.value = true
    try {
      const response = await fetch('/api/image-analysis/style-preferences')
      if (response.ok) {
        const body = await response.json() as { success: boolean; data?: { preferences: string[] } }
        stylePreferences.value = body.success && body.data ? body.data.preferences : []
      } else {
        stylePreferences.value = []
      }
    } catch {
      stylePreferences.value = []
    } finally {
      loadingPreferences.value = false
    }
  }

  async function optimizePreferences(): Promise<void> {
    if (stylePreferences.value.length === 0) return
    optimizingPreferences.value = true
    optimizeError.value = ''
    optimizedPreferences.value = null

    try {
      const response = await fetch('/api/image-analysis/style-preferences/optimize', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ preferences: stylePreferences.value }),
      })
      const body = await response.json() as { success: boolean; data?: { preferences: string[] }; error?: string }
      if (!response.ok || !body.success) {
        throw new Error(body.error || '风格偏好优化失败')
      }
      optimizedPreferences.value = body.data?.preferences ?? []
    } catch (err: unknown) {
      optimizeError.value = err instanceof Error ? err.message : '风格偏好优化失败'
    } finally {
      optimizingPreferences.value = false
    }
  }

  async function confirmOptimizedPreferences(): Promise<void> {
    if (!optimizedPreferences.value) return
    const success = await updateStylePreferencesOnServer(optimizedPreferences.value)
    if (success) {
      optimizedPreferences.value = null
      preferencePage.value = 1
    }
  }

  function cancelOptimizePreferences(): void {
    optimizedPreferences.value = null
    optimizeError.value = ''
  }

  async function toggleStylePreferences(): Promise<void> {
    if (showStylePreferences.value) {
      showStylePreferences.value = false
      optimizedPreferences.value = null
      optimizeError.value = ''
      return
    }
    await loadStylePreferences()
    preferencePage.value = 1
    optimizedPreferences.value = null
    optimizeError.value = ''
    showStylePreferences.value = true
  }

  async function updateStylePreferencesOnServer(preferences: string[]): Promise<boolean> {
    savingPreference.value = true
    try {
      const response = await fetch('/api/image-analysis/style-preferences', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ preferences }),
      })
      const body = await response.json() as UpdateStylePreferencesResponse
      if (!response.ok || !body.success) return false
      stylePreferences.value = body.data?.preferences ?? preferences
      return true
    } catch {
      return false
    } finally {
      savingPreference.value = false
    }
  }

  async function deleteStylePreference(globalIndex: number): Promise<void> {
    const updated = [...stylePreferences.value]
    updated.splice(globalIndex, 1)
    const success = await updateStylePreferencesOnServer(updated)
    if (success && preferencePage.value > Math.max(1, Math.ceil(updated.length / PREFERENCE_PAGE_SIZE))) {
      preferencePage.value = Math.max(1, preferencePage.value - 1)
    }
  }

  function startEditingPreference(globalIndex: number): void {
    editingPreferenceIndex.value = globalIndex
    editingPreferenceValue.value = stylePreferences.value[globalIndex] ?? ''
  }

  async function confirmEditingPreference(): Promise<void> {
    if (editingPreferenceIndex.value === null) return
    const updated = [...stylePreferences.value]
    const trimmed = editingPreferenceValue.value.trim()
    if (!trimmed) return
    updated[editingPreferenceIndex.value] = trimmed
    await updateStylePreferencesOnServer(updated)
    editingPreferenceIndex.value = null
    editingPreferenceValue.value = ''
  }

  function cancelEditingPreference(): void {
    editingPreferenceIndex.value = null
    editingPreferenceValue.value = ''
  }

  if (getCurrentInstance()) {
    onBeforeUnmount(() => {
      currentController?.abort()
      revokeAllPreviews()
    })
  }

  return {
    images,
    result,
    reviewLength,
    feelings,
    platform,
    loading,
    generationStage,
    error,
    progressEvents,
    currentProgress,
    exporting,
    exportError,
    exportedDocUrl,
    exportedDocTitle,
    isEditing,
    editTitle,
    editReview,
    editTags,
    savingStyle,
    saveStyleError,
    saveStyleSuccess,
    stylePreferences,
    hasStylePreferences,
    loadingPreferences,
    showStylePreferences,
    oversizedFiles,
    showOversizedDialog,
    compressing,
    preferencePage,
    totalPreferencePages,
    paginatedPreferences,
    paginatedStartIndex,
    editingPreferenceIndex,
    editingPreferenceValue,
    savingPreference,
    optimizingPreferences,
    optimizedPreferences,
    optimizeError,
    addFiles,
    removeImage,
    cancel,
    reset,
    exportToFeishu,
    startGeneration,
    proceedToOptimize,
    proceedToStyleRefine,
    completeWithoutStyleRefine,
    startEditing,
    cancelEditing,
    applyEditsLocally,
    saveStyleMemory,
    loadStylePreferences,
    toggleStylePreferences,
    compressOversizedImages,
    removeOversizedImages,
    cancelOversizedImages,
    stepResults,
    selectedStepResult,
    selectStepResult,
    clearStepResult,
    deleteStylePreference,
    startEditingPreference,
    confirmEditingPreference,
    cancelEditingPreference,
    optimizePreferences,
    confirmOptimizedPreferences,
    cancelOptimizePreferences,
  }
}
