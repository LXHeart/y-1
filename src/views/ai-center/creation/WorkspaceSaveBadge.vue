<template>
  <span class="save-badge" :data-state="state" :data-conflict="conflict ? 'true' : undefined" aria-live="polite">
    <template v-if="state === 'saving'">保存中…</template>
    <template v-else-if="state === 'saved'">已保存</template>
    <template v-else-if="state === 'error'">
      {{ conflict || '保存失败' }}·
      <button type="button" class="save-retry" data-testid="save-retry" @click="emit('retry')">重试</button>
    </template>
    <template v-else>未保存</template>
  </span>
</template>

<script setup lang="ts">
/**
 * 保存状态徽标（任务书 #92 §8.3）：`保存中…` / `已保存` / `保存失败·重试`（失败按钮可聚焦）。
 * 状态不只靠颜色表达（带文字与 data-state），aria-live 播报状态变化。
 */
defineProps<{ state: 'idle' | 'saving' | 'saved' | 'error'; conflict?: string }>()
const emit = defineEmits<{ retry: [] }>()
</script>

<style scoped>
.save-badge { display: inline-flex; align-items: center; gap: 4px; color: var(--color-text-muted); font-size: var(--text-xs); }
.save-badge[data-state="saving"] { color: var(--color-text-secondary); }
.save-badge[data-state="saved"] { color: var(--color-text-secondary); }
.save-badge[data-state="error"] { color: var(--color-danger); }
.save-retry { padding: 0; border: 0; background: transparent; color: var(--color-danger); font: inherit; text-decoration: underline; cursor: pointer; }
.save-retry:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 2px; border-radius: var(--radius-xs); }
</style>
