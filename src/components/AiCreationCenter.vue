<template>
  <section class="creation-center" aria-labelledby="creation-center-title">
    <header class="center-head">
      <div>
        <p class="section-kicker">AI 内容创作中心</p>
        <h2 id="creation-center-title">{{ sectionTitle }}</h2>
      </div>
      <p class="capability-version">规则 {{ AI_PLATFORM_CAPABILITY_VERSION }}</p>
    </header>

    <nav class="center-tabs" role="tablist" aria-label="创作中心模块">
      <button
        v-for="section in centerSections"
        :key="section.id"
        type="button"
        role="tab"
        :aria-selected="activeSection === section.id"
        :class="{ active: activeSection === section.id }"
        @click="selectSection(section.id)"
      >{{ section.label }}</button>
    </nav>

    <template v-if="activeSection === 'create'">
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
        <span v-if="taskSourceLocked">由工作台带入</span>
      </div>
      <div class="source-grid" role="group" aria-label="创作来源">
        <button
          v-for="sourceOption in sourceOptions"
          :key="sourceOption.id"
          type="button"
          class="source-option"
          :class="{ selected: sourceOption.id === sourceType }"
          :aria-pressed="sourceOption.id === sourceType"
          :disabled="taskSourceLocked"
          @click="selectSource(sourceOption.id)"
        >
          <strong>{{ sourceOption.label }}</strong>
          <span>{{ sourceOption.note }}</span>
        </button>
      </div>
      </section>

      <section v-if="sourceType" class="context-band" aria-labelledby="context-title">
      <div class="choice-title-row">
        <h3 id="context-title">创作上下文</h3>
        <span v-if="sourceType === 'task'">仅用于预填，不作为任务核实依据</span>
      </div>

      <div v-if="sourceType === 'store' && !taskSourceLocked" class="store-fields">
        <label>
          组织
          <select name="organization" :value="organizationId" :disabled="loadingContext" @change="handleOrganizationChange">
            <option value="">请选择组织</option>
            <option v-for="org in organizations" :key="org.id" :value="org.id">{{ org.name }}</option>
          </select>
        </label>
        <label>
          门店
          <select name="store" :value="storeId" :disabled="!organizationId || loadingContext" @change="handleStoreChange">
            <option value="">请选择门店</option>
            <option v-for="store in stores" :key="store.id" :value="store.id">{{ store.name }}</option>
          </select>
        </label>
      </div>

      <div v-if="sourceType === 'task' && !taskSourceLocked" class="inline-state">
        <p>从草场工作台中已接受的履约进入创作。</p>
        <button type="button" class="secondary-command" @click="emit('open-grassland')">打开草场</button>
      </div>

      <div v-if="sourceType === 'hot-topic'" class="hot-picker">
        <div class="hot-picker-head">
          <p class="hot-picker-note">{{ hotMetaNote }}</p>
          <button type="button" class="secondary-command hot-refresh" :disabled="hotLoading" @click="refreshHotItems">
            {{ hotLoading ? '刷新中…' : '刷新热点' }}
          </button>
        </div>

        <div v-if="hotLoading && !hotHasContent" class="hot-skeleton-list" aria-hidden="true">
          <div v-for="index in 4" :key="index" class="hot-skeleton"></div>
        </div>

        <p v-else-if="hotError" class="error-state" role="alert">{{ hotError }}</p>

        <template v-else-if="hotHasContent">
          <div v-if="hotGroups.length > 1" class="hot-tabs" role="tablist" aria-label="热点平台">
            <button
              v-for="group in hotGroups"
              :key="group.platform"
              type="button"
              role="tab"
              :aria-selected="activeHotPlatform === group.platform"
              :class="{ active: activeHotPlatform === group.platform }"
              @click="activeHotPlatform = group.platform"
            >{{ group.label }}</button>
          </div>

          <ol class="hot-list">
            <li v-for="item in hotActiveItems" :key="`${item.rank}-${item.title}`" class="hot-item">
              <span class="hot-rank">{{ item.rank }}</span>
              <div class="hot-main">
                <a v-if="item.url" class="hot-title-link" :href="item.url" target="_blank" rel="noreferrer">{{ item.title }}</a>
                <p v-else class="hot-title">{{ item.title }}</p>
                <span class="hot-meta">
                  <template v-if="item.hotValue">热度 {{ item.hotValue }}</template>
                  <template v-if="item.hotValue && item.sourceLabel"> · </template>
                  <template v-if="item.sourceLabel">{{ item.sourceLabel }}</template>
                </span>
              </div>
              <button
                type="button"
                class="hot-pick"
                :class="{ selected: topic === item.title }"
                @click="pickHotTopic(item.title)"
              >{{ topic === item.title ? '已选' : '选为选题' }}</button>
            </li>
          </ol>
        </template>

        <p v-else class="hot-empty-note">暂无热点数据，稍后点击「刷新热点」重试。</p>

        <div v-if="pickedHotTitle" class="hot-refine">
          <button
            type="button"
            class="secondary-command"
            :disabled="assistant.resolvingTopic.value"
            @click="refineHotTopic"
          >{{ assistant.resolvingTopic.value ? '生成选题中…' : 'AI 拆解为结构化选题' }}</button>
          <p v-if="assistant.topicError.value" class="error-state" role="alert">
            {{ assistant.topicError.value }}
          </p>
          <dl v-else-if="assistant.structuredTopic.value" class="hot-topic-detail">
            <div><dt>选题</dt><dd>{{ assistant.structuredTopic.value.topic }}</dd></div>
            <div><dt>角度</dt><dd>{{ assistant.structuredTopic.value.angle }}</dd></div>
            <div><dt>立意</dt><dd>{{ assistant.structuredTopic.value.thesis }}</dd></div>
            <div><dt>受众</dt><dd>{{ assistant.structuredTopic.value.audience }}</dd></div>
            <div v-if="assistant.structuredTopic.value.entryPoints.length">
              <dt>切入点</dt>
              <dd>
                <ul class="hot-entry-points">
                  <li v-for="(point, index) in assistant.structuredTopic.value.entryPoints" :key="index">
                    {{ point }}
                  </li>
                </ul>
              </dd>
            </div>
          </dl>
        </div>
      </div>

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

      <label v-if="sourceType === 'reference'" class="topic-field">
        <span>参考链接或分享文本</span>
        <textarea
          v-model="referenceUrl"
          name="reference-url"
          rows="3"
          maxlength="2000"
          placeholder="粘贴抖音或 B 站链接、分享文本"
        />
      </label>

      <label v-if="sourceType === 'independent' || sourceType === 'hot-topic' || sourceType === 'store'" class="topic-field">
        <span>补充要求</span>
        <textarea v-model="instructions" rows="3" maxlength="1000" placeholder="可选：语气、重点、必须包含或避免的内容" />
      </label>

      <div v-if="entry?.prefill && taskSourceLocked" class="context-summary">
        <strong>{{ entry.prefill.topic || '任务创作' }}</strong>
        <span v-if="entry.source.type === 'task' && entry.source.taskVersion">任务版本 {{ entry.source.taskVersion }}</span>
        <p v-if="entry.prefill.instructions">{{ entry.prefill.instructions }}</p>
      </div>

      <p v-if="contextError" class="error-state" role="alert">{{ contextError }}</p>
      </section>

      <footer v-if="sourceType" class="start-bar">
        <p data-testid="selection-summary">{{ selectionSummary }}</p>
        <div>
          <span v-if="workflow.status === 'planned'" class="planned-state">该创作路径尚未接入</span>
          <button type="button" class="primary-command" :disabled="!canStart" @click="startWorkflow">开始创作</button>
        </div>
      </footer>
    </template>

    <AiRunHistoryPanel v-else-if="activeSection === 'runs'" />
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
      @request-login="emit('request-login')"
    />
    <AiProviderKeysPanel v-else />
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import AiProviderKeysPanel from './AiProviderKeysPanel.vue'
import AiRunHistoryPanel from './AiRunHistoryPanel.vue'
import CreationAssistantPanel from './CreationAssistantPanel.vue'
import MediaLibraryPanel from './MediaLibraryPanel.vue'
import { useCreationAssistant } from '../composables/useCreationAssistant'
import {
  AI_PLATFORM_CAPABILITY_VERSION,
  AI_PLATFORM_DEFINITIONS,
  getPlatform,
  resolveWorkflow,
} from '../config/ai-platform-capabilities'
import { useGrassland } from '../composables/useGrassland'
import { useHomepageHotItems } from '../composables/useHomepageHotItems'
import type { Organization, Store, StoreProfile } from '../types/grassland'
import type {
  AiContentFormId,
  AiPlatformId,
  CreationDraftPrefill,
  CreationEntry,
  CreationHandoff,
  CreationSource,
  CreationSourceType,
} from '../types/ai-creation'

