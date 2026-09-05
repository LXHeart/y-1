<template>
  <section class="creation-center gl-field" aria-labelledby="creation-center-title">
    <header class="center-head">
      <div>
        <p class="section-kicker">AI 内容创作中心</p>
        <h2 id="creation-center-title">{{ sectionTitle }}</h2>
      </div>
      <p class="capability-version gl-num">规则 {{ AI_PLATFORM_CAPABILITY_VERSION }}</p>
    </header>

    <AiCenterNavigation :model-value="activeSection" :sections="navigationSections" @update:model-value="selectSection" />

    <template v-if="activeSection === 'create'">
      <!-- 任务书 #36 / ADR-D14：未登录游客的免费体验入口（登录用户不显示，功能面不变） -->
      <GuestTrialPanel v-if="!props.authenticated" @request-login="emit('request-login')" />
      <section class="gl-zone create-zone">
      <div class="gl-zone-head">
        <h3 class="gl-zone-title">创作配置</h3>
        <p class="gl-zone-note">平台 · 形式 · 来源 · 上下文，配好即可开始</p>
      </div>
      <div class="platform-grid" role="list" aria-label="发布平台">
        <button
          v-for="platform in AI_PLATFORM_DEFINITIONS"
          :key="platform.id"
          type="button"
          class="platform-option"
          :class="{ selected: platform.id === platformId }"
          :data-platform-id="platform.id"
          :aria-pressed="platform.id === platformId"
          :disabled="platformLocked"
          @click="selectPlatform(platform.id)"
        >
          <strong>{{ platform.label }}</strong>
          <span>{{ platform.forms.map((form) => form.label).join(' / ') }}</span>
        </button>
      </div>

      <section v-if="selectedPlatform" class="choice-band" aria-labelledby="content-form-title">
      <div class="choice-title-row">
        <h3 id="content-form-title">内容形式</h3>
        <span>{{ selectedPlatform.label }}</span>
      </div>
      <div class="segmented" role="group" aria-label="内容形式">
        <button
          v-for="form in selectedPlatform.forms"
          :key="form.id"
          type="button"
          :class="{ active: form.id === contentFormId }"
          :aria-pressed="form.id === contentFormId"
          :disabled="contentFormLocked"
          @click="selectContentForm(form.id)"
        >{{ form.label }}</button>
      </div>
      </section>

      <section v-if="contentFormId" class="choice-band" aria-labelledby="source-title">
      <div class="choice-title-row">
        <h3 id="source-title">创作来源</h3>
        <span v-if="entrySourceLocked">{{ storeSourceLocked ? '门店上下文 · 来自草场工作台' : '由工作台带入' }}</span>
      </div>
      <div class="source-grid" role="group" aria-label="创作来源">
        <button
          v-for="sourceOption in sourceOptions"
          :key="sourceOption.id"
          type="button"
          class="source-option"
          :class="{ selected: sourceOption.id === sourceType }"
          :aria-pressed="sourceOption.id === sourceType"
          :disabled="entrySourceLocked"
          @click="selectSource(sourceOption.id)"
        >
          <strong>{{ sourceOption.label }}</strong>
          <span>{{ sourceOption.note }}</span>
        </button>
      </div>
      </section>

      <section
        v-if="contentFormId === 'video' && sourceType && sourceType !== 'reference'"
        class="choice-band"
        aria-labelledby="video-workflow-title"
      >
        <div class="choice-title-row">
          <h3 id="video-workflow-title">创作类型</h3>
          <span>选择本次视频文稿的工作流</span>
        </div>
        <div class="segmented" role="group" aria-label="视频创作类型">
          <button
            v-for="option in videoWorkflowOptions"
            :key="option.id"
            type="button"
            :class="{ active: option.id === videoWorkflowId }"
            :aria-pressed="option.id === videoWorkflowId"
            @click="videoWorkflowId = option.id"
          >{{ option.label }}</button>
        </div>
      </section>

      <section v-if="sourceType" class="context-band" aria-labelledby="context-title">
      <div class="choice-title-row">
        <h3 id="context-title">创作上下文</h3>
        <span v-if="sourceType === 'task'">仅用于预填，不作为任务核实依据</span>
      </div>

      <!-- 任务书 #76：门店来源只经商家工作台深链锁定进入（entry.source=store + hydrateStoreContext），
           手动组织/门店选择器已随「AI 应用自由路径无组织概念 / 草场面只留任务」一并下线。 -->
      <div v-if="sourceType === 'store' && storeSourceLocked" class="task-context-summary store-context-summary">
        <div class="task-context-head">
          <strong>{{ lockedStoreName }}</strong>
          <span>门店上下文 · 来自草场工作台 · 不可更改</span>
        </div>
        <dl>
          <div><dt>组织</dt><dd>{{ lockedOrganizationName }}</dd></div>
          <div><dt>门店</dt><dd>{{ lockedStoreName }}</dd></div>
          <div v-if="storeProfile?.description"><dt>门店介绍</dt><dd>{{ storeProfile.description }}</dd></div>
          <div v-if="lockedStoreAddress"><dt>地址</dt><dd>{{ lockedStoreAddress }}</dd></div>
        </dl>
        <p v-if="loadingContext">正在载入门店上下文…</p>
      </div>

      <div v-if="sourceType === 'task' && !taskSourceLocked" class="inline-state">
        <p>从草场工作台中已接受的履约进入创作。</p>
        <button type="button" class="secondary-command" @click="emit('open-grassland')">打开草场</button>
      </div>

      <HotTopicPicker
        v-if="sourceType === 'hot-topic'"
        :items="hotItems"
        :groups="hotGroups"
        :provider="hotProvider"
        :fetched-at="hotFetchedAt"
        :taxonomy="hotTaxonomy"
        :filters="hotFilters"
        :loading="hotLoading"
        :error="hotError"
        :selected-title="topic"
        :picked-title="pickedHotTitle"
        :resolving-topic="assistant.resolvingTopic.value"
        :topic-error="assistant.topicError.value"
        :structured-topic="assistant.structuredTopic.value"
        @refresh="refreshHotItems"
        @filter="applyHotFilters"
        @pick="pickHotTopic"
        @refine="refineHotTopic"
      />

      <label v-if="sourceType !== 'reference' && (sourceType !== 'store' || storeId)" class="topic-field">
        <span>创作主题</span>
        <textarea
          v-model="topic"
          name="creation-topic"
          aria-label="创作主题"
          rows="3"
          maxlength="500"
          :readonly="taskSourceLocked && sourceType === 'task'"
          placeholder="输入主题、门店卖点或内容方向"
        />
      </label>

      <div v-if="sourceType === 'reference'" class="topic-field">
        <span id="reference-source-label">参考链接来源</span>
        <div class="segmented" role="group" aria-label="参考链接来源">
          <button
            type="button"
            :class="{ active: referencePlatform === 'douyin' }"
            :aria-pressed="referencePlatform === 'douyin'"
            @click="referencePlatform = 'douyin'"
          >抖音</button>
          <button
            type="button"
            :class="{ active: referencePlatform === 'bilibili' }"
            :aria-pressed="referencePlatform === 'bilibili'"
            @click="referencePlatform = 'bilibili'"
          >B 站</button>
        </div>
        <span>链接或分享文本</span>
        <textarea
          v-model="referenceUrl"
          name="reference-url"
          aria-label="参考链接或分享文本"
          rows="3"
          maxlength="2000"
          placeholder="粘贴抖音或 B 站链接、分享文本"
        />
      </div>

      <div v-if="videoWorkflowId === 'video-recreation' && sourceType !== 'reference'" class="topic-field">
        <span id="recreation-reference-label">参考链接或分享文本</span>
        <div class="segmented" role="group" aria-label="参考视频来源平台">
          <button
            type="button"
            :class="{ active: referencePlatform === 'douyin' }"
            :aria-pressed="referencePlatform === 'douyin'"
            @click="referencePlatform = 'douyin'"
          >抖音</button>
          <button
            type="button"
            :class="{ active: referencePlatform === 'bilibili' }"
            :aria-pressed="referencePlatform === 'bilibili'"
            @click="referencePlatform = 'bilibili'"
          >B 站</button>
        </div>
        <textarea
          v-model="referenceUrl"
          name="recreation-reference-url"
          aria-labelledby="recreation-reference-label"
          rows="3"
          maxlength="2000"
          placeholder="粘贴抖音或 B 站链接、分享文本"
        />
      </div>

      <label v-if="sourceType === 'independent' || sourceType === 'hot-topic' || sourceType === 'store'" class="topic-field">
        <span>补充要求</span>
        <textarea v-model="instructions" rows="3" maxlength="1000" placeholder="可选：语气、重点、必须包含或避免的内容" />
      </label>

      <div v-if="entry?.taskContext && taskSourceLocked" class="task-context-summary">
        <div class="task-context-head">
          <strong>{{ entry.taskContext.title }}</strong>
          <span>接受时快照 · v{{ entry.taskContext.taskVersion }}</span>
        </div>
        <dl>
          <div><dt>发布平台</dt><dd>{{ entry.taskContext.platform || '未指定' }}</dd></div>
          <div><dt>内容形式</dt><dd>{{ entry.taskContext.contentForm || '未指定' }}</dd></div>
          <div><dt>任务赏金</dt><dd class="gl-num">{{ formatYuan(entry.taskContext.bountyCents) }}</dd></div>
          <div><dt>接受时间</dt><dd>{{ formatTaskDate(entry.taskContext.acceptedAt) }}</dd></div>
          <div v-if="entry.taskContext.storeId"><dt>门店范围</dt>
            <dd>{{ taskStoreBranding?.storeName || entry.taskContext.storeId }}</dd></div>
        </dl>
        <p v-if="entry.taskContext.description">{{ entry.taskContext.description }}</p>
        <div v-if="taskRequirementEntries.length" class="task-requirements">
          <strong>任务要求</strong>
          <div v-for="item in taskRequirementEntries" :key="item[0]">
            <span>{{ item[0] }}</span><p>{{ formatRequirement(item[1]) }}</p>
          </div>
        </div>
        <!-- 任务书 #24：AI 商家上下文（品牌语气/必须强调/禁止表达/标签池） -->
        <div v-if="taskStoreBrandingEntries.length" class="task-requirements">
          <strong>门店品牌约束</strong>
          <div v-for="item in taskStoreBrandingEntries" :key="item[0]">
            <span>{{ item[0] }}</span><p>{{ formatRequirement(item[1]) }}</p>
          </div>
        </div>
      </div>
      <div v-else-if="entry?.prefill && taskSourceLocked" class="context-summary">
        <strong>{{ entry.prefill.topic || '任务创作' }}</strong>
        <span v-if="entry.source.type === 'task' && entry.source.taskVersion">任务版本 {{ entry.source.taskVersion }}</span>
        <p v-if="entry.prefill.instructions">{{ entry.prefill.instructions }}</p>
      </div>

      <div v-if="taskSourceLocked" class="material-selection">
        <span>{{ materialIds.length ? `已选择 ${materialIds.length} 项创作素材` : '未选择创作素材' }}</span>
        <button type="button" class="secondary-command" @click="activeSection = 'library'">选择素材</button>
      </div>

      <p v-if="contextError" class="error-state" role="alert">{{ contextError }}</p>
      </section>

      <footer v-if="sourceType" class="start-bar">
        <p data-testid="selection-summary">{{ selectionSummary }}</p>
        <div>
          <span v-if="workflow.status === 'planned'" class="planned-state">该创作路径尚未接入</span>
          <button type="button" class="primary-command gl-btn-primary" :disabled="!canStart || freezingContext" @click="startWorkflow">
            {{ freezingContext ? '正在准备...' : '开始创作' }}
          </button>
        </div>
      </footer>
      </section>
    </template>

    <div v-else-if="activeSection === 'runs'" class="runs-section">
      <PersonalAiBudgetCard />
      <AiRunHistoryPanel />
    </div>
    <SpeechTranscriptionPanel v-else-if="activeSection === 'speech'" />
        <ImageStudioView v-else-if="activeSection === 'image-studio'" ref="imageStudioRef" />
        <ImageGenerationStudio v-else-if="activeSection === 'image-gen'" />
        <VideoStudioView v-else-if="activeSection === 'video-studio'" @handoff="onVideoStudioHandoff" />
    <CreationAssistantPanel
      v-else-if="activeSection === 'assistant'"
      :authenticated="props.authenticated"
      :platform="platformId || undefined"
      :content-form="contentFormId || undefined"
      :source="assistantSource"
      :topic="topic.trim() || undefined"
      :task-requirements="taskRequirements"
      @request-login="emit('request-login')"
    />
    <MediaLibraryPanel
      v-else-if="activeSection === 'library'"
      :authenticated="props.authenticated"
      :selectable="taskSourceLocked"
      :selected-asset-ids="materialIds"
      :recommendation-context="recommendationContext"
      @selection-change="setSelectedMaterials"
      @edit-image="onEditImageFromLibrary"
      @request-login="emit('request-login')"
    />
    <AiProviderKeysPanel v-else />
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import AiProviderKeysPanel from '../../components/AiProviderKeysPanel.vue'
import AiRunHistoryPanel from '../../components/AiRunHistoryPanel.vue'
import PersonalAiBudgetCard from '../../components/PersonalAiBudgetCard.vue'
import CreationAssistantPanel from '../../components/CreationAssistantPanel.vue'
import SpeechTranscriptionPanel from '../../components/SpeechTranscriptionPanel.vue'
import ImageStudioView from './components/ImageStudioView.vue'
import ImageGenerationStudio from './components/ImageGenerationStudio.vue'
import VideoStudioView from './components/VideoStudioView.vue'
import AiCenterNavigation, { AI_CENTER_SECTIONS, type AiCenterSection } from './components/AiCenterNavigation.vue'
import MediaLibraryPanel from '../../components/MediaLibraryPanel.vue'
import HotTopicPicker from './components/HotTopicPicker.vue'
import { useCreationAssistant } from '../../composables/useCreationAssistant'
import GuestTrialPanel from '../../components/GuestTrialPanel.vue'
import {
  AI_PLATFORM_CAPABILITY_VERSION,
  AI_PLATFORM_DEFINITIONS,
  getPlatform,
  resolveWorkflow,
} from '../../config/ai-platform-capabilities'
import { useGrassland } from '../../composables/useGrassland'
import { formatYuan } from '../../lib/money'
import { useHomepageHotItems } from '../../composables/useHomepageHotItems'
import type { Organization, Store, StoreProfile, StorePublicProfile } from '../../types/grassland'
import type { HomepageHotFilters } from '../../types/homepage-hot'
import type {
  AiContentFormId,
  AiPlatformId,
  CreationDraftPrefill,
  CreationEntry,
  CreationHandoff,
  CreationRecommendationContext,
  CreationSource,
  CreationSourceType,
  VideoCreationWorkflowId,
} from '../../types/ai-creation'

