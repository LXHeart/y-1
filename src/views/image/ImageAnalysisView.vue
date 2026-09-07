<template>
  <div class="image-analysis gl-field">
    <nav class="page-back" aria-label="创作流程导航">
      <button class="btn-back" type="button" @click="emit('open-view', 'ai-center')">
        <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path d="M10 3L5 8l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        返回创作中心
      </button>
      <span v-if="platformLocked" class="page-back-context">大众点评 · 图文创作</span>
    </nav>
    <section class="image-shell">
    <!-- 任务书 #91 I1：控制卡面板化 components/AnalysisControlCard.vue；飞书凭据由卡片持有，         导出守卫 handleExportToFeishu 经 defineExpose 上抛给视图接线结果卡。 -->    <AnalysisControlCard      ref="controlCardRef"      :images="images" :platform-locked="platformLocked"      v-model:platform="platform"      v-model:review-length="reviewLength"      v-model:feelings="feelings"      :loading="loading" :generation-stage="generationStage"      :add-files="addFiles" :remove-image="removeImage" :preview-image="previewImage"      :start-generation="startGeneration" :handle-reset="handleReset" :cancel-analysis="cancelAnalysis"    />
      <section class="preview-column">
        <SessionVersionsCard
          :versions="sessionVersions"
          :selected-id="selectedVersionId"
          :loading="loading"
          :result="result"
          :is-editing="isEditing"
          @save-version="saveVersionSnapshot()"
          @select-version="selectVersion"
          @remove-version="removeVersion"
        />

        <section v-if="generationStage === 'drafting'" class="progress-card gl-zone fade-in">
          <header class="result-head">
            <div>
              <p class="section-kicker">生成进度</p>
              <h3 class="result-title">正在逐步整理图片评价文案</h3>
            </div>
            <span v-if="currentProgress?.attempt && currentProgress?.totalAttempts" class="progress-count">
              {{ currentProgress.attempt }}/{{ currentProgress.totalAttempts }}
            </span>
          </header>

          <p class="status-copy">
            {{ currentProgress?.message || '正在准备生成…' }}
          </p>

          <GenerationStepsList            :items="progressEvents" key-prefix="" :clickable="false"            :step-results="stepResults" :get-stage-label="getStageLabel"            :get-event-duration-label="getEventDurationLabel" :select-step-result="selectStepResult"          />        </section>

        <section v-else-if="showStepLoading" class="progress-card gl-zone fade-in">
          <header class="result-head">
            <div>
              <p class="section-kicker">生成进度</p>
              <h3 class="result-title">{{ loadingLabel }}</h3>
            </div>
          </header>
          <p class="status-copy step-loading-copy">
            <svg class="spin-icon" width="20" height="20" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="2" stroke-dasharray="28" stroke-dashoffset="10" stroke-linecap="round"/></svg>
            {{ loadingDescription }}
          </p>
        </section>

        <section v-else-if="error && !result" class="status-card status-card-error gl-zone fade-in">
          <p class="status-title">生成失败</p>
          <p class="status-copy">{{ error }}</p>
        </section>

        <!-- 任务书 #91 I2：步审卡面板化 components/StepReviewCard.vue -->        <StepReviewCard          v-else-if="result && isStepReview"          :result="result" :step-label="stepLabel" :step-description="stepDescription"          :error="error" :is-editing="isEditing" :saving-style="savingStyle"          :save-style-error="saveStyleError" :save-style-success="saveStyleSuccess"          v-model:edit-title="editTitle"          v-model:edit-review="editReview"          v-model:new-tag-input="newTagInput"          @add-tag="addEditTag"          :edit-tags="editTags" :safety-report="safetyReport"          @update:safety-report="safetyReport = $event"          :generation-stage="generationStage" :has-generation-steps="hasGenerationSteps"          :progress-events="progressEvents" :step-results="stepResults"          :get-stage-label="getStageLabel" :get-event-duration-label="getEventDurationLabel"          :select-step-result="selectStepResult"          :start-editing="startEditing" :cancel-editing="cancelEditing"          :apply-edits-locally="handleApplyEditsLocally" :save-style-memory="handleSaveStyleMemory"          :remove-edit-tag="removeEditTag"          :proceed-to-optimize="proceedToOptimize" :proceed-to-style-refine="proceedToStyleRefine"        />
        <!-- 任务书 #91 I2：结果卡面板化 components/AnalysisResultCard.vue -->        <AnalysisResultCard          v-else-if="result"          :result="result" :copy-label="copyLabel" :copy-link-label="copyLinkLabel"          :is-editing="isEditing" :saving-style="savingStyle"          :save-style-error="saveStyleError" :save-style-success="saveStyleSuccess"          v-model:edit-title="editTitle"          v-model:edit-review="editReview"          v-model:new-tag-input="newTagInput"          @add-tag="addEditTag"          :edit-tags="editTags" :safety-report="safetyReport"          @update:safety-report="safetyReport = $event"          :exporting="exporting" :export-error="exportError"          :exported-doc-url="exportedDocUrl" :exported-doc-title="exportedDocTitle"          :show-generation-steps="showGenerationSteps" :has-generation-steps="hasGenerationSteps"          :generation-step-toggle-label="generationStepToggleLabel"          :loading="loading" :loading-preferences="loadingPreferences" :show-style-preferences="showStylePreferences"          :progress-events="progressEvents" :step-results="stepResults"          :get-stage-label="getStageLabel" :get-event-duration-label="getEventDurationLabel"          :select-step-result="selectStepResult"          :copy-review="copyReview" :toggle-generation-steps="toggleGenerationSteps"          :start-editing="startEditing" :cancel-editing="cancelEditing"          :apply-edits-locally="handleApplyEditsLocally" :save-style-memory="handleSaveStyleMemory"          :remove-edit-tag="removeEditTag"          :guarded-export="(action: () => Promise<void>) => controlCardRef?.handleExportToFeishu(action)"          :export-to-feishu="exportToFeishu"          :toggle-style-preferences="toggleStylePreferences"          :save-version-snapshot="() => saveVersionSnapshot()"          :copy-doc-link="copyDocLink"        />
        <section v-else class="empty-card gl-zone">
          <p class="section-kicker">等待生成</p>
          <h2 class="empty-title">上传图片后，这里会显示评价结果</h2>
          <p class="empty-copy">{{ platformLocked ? '生成完成后可直接复制标题、正文和标签，用于大众点评发布。' : '生成完成后可直接复制标题、正文和标签，用于淘宝或大众点评发布。' }}</p>
        </section>
      </section>
    </section>

    <OversizedImageDialog
      :visible="showOversizedDialog"
      :files="oversizedFiles"
      :compressing="compressing"
      @compress="compressOversizedImages"
      @skip="removeOversizedImages"
      @cancel="cancelOversizedImages"
    />

    <ImageLightbox
      :images="images"
      :preview-index="previewIndex"
      @close="closePreview"
      @navigate="(i: number) => previewIndex = i"
    />

    <StylePreferencesModal
      :visible="showStylePreferences"
      :loading="loadingPreferences"
      :preferences="stylePreferences"
      :saving="savingPreference"
      :optimizing="optimizingPreferences"
      :optimized-preferences="optimizedPreferences"
      :optimize-error="optimizeError"
      :page="preferencePage"
      :total-pages="totalPreferencePages"
      :paginated-preferences="paginatedPreferences"
      :paginated-start-index="paginatedStartIndex"
      :editing-index="editingPreferenceIndex"
      :editing-value="editingPreferenceValue"
      @toggle="toggleStylePreferences"
      @optimize="optimizePreferences"
      @confirm-optimize="confirmOptimizedPreferences"
      @cancel-optimize="cancelOptimizePreferences"
      @delete="deleteStylePreference"
      @start-edit="startEditingPreference"
      @confirm-edit="confirmEditingPreference"
      @cancel-edit="cancelEditingPreference"
      @update:page="(p: number) => preferencePage = p"
      @update:editing-value="(v: string) => editingPreferenceValue = v"
    />

    <StepResultOverlay
      :step-result="selectedStepResult"
      :get-stage-label="getStageLabel"
      @close="clearStepResult"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useImageAnalysis } from '../../composables/useImageAnalysis'
