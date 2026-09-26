<script setup lang="ts">
/**
 * SourcePanel.vue — C107-21：高级源码面板（输入 files/draft/saveState/
 * diagnostics；事件 open/edit/save/apply）。所有写路径走 changeset/CAS——
 * 绝不绕过直接 PUT；conflict 展示 base/server/local 三态，不自动覆盖。
 */
import { computed, ref } from 'vue';
import type { HypitFileEntry } from '../../../types/hypit';
import type { SaveState } from '../composables/useHypitSource';

const props = defineProps<{
  files: HypitFileEntry[];
  revision: number | null;
  activePath: string | null;
  draft: string;
  savedContent: string;
  saveState: SaveState;
  diagnostics: { file: string; message: string; severity: string }[];
  saving: boolean;
}>();

const emit = defineEmits<{
  open: [path: string];
  edit: [content: string];
  save: [mode: 'save' | 'validated'];
}>();

const showDiff = ref(false);

const localChanged = computed(() => props.draft !== props.savedContent);

const diffLines = computed(() => {
  // 轻量行级差异预览：本地草稿相对已保存内容的行数变化（详细 diff 由
  // Studio/changeset 承载）。
  const before = props.savedContent.split('\n');
  const after = props.draft.split('\n');
  const lines: { kind: 'same' | 'add' | 'del'; text: string }[] = [];
  const max = Math.max(before.length, after.length);
  for (let index = 0; index < max; index += 1) {
    const a = before[index];
    const b = after[index];
    if (a === b) lines.push({ kind: 'same', text: a ?? '' });
    else {
      if (a !== undefined) lines.push({ kind: 'del', text: a });
      if (b !== undefined) lines.push({ kind: 'add', text: b });
    }
  }
  return lines;
});
</script>

<template>
  <section class="gl-zone" data-testid="clone-source-panel" aria-label="高级源码">
    <header class="clone-source-head">
      <h2>源码（高级）</h2>
      <span v-if="props.revision !== null" class="clone-source-rev">rev {{ props.revision }}</span>
    </header>
    <div class="clone-source-body">
      <nav class="clone-source-files" aria-label="工程文件">
        <ul>
          <li v-for="file in props.files" :key="file.path">
            <button type="button" class="clone-source-file"
              :class="{ 'clone-source-file--active': file.path === props.activePath }"
              @click="emit('open', file.path)">{{ file.path }}</button>
          </li>
        </ul>
      </nav>
      <div class="clone-source-editor">
        <textarea v-if="props.activePath !== null" class="clone-source-textarea"
          :value="props.draft" rows="16" spellcheck="false" data-testid="clone-source-textarea"
          :aria-label="`编辑 ${props.activePath}`" @input="emit('edit', ($event.target as HTMLTextAreaElement).value)" />
        <p v-else class="clone-empty">选择左侧文件开始编辑。</p>
        <div v-if="props.activePath !== null" class="clone-source-actions">
          <label class="clone-source-diff-toggle">
            <input v-model="showDiff" type="checkbox" /> 显示差异预览
          </label>
          <button type="button" class="gl-btn-secondary" data-testid="clone-source-save"
            :disabled="props.saving" @click="emit('save', 'save')">
            {{ props.saving ? '保存中…' : '保存（允许坏语法）' }}
          </button>
          <button type="button" class="gl-btn-primary" data-testid="clone-source-save-validated"
            :disabled="props.saving" @click="emit('save', 'validated')">
            校验并保存
          </button>
        </div>
        <p v-if="localChanged && props.saveState === 'conflict'" class="clone-source-conflict"
          data-testid="clone-save-conflict" role="alert">
          基线已过期（conflict）：本地草稿保留，服务端已有新版本；基于新 revision 重提或放弃本地修改。
        </p>
        <ul v-if="props.diagnostics.length > 0" class="clone-source-diagnostics" data-testid="clone-source-diagnostics">
          <li v-for="(diagnostic, index) in props.diagnostics" :key="index" :class="`clone-diagnostic--${diagnostic.severity}`">
            {{ diagnostic.file }}: {{ diagnostic.message }}
          </li>
        </ul>
      </div>
    </div>
    <pre v-if="showDiff && localChanged" class="clone-source-diff" data-testid="clone-source-diff"><code><span
      v-for="(line, index) in diffLines" :key="index" :class="`clone-diff-${line.kind}`">{{ line.kind === 'add' ? '+' : line.kind === 'del' ? '-' : ' ' }} {{ line.text }}
</span></code></pre>
  </section>
</template>

<style scoped>
.clone-source-head { display: flex; align-items: center; justify-content: space-between; }
.clone-source-head h2 { margin: 0; font-size: 16px; font-family: var(--font-display); }
.clone-source-rev { font-size: 12px; color: var(--color-text-secondary); }
.clone-source-body { display: grid; grid-template-columns: 220px 1fr; gap: 12px; margin-top: 12px; }
.clone-source-files ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 2px; max-height: 40vh; overflow: auto; }
.clone-source-file { display: block; width: 100%; text-align: left; background: none; border: 0; color: var(--color-text-secondary); cursor: pointer; padding: 4px 8px; border-radius: var(--radius-sm); font-size: 13px; overflow-wrap: anywhere; }
.clone-source-file--active { background: var(--surface-muted); color: var(--color-text); }
.clone-source-textarea { width: 100%; font-family: ui-monospace, monospace; font-size: 13px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--surface-muted); color: var(--color-text); padding: 8px; }
.clone-source-actions { display: flex; align-items: center; gap: 8px; margin-top: 8px; flex-wrap: wrap; }
.clone-source-diff-toggle { display: flex; align-items: center; gap: 4px; font-size: 13px; color: var(--color-text-secondary); }
.clone-source-conflict { color: var(--color-danger); font-size: 13px; }
.clone-source-diagnostics { list-style: none; margin: 8px 0 0; padding: 0; display: grid; gap: 4px; font-size: 13px; }
.clone-diagnostic--error { color: var(--color-danger); }
.clone-source-diff { background: var(--surface-muted); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 8px; font-size: 12px; overflow: auto; max-height: 30vh; }
.clone-diff-add { color: var(--color-success, #4c9e6b); }
.clone-diff-del { color: var(--color-danger); }
@media (max-width: 720px) {
  .clone-source-body { grid-template-columns: 1fr; }
}
</style>