type AiCenterSection = 'create' | 'runs' | 'assistant' | 'keys' | 'library'

const props = defineProps<{
  authenticated: boolean
  entry: CreationEntry | null
}>()

const emit = defineEmits<{
  'start-workflow': [handoff: CreationHandoff]
  'request-login': []
  'open-grassland': []
}>()

const grassland = useGrassland()
const assistant = useCreationAssistant()
const activeSection = ref<AiCenterSection>('create')
/** 已选热点标题（与 topic 分开存：topic 会被结构化选题覆盖，refine 仍需原标题）。 */
const pickedHotTitle = ref('')
const platformId = ref<AiPlatformId | ''>('')
const contentFormId = ref<AiContentFormId | ''>('')
const sourceType = ref<CreationSourceType | ''>('')
const topic = ref('')
const instructions = ref('')
const referenceUrl = ref('')
const organizations = ref<Organization[]>([])
const stores = ref<Store[]>([])
const organizationId = ref('')
const storeId = ref('')
const storeProfile = ref<StoreProfile | null>(null)
const loadingContext = ref(false)
const contextError = ref('')
const storeProfileLoaded = ref(false)
const hydratedRevision = ref<number | null>(null)
let contextRequestEpoch = 0
let hotRefineEpoch = 0
let workflowRevision = Date.now()

