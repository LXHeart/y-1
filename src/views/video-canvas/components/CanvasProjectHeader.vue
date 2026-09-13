<script setup lang="ts">
import { computed } from 'vue'
import WorkspaceSaveBadge from '../../ai-center/creation/WorkspaceSaveBadge.vue'
import type { CreationProject } from '../../../types/creation'

type SaveState = 'idle' | 'pending' | 'saving' | 'saved' | 'conflict' | 'error'
const props = defineProps<{
  project: CreationProject | null
  shotCount: number
  duration?: number
  contentState: SaveState
  canvasState: SaveState
  deliveryState: SaveState
  readonly?: boolean
  binding?: boolean
}>()
const emit = defineEmits<{ back: []; quick: []; retry: []; reloadCanvas: [] }>()
const source = computed(() => {
  const project = props.project
  if (!project) return '正在读取项目来源'
  const label = project.workspace.sourceLabel
  if (project.sourceType === 'task') return `${label || '任务创作'}${project.taskVersion ? ` · 任务 v${project.taskVersion}` : ''}`
  if (project.sourceType === 'store') return label || '门店创作'
  return label || '自由创作'
})
</script>

<template>
  <header class="canvas-project-header" data-test="canvas-project-header">
    <div class="canvas-project-identity">
      <button type="button" class="gl-btn-ghost" @click="emit('back')">返回创作中心</button>
      <div class="canvas-project-title">
        <p class="field-note" data-test="canvas-project-source">{{ source }} · 专业模式</p>
        <h2>{{ project?.title || '创作画布' }}</h2>
        <p v-if="shotCount" class="field-note gl-num">{{ shotCount }} 镜{{ duration ? ` · ${duration} 秒` : '' }}</p>
      </div>
      <button type="button" class="gl-btn-ghost" data-test="switch-quick-mode" @click="emit('quick')">快速模式</button>
    </div>
    <p v-if="binding" class="field-note" role="status" data-test="canvas-binding">工作区连接中…</p>
    <div v-else-if="project" class="canvas-save-summary" data-test="canvas-save-summary">
      <span :data-test="contentState === 'pending' ? 'canvas-dirty-badge' : undefined">镜头 <WorkspaceSaveBadge :state="contentState === 'conflict' ? 'error' : contentState" :conflict="contentState === 'conflict' ? '版本已变化，请打开属性处理' : undefined" :readonly="readonly" @retry="emit('retry')" /></span>
      <span>画布 <WorkspaceSaveBadge :state="canvasState" :conflict="canvasState === 'conflict' ? '已保留本地布局，载入远端会放弃本地修改' : undefined" :readonly="readonly" @retry="emit('retry')" @reload="emit('reloadCanvas')" /></span>
      <span>交付 <WorkspaceSaveBadge :state="deliveryState === 'conflict' ? 'error' : deliveryState" :conflict="deliveryState === 'conflict' ? '版本已变化，已保留交付编辑' : undefined" :readonly="readonly" @retry="emit('retry')" /></span>
    </div>
    <slot />
  </header>
</template>
