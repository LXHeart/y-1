<script setup lang="ts">
import CommissionLadderSummary from './CommissionLadderSummary.vue'
import { formatYuan } from '../../../lib/money'
import type { TaskPreview } from '../../../types/grassland'

defineProps<{ preview: TaskPreview }>()

function pct(bps: number): string {
  return `${bps / 100}%`
}
</script>

<template>
  <section class="task-terms-preview" data-testid="task-terms-preview" aria-label="合作条款预览">
    <h4>合作条款</h4>
    <ul class="preview-list">
      <li v-for="item in preview.highlights" :key="item">{{ item }}</li>
    </ul>
    <p v-if="preview.what.description">{{ preview.what.description }}</p>
    <p v-if="preview.what.productServiceInfo">{{ preview.what.productServiceInfo }}</p>
    <dl class="preview-grid">
      <div>
        <dt>{{ preview.payout.mode === 'freebie' ? '达标返还押金' : '预计到手' }}</dt>
        <dd class="gl-num" data-testid="preview-payout">
          <template v-if="preview.payout.estimatedPayoutCents != null">{{ formatYuan(preview.payout.estimatedPayoutCents) }}</template>
          <template v-else-if="preview.payout.mode === 'ladder'">最高 {{ formatYuan(preview.payout.maximumPayoutCents ?? 0) }}，按达标档位结算</template>
          <template v-else>按订单冻结的套餐佣金结算</template>
        </dd>
        <CommissionLadderSummary v-if="preview.payout.ladder" :ladder="preview.payout.ladder" />
      </div>
      <div>
        <dt>可提现时间</dt>
        <dd data-testid="preview-withdrawable">{{ preview.payout.withdrawablePolicy }}</dd>
      </div>
      <div>
        <dt>交付期限</dt>
        <dd>接受后 {{ preview.delivery.deliveryDeadlineDays }} 天内</dd>
      </div>
      <div>
        <dt>发布前审稿</dt>
        <dd v-if="preview.review.required">
          {{ preview.review.reviewWindowHours }} 小时内审稿，最多退改 {{ preview.review.reviseCap }} 次，退回后 {{ preview.review.resubmitHours }} 小时内补交。
          {{ preview.review.timeoutPolicy }}。
        </dd>
        <dd v-else>无需发布前审稿</dd>
      </div>
      <div class="preview-full">
        <dt>取消处理</dt>
        <dd data-testid="preview-cancel">
          <template v-if="preview.payout.mode === 'bounty' || preview.payout.mode === 'ladder'">
            已确认脚本 {{ pct(preview.cancel.scriptBps) }} / 合格成品 {{ pct(preview.cancel.deliverableBps) }} / 按约发布 {{ pct(preview.cancel.publishedBps) }}。
          </template>
          {{ preview.cancel.cap }}。
        </dd>
      </div>
      <div v-for="group in [
        { label: '必须包含', items: preview.what.mustInclude },
        { label: '禁止内容', items: preview.what.forbiddenContent },
        { label: '指标要求', items: preview.what.metricRequirements },
        { label: '凭证要求', items: preview.what.evidenceRequirements },
      ].filter((item) => item.items.length)" :key="group.label">
        <dt>{{ group.label }}</dt>
        <dd><ul class="preview-list"><li v-for="item in group.items" :key="item">{{ item }}</li></ul></dd>
      </div>
    </dl>
  </section>
</template>

<style scoped>
.task-terms-preview { display: flex; flex-direction: column; gap: var(--space-sm); min-width: 0; overflow-wrap: anywhere; }
.task-terms-preview h4 { margin: 0; font-family: var(--font-display); font-size: var(--text-lg); font-weight: 400; letter-spacing: 0; }
.task-terms-preview p { margin: 0; font-size: var(--text-sm); }
.preview-list { margin: 0; padding-left: var(--space-lg); display: flex; flex-direction: column; gap: var(--space-xxs); font-size: var(--text-sm); }
.preview-grid { margin: 0; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-md); }
.preview-grid dt { font-size: var(--text-sm); color: var(--color-text-secondary); }
.preview-grid dd { margin: var(--space-xxs) 0 0; font-size: var(--text-sm); }
.preview-full { grid-column: 1 / -1; }
@media (max-width: 768px) { .preview-grid { grid-template-columns: minmax(0, 1fr); } }
</style>
