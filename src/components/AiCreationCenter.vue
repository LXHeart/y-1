<template>
  <section class="creation-center" aria-labelledby="creation-center-title">
    <header class="center-head">
      <div>
        <p class="section-kicker">AI 内容创作中心</p>
        <h2 id="creation-center-title">选择发布平台</h2>
      </div>
      <p class="capability-version">规则 {{ AI_PLATFORM_CAPABILITY_VERSION }}</p>
    </header>

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
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  AI_PLATFORM_CAPABILITY_VERSION,
  AI_PLATFORM_DEFINITIONS,
  getPlatform,
  resolveWorkflow,
} from '../config/ai-platform-capabilities'
import { useGrassland } from '../composables/useGrassland'
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
let workflowRevision = Date.now()

const sourceOptions: ReadonlyArray<{ id: CreationSourceType; label: string; note: string }> = [
  { id: 'independent', label: '独立创作', note: '从主题或想法开始' },
  { id: 'task', label: '从任务创作', note: '带入已接受履约' },
  { id: 'store', label: '从门店创作', note: '带入门店资料' },
  { id: 'hot-topic', label: '从热点创作', note: '以热点标题为主题' },
  { id: 'reference', label: '参考素材', note: '分析抖音或 B站视频' },
]

const taskSourceLocked = computed(() => props.entry?.source.type === 'task')
const platformLocked = computed(() => taskSourceLocked.value && Boolean(props.entry?.platformId))
const contentFormLocked = computed(() => taskSourceLocked.value && Boolean(props.entry?.contentFormId))
const selectedPlatform = computed(() => platformId.value ? getPlatform(platformId.value) : null)
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
  return topic.value.trim().length > 0
})

watch(() => props.entry, (entry) => {
  if (!entry || hydratedRevision.value === entry.revision) return
  hydratedRevision.value = entry.revision
  platformId.value = entry.platformId ?? ''
  contentFormId.value = entry.contentFormId ?? ''
  sourceType.value = entry.source.type
  topic.value = entry.prefill?.topic || ''
  instructions.value = entry.prefill?.instructions || ''
  referenceUrl.value = entry.source.type === 'reference' ? entry.source.sourceUrl || '' : ''
  if (entry.source.type === 'store') {
    organizationId.value = entry.source.organizationId
    storeId.value = entry.source.storeId
  }
}, { immediate: true })

function selectPlatform(next: AiPlatformId): void {
  platformId.value = next
  contentFormId.value = ''
  if (!props.entry) sourceType.value = ''
}

function selectContentForm(next: AiContentFormId): void {
  contentFormId.value = next
  if (!props.entry) sourceType.value = ''
}

async function selectSource(next: CreationSourceType): Promise<void> {
  if ((next === 'task' || next === 'store') && !props.authenticated) {
    emit('request-login')
    return
  }
  sourceType.value = next
  contextError.value = ''
  if (next === 'store' && organizations.value.length === 0) await loadOrganizations()
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
      title: topic.value.trim(),
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