const props = withDefaults(defineProps<{
  authenticated: boolean
  entry: CreationEntry | null
  /**
   * 挂载形态（任务书 #76 卡 C）：personal = AI 独立应用（自由创作：independent/hot-topic/reference，
   * 九板块全量）；platform = 草场内嵌创作面（任务锁定态 + 素材库，来源只有 task）。
   * 共享组件单实现、两应用双挂载（工程红线），模式差异只经此 prop 表达。
   */
  mode?: 'personal' | 'platform'
}>(), { mode: 'personal' })

const emit = defineEmits<{
  'start-workflow': [handoff: CreationHandoff]
  'request-login': []
  'open-grassland': []
}>()

const grassland = useGrassland()
const imageStudioRef = ref<InstanceType<typeof ImageStudioView> | null>(null)

/**
 * 素材库直连编辑器（任务书 #43 D9 补欠）：素材库图片 → presigned 下载 URL → 图片编辑台
 * 载入底图（与本地文件选择同管线）。URL 短时有效（300s），取到即用不缓存。
 */
async function onEditImageFromLibrary(asset: { id: string; title: string; mimeType: string }): Promise<void> {
  const granted = await grassland.getContentAssetDownloadUrl(asset.id)
  if (!granted?.downloadUrl) return
  activeSection.value = 'image-studio'
  await nextTick()
  try {
    await imageStudioRef.value?.loadImageFromUrl(granted.downloadUrl, asset.title)
  } catch {
    grassland.error.value = '素材载入编辑器失败，请重试'
  }
}
const assistant = useCreationAssistant()
const activeSection = ref<AiCenterSection>('create')
/** 已选热点标题（与 topic 分开存：topic 会被结构化选题覆盖，refine 仍需原标题）。 */
const pickedHotTitle = ref('')
const platformId = ref<AiPlatformId | ''>('')
const contentFormId = ref<AiContentFormId | ''>('')
const sourceType = ref<CreationSourceType | ''>('')
const videoWorkflowId = ref<VideoCreationWorkflowId>('video-script')
const topic = ref('')
const instructions = ref('')
const referenceUrl = ref('')
const referencePlatform = ref<'douyin' | 'bilibili'>('douyin')
const organizations = ref<Organization[]>([])
const stores = ref<Store[]>([])
const organizationId = ref('')
const storeId = ref('')
const storeProfile = ref<StoreProfile | null>(null)
const loadingContext = ref(false)
const contextError = ref('')
const contextSnapshotId = ref('')
const freezingContext = ref(false)
const materialIds = ref<string[]>([])
const storeProfileLoaded = ref(false)
const hydratedRevision = ref<number | null>(null)
let contextRequestEpoch = 0
let hotRefineEpoch = 0
let workflowRevision = Date.now()