import type { CreationHandoff } from '../../types/ai-creation'
import type { ImageAnalysisProgressEvent, ImageAnalysisProgressStage } from '../../types/image-analysis'
import ImageLightbox from './components/ImageLightbox.vue'
import AnalysisControlCard from './components/AnalysisControlCard.vue'
import OversizedImageDialog from './components/OversizedImageDialog.vue'
import SessionVersionsCard from './components/SessionVersionsCard.vue'
import StepResultOverlay from './components/StepResultOverlay.vue'
import StylePreferencesModal from './components/StylePreferencesModal.vue'
import StepReviewCard from './components/StepReviewCard.vue'
import AnalysisResultCard from './components/AnalysisResultCard.vue'
import GenerationStepsList from './components/GenerationStepsList.vue'
import { useSessionVersions } from './composables/useSessionVersions'
import { useImagePreview } from './composables/useImagePreview'

const props = defineProps<{
  creationHandoff?: CreationHandoff | null
}>()

const emit = defineEmits<{
  'open-view': [view: 'ai-center']
}>()

// 任务书 #91 I1：飞书凭据维护已随 AnalysisControlCard 迁出（导出守卫经 ref 上抛）。控制卡引用：
const controlCardRef = ref<InstanceType<typeof AnalysisControlCard> | null>(null)

