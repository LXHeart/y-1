import { watch } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import type { Router } from 'vue-router'
import type { CanvasStoryboard } from '../useVideoCanvas'
import { useCanvasVariants } from './useCanvasVariants'

/**
 * 任务书 #100 C100-19：独立方案装配（视图体积门禁下沉）。
 *
 * 包装 useCanvasVariants 会话 + 载入跟随 + 创建/切换动作：
 * - 载入：storyboard key 变化即重拉方案谱系（绑定时 immediate）。
 * - 创建：title 缺省按谱系序号（C100-14 title 必填）；成功导航到新方案深链。
 * - 切换：draftId 从谱系行取（面板只上抛 storyboardId）；flush 失败停留当前方案。
 */
export function useCanvasVariantHost(options: {
  storyboard: Ref<CanvasStoryboard | null>
  storyboardKey: ComputedRef<string>
  draftVersion: () => number | null
  flushBeforeSwitch: () => Promise<boolean>
  router: Router
}) {
  const { storyboard, storyboardKey, draftVersion, flushBeforeSwitch, router } = options

  const session = useCanvasVariants({
    storyboardId: storyboardKey,
    flushBeforeSwitch,
    navigateToVariant: async (nextStoryboardId, nextDraftId) => {
      await router.replace({
        name: 'video-canvas',
        query: { storyboard: nextStoryboardId, draft: nextDraftId },
      })
    },
  })

  watch(storyboardKey, (id) => {
    if (id) void session.load()
  }, { immediate: true })

  async function createVariant(): Promise<void> {
    const current = storyboard.value
    if (!current) return
    const result = await session.create({
      expectedEditVersion: current.editVersion,
      expectedDraftVersion: draftVersion() ?? 1,
      title: `独立方案 ${session.variants.value.length}`,
      shotIds: current.shots.map((shot) => shot.id),
    })
    if (result) {
      await router.replace({
        name: 'video-canvas',
        query: { storyboard: result.variant.storyboardId, draft: result.project.id },
      })
    }
  }

  async function switchVariant(target: { storyboardId: string }): Promise<void> {
    const summary = session.variants.value.find(
      (item) => item.storyboardId === target.storyboardId,
    )
    await session.switchTo({
      storyboardId: target.storyboardId,
      draftId: summary?.draftId ?? '',
    })
  }

  const host = {
    variants: session.variants,
    loading: session.loading,
    creating: session.creating,
    error: session.error,
    hasPendingCreation: session.hasPendingCreation,
  }

  return { host, createVariant, switchVariant, retryPending: session.retryPending }
}