/** AI 应用（personal）：自由创作三来源——store/task 是草场侧概念，不在此露出。 */
const PERSONAL_SOURCE_OPTIONS: ReadonlyArray<{ id: CreationSourceType; label: string; note: string }> = [
  { id: 'independent', label: '独立创作', note: '从主题或想法开始' },
  { id: 'hot-topic', label: '从热点创作', note: '以热点标题为主题' },
  { id: 'reference', label: '参考素材', note: '分析抖音或 B 站视频，再适配目标平台' },
]
/** 草场内嵌创作面（platform）：任务锁定态——入口在工作台已接受履约。 */
const PLATFORM_SOURCE_OPTIONS: ReadonlyArray<{ id: CreationSourceType; label: string; note: string }> = [
  { id: 'task', label: '从任务创作', note: '带入已接受履约' },
]
const sourceOptions = computed(() => (props.mode === 'platform' ? PLATFORM_SOURCE_OPTIONS : PERSONAL_SOURCE_OPTIONS))
/** 草场创作面只留 create+library；AI 应用九板块全量（AiCenterNavigation 缺省）。 */
const navigationSections = computed(() => props.mode === 'platform'
  ? AI_CENTER_SECTIONS.filter((section) => section.id === 'create' || section.id === 'library')
  : undefined)
