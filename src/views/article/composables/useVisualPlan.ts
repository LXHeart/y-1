import { computed, ref } from 'vue'
import type { RecipeRef, SourceBlock, SourceDocument, VisualPlan, VisualPlanDocument,
  VisualPlanItem, VisualStrategy } from '../../../types/creation-studio'
import { studioPost, studioRequest, StudioHttpError, studioErrorMessage, useStudioActivity, useStudioGuard } from '../../../lib/creation-studio-http'

export interface PreparePlanInput {
  strategy?: VisualStrategy
  itemCount?: number
  style?: Partial<{ styleId: string; layoutId: string; paletteId: string }>
  targetAspect?: string
  selectedBlockIds?: string[]
}

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b)

export function useVisualPlan(options: {
  draftId: () => string | null
  draftVersion: () => number
  sourceDocumentId: () => string | null
  sourceContentHash: () => string | null
  recipe: () => RecipeRef | null
  onPlanCreated: (plan: VisualPlan) => void
  beforeConfirm?: () => Promise<boolean>
}) {
  const current = ref<VisualPlan | null>(null)
  const document = ref<VisualPlanDocument | null>(null)
  const boundSource = ref<SourceDocument | null>(null)
  const preparing = ref(false)
  const saving = ref(false)
  const confirming = ref(false)
  const error = ref('')
  const conflict = ref(false)
  const guard = useStudioGuard(options.draftId)
  const dirty = computed(() => !same(document.value, current.value?.document ?? null))
  let sequence = 0
  let saveTimer: ReturnType<typeof setTimeout> | null = null
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let saveInFlight: Promise<boolean> | null = null
  let pendingPatch: { key: string; requestId: string } | null = null
  let pendingPrepare: { key: string; requestId: string } | null = null
  let pendingConfirm: { key: string; requestId: string } | null = null
  let pollCount = 0
  function clearSave(): void { if (saveTimer) clearTimeout(saveTimer); saveTimer = null }
  function clearPoll(): void { if (pollTimer) clearTimeout(pollTimer); pollTimer = null }
  const activity = useStudioActivity(() => { clearSave(); clearPoll() }, () => {
    if (dirty.value) touch()
    if (current.value && !preparing.value && !saving.value && !confirming.value) void refresh()
  })
  guard.onInvalidate(() => {
    sequence += 1
    clearSave(); clearPoll()
    current.value = null; document.value = null; boundSource.value = null
    preparing.value = false; saving.value = false; confirming.value = false
    error.value = ''; conflict.value = false
    pendingPatch = null; pendingPrepare = null; pendingConfirm = null; saveInFlight = null; pollCount = 0
  })

  const sourceBlocks = computed<Record<string, SourceBlock>>(() => Object.fromEntries(
    (boundSource.value?.blocks ?? []).map(block => [block.id, block])))
  function bindSource(source: SourceDocument | null): void { boundSource.value = source }

  function poll(planId: string): void {
    clearPoll()
    if (!activity.isActive()) return
    if (pollCount >= 60) { error.value = '策划耗时较长，请刷新状态核实'; return }
    pollCount += 1
    pollTimer = setTimeout(() => { void refresh(planId) }, 2000)
  }

  async function prepare(input: PreparePlanInput = {}, fresh = false): Promise<void> {
    if (preparing.value || saving.value) return
    const sourceId = boundSource.value?.id ?? options.sourceDocumentId()
    const sourceHash = boundSource.value?.contentHash ?? options.sourceContentHash()
    const recipe = options.recipe()
    const draftId = options.draftId()
    if (!draftId || !sourceId || !sourceHash || !recipe) { error.value = '请先保存原稿并选择模板'; return }
    const selectedBlockIds = input.selectedBlockIds ?? boundSource.value?.blocks.map(block => block.id) ?? []
    if (!selectedBlockIds.length || selectedBlockIds.length > 200 || new Set(selectedBlockIds).size !== selectedBlockIds.length) {
      error.value = '请先读取原稿并选择 1～200 个段落'; return
    }
    const payload = { ...input, draftId, expectedDraftVersion: options.draftVersion(), recipe,
      source: { id: sourceId, contentHash: sourceHash }, selectedBlockIds }
    const key = JSON.stringify(payload)
    if (fresh || pendingPrepare?.key !== key) pendingPrepare = { key, requestId: crypto.randomUUID() }
    const isCurrent = guard.capture()
    const requestSequence = ++sequence
    clearPoll(); clearSave()
    preparing.value = true; error.value = ''
    try {
      const plan = await studioPost<VisualPlan>('/api/creation-studio/visual-plans',
        { ...payload, requestId: pendingPrepare.requestId }, 120_000)
      if (!await activity.whenActive() || !isCurrent() || sequence !== requestSequence) return
      current.value = plan
      document.value = plan.document ? clone(plan.document) : null
      conflict.value = false
      pendingPatch = null
      options.onPlanCreated(plan)
      pollCount = 0
      if (plan.status === 'preparing') poll(plan.id)
    } catch (failure) {
      if (await activity.whenActive() && isCurrent() && sequence === requestSequence) error.value = studioErrorMessage(failure)
    } finally { if (isCurrent() && sequence === requestSequence) preparing.value = false }
  }

  async function refresh(planId?: string, discardLocal = false): Promise<void> {
    const id = planId ?? current.value?.id
    if (!id || !activity.isActive()) return
    const isCurrent = guard.capture()
    const isActive = activity.capture()
    const requestSequence = ++sequence
    clearPoll()
    try {
      const plan = await studioRequest<VisualPlan>('/api/creation-studio/visual-plans/' + id)
      if (!isCurrent() || !isActive() || sequence !== requestSequence) return
      const preserve = dirty.value && current.value?.id === id && !discardLocal
      if (!preserve) {
        current.value = plan
        document.value = plan.document ? clone(plan.document) : null
        conflict.value = false
        error.value = ''
        options.onPlanCreated(plan)
      } else if (plan.revision !== current.value?.revision) {
        conflict.value = true
        error.value = '计划已在其他窗口修改，本地编辑已保留；可载入远端版本'
      } else if (current.value) current.value.stale = plan.stale
      if (plan.status === 'preparing') poll(plan.id)
      if (boundSource.value?.id !== plan.source.id) {
        const source = await studioRequest<SourceDocument>('/api/creation-studio/sources/' + plan.source.id)
        if (isCurrent() && isActive() && sequence === requestSequence) bindSource(source)
      }
    } catch (failure) { if (isCurrent() && isActive() && sequence === requestSequence) error.value = studioErrorMessage(failure) }
  }

  function touch(): void {
    if (!activity.isActive() || !current.value || !document.value || !dirty.value || conflict.value) return
    clearSave()
    saveTimer = setTimeout(() => { void flush() }, 800)
  }

  function flush(): Promise<boolean> {
    clearSave()
    if (saveInFlight) return saveInFlight
    if (!activity.isActive()) return Promise.resolve(false)
    if (conflict.value) return Promise.resolve(false)
    if (!current.value || !document.value || !dirty.value) return Promise.resolve(!dirty.value)
    const planId = current.value.id
    const expectedRevision = current.value.revision
    const snapshot = clone(document.value)
    const key = JSON.stringify([planId, expectedRevision, snapshot])
    if (pendingPatch?.key !== key) pendingPatch = { key, requestId: crypto.randomUUID() }
    const requestId = pendingPatch.requestId
    const isCurrent = guard.capture()
    saving.value = true; error.value = ''
    const operation = async () => {
      try {
        const plan = await studioRequest<VisualPlan>('/api/creation-studio/visual-plans/' + planId, {
          method: 'PATCH', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ requestId, expectedRevision, document: snapshot }),
        })
        if (!await activity.whenActive() || !isCurrent() || current.value?.id !== planId) return false
        current.value = plan
        options.onPlanCreated(plan)
        if (same(document.value, snapshot)) document.value = plan.document ? clone(plan.document) : null
        pendingPatch = null
        if (dirty.value) touch()
        return !dirty.value
      } catch (failure) {
        if (await activity.whenActive() && isCurrent() && current.value?.id === planId) {
          error.value = studioErrorMessage(failure)
          conflict.value = failure instanceof StudioHttpError && failure.status === 409
        }
        return false
      } finally {
        if (isCurrent()) { saving.value = false; saveInFlight = null }
      }
    }
    saveInFlight = operation()
    return saveInFlight
  }

  async function confirm(): Promise<boolean> {
    if (confirming.value) return false
    confirming.value = true
    const isCurrent = guard.capture()
    try {
      if (!await flush() || (options.beforeConfirm && !await options.beforeConfirm()) || !isCurrent()) return false
      const plan = current.value
      if (!plan || plan.stale) return false
      const key = JSON.stringify([plan.id, plan.revision, options.draftVersion(), plan.source.contentHash])
      if (pendingConfirm?.key !== key) pendingConfirm = { key, requestId: crypto.randomUUID() }
      const confirmed = await studioPost<VisualPlan>('/api/creation-studio/visual-plans/' + plan.id + '/confirm', {
        requestId: pendingConfirm.requestId, draftId: options.draftId(), expectedDraftVersion: options.draftVersion(),
        expectedRevision: plan.revision, sourceContentHash: plan.source.contentHash,
      })
      if (!await activity.whenActive() || !isCurrent() || current.value?.id !== plan.id) return false
      const preserve = dirty.value
      current.value = confirmed
      if (!preserve) document.value = confirmed.document ? clone(confirmed.document) : null
      return true
    } catch (failure) { if (await activity.whenActive() && isCurrent()) error.value = studioErrorMessage(failure); return false }
    finally { if (isCurrent()) confirming.value = false }
  }

  function renumber(): void { document.value?.items.forEach((item, index) => { item.position = index + 1 }); touch() }
  function moveItem(index: number, direction: -1 | 1): void {
    const items = document.value?.items
    const target = index + direction
    if (!items || !items[index] || target < 0 || target >= items.length) return
    if (items[index].role === 'cover' || items[target].role === 'cover') {
      error.value = '封面须位于首位；可使用“设为封面”更换封面'
      return
    }
    const [moved] = items.splice(index, 1)
    items.splice(target, 0, moved); renumber()
  }
  function removeItem(index: number): string | null {
    const items = document.value?.items
    if (!items?.[index]) return null
    const message = items.length <= 1 ? '至少保留 1 项，不能删除'
      : items[index].role === 'cover' ? '删除封面前，请先把其他页设为封面' : null
    if (message) { error.value = message; return message }
    items.splice(index, 1); renumber(); return null
  }
  function promoteToCover(index: number): void {
    const items = document.value?.items
    const target = items?.[index]
    if (!items || !target || target.role === 'cover') return
    const previous = items.find(item => item.role === 'cover')
    if (previous) {
      previous.role = document.value?.recipe.id === 'article-visuals' ? 'illustration'
        : target.role === 'summary' ? 'summary' : 'content'
      previous.placement = previous.role === 'illustration' ? target.placement : null
    }
    target.role = 'cover'; target.placement = null
    items.splice(index, 1); items.unshift(target); renumber()
  }
  return { current, document, boundSource, sourceBlocks, preparing, saving, confirming, error, dirty, conflict,
    prepare, refresh, restore: refresh, flush, confirm, touch, moveItem, removeItem, promoteToCover,
    bindSource, dismiss: guard.invalidate }
}

