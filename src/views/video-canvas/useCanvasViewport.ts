import { ref } from 'vue'

/**
 * 画布视口（任务书 #66 卡C2）：transform 缩放（0.25–2.5）+ 平移 + 滚轮/触控板。
 * 纯逻辑 composable——变换计算与钳制可单测；DOM 事件绑定在 CanvasBoard。
 */
export const CANVAS_MIN_SCALE = 0.25
export const CANVAS_MAX_SCALE = 2.5

export interface ViewportState {
  scale: number
  panX: number
  panY: number
}

export interface Bounds {
  minX: number
  minY: number
  maxX: number
  maxY: number
}

export function clampScale(scale: number): number {
  if (!Number.isFinite(scale)) return 1
  return Math.min(CANVAS_MAX_SCALE, Math.max(CANVAS_MIN_SCALE, scale))
}

/** 围绕画布内锚点（px, py 为视口内像素坐标）缩放：锚点在变换前后保持不动。 */
export function zoomViewport(state: ViewportState, px: number, py: number, factor: number): ViewportState {
  const next = clampScale(state.scale * factor)
  const k = next / state.scale
  return {
    scale: next,
    panX: px - (px - state.panX) * k,
    panY: py - (py - state.panY) * k,
  }
}

/** 适配视野：节点包围盒留 padding 后整体落入视口。 */
export function fitViewport(state: ViewportState, bounds: Bounds, viewportWidth: number,
  viewportHeight: number, padding = 40): ViewportState {
  const width = Math.max(1, bounds.maxX - bounds.minX)
  const height = Math.max(1, bounds.maxY - bounds.minY)
  const scale = clampScale(Math.min(
    (viewportWidth - padding * 2) / width,
    (viewportHeight - padding * 2) / height))
  return {
    scale,
    panX: -bounds.minX * scale + padding,
    panY: -bounds.minY * scale + padding,
  }
}

/** 视口像素坐标 → 画布逻辑坐标（节点定位/命中换算）。 */
export function screenToCanvas(state: ViewportState, px: number, py: number): { x: number; y: number } {
  return { x: (px - state.panX) / state.scale, y: (py - state.panY) / state.scale }
}

export function useCanvasViewport(initial: Partial<ViewportState> = {}) {
  const state = ref<ViewportState>({ scale: 1, panX: 0, panY: 0, ...initial })
  return {
    state,
    zoomAt(px: number, py: number, factor: number): void {
      state.value = zoomViewport(state.value, px, py, factor)
    },
    panBy(dx: number, dy: number): void {
      state.value = { ...state.value, panX: state.value.panX + dx, panY: state.value.panY + dy }
    },
    panTo(panX: number, panY: number): void {
      state.value = { ...state.value, panX, panY }
    },
    fit(bounds: Bounds, viewportWidth: number, viewportHeight: number): void {
      state.value = fitViewport(state.value, bounds, viewportWidth, viewportHeight)
    },
    reset(): void {
      state.value = { scale: 1, panX: 0, panY: 0 }
    },
  }
}