const videoWorkflowOptions: ReadonlyArray<{ id: VideoCreationWorkflowId; label: string }> = [
  { id: 'video-script', label: '常规视频脚本' },
  { id: 'comedy-script', label: '风格化喜剧脚本' },
  { id: 'video-recreation', label: '参考视频复刻' },
]

const taskSourceLocked = computed(() => props.entry?.source.type === 'task')
/** 门店深链锁定（任务书 #76 卡 C）：entry.source=store 进入，不可改组织/门店。 */
const storeSourceLocked = computed(() => props.entry?.source.type === 'store')
const entrySourceLocked = computed(() => taskSourceLocked.value || storeSourceLocked.value)
/** 锁定门店展示（hydrateStoreContext 载入的组织/门店/资料）。 */
const lockedStoreName = computed(() =>
  stores.value.find((store) => store.id === storeId.value)?.name || storeId.value || '门店载入中…')
const lockedOrganizationName = computed(() =>
  organizations.value.find((organization) => organization.id === organizationId.value)?.name || organizationId.value || '组织载入中…')
const lockedStoreAddress = computed(() => parseAddress(storeProfile.value?.address))
/**
 * 任务要求快照，传给助手做覆盖检查（§4.9.3）。intelligence 不跨服务读 marketplace，
 * 要求文本必须由前端从 entry 的 task 快照带入；非任务来源为 undefined（助手据此隐藏该能力）。
 */
const taskRequirements = computed(() => {
  if (props.entry?.source.type !== 'task') return undefined
  if (props.entry.taskContext) {
    const context = props.entry.taskContext
    const requirements = Object.entries(context.requirements || {})
      .map(([key, value]) => `${key}: ${formatRequirement(value)}`)
    return [context.title, context.description, ...requirements].filter(Boolean).join('\n')
  }
  const parts = [props.entry.prefill?.topic, props.entry.prefill?.instructions]
    .map((part) => part?.trim()).filter(Boolean)
  return parts.length ? parts.join('\n') : undefined
})
const taskRequirementEntries = computed(() => Object.entries(props.entry?.taskContext?.requirements || {}))

