import { watch } from 'vue'
import type { Ref } from 'vue'
import type { VideoCanvasLayout } from '../../../types/video-canvas'
import type { useCanvasDocument } from './useCanvasDocument'
import type { CanvasStoryboard } from '../useVideoCanvas'
import type { CanvasDocumentBody } from '../../../types/video-canvas'
import type { useCanvasHistory } from './useCanvasHistory'
import { clampPosition, clampScale } from '../useCanvasViewport'

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
      void session.load().then((outcome) => {
        if (id !== draftId.value || outcome !== 'missing' || session.revision.value > 0) return
        void session.upgradeFromLegacy(collectLegacy())
      })
    },
    { immediate: true },
  )
}

/** Project layout projection: canonical content is merged, existing coordinates remain authoritative. */
export function useCanvasDocumentLayout(options: {
  session: ReturnType<typeof useCanvasDocument>
  storyboard: Ref<CanvasStoryboard | null>
  activeBranchId: Ref<string | null>
  viewport: Ref<{ panX: number; panY: number; scale: number }>
  restoredViewport: Ref<{ panX: number; panY: number; scale: number } | null>
  history: ReturnType<typeof useCanvasHistory>
  moveShot: (id: string, x: number, y: number) => void
}) {
  const { session, storyboard, activeBranchId, viewport, restoredViewport, history, moveShot } = options
  function restore(body: CanvasDocumentBody): void {
    if (body.storyboardId !== storyboard.value?.id || body.schemaVersion !== 1) return
    viewport.value = { panX: clampPosition(body.viewport.panX), panY: clampPosition(body.viewport.panY), scale: clampScale(body.viewport.scale) }
    restoredViewport.value = { ...viewport.value }
    for (const node of body.nodes) {
      if (node.kind === 'shot' && node.refId) moveShot(node.refId, node.x, node.y)
    }
    activeBranchId.value = body.activeBranchId
  }
  watch(() => session.document.value, body => { if (body) restore(body) })
  watch(() => storyboard.value?.shots.map(shot => shot.id).join(','), () => {
    if (session.document.value) { restore(session.document.value); queue() }
  })

  function collect(): CanvasDocumentBody | null {
    const body = session.document.value
    if (!body || body.schemaVersion !== 1 || body.storyboardId !== storyboard.value?.id) return null
    const copy = JSON.parse(JSON.stringify(body)) as CanvasDocumentBody
    const byId = new Map(copy.nodes.map(node => [node.id, node]))
    for (const shot of storyboard.value.shots) {
      const id = `shot:${shot.id}`
      const node = byId.get(id)
      if (node) { node.x = clampPosition(shot.x); node.y = clampPosition(shot.y) }
      else copy.nodes.push({ id, kind: 'shot', refType: 'shot', refId: shot.id, label: null, text: null,
        x: clampPosition(shot.x), y: clampPosition(shot.y) })
    }
    copy.viewport = { ...viewport.value }
    copy.activeBranchId = activeBranchId.value
    return copy
  }
  function queue(): void {
    const body = collect()
    if (body) session.queue(body)
  }
  function applyHistory(changes: ReturnType<typeof history.undo>): void {
    if (!changes) return
    if (history.restoredDocument.value) {
      session.queue(history.restoredDocument.value)
      restore(history.restoredDocument.value)
    } else {
      for (const change of changes) moveShot(change.shotId, change.to.x, change.to.y)
      queue()
    }
  }
  function viewportChanged(next: { panX: number; panY: number; scale: number }): void {
    const before = session.document.value
    viewport.value = next
    const after = collect()
    if (before && after) history.recordDocument(before, after)
    queue()
  }
  return { queue, applyHistory, viewportChanged, collect, restore, flush: session.flush }
}
