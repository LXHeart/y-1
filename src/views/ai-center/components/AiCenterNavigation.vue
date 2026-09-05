<template>
  <nav class="center-tabs" role="tablist" aria-label="创作中心模块">
    <button v-for="section in visibleSections" :key="section.id" type="button" role="tab"
      :aria-selected="modelValue === section.id" :class="{ active: modelValue === section.id }"
      @click="emit('update:modelValue', section.id)">{{ section.label }}</button>
  </nav>
</template>

<script lang="ts">
/** 九板块全量清单（任务书 #76 卡 C）：个人能力（assistant/speech/…/keys）只在 AI 应用露出；
 * 草场内嵌创作面（platform 模式）由 AiCreationCenter 过滤为 create+library 后传入。
 * 值导出须放普通 script 块——<script setup> 不允许 ES module exports。 */
export const AI_CENTER_SECTIONS: ReadonlyArray<{ id: AiCenterSection; label: string }> = [
  { id: 'create', label: '开始创作' }, { id: 'assistant', label: '创作助手' },
  { id: 'speech', label: '语音转写' }, { id: 'image-studio', label: '图片编辑' },
  { id: 'image-gen', label: '图片生成' }, { id: 'video-studio', label: '视频工坊' },
  { id: 'runs', label: '运行记录' }, { id: 'library', label: '素材库' },
  // 任务书 #78 卡 C：keys 板块扩为「AI 与治理」（id 保留 keys 最小连坐）
  { id: 'keys', label: 'AI 与治理' },
]
</script>

<script setup lang="ts">
import { computed } from 'vue'

export type AiCenterSection = 'create' | 'runs' | 'assistant' | 'speech' | 'image-studio' | 'image-gen' | 'video-studio' | 'keys' | 'library'

const props = defineProps<{
  modelValue: AiCenterSection
  /** 板块过滤（受控）：草场内嵌创作面只留 create+library；缺省全量（AI 应用）。 */
  sections?: ReadonlyArray<{ id: AiCenterSection; label: string }>
}>()
const emit = defineEmits<{ 'update:modelValue': [section: AiCenterSection] }>()

const visibleSections = computed(() => props.sections ?? AI_CENTER_SECTIONS)
</script>

<style scoped>
.center-tabs { display: flex; gap: 4px; border-bottom: 1px solid var(--color-border); overflow-x: auto; }
.center-tabs button { flex: 0 0 auto; min-height: 40px; padding: 0 14px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--color-text-muted); cursor: pointer; }
.center-tabs button.active { border-bottom-color: var(--color-accent); color: var(--color-text); font-weight: 600; }
</style>
