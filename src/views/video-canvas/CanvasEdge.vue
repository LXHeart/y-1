<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  from: { x: number; y: number }
  to: { x: number; y: number }
  dashed?: boolean
}>()

/** 顺序连线：水平出/入的三次贝塞尔（出点=源节点右缘中点，入点=目标左缘箭头）。 */
const path = computed(() => {
  const x1 = props.from.x
  const y1 = props.from.y
  const x2 = props.to.x
  const y2 = props.to.y
  const mx = (x1 + x2) / 2
  return `M ${x1} ${y1} C ${mx} ${y1}, ${mx} ${y2}, ${x2 - 8} ${y2}`
})
</script>

<template>
  <g class="canvas-edge" :class="{ 'canvas-edge-dashed': dashed }">
    <path :d="path" />
    <polygon :points="`${to.x},${to.y} ${to.x - 9},${to.y - 5} ${to.x - 9},${to.y + 5}`" />
  </g>
</template>

<style scoped>
.canvas-edge path {
  stroke: color-mix(in srgb, var(--color-accent) 62%, var(--color-border));
  stroke-width: 2;
  fill: none;
}
.canvas-edge polygon { fill: color-mix(in srgb, var(--color-accent) 62%, var(--color-border)); }
.canvas-edge-dashed path { stroke-dasharray: 6 5; }
.canvas-edge-dashed polygon {
  fill: color-mix(in srgb, var(--color-accent-warm) 62%, var(--color-border));
}
</style>
