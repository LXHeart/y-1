<template>
  <section
    v-if="visible"
    class="proposal-panel gl-zone"
    aria-labelledby="proposal-panel-title"
    data-testid="text-proposal-panel"
  >
    <div class="proposal-head">
      <h3 id="proposal-panel-title">{{ title }}</h3>
      <div class="proposal-actions">
        <button
          v-if="!current"
          type="button"
          class="secondary-command"
          data-testid="proposal-prepare"
          :disabled="busy"
          @click="$emit('prepare', action)"
        >{{ preparing ? '生成建议中…' : actionLabel }}</button>
        <button
          v-if="current && current.status === 'ready'"
          type="button"
          class="secondary-command"
          data-testid="proposal-dismiss"
          :disabled="busy"
          @click="$emit('dismiss')"
        >取消预览</button>
      </div>
    </div>

    <p v-if="error" class="proposal-error" role="alert" data-testid="proposal-error">{{ error }}</p>

    <div v-if="current && current.result" class="proposal-body" data-testid="proposal-result">
      <dl v-if="changes.length" class="proposal-changes">
        <div v-for="(change, index) in changes" :key="index">
          <dt>变更 {{ index + 1 }}</dt>
          <dd>{{ change }}</dd>
        </div>
      </dl>

      <fieldset v-if="selectableFields.length" class="proposal-fields">
        <legend>选择要应用的字段（未选择字段保持原稿不变）</legend>
        <label v-for="field in selectableFields" :key="field.id">
          <input v-model="selected" type="checkbox" :value="field.id" :data-testid="`proposal-field-${field.id}`">
          <span>{{ field.label }}</span>
          <em v-if="field.hint">{{ field.hint }}</em>
        </label>
      </fieldset>

      <details v-if="current.result.body" class="proposal-diff">
        <summary>候选正文（{{ bodyLength }} 字）</summary>
        <pre class="proposal-body-preview">{{ current.result.body }}</pre>
      </details>

      <div class="proposal-apply-row">
        <button
          type="button"
          class="gl-btn-primary"
          data-testid="proposal-apply"
          :disabled="!selected.length || busy || expired"
          @click="$emit('apply', current.id, [...selected])"
        >{{ applying ? '应用中…' : '应用所选字段' }}</button>
        <span v-if="expired" class="proposal-expired" role="note">建议已过期，请重新生成</span>
        <span v-else class="proposal-meta">建议基于草稿 v{{ current.baseDraftVersion }}；应用前会再校验版本</span>
      </div>
    </div>

    <p v-else-if="current && current.status === 'preparing'" class="proposal-meta" aria-live="polite">
      正在生成建议…断线后可用同一请求安全重试，不会重复计费。
    </p>
    <p v-else-if="current && current.status === 'unknown'" class="proposal-meta" role="note">
      结果待核实：请稍后刷新建议状态；不会自动重新生成。
    </p>
    <p v-else-if="current && current.status === 'failed'" class="proposal-error" role="alert">
      建议生成失败{{ current.error ? `：${current.error.message}` : '' }}。可重新发起。
    </p>
    <p v-else-if="current && current.status === 'applied'" class="proposal-meta" aria-live="polite">
      已应用到草稿 v{{ current.appliedDraftVersion }}。
    </p>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { TextProposal } from '../../../types/creation-studio'

/**
 * 任务书 #101 C101-04（§8.1 正文区域）：原文／候选差异与字段选择。
 * 纯预览面板——prepare/apply 由父层经 useTextProposal + runExternalMutation 执行；
 * 无隐式应用（不选字段不禁用应用；取消预览原稿完全不变）。
 */
const props = defineProps<{
  action: 'adapt-body' | 'suggest-metadata'
  visible: boolean
  preparing: boolean
  applying: boolean
  error: string
  current: TextProposal | null
  now?: number
}>()

const emit = defineEmits<{
  prepare: [action: 'adapt-body' | 'suggest-metadata']
  dismiss: []
  apply: [id: string, fields: Array<'title' | 'body' | 'summary'>]
}>()

