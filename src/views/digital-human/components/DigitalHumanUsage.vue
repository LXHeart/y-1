<template>
  <section class="dh-usage" aria-labelledby="dh-usage-title" data-testid="dh-usage">
    <h3 id="dh-usage-title" class="dh-usage-title">本场用量与费用</h3>
    <dl class="dh-usage-grid">
      <div class="dh-usage-item">
        <dt>已确认费用</dt>
        <dd class="gl-num">{{ confirmedText }}</dd>
      </div>
      <div class="dh-usage-item">
        <dt>平台补贴</dt>
        <dd class="gl-num">{{ formatYuan(billing?.subsidizedCents ?? 0) }}</dd>
      </div>
      <div class="dh-usage-item">
        <dt>待核对调用</dt>
        <dd>
          <span v-if="pendingCount > 0" class="badge badge-warning" data-testid="dh-usage-pending">
            {{ pendingCount }} 笔
          </span>
          <span v-else class="dh-hint">无</span>
        </dd>
      </div>
      <div class="dh-usage-item">
        <dt>价表版本</dt>
        <dd class="dh-hint">{{ billing?.priceTableVersion ?? '—' }}</dd>
      </div>
    </dl>
    <p class="dh-hint">
      已确认=按实际调用结算的部分；「待核对」表示用量未知（不以 0 冒充），由平台核对后更新。
      文本费用按所选来源（平台积分或你的自有账户）另计。
    </p>
  </section>
</template>

<script setup lang="ts">
// 纯 props（K11/K08.1）：confirmed/pending/来源分列；未知金额显示待核对而非 0（K01）。
import { computed } from 'vue'
import { formatYuan } from '../../../lib/money'
import type { SessionBilling } from '../../../types/digital-human'

const props = defineProps<{
  billing: SessionBilling | null
  /** 兼容 K11 DigitalHumanUsage(billing,pending) 形态：pending=true 时整体显示待核对。 */
  pending?: boolean
}>()

const pendingCount = computed(() => props.billing?.pendingCount ?? 0)

const confirmedText = computed(() => {
  if (props.pending) return '待核对'
  const cents = props.billing?.confirmedCents
  if (cents == null) return '待核对'
  return formatYuan(cents)
})
</script>

<style scoped>
.dh-usage { display: grid; gap: var(--space-sm); }
.dh-usage-title {
  margin: 0; font-family: var(--font-display); font-size: var(--type-card-title);
  font-weight: var(--weight-heading); color: var(--color-text);
}
.dh-usage-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: var(--space-sm); margin: 0; }
.dh-usage-item { display: grid; gap: var(--space-xxs); }
.dh-usage-item dt { font-size: var(--type-caption); color: var(--color-text-muted); }
.dh-usage-item dd { margin: 0; font-size: var(--type-body-sm); color: var(--color-text); }
</style>
