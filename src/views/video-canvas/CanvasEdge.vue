<script setup lang="ts">
import { computed } from 'vue'

/**
 * 任务书 #100 C100-10：画布连线三种语义（§6.3）——
 * sequence（权威镜序，实线箭头）/ reference（用户参考，虚线）/ derived-from（候选/交付
 * 来源投影，点线）。线只有类别与可读名称，不承载业务写操作（参考连线不触发制作或扣费）。
 */
const props = withDefaults(defineProps<{
  from: { x: number; y: number }
  to: { x: number; y: number }
  kind?: 'sequence' | 'reference' | 'derived-from'
  label?: string
  /** 旧调用兼容：等价 kind=reference。 */
  dashed?: boolean
}>(), {
  kind: 'sequence',
  label: '',
  dashed: false,
})

const effectiveKind = computed(() => (props.dashed ? 'reference' : props.kind))

/** 顺序连线：水平出/入的三次贝塞尔（出点=源节点右缘中点，入点=目标左缘箭头）。 */
const path = computed(() => {
  const x1 = props.from.x
  const y1 = props.from.y
  const x2 = props.to.x
  const y2 = props.to.y
  const mx = (x1 + x2) / 2
  return `M ${x1} ${y1} C ${mx} ${y1}, ${mx} ${y2}, ${x2 - 8} ${y2}`
})

const KIND_LABELS: Record<string, string> = {
  sequence: '顺序连线',
  reference: '参考连线',
  'derived-from': '来源连线',
}
</script>

<template>
  <g class="canvas-edge" :class="`canvas-edge-${effectiveKind}`"
    :data-test="`canvas-edge-${effectiveKind}`" role="img"
    :aria-label="`${KIND_LABELS[effectiveKind] ?? effectiveKind}${label ? `：${label}` : ''}`">
    <path :d="path" />
    <polygon :points="`${to.x},${to.y} ${to.x - 9},${to.y - 5} ${to.x - 9},${to.y + 5}`" />
  </g>
</template>

<style scoped>
.canvas-edge path {
  stroke: color-mix(in srgb, var(--color-accent) 62%, var(--color-border));
  stroke-width: 2px;
  fill: none;
}

.canvas-edge polygon { fill: color-mix(in srgb, var(--color-accent) 62%, var(--color-border)); }

.canvas-edge-reference path { stroke-dasharray: 6 5; }

.canvas-edge-reference path,
.canvas-edge-reference polygon {
  stroke: color-mix(in srgb, var(--color-accent-warm) 62%, var(--color-border));
  fill: color-mix(in srgb, var(--color-accent-warm) 62%, var(--color-border));
}

.canvas-edge-reference polygon {
  fill: color-mix(in srgb, var(--color-accent-warm) 62%, var(--color-border));
}

.canvas-edge-derived-from path {
  stroke-dasharray: 2 5;
  stroke: color-mix(in srgb, var(--color-text) 45%, var(--color-border));
}

.canvas-edge-derived-from polygon {
  fill: color-mix(in srgb, var(--color-text) 45%, var(--color-border));
}
</style>