const {
  images,
  result,
  safetyReport,
  reviewLength,
  feelings,
  platform,
  loading,
  generationStage,
  error,
  progressEvents,
  currentProgress,
  exporting,
  exportError,
  exportedDocUrl,
  exportedDocTitle,
  isEditing,
  editTitle,
  editReview,
  editTags,
  savingStyle,
  saveStyleError,
  saveStyleSuccess,
  stylePreferences,
  loadingPreferences,
  showStylePreferences,
  oversizedFiles,
  showOversizedDialog,
  compressing,
  preferencePage,
  totalPreferencePages,
  paginatedPreferences,
  paginatedStartIndex,
  editingPreferenceIndex,
  editingPreferenceValue,
  savingPreference,
  optimizingPreferences,
  optimizedPreferences,
  optimizeError,
  addFiles,
  removeImage,
  cancel,
  reset,
  exportToFeishu,
  startGeneration,
  proceedToOptimize,
  proceedToStyleRefine,
  startEditing,
  cancelEditing,
  applyEditsLocally,
  saveStyleMemory,
  loadStylePreferences,
  toggleStylePreferences,
  compressOversizedImages,
  removeOversizedImages,
  cancelOversizedImages,
  stepResults,
  selectedStepResult,
  selectStepResult,
  clearStepResult,
  deleteStylePreference,
  startEditingPreference,
  confirmEditingPreference,
  cancelEditingPreference,
  optimizePreferences,
  confirmOptimizedPreferences,
  cancelOptimizePreferences,
  bindCreationContext,
} = useImageAnalysis()

const hydratedCreationRevision = ref<number | null>(null)
/** 创作中心带入的大众点评图文流：平台定死为大众点评，隐藏淘宝切换。 */
const platformLocked = ref(false)

watch(() => props.creationHandoff, (handoff) => {
  if (!handoff || handoff.targetView !== 'image' || hydratedCreationRevision.value === handoff.revision) return
  hydratedCreationRevision.value = handoff.revision
  reset()
  bindCreationContext(handoff.source.type === 'task', handoff.contextSnapshotId)
  platformLocked.value = true
  platform.value = 'dianping'
  feelings.value = [handoff.prefill?.topic, handoff.prefill?.instructions].filter(Boolean).join('\n')
}, { immediate: true })

const copyLabel = ref('复制文案')
const copyLinkLabel = ref('复制链接')
const showGenerationSteps = ref(false)
const newTagInput = ref('')
const now = ref(Date.now())
let nowTimer: number | null = null

// --- 本次会话多版本对比（纯前端内存态，不引入持久化） ---
const {
  sessionVersions,
  selectedVersionId,
  saveVersionSnapshot,
  selectVersion,
  removeVersion,
} = useSessionVersions(generationStage, result, platform, stepResults)

