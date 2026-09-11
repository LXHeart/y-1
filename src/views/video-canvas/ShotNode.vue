<script setup lang="ts">
import { computed, inject, ref } from 'vue'
import type { ComputedRef } from 'vue'
import type { CanvasShot } from './useVideoCanvas'
import type { useCanvasInteraction } from './composables/useCanvasInteraction'

const props = defineProps<{
  shot: CanvasShot
  selected: boolean
}>()

const emit = defineEmits<{
  (e: 'select', shotId: string): void
}>()

/** CanvasBoard 提供的交互状态机（拖拽换算/键盘微移归它，节点只做 DOM 手势绑定）。 */
const interaction = inject<ReturnType<typeof useCanvasInteraction> | null>('canvasInteraction', null)

/** 视图提供的任务选片映射（#100 C100-06）：当前采用高亮与缩略预览；无任务时缺省。 */
const taskSelection = inject<ComputedRef<Record<string, string>> | null>('canvasTaskSelection', null)
const adoptedTake = computed(() => {
  const adoptedId = taskSelection?.value[props.shot.id]
  return adoptedId ? props.shot.takes.find(take => take.id === adoptedId) ?? null : null
})

const nodeRoot = ref<HTMLElement | null>(null)

function bestScore(shot: CanvasShot): number | null {
  const scored = shot.takes.map(take => take.score).filter((score): score is number => score != null)
  return scored.length ? Math.max(...scored) : null
}

/** 节点任意处按下：选中（拖拽只经手柄启动；缩略预览 draggable=false 不阻冒泡，点预览同样选中）。 */
function onNodePointerDown(event: PointerEvent, shot: CanvasShot): void {
  if (event.button !== 0) return
  if (interaction?.isDragActive()) return
  emit('select', shot.id)
}

/**
 * 独立拖拽手柄（§8.2）：只有手柄启动拖拽；手势期间监听挂在 window（capture 失效也跟手），
 * pointercancel/lostpointercapture/Escape 终止并回到起始位置，pointerup 只提交一次逻辑坐标。
 */
function onHandlePointerDown(event: PointerEvent, shot: CanvasShot): void {
  if (!interaction || event.button !== 0) return
  event.stopPropagation()
  emit('select', shot.id)
  if (!interaction.beginNodeDrag(shot.id, event, shot.x, shot.y)) return
  const handle = event.currentTarget as HTMLElement
  try {
    handle.setPointerCapture?.(event.pointerId)
  } catch {
    // 无 capture 环境回落 window 监听
  }
  const cleanup = (): void => {
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
    window.removeEventListener('pointercancel', onCancel)
    handle.removeEventListener('lostpointercapture', onLostCapture)
  }
  const onMove = (ev: PointerEvent): void => interaction.dragMove(ev)
  const onUp = (ev: PointerEvent): void => {
    cleanup()
    interaction.dragEnd(ev)
  }
  const onCancel = (ev: PointerEvent): void => {
    cleanup()
    interaction.dragCancel(ev)
  }
  const onLostCapture = (): void => {
    cleanup()
    interaction.dragCancel()
  }
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
  window.addEventListener('pointercancel', onCancel)
  handle.addEventListener('lostpointercapture', onLostCapture)
}

/** 键盘（§8.2）：Enter/Space 选中；方向键 8 / Shift 24 逻辑单位移动（阻止页面滚动）。 */
function onKeydown(event: KeyboardEvent, shot: CanvasShot): void {
  if (!interaction) return
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    emit('select', shot.id)
    return
  }
  if (event.key === 'Escape') {
    // 手势取消由画布层统一处理；节点层停住 Escape 防止冒泡触发删除类默认行为
    event.stopPropagation()
    return
  }
  if (event.key.startsWith('Arrow')) {
    if (interaction.isDragActive()) {
      event.preventDefault()
      return
    }
    if (interaction.commitKeyboardMove(shot.id, event.key, event.shiftKey, shot.x, shot.y)) {
      event.preventDefault()
    }
  }
}
</script>