const selected = ref<Array<'title' | 'body' | 'summary'>>([])

const title = computed(() => props.action === 'adapt-body' ? '改编建议' : '标题／摘要建议')
const actionLabel = computed(() => props.action === 'adapt-body' ? '发起全文改编建议' : '生成标题／摘要建议')
const busy = computed(() => props.preparing || props.applying)
const changes = computed(() => props.current?.result?.changes ?? [])
const bodyLength = computed(() => [...(props.current?.result?.body ?? '')].length)
const expired = computed(() => props.current != null && props.current.status === 'ready'
  && new Date(props.current.expiresAt).getTime() <= (props.now ?? Date.now()))

const selectableFields = computed(() => {
  const result = props.current?.result
  if (!result) return []
  const fields: Array<{ id: 'title' | 'body' | 'summary'; label: string; hint: string }> = []
  if (result.title != null) fields.push({ id: 'title', label: '标题', hint: '替换文章标题（导航名不变）' })
  if (result.body != null) fields.push({ id: 'body', label: '正文', hint: '整篇替换为候选改编' })
  if (result.summary != null) fields.push({ id: 'summary', label: '摘要', hint: '写入交付摘要' })
  return fields
})

// 新建议到达时默认全选可应用字段；用户随后可手动缩减。
watch(() => props.current?.id, () => {
  selected.value = selectableFields.value.map((field) => field.id)
}, { immediate: true })
</script>

<style scoped>
.proposal-panel { display: grid; gap: var(--space-sm); }
.proposal-head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-sm); flex-wrap: wrap; }
.proposal-head h3 { margin: 0; font-size: 1rem; color: var(--color-text); }
.proposal-actions { display: flex; gap: var(--space-xs); }
.secondary-command { min-height: 34px; padding: 0 var(--space-sm); border: 1px solid var(--color-border);
  border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text-secondary); cursor: pointer; }
.secondary-command:disabled { opacity: 0.5; cursor: not-allowed; }
.proposal-error { margin: 0; color: var(--color-danger); font-size: 0.84rem; }
.proposal-body { display: grid; gap: var(--space-sm); }
.proposal-changes { display: grid; gap: 6px; margin: 0; }
.proposal-changes > div { display: grid; grid-template-columns: 64px 1fr; gap: 8px; }
.proposal-changes dt { color: var(--color-text-muted); font-size: var(--text-xs); }
.proposal-changes dd { margin: 0; color: var(--color-text-secondary); font-size: 0.86rem; }
.proposal-fields { display: grid; gap: 6px; margin: 0; padding: var(--space-sm); border: 1px solid var(--color-border);
  border-radius: var(--radius-sm); }
.proposal-fields legend { color: var(--color-text-muted); font-size: var(--text-xs); padding: 0 4px; }
.proposal-fields label { display: flex; align-items: baseline; gap: 8px; color: var(--color-text-secondary);
  font-size: 0.88rem; }
.proposal-fields em { color: var(--color-text-muted); font-size: var(--text-xs); font-style: normal; }
.proposal-diff summary { cursor: pointer; color: var(--color-text-secondary); font-size: 0.86rem; }
.proposal-body-preview { margin: 8px 0 0; padding: var(--space-sm); max-height: 260px; overflow: auto;
  border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--surface-furrow);
  color: var(--color-text); font-size: 0.84rem; white-space: pre-wrap; overflow-wrap: anywhere; }
.proposal-apply-row { display: flex; align-items: center; gap: var(--space-sm); flex-wrap: wrap; }
.gl-btn-primary[data-testid="proposal-apply"] { min-height: 36px; padding: 0 var(--space-md);
  border-radius: var(--radius-sm); cursor: pointer; }
.gl-btn-primary[data-testid="proposal-apply"]:disabled { opacity: 0.45; cursor: not-allowed; }
.proposal-meta { margin: 0; color: var(--color-text-muted); font-size: var(--text-xs); }
.proposal-expired { color: var(--color-warning); font-size: var(--text-xs); }
@media (max-width: 767px) { .proposal-changes > div { grid-template-columns: 1fr; } }
</style>
