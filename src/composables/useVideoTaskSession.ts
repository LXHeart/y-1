import { computed, effectScope, getCurrentInstance, getCurrentScope, onActivated, onDeactivated, onScopeDispose, ref, shallowRef, watch } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import type { Pinia } from 'pinia'
import { request } from './grassland-http'
import { createVideoTaskEvents } from './video-task-events'
import type { VideoTaskEvents } from './video-task-events'
import { useAccountSessionStore } from '../stores/account-session'
import type { SelectionResult } from '../types/video-canvas'
import type { VideoTask } from '../types/video-production'

/**
 * 任务书 #100 C100-05：快速/专业模式共用的任务会话（§6.8）。
 *
 * 同账号同 task 只建一条会话（按 pinia 池化，账号 epoch 变化整池清除）：
 * 任务态、选片待确认队列与 SSE/轮询生命周期全部归会话所有；视图侧只按消费者
 * 引用计数 acquire/release——最后一个消费者释放时停止浏览器连接，不取消服务端任务。
 * 空 taskId 不发请求；taskId 改变先释放旧会话再绑定；写方法失败落 taskError 并保留已确认数据。
 */
export interface VideoTaskSession {
  task: Ref<VideoTask | null>
  taskError: Ref<string>
  pendingSelectionCount: ComputedRef<number>
  acquire(consumerId: string): void
  release(consumerId: string): void
  refreshTask(): Promise<void>
  selectTake(shotId: string, takeId: string): Promise<void>
  useRecommendedSelection(): Promise<void>
  regenerateShot(shotId: string): Promise<void>
  rerollShot(shotId: string): Promise<void>
  composeTask(): Promise<void>
  cancelTask(): Promise<void>
}

/** 宿主（useVideoProduction/画布适配）可用的扩展控制面；§6.8 之外不对外承诺。 */
export interface VideoTaskSessionHost extends VideoTaskSession {
  /** SSE 断流降级标记（宿主透传展示）。 */
  readonly eventsDegraded: ComputedRef<boolean>
  /** 合成提交中（宿主透传按钮态）。 */
  readonly composeSubmitting: Ref<boolean>
  /** 挂起宿主：释放本消费者的全部引用并断开通道；taskId 再变化时自动重绑。 */
  suspendSession(): void
  /** 已有在途任务时确保通道打开（beginGeneration 重入分支）。 */
  resumeChannel(): void
}

interface TaskSessionCore {
  id: string
  task: Ref<VideoTask | null>
  taskError: Ref<string>
  pendingSelection: Ref<Record<string, string>>
  composeSubmitting: Ref<boolean>
  events: VideoTaskEvents
  consumers: Set<string>
  /** 服务端已确认的完整选择与单调版本（select 响应 / 任务详情共同维护）。 */
  confirmedSelection: Record<string, string>
  confirmedSelectionVersion: number
  /** 每镜最新意图（含已排队未发送值）；发送前复核，被更新意图取代的批次跳过。 */
  desiredSelection: Map<string, string>
  /** 选片写串行链：同一时刻至多一个请求在途。 */
  writeChain: Promise<void>
}

const pools = new WeakMap<Pinia, Map<string, TaskSessionCore>>()
let handleSeq = 0

function isTaskTerminal(task: VideoTask | null): boolean {
  return !!task && ['succeeded', 'failed', 'cancelled'].includes(task.phase)
}

/** 全部需生成镜头有已选候选（合成闸；宿主与画布适配共用同一判定）。
 * 任务书 #100 C100-12/13：own-media 镜头是确定片段（无候选），不计入选片完整性。 */
export function isSelectionComplete(task: VideoTask | null): boolean {
  return !!task
    && task.shots.length > 0
    && task.shots.every((shot) => shot.source?.kind === 'own-media'
      || (!!task.selection[shot.id]
        && shot.takes.some((take) => take.id === task.selection[shot.id] && take.selectable)))
}

