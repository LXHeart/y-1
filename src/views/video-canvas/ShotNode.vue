<script setup lang="ts">
import type { CanvasShot } from './useVideoCanvas'

defineProps<{
  shot: CanvasShot
  selected: boolean
}>()

const emit = defineEmits<{
  (e: 'select', shotId: string): void
  (e: 'drag-end', shotId: string, x: number, y: number): void
}>()

function onPointerDown(event: PointerEvent, shot: CanvasShot): void {
  if (event.button !== 0) return
  emit('select', shot.id)
  const startX = event.clientX
  const startY = event.clientY
  const originX = shot.x
  const originY = shot.y
  const card = event.currentTarget as HTMLElement
  card.setPointerCapture(event.pointerId)
  const move = (ev: PointerEvent): void => {
    card.style.left = `${originX + (ev.clientX - startX)}px`
    card.style.top = `${originY + (ev.clientY - startY)}px`
  }
  const up = (ev: PointerEvent): void => {
    card.releasePointerCapture(ev.pointerId)
    card.removeEventListener('pointermove', move)
    card.removeEventListener('pointerup', up)
    emit('drag-end', shot.id, originX + (ev.clientX - startX), originY + (ev.clientY - startY))
  }
  card.addEventListener('pointermove', move)
  card.addEventListener('pointerup', up)
}

function bestScore(shot: CanvasShot): number | null {
  const scored = shot.takes.map(take => take.score).filter((score): score is number => score != null)
  return scored.length ? Math.max(...scored) : null
}
</script>

<template>
  <div
    class="canvas-node glass-card"
    :class="{ 'canvas-node-selected': selected }"
    :data-test="`canvas-node-${shot.seq}`"
    :style="{ left: `${shot.x}px`, top: `${shot.y}px` }"
    @pointerdown="onPointerDown($event, shot)"
  >
    <div class="node-head">
      <span class="badge badge-accent" :data-test="`canvas-node-seq-${shot.seq}`">镜头 {{ shot.seq }}</span>
      <span class="node-meta gl-num">{{ shot.plannedSeconds }}s · {{ shot.cameraMove }}</span>
    </div>
    <p class="node-visual">{{ shot.visual }}</p>
    <p class="node-narration">{{ shot.narration }}</p>
    <div v-if="shot.takes.length" class="node-takes" :data-test="`canvas-takes-${shot.seq}`">
      <span
        v-for="take in shot.takes"
        :key="take.id"
        class="node-take"
        :class="{ 'node-take-selectable': take.selectable }"
        :title="take.selectable ? `候选 ${take.takeNo}${take.score != null ? ` · 质检 ${take.score}` : ''}` : take.status"
      >{{ take.takeNo }}<template v-if="take.score != null"> · {{ take.score }}</template></span>
    </div>
    <span v-if="bestScore(shot) != null" class="badge badge-warning node-score">质检 {{ bestScore(shot) }}</span>
  </div>
</template>

<style scoped>
.canvas-node {
  position: absolute;
  width: 232px;
  padding: var(--space-sm) var(--space-md);
  cursor: grab;
  user-select: none;
  touch-action: none;
}
.canvas-node:active { cursor: grabbing; }
.canvas-node-selected {
  border-color: var(--color-accent);
  box-shadow: var(--shadow-glow);
}
.node-head { display: flex; align-items: center; gap: var(--space-xs); margin-bottom: var(--space-xs); }
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
.node-score { position: absolute; right: var(--space-sm); bottom: var(--space-sm); }
</style>
