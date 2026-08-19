<template>
  <nav class="center-tabs" role="tablist" aria-label="创作中心模块">
    <button v-for="section in sections" :key="section.id" type="button" role="tab"
      :aria-selected="modelValue === section.id" :class="{ active: modelValue === section.id }"
      @click="emit('update:modelValue', section.id)">{{ section.label }}</button>
  </nav>
</template>

<script setup lang="ts">
export type AiCenterSection = 'create' | 'runs' | 'assistant' | 'speech' | 'image-studio' | 'video-studio' | 'keys' | 'library'

defineProps<{ modelValue: AiCenterSection }>()
const emit = defineEmits<{ 'update:modelValue': [section: AiCenterSection] }>()
const sections: ReadonlyArray<{ id: AiCenterSection; label: string }> = [
  { id: 'create', label: '开始创作' }, { id: 'assistant', label: '创作助手' },
  { id: 'speech', label: '语音转写' }, { id: 'image-studio', label: '图片编辑' },
  { id: 'video-studio', label: '视频工坊' }, { id: 'runs', label: '运行记录' },
  { id: 'library', label: '素材库' }, { id: 'keys', label: '模型密钥' },
]
</script>

<style scoped>
.center-tabs { display: flex; gap: 4px; border-bottom: 1px solid var(--color-border); overflow-x: auto; }
.center-tabs button { flex: 0 0 auto; min-height: 40px; padding: 0 14px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--color-text-muted); cursor: pointer; }
.center-tabs button.active { border-bottom-color: var(--color-accent); color: var(--color-text); font-weight: 600; }
</style>