const { previewIndex, previewImage, closePreview } = useImagePreview()

function addEditTag(): void {
  const tag = newTagInput.value.trim()
  if (!tag) return
  editTags.value = [...editTags.value, tag]
  newTagInput.value = ''
}

function flushPendingTag(): void {
  addEditTag()
}

function removeEditTag(index: number): void {
  editTags.value = editTags.value.filter((_, i) => i !== index)
}

async function handleSaveStyleMemory(): Promise<void> {
  flushPendingTag()
  await saveStyleMemory()
}

function handleApplyEditsLocally(): void {
  flushPendingTag()
  applyEditsLocally()
}

watch(generationStage, (stage) => {
  if (stage === 'drafting') {
    showGenerationSteps.value = false
  }
  if (stage === 'optimize-review' && !stylePreferences.value.length && !loadingPreferences.value) {
    loadStylePreferences()
  }
})

async function copyDocLink(): Promise<void> {
  if (!exportedDocUrl.value) return
  try {
    await navigator.clipboard.writeText(exportedDocUrl.value)
    copyLinkLabel.value = '已复制'
    setTimeout(() => { copyLinkLabel.value = '复制链接' }, 2000)
  } catch {
    const ta = document.createElement('textarea')
    ta.value = exportedDocUrl.value
    ta.style.cssText = 'position:fixed;opacity:0'
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
    copyLinkLabel.value = '已复制'
    setTimeout(() => { copyLinkLabel.value = '复制链接' }, 2000)
  }
}

function buildReviewText(): string {
  if (!result.value) return ''
  const parts: string[] = []
  if (result.value.title) parts.push(result.value.title)
  if (result.value.review) parts.push(result.value.review)
  if (result.value.tags?.length) parts.push(result.value.tags.join(' '))
  return parts.join('\n\n')
}

async function copyReview(): Promise<void> {
  const text = buildReviewText()
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    copyLabel.value = '已复制'
    setTimeout(() => { copyLabel.value = '复制文案' }, 2000)
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.cssText = 'position:fixed;opacity:0'
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
    copyLabel.value = '已复制'
    setTimeout(() => { copyLabel.value = '复制文案' }, 2000)
  }
}

// 任务书 #91 I1：openFilePicker/handleFileInput/handleDrop 已随 AnalysisControlCard 迁出。
function handleReset(): void {
  controlCardRef.value?.resetLocalUploadState()
  showGenerationSteps.value = false
  reset()
  // composable 的 reset 会把平台翻回默认淘宝；锁定流必须翻回大众点评
  if (platformLocked.value) platform.value = 'dianping'
}

function cancelAnalysis(): void {
  showGenerationSteps.value = false
  cancel()
}

function formatDuration(durationMs: number): string {
  return `${(durationMs / 1000).toFixed(1)}s`
}

function getEventDurationLabel(event: ImageAnalysisProgressEvent): string {
  if (typeof event.durationMs === 'number') {
    return `耗时 ${formatDuration(event.durationMs)}`
  }

  if (!loading.value || !event.startedAt || event.completedAt) {
    return ''
  }

  const startedAtMs = Date.parse(event.startedAt)
  if (Number.isNaN(startedAtMs)) {
    return ''
  }

  return `进行中 · ${formatDuration(Math.max(0, now.value - startedAtMs))}`
}

const hasGenerationSteps = computed(() => progressEvents.value.length > 0)
const generationStepToggleLabel = computed(() => showGenerationSteps.value ? '收起生成步骤' : '查看生成步骤')

const isStepReview = computed(() => ['draft-review', 'optimize-review'].includes(generationStage.value))
const showStepLoading = computed(() => ['optimizing', 'style-refining'].includes(generationStage.value))
const stepLabel = computed(() => generationStage.value === 'draft-review' ? '初稿结果' : '润色结果')
const stepDescription = computed(() => generationStage.value === 'draft-review' ? '初稿已生成，可编辑后继续' : '润色完成，可编辑后继续')
const loadingLabel = computed(() => generationStage.value === 'optimizing' ? '正在润色优化…' : '正在风格偏好优化…')
const loadingDescription = computed(() => generationStage.value === 'optimizing' ? '正在润色评价文案，请稍候…' : '正在根据风格偏好优化，请稍候…')

