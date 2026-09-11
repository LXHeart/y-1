<script setup lang="ts">
import { computed, nextTick, onDeactivated, onMounted, onUnmounted, provide, ref, watch } from 'vue'
import ShotNode from './ShotNode.vue'
import CanvasEdge from './CanvasEdge.vue'
import { useCanvasViewport, clampPosition, clampScale } from './useCanvasViewport'
import { useCanvasInteraction } from './composables/useCanvasInteraction'
import { CANVAS_NODE_SIZE } from './useVideoCanvas'
import CanvasReferenceNode from './components/CanvasReferenceNode.vue'
import type { CanvasShot, GroupingBranch } from './useVideoCanvas'
import type { GraphEdge, GraphNode } from './composables/useCanvasGraph'

const props = withDefaults(defineProps<{
  shots: CanvasShot[]
  selectedShotId: string | null
  branches: GroupingBranch[]
  activeBranchId: string | null
  canUndo?: boolean
  canRedo?: boolean
  /** 恢复的初始视口（#100 C100-04 布局恢复；对象引用变化时重新应用）。 */
  initialViewport?: { panX: number; panY: number; scale: number } | null
  /** C100-10：引用/备注节点与投影连线（权威 sequence 线仍按镜序在本板推导）。 */
  referenceNodes?: GraphNode[]
  graphEdges?: GraphEdge[]
  selectedNodeId?: string | null
}>(), {
  referenceNodes: () => [],
  graphEdges: () => [],
  selectedNodeId: null,
})

const emit = defineEmits<{
  (e: 'select', shotId: string): void
  (e: 'drag-move', shotId: string, x: number, y: number): void
  (e: 'move', shotId: string, x: number, y: number, fromX: number, fromY: number): void
  (e: 'undo'): void
  (e: 'redo'): void
  (e: 'viewport-change', viewport: { panX: number; panY: number; scale: number }): void
  (e: 'select-node', nodeId: string): void
  (e: 'reselect-media', nodeId: string): void
}>()

const wrap = ref<HTMLElement | null>(null)
const viewport = useCanvasViewport(props.initialViewport
  ? { panX: props.initialViewport.panX, panY: props.initialViewport.panY, scale: props.initialViewport.scale }
  : {})
const panning = ref(false)

// 布局恢复/持久化（#100 C100-04）：视口上抛（视图收集进 inputs.videoCanvas），引用变化时重放
watch(() => props.initialViewport, next => {
  if (!next) return
  viewport.state.value = {
    panX: clampPosition(next.panX),
    panY: clampPosition(next.panY),
    scale: clampScale(next.scale),
  }
})
watch(() => viewport.state.value, next => {
  emit('viewport-change', { panX: next.panX, panY: next.panY, scale: next.scale })
}, { deep: true })

/** 交互状态机（任务书 #100 C100-02）：scale 换算/键盘微移归它，提交走事件上抛视图记历史。 */
const interaction = useCanvasInteraction({
  scale: () => viewport.state.value.scale,
  onTransientMove(shotId, x, y) {
    emit('drag-move', shotId, x, y)
  },
  onCommit(shotId, from, to) {
    emit('move', shotId, to.x, to.y, from.x, from.y)
  },
  onCancel(shotId, origin) {
    emit('drag-move', shotId, origin.x, origin.y)
  },
})
provide('canvasInteraction', interaction)

// ---- 节点真实包围盒（连线端点不再用固定高度假设；未测得时回落常量） ----
const nodeEls = new Map<string, HTMLElement>()
const nodeBounds = ref<Record<string, { width: number; height: number }>>({})

function registerNode(shotId: string, instance: unknown): void {
  const el = (instance as { $el?: HTMLElement } | null)?.$el
  if (el instanceof HTMLElement) {
    nodeEls.set(shotId, el)
  } else {
    nodeEls.delete(shotId)
  }
}

function boundsOf(shot: CanvasShot): { width: number; height: number } {
  return nodeBounds.value[shot.id] ?? CANVAS_NODE_SIZE
}

function measureNodes(): void {
  const next: Record<string, { width: number; height: number }> = {}
  nodeEls.forEach((el, shotId) => {
    const width = el.offsetWidth
    const height = el.offsetHeight
    next[shotId] = width > 0 && height > 0 ? { width, height } : CANVAS_NODE_SIZE
  })
  // 等值不赋新对象：onUpdated/深度 watch 高频触发，避免自反馈递归渲染
  const current = nodeBounds.value
  const keys = new Set([...Object.keys(current), ...Object.keys(next)])
  for (const key of keys) {
    if (current[key]?.width !== next[key]?.width || current[key]?.height !== next[key]?.height) {
      nodeBounds.value = next
      return
    }
  }
}

watch(() => props.shots, () => void nextTick(measureNodes), { deep: true })
onMounted(measureNodes)

