<script setup lang="ts">
/**
 * ExecutionGrantDialog.vue — C107F2-27 (W132 NEW，主生成流/变体构建流共享)：
 * 远程执行授权确认。展示总范围（目标能力×项数）、逐项报价与累计上限；
 * 价格未知时明示风险不隐藏。确认/取消只发事件——扣费授权语义在调用方
 * （useHypitWorkflow / useHypitVariants），本组件零请求。
 */
import GlModal from '../../../components/GlModal.vue';
import type { HypitPendingGrant } from '../../../types/hypit';

const props = defineProps<{
  /** null = 不显示；非 null = 待确认快照。 */
  pending: HypitPendingGrant | null;
  /** 确认提交中（防连点）。 */
  confirming?: boolean;
  /** 授权/提交失败的展示位（输入保留，可重试或取消）。 */
  error?: string | null;
}>();

const emit = defineEmits<{
  confirm: [];
  dismiss: [];
}>();

/** 累计上限 = 单项合计 × 项数；单项未知则为 null（如实展示「价格未知」）。 */
function cumulative(pending: HypitPendingGrant): string | null {
  if (pending.maxAmount === null) return null;
  return (Number(pending.maxAmount) * pending.variantCount).toFixed(2);
}
</script>

<template>
  <GlModal v-if="props.pending !== null" title="授权远程执行" persistent @close="emit('dismiss')">
    <div class="grant-body" data-testid="hypit-grant-dialog">
      <p class="grant-scope">
        执行范围：<strong>{{ props.pending.variantCount }}</strong> 项 · {{ props.pending.currency }}
      </p>
      <ul class="grant-needs" data-testid="hypit-grant-needs">
        <li v-for="need in props.pending.needs" :key="need">{{ need }}</li>
      </ul>
      <p class="grant-total">
        <template v-if="cumulative(props.pending) !== null">
          累计上限：<strong data-testid="hypit-grant-total">{{ cumulative(props.pending) }} {{ props.pending.currency }}</strong>
          <span class="grant-total-note">（单项合计 {{ props.pending.maxAmount }} × {{ props.pending.variantCount }} 项，实际按上游计量结算）</span>
        </template>
        <template v-else>
          <span class="grant-unknown" data-testid="hypit-grant-unknown">上游报价未知——确认前请到治理台核对价目，费用按实际计量。</span>
        </template>
      </p>
      <p v-if="props.error" class="grant-error" data-testid="hypit-grant-error" role="alert">{{ props.error }}</p>
    </div>
    <footer class="grant-actions">
      <button type="button" class="gl-btn-secondary" data-testid="hypit-grant-cancel"
        :disabled="props.confirming" @click="emit('dismiss')">取消授权</button>
      <button type="button" class="gl-btn-primary" data-testid="hypit-grant-confirm"
        :disabled="props.confirming" @click="emit('confirm')">
        {{ props.confirming ? '提交中…' : '确认并执行' }}
      </button>
    </footer>
  </GlModal>
</template>

<style scoped>
.grant-body { display: grid; gap: 10px; }
.grant-scope { margin: 0; color: var(--color-text); }
.grant-needs { margin: 0; padding-left: 18px; display: grid; gap: 4px; color: var(--color-text-secondary); font-size: 13px; }
.grant-total { margin: 0; color: var(--color-text); }
.grant-total-note { color: var(--color-text-secondary); font-size: 12px; }
.grant-unknown { color: var(--color-warning); }
.grant-error { margin: 0; color: var(--color-danger); }
.grant-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
</style>