function toggleGenerationSteps(): void {
  showGenerationSteps.value = !showGenerationSteps.value
}

function startNowTicker(): void {
  if (nowTimer !== null) {
    window.clearInterval(nowTimer)
  }

  nowTimer = window.setInterval(() => {
    now.value = Date.now()
  }, 200)
}

function stopNowTicker(): void {
  if (nowTimer !== null) {
    window.clearInterval(nowTimer)
    nowTimer = null
  }
}

onMounted(() => {
  startNowTicker()
})

watch(previewIndex, async (val) => {
  if (val !== null) {
    await nextTick()
    ;(document.querySelector('.preview-overlay') as HTMLElement | null)?.focus()
  }
})

onBeforeUnmount(() => {
  stopNowTicker()
})

function getStageLabel(stage: ImageAnalysisProgressStage): string {
  if (stage === 'prepare') return '准备中'
  if (stage === 'draft') return '初稿生成'
  if (stage === 'optimize') return '润色优化'
  if (stage === 'style-refine') return '风格偏好优化'
  return '已完成'
}
</script>

<style scoped>
/* 任务书 #91 I1/I2：控制卡/步审卡/结果卡/编辑表单/步骤列表样式已随组件迁出。 */
.image-analysis {
  display: grid;
  gap: var(--space-lg);
}

.page-back {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.btn-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 34px;
  padding: 0 var(--space-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-secondary);
  font: inherit;
  font-size: 0.84rem;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.btn-back:hover {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
}

.page-back-context {
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

.image-shell {
  display: grid;
  grid-template-columns: minmax(320px, 420px) minmax(0, 1fr);
  gap: var(--space-lg);
  align-items: start;
}

.control-card,
.preview-column,
.result-card,
.empty-card,
.status-card,
.progress-card {
  display: grid;
  gap: var(--space-md);
}

.control-card {
  position: sticky;
  top: var(--space-md);
}

.section-head {
  gap: var(--space-xs);
}

.section-kicker,
.result-label,
.selected-images-title,
.field-block-title {
  margin: 0;
  font-size: 0.75rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-text-muted);
  font-weight: 600;
}

.section-title,
.result-title,
.empty-title,
.status-title {
  margin: 0;
  color: var(--color-text);
}

.section-title {
  font-size: 1.14rem;
  line-height: 1.25;
}

.section-note,
.drop-zone-text,
.selected-images-count,
.field-block-copy,
.empty-copy,
.status-copy {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 0.86rem;
  line-height: 1.55;
}

.spin-icon {
  animation: spin 1s linear infinite;
}

.preview-column {
  min-width: 0;
}

.result-head {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
}

.result-card {
  align-content: start;
}

.result-title {
  font-size: 1.08rem;
}

.empty-card,
.status-card,
.progress-card {
  min-height: 320px;
  align-content: start;
}

.empty-copy,
.status-copy {
  max-width: 46ch;
}

.status-card-error {
  border-color: color-mix(in srgb, var(--color-danger) 28%, transparent);
  background: color-mix(in srgb, var(--color-danger) 8%, transparent);
}

.progress-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 48px;
  min-height: 32px;
  padding: 0 10px;
  border-radius: var(--radius-pill);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  color: var(--color-text-secondary);
  font-size: 0.8rem;
  font-weight: 600;
}

.step-loading-copy {
  display: flex;
  align-items: center;
  gap: 10px;
}

@media (max-width: 980px) {
  .image-shell {
    grid-template-columns: 1fr;
  }

  .control-card {
    position: static;
  }
}

@media (max-width: 720px) {
  .drop-zone,
  .result-head {
    grid-template-columns: 1fr;
  }

  .btn-primary,
  .btn-secondary,
  .btn-copy,
  .btn-export-feishu {
    width: 100%;
  }
}
</style>