/** 引用节点占位尺寸（端点计算用；真实尺寸由 CSS 决定）。 */
const REF_NODE_WIDTH = 200
const REF_NODE_HEIGHT = 110

function nodeBoundsOf(node: GraphNode): { width: number; height: number } {
  return node.kind === 'shot'
    ? boundsOf(props.shots.find(shot => `shot:${shot.id}` === node.id)
        ?? { ...props.shots[0], id: node.id } as CanvasShot)
    : { width: REF_NODE_WIDTH, height: REF_NODE_HEIGHT }
}

/**
 * 连线端点：源右缘中点 → 目标左缘中点。sequence 按真实节点包围盒（分支视图外的镜头
 * 不连线）；reference/derived-from 由 useCanvasGraph 投影传入，本板只解坐标。
 */
const edges = computed(() => {
  const result: Array<{ from: { x: number; y: number }; to: { x: number; y: number };
    kind: 'sequence' | 'reference' | 'derived-from'; label: string }> = []
  for (let i = 0; i < props.shots.length - 1; i += 1) {
    const a = props.shots[i]
    const b = props.shots[i + 1]
    const sizeA = boundsOf(a)
    const sizeB = boundsOf(b)
    result.push({
      from: { x: a.x + sizeA.width, y: a.y + sizeA.height / 2 },
      to: { x: b.x, y: b.y + sizeB.height / 2 },
      kind: 'sequence',
      label: '',
    })
  }
  const byId = new Map<string, GraphNode>()
  for (const node of props.referenceNodes) byId.set(node.id, node)
  for (const shot of props.shots) {
    byId.set(`shot:${shot.id}`, {
      id: `shot:${shot.id}`, kind: 'shot', refType: 'shot', refId: shot.id, label: null, text: null,
      x: shot.x, y: shot.y, unavailableReason: null,
    })
  }
  for (const edge of props.graphEdges) {
    if (edge.kind === 'sequence') continue // 镜序线只由权威推导（防伪造）
    const from = byId.get(edge.fromNodeId)
    const to = byId.get(edge.toNodeId)
    if (!from || !to) continue
    const sizeFrom = nodeBoundsOf(from)
    const sizeTo = nodeBoundsOf(to)
    result.push({
      from: { x: from.x + sizeFrom.width, y: from.y + sizeFrom.height / 2 },
      to: { x: to.x, y: to.y + sizeTo.height / 2 },
      kind: edge.kind,
      label: from.label ?? from.text ?? '',
    })
  }
  return result
})

const transform = computed(() =>
  `translate(${viewport.state.value.panX}px, ${viewport.state.value.panY}px) scale(${viewport.state.value.scale})`)

function onWheel(event: WheelEvent): void {
  event.preventDefault()
  const rect = wrap.value?.getBoundingClientRect()
  const px = rect ? event.clientX - rect.left : 0
  const py = rect ? event.clientY - rect.top : 0
  viewport.zoomAt(px, py, event.deltaY < 0 ? 1.12 : 1 / 1.12)
}

// ---- 空白画布平移（左键/中键；pointercancel/失活终止，不提交半次手势） ----
let panCleanup: (() => void) | null = null

function stopPan(): void {
  panCleanup?.()
  panCleanup = null
  panning.value = false
}

function onPointerDown(event: PointerEvent): void {
  if (event.button !== 0 && event.button !== 1) return
  // 节点内按下（含其子元素）交给节点自己的手势；空白处才平移画布
  if ((event.target as HTMLElement).closest('.canvas-node')) return
  if (event.button === 1) event.preventDefault()
  panning.value = true
  const startX = event.clientX
  const startY = event.clientY
  const originX = viewport.state.value.panX
  const originY = viewport.state.value.panY
  const move = (ev: PointerEvent): void => {
    viewport.panTo(originX + ev.clientX - startX, originY + ev.clientY - startY)
  }
  const up = (): void => stopPan()
  const cancel = (): void => stopPan()
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
  window.addEventListener('pointercancel', cancel)
  panCleanup = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', up)
    window.removeEventListener('pointercancel', cancel)
  }
}

/** 手势中的 Escape：终止拖拽并回起始位置（§8.2 取消当前手势）。 */
function onWindowKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && interaction.isDragActive()) {
    interaction.dragCancel()
  }
}

onMounted(() => window.addEventListener('keydown', onWindowKeydown))
onUnmounted(() => {
  window.removeEventListener('keydown', onWindowKeydown)
  stopPan()
})
onDeactivated(() => {
  window.removeEventListener('keydown', onWindowKeydown)
  stopPan()
  interaction.dragCancel()
})

function fitView(): void {
  if (!props.shots.length || !wrap.value) return
  const bounds = props.shots.reduce((acc, shot) => {
    const size = boundsOf(shot)
    return {
      minX: Math.min(acc.minX, shot.x),
      minY: Math.min(acc.minY, shot.y),
      maxX: Math.max(acc.maxX, shot.x + size.width),
      maxY: Math.max(acc.maxY, shot.y + size.height),
    }
  }, { minX: Infinity, minY: Infinity, maxX: -Infinity, maxY: -Infinity })
  viewport.fit(bounds, wrap.value.clientWidth, wrap.value.clientHeight)
}

