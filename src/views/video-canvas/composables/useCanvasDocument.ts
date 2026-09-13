import { computed, onScopeDispose, ref, watch } from 'vue'
import type { Ref } from 'vue'
import { request } from '../../../composables/grassland-http'
import type { CanvasDocument, CanvasDocumentBody, CanvasNodeRef, VideoCanvasLayout } from '../../../types/video-canvas'

export type CanvasLoadOutcome = 'loaded' | 'missing' | 'error' | 'stale'
export interface UseCanvasDocumentOptions {
  fallbackShots?: () => Array<{ id: string }>
  epoch?: () => number
}
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T

/** A project-scoped local document and one CAS writer. Only an explicit missing read permits creation. */
export function useCanvasDocument(draftId: Ref<string>, options: UseCanvasDocumentOptions = {}) {
  const document = ref<CanvasDocumentBody | null>(null)
  const revision = ref(0)
  const loading = ref(false)
  const error = ref('')
  const conflict = ref(false)
  const upgrading = ref(false)
  const saveState = ref<'idle' | 'pending' | 'saving' | 'saved' | 'conflict' | 'error'>('idle')
  const readOnly = computed(() => document.value !== null && document.value.schemaVersion !== 1)
  const edits = ref(0)
  const savedEdits = ref(0)
  const dirty = computed(() => edits.value !== savedEdits.value)
  let generation = 0
  let readSequence = 0
  let readController: AbortController | null = null
  let saveTimer: ReturnType<typeof setTimeout> | null = null
  let inFlight: Promise<boolean> | null = null

  function clearTimer(): void {
    if (saveTimer !== null) clearTimeout(saveTimer)
    saveTimer = null
  }
  function reset(): void {
    generation += 1
    readSequence += 1
    readController?.abort()
    readController = null
    clearTimer()
    inFlight = null
    document.value = null
    revision.value = 0
    loading.value = false
    upgrading.value = false
    conflict.value = false
    error.value = ''
    edits.value = 0
    savedEdits.value = 0
    saveState.value = 'idle'
  }
  watch(() => [draftId.value, options.epoch?.()], reset, { flush: 'sync' })
  onScopeDispose(reset)

  function adoptRemote(remote: CanvasDocument): void {
    document.value = clone(remote.document)
    revision.value = remote.revision
    conflict.value = false
    savedEdits.value = edits.value
  }

  async function load(discardLocal = false): Promise<CanvasLoadOutcome> {
    const id = draftId.value
    if (!id) return 'stale'
    const ticket = generation
    const sequence = ++readSequence
    readController?.abort()
    const controller = new AbortController()
    readController = controller
    loading.value = true
    error.value = ''
    const current = () => ticket === generation && sequence === readSequence
    try {
      const remote = await request<CanvasDocument | null>(
        `/api/creation-drafts/${encodeURIComponent(id)}/canvas`, { signal: controller.signal })
      if (!current()) return 'stale'
      if (dirty.value && !discardLocal) {
        error.value = '存在未保存的画布修改，请先保存或明确载入最新版本'
        return 'error'
      }
      if (remote === null) {
        document.value = null
        revision.value = 0
        return 'missing'
      }
      if (!remote || remote.draftId !== id) throw new Error('画布响应与当前项目不匹配')
      adoptRemote(remote)
      return 'loaded'
    } catch (err) {
      if (!current()) return 'stale'
      if ([401, 404].includes((err as { status?: number }).status ?? 0)) {
        document.value = null
        revision.value = 0
        savedEdits.value = edits.value
      }
      error.value = `画布读取失败${err instanceof Error ? `：${err.message}` : '，已保留当前内容'}`
      return 'error'
    } finally {
      if (current()) loading.value = false
    }
  }

  function buildInitialBody(legacy: VideoCanvasLayout | null): CanvasDocumentBody {
    const nodes: CanvasNodeRef[] = (options.fallbackShots?.() ?? []).map((shot, index) => ({
      id: `shot:${shot.id}`, kind: 'shot', refType: 'shot', refId: shot.id, label: null, text: null,
      ...(legacy?.positions?.[shot.id] ?? { x: 40 + (index % 3) * 320, y: 40 + Math.floor(index / 3) * 290 }),
    }))
    nodes.unshift({ id: `brief:${draftId.value}`, kind: 'brief', refType: 'draft', refId: draftId.value,
      label: '创作要求', text: null, x: -280, y: 40 })
    return { schemaVersion: 1, storyboardId: legacy?.storyboardId ?? '',
      viewport: legacy?.viewport ?? { panX: 0, panY: 0, scale: 1 }, nodes, edges: [],
      activeBranchId: legacy?.activeBranchId ?? null }
  }

  async function put(id: string, expectedRevision: number, body: CanvasDocumentBody): Promise<CanvasDocument> {
    return request<CanvasDocument>(`/api/creation-drafts/${encodeURIComponent(id)}/canvas`, {
      method: 'PUT', body: JSON.stringify({ expectedRevision, document: body }),
    })
  }

  async function upgradeFromLegacy(legacy: VideoCanvasLayout | null): Promise<boolean> {
    if (!draftId.value || upgrading.value || document.value || revision.value > 0) return false
    const id = draftId.value
    const ticket = generation
    upgrading.value = true
    try {
      const outcome = await load()
      if (ticket !== generation) return false
      if (outcome === 'loaded') return true
      if (outcome !== 'missing') return false
      const created = await put(id, 0, buildInitialBody(legacy))
      if (ticket !== generation) return false
      adoptRemote(created)
      saveState.value = 'saved'
      return true
    } catch (err) {
      if (ticket !== generation) return false
      if ((err as { status?: number }).status === 409 && await load() === 'loaded') return true
      if (ticket === generation) error.value = err instanceof Error ? err.message : '画布升级失败，请重试'
      return false
    } finally {
      if (ticket === generation) upgrading.value = false
    }
  }

  function queue(next: CanvasDocumentBody): boolean {
    if (!draftId.value || readOnly.value || revision.value === 0) return false
    if (JSON.stringify(next) === JSON.stringify(document.value)) return true
    document.value = clone(next)
    edits.value += 1
    if (!conflict.value) saveState.value = 'pending'
    clearTimer()
    saveTimer = setTimeout(() => { void flush() }, 800)
    return true
  }

  async function drain(): Promise<boolean> {
    const ticket = generation
    const id = draftId.value
    while (ticket === generation && dirty.value && document.value) {
      const sequence = edits.value
      const next = clone(document.value)
      saveState.value = 'saving'
      error.value = ''
      try {
        const saved = await put(id, revision.value, next)
        if (ticket !== generation) return false
        revision.value = saved.revision
        savedEdits.value = sequence
        if (edits.value === sequence) document.value = clone(saved.document)
        saveState.value = dirty.value ? 'pending' : 'saved'
      } catch (err) {
        if (ticket !== generation) return false
        clearTimer()
        const status = (err as { status?: number }).status
        const code = (err as { code?: string }).code
        conflict.value = status === 409 && (!code || code === 'CANVAS_VERSION_CONFLICT')
        saveState.value = conflict.value ? 'conflict' : 'error'
        error.value = conflict.value ? '画布已在其他窗口更新，已保留本地修改，请载入最新版本后再编辑'
          : err instanceof Error ? err.message : '画布保存失败，已保留本地修改'
        if (status === 401 || status === 404) { document.value = null; savedEdits.value = edits.value }
        return false
      }
    }
    return ticket === generation
  }

  function flush(): Promise<boolean> {
    clearTimer()
    if (inFlight) return inFlight
    if (conflict.value || (readOnly.value && dirty.value)) return Promise.resolve(false)
    if (!dirty.value) return Promise.resolve(true)
    const run = drain()
    inFlight = run
    void run.finally(() => { if (inFlight === run) inFlight = null })
    return run
  }
  async function save(next: CanvasDocumentBody): Promise<boolean> {
    return queue(next) ? flush() : false
  }
  async function adoptLatest(): Promise<boolean> {
    if (inFlight && !(await inFlight)) return false
    clearTimer()
    const outcome = await load(true)
    if (outcome === 'loaded') saveState.value = 'saved'
    return outcome === 'loaded'
  }

  return { document, revision, loading, error, conflict, upgrading, readOnly, dirty, saveState,
    load, queue, save, flush, upgradeFromLegacy, adoptLatest, reset }
}