/** 任务书 #24：任务模式上下文预览的门店品牌块（按 taskContext.storeId 拉公开白名单）。 */
const taskStoreBranding = ref<StorePublicProfile | null>(null)
let taskStoreBrandingEpoch = 0
watch(() => props.entry?.taskContext?.storeId ?? null, async (nextStoreId) => {
  const epoch = ++taskStoreBrandingEpoch
  taskStoreBranding.value = null
  if (!nextStoreId) return
  const profile = await grassland.getStorePublicProfile(nextStoreId)
  if (epoch === taskStoreBrandingEpoch) taskStoreBranding.value = profile
}, { immediate: true })
const taskStoreBrandingEntries = computed<Array<[string, unknown]>>(() => {
  const profile = taskStoreBranding.value
  if (!profile) return []
  return [
    ['品牌语气', profile.brandTone],
    ['必须强调', profile.mustEmphasize],
    ['禁止表达', profile.forbiddenPhrases],
    ['可使用标签', profile.allowedTags],
    ['推荐卖点', profile.sellingPoints],
    ['主营品类', profile.categories],
    ['特色产品/服务', profile.signatureItems],
  ].filter(([, value]) => Array.isArray(value) ? value.length > 0 : Boolean(value)) as Array<[string, unknown]>
})
/** 素材库智能推荐上下文：任务模式带权威任务引用，独立模式带当前平台/内容形式。 */
const recommendationContext = computed<CreationRecommendationContext>(() => {
  const source = props.entry?.source
  return {
    applicationId: source?.type === 'task' ? source.applicationId : undefined,
    taskId: source?.type === 'task' ? source.taskId : undefined,
    platform: platformId.value || undefined,
    contentForm: contentFormId.value || undefined,
  }
})
const assistantSource = computed<CreationSource | undefined>(() => sourceForHandoff() ?? undefined)
const platformLocked = computed(() => taskSourceLocked.value && Boolean(props.entry?.platformId))
const contentFormLocked = computed(() => taskSourceLocked.value && Boolean(props.entry?.contentFormId))
const selectedPlatform = computed(() => platformId.value ? getPlatform(platformId.value) : null)
const sectionTitle = computed(() => {
  if (activeSection.value === 'runs') return 'AI 运行记录'
  if (activeSection.value === 'speech') return '语音转写'
  if (activeSection.value === 'assistant') return '智能创作助手'
  if (activeSection.value === 'library') return '内容素材库'
  if (activeSection.value === 'keys') return '模型密钥'
  if (activeSection.value === 'image-studio') return '图片编辑'
  if (activeSection.value === 'image-gen') return '图片生成'
  if (activeSection.value === 'video-studio') return '视频工坊'
  return '选择发布平台'
})
const workflow = computed(() => platformId.value && contentFormId.value && sourceType.value
  ? resolveWorkflow(platformId.value, contentFormId.value, sourceType.value, videoWorkflowId.value)
  : { status: 'unsupported' as const, workflowId: null, targetView: null })
const selectionSummary = computed(() => {
  const platform = selectedPlatform.value?.label || '未选平台'
  const form = selectedPlatform.value?.forms.find((item) => item.id === contentFormId.value)?.label || '未选形式'
  return `${platform} · ${form}`
})
const canStart = computed(() => {
  if (workflow.value.status !== 'available' || !sourceType.value) return false
  if (sourceType.value === 'task') {
    return props.authenticated && props.entry?.source.type === 'task'
      && (videoWorkflowId.value !== 'video-recreation' || referenceUrl.value.trim().length > 0)
  }
  if (sourceType.value === 'store') {
    return props.authenticated
      && Boolean(organizationId.value && storeId.value && storeProfileLoaded.value)
      && !loadingContext.value
      && !contextError.value
  }
  if (sourceType.value === 'reference') return referenceUrl.value.trim().length > 0
  if (sourceType.value === 'hot-topic') {
    return Boolean(pickedHotTitle.value && topic.value.trim())
  }
  return topic.value.trim().length > 0
})

watch(() => props.entry, (entry) => {
  hotRefineEpoch += 1
  if (!entry) {
    hydratedRevision.value = null
    pickedHotTitle.value = ''
    clearStoreContext()
    contextSnapshotId.value = ''
    materialIds.value = []
    return
  }
  if (hydratedRevision.value === entry.revision) return
  contextRequestEpoch += 1
  activeSection.value = 'create'
  hydratedRevision.value = entry.revision
  contextSnapshotId.value = entry.contextSnapshotId || ''
  materialIds.value = [...(entry.materialIds || [])]
  platformId.value = entry.platformId ?? ''
  contentFormId.value = entry.contentFormId ?? ''
  sourceType.value = entry.source.type
  videoWorkflowId.value = 'video-script'
  topic.value = entry.prefill?.topic || ''
  instructions.value = entry.prefill?.instructions || ''
  pickedHotTitle.value = entry.source.type === 'hot-topic' ? entry.source.title : ''
  referenceUrl.value = entry.source.type === 'reference' ? entry.source.sourceUrl || '' : ''
  if (entry.prefill?.referenceUrl) referenceUrl.value = entry.prefill.referenceUrl
  referencePlatform.value = entry.prefill?.referencePlatform || 'douyin'
  if (entry.source.type === 'store') {
    organizationId.value = entry.source.organizationId
    storeId.value = entry.source.storeId
    if (props.authenticated) {
      void hydrateStoreContext(entry.source.organizationId, entry.source.storeId)
    }
  } else {
    clearStoreContext()
  }
}, { immediate: true })

watch(() => props.authenticated, (authenticated) => {
  if (!authenticated) {
    activeSection.value = 'create'
    clearStoreContext()
    return
  }
  if (props.entry?.source.type === 'store' && !storeProfileLoaded.value && !loadingContext.value) {
    void hydrateStoreContext(props.entry.source.organizationId, props.entry.source.storeId)
  }
})

function clearStoreContext(): void {
  contextRequestEpoch += 1
  organizationId.value = ''
  storeId.value = ''
  organizations.value = []
  stores.value = []
  storeProfile.value = null
  storeProfileLoaded.value = false
  loadingContext.value = false
  contextError.value = ''
}

function selectSection(next: AiCenterSection): void {
  if (next !== 'create' && !props.authenticated) {
    emit('request-login')
    return
  }
  activeSection.value = next
}

function setSelectedMaterials(assetIds: string[]): void {
  materialIds.value = [...new Set(assetIds)].slice(0, 50)
}

/**
 * 换平台＝换创作链路：来源选择与已填的上下文（主题/补充要求/参考链接/门店、热点拆解）全部作废。
 * 上下文字段必须真实清值，只藏区块会让旧文本在下次选来源时回弹。
 */