const centerSections: ReadonlyArray<{ id: AiCenterSection; label: string }> = [
  { id: 'create', label: '开始创作' },
  { id: 'assistant', label: '创作助手' },
  { id: 'runs', label: '运行记录' },
  { id: 'library', label: '素材库' },
  { id: 'keys', label: '模型密钥' },
]

const sourceOptions: ReadonlyArray<{ id: CreationSourceType; label: string; note: string }> = [
  { id: 'independent', label: '独立创作', note: '从主题或想法开始' },
  { id: 'task', label: '从任务创作', note: '带入已接受履约' },
  { id: 'store', label: '从门店创作', note: '带入门店资料' },
  { id: 'hot-topic', label: '从热点创作', note: '以热点标题为主题' },
  { id: 'reference', label: '参考素材', note: '分析抖音或 B站视频' },
]

const taskSourceLocked = computed(() => props.entry?.source.type === 'task')
/**
 * 任务要求快照，传给助手做覆盖检查（§4.9.3）。intelligence 不跨服务读 marketplace，
 * 要求文本必须由前端从 entry 的 task 快照带入；非任务来源为 undefined（助手据此隐藏该能力）。
 */
const taskRequirements = computed(() => {
  if (props.entry?.source.type !== 'task') return undefined
  const parts = [props.entry.prefill?.topic, props.entry.prefill?.instructions]
    .map((part) => part?.trim()).filter(Boolean)
  return parts.length ? parts.join('\n') : undefined
})
const assistantSource = computed<CreationSource | undefined>(() => sourceForHandoff() ?? undefined)
const platformLocked = computed(() => taskSourceLocked.value && Boolean(props.entry?.platformId))
const contentFormLocked = computed(() => taskSourceLocked.value && Boolean(props.entry?.contentFormId))
const selectedPlatform = computed(() => platformId.value ? getPlatform(platformId.value) : null)
const sectionTitle = computed(() => {
  if (activeSection.value === 'runs') return 'AI 运行记录'
  if (activeSection.value === 'assistant') return '智能创作助手'
  if (activeSection.value === 'library') return '内容素材库'
  if (activeSection.value === 'keys') return '模型密钥'
  return '选择发布平台'
})
const workflow = computed(() => platformId.value && contentFormId.value && sourceType.value
  ? resolveWorkflow(platformId.value, contentFormId.value, sourceType.value)
  : { status: 'unsupported' as const, workflowId: null, targetView: null })
