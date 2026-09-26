<script setup lang="ts">
/**
 * ProjectHeader.vue — C107-21：工程头（输入 project/saveState；事件
 * rename/openStudio/export）。不执行长任务——只发动作事件。
 */
import { ref } from 'vue';
import type { HypitProject } from '../../../types/hypit';

export type HeaderSaveState = 'idle' | 'dirty' | 'saving' | 'saved' | 'conflict' | 'error';

const props = defineProps<{
  project: HypitProject;
  saveState: HeaderSaveState;
  exporting: boolean;
}>();

const emit = defineEmits<{
  rename: [title: string];
  openStudio: [];
  export: [];
}>();

const editing = ref(false);
const draftTitle = ref(props.project.title);

function saveRename(): void {
  const next = draftTitle.value.trim();
  if (next.length > 0 && next !== props.project.title) emit('rename', next);
  editing.value = false;
}

const saveLabels: Record<HeaderSaveState, string> = {
  idle: '',
  dirty: '有未保存修改',
  saving: '保存中…',
  saved: '已保存',
  conflict: '保存冲突',
  error: '保存失败',
};
</script>

<template>
  <header class="gl-zone clone-header" data-testid="clone-project-header">
    <div class="clone-header-main">
      <template v-if="editing">
        <input v-model="draftTitle" type="text" maxlength="60" class="clone-rename-input"
          :aria-label="`重命名 ${props.project.title}`" @keydown.enter="saveRename" @keydown.esc="editing = false" />
        <button type="button" class="gl-btn-secondary" @click="saveRename">确定</button>
      </template>
      <template v-else>
        <h1 class="clone-header-title">{{ props.project.title }}</h1>
        <button type="button" class="gl-btn-secondary clone-rename" @click="draftTitle = props.project.title; editing = true">
          重命名
        </button>
      </template>
      <span v-if="props.saveState !== 'idle'" class="clone-save-state"
        :data-testid="props.saveState === 'conflict' ? 'clone-save-conflict' : 'clone-save-state'"
        :class="{ 'clone-save-state--warn': props.saveState === 'conflict' || props.saveState === 'error' }"
        aria-live="polite">{{ saveLabels[props.saveState] }}</span>
    </div>
    <div class="clone-header-meta">
      <span>由 Hypit 驱动 · rev {{ props.project.revision }}</span>
      <button type="button" class="gl-btn-secondary" data-testid="clone-open-studio" @click="emit('openStudio')">
        专业编辑器
      </button>
      <button type="button" class="gl-btn-primary" data-testid="clone-export" :disabled="props.exporting"
        @click="emit('export')">{{ props.exporting ? '导出中…' : '导出工程包' }}</button>
    </div>
  </header>
</template>

<style scoped>
.clone-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.clone-header-main { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.clone-header-title { margin: 0; font-family: var(--font-display); font-size: 20px; }
.clone-rename-input { font: inherit; padding: 4px 8px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--surface-muted); color: var(--color-text); }
.clone-save-state { font-size: 12px; color: var(--color-text-secondary); }
.clone-save-state--warn { color: var(--color-danger); }
.clone-header-meta { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--color-text-secondary); }
</style>
