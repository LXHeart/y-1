<script setup lang="ts">
import { computed } from 'vue'
import type { SourceDocument } from '../../../types/creation-studio'
const props = defineProps<{ source: SourceDocument; modelValue: string[]; disabled?: boolean; error?: string }>()
const emit = defineEmits<{ 'update:modelValue': [ids: string[]] }>()
const count = computed(() => props.source.blocks.filter(block => props.modelValue.includes(block.id))
  .reduce((sum, block) => sum + [...block.text].length, 0))
function toggle(id: string, selected: boolean): void {
  emit('update:modelValue', selected ? [...props.modelValue, id] : props.modelValue.filter(value => value !== id))
}
</script>

<template>
  <details class="studio-range gl-zone" :open="Boolean(error)">
    <summary>本次 AI 处理范围：{{ modelValue.length }} 段 / {{ count }} 字符</summary>
    <p class="studio-hint">每次最多 200 段、8,000 字符。未选择的内容保留在原稿中，不参与本次策划。</p>
    <div class="studio-actions">
      <button type="button" class="gl-btn-secondary" :disabled="disabled" @click="emit('update:modelValue', source.blocks.map(block => block.id))">全选</button>
      <button type="button" class="gl-btn-secondary" :disabled="disabled" @click="emit('update:modelValue', [])">清空选择</button>
    </div>
    <div class="studio-range-blocks">
      <label v-for="block in source.blocks" :key="block.id">
        <input type="checkbox" :checked="modelValue.includes(block.id)" :disabled="disabled"
          @change="toggle(block.id, ($event.target as HTMLInputElement).checked)">
        <span>{{ block.position }}. {{ block.text }}</span>
      </label>
    </div>
    <p v-if="error" class="studio-error" role="alert">{{ error }}</p>
  </details>
</template>

<style scoped>
.studio-range summary { cursor: pointer; font-weight: var(--weight-label); }
.studio-range-blocks { display: grid; gap: var(--space-xs); max-height: var(--layout-rail); overflow: auto; }
.studio-range-blocks label { display: flex; align-items: flex-start; gap: var(--space-xs); padding: var(--space-xs) var(--space-none); }
.studio-range-blocks span { white-space: pre-wrap; overflow-wrap: anywhere; min-width: 0; }
</style>
