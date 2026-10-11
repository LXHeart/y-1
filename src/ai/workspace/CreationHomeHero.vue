<template>
  <section class="workspace-hero" aria-labelledby="workspace-home-title">
    <header class="workspace-hero-head">
      <h2 id="workspace-home-title" class="workspace-hero-title">想做点什么？一句话说清就行</h2>
      <p class="gl-hint">先写清楚要表达什么；平台和内容形式在下面选，文风与篇幅进入创作页后再调。</p>
    </header>
    <textarea
      id="workspace-hero-topic"
      v-model="topicDraft"
      class="workspace-hero-input"
      rows="3"
      aria-label="创作主题"
      placeholder="例如：给新开的社区咖啡馆写一篇小红书种草笔记，突出工作日午后的人少和窗边座位"
      @keydown.enter.ctrl.prevent="start"
      @keydown.enter.meta.prevent="start"
    ></textarea>
    <div class="workspace-hero-row">
      <div class="workspace-hero-field">
        <label class="gl-label" for="workspace-hero-platform">平台</label>
        <select id="workspace-hero-platform" v-model="platformId" class="workspace-hero-select">
          <option v-for="platform in AI_PLATFORM_DEFINITIONS" :key="platform.id" :value="platform.id">{{ platform.label }}</option>
        </select>
      </div>
      <div class="workspace-hero-field">
        <label class="gl-label" for="workspace-hero-form">内容形式</label>
        <select id="workspace-hero-form" v-model="formId" class="workspace-hero-select" :disabled="formOptions.length < 2">
          <option v-for="form in formOptions" :key="form.id" :value="form.id">{{ form.label }}</option>
        </select>
      </div>
      <button type="button" class="gl-btn-primary workspace-hero-submit" @click="start">开始创作</button>
    </div>
    <div class="workspace-hero-hint">
      <p class="gl-hint"><span class="workspace-kbd">Ctrl</span> + <span class="workspace-kbd">Enter</span> 直接开始</p>
      <div class="workspace-example-row">
        <span class="gl-hint">试试：</span>
        <button v-for="example in EXAMPLES" :key="example.label" type="button" class="workspace-example-chip" @click="pickExample(example.topic)">{{ example.label }}</button>
      </div>
    </div>
  </section>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { AI_PLATFORM_DEFINITIONS, getPlatform, resolveWorkflow } from '../../config/ai-platform-capabilities'
import { buildCreationHandoff } from '../../views/ai-center/creation/useCreationRecipeEntry'
import type { AiContentFormId, AiPlatformId, CreationHandoff } from '../../types/ai-creation'

/**
 * 创作首页主行动区（2026-10 重设计）：一句话主题 + 平台 × 内容形式直达创作大页。
 * 内容形式选项跟随所选平台的能力矩阵（不出现不支持的形式），与旧版「静默取首个形式」不同。
 */
const props = defineProps<{ topic: string }>()
const emit = defineEmits<{ 'update:topic': [value: string]; 'start-workflow': [handoff: CreationHandoff] }>()

const EXAMPLES = [
  { label: '城市漫步路线', topic: '周末城市漫步路线合集，人少免费还出片' },
  { label: '咖啡馆复盘', topic: '社区咖啡馆开业首月复盘，讲清选址和客流' },
  { label: '独居生活', topic: '一个人住怎么把日子过得有秩序' },
] as const

const topicDraft = ref(props.topic)
watch(() => props.topic, value => { if (value !== topicDraft.value) topicDraft.value = value })
watch(topicDraft, value => emit('update:topic', value))

const platformId = ref<AiPlatformId>('xiaohongshu')
const formOptions = computed(() => getPlatform(platformId.value)?.forms ?? [])
const formId = ref<AiContentFormId>(formOptions.value[0]?.id ?? 'graphic')
// 平台切换后保留仍被支持的形式，否则落到该平台首个可用形式
watch(formOptions, options => {
  if (!options.some(form => form.id === formId.value)) formId.value = options[0]?.id ?? 'graphic'
})

function pickExample(topic: string): void {
  topicDraft.value = topic
  document.getElementById('workspace-hero-topic')?.focus()
}

// 与平台卡入口同一 handoff 机制：主题作为 prefill.topic 带入创作大页，可留空后补
let entryRevision = 0
const nextRevision = () => (entryRevision = Math.max(entryRevision + 1, Date.now()))

function start(): void {
  const workflow = resolveWorkflow(platformId.value, formId.value, 'independent')
  if (workflow.status !== 'available' || !workflow.workflowId || !workflow.targetView) return
  const handoff = buildCreationHandoff({
    entry: null,
    taskSourceLocked: false,
    platformId: platformId.value,
    contentFormId: formId.value,
    sourceType: 'independent',
    topic: topicDraft.value.trim(),
    pickedHotTitle: '',
    instructions: '',
    referenceUrl: '',
    referencePlatform: 'douyin',
    videoWorkflowId: 'video-script',
    stores: [],
    storeId: '',
    storeProfile: null,
    contextSnapshotId: '',
    materialIds: [],
    workflow,
    nextRevision,
  }, null)
  if (handoff) emit('start-workflow', handoff)
}
</script>
