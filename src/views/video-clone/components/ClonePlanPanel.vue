<script setup lang="ts">
/**
 * ClonePlanPanel.vue — C107-21：复刻方案面板（输入 plan/planLoading/planError；
 * 事件 regenerate/generate）。展示保留/替换步骤与材料缺口；不做服务端预算
 * 计算（费用以服务端 pricing 为准，未知价如实显示）。
 */
import type { HypitClonePlan } from '../../../types/hypit';

const props = defineProps<{
  plan: HypitClonePlan | null;
  planLoading: boolean;
  planError: string | null;
  generating: boolean;
}>();

const emit = defineEmits<{
  regenerate: [];
  generate: [];
}>();
</script>

<template>
  <section class="gl-zone" data-testid="clone-plan-panel" aria-label="复刻方案">
    <header class="clone-plan-head">
      <h2>复刻方案</h2>
      <button type="button" class="gl-btn-secondary" data-testid="clone-plan-regenerate" :disabled="props.generating"
        @click="emit('regenerate')">重新生成方案</button>
    </header>
    <p v-if="props.planError" class="clone-error" data-testid="clone-plan-error" role="alert">{{ props.planError }}</p>
    <p v-else-if="props.planLoading" class="clone-loading" data-testid="clone-plan-loading" aria-live="polite">
      正在读取方案…
    </p>
    <template v-else-if="props.plan === null">
      <p class="clone-empty" data-testid="clone-plan-empty">
        还没有复刻方案：先完成参考分析，再生成方案。
      </p>
    </template>
    <template v-else>
      <p v-if="props.plan.status === 'WAITING_INPUT'" class="clone-plan-waiting" data-testid="clone-job-waiting"
        role="status">方案存在材料缺口，补齐后才能生成。</p>
      <ol class="clone-plan-steps">
        <li v-for="step in props.plan.steps" :key="step.index" class="clone-plan-step">
          <span class="clone-plan-capability">{{ step.capability }}</span>
          <span v-if="step.boundSystemId" class="clone-plan-system">{{ step.boundSystemId }} @ {{ step.anchorSeconds }}s</span>
          <span class="clone-plan-desc">{{ step.description }}</span>
        </li>
      </ol>
      <div v-if="props.plan.materialGaps.length > 0" class="clone-plan-gaps" data-testid="clone-plan-gaps">
        <h3>材料缺口</h3>
        <ul>
          <li v-for="(gap, index) in props.plan.materialGaps" :key="index">
            <strong>{{ gap.kind }}</strong> — {{ gap.description }}
            <span v-if="gap.suggestedSource" class="clone-plan-gap-source">建议来源：{{ gap.suggestedSource }}</span>
          </li>
        </ul>
      </div>
      <button type="button" class="gl-btn-primary" data-testid="clone-generate"
        :disabled="props.generating || props.plan.status === 'WAITING_INPUT'" @click="emit('generate')">
        {{ props.generating ? '生成中…' : '按方案生成' }}
      </button>
    </template>
  </section>
</template>

<style scoped>
.clone-plan-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.clone-plan-head h2 { margin: 0; font-size: 16px; font-family: var(--font-display); }
.clone-error { color: var(--color-danger); }
.clone-loading, .clone-empty { color: var(--color-text-secondary); }
.clone-plan-waiting { color: var(--color-warning, #d8a024); }
.clone-plan-steps { list-style: decimal; padding-left: 20px; display: grid; gap: 8px; }
.clone-plan-step { display: grid; gap: 2px; }
.clone-plan-capability { font-weight: 600; }
.clone-plan-system { font-size: 12px; color: var(--color-text-secondary); }
.clone-plan-desc { font-size: 13px; }
.clone-plan-gaps { border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 12px; margin: 12px 0; }
.clone-plan-gaps h3 { margin: 0 0 8px; font-size: 14px; }
.clone-plan-gap-source { display: block; font-size: 12px; color: var(--color-text-secondary); }
</style>
