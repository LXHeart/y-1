<script setup lang="ts">
import type { CreationDeclarations, CreationDeclarationState } from '../types/creation'
import { creationDeclarations } from '../lib/creation-delivery'

const props = defineProps<{ modelValue: CreationDeclarations; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: CreationDeclarations] }>()
const fields = [
  { key: 'aiGenerated', label: 'AI 使用', confirmed: '已使用 AI', absent: '不适用' },
  { key: 'commercial', label: '商业合作', confirmed: '存在商业合作', absent: '无商业合作' },
  { key: 'original', label: '原创声明', confirmed: '已确认原创权属', absent: '不作原创声明' },
] as const
function update(key: keyof CreationDeclarations, event: Event): void {
  emit('update:modelValue', { ...creationDeclarations(props.modelValue),
    [key]: (event.target as HTMLSelectElement).value as CreationDeclarationState })
}
</script>

<template>
  <fieldset class="creation-declarations gl-field" :disabled="disabled">
    <legend>内容声明</legend>
    <div class="declaration-fields">
      <label v-for="field in fields" :key="field.key">
        <span>{{ field.label }}</span>
        <select :aria-label="field.label" :value="modelValue[field.key] ?? 'pending'" @change="update(field.key, $event)">
          <option value="pending">待确认</option>
          <option value="confirmed">{{ field.confirmed }}</option>
          <option value="not-applicable">{{ field.absent }}</option>
        </select>
      </label>
    </div>
    <p class="declaration-platform-state">目标平台声明状态：待确认</p>
  </fieldset>
</template>

<style scoped>
.creation-declarations { margin: 0; padding: var(--space-md) 0; border: 0; min-width: 0; }
.creation-declarations legend { padding: 0; color: var(--color-text); font-size: var(--text-sm); }
.declaration-fields { display: flex; flex-wrap: wrap; gap: var(--space-md); }
.declaration-fields label { display: grid; flex: 1; min-width: min(100%, 12rem); gap: var(--space-xs); color: var(--color-text-secondary); font-size: var(--text-sm); }
.declaration-fields select { width: 100%; min-width: 0; font: inherit; }
.declaration-platform-state { margin: var(--space-sm) 0 0; color: var(--color-text-muted); font-size: var(--text-xs); }
</style>
