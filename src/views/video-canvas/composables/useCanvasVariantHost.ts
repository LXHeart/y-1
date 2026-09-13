import { computed, shallowRef, watch, type ComputedRef, type Ref } from 'vue'
import { isNavigationFailure, type Router } from 'vue-router'
import type { CanvasStoryboard } from '../useVideoCanvas'
import type { CreateVariantResult } from '../../../types/video-canvas'
import { useCanvasVariants } from './useCanvasVariants'

/** All creation paths use confirmed versions and all successful retries use the same navigation path. */
export function useCanvasVariantHost(options: {
  storyboard: Ref<CanvasStoryboard | null>
  storyboardKey: ComputedRef<string>
  draftVersion: () => number | null
  flushBeforeSwitch: () => Promise<boolean>
  epoch?: () => number
  authenticated?: () => boolean
  router: Router
}) {
  const { storyboard, storyboardKey, draftVersion, flushBeforeSwitch, router } = options
  const navigateToVariant = async (nextStoryboardId: string, nextDraftId: string) => {
    const result = await router.replace({ name: 'video-canvas', query: { storyboard: nextStoryboardId, draft: nextDraftId } })
    if (isNavigationFailure(result)) throw new Error('有未保存的修改，已停留在当前方案')
  }
  const session = useCanvasVariants({ storyboardId: storyboardKey, flushBeforeSwitch, navigateToVariant,
    epoch: options.epoch, enabled: options.authenticated })
  watch(() => [storyboardKey.value, options.epoch?.(), options.authenticated?.()], () => { void session.load() }, { immediate: true })
  const identity = () => `${options.epoch?.() ?? 0}:${storyboardKey.value}`
  const createdTarget = shallowRef<{ result: CreateVariantResult; original: string } | null>(null)
  watch(identity, () => { createdTarget.value = null }, { flush: 'sync' })
  async function navigateCreated(result: CreateVariantResult | null, original: string): Promise<void> {
    if (!result || original !== identity()) return
    createdTarget.value = { result, original }
    try { await navigateToVariant(result.variant.storyboardId, result.project.id); createdTarget.value = null }
    catch (err) { session.error.value = err instanceof Error ? err.message : '方案已创建，切换失败' }
  }
  async function createVariant(input: { title: string; shotIds: string[] }): Promise<void> {
    if (session.creating.value || session.hasPendingCreation.value || createdTarget.value) return
    const original = identity()
    if (!(await flushBeforeSwitch())) { session.error.value = '有未保存的修改，尚未创建方案'; return }
    if (identity() !== original) return
    const current = storyboard.value; const version = draftVersion()
    if (!current || current.id !== storyboardKey.value || version == null) return
    if (!input?.shotIds?.length || input.shotIds.some(id => !current.shots.some(shot => shot.id === id))) {
      session.error.value = '请选择当前方案中的镜头'; return
    }
    const result = await session.create({ expectedEditVersion: current.editVersion, expectedDraftVersion: version,
      title: input.title, shotIds: input.shotIds })
    await navigateCreated(result, original)
  }
  async function retryPending(): Promise<void> {
    const original = identity()
    if (!(await flushBeforeSwitch()) || original !== identity()) return
    await navigateCreated(createdTarget.value?.result ?? await session.retryPending(), original)
  }
  async function switchVariant(target: { storyboardId: string }): Promise<void> {
    const summary = session.variants.value.find(item => item.storyboardId === target.storyboardId)
    if (!summary) { session.error.value = '方案列表已变化，请刷新后重试'; return }
    await session.switchTo(summary)
  }
  const host = { variants: session.variants, loading: session.loading, creating: session.creating,
    error: session.error, hasPendingCreation: computed(() => session.hasPendingCreation.value || createdTarget.value !== null) }
  return { host, createVariant, switchVariant, retryPending }
}
