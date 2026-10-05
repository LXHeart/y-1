<script setup lang="ts">
/**
 * VariantsPanel.vue — C107-21：变体批次（输入 items；事件 create/build/retry/
 * cancel）。一次失败不清空全批：逐项状态与 attempt 如实展示。
 */
import { ref } from 'vue';
import type { HypitVariantItem } from '../../../types/hypit';

const props = defineProps<{
  items: HypitVariantItem[];
  loading: boolean;
  error: string | null;
  creating: boolean;
  actingId: string | null;
}>();

const emit = defineEmits<{
  create: [axes: { key: string; values: string[] }[]];
  build: [variant: HypitVariantItem];
  retry: [variant: HypitVariantItem];
  cancel: [variant: HypitVariantItem];
}>();

const axisKey = ref('');
const axisValues = ref('');

function addAxis(): void {
  const key = axisKey.value.trim();
  const values = axisValues.value.split(',').map((value) => value.trim()).filter((value) => value.length > 0);
  if (key.length === 0 || values.length === 0) return;
  emit('create', [{ key, values }]);
  axisKey.value = '';
  axisValues.value = '';
}

const stateLabels: Record<HypitVariantItem['state'], string> = {
  draft: '草稿',
  planned: '已计划',
  queued: '排队中',
  running: '生成中',
  succeeded: '成功',
  failed: '失败',
  cancelled: '已取消',
};
</script>

<template>
  <section class="gl-field gl-zone" data-testid="clone-variants-panel" aria-label="批量变体">
    <h2>批量变体</h2>
    <form class="clone-variants-form" @submit.prevent="addAxis">
      <label class="gl-form-field clone-variant-key">
        <span>变化轴</span>
        <input v-model="axisKey" type="text" placeholder="例如 topic" data-testid="clone-variant-key" />
      </label>
      <label class="gl-form-field clone-variant-values">
        <span>取值（逗号分隔）</span>
        <input v-model="axisValues" type="text" placeholder="a, b, c" data-testid="clone-variant-values" />
      </label>
      <button type="submit" class="gl-btn-primary" data-testid="clone-variant-create" :disabled="props.creating">
        {{ props.creating ? '创建中…' : '创建批次' }}
      </button>
    </form>
    <!-- C107F2-37（缺陷 T）：错误独立于列表链——旧 else 链让任一动作失败就把
         整个变体列表卸载（行状态/重试入口一起消失），面板无法就地恢复。 -->
    <p v-if="props.error" class="clone-error" data-testid="clone-error" role="alert">{{ props.error }}</p>
    <p v-if="props.loading" class="clone-loading" aria-live="polite">正在读取变体…</p>
    <p v-else-if="props.items.length === 0" class="clone-empty">还没有变体批次。</p>
    <ul v-else class="clone-variant-list">
      <li v-for="variant in props.items" :key="variant.id" class="clone-variant"
        :data-testid="`clone-variant-${variant.ordinal}`">
        <span class="clone-variant-ordinal">#{{ variant.ordinal }}</span>
        <span class="clone-variant-params">{{ JSON.stringify(variant.parameters) }}</span>
        <span class="clone-variant-state" :data-state="variant.state" data-testid="clone-variant-state">
          {{ stateLabels[variant.state] }}（attempt {{ variant.attempt }}）
        </span>
        <span v-if="variant.buildId" class="clone-variant-build" data-testid="clone-variant-build-id">
          build {{ variant.buildId.slice(0, 8) }}
        </span>
        <span class="clone-variant-actions">
          <button v-if="variant.state === 'draft' || variant.state === 'planned' || variant.state === 'queued' && !variant.buildId"
            type="button" class="gl-btn-secondary" data-testid="clone-variant-build"
            :disabled="props.actingId === variant.id" @click="emit('build', variant)">构建</button>
          <button v-if="variant.state === 'failed' || variant.state === 'cancelled'" type="button"
            class="gl-btn-secondary" data-testid="clone-variant-retry"
            :disabled="props.actingId === variant.id" @click="emit('retry', variant)">重试</button>
          <button v-if="variant.state === 'queued' || variant.state === 'running'" type="button"
            class="gl-btn-secondary" data-testid="clone-variant-cancel"
            :disabled="props.actingId === variant.id" @click="emit('cancel', variant)">
            取消
          </button>
        </span>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.clone-variants-form { display: flex; gap: var(--space-xs); align-items: flex-end; flex-wrap: wrap; margin-bottom: var(--space-sm); }
.clone-variant-key { flex: 1; min-width: var(--space-none); }
.clone-variant-values { flex: 2; min-width: var(--space-none); }
.clone-error { color: var(--color-danger); }
.clone-loading, .clone-empty { color: var(--color-text-secondary); }
.clone-variant-list { list-style: none; margin: var(--space-none); padding: var(--space-none); display: grid; gap: var(--space-xs); }
.clone-variant { display: flex; align-items: center; gap: var(--space-xs); border: var(--border-width) solid var(--color-border); border-radius: var(--radius-md); padding: var(--space-xs) var(--space-sm); flex-wrap: wrap; }
.clone-variant-ordinal { font-variant-numeric: tabular-nums; }
.clone-variant-params { flex: 1; min-width: var(--space-none); font-size: var(--text-sm); color: var(--color-text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.clone-variant-state[data-state='failed'] { color: var(--color-danger); }
.clone-variant-state[data-state='cancelled'] { color: var(--color-text-secondary); }
.clone-variant-state[data-state='succeeded'] { color: var(--color-success); }
.clone-variant-build { font-size: var(--text-sm); color: var(--color-text-secondary); font-variant-numeric: tabular-nums; }
.clone-variant-actions { display: flex; gap: var(--space-xs); }
@media (max-width: 767px) {
  .clone-variant-key, .clone-variant-values { flex-basis: 100%; }
  .clone-variant { display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: start; }
  .clone-variant-params { white-space: normal; overflow-wrap: anywhere; }
  .clone-variant-state, .clone-variant-build, .clone-variant-actions { grid-column: 2; }
}
</style>
