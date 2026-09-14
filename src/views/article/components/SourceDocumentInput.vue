<template>
  <section class="source-input gl-zone studio-panel" aria-labelledby="source-input-title" data-testid="source-document-input">
    <div class="source-input-head">
      <h3 id="source-input-title">原稿输入</h3>
      <p class="source-input-note">{{ note }}</p>
    </div>

    <div v-if="state === 'saved'" class="source-imported" data-testid="source-imported" aria-live="polite">
      <p>原稿已导入并进入正文编辑。来源不可变，重新导入会创建新版本来源。</p>
      <span class="source-hash gl-num">SHA-256 {{ importedHash?.slice(0, 12) }}…</span>
    </div>

    <template v-else>
      <div class="segmented source-kind" role="group" aria-label="原稿格式">
        <button
          v-for="kindOption in KIND_OPTIONS"
          :key="kindOption.id"
          type="button"
          :class="{ active: kind === kindOption.id }"
          :aria-pressed="kind === kindOption.id"
          :disabled="state === 'saving' || disabled"
          @click="kind = kindOption.id"
        >{{ kindOption.label }}</button>
      </div>

      <label class="source-field">
        <span id="source-text-label">粘贴或输入原稿（UTF-8 文本）</span>
        <textarea
          v-model="text"
          data-testid="source-text"
          aria-labelledby="source-text-label"
          rows="8"
          :readonly="state === 'saving' || disabled"
          :aria-invalid="overLimit"
          aria-describedby="source-input-help"
          placeholder="粘贴文章、笔记或已有稿件全文"
          @input="onTextEdited"
        />
        <span id="source-input-help" class="source-counter" :class="{ 'source-counter-over': overLimit }" aria-live="polite">
          {{ codePointCount }} / {{ MAX_CODE_POINTS }} 字符（超出上限将拒绝导入，不做截断）
        </span>
      </label>

      <div class="source-actions">
        <label class="file-pick">
          <input
            type="file"
            accept=".txt,.md,text/plain,text/markdown"
            data-testid="source-file"
            :disabled="state === 'saving' || disabled"
            @change="onFilePicked"
          >
          <span>选择 TXT / MD 文件</span>
        </label>
        <button
          type="button"
          class="gl-btn-primary source-import"
          data-testid="source-import"
          :disabled="!canImport || state === 'saving' || disabled"
          @click="requestImport"
        >{{ state === 'saving' ? '导入中…' : '导入并编辑' }}</button>
      </div>

      <p v-if="state === 'error'" class="source-error" role="alert" data-testid="source-error">{{ error }}</p>
      <p v-else-if="fileError" class="source-error" role="alert" data-testid="source-file-error">{{ fileError }}</p>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useStudioGuard } from '../../../lib/creation-studio-http'
import type { SourceInputState } from '../composables/useSourceDocument'

/**
 * 任务书 #101 C101-03（§8.1 原稿输入）：模式（纯文本/Markdown）、粘贴／选 TXT/MD、
 * 明确字数范围。展示型组件——导入编排（先建草稿再 POST 来源）在视图层，见
 * ArticleCreationView.onSourceImportRequested。
 *
 * 浏览器严格 UTF-8 解码（TextDecoder fatal）——非 UTF-8／损坏文件报错不清空已有输入；
 * state 由父层状态机驱动（empty/editing/saving/saved/error）。
 */
const props = defineProps<{
  state: SourceInputState
  error: string
  importedHash: string | null
  note: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  import: [input: { kind: 'plain-text' | 'markdown'; text: string }]
  edit: []
}>()

const MAX_CODE_POINTS = 30_000
const KIND_OPTIONS = [
  { id: 'plain-text' as const, label: '纯文本' },
  { id: 'markdown' as const, label: 'Markdown' },
]

const text = ref('')
const kind = ref<'plain-text' | 'markdown'>('plain-text')
const fileError = ref('')
let inputRevision = 0
const guard = useStudioGuard()
guard.onInvalidate(() => { inputRevision += 1; text.value = ''; kind.value = 'plain-text'; fileError.value = '' })
function onTextEdited(): void { inputRevision += 1; fileError.value = ''; emit('edit') }

