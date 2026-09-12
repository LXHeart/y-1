import { watch } from 'vue'
import type { Ref } from 'vue'
import type { VideoCanvasLayout } from '../../../types/video-canvas'
import type { useCanvasDocument } from './useCanvasDocument'

/**
 * 任务书 #100 C100-09/C100-10：GET null 才用旧轻量布局构建初始文档（§7.3 一次性升级；
 * 失败保留轻量布局不回落覆盖）。装配下沉以守住视图体积门禁。
 *
 * ready（分镜已载入）前不升级：绑定先于分镜载入完成时 fallbackShots 为空，会把
 * 「无 shot 节点」的空文档固化为权威（C100-19 实测——AI 助手图投影零可选节点）。
 * 就绪信号翻转后重查一次：文档已存在则原样采纳（幂等）。
 */
export function upgradeLegacyCanvasOnBind(
  session: ReturnType<typeof useCanvasDocument>,
  draftId: Ref<string>,
  collectLegacy: () => VideoCanvasLayout | null,
  ready?: Ref<boolean>,
): void {
  watch(
    () => [draftId.value, ready?.value ?? true] as const,
    ([id, isReady]) => {
      if (!id || !isReady) return
      void session.load().then((exists) => {
        if (exists || session.revision.value > 0) return
        void session.upgradeFromLegacy(collectLegacy())
      })
    },
    { immediate: true },
  )
}
