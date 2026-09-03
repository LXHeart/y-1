import { ref, computed, onMounted } from 'vue'
import type {
  StoryboardShot,
  VideoCapabilities,
  VideoProductionForm,
  VideoProductionImage,
  VideoProductionStage,
  VideoResolution,
} from '../types/video-production'
import {
  SHOT_COUNT_MAX,
  SHOT_SECONDS_MAX,
  SHOT_SECONDS_MIN,
  TARGET_DURATION_DEFAULT,
  TARGET_DURATION_MAX,
  TARGET_DURATION_MIN,
  TARGET_DURATION_STEP,
  defaultResolutionFor,
} from '../types/video-production'
import { compressImageToFile } from './compress-image'
import { parseSafetyFrame } from './useContentSafety'
import type { SafetyReport } from './useContentSafety'
import { fetchApi, request } from './grassland-http'

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

/** #69 卡C：GET /api/video-production/storyboards/{id} 响应（画布同款数据源；takes 不入 StoryboardShot）。 */
interface StoryboardDetail {
  id?: string
  targetDurationSeconds?: number
  resolution?: string | null
  shots?: Array<Partial<StoryboardShot> & { id: string; seq?: number }>
}

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
    resolution: '',
  }
}

/** 参考结构引用（任务书 #66 E1，§3 契约）：分镜请求透传给后端按 §3 文案注入。 */
export interface ShotStructureRef {
  shotStructure: Array<{ durationSeconds: number; purpose: 'hook' | 'point' | 'cta' | 'transition' }>
  hookAtSeconds?: number
}

/** 非法时长钳制到步进档位（#65 卡1：15-180 步进 5 的前端闸）。 */
export function clampTargetDuration(value: number): number {
  if (!Number.isFinite(value)) return TARGET_DURATION_DEFAULT
  const stepped = Math.round(value / TARGET_DURATION_STEP) * TARGET_DURATION_STEP
  return Math.min(TARGET_DURATION_MAX, Math.max(TARGET_DURATION_MIN, stepped))
}

export interface TaskTake {
  id: string
  takeNo: number
  status: string
  attempts: number
  provider: string | null
  model: string | null
  mediaId: string | null
  durationMs: number | null
  errorCode: string | null
  errorMessage: string | null
  selectable: boolean
  /** 质检评分（任务书 #66 D1）：0-100；null=未评不显角标（advisory）。 */
  score: number | null
  /** 评分提示标签（如「与锚定图差异大」）；未评为空数组。 */
  scoreLabels: string[]
  url: string | null
}

export interface TaskShot {
  id: string
  seq: number
  visual: string
  narration: string
  plannedSeconds: number
  cameraMove: string
  anchorImageIndex: number
  prompt: string
  status: string
  audio: { status: string | null; provider: string | null; model: string | null; durationMs: number | null }
  takes: TaskTake[]
}

export interface VideoTask {
  id: string
  storyboardId: string
  mode: 'video' | 'slideshow'
  phase: string
  progress: number
  targetDurationSeconds: number
  provider: string | null
  model: string | null
  unitPriceCents: number
  estimatedCostCents: number
  actualCostCents: number | null
  actualDurationSeconds: number | null
  errorCode: string | null
  errorMessage: string | null
  selection: Record<string, string>
  recommended: Record<string, string>
  finalUrl: string | null
  subtitleUrl: string | null
  shots: TaskShot[]
}

export interface HistoryItem {
  id: string
  storyboardId: string
  mode: string
  phase: string
  progress: number
  targetDurationSeconds: number
  actualDurationSeconds: number | null
  estimatedCostCents: number
  actualCostCents: number | null
  unitPriceCents: number
  createdAt: string | null
  completedAt: string | null
  errorCode: string | null
  errorMessage: string | null
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
  const referenceShotStructure = ref<ShotStructureRef | null>(null)
  const safetyReport = ref<SafetyReport | null>(null)
  const storyboardLoading = ref(false)
  const error = ref('')
  const task = ref<VideoTask | null>(null)
  const taskError = ref('')
  const composeSubmitting = ref(false)
  const history = ref<{ items: HistoryItem[]; total: number; page: number }>({ items: [], total: 0, page: 1 })
  const historyLoading = ref(false)
  const historyError = ref('')
  const taskMode = ref(false)
  const contextSnapshotId = ref<string | null>(null)
  /** #65 卡2：逐镜补图态（shotId → loading/error）；成功态落在 shot.anchorUrl 上。 */
  const anchorGenerating = ref<Record<string, boolean>>({})
  const anchorErrors = ref<Record<string, string>>({})
  /** #69 卡C：按 id 恢复的分镜 id（非空时视图显示「已载入分镜 …」提示）。 */
  const restoredStoryboardId = ref('')

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