/** 展示态 = recommended 补缺 + 服务端已确认 + 未确认乐观层（失效候选全部滤除）。 */
function displaySelection(core: TaskSessionCore, body: VideoTask): Record<string, string> {
  const selectable = new Set(body.shots.flatMap((shot) =>
    shot.takes.filter((take) => take.selectable).map((take) => take.id)))
  const merged: Record<string, string> = { ...body.recommended }
  for (const [shotId, takeId] of Object.entries(core.confirmedSelection)) {
    if (selectable.has(takeId)) merged[shotId] = takeId
  }
  for (const [shotId, takeId] of Object.entries(core.pendingSelection.value)) {
    if (selectable.has(takeId)) merged[shotId] = takeId
  }
  return merged
}

/**
 * 任务详情落地：服务端选择是权威，客户端只叠加未确认操作——
 * recommended 仅补缺展示；低于已接收 selectionVersion 的响应不回退选择数据（乱序隔离）。
 */
function applyTask(core: TaskSessionCore, body: VideoTask): void {
  const incomingVersion = body.selectionVersion ?? 0
  if (incomingVersion >= core.confirmedSelectionVersion) {
    core.confirmedSelectionVersion = incomingVersion
    core.confirmedSelection = { ...body.selection }
  }
  core.task.value = {
    ...body,
    selectionVersion: core.confirmedSelectionVersion,
    selection: displaySelection(core, body),
  }
}

/** 服务端完整选择落地（版本闸：不低于已接收版本才接受）。 */
function acceptConfirmedSelection(core: TaskSessionCore, result: SelectionResult | null | undefined): void {
  if (!result) return
  const version = Number(result.selectionVersion)
  if (!Number.isFinite(version) || version < core.confirmedSelectionVersion) return
  core.confirmedSelectionVersion = version
  core.confirmedSelection = { ...result.selection }
}

function clearPending(core: TaskSessionCore, shotId: string): void {
  if (!(shotId in core.pendingSelection.value)) return
  const next = { ...core.pendingSelection.value }
  delete next[shotId]
  core.pendingSelection.value = next
}

function enqueueSelectionWrite(core: TaskSessionCore, run: () => Promise<void>): Promise<void> {
  const executed = core.writeChain.then(run)
  core.writeChain = executed.then(() => undefined, () => undefined)
  return executed
}

/** 任务落地后的通道整备：在途 + 有消费者 → 开/维持事件通道；终态 → 收口。 */
function afterTaskApplied(core: TaskSessionCore): void {
  if (isTaskTerminal(core.task.value)) {
    core.events.stop()
    return
  }
  if (core.consumers.size > 0 && !core.events.hasChannel()) {
    core.events.start()
  }
}

function createCore(id: string): TaskSessionCore {
  const core: TaskSessionCore = {
    id,
    task: ref<VideoTask | null>(null),
    taskError: ref(''),
    pendingSelection: ref<Record<string, string>>({}),
    composeSubmitting: ref(false),
    events: createVideoTaskEvents({
      taskId: () => core.id,
      isTerminal: () => isTaskTerminal(core.task.value),
      refresh: async () => { await refreshTask(core) },
    }),
    consumers: new Set<string>(),
    confirmedSelection: {},
    confirmedSelectionVersion: 0,
    desiredSelection: new Map<string, string>(),
    writeChain: Promise.resolve(),
  }
  return core
}

/** 账号 epoch 变化：清除所有句柄与浏览器通道，任务态不跨账号残留。 */
function teardownCore(core: TaskSessionCore): void {
  core.events.stop()
  core.consumers.clear()
  core.task.value = null
  core.taskError.value = ''
  core.pendingSelection.value = {}
  core.confirmedSelection = {}
  core.confirmedSelectionVersion = 0
  core.desiredSelection.clear()
  core.writeChain = Promise.resolve()
}

async function refreshTask(core: TaskSessionCore): Promise<void> {
  if (!core.id) return
  try {
    const body = await request<VideoTask>(`/api/video-production/tasks/${core.id}`, {},
      { fallbackError: '任务状态读取失败' })
    if (body?.id !== core.id) return
    applyTask(core, body)
    afterTaskApplied(core)
  } catch (err: unknown) {
    core.taskError.value = err instanceof Error ? err.message : '任务状态读取失败'
  }
}

