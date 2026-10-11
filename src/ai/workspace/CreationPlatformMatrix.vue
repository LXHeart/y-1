<template>
  <section class="workspace-platform-matrix" aria-labelledby="workspace-platform-title">
    <div class="workspace-section-head">
      <h2 id="workspace-platform-title">按平台开始</h2>
      <p class="gl-hint">默认显示全部；选一个内容形式就只看能做的</p>
    </div>
    <div class="workspace-form-tabs" role="group" aria-label="按内容形式筛选平台">
      <button
        v-for="filter in filters" :key="filter.id" type="button"
        :aria-pressed="activeFilter === filter.id"
        @click="activeFilter = filter.id"
      >{{ filter.label }}</button>
    </div>
    <p class="gl-hint" role="status">{{ filterNote }}</p>
    <nav class="workspace-platform-grid" :aria-label="`支持${activeFilterLabel}的平台`">
      <button
        v-for="item in listedPlatforms" :key="item.id" type="button"
        class="workspace-entry workspace-platform-entry"
        :aria-label="`用${item.label}创作${entryFormLabel(item)}`"
        @click="startEntry(item.id)"
      >
        <span class="workspace-platform-icon"><component :is="PLATFORM_ICONS[item.id]" aria-hidden="true" /></span>
        <h3>{{ item.label }}</h3>
        <p>{{ DESCRIPTIONS[item.id] }}</p>
        <span class="workspace-platform-forms">
          <span v-for="form in entryForms(item)" :key="form.id" class="badge badge-accent">{{ form.label }}</span>
        </span>
        <span class="workspace-entry-action">开始创作 <ArrowRight aria-hidden="true" /></span>
      </button>
    </nav>
  </section>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowRight, BookOpen, MapPin, Clapperboard, Newspaper, MessageCircle, Images, Video, CirclePlay, Tv } from '@lucide/vue'
import { AI_PLATFORM_DEFINITIONS, resolveWorkflow } from '../../config/ai-platform-capabilities'
import { buildCreationHandoff } from '../../views/ai-center/creation/useCreationRecipeEntry'
import type { AiContentFormId, AiPlatformId, CreationHandoff } from '../../types/ai-creation'

/**
 * 内容形式 × 平台（2026-10 重设计）：平台能力是二维的——默认列出全部平台并标注
 * 各自支持的形式；筛选具体形式后只展示能做的平台，不再静默取 forms[0] 让用户
 * 事后才发现形式不对。点卡片即按「筛选形式（全部档=平台首个形式）」开始创作。
 */
const props = defineProps<{ topic: string }>()
const emit = defineEmits<{ 'start-workflow': [handoff: CreationHandoff] }>()

const PLATFORM_ICONS = {
  xiaohongshu: BookOpen, dianping: MapPin, douyin: Clapperboard, 'wechat-official': Newspaper,
  zhihu: MessageCircle, moments: Images, kuaishou: Video, 'wechat-channels': CirclePlay, bilibili: Tv,
} as const

const DESCRIPTIONS: Record<AiPlatformId, string> = {
  xiaohongshu: '种草笔记与视频',
  dianping: '探店评价与分享',
  douyin: '短视频脚本与图文',
  'wechat-official': '文章排版与配图',
  zhihu: '专业回答与文章',
  moments: '日常分享与配文',
  kuaishou: '生活短视频与脚本',
  'wechat-channels': '视频内容与口播脚本',
  bilibili: '视频选题与创作脚本',
}

/** 全量形式清单直接取能力矩阵（配置仍是唯一事实源，不另抄一份）。 */
const CONTENT_FORMS = [...new Map(AI_PLATFORM_DEFINITIONS.flatMap(platform => platform.forms).map(form => [form.id, form])).values()]
const filters = [{ id: 'all', label: '全部' }, ...CONTENT_FORMS] as const
type FilterId = 'all' | AiContentFormId

const activeFilter = ref<FilterId>('all')
const activeFilterLabel = computed(() => filters.find(filter => filter.id === activeFilter.value)?.label ?? '全部')
const listedPlatforms = computed(() => activeFilter.value === 'all'
  ? AI_PLATFORM_DEFINITIONS
  : AI_PLATFORM_DEFINITIONS.filter(platform => platform.forms.some(form => form.id === activeFilter.value)))

const filterNote = computed(() => {
  if (activeFilter.value === 'all') return `全部 ${AI_PLATFORM_DEFINITIONS.length} 个平台；选一个内容形式可以只看能做的。`
  const labels = listedPlatforms.value.map(platform => platform.label).join('、')
  return listedPlatforms.value.length
    ? `支持${activeFilterLabel.value}的 ${listedPlatforms.value.length} 个平台：${labels}。`
    : `当前没有平台支持${activeFilterLabel.value}。`
})

function entryForms(platform: (typeof AI_PLATFORM_DEFINITIONS)[number]) {
  return activeFilter.value === 'all' ? platform.forms : platform.forms.filter(form => form.id === activeFilter.value)
}
function entryFormLabel(platform: (typeof AI_PLATFORM_DEFINITIONS)[number]): string {
  const formId = activeFilter.value === 'all' ? platform.forms[0]?.id : activeFilter.value
  return CONTENT_FORMS.find(form => form.id === formId)?.label ?? '内容'
}

// 与创作中心「开始创作」同机制：handoff 上抛壳层直达创作大页；主题来自上方一句话输入
let entryRevision = 0
const nextRevision = () => (entryRevision = Math.max(entryRevision + 1, Date.now()))

function startEntry(platformId: AiPlatformId): void {
  const formId = activeFilter.value === 'all'
    ? AI_PLATFORM_DEFINITIONS.find(platform => platform.id === platformId)?.forms[0]?.id
    : activeFilter.value
  if (!formId) return
  const workflow = resolveWorkflow(platformId, formId, 'independent')
  if (workflow.status !== 'available' || !workflow.workflowId || !workflow.targetView) return
  const handoff = buildCreationHandoff({
    entry: null,
    taskSourceLocked: false,
    platformId,
    contentFormId: formId,
    sourceType: 'independent',
    topic: props.topic.trim(),
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
