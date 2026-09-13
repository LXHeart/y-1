import { computed, getCurrentScope, onScopeDispose, ref, shallowRef, watch, type Ref } from 'vue'
import { request } from '../../../composables/grassland-http'
import type { CreateVariantRequest, CreateVariantResult, VariantSummary } from '../../../types/video-canvas'

export interface UseCanvasVariantsOptions {
  storyboardId: Ref<string>
  epoch?: () => number
  enabled?: () => boolean
  flushBeforeSwitch: () => Promise<boolean>
  navigateToVariant: (storyboardId: string, draftId: string) => Promise<void>
}
interface PendingCreation { storyboardId: string; input: CreateVariantRequest }

export function compareVariantFields(current: VariantSummary, other: VariantSummary, variants: VariantSummary[] = []) {
  const parentTitle = (id: string | null) => id == null ? '—' : variants.find(item => item.storyboardId === id)?.title || '未载入的父方案'
  return [
    { field: '标题', current: current.title, other: other.title },
    { field: '来源版本', current: current.sourceEditVersion == null ? '根方案' : `v${current.sourceEditVersion}`,
      other: other.sourceEditVersion == null ? '根方案' : `v${other.sourceEditVersion}` },
    { field: '父方案', current: parentTitle(current.parentStoryboardId), other: parentTitle(other.parentStoryboardId) },
  ]
}

/** One immutable pending creation per project; list/POST/finally responses share the same account generation. */
export function useCanvasVariants(options: UseCanvasVariantsOptions) {
  const { storyboardId, flushBeforeSwitch, navigateToVariant } = options
  const variants = ref<VariantSummary[]>([])
  const loading = ref(false); const creating = ref(false); const error = ref('')
  const pendingCreation = shallowRef<PendingCreation | null>(null)
  const pendingByProject = new Map<string, PendingCreation>()
  let generation = 0; let listSequence = 0
  const requests = new Set<AbortController>()
  const enabled = () => options.enabled?.() !== false
  const key = () => `${options.epoch?.() ?? 0}:${storyboardId.value}`

  function reset(): void {
    generation++; listSequence++
    requests.forEach(controller => controller.abort()); requests.clear()
    variants.value = []; loading.value = false; creating.value = false; error.value = ''
    pendingCreation.value = enabled() ? pendingByProject.get(key()) ?? null : null
  }
  watch(() => [options.epoch?.(), enabled()], () => { pendingByProject.clear(); reset() }, { flush: 'sync' })
  watch(storyboardId, reset, { flush: 'sync' })
  if (getCurrentScope()) onScopeDispose(() => { reset(); pendingByProject.clear() })

  async function call<T>(url: string, init: RequestInit = {}): Promise<T> {
    const controller = new AbortController(); requests.add(controller)
    const timeout = setTimeout(() => controller.abort(), 20_000)
    try { return await request<T>(url, { ...init, signal: controller.signal }) }
    finally { clearTimeout(timeout); requests.delete(controller) }
  }

  async function load(): Promise<void> {
    const id = storyboardId.value
    if (!id || !enabled()) return
    const ticket = generation; const sequence = ++listSequence
    loading.value = true; error.value = ''
    const current = () => ticket === generation && sequence === listSequence
    try {
      const body = await call<{ items: VariantSummary[] }>(`/api/video-production/storyboards/${encodeURIComponent(id)}/variants`)
      if (current()) variants.value = body.items ?? []
    } catch (err) {
      if (!current()) return
      if ([401, 404].includes((err as { status?: number }).status ?? 0)) variants.value = []
      error.value = `方案列表读取失败：${err instanceof Error ? err.message : '网络异常'}`
    } finally { if (current()) loading.value = false }
  }

  async function submit(pending: PendingCreation): Promise<CreateVariantResult | null> {
    if (creating.value || !enabled() || pending.storyboardId !== storyboardId.value) return null
    const ticket = generation; const projectKey = key()
    pendingByProject.set(projectKey, pending); pendingCreation.value = pending
    creating.value = true; error.value = ''
    try {
      const result = await call<CreateVariantResult>(`/api/video-production/storyboards/${encodeURIComponent(pending.storyboardId)}/variants`, {
        method: 'POST', body: JSON.stringify(pending.input),
      })
      if (ticket !== generation) return null
      if (!result.variant?.storyboardId || !result.project?.id) throw new Error('方案响应不完整，请恢复原请求')
      pendingByProject.delete(projectKey); pendingCreation.value = null
      return result
    } catch (err) {
      if (ticket === generation) error.value = `方案创建未完成（可原键重试）：${err instanceof Error ? err.message : '网络异常'}`
      return null
    } finally { if (ticket === generation) creating.value = false }
  }

  async function create(input: Omit<CreateVariantRequest, 'operationId'> & { operationId?: string }): Promise<CreateVariantResult | null> {
    if (pendingCreation.value) { error.value = '上次创建尚未确认，请先原键重试'; return null }
    const title = input.title.trim()
    if (!title || [...title].length > 60 || !input.shotIds.length || new Set(input.shotIds).size !== input.shotIds.length) {
      error.value = '请填写 1～60 字的方案名称并明确选择镜头'; return null
    }
    if (![input.expectedDraftVersion, input.expectedEditVersion].every(value => Number.isSafeInteger(value) && value > 0)) {
      error.value = '项目版本尚未就绪，请刷新后再创建'; return null
    }
    return submit({ storyboardId: storyboardId.value, input: { ...input, title,
      operationId: input.operationId ?? crypto.randomUUID(), shotIds: [...input.shotIds] } })
  }
  async function retryPending(): Promise<CreateVariantResult | null> {
    const pending = pendingCreation.value
    return pending ? submit(pending) : null
  }
  async function switchTo(target: { storyboardId: string; draftId: string }): Promise<boolean> {
    const ticket = generation
    if (!enabled() || !target.storyboardId || !target.draftId) return false
    if (!(await flushBeforeSwitch())) {
      if (ticket === generation) error.value = '有未保存的修改，已停留在当前方案'
      return false
    }
    if (ticket !== generation) return false
    try { await navigateToVariant(target.storyboardId, target.draftId); return true }
    catch (err) { if (ticket === generation) error.value = err instanceof Error ? err.message : '方案切换失败'; return false }
  }
  return { variants, loading, error, creating, hasPendingCreation: computed(() => pendingCreation.value !== null),
    load, create, retryPending, switchTo,
    compareFields: (current: VariantSummary, other: VariantSummary) => compareVariantFields(current, other, variants.value) }
}