async function selectTake(core: TaskSessionCore, shotId: string, takeId: string): Promise<void> {
  const current = core.task.value
  if (!current) return
  core.desiredSelection.set(shotId, takeId)
  core.pendingSelection.value = { ...core.pendingSelection.value, [shotId]: takeId }
  core.task.value = { ...current, selection: displaySelection(core, current) }
  const taskId = core.id
  await enqueueSelectionWrite(core, async () => {
    if (core.task.value?.id !== taskId || core.desiredSelection.get(shotId) !== takeId) return
    try {
      const result = await request<SelectionResult>(
        `/api/video-production/tasks/${taskId}/takes/select`, {
          method: 'POST',
          body: JSON.stringify({ selections: [{ shotId, takeId }] }),
        }, { fallbackError: '选片保存失败' })
      if (core.desiredSelection.get(shotId) === takeId) {
        core.desiredSelection.delete(shotId)
        clearPending(core, shotId)
      }
      acceptConfirmedSelection(core, result)
    } catch (err: unknown) {
      // 失败撤回该次乐观覆盖：展示回退到已确认值，输入不丢、可重试
      if (core.desiredSelection.get(shotId) === takeId) {
        core.desiredSelection.delete(shotId)
        clearPending(core, shotId)
      }
      core.taskError.value = err instanceof Error ? err.message : '选片保存失败'
    }
    if (core.task.value?.id === taskId) {
      core.task.value = {
        ...core.task.value,
        selectionVersion: core.confirmedSelectionVersion,
        selection: displaySelection(core, core.task.value),
      }
    }
  })
}

async function useRecommendedSelection(core: TaskSessionCore): Promise<void> {
  const current = core.task.value
  if (!current) return
  core.desiredSelection.clear()
  core.pendingSelection.value = {}
  const snapshot = { ...current.selection }
  core.task.value = { ...current, selection: { ...current.recommended } }
  const taskId = core.id
  await enqueueSelectionWrite(core, async () => {
    if (core.task.value?.id !== taskId || core.desiredSelection.size > 0) return
    try {
      const result = await request<SelectionResult>(
        `/api/video-production/tasks/${taskId}/takes/select`, {
          method: 'POST',
          body: JSON.stringify({ useRecommended: true }),
        }, { fallbackError: '一键选片失败' })
      acceptConfirmedSelection(core, result)
    } catch (err: unknown) {
      // 失败撤回乐观层：回到点击前的已确认展示
      core.taskError.value = err instanceof Error ? err.message : '一键选片失败'
      if (core.task.value?.id === taskId) {
        core.task.value = { ...core.task.value, selection: snapshot }
      }
      return
    }
    if (core.task.value?.id === taskId) {
      core.task.value = {
        ...core.task.value,
        selectionVersion: core.confirmedSelectionVersion,
        selection: displaySelection(core, core.task.value),
      }
    }
  })
}

async function mutateTask(core: TaskSessionCore, path: string, fallbackError: string): Promise<void> {
  if (!core.task.value) return
  try {
    await request(`/api/video-production/tasks/${core.id}/${path}`, { method: 'POST' },
      { fallbackError })
    await refreshTask(core)
  } catch (err: unknown) {
    core.taskError.value = err instanceof Error ? err.message : fallbackError
  }
}

async function composeTask(core: TaskSessionCore): Promise<void> {
  if (!core.task.value || !isSelectionComplete(core.task.value) || core.composeSubmitting.value) return
  core.composeSubmitting.value = true
  core.taskError.value = ''
  try {
    await request(`/api/video-production/tasks/${core.id}/compose`, {
      method: 'POST',
    }, { fallbackError: '合成请求失败' })
    await refreshTask(core)
    // 通道活跃（含降级期轮询）时不重复开表；无通道才回落轮询
    if (!core.events.hasChannel()) {
      core.events.fallbackPolling()
    }
  } catch (err: unknown) {
    core.taskError.value = err instanceof Error ? err.message : '合成请求失败'
  } finally {
    core.composeSubmitting.value = false
  }
}

/**
 * 取（或建）账号内同 task 会话。组件装配期传入可变 taskId 引用；
 * taskId 改变先释放旧会话再绑定，KeepAlive 失活自动释放、激活自动重取。
 */
