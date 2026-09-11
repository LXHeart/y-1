import { watch } from 'vue'
import type { Ref } from 'vue'
import type { VideoCanvasLayout } from '../../../types/video-canvas'
import type { useCanvasDocument } from './useCanvasDocument'

/**
 * 任务书 #100 C100-09/C100-10：GET null 才用旧轻量布局构建初始文档（§7.3 一次性升级；
 * 失败保留轻量布局不回落覆盖）。装配下沉以守住视图体积门禁。
 */
export function upgradeLegacyCanvasOnBind(
  session: ReturnType<typeof useCanvasDocument>,
  draftId: Ref<string>,
  collectLegacy: () => VideoCanvasLayout | null,
): void {
  watch(
    () => draftId.value,
    (id) => {
      if (!id) return
      void session.load().then((exists) => {
        if (exists || session.revision.value > 0) return
        void session.upgradeFromLegacy(collectLegacy())
      })
    },
    { immediate: true },
  )
}