export type VisualPlanController = ReturnType<typeof useVisualPlan>
export type { VisualPlanItem }

/** Never silently submit an oversized block. The caller must show the uncovered range. */
export function defaultSelectedBlockIds(blocks: SourceBlock[]): string[] {
  const ids: string[] = []
  let total = 0
  for (const block of blocks) {
    const length = [...block.text].length
    if (ids.length >= 200 || total + length > 8000) break
    ids.push(block.id); total += length
  }
  return ids
}

export async function launchVisualPlan(plan: VisualPlanController, options: {
  draftId: () => string | null; draftVersion: () => number; ensureDraftSaved: () => Promise<boolean>
  setStudioSource: (documentId: string, recipe?: RecipeRef) => void
  sourceDocumentId: () => string | null
}): Promise<void> {
  if (plan.preparing.value || !await options.ensureDraftSaved()) return
  try {
    const draftId = options.draftId()
    if (!draftId) return
    const source = await studioPost<SourceDocument>('/api/creation-studio/sources', {
      requestId: crypto.randomUUID(), draftId, expectedDraftVersion: options.draftVersion(), kind: 'draft-content',
    })
    if (options.draftId() !== draftId) return
    plan.bindSource(source)
    const ids = defaultSelectedBlockIds(source.blocks)
    if (ids.length !== source.blocks.length) {
      plan.error.value = '原稿超过单次 8,000 字符或 200 段上限，请先选择处理范围'
      return
    }
    await plan.prepare({ selectedBlockIds: ids })
  } catch (failure) { plan.error.value = studioErrorMessage(failure) }
}
