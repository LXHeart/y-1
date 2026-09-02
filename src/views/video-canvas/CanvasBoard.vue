<script setup lang="ts">
import { computed, ref } from 'vue'
import ShotNode from './ShotNode.vue'
import CanvasEdge from './CanvasEdge.vue'
import { useCanvasViewport } from './useCanvasViewport'
import { CANVAS_NODE_SIZE } from './useVideoCanvas'
import type { CanvasShot, GroupingBranch } from './useVideoCanvas'

const props = defineProps<{
  shots: CanvasShot[]
  selectedShotId: string | null
  branches: GroupingBranch[]
  activeBranchId: string | null
}>()

const emit = defineEmits<{
  (e: 'select', shotId: string): void
  (e: 'move', shotId: string, x: number, y: number): void
}>()

const wrap = ref<HTMLElement | null>(null)
const viewport = useCanvasViewport()
const panning = ref(false)

/** 顺序连线端点：源右缘中点 → 目标左缘中点（分支视图外的镜头不连线）。 */
const edges = computed(() => {
  const result: Array<{ from: { x: number; y: number }; to: { x: number; y: number }; dashed: boolean }> = []
  for (let i = 0; i < props.shots.length - 1; i += 1) {
    const a = props.shots[i]
    const b = props.shots[i + 1]
    result.push({
      from: { x: a.x + CANVAS_NODE_SIZE.width, y: a.y + CANVAS_NODE_SIZE.height / 2 },
      to: { x: b.x, y: b.y + CANVAS_NODE_SIZE.height / 2 },
      dashed: false,
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

function onPointerDown(event: PointerEvent): void {
  if (event.button !== 0 && event.button !== 1) return
  // 节点内按下（含其子元素）交给节点自己的拖拽；空白处才平移画布
  if ((event.target as HTMLElement).closest('.canvas-node')) return
  panning.value = true
  const startX = event.clientX
  const startY = event.clientY
  const originX = viewport.state.value.panX
  const originY = viewport.state.value.panY
  const move = (ev: PointerEvent): void => {
    viewport.panTo(originX + ev.clientX - startX, originY + ev.clientY - startY)
  }
  const up = (): void => {
    panning.value = false
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', up)
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
}

function fitView(): void {
  if (!props.shots.length || !wrap.value) return
  const bounds = props.shots.reduce((acc, shot) => ({
    minX: Math.min(acc.minX, shot.x),
    minY: Math.min(acc.minY, shot.y),
    maxX: Math.max(acc.maxX, shot.x + CANVAS_NODE_SIZE.width),
    maxY: Math.max(acc.maxY, shot.y + CANVAS_NODE_SIZE.height),
  }), { minX: Infinity, minY: Infinity, maxX: -Infinity, maxY: -Infinity })
  viewport.fit(bounds, wrap.value.clientWidth, wrap.value.clientHeight)
}

defineExpose({ fitView, scale: viewport.state })
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
    <div class="canvas-layer" :style="{ transform }">
      <svg class="canvas-edges" data-test="canvas-edges">
        <CanvasEdge v-for="(edge, index) in edges" :key="index" v-bind="edge" />
      </svg>
      <ShotNode
        v-for="shot in shots"
        :key="shot.id"
        :shot="shot"
        :selected="shot.id === selectedShotId"
        @select="emit('select', $event)"
        @drag-end="(id, x, y) => emit('move', id, x, y)"
      />
    </div>
    <div class="canvas-toolbar">
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
.canvas-zoom-label { font-size: var(--text-xs); color: var(--color-text-secondary); min-width: 42px; text-align: center; }
.canvas-branch-chip { margin-left: var(--space-xs); }
</style>