  /** 全部镜头有已选候选（合成按钮闸）。 */
  const selectionComplete = computed(() => {
    const current = task.value
    return !!current
      && current.shots.length > 0
      && current.shots.every((shot) => !!current.selection[shot.id]
        && shot.takes.some((take) => take.id === current.selection[shot.id] && take.selectable))
  })
  /** 逐镜生成是否仍在进行（queued/submitted/processing 任一候选存在）。 */
  const generationInProgress = computed(() => {
    const current = task.value
    return !!current && current.shots.some((shot) =>
      shot.takes.some((take) => ['queued', 'submitted', 'processing'].includes(take.status)))
  })
  /** 任务终态（失败/已取消）——生成步收起操作并展示状态（succeeded 进 compose 步）。 */
  const taskTerminal = computed(() => !!task.value
    && ['failed', 'cancelled'].includes(task.value.phase))
  const canAddShot = computed(() => shots.value.length < SHOT_COUNT_MAX)
  const totalPlannedSeconds = computed(() =>
    shots.value.reduce((sum, shot) => sum + shot.plannedSeconds, 0))
  /** 安全面板定位用：全部旁白拼接（镜头正文是旁白，prompt 不进用户可见正文）。 */
  const narrationText = computed(() =>
    shots.value.map((shot) => shot.narration).filter(Boolean).join('\n'))

  /** #65 卡1：生效分辨率（显式选择优先，否则按平台缺省）。 */
  const resolvedResolution = computed<VideoResolution>(() =>
    form.value.resolution || defaultResolutionFor(form.value.targetPlatform))
  const isLandscape = computed(() => resolvedResolution.value === '1920x1080')
  /** #65 卡1 范围内软提示：竖版平台 >60s 仅文案建议（不锁死）。 */
  const verticalDurationHint = computed(() =>
    !isLandscape.value && form.value.targetDurationSeconds > 60
      ? '竖版平台建议 60 秒内，过长完播率易下滑'
      : '')
  /** 预估价展示（#65 卡3：时长变化重算；slideshow/未知价时不显示）。 */
  const estimatedPriceCents = computed(() => {
    const unit = capabilities.value?.video?.unitPriceCents ?? null
    return unit === null ? null : unit * form.value.targetDurationSeconds
  })

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
          resolution: resolvedResolution.value,
          referenceShotStructure: referenceShotStructure.value ?? undefined,
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

