import { ref } from 'vue'
import { clampPosition } from '../useCanvasViewport'

/**
 * 画布交互状态机（任务书 #100 C100-02，§8.2）：节点拖拽（屏幕位移÷scale=逻辑位移，
 * pointercancel/lostpointercapture 回起始位置不提交半次拖拽）与键盘微移（方向键 8 /
 * Shift 24 逻辑单位，取根 spacing token）。纯逻辑可单测；DOM 绑定在 CanvasBoard/ShotNode。
 */

/** 这些目标内的按下不启动拖拽（§8.2），也保留其原生输入/播放语义。 */
const INTERACTIVE_SELECTOR = 'button,input,textarea,select,a,video,[contenteditable="true"],[data-no-drag]'

export function isInteractiveTarget(target: EventTarget | null): boolean {
  if (!target || typeof (target as HTMLElement).closest !== 'function') return false
  return !!(target as HTMLElement).closest(INTERACTIVE_SELECTOR)
}

/** 从根 spacing token 读取键盘步长（--space-xs=8 / --space-lg=24），读不到回退缺省。 */
export function spacingTokenStep(tokenName: '--space-xs' | '--space-lg', fallback: number): number {
  if (typeof window === 'undefined' || !window.document?.documentElement) return fallback
  const raw = window.getComputedStyle(window.document.documentElement).getPropertyValue(tokenName).trim()
  const value = Number.parseFloat(raw)
  return Number.isFinite(value) && value > 0 ? value : fallback
}

export const NUDGE_BASE_STEP = 8
export const NUDGE_LARGE_STEP = 24

export interface ActiveDrag {
  shotId: string
  pointerId: number
  startX: number
  startY: number
  originX: number
  originY: number
}

export interface CanvasInteractionOptions {
  scale: () => number
  onTransientMove(shotId: string, x: number, y: number): void
  onCommit(shotId: string, from: { x: number; y: number }, to: { x: number; y: number }): void
  onCancel(shotId: string, origin: { x: number; y: number }): void
}

export function useCanvasInteraction(options: CanvasInteractionOptions) {
  const activeDrag = ref<ActiveDrag | null>(null)

  /** 手柄按下尝试开拽：非左键/交互目标内/已有手势在途时拒绝（返回 false 由调用方保留原生行为）。 */
  function beginNodeDrag(shotId: string, event: PointerEvent, originX: number, originY: number): boolean {
    if (event.button !== 0) return false
    if (isInteractiveTarget(event.target)) return false
    if (activeDrag.value) return false
    activeDrag.value = {
      shotId,
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      originX,
      originY,
    }
    return true
  }

  /** 拖拽中的瞬时逻辑坐标（clamp 到 ±100000）；视觉 = 逻辑×scale，与屏幕位移跟手一致。 */
  function dragMove(event: PointerEvent): void {
    const drag = activeDrag.value
    if (!drag || drag.pointerId !== event.pointerId) return
    const scale = options.scale() || 1
    options.onTransientMove(drag.shotId,
      clampPosition(drag.originX + (event.clientX - drag.startX) / scale),
      clampPosition(drag.originY + (event.clientY - drag.startY) / scale))
  }

  /** 松手：只提交一次最终逻辑坐标（携带起点供历史记录）。 */
  function dragEnd(event: PointerEvent): void {
    const drag = activeDrag.value
    if (!drag) return
    if (event && event.pointerId !== undefined && drag.pointerId !== event.pointerId) return
    activeDrag.value = null
    const scale = options.scale() || 1
    const to = {
      x: clampPosition(drag.originX + (event.clientX - drag.startX) / scale),
      y: clampPosition(drag.originY + (event.clientY - drag.startY) / scale),
    }
    options.onCommit(drag.shotId, { x: drag.originX, y: drag.originY }, to)
  }

  /** pointercancel / lostpointercapture / Escape：终止手势，不提交半次拖拽——回起始位置。 */
  function dragCancel(event?: { pointerId?: number }): void {
    const drag = activeDrag.value
    if (!drag) return
    if (event && event.pointerId !== undefined && drag.pointerId !== event.pointerId) return
    activeDrag.value = null
    options.onCancel(drag.shotId, { x: drag.originX, y: drag.originY })
  }

  /** 键盘微移步长（token 化）：Shift 用大步。 */
  function nudgeStep(large: boolean): number {
    return large
      ? spacingTokenStep('--space-lg', NUDGE_LARGE_STEP)
      : spacingTokenStep('--space-xs', NUDGE_BASE_STEP)
  }

  /** 方向键移动：返回新逻辑坐标（clamp）；非方向键返回 null（删除键不删业务实体——无操作）。 */
  function keyboardNudge(key: string, shiftKey: boolean, originX: number,
    originY: number): { x: number; y: number } | null {
    const step = nudgeStep(shiftKey)
    switch (key) {
      case 'ArrowUp':
        return { x: originX, y: clampPosition(originY - step) }
      case 'ArrowDown':
        return { x: originX, y: clampPosition(originY + step) }
      case 'ArrowLeft':
        return { x: clampPosition(originX - step), y: originY }
      case 'ArrowRight':
        return { x: clampPosition(originX + step), y: originY }
      default:
        return null
    }
  }

  /** 键盘微移提交（节点 keydown 直调）：命中方向键时按 token 步长经 onCommit 落位并返回 true。 */
  function commitKeyboardMove(shotId: string, key: string, shiftKey: boolean, originX: number,
    originY: number): boolean {
    const next = keyboardNudge(key, shiftKey, originX, originY)
    if (!next) return false
    options.onCommit(shotId, { x: originX, y: originY }, next)
    return true
  }

  return {
    activeDrag,
    beginNodeDrag,
    dragMove,
    dragEnd,
    dragCancel,
    keyboardNudge,
    nudgeStep,
    commitKeyboardMove,
    isDragActive: () => activeDrag.value !== null,
  }
}
