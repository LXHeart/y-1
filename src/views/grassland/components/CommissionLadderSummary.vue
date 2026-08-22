<template>
  <div class="gl-ladder-summary" :class="{ 'gl-ladder-compact': compact }">
    <p class="gl-ladder-head">
      <span class="gl-tag gl-tag-ladder">阶梯佣金</span>
      <span class="gl-ladder-metric"><code>{{ ladder.metricKey }}</code></span>
      <span class="gl-ladder-range">{{ payoutRange }}</span>
    </p>
    <details class="gl-ladder-details">
      <summary>档位明细</summary>
      <ul class="gl-ladder-tiers">
        <li v-for="tier in tiers" :key="tier.threshold">
          {{ tier.threshold.toLocaleString('en-US') }} → {{ formatYuan(tier.payoutCents) }}
        </li>
      </ul>
      <p class="gl-ladder-note">按已达最高档发固定佣金，不累加</p>
    </details>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CommissionLadder } from '../../../types/grassland'
import { formatYuan } from '../../../lib/money'

/**
 * 任务书 #25：配置了阶梯佣金的任务在商家任务列表 / 推荐官任务大厅共用的只读摘要。
 *
 * 赏金金额（最高可预留金额）由调用方继续展示；这里只负责「阶梯佣金」标识、指标标识、
 * 最低-最高档佣金范围与 <details> 展开的「阈值 → 固定佣金」明细 + 不累加说明。
 * compact 模式语义相同，仅缩小间距（商家任务列表行内）。
 */
const props = withDefaults(defineProps<{
  ladder: CommissionLadder
  compact?: boolean
}>(), { compact: false })

/** 展示前按阈值升序——后端快照顺序不保证，排序只影响展示不影响结算。 */
const tiers = computed(() => [...props.ladder.tiers].sort((left, right) => left.threshold - right.threshold))

const payoutRange = computed(() => {
  const sorted = tiers.value
  // tsconfig lib=ES2020 无 Array.prototype.at，用下标取首末档。
  const first = sorted[0]?.payoutCents ?? 0
  const last = sorted[sorted.length - 1]?.payoutCents ?? 0
  return `${formatYuan(first)}–${formatYuan(last)}`
})
</script>

<style scoped>
.gl-ladder-summary { display: inline-flex; flex-direction: column; gap: 2px; text-align: left; vertical-align: middle; }
.gl-ladder-head { display: flex; align-items: center; gap: 6px; margin: 0; flex-wrap: wrap; }
.gl-tag-ladder {
  display: inline-block; font-size: 11px; padding: 1px 7px; border-radius: 10px; white-space: nowrap;
  background: color-mix(in srgb, var(--color-accent) 14%, transparent); color: var(--color-accent);
}
.gl-ladder-metric { font-size: 12px; opacity: 0.8; }
.gl-ladder-metric code { font-size: 11px; }
.gl-ladder-range { font-size: 12px; font-weight: 600; white-space: nowrap; }
.gl-ladder-details { font-size: 12px; }
.gl-ladder-details summary { cursor: pointer; opacity: 0.75; user-select: none; }
.gl-ladder-tiers { margin: 2px 0 0; padding-left: 18px; }
.gl-ladder-tiers li { white-space: nowrap; }
.gl-ladder-note { margin: 2px 0 0; font-size: 11px; opacity: 0.65; }

/* compact：商家任务列表行内——同语义小间距小字号。 */
.gl-ladder-compact { gap: 1px; }
.gl-ladder-compact .gl-ladder-metric,
.gl-ladder-compact .gl-ladder-range { font-size: 11px; }
.gl-ladder-compact .gl-ladder-details { font-size: 11px; }
.gl-ladder-compact .gl-ladder-tiers { margin-top: 1px; }
</style>