  /**
   * 按 storyboardId 恢复到分镜步（任务书 #69 卡C）：回填 shots/storyboardId 与可得的 form 字段
   * （目标时长/分辨率；平台等行业店铺字段响应不含，保持当前默认值）。
   * 不自动开始生成——beginGeneration 是资金动作（扣费预留），必须用户手点（D3）。
   */
  async function restoreStoryboard(id: string): Promise<void> {
    if (!id || storyboardLoading.value) return
    storyboardLoading.value = true
    error.value = ''
    try {
      const detail = await request<StoryboardDetail>(
        `/api/video-production/storyboards/${encodeURIComponent(id)}`, {},
        { fallbackError: '分镜载入失败' })
      if (!detail?.id) throw new Error('分镜载入失败')
      storyboardId.value = detail.id
      shots.value = (detail.shots ?? [])
        .slice()
        .sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))
        .map((shot, index) => normalizeShot(shot, index + 1))
      if (typeof detail.targetDurationSeconds === 'number') {
        form.value = { ...form.value, targetDurationSeconds: clampTargetDuration(detail.targetDurationSeconds) }
      }
      if (detail.resolution === '1080x1920' || detail.resolution === '1920x1080') {
        form.value = { ...form.value, resolution: detail.resolution }
      }
      restoredStoryboardId.value = detail.id
      stage.value = 'storyboard'
    } catch (err: unknown) {
      error.value = err instanceof Error ? err.message : '分镜载入失败'
    } finally {
      storyboardLoading.value = false
    }
  }

  /**
   * 镜头编辑：本地即时回显 + 写通服务端（PUT /shots/{id}/content，行是任务生成的真相源）。
   * 无 id 的本地新增镜头无法写通（无创建端点，遗留缺口）；写失败落 error 不静默。
   */
  function updateShot(index: number, patch: Partial<StoryboardShot>): void {
    const current = shots.value[index]
    if (!current) return
    const merged = normalizeShot({ ...current, ...patch }, current.seq)
    shots.value = shots.value.map((shot, position) => position === index ? merged : shot)
    if (!merged.id) return
    void request('/api/video-production/shots/' + encodeURIComponent(merged.id) + '/content', {
      method: 'PUT',
      body: JSON.stringify({
        visual: merged.visual,
        narration: merged.narration,
        plannedSeconds: merged.plannedSeconds,
        cameraMove: merged.cameraMove,
        anchorImageIndex: merged.anchorImageIndex,
      }),
    }, { fallbackError: '镜头保存失败' }).catch((err: unknown) => {
      error.value = err instanceof Error ? err.message : '镜头保存失败'
    })
  }

  /**
   * AI 补图首帧（#65 卡2/卡3）：默认关闭、逐镜手动触发；成功后本地回填 anchorUrl 角标数据。
   * 失败只落 anchorErrors（提示不阻断编辑），成功清除错误。
   */
  async function generateAnchorImage(shotId: string): Promise<void> {
    if (!shotId || anchorGenerating.value[shotId]) return
    anchorGenerating.value = { ...anchorGenerating.value, [shotId]: true }
    anchorErrors.value = { ...anchorErrors.value, [shotId]: '' }
    try {
      const body = await request<{ mediaId: string; shot: Partial<StoryboardShot> }>(
        `/api/video-production/shots/${shotId}/anchor:generate`, {
          method: 'POST',
        }, { fallbackError: 'AI 生成首帧失败' })
      const generated = body?.shot
      if (generated?.anchorMediaId) {
        shots.value = shots.value.map(shot => shot.id === shotId
          ? {
              ...shot,
              anchorSource: 'ai',
              anchorMediaId: generated.anchorMediaId,
              anchorUrl: generated.anchorUrl ?? null,
            }
          : shot)
      }
    } catch (err: unknown) {
      anchorErrors.value = {
        ...anchorErrors.value,
        [shotId]: err instanceof Error ? err.message : 'AI 生成首帧失败',
      }
    } finally {
      anchorGenerating.value = { ...anchorGenerating.value, [shotId]: false }
    }
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
    restoredStoryboardId.value = ''
    safetyReport.value = null
    storyboardLoading.value = false
    error.value = ''
    stage.value = 'upload'
  }

  /** 进入生成与挑选步：建任务（幂等键沿用 storyboardId 派生）并开始轮询（卡6/卡9 接线）。 */
  async function beginGeneration(): Promise<void> {
    if (storyboardLoading.value || shots.value.length === 0 || !storyboardId.value) return
    error.value = ''
    taskError.value = ''
    stage.value = 'generate'
    if (task.value && task.value.storyboardId === storyboardId.value) {
      resumePolling()
      return
    }
    try {
      const created = await request<{ id: string }>('/api/video-production/tasks', {
        method: 'POST',
        body: JSON.stringify({
          storyboardId: storyboardId.value,
          operationId: `web-${storyboardId.value}`,
        }),
      }, { fallbackError: '成片任务创建失败' })
      if (!created?.id) throw new Error('成片任务创建失败')
      await loadTask(created.id)
      startTaskEvents()
    } catch (err: unknown) {
      error.value = err instanceof Error ? err.message : '成片任务创建失败'
    }
  }

  /**
   * 轮询任务详情（2s 沿用旧链节奏）；终结态停表、合成完成进 compose 步。
   * #65 卡5：降级通道——SSE 断流 2 个心跳周期（60s）后由 watchdog 切入；恢复后自动升回。
   */
  let pollTimer: ReturnType<typeof setTimeout> | null = null

  function startPolling(): void {
    stopPolling()
    const tick = async () => {
      await refreshTask()
      applyPhaseTransition()
      if (!taskTerminal.value) {
        pollTimer = setTimeout(tick, 2000)
      }
    }
    pollTimer = setTimeout(tick, 2000)
  }

  /** 终态/合成期的阶段推进（轮询 tick 与 SSE 事件路径共用）。 */
  function applyPhaseTransition(): void {
    const phase = task.value?.phase
    if (phase === 'succeeded' || phase === 'failed' || phase === 'cancelled') {
      stopPolling()
      stopTaskEvents()
      if (phase === 'succeeded') {
        stage.value = 'compose'
      }
      return
    }
    if (phase === 'composing') {
      stage.value = 'compose'
    }
  }

  function resumePolling(): void {
    startTaskEvents()
  }

  function stopPolling(): void {
    if (pollTimer) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  // ---- #65 卡5：任务 SSE 消费与轮询降级 ----
  /** 心跳周期 30s；2 个周期（60s）无帧回落轮询；降级后每 60s 尝试升回。 */
  const SSE_DEGRADE_AFTER_MS = 60_000
  const SSE_RECONNECT_AFTER_MS = 60_000
  const SSE_WATCHDOG_INTERVAL_MS = 5_000
  const EVENT_REFRESH_COALESCE_MS = 300

  const eventsDegraded = ref(false)
  let eventsController: AbortController | null = null
  let eventsWatchdog: ReturnType<typeof setInterval> | null = null
  let lastFrameAt = 0
  let lastReconnectAt = 0
  let eventRefreshTimer: ReturnType<typeof setTimeout> | null = null

  /** 生成与挑选步进入时开（beginGeneration 调用）；事件只触发快照拉取，不直接改状态。 */
  function startTaskEvents(): void {
    if (!task.value) return
    stopTaskEvents()
    eventsDegraded.value = false
    lastFrameAt = Date.now()
    const controller = new AbortController()
    eventsController = controller
    void consumeTaskEvents(controller)
    eventsWatchdog = setInterval(watchdogTick, SSE_WATCHDOG_INTERVAL_MS)
  }

  /** 离开生成步/终态/卸载（reset）调用；clearWatchdog=false 保留降级期的回升探测。 */
  function stopTaskEvents(clearWatchdog = true): void {
    eventsController?.abort()
    eventsController = null
    if (eventRefreshTimer) {
      clearTimeout(eventRefreshTimer)
      eventRefreshTimer = null
    }
    if (clearWatchdog && eventsWatchdog) {
      clearInterval(eventsWatchdog)
      eventsWatchdog = null
    }
  }

  async function consumeTaskEvents(controller: AbortController): Promise<void> {
    const taskId = task.value?.id
    if (!taskId) return
    try {
      const response = await fetchApi(`/api/video-production/tasks/${taskId}/events`, {
        headers: { Accept: 'text/event-stream' },
        signal: controller.signal,
      })
      if (!response.ok || !response.body) {
        throw new Error('事件流打开失败')
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      while (true) {
        if (controller.signal.aborted) {
          reader.cancel().catch(() => undefined)
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
          if (!payload || payload === '[DONE]') continue
          lastFrameAt = Date.now()
          let type = ''
          try {
            type = (JSON.parse(payload) as { type?: string }).type ?? ''
          } catch {
            continue
          }
          // 心跳只续命；其余事件合并触发一次快照拉取（渲染以快照为准）
          if (type !== 'heartbeat') {
            scheduleEventRefresh()
          }
        }
      }
      // 流正常收口（终态后端 complete）——补一次快照，不降级
      scheduleEventRefresh()
    } catch {
      if (!controller.signal.aborted) {
        degradeToPolling()
      }
    } finally {
      if (eventsController === controller) {
        eventsController = null
      }
    }
  }

  /** 事件合并刷新：窗口内多个事件只拉一次快照。 */
  function scheduleEventRefresh(): void {
    if (eventRefreshTimer) return
    eventRefreshTimer = setTimeout(async () => {
      eventRefreshTimer = null
      await refreshTask()
      applyPhaseTransition()
    }, EVENT_REFRESH_COALESCE_MS)
  }

  /** 断流/异常 → 回落 2s 轮询并标记 degraded；watchdog 周期尝试升回。 */
  function degradeToPolling(): void {
    if (!task.value || taskTerminal.value) return
    eventsDegraded.value = true
    lastReconnectAt = Date.now()
    stopTaskEvents(false)
    startPolling()
  }

  function watchdogTick(): void {
    if (!task.value || taskTerminal.value) {
      stopTaskEvents()
      return
    }
    if (!eventsDegraded.value) {
      if (Date.now() - lastFrameAt > SSE_DEGRADE_AFTER_MS) {
        eventsController?.abort()
        degradeToPolling()
      }
      return
    }
    if (Date.now() - lastReconnectAt > SSE_RECONNECT_AFTER_MS) {
      // 升回：重开 SSE（心跳续命则留在事件通道；2 周期无帧由同一 watchdog 再降级）
      stopPolling()
      startTaskEvents()
    }
  }

  /**
   * 任务详情落地（任务书 4.6「采用单选预选推荐」）：recommended 补缺预选、
   * 用户显式选择优先、重抽后失效的选择回退推荐——否则合成按钮永远不解禁。
   */
  function applyTask(body: VideoTask): void {
    const selectable = new Set(body.shots.flatMap((shot) =>
      shot.takes.filter((take) => take.selectable).map((take) => take.id)))
    const merged: Record<string, string> = { ...body.recommended }
    for (const source of [body.selection, task.value?.selection]) {
      for (const [shotId, takeId] of Object.entries(source ?? {})) {
        if (selectable.has(takeId)) merged[shotId] = takeId
      }
    }
    task.value = { ...body, selection: merged }
  }

  async function refreshTask(): Promise<void> {
    if (!task.value) return
    try {
      const body = await request<VideoTask>(`/api/video-production/tasks/${task.value.id}`, {},
        { fallbackError: '任务状态读取失败' })
      if (body) {
        applyTask(body)
      }
    } catch (err: unknown) {
      taskError.value = err instanceof Error ? err.message : '任务状态读取失败'
    }
  }

  /** 建任务后取详情（beginGeneration 首次拿任务 id）。 */
  async function loadTask(id: string): Promise<void> {
    const body = await request<VideoTask>(`/api/video-production/tasks/${id}`, {},
      { fallbackError: '任务状态读取失败' })
    applyTask(body)
  }

  /** 选片（本地即时回显 + 服务端持久）。 */
  async function selectTake(shotId: string, takeId: string): Promise<void> {
    if (!task.value) return
    task.value = {
      ...task.value,
      selection: { ...task.value.selection, [shotId]: takeId },
    }
    try {
      await request(`/api/video-production/tasks/${task.value.id}/takes/select`, {
        method: 'POST',
        body: JSON.stringify({ selections: [{ shotId, takeId }] }),
      }, { fallbackError: '选片保存失败' })
    } catch (err: unknown) {
      taskError.value = err instanceof Error ? err.message : '选片保存失败'
    }
  }

  async function useRecommendedSelection(): Promise<void> {
    if (!task.value) return
    task.value = { ...task.value, selection: { ...task.value.recommended } }
    try {
      await request(`/api/video-production/tasks/${task.value.id}/takes/select`, {
        method: 'POST',
        body: JSON.stringify({ useRecommended: true }),
      }, { fallbackError: '一键选片失败' })
    } catch (err: unknown) {
      taskError.value = err instanceof Error ? err.message : '一键选片失败'
    }
  }

  /** 单镜重抽一批（计费不追加）。 */
  async function regenerateShot(shotId: string): Promise<void> {
    if (!task.value) return
    try {
      await request(`/api/video-production/tasks/${task.value.id}/shots/${shotId}/regenerate`, {
        method: 'POST',
      }, { fallbackError: '重抽失败' })
      await refreshTask()
    } catch (err: unknown) {
      taskError.value = err instanceof Error ? err.message : '重抽失败'
    }
  }

  /** 合成成片：phase=composing 后轮询接管，完成进 compose 步。 */
  async function composeTask(): Promise<void> {
    if (!task.value || !selectionComplete.value || composeSubmitting.value) return
    composeSubmitting.value = true
    taskError.value = ''
    try {
      await request(`/api/video-production/tasks/${task.value.id}/compose`, {
        method: 'POST',
      }, { fallbackError: '合成请求失败' })
      await refreshTask()
      // SSE 通道活跃（含降级期轮询）时不重复开表；无通道才回落轮询
      if (!eventsWatchdog) {
        startPolling()
      }
    } catch (err: unknown) {
      taskError.value = err instanceof Error ? err.message : '合成请求失败'
    } finally {
      composeSubmitting.value = false
    }
  }

  /** 取消任务（预留全额退）。 */
  async function cancelTask(): Promise<void> {
    if (!task.value) return
    try {
      await request(`/api/video-production/tasks/${task.value.id}/cancel`, {
        method: 'POST',
      }, { fallbackError: '取消失败' })
      await refreshTask()
    } catch (err: unknown) {
      taskError.value = err instanceof Error ? err.message : '取消失败'
    }
  }

  /** SRT 下载（presign 短链新窗）。 */
  async function downloadSubtitle(): Promise<void> {
    if (!task.value) return
    try {
      const body = await request<{ downloadUrl: string }>(
        `/api/video-production/tasks/${task.value.id}/subtitle`, {},
        { fallbackError: '字幕下载失败' })
      if (body?.downloadUrl) {
        window.open(body.downloadUrl, '_blank', 'noopener')
      }
    } catch (err: unknown) {
      taskError.value = err instanceof Error ? err.message : '字幕下载失败'
    }
  }

  /** 历史任务列表（卡9）。 */
  async function loadHistory(page = 1): Promise<void> {
    historyLoading.value = true
    historyError.value = ''
    try {
      const body = await request<{ items: HistoryItem[]; total: number; page: number; pageSize: number }>(
        `/api/video-production/tasks?page=${page}&pageSize=10`, {},
        { fallbackError: '历史任务加载失败' })
      history.value = {
        items: body?.items ?? [],
        total: body?.total ?? 0,
        page: body?.page ?? page,
      }
    } catch (err: unknown) {
      historyError.value = err instanceof Error ? err.message : '历史任务加载失败'
    } finally {
      historyLoading.value = false
    }
  }

  function goBackToStoryboard(): void {
    stopPolling()
    stopTaskEvents()
    error.value = ''
    stage.value = 'storyboard'
  }

  function reset(): void {
    storyboardController?.abort()
    storyboardController = null
    stopPolling()
    stopTaskEvents()
    task.value = null
    taskError.value = ''
    composeSubmitting.value = false
    anchorGenerating.value = {}
    anchorErrors.value = {}

    stage.value = 'upload'
    images.value = []
    form.value = defaultForm()
    shots.value = []
    storyboardId.value = ''
    restoredStoryboardId.value = ''
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

  /** SSE 帧载荷校验 + 钳制：时长 4-6、锚定图 [0, 图片数]；id/锚定回填字段透传（#65 卡2/3）。 */
  function normalizeShot(raw: Partial<StoryboardShot>, seq: number): StoryboardShot {
    const planned = Number(raw.plannedSeconds)
    const anchor = Number(raw.anchorImageIndex)
    return {
      id: raw.id,
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
      anchorSource: raw.anchorSource,
      anchorMediaId: raw.anchorMediaId,
      anchorUrl: raw.anchorUrl,
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
    resolvedResolution, isLandscape, verticalDurationHint, estimatedPriceCents,
    anchorGenerating, anchorErrors, generateAnchorImage, eventsDegraded,
    addImages, removeImage, reorderImage,
    generateStoryboard, updateShot, removeShot, addShot, referenceShotStructure,
    restoreStoryboard, restoredStoryboardId,
    goBackToUpload, beginGeneration, goBackToStoryboard,
    reset, bindCreationContext, loadCapabilities,
    task, taskError, composeSubmitting, history, historyLoading, historyError,
    selectionComplete, generationInProgress, taskTerminal, refreshTask,
    selectTake, useRecommendedSelection, regenerateShot, composeTask, cancelTask,
    downloadSubtitle, loadHistory,
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