const codePointCount = computed(() => [...text.value].length)
const overLimit = computed(() => codePointCount.value > MAX_CODE_POINTS || new TextEncoder().encode(text.value).length > 128 * 1024)
const canImport = computed(() => text.value.trim().length > 0 && !overLimit.value)

watch(() => props.state, (state, previous) => {
  if (previous === 'error' && state === 'editing') fileError.value = ''
})

async function onFilePicked(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 一次只处理一个导入：重置 value 允许同一文件再次选择；读取失败不覆盖已有文本。
  input.value = ''
  if (!file) return
  const revision = ++inputRevision
  const valid = guard.capture()
  if (!/\.(txt|md)$/i.test(file.name)) { fileError.value = '请选择 TXT 或 MD 文本文件'; return }
  if (file.size > 128 * 1024) {
    fileError.value = '文件超过 128KiB 上限，请拆分后导入'
    return
  }
  try {
    const buffer = await file.arrayBuffer()
    if (!valid() || revision !== inputRevision || props.disabled || props.state === 'saving') return
    const decoded = new TextDecoder('utf-8', { fatal: true }).decode(buffer)
    if (!decoded.trim()) {
      fileError.value = '文件内容为空'
      return
    }
    fileError.value = ''
    text.value = decoded
    kind.value = /\.md$/i.test(file.name) ? 'markdown' : 'plain-text'
    emit('edit')
  } catch {
    if (valid() && revision === inputRevision) fileError.value = '文件不是有效的 UTF-8 文本，未导入（此前输入已保留）'
  }
}

function requestImport(): void {
  if (!canImport.value || props.state === 'saving' || props.disabled) return
  inputRevision += 1
  emit('import', { kind: kind.value, text: text.value })
}
</script>

<style scoped>
.source-input { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--space-sm); }
.source-input > * { min-width: 0; }
.source-input-head { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-sm); flex-wrap: wrap; }
.source-input-head h3 { margin: 0; font-size: var(--type-section-title); color: var(--color-text); }
.source-input-note { margin: 0; color: var(--color-text-muted); font-size: var(--type-caption); }
.segmented { display: inline-flex; width: fit-content; padding: var(--space-xxs); gap: var(--space-xxs);
  background: var(--surface-muted); border: var(--border-width) solid var(--color-border); border-radius: var(--radius-md); }
.segmented button { min-width: calc(var(--space-xxl) * 2); min-height: var(--control-height); padding: 0 var(--space-sm); border: 0;
  border-radius: var(--radius-sm); color: var(--color-text-secondary); background: transparent; cursor: pointer; }
.segmented button.active { background: var(--color-accent); color: var(--color-on-accent); font-weight: var(--weight-heading); }
.segmented button:disabled { cursor: default; opacity: 1; }
.source-field { display: grid; gap: var(--space-xxs); color: var(--color-text-secondary); font-size: var(--type-label); }
.source-field textarea { width: 100%; box-sizing: border-box; border: var(--border-width) solid var(--color-border-control);
  border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text);
  padding: var(--space-xs) var(--space-sm); resize: vertical; min-height: calc(var(--space-section) * 2); }
.source-counter { color: var(--color-text-muted); font-size: var(--type-caption); }
.source-counter-over { color: var(--color-danger); font-weight: var(--weight-heading); }
.source-actions { display: flex; align-items: center; justify-content: space-between; gap: var(--space-sm); flex-wrap: wrap; }
.file-pick { display: inline-flex; align-items: center; flex-wrap: wrap; max-width: 100%; gap: var(--space-xxs); color: var(--color-text-secondary);
  font-size: var(--type-caption); cursor: pointer; }
.file-pick input { max-width: 100%; }
.source-import { min-height: var(--control-height); padding: 0 var(--space-md); border-radius: var(--radius-sm); cursor: pointer; }
.source-import:disabled { opacity: 1; cursor: not-allowed; }
.source-error { margin: 0; color: var(--color-danger); font-size: var(--type-body); }
.source-imported { display: grid; gap: var(--space-xxs); padding: var(--space-sm);
  border: var(--border-width) solid var(--color-border); border-radius: var(--radius-md); background: var(--surface-muted);
  color: var(--color-text-secondary); }
.source-imported p { margin: 0; }
.source-hash { color: var(--color-text-muted); font-size: var(--type-caption); }
@media (max-width: 767px) { .source-actions { align-items: stretch; flex-direction: column; } }
</style>
