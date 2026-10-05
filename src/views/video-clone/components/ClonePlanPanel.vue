<script setup lang="ts">
/**
 * ClonePlanPanel.vue — C107-21：复刻方案面板（输入 plan/planLoading/planError；
 * 事件 regenerate/generate）。展示保留/替换步骤与材料缺口；不做服务端预算
 * 计算（费用以服务端 pricing 为准，未知价如实显示）。
 *
 * C107F3-10（§8 UI-01～04）：再生成状态投影——提交/运行中按钮禁用并显示
 * 「重新生成中…」（202 不展示成功）；终态 succeeded 显示「方案已更新」、
 * failed/waiting_input 保留旧方案与可操作原因；409 前置失败按服务端 message
 * 关键词映射三句可行动文案（无完整分析/素材变化/版本冲突）。可选 props 缺省时
 * 与旧面板行为一致（既有调用方/测试不破坏）。
 */
import { computed } from 'vue';
import type { HypitClonePlan } from '../../../types/hypit';

const props = defineProps<{
  plan: HypitClonePlan | null;
  planLoading: boolean;
  planError: string | null;
  generating: boolean;
  regenerating?: boolean;
  regenerateStatus?: 'idle' | 'running' | 'succeeded' | 'failed';
  regenerateError?: string | null;
  /**
   * C107F3-11（W79，§13.3 增量七）：「重新生成方案」的独立忙碌闸——§8 UI-03
   * 语义是分析在途时放行点击（服务端 409 前置拒绝→第一句可行动文案引导），
   * 而「按方案生成」仍受 generating 全闸保护；缺省 undefined 时沿用旧语义
   * （generating），既有调用方与单测零变化。
   */
  regenerateGate?: boolean;
}>();

const emit = defineEmits<{
  regenerate: [];
  generate: [];
}>();

/** UI-03：409 前置失败按服务端 message 关键词映射为可行动文案（§6.4 三种拒绝语义）。
 * 顺序先「素材」后「分析」：服务端素材变化消息（「参考素材已变化（sha 不匹配或
 * 非 ready），请重新分析」）尾缀含「分析」二字，先判「分析」会误吞成第一句。 */
function mapRegenerateError(message: string): string {
  if (message.includes('素材')) return '参考素材已变化，请重新分析';
  if (message.includes('分析')) return '请先完成参考视频分析';
  return '工程已更新，请刷新后重试';
}

const regenerateHint = computed<string | null>(() => {
  if (props.regenerating) return null; // 处理中只显示按钮态，不预示成败（202 不展示成功）
  if (props.regenerateStatus === 'succeeded') return '方案已更新';
  if (props.regenerateStatus === 'failed' && props.regenerateError) return mapRegenerateError(props.regenerateError);
  return null;
});

const regenerateHintState = computed<'updated' | 'error' | null>(() => {
  if (props.regenerating === true) return null;
  if (props.regenerateStatus !== 'succeeded' && props.regenerateStatus !== 'failed') return null;
  return props.regenerateStatus === 'succeeded' ? 'updated' : 'error';
});
</script>

<template>
  <section class="gl-zone" data-testid="clone-plan-panel" aria-label="复刻方案"
    :aria-busy="props.regenerating === true ? 'true' : undefined">
    <header class="clone-plan-head">
      <h2>复刻方案</h2>
      <button type="button" class="gl-btn-secondary" data-testid="clone-plan-regenerate"
        :disabled="(props.regenerateGate ?? props.generating) || props.regenerating === true"
        @click="emit('regenerate')">{{ props.regenerating === true ? '重新生成中…' : '重新生成方案' }}</button>
    </header>
    <!-- C107F3-10 UI-01/UI-02：处理中保留旧方案（不清空 plan），终态才给状态提示。 -->
    <p v-if="regenerateHint" class="clone-plan-regenerate-hint"
      :class="{ 'clone-plan-regenerate-updated': regenerateHintState === 'updated', 'clone-plan-regenerate-failed': regenerateHintState === 'error' }"
      :data-testid="regenerateHintState === 'updated' ? 'clone-plan-updated' : 'clone-plan-regenerate-error'"
      :role="regenerateHintState === 'error' ? 'alert' : 'status'">
      {{ regenerateHint }}
    </p>
    <!-- C107F2-37（缺陷 R）：plan 为 null 时空态是可行动真相（先完成参考分析），
         读取错误只作附加 alert——独占式 error 分支会把面板锁死在早期 404
         （工程刚建、分析未完属预期），空/等待判据永久不可见、面板再也无法恢复。 -->
    <template v-if="props.plan === null">
      <p v-if="props.planError && !props.planLoading" class="clone-error" data-testid="clone-plan-error" role="alert">
        {{ props.planError }}
      </p>
      <p v-if="props.planLoading" class="clone-loading" data-testid="clone-plan-loading" aria-live="polite">
        正在读取方案…
      </p>
      <p v-else class="clone-empty" data-testid="clone-plan-empty">
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
.clone-plan-regenerate-hint { margin: 0 0 8px; font-size: 13px; }
.clone-plan-regenerate-updated { color: var(--color-success, #2e9e6b); }
.clone-plan-regenerate-failed { color: var(--color-danger); }
.clone-plan-steps { list-style: decimal; padding-left: 20px; display: grid; gap: 8px; }
.clone-plan-step { display: grid; gap: 2px; }
.clone-plan-capability { font-weight: 600; }
.clone-plan-system { font-size: 12px; color: var(--color-text-secondary); }
.clone-plan-desc { font-size: 13px; }
.clone-plan-gaps { border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 12px; margin: 12px 0; }
.clone-plan-gaps h3 { margin: 0 0 8px; font-size: 14px; }
.clone-plan-gap-source { display: block; font-size: 12px; color: var(--color-text-secondary); }
</style>