const selectionSummary = computed(() => {
  const platform = selectedPlatform.value?.label || '未选平台'
  const form = selectedPlatform.value?.forms.find((item) => item.id === contentFormId.value)?.label || '未选形式'
  return `${platform} · ${form}`
})
const canStart = computed(() => {
  if (workflow.value.status !== 'available' || !sourceType.value) return false
  if (sourceType.value === 'task') return props.authenticated && props.entry?.source.type === 'task'
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
    return
  }
  if (hydratedRevision.value === entry.revision) return
  contextRequestEpoch += 1
  activeSection.value = 'create'
  hydratedRevision.value = entry.revision
  platformId.value = entry.platformId ?? ''
  contentFormId.value = entry.contentFormId ?? ''
  sourceType.value = entry.source.type
  topic.value = entry.prefill?.topic || ''
  instructions.value = entry.prefill?.instructions || ''
  pickedHotTitle.value = entry.source.type === 'hot-topic' ? entry.source.title : ''
  referenceUrl.value = entry.source.type === 'reference' ? entry.source.sourceUrl || '' : ''
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

function selectPlatform(next: AiPlatformId): void {
  const preservesEntryContext = props.entry?.source.type === 'hot-topic' && !platformId.value
  if (sourceType.value === 'hot-topic') clearHotTopicContext(!preservesEntryContext)
  platformId.value = next
  contentFormId.value = ''
  if (!props.entry) sourceType.value = ''
}

function selectContentForm(next: AiContentFormId): void {
  const preservesEntryContext = props.entry?.source.type === 'hot-topic' && !contentFormId.value
  if (sourceType.value === 'hot-topic') clearHotTopicContext(!preservesEntryContext)
  contentFormId.value = next
  if (!props.entry) sourceType.value = ''
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
  if ((next === 'task' || next === 'store') && !props.authenticated) {
    emit('request-login')
    return
  }
  if (sourceType.value === 'hot-topic' && next !== 'hot-topic') {
    clearHotTopicContext()
  }
  sourceType.value = next
  contextError.value = ''
  if (next === 'store' && organizations.value.length === 0) await loadOrganizations()
}

const {
  items: hotItems,
  groups: hotGroups,
  provider: hotProvider,
  fetchedAt: hotFetchedAt,
  loading: hotLoading,
  error: hotError,
  loadHotItems: fetchHotItems,
} = useHomepageHotItems()
const hotLoaded = ref(false)
const activeHotPlatform = ref('')

const hotHasContent = computed(() => hotGroups.value.length > 0 || hotItems.value.length > 0)
const hotActiveItems = computed(() => {
  const group = hotGroups.value.find((entry) => entry.platform === activeHotPlatform.value)
  return group ? group.items : hotItems.value
})
const hotMetaNote = computed(() => {
  const notes = [hotProvider.value === 'alapi' ? '来源 ALAPI' : '来源 60s']
  const parsedTime = hotFetchedAt.value ? new Date(hotFetchedAt.value) : null
  if (parsedTime && !Number.isNaN(parsedTime.getTime())) {
    notes.push(`抓取于 ${formatHotFetchedTime(parsedTime)}`)
  }
  return notes.join(' · ')
})

watch(sourceType, (next) => {
  if (next === 'hot-topic') void ensureHotItemsLoaded()
}, { immediate: true })

watch(hotGroups, (groups) => {
  if (groups.length === 0) {
    activeHotPlatform.value = ''
  } else if (!groups.some((group) => group.platform === activeHotPlatform.value)) {
    activeHotPlatform.value = groups[0].platform
  }
}, { immediate: true })

async function ensureHotItemsLoaded(): Promise<void> {
  if (hotLoaded.value || hotLoading.value) return
  await fetchHotItems()
  // 加载失败不标记为已加载，重新选择该来源时会自动重试
  if (!hotError.value) hotLoaded.value = true
}

async function refreshHotItems(): Promise<void> {
  await fetchHotItems()
  hotLoaded.value = true
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

function formatHotFetchedTime(value: Date): string {
  const pad = (num: number) => String(num).padStart(2, '0')
  return `${pad(value.getMonth() + 1)}-${pad(value.getDate())} ${pad(value.getHours())}:${pad(value.getMinutes())}`
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

async function loadOrganizations(): Promise<void> {
  const requestEpoch = ++contextRequestEpoch
  loadingContext.value = true
  const result = await grassland.listOrganizations()
  if (requestEpoch !== contextRequestEpoch) return
  organizations.value = result ? [...result] : []
  contextError.value = result ? '' : grassland.error.value
  loadingContext.value = false
}

async function handleOrganizationChange(event: Event): Promise<void> {
  const nextOrganizationId = (event.target as HTMLSelectElement).value
  const requestEpoch = ++contextRequestEpoch
  organizationId.value = nextOrganizationId
  storeId.value = ''
  stores.value = []
  storeProfile.value = null
  storeProfileLoaded.value = false
  contextError.value = ''
  if (!nextOrganizationId) {
    loadingContext.value = false
    return
  }
  loadingContext.value = true
  const result = await grassland.listStores(nextOrganizationId)
  if (requestEpoch !== contextRequestEpoch || organizationId.value !== nextOrganizationId) return
  stores.value = result ? [...result] : []
  contextError.value = result ? '' : grassland.error.value
  loadingContext.value = false
}

async function handleStoreChange(event: Event): Promise<void> {
  const nextStoreId = (event.target as HTMLSelectElement).value
  const requestOrganizationId = organizationId.value
  const requestEpoch = ++contextRequestEpoch
  storeId.value = nextStoreId
  storeProfile.value = null
  storeProfileLoaded.value = false
  if (!nextStoreId) {
    loadingContext.value = false
    return
  }
  loadingContext.value = true
  contextError.value = ''
  try {
    const profile = await grassland.getStoreProfile(requestOrganizationId, nextStoreId)
    if (
      requestEpoch !== contextRequestEpoch
      || organizationId.value !== requestOrganizationId
      || storeId.value !== nextStoreId
    ) return
    storeProfile.value = profile
    storeProfileLoaded.value = Boolean(profile)
    if (!profile) contextError.value = grassland.error.value || '门店资料加载失败'
    const store = stores.value.find((item) => item.id === nextStoreId)
    topic.value = store?.name || ''
  } catch (error: unknown) {
    if (requestEpoch !== contextRequestEpoch) return
    contextError.value = error instanceof Error ? error.message : '门店资料加载失败'
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
  if (props.entry?.prefill && taskSourceLocked.value) return { ...props.entry.prefill }
  const store = stores.value.find((item) => item.id === storeId.value)
  return {
    topic: topic.value.trim() || undefined,
    instructions: instructions.value.trim() || undefined,
    storeName: store?.name,
    address: parseAddress(storeProfile.value?.address),
    storeDescription: storeProfile.value?.description || undefined,
  }
}

function startWorkflow(): void {
  if (!canStart.value || !platformId.value || !contentFormId.value) return
  const source = sourceForHandoff()
  if (!source || !workflow.value.workflowId || !workflow.value.targetView) return
  emit('start-workflow', {
    revision: nextWorkflowRevision(),
    platformId: platformId.value,
    contentFormId: contentFormId.value,
    source,
    workflowId: workflow.value.workflowId,
    targetView: workflow.value.targetView,
    prefill: prefillForHandoff(),
  })
}

function nextWorkflowRevision(): number {
  workflowRevision = Math.max(workflowRevision + 1, Date.now(), (props.entry?.revision ?? 0) + 1)
  return workflowRevision
}
</script>

<style scoped>
.creation-center { display: grid; gap: 22px; max-width: 1040px; margin: 0 auto; }
.center-head, .choice-title-row, .start-bar { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.center-head h2, .choice-title-row h3 { margin: 0; color: var(--color-text); letter-spacing: 0; }
.center-head h2 { font-size: 1.5rem; }
.choice-title-row h3 { font-size: 1rem; }
.section-kicker, .capability-version, .choice-title-row span, .start-bar p { margin: 0; color: var(--color-text-muted); font-size: 0.78rem; }
.section-kicker { margin-bottom: 4px; font-weight: 700; color: var(--color-accent); }
.center-tabs { display: flex; gap: 4px; border-bottom: 1px solid var(--color-border); }
.center-tabs button { min-height: 40px; padding: 0 14px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--color-text-muted); cursor: pointer; }
.center-tabs button.active { border-bottom-color: var(--color-accent); color: var(--color-text); font-weight: 600; }
.platform-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); border: 1px solid var(--color-border); border-radius: 8px; overflow: hidden; }
.platform-option { min-height: 78px; padding: 14px; display: grid; gap: 5px; text-align: left; background: var(--color-surface); color: var(--color-text); border: 0; border-right: 1px solid var(--color-border); border-bottom: 1px solid var(--color-border); cursor: pointer; }
.platform-option:nth-child(3n) { border-right: 0; }
.platform-option:nth-last-child(-n + 3) { border-bottom: 0; }
.platform-option span, .source-option span { color: var(--color-text-muted); font-size: 0.78rem; }
.platform-option.selected, .source-option.selected { background: color-mix(in srgb, var(--color-accent) 10%, var(--color-surface)); box-shadow: inset 3px 0 0 var(--color-accent); }
.platform-option:disabled { cursor: default; opacity: 0.72; }
.choice-band, .context-band { display: grid; gap: 14px; padding-top: 18px; border-top: 1px solid var(--color-border); }
.segmented { display: inline-flex; width: fit-content; padding: 3px; gap: 3px; background: var(--color-surface-muted); border: 1px solid var(--color-border); border-radius: 7px; }
.segmented button { min-width: 108px; padding: 8px 14px; border: 0; border-radius: 5px; color: var(--color-text-secondary); background: transparent; cursor: pointer; }
.segmented button.active { background: var(--color-surface); color: var(--color-text); box-shadow: var(--shadow-sm); }
.source-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 8px; }
.source-option { min-height: 72px; display: grid; gap: 5px; align-content: center; padding: 10px; text-align: left; border: 1px solid var(--color-border); border-radius: 7px; background: var(--color-surface); color: var(--color-text); cursor: pointer; }
.store-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.store-fields label, .topic-field { display: grid; gap: 6px; color: var(--color-text-secondary); font-size: 0.82rem; }
select, textarea { width: 100%; box-sizing: border-box; border: 1px solid var(--color-border); border-radius: 6px; background: var(--color-surface); color: var(--color-text); padding: 9px 10px; font: inherit; letter-spacing: 0; }
textarea { resize: vertical; min-height: 76px; }
.inline-state, .context-summary { padding: 12px 0; display: flex; align-items: center; justify-content: space-between; gap: 12px; border-bottom: 1px solid var(--color-border); }
.inline-state p, .context-summary p { margin: 0; color: var(--color-text-secondary); }
.context-summary { display: grid; justify-content: start; }
.context-summary span { color: var(--color-text-muted); font-size: 0.78rem; }
.start-bar { position: sticky; bottom: 12px; padding: 12px 14px; border: 1px solid var(--color-border); border-radius: 8px; background: color-mix(in srgb, var(--color-surface) 94%, transparent); backdrop-filter: blur(12px); }
.start-bar > div { display: flex; align-items: center; gap: 12px; }
.primary-command, .secondary-command { padding: 9px 15px; border-radius: 6px; cursor: pointer; }
.primary-command { border: 1px solid var(--color-accent); background: var(--color-accent); color: #fff; font-weight: 700; }
.secondary-command { border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); }
.primary-command:disabled { opacity: 0.45; cursor: not-allowed; }
.planned-state { color: var(--color-warning, #a16207); font-size: 0.82rem; }
.error-state { margin: 0; color: var(--color-danger); font-size: 0.84rem; }
.hot-picker { display: grid; gap: 10px; padding: 12px; border: 1px solid var(--color-border); border-radius: 8px; background: var(--color-surface); }
.hot-picker-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.hot-refresh { padding: 6px 12px; font-size: 0.8rem; }
.hot-refresh:disabled { opacity: 0.5; cursor: not-allowed; }
.hot-picker-note { margin: 0; color: var(--color-text-muted); font-size: 0.78rem; }
.hot-tabs { display: flex; gap: 4px; flex-wrap: wrap; }
.hot-tabs button { padding: 5px 12px; border: 1px solid var(--color-border); border-radius: 999px; background: transparent; color: var(--color-text-muted); cursor: pointer; font-size: 0.8rem; }
.hot-tabs button.active { border-color: var(--color-accent); color: var(--color-accent); font-weight: 600; }
.hot-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 2px; max-height: 324px; overflow-y: auto; }
.hot-item { display: flex; align-items: center; gap: 10px; padding: 8px 6px; border-bottom: 1px solid var(--color-border); }
.hot-item:last-child { border-bottom: 0; }
.hot-rank { flex: 0 0 22px; text-align: center; color: var(--color-text-muted); font-size: 0.8rem; font-weight: 700; }
.hot-main { flex: 1; min-width: 0; display: grid; gap: 2px; }
.hot-title, .hot-title-link { margin: 0; color: var(--color-text); font-size: 0.86rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hot-title-link { text-decoration: none; }
.hot-title-link:hover { color: var(--color-accent); }
.hot-meta { color: var(--color-text-muted); font-size: 0.74rem; }
.hot-pick { flex: 0 0 auto; padding: 5px 10px; border: 1px solid var(--color-border); border-radius: 6px; background: var(--color-surface); color: var(--color-text-secondary); cursor: pointer; font-size: 0.78rem; }
.hot-pick.selected { border-color: var(--color-accent); color: var(--color-accent); font-weight: 600; }
.hot-skeleton-list { display: grid; gap: 8px; }
.hot-skeleton { height: 34px; border-radius: 6px; background: var(--color-surface-muted); animation: hot-pulse 1.2s ease-in-out infinite; }
.hot-empty-note { margin: 0; color: var(--color-text-muted); font-size: 0.84rem; }
.hot-refine { display: grid; gap: 8px; padding-top: 8px; border-top: 1px solid var(--color-border); }
.hot-topic-detail { display: grid; gap: 6px; margin: 0; }
.hot-topic-detail > div { display: flex; gap: 8px; font-size: 0.84rem; }
.hot-topic-detail dt { flex: 0 0 44px; color: var(--color-text-muted); }
.hot-topic-detail dd { margin: 0; }
.hot-entry-points { margin: 0; padding-left: 18px; }
@keyframes hot-pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.55; } }
@media (max-width: 760px) {
  .platform-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .platform-option:nth-child(3n) { border-right: 1px solid var(--color-border); }
  .platform-option:nth-child(2n) { border-right: 0; }
  .platform-option:nth-last-child(-n + 3) { border-bottom: 1px solid var(--color-border); }
  .platform-option:last-child { border-bottom: 0; }
  .source-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .store-fields { grid-template-columns: 1fr; }
  .start-bar { align-items: flex-start; flex-direction: column; }
  .start-bar > div { width: 100%; justify-content: space-between; }
}
</style>