function resetCreationSetup(): void {
  hotRefineEpoch += 1
  assistant.structuredTopic.value = null
  pickedHotTitle.value = ''
  topic.value = ''
  instructions.value = ''
  referenceUrl.value = ''
  referencePlatform.value = 'douyin'
  contextError.value = ''
  sourceType.value = ''
  clearStoreContext()
}

function selectPlatform(next: AiPlatformId): void {
  // 首页热点卡等外部入口带入而平台未定的：首次点平台是完成配置而非放弃，保留预填；
  // 之后改选任何平台都是用户主动重启，按新链路清空。任务注入的平台/形式被锁定，本就不经此处。
  const preservesEntryPrefill = props.entry?.source.type === 'hot-topic' && !platformId.value
  if (!preservesEntryPrefill && !taskSourceLocked.value) {
    resetCreationSetup()
  } else if (sourceType.value === 'hot-topic') {
    clearHotTopicContext(!preservesEntryPrefill)
  }
  platformId.value = next
  contentFormId.value = ''
  videoWorkflowId.value = 'video-script'
}

function selectContentForm(next: AiContentFormId): void {
  const preservesEntryContext = props.entry?.source.type === 'hot-topic' && !contentFormId.value
  if (sourceType.value === 'hot-topic') clearHotTopicContext(!preservesEntryContext)
  contentFormId.value = next
  videoWorkflowId.value = 'video-script'
  // 与换平台同口径：清空来源链路时必须连字段值一起清，避免藏而不清、再选来源时回弹旧文本。
  if (!props.entry) resetCreationSetup()
}

function clearHotTopicContext(clearSelection = true): void {
  hotRefineEpoch += 1
  assistant.structuredTopic.value = null
  if (clearSelection) {
    pickedHotTitle.value = ''
    topic.value = ''
  }
}

async function selectSource(next: CreationSourceType): Promise<void> {
  if (next === 'task' && !props.authenticated) {
    emit('request-login')
    return
  }
  if (sourceType.value === 'hot-topic' && next !== 'hot-topic') {
    clearHotTopicContext()
  }
  sourceType.value = next
  contextError.value = ''
}

const {
  items: hotItems,
  groups: hotGroups,
  provider: hotProvider,
  fetchedAt: hotFetchedAt,
  taxonomy: hotTaxonomy,
  filters: hotFilters,
  loading: hotLoading,
  error: hotError,
  loadHotItems: fetchHotItems,
} = useHomepageHotItems()
const hotLoaded = ref(false)

watch(sourceType, (next) => {
  if (next === 'hot-topic') void ensureHotItemsLoaded()
}, { immediate: true })

async function ensureHotItemsLoaded(): Promise<void> {
  if (hotLoaded.value || hotLoading.value) return
  await fetchHotItems()
  // 加载失败不标记为已加载，重新选择该来源时会自动重试
  if (!hotError.value) hotLoaded.value = true
}

async function refreshHotItems(): Promise<void> {
  await fetchHotItems()
  hotLoaded.value = !hotError.value
}

async function applyHotFilters(filters: HomepageHotFilters): Promise<void> {
  await fetchHotItems(filters)
  hotLoaded.value = !hotError.value
}

function pickHotTopic(title: string): void {
  hotRefineEpoch += 1
  topic.value = title
  pickedHotTitle.value = title
  // 换热点就丢掉上一条的结构化结果，否则「角度/立意」会挂在不相干的标题下。
  assistant.structuredTopic.value = null
}

/**
 * 热点 → 结构化选题（§4.9.5）。把纯标题换成角度/立意/受众/切入点，
 * 并用结构化 topic 覆盖创作主题——后续大纲/正文拿到的是可创作的选题而不是一句热搜词。
 */
async function refineHotTopic(): Promise<void> {
  if (!pickedHotTitle.value) return
  if (!props.authenticated) {
    emit('request-login')
    return
  }
  const requestEpoch = ++hotRefineEpoch
  const requestedHotTitle = pickedHotTitle.value
  const topicBeforeRequest = topic.value
  const instructionsBeforeRequest = instructions.value.trim()
  const refined = await assistant.topicFromHot(
    requestedHotTitle, platformId.value || undefined, instructionsBeforeRequest || undefined)
  const stillCurrent = requestEpoch === hotRefineEpoch
    && sourceType.value === 'hot-topic'
    && pickedHotTitle.value === requestedHotTitle
    && topic.value === topicBeforeRequest
    && instructions.value.trim() === instructionsBeforeRequest
  if (!stillCurrent) {
    if (assistant.structuredTopic.value === refined) assistant.structuredTopic.value = null
    return
  }
  if (refined) topic.value = refined.topic
}

async function hydrateStoreContext(nextOrganizationId: string, nextStoreId: string): Promise<void> {
  const requestEpoch = ++contextRequestEpoch
  loadingContext.value = true
  contextError.value = ''
  organizations.value = []
  stores.value = []
  storeProfile.value = null
  storeProfileLoaded.value = false
  try {
    const organizationResult = await grassland.listOrganizations()
    if (requestEpoch !== contextRequestEpoch) return
    organizations.value = organizationResult ? [...organizationResult] : []
    if (!organizationResult) {
      contextError.value = grassland.error.value || '组织列表加载失败'
      return
    }
    if (!organizationResult.some((item) => item.id === nextOrganizationId)) {
      contextError.value = '当前账号无权访问该组织'
      return
    }

    const storeResult = await grassland.listStores(nextOrganizationId)
    if (requestEpoch !== contextRequestEpoch) return
    stores.value = storeResult ? [...storeResult] : []
    if (!storeResult) {
      contextError.value = grassland.error.value || '门店列表加载失败'
      return
    }
    const selectedStore = storeResult.find((item) => item.id === nextStoreId)
    if (!selectedStore) {
      contextError.value = '当前账号无权访问该门店'
      return
    }

    const profile = await grassland.getStoreProfile(nextOrganizationId, nextStoreId)
    if (requestEpoch !== contextRequestEpoch) return
    storeProfile.value = profile
    storeProfileLoaded.value = Boolean(profile)
    if (!profile) contextError.value = grassland.error.value || '门店资料加载失败'
  } finally {
    if (requestEpoch === contextRequestEpoch) loadingContext.value = false
  }
}