<template>
  <div
    ref="nodeRoot"
    class="canvas-node glass-card"
    :class="{ 'canvas-node-selected': selected }"
    :data-test="`canvas-node-${shot.seq}`"
    :style="{ left: `${shot.x}px`, top: `${shot.y}px` }"
    role="option"
    :aria-selected="selected"
    :aria-label="`镜头 ${shot.seq}：${shot.visual}`"
    tabindex="0"
    @pointerdown="onNodePointerDown($event, shot)"
    @keydown="onKeydown($event, shot)"
  >
    <div
      class="node-head node-drag-handle"
      data-test="canvas-drag-handle"
      aria-label="拖拽移动镜头"
      title="拖拽移动"
      @pointerdown="onHandlePointerDown($event, shot)"
    >
      <span class="badge badge-accent" :data-test="`canvas-node-seq-${shot.seq}`">镜头 {{ shot.seq }}</span>
      <span class="node-meta gl-num">{{ shot.plannedSeconds }}s · {{ shot.cameraMove }}</span>
    </div>
    <p class="node-visual">{{ shot.visual }}</p>
    <p class="node-narration">{{ shot.narration }}</p>
    <video
      v-if="adoptedTake?.url"
      class="node-preview"
      :src="adoptedTake.url"
      muted
      playsinline
      preload="metadata"
      draggable="false"
      :aria-label="`镜头 ${shot.seq} 当前采用候选 ${adoptedTake.takeNo} 的缩略预览`"
      :data-test="`canvas-node-preview-${shot.seq}`"
    ></video>
    <div v-if="shot.takes.length" class="node-takes" :data-test="`canvas-takes-${shot.seq}`">
      <span
        v-for="take in shot.takes"
        :key="take.id"
        class="node-take"
        :class="{ 'node-take-selectable': take.selectable, 'node-take-adopted': take.id === adoptedTake?.id }"
        :title="take.id === adoptedTake?.id
          ? `当前采用：候选 ${take.takeNo}${take.score != null ? ` · 质检 ${take.score}` : ''}`
          : take.selectable ? `候选 ${take.takeNo}${take.score != null ? ` · 质检 ${take.score}` : ''}` : take.status"
      >{{ take.takeNo }}<template v-if="take.score != null"> · {{ take.score }}</template></span>
    </div>
    <span
      v-if="adoptedTake"
      class="badge badge-success node-adopted"
      :data-test="`canvas-node-adopted-${shot.seq}`"
    >已采用 {{ adoptedTake.takeNo }}<template v-if="adoptedTake.score != null"> · {{ adoptedTake.score }}</template></span>
    <span v-else-if="bestScore(shot) != null" class="badge badge-warning node-score">质检 {{ bestScore(shot) }}</span>
  </div>
</template>

<style scoped>
.canvas-node {
  position: absolute;
  width: 232px;
  padding: var(--space-sm) var(--space-md);
  user-select: none;
  touch-action: none;
}
.canvas-node:focus-visible {
  outline: 2px solid var(--color-accent);
  outline-offset: 2px;
}
.canvas-node-selected {
  border-color: var(--color-accent);
  box-shadow: var(--shadow-glow);
}
.node-head {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  margin-bottom: var(--space-xs);
  cursor: grab;
}
.node-head:active { cursor: grabbing; }
.node-meta { margin-left: auto; font-size: var(--text-xs); color: var(--color-text-secondary); }
.node-visual {
  font-size: var(--text-sm);
  color: var(--color-text);
  margin: 0 0 var(--space-2xs, 2px);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.node-narration {
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
  margin: 0 0 var(--space-xs);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.node-takes { display: flex; gap: var(--space-2xs, 2px); flex-wrap: wrap; }
.node-take {
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 0 var(--space-xs);
}
.node-take-selectable { color: var(--color-text); border-color: var(--color-border-hover); }
.node-take-adopted { color: var(--color-accent); border-color: var(--color-accent); }
.node-preview {
  width: 100%;
  height: 72px;
  object-fit: cover;
  border-radius: var(--radius-sm);
  background: var(--color-surface-strong);
  pointer-events: auto;
}
.node-adopted { position: absolute; right: var(--space-sm); bottom: var(--space-sm); }
.node-score { position: absolute; right: var(--space-sm); bottom: var(--space-sm); }
</style>
