<script setup lang="ts">
import { inject, onDeactivated, onUnmounted, ref } from 'vue'
import type { CanvasNodeRef } from '../../../types/video-canvas'
import type { useCanvasInteraction } from '../composables/useCanvasInteraction'

const props = defineProps<{
  id: string
  kind: CanvasNodeRef['kind']
  label: string | null
  text: string | null
  x: number
  y: number
  selected: boolean
  unavailableReason: string | null
}>()
const emit = defineEmits<{
  (e: 'select', nodeId: string, additive?: boolean): void
  (e: 'request-reselect', nodeId: string): void
}>()
const interaction = inject<ReturnType<typeof useCanvasInteraction> | null>('canvasInteraction', null)
const root = ref<HTMLElement | null>(null)
const labels: Record<string, string> = { note: '备注', media: '素材参考', brief: '创作要求', take: '候选', delivery: '交付' }
let cleanup: (() => void) | null = null
function select(event: MouseEvent | KeyboardEvent): void { emit('select', props.id, event.ctrlKey || event.metaKey) }
function pointer(event: PointerEvent): void {
  if (event.button !== 0 || (event.target as HTMLElement).closest('button,input,textarea,select,a,video')) return
  select(event)
}
function keyboard(event: KeyboardEvent): void {
  if ((event.target as HTMLElement).closest('button,input,textarea,select,a,video')) return
  if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); select(event) }
  else if (event.key.startsWith('Arrow') && interaction?.commitKeyboardMove(props.id, event.key, event.shiftKey, props.x, props.y)) event.preventDefault()
  else if (event.key === 'Escape') cancel()
}
function cancel(): void { if (cleanup) { cleanup(); cleanup = null; interaction?.dragCancel() } }
function drag(event: PointerEvent): void {
  if (!interaction || event.button !== 0) return
  event.stopPropagation(); select(event)
  if (!interaction.beginNodeDrag(props.id, event, props.x, props.y)) return
  const handle = event.currentTarget as HTMLElement
  const move = (next: PointerEvent) => interaction.dragMove(next)
  const end = (next: PointerEvent) => { cleanup?.(); cleanup = null; interaction.dragEnd(next) }
  cleanup = () => {
    window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', end)
    window.removeEventListener('pointercancel', cancel); handle.removeEventListener('lostpointercapture', cancel)
  }
  window.addEventListener('pointermove', move); window.addEventListener('pointerup', end)
  window.addEventListener('pointercancel', cancel); handle.addEventListener('lostpointercapture', cancel)
  try { handle.setPointerCapture?.(event.pointerId) } catch { /* window listeners handle unsupported capture */ }
}
onDeactivated(cancel); onUnmounted(cancel)
</script>

<template>
  <div ref="root" class="glass-card reference-node" :class="{ 'reference-node-selected': selected }"
    :data-test="'canvas-ref-' + kind + '-' + id" :style="{ left: x + 'px', top: y + 'px' }"
    role="option" :aria-selected="selected" :aria-label="(labels[kind] || '节点') + ' ' + (label || '')" tabindex="0"
    @pointerdown="pointer" @keydown="keyboard">
    <div class="reference-head">
      <span class="badge">{{ labels[kind] || '节点' }}</span>
      <button type="button" class="gl-btn-ghost" data-test="canvas-reference-drag" aria-label="移动节点，或聚焦节点后按方向键"
        @pointerdown="drag" @click="root?.focus()">移动</button>
    </div>
    <label class="reference-selection" @pointerdown.stop @keydown.stop>
      <input type="checkbox" :checked="selected" :aria-label="'选择' + (labels[kind] || '节点')" data-test="canvas-reference-select"
        @change="emit('select', id, true)">选择{{ labels[kind] || '节点' }}
    </label>
    <p v-if="kind === 'note'" class="reference-text" data-test="canvas-note-text">{{ text || '尚未填写备注' }}</p>
    <p v-else class="reference-text">{{ label || labels[kind] }}</p>
    <p v-if="unavailableReason" class="field-note" role="status" data-test="canvas-ref-unavailable">
      {{ unavailableReason }}
      <button v-if="kind === 'media'" type="button" class="gl-btn-ghost" data-test="canvas-ref-reselect"
        @pointerdown.stop @click.stop="emit('request-reselect', id)">重新选择</button>
    </p>
  </div>
</template>

<style scoped>
.reference-node { position: absolute; width: var(--layout-rail); padding: var(--space-md); user-select: none; touch-action: none; }
.reference-node-selected { border-color: var(--color-accent-2); background: var(--color-surface-highlight); }
.reference-node:focus-visible { outline: var(--focus-width) solid var(--focus-color); outline-offset: var(--focus-offset); }
.reference-head, .reference-selection { display: flex; align-items: center; gap: var(--space-xs); min-height: var(--touch-target); }
.reference-head { justify-content: space-between; }
.reference-selection { font-size: var(--text-sm); }
.reference-selection input { width: var(--icon-size); height: var(--icon-size); accent-color: var(--color-accent); }
.reference-text { font-size: var(--text-base); color: var(--color-text-secondary); overflow-wrap: anywhere; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; margin: var(--space-xs) 0 0; }
</style>