function parseAddress(raw: string | null | undefined): string {
  if (!raw) return ''
  try {
    const parsed = JSON.parse(raw) as { province?: string; city?: string; district?: string; address?: string }
    return [parsed.province, parsed.city, parsed.district, parsed.address].filter(Boolean).join(' ')
  } catch {
    return raw
  }
}

function sourceForHandoff(): CreationSource | null {
  if (props.entry && taskSourceLocked.value) return { ...props.entry.source }
  if (sourceType.value === 'independent') return { type: 'independent' }
  if (sourceType.value === 'hot-topic') {
    return {
      type: 'hot-topic',
      title: pickedHotTitle.value.trim() || topic.value.trim(),
      topicId: props.entry?.source.type === 'hot-topic' ? props.entry.source.topicId : undefined,
    }
  }
  if (sourceType.value === 'reference') {
    return {
      type: 'reference',
      sourceUrl: referenceUrl.value.trim(),
    }
  }
  if (sourceType.value === 'store' && organizationId.value && storeId.value) {
    return { type: 'store', organizationId: organizationId.value, storeId: storeId.value }
  }
  return null
}

function prefillForHandoff(): CreationDraftPrefill {
  if (taskSourceLocked.value) {
    return {
      ...(props.entry?.prefill || {}),
      referenceUrl: videoWorkflowId.value === 'video-recreation'
        ? referenceUrl.value.trim() || undefined
        : undefined,
      referencePlatform: videoWorkflowId.value === 'video-recreation' || sourceType.value === 'reference'
        ? referencePlatform.value
        : undefined,
    }
  }
  const store = stores.value.find((item) => item.id === storeId.value)
  return {
    topic: topic.value.trim() || undefined,
    instructions: instructions.value.trim() || undefined,
    referenceUrl: videoWorkflowId.value === 'video-recreation'
      ? referenceUrl.value.trim() || undefined
      : undefined,
    referencePlatform: videoWorkflowId.value === 'video-recreation' || sourceType.value === 'reference'
      ? referencePlatform.value
      : undefined,
    storeName: store?.name,
    address: parseAddress(storeProfile.value?.address),
    storeDescription: storeProfile.value?.description || undefined,
  }
}

function startWorkflow(): void {
  if (!canStart.value || !platformId.value || !contentFormId.value) return
  const source = sourceForHandoff()
  if (!source || !workflow.value.workflowId || !workflow.value.targetView) return
  const handoff: CreationHandoff = {
    revision: nextWorkflowRevision(),
    platformId: platformId.value,
    contentFormId: contentFormId.value,
    source,
    workflowId: workflow.value.workflowId,
    targetView: workflow.value.targetView,
    prefill: prefillForHandoff(),
    taskContext: props.entry?.taskContext,
    contextSnapshotId: contextSnapshotId.value || undefined,
    materialIds: materialIds.value.length ? [...materialIds.value] : undefined,
  }
  if (source.type === 'task' && source.applicationId && source.taskVersion) {
    freezingContext.value = true
    contextError.value = ''
    void grassland.createCreationContext({
      taskId: source.taskId, applicationId: source.applicationId, taskVersion: source.taskVersion,
      platformId: platformId.value, contentFormId: contentFormId.value,
      materialIds: materialIds.value.length ? [...materialIds.value] : undefined,
    }).then((snapshot) => {
      if (snapshot) {
        contextSnapshotId.value = snapshot.id
        emit('start-workflow', { ...handoff, contextSnapshotId: snapshot.id })
      } else {
        contextError.value = grassland.error.value || '创作上下文冻结失败，请重试'
      }
    }).finally(() => { freezingContext.value = false })
    return
  }
  emit('start-workflow', handoff)
}

/** 视频工坊模板「带入创作」：预填平台/形式/主题，切回创作 tab。 */
function onVideoStudioHandoff(payload: { platformId: string; contentFormId: string; topic: string }) {
  platformId.value = payload.platformId as AiPlatformId
  contentFormId.value = payload.contentFormId as AiContentFormId
  topic.value = payload.topic || ''
  activeSection.value = 'create'
}