/** 以视口中心为锚缩放（工具栏/外部驱动同款）。 */
function zoom(factor: number): void {
  if (!wrap.value) return
  viewport.zoomAt(wrap.value.clientWidth / 2, wrap.value.clientHeight / 2, factor)
}

/** 把画布逻辑坐标点平移到视口中心（外部驱动/harness 用）。 */
function centerOn(x: number, y: number): void {
  if (!wrap.value) return
  viewport.panTo(wrap.value.clientWidth / 2 - x * viewport.state.value.scale,
    wrap.value.clientHeight / 2 - y * viewport.state.value.scale)
}

defineExpose({ fitView, zoom, centerOn, scale: viewport.state })
</script>

<template>
  <div
    ref="wrap"
    class="canvas-board gl-zone"
    :class="{ 'canvas-panning': panning }"
    data-test="canvas-board"
    @wheel="onWheel"
    @pointerdown="onPointerDown"
  >
    <div class="canvas-layer" role="listbox" aria-label="分镜节点" :style="{ transform }">
      <svg class="canvas-edges" data-test="canvas-edges">
        <CanvasEdge v-for="(edge, index) in edges" :key="index" v-bind="edge" />
      </svg>
      <ShotNode
        v-for="shot in shots"
        :key="shot.id"
        :ref="(instance: unknown) => registerNode(shot.id, instance)"
        :shot="shot"
        :selected="shot.id === selectedShotId"
        @select="emit('select', $event)"
      />
      <CanvasReferenceNode
        v-for="node in referenceNodes"
        :key="node.id"
        :id="node.id"
        :kind="node.kind === 'note' ? 'note' : 'media'"
        :label="node.label"
        :text="node.text"
        :x="node.x"
        :y="node.y"
        :selected="node.id === selectedNodeId"
        :unavailable-reason="node.unavailableReason"
        @select="emit('select-node', $event)"
        @request-reselect="emit('reselect-media', $event)"
      />
    </div>
    <div class="canvas-toolbar">
      <button
        type="button"
        data-test="canvas-undo"
        :disabled="!canUndo"
        aria-label="撤销移动"
        @click="emit('undo')"
      >撤销</button>
      <button
        type="button"
        data-test="canvas-redo"
        :disabled="!canRedo"
        aria-label="重做移动"
        @click="emit('redo')"
      >重做</button>
      <button type="button" data-test="canvas-zoom-out" @click="wrap && viewport.zoomAt(wrap.clientWidth / 2, wrap.clientHeight / 2, 1 / 1.2)">−</button>
      <span class="gl-num canvas-zoom-label" data-test="canvas-zoom-label">{{ Math.round(viewport.state.value.scale * 100) }}%</span>
      <button type="button" data-test="canvas-zoom-in" @click="wrap && viewport.zoomAt(wrap.clientWidth / 2, wrap.clientHeight / 2, 1.2)">+</button>
      <button type="button" data-test="canvas-fit" @click="fitView">适配</button>
      <span v-if="activeBranchId" class="badge badge-accent canvas-branch-chip" data-test="canvas-branch-chip">
        分支：{{ branches.find(branch => branch.id === activeBranchId)?.name ?? '' }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.canvas-board {
  position: relative;
  flex: 1;
  overflow: hidden;
  cursor: grab;
  user-select: none;
  background-image: radial-gradient(color-mix(in srgb, var(--color-text-secondary) 22%, transparent) 1px, transparent 1px);
  background-size: 24px 24px;
}
.canvas-panning { cursor: grabbing; }
.canvas-layer { position: absolute; inset: 0; transform-origin: 0 0; }
.canvas-edges { position: absolute; inset: 0; overflow: visible; pointer-events: none; }
.canvas-toolbar {
  position: absolute;
  right: var(--space-md);
  bottom: var(--space-md);
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}
.canvas-toolbar button {
  position: relative;
  padding: var(--space-2xs, 2px) var(--space-xs);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--surface-1);
  color: var(--color-text);
  cursor: pointer;
}
/* 触控目标 ≥44×44（§8.4）：伪元素扩大命中区，不改变视觉尺寸 */
.canvas-toolbar button::before {
  content: '';
  position: absolute;
  inset: -10px;
}
.canvas-toolbar button:disabled { opacity: 0.45; cursor: not-allowed; }
.canvas-toolbar button:not(:disabled):hover { border-color: var(--color-border-hover); }
.canvas-zoom-label { font-size: var(--text-xs); color: var(--color-text-secondary); min-width: 42px; text-align: center; }
.canvas-branch-chip { margin-left: var(--space-xs); }
</style>
