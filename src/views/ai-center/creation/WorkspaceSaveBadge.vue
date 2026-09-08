<template>
  <span class="save-badge" :data-state="state" :data-conflict="conflict ? 'true' : undefined" aria-live="polite">
    <template v-if="readonly">{{ conflict || '当前版本仅可查看' }}</template>
    <template v-else-if="state === 'pending'">待保存…</template>
    <template v-else-if="state === 'saving'">保存中…</template>
    <template v-else-if="state === 'saved'">已保存</template>
    <template v-else-if="state === 'conflict'">
      <span>{{ conflict || '草稿存在冲突' }}</span>
      <button type="button" class="save-retry" @click="emit('reload')">载入远端版本</button>
      <button type="button" class="save-retry" data-testid="save-retry" @click="emit('retry')">保留当前编辑</button>
    </template>
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
defineProps<{ state: 'idle' | 'pending' | 'saving' | 'saved' | 'conflict' | 'error'; conflict?: string; readonly?: boolean }>()
const emit = defineEmits<{ retry: []; reload: [] }>()
</script>

<style scoped>
.save-badge { display: inline-flex; flex-wrap: wrap; align-items: center; gap: var(--space-xs); color: var(--color-text-muted); font-size: var(--text-xs); }
.save-badge[data-state="saving"] { color: var(--color-text-secondary); }
.save-badge[data-state="pending"] { color: var(--color-warning); }
.save-badge[data-state="saved"] { color: var(--color-text-secondary); }
.save-badge[data-state="conflict"] { color: var(--color-warning); }
.save-badge[data-state="error"] { color: var(--color-danger); }
.save-retry { padding: 0; border: 0; background: transparent; color: var(--color-danger); font: inherit; text-decoration: underline; cursor: pointer; }
.save-retry:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 2px; border-radius: var(--radius-xs); }
</style>