function formatRequirement(value: unknown): string {
  if (value == null) return '未填写'
  if (typeof value === 'string') return value
  if (Array.isArray(value)) return value.map(formatRequirement).join('、')
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function formatTaskDate(value: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '未记录'
}

function nextWorkflowRevision(): number {
  workflowRevision = Math.max(workflowRevision + 1, Date.now(), (props.entry?.revision ?? 0) + 1)
  return workflowRevision
}
</script>

<style scoped>
.creation-center { display: grid; gap: var(--space-lg); max-width: 1040px; margin: 0 auto; }
.center-head, .choice-title-row, .start-bar { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.center-head h2, .choice-title-row h3 { margin: 0; color: var(--color-text); letter-spacing: 0; }
.center-head h2 { font-size: var(--text-xl); font-weight: 800; letter-spacing: -0.02em; }
.choice-title-row h3 { font-size: 1rem; }
.section-kicker, .capability-version, .choice-title-row span, .start-bar p { margin: 0; color: var(--color-text-muted); font-size: var(--text-xs); }
.section-kicker { margin-bottom: 4px; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: var(--color-accent-2); }
.platform-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--space-sm); }
.platform-option { min-height: 78px; padding: var(--space-sm); display: grid; gap: 5px; align-content: center; text-align: left; background: var(--gradient-surface); color: var(--color-text); border: 1px solid var(--color-border); border-radius: var(--radius-md); cursor: pointer; transition: transform var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out); }
.platform-option:hover:not(:disabled) { transform: translateY(-2px); border-color: var(--color-border-hover); box-shadow: var(--shadow-elevated); }
.platform-option span, .source-option span { color: var(--color-text-muted); font-size: var(--text-xs); }
.platform-option.selected, .source-option.selected { background: color-mix(in srgb, var(--color-accent) 10%, var(--color-surface)); box-shadow: inset 3px 0 0 var(--color-accent); }
.platform-option:disabled { cursor: default; opacity: 0.72; }
.choice-band, .context-band { display: grid; gap: var(--space-sm); padding-top: var(--space-md); border-top: 1px solid var(--color-border); }
.create-zone > .choice-band:first-of-type, .create-zone > .context-band:first-of-type { border-top: 0; padding-top: 0; }
.segmented { display: inline-flex; width: fit-content; padding: 4px; gap: 4px; background: var(--surface-muted); border: 1px solid var(--color-border); border-radius: var(--radius-pill); }
.segmented button { min-width: 108px; min-height: 30px; padding: 0 14px; border: 0; border-radius: var(--radius-pill); color: var(--color-text-secondary); background: transparent; cursor: pointer; }
.segmented button.active { background: color-mix(in srgb, var(--color-accent) 12%, transparent); color: var(--color-accent-2); font-weight: 600; }
.source-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 8px; }
.source-option { min-height: 72px; display: grid; gap: 5px; align-content: center; padding: var(--space-sm); text-align: left; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--gradient-surface); color: var(--color-text); cursor: pointer; transition: transform var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out); }
.source-option:hover:not(:disabled) { transform: translateY(-2px); border-color: var(--color-border-hover); box-shadow: var(--shadow-elevated); }
.topic-field { display: grid; gap: 6px; color: var(--color-text-secondary); font-size: 0.82rem; }
select, textarea { width: 100%; box-sizing: border-box; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); padding: 8px var(--space-sm); font: inherit; letter-spacing: 0; }
textarea { resize: vertical; min-height: 76px; }
.inline-state, .context-summary { padding: 12px 0; display: flex; align-items: center; justify-content: space-between; gap: 12px; border-bottom: 1px solid var(--color-border); }
.inline-state p, .context-summary p { margin: 0; color: var(--color-text-secondary); }
.context-summary { display: grid; justify-content: start; }
.context-summary span { color: var(--color-text-muted); font-size: 0.78rem; }
.task-context-summary { display: grid; gap: var(--space-sm); padding: var(--space-sm); border-radius: var(--radius-md); background: var(--surface-furrow); border: none; }
.task-context-head { display: flex; justify-content: space-between; gap: 12px; align-items: baseline; }
.task-context-head span { color: var(--color-text-muted); font-size: 0.78rem; }
.task-context-summary dl { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin: 0; }
.task-context-summary dl > div { min-width: 0; }
.task-context-summary dt { color: var(--color-text-muted); font-size: 0.72rem; }
.task-context-summary dd { margin: 3px 0 0; color: var(--color-text); overflow-wrap: anywhere; }
.task-context-summary > p, .task-requirements p { margin: 0; color: var(--color-text-secondary); }
.material-selection { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-top: 10px; border-top: 1px solid var(--color-border); color: var(--color-text-secondary); font-size: 0.82rem; }
.task-requirements { display: grid; gap: 7px; padding-top: 10px; border-top: 1px solid var(--color-border); }
.task-requirements > div { display: grid; grid-template-columns: minmax(100px, 0.35fr) 1fr; gap: 10px; }
.task-requirements span { color: var(--color-text-muted); font-size: 0.78rem; overflow-wrap: anywhere; }
.start-bar { position: sticky; bottom: var(--space-xs); padding: var(--space-sm) var(--space-md); border: 1px solid var(--color-border); border-radius: var(--radius-md); background: color-mix(in srgb, var(--color-surface) 94%, transparent); backdrop-filter: blur(12px); box-shadow: var(--shadow-card); }
.start-bar > div { display: flex; align-items: center; gap: 12px; }
.primary-command, .secondary-command { min-height: 38px; padding: 0 var(--space-md); border-radius: var(--radius-sm); cursor: pointer; }
.primary-command:disabled { opacity: 0.45; cursor: not-allowed; }
.planned-state { color: var(--color-warning); font-size: 0.82rem; }
.error-state { margin: 0; color: var(--color-danger); font-size: 0.84rem; }
@media (max-width: 760px) {
  .platform-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .platform-option:nth-child(3n) { border-right: 1px solid var(--color-border); }
  .platform-option:nth-child(2n) { border-right: 0; }
  .platform-option:nth-last-child(-n + 3) { border-bottom: 1px solid var(--color-border); }
  .platform-option:last-child { border-bottom: 0; }
  .source-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .task-context-summary dl { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .start-bar { align-items: flex-start; flex-direction: column; }
  .start-bar > div { width: 100%; justify-content: space-between; }
}
</style>
