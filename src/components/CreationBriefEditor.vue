<script setup lang="ts">
import { computed } from 'vue'
import type { CreationBrief, CreationProcessingMode } from '../types/creation'

const props = defineProps<{ modelValue: CreationBrief | null; review?: boolean; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: CreationBrief] }>()
function update(patch: Partial<CreationBrief>): void {
  emit('update:modelValue', { processingMode: 'create', ...props.modelValue, ...patch })
}
function value(event: Event): string { return (event.target as HTMLInputElement).value }
/** 任务2 §2.3：加工方式与来源正交——分段控件，默认从资料创作。 */
const MODES: Array<{ id: CreationProcessingMode; label: string; hint: string }> = [
  { id: 'create', label: '从资料创作', hint: '按事实与素材生成新内容' },
  { id: 'adapt', label: '改编已有内容', hint: '调整结构与表达，保留事实与来源' },
  { id: 'format', label: '原文排版', hint: '保留原文文字，只做排版与素材整理' },
]
const mode = computed(() => props.modelValue?.processingMode ?? 'create')
function experience(id: string) {
  return computed({
    get: () => props.modelValue?.facts?.find(fact => fact.id === id)?.statement ?? '',
    set: (statement: string) => {
      const facts = (props.modelValue?.facts ?? []).filter(fact => fact.id !== id)
      if (statement.trim()) facts.push({ id, statement, basis: 'user-confirmed' })
      update({ facts })
    },
  })
}
const positives = experience('review-positives')
const negatives = experience('review-negatives')
</script>

<template>
  <details class="creation-brief gl-field" :open="review || undefined">
    <summary>事实与补充要求</summary>
    <fieldset :disabled="disabled" class="brief-fields">
      <fieldset class="gl-row brief-modes">
        <legend>加工方式</legend>
        <label v-for="item in MODES" :key="item.id" class="brief-mode" :class="{ active: mode === item.id }">
          <input
            type="radio"
            name="brief-processing-mode"
            :value="item.id"
            :checked="mode === item.id"
            :data-test="`brief-mode-${item.id}`"
            @change="update({ processingMode: item.id })"
          >
          {{ item.label }}
        </label>
        <p class="brief-mode-hint">{{ MODES.find(item => item.id === mode)?.hint }}</p>
      </fieldset>
      <label class="gl-row">表达身份
        <select :value="modelValue?.authorRole ?? ''" @change="update({ authorRole: value($event) })">
          <option value="">待确认</option>
          <option value="consumer">真实体验用户</option>
          <option value="merchant">商家</option>
          <option value="commercial-creator">商业合作作者</option>
          <option value="researcher">资料整理者</option>
        </select>
      </label>
      <label class="gl-row">已确认的经历
        <textarea :value="modelValue?.confirmedExperience ?? ''" rows="2" maxlength="500"
          @input="update({ confirmedExperience: value($event) })" />
      </label>
      <template v-if="review">
        <label class="gl-row">已确认的优点<textarea v-model="positives" rows="2" maxlength="500" /></label>
        <label class="gl-row">已确认的不足<textarea v-model="negatives" rows="2" maxlength="500" /></label>
      </template>
      <label class="gl-row">补充要求
        <textarea :value="modelValue?.extraInstructions ?? ''" rows="3" maxlength="2000"
          @input="update({ extraInstructions: value($event) })" />
      </label>
    </fieldset>
  </details>
</template>

<style scoped>
.creation-brief { padding-block: var(--space-md); color: var(--color-text-secondary); font-size: var(--text-sm); }
.creation-brief summary { cursor: pointer; color: var(--color-text); }
.brief-fields { display: grid; gap: var(--space-sm); margin: var(--space-sm) 0 0; padding: 0; border: 0; min-width: 0; }
.brief-fields label { min-width: 0; align-items: start; }
.brief-fields textarea, .brief-fields select { font: inherit; width: 100%; min-width: 0; box-sizing: border-box; }
.brief-modes { display: flex; flex-wrap: wrap; gap: var(--space-xs); align-items: center; border: 0; padding: 0; }
.brief-modes legend { float: left; margin-right: var(--space-sm); font-size: var(--text-sm); color: var(--color-text); }
.brief-mode {
  display: inline-flex; align-items: center; gap: 4px; padding: 2px var(--space-sm); min-height: 30px;
  border: 1px solid var(--color-border); border-radius: var(--radius-pill); cursor: pointer; font-size: var(--text-xs);
}
.brief-mode.active { border-color: var(--color-accent); color: var(--color-accent); background: color-mix(in srgb, var(--color-accent) 10%, transparent); }
.brief-mode-hint { flex-basis: 100%; margin: 0; color: var(--color-text-muted); }
</style>