export function useVideoTaskSession(taskId: Ref<string>): VideoTaskSessionHost {
  const instance = getCurrentInstance()
  const pinia = instance?.appContext.config.globalProperties.$pinia as Pinia | undefined
  const account = pinia ? useAccountSessionStore(pinia) : undefined
  let pool = pinia ? pools.get(pinia) : undefined
  if (pinia && !pool) {
    pool = new Map()
    pools.set(pinia, pool)
    const cache = pool
    effectScope(true).run(() => {
      if (account) watch(() => account.epoch, () => {
        for (const core of cache.values()) teardownCore(core)
        cache.clear()
      }, { flush: 'sync' })
    })
  }
  // 无组件上下文（单测直调）：不做跨实例共享，本句柄私有池
  const privatePool = pinia ? null : new Map<string, TaskSessionCore>()
  const activePool = pool ?? privatePool!

  const selfConsumer = `handle-${++handleSeq}`
  const namedConsumers = new Set<string>()
  // shallowRef：句柄上的 task/taskError 等 computed 既依赖绑定对象也依赖核心内部 ref，
  // 绑定切换（taskId 变化/挂起）必须触发失效，又不深跟踪核心
  const bound = shallowRef<TaskSessionCore | null>(null)

  function attach(id: string): void {
    const core = activePool.get(id) ?? createCore(id)
    activePool.set(id, core)
    core.consumers.add(selfConsumer)
    for (const named of namedConsumers) core.consumers.add(named)
    bound.value = core
    afterTaskApplied(core)
  }

  function detach(): void {
    const core = bound.value
    if (!core) return
    core.consumers.delete(selfConsumer)
    for (const named of namedConsumers) core.consumers.delete(named)
    if (core.consumers.size === 0) core.events.stop()
    bound.value = null
  }

  function rebind(): void {
    detach()
    const id = taskId.value
    if (id) attach(id)
  }

  watch(taskId, () => rebind(), { flush: 'sync' })
  rebind()

  if (instance) {
    onActivated(() => rebind())
    onDeactivated(() => detach())
  }
  if (getCurrentScope()) onScopeDispose(detach)

  const task = computed<VideoTask | null>({
    get: () => bound.value?.task.value ?? null,
    set: (value) => { if (bound.value) bound.value.task.value = value },
  })
  const taskError = computed<string>({
    get: () => bound.value?.taskError.value ?? '',
    set: (value) => { if (bound.value) bound.value.taskError.value = value },
  })
  const pendingSelectionCount = computed(() => Object.keys(bound.value?.pendingSelection.value ?? {}).length)
  const eventsDegraded = computed(() => bound.value?.events.degraded.value ?? false)
  const composeSubmitting = computed(() => bound.value?.composeSubmitting.value ?? false)

  function acquire(consumerId: string): void {
    const core = bound.value
    if (!core) return
    namedConsumers.add(consumerId)
    core.consumers.add(consumerId)
    afterTaskApplied(core)
  }

  function release(consumerId: string): void {
    const core = bound.value
    if (!core || !namedConsumers.has(consumerId)) return
    namedConsumers.delete(consumerId)
    core.consumers.delete(consumerId)
    if (core.consumers.size === 0) core.events.stop()
  }

  return {
    task,
    taskError,
    pendingSelectionCount,
    acquire,
    release,
    refreshTask: async () => { if (bound.value) await refreshTask(bound.value) },
    selectTake: async (shotId, takeId) => { if (bound.value) await selectTake(bound.value, shotId, takeId) },
    useRecommendedSelection: async () => { if (bound.value) await useRecommendedSelection(bound.value) },
    regenerateShot: async (shotId) => {
      if (bound.value) await mutateTask(bound.value, `shots/${encodeURIComponent(shotId)}/regenerate`, '重抽失败')
    },
    rerollShot: async (shotId) => {
      if (bound.value) await mutateTask(bound.value, `shots/${encodeURIComponent(shotId)}/reroll`, '成片后重抽失败')
    },
    composeTask: async () => { if (bound.value) await composeTask(bound.value) },
    cancelTask: async () => { if (bound.value) await mutateTask(bound.value, 'cancel', '取消失败') },
    eventsDegraded,
    composeSubmitting,
    suspendSession: detach,
    resumeChannel: () => { if (bound.value) afterTaskApplied(bound.value) },
  }
}
