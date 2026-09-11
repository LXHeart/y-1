<template>
  <div class="video-production gl-field">
    <WorkspaceSaveBadge :state="autosave.saveState.value" :conflict="autosave.conflictNotice.value"
      :readonly="autosave.readonly.value" @retry="autosave.retry" @reload="autosave.reloadRemote" />
    <p v-if="taskError && !task" class="error-hint" role="alert">
      {{ taskError }} <button type="button" class="btn-secondary" @click="autosave.retryReferences">重新载入素材</button>
    </p>
    <nav class="steps-bar" aria-label="制作步骤">
      <div
        v-for="(s, i) in steps"
        :key="s.key"
        class="step-dot"
        :class="{
          'step-active': stage === s.key,
          'step-done': stepIndex(stage) > i,
        }"
      >
        <span class="step-num">{{ i + 1 }}</span>
        <span class="step-label">{{ s.label }}</span>
      </div>
    </nav>

    <!-- Step 1: Upload -->
    <!-- Step 1: Upload（任务书 #91 V1：面板化 components/UploadStage.vue；D-06：images/form 留视图经 props 下传） -->
    <!-- AI改造-01 §1.3 尾项：视频工作流内可编辑统一创作简报（Brief 随分镜请求透传） -->
    <CreationBriefEditor
      v-if="stage === 'upload'"
      :model-value="form.brief ?? null"
      :disabled="autosave.readonly.value"
      @update:model-value="form.brief = $event ?? undefined"
    />
    <UploadStage
      v-if="stage === 'upload'"
      :images="images" :go-to-creation-center="goToCreationCenter"
      :add-images="addImages" :remove-image="removeImage" :reorder-image="reorderImage" :open-lightbox="openLightbox"
      :input-mode="form.inputMode ?? 'store-photos'"
      :script="form.script ?? ''"
      :own-media-refs="form.ownMediaRefs ?? []"
      @update:input-mode="form.inputMode = $event"
      @update:script="form.script = $event"
      @update:own-media-refs="form.ownMediaRefs = $event"
      v-model:shop-name="form.shopName"
      v-model:industry-type="form.industryType"
      v-model:target-platform="form.targetPlatform"
      v-model:shop-address="form.shopAddress"
      v-model:video-style="form.videoStyle"
      v-model:shop-description="form.shopDescription"
      v-model:custom-prompt="form.customPrompt"
      :target-duration-seconds="form.targetDurationSeconds" :handle-duration-input="handleDurationInput"
      :estimated-price-cents="estimatedPriceCents" :is-landscape="isLandscape" :vertical-duration-hint="verticalDurationHint"
      :error="error" :can-proceed-to-storyboard="canProceedToStoryboard" :storyboard-loading="storyboardLoading"
      :generate-storyboard="generateStoryboard" :video-platforms="videoPlatforms"
      :reference-platform="referencePlatform"
      v-model:reference-input="referenceInput"
      v-model:hot-topic-input="hotTopicInput"
      :reference-cards="referenceCards" :has-selected-reference-cards="hasSelectedReferenceCards"
      :reference-applied="referenceApplied" :reference-parse-loading="referenceParseLoading"
      :handle-switch-reference-platform="handleSwitchReferencePlatform"
      :handle-extract-reference="handleExtractReference" :handle-clear-reference="handleClearReference"
      :apply-reference-to-prompt="applyReferenceToPrompt" :apply-hot-topic-to-prompt="applyHotTopicToPrompt"
      :toggle-reference-card="toggleReferenceCard"
      :douyin-extracted-video="douyinExtractedVideo" :douyin-parse-loading="douyinParseLoading" :douyin-parse-error="douyinParseError"
      :douyin-video-analysis="douyinVideoAnalysis" :douyin-analysis-loading="douyinAnalysisLoading" :douyin-analysis-error="douyinAnalysisError"
      :handle-retry-douyin-analysis="handleRetryDouyinAnalysis"
      :bilibili-extracted-video="bilibiliExtractedVideo" :bilibili-parse-loading="bilibiliParseLoading" :bilibili-parse-error="bilibiliParseError"
      :bilibili-video-analysis="bilibiliVideoAnalysis" :bilibili-analysis-loading="bilibiliAnalysisLoading" :bilibili-analysis-error="bilibiliAnalysisError"
      :handle-retry-bilibili-analysis="handleRetryBilibiliAnalysis"
    />

    <!-- Step 2: Storyboard Editing（任务书 #64 卡4） -->
    <!-- Step 2: Storyboard Editing（任务书 #91 V2：面板化 components/StoryboardStage.vue） -->
    <StoryboardStage
      v-if="stage === 'storyboard'"
      :go-back-to-upload="goBackToUpload" :shots="shots" :images="images"
      :total-planned-seconds="totalPlannedSeconds" :target-duration-seconds="form.targetDurationSeconds"
      :reference-applied="referenceApplied" :restored-storyboard-id="restoredStoryboardId" :storyboard-id="storyboardId"
      :storyboard-loading="storyboardLoading" :can-add-shot="canAddShot"
      :anchor-generating="anchorGenerating" :anchor-errors="anchorErrors"
      :update-shot="updateShot" :remove-shot="removeShot" :add-shot="addShot"
      :generate-storyboard="generateStoryboard" :generate-anchor-image="generateAnchorImage"
      :open-lightbox="openLightbox" :error="error"
      :safety-report="safetyReport" :narration-text="narrationText"
      :draft-id="autosave.draftId.value || undefined"
      @update:safety-report="safetyReport = $event"
      :begin-generation="beginGeneration"
    />

    <!-- Step 3: 生成与挑选（任务书 #64 卡9） -->
    <section v-if="stage === 'generate'" class="stage-card gl-zone fade-in">
      <header class="card-head">
        <div class="card-head-row">
          <button class="btn-back" type="button" @click="goBackToStoryboard">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M10 3L5 8l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            返回分镜
          </button>
          <p class="eyebrow">第三步</p>
        </div>
        <h2 class="card-title">生成与挑选</h2>
        <p class="field-note">
          每镜 {{ defaultTakeCount }} 个候选，挑一个进入合成；
          <span v-if="task">任务进度 {{ task.progress }}%</span>
        </p>
      </header>

      <p v-if="isSlideshowMode" class="field-note mode-notice" data-test="slideshow-notice">
        当前未配置视频生成模型，将以图文成片模式产出（图片轮播+运镜+配音+字幕）
      </p>
      <p v-if="ttsUnavailable" class="field-note mode-notice" data-test="tts-notice">
        配音模型未配置，成片将无配音
      </p>
      <div v-if="error || taskError" class="error-hint">{{ error || taskError }}</div>
      <div v-if="taskTerminal" class="error-hint" data-test="task-terminal">
        {{ task?.phase === 'cancelled'
          ? '任务已取消，预留积分已退回'
          : `任务失败：${task?.errorMessage || task?.errorCode || '未知原因'}` }}
      </div>

      <template v-if="task && !isSlideshowMode">
        <TakePickCard
          v-for="shot in task.shots"
          :key="shot.id"
          :shot="shot"
          :task="task"
          :compose-submitting="composeSubmitting"
          @select-take="selectTake"
          @regenerate-shot="regenerateShot"
        />

        <div class="action-row">
          <button type="button" class="btn-secondary" data-test="use-recommended" @click="useRecommendedSelection">
            一键采用推荐
          </button>
          <button
            class="btn-primary gl-btn-primary"
            :disabled="!selectionComplete || composeSubmitting || taskTerminal"
            data-test="compose-button"
            @click="composeTask"
          >
            {{ composeSubmitting ? '提交合成中…' : '合成成片' }}
          </button>
          <button type="button" class="btn-secondary" :disabled="composeSubmitting || taskTerminal" data-test="cancel-task" @click="cancelTask">
            取消任务
          </button>
        </div>
      </template>

      <template v-else-if="task && isSlideshowMode">
        <div class="action-row">
          <button
            class="btn-primary gl-btn-primary"
            :disabled="composeSubmitting || taskTerminal"
            data-test="compose-button"
            @click="composeTask"
          >
            {{ composeSubmitting ? '提交合成中…' : '合成成片（图文模式）' }}
          </button>
          <button type="button" class="btn-secondary" :disabled="composeSubmitting || taskTerminal" data-test="cancel-task" @click="cancelTask">取消任务</button>
        </div>
      </template>

      <p v-else class="field-note">任务创建中…</p>
    </section>

    <!-- Step 4: 合成成片（任务书 #64 卡9） -->
    <!-- Step 4: 合成成片（任务书 #91 V2：面板化 components/ComposeStage.vue；exportArtifact 随迁） -->
    <ComposeStage
      v-if="stage === 'compose'"
      :task="task" :task-error="taskError"
      :go-back-to-storyboard="goBackToStoryboard" :handle-reset-all="handleResetAll"
      :download-subtitle="downloadSubtitle"
      :report-error="(message: string) => taskError = message"
    />
    <CreationDeclarations v-if="stage === 'storyboard' || stage === 'compose'"
      v-model="autosave.declarations.value" :disabled="autosave.readonly.value" />
    <!-- AI改造-03 §3.4：视频配文独立编辑（描述/话题/分享配文）+ readiness + 脚本/素材包导出；
         修改配文只写 delivery，不触发视频重新生成。脚本先行的 B站专题可在此导出脚本交付。 -->
    <DeliveryPanel
      v-if="stage === 'storyboard' || stage === 'compose'"
      :model-value="autosave.deliveryValue.value"
      :platform="form.targetPlatform"
      :disabled="autosave.readonly.value"
      :hide-fields="['titleOrOpening']"
      :draft-id="autosave.draftId.value || undefined"
      :export-title="form.shopName || form.customPrompt || '视频创作'"
      @update:model-value="autosave.updateDelivery"
    />

    <!-- 历史任务（任务书 #64 卡9，参考 VideoRecreationPanel 手风琴；#68 卡 E 抽取为组件） -->
    <VideoHistorySection
      v-if="stage !== 'upload'"
      :history="history"
      :history-loading="historyLoading"
      :history-error="historyError"
      @refresh="loadHistory(1)"
    />

    <Teleport to="body">
      <div v-if="lightboxSrc" class="lightbox-overlay" @click="closeLightbox">
        <img :src="lightboxSrc" class="lightbox-img" @click.stop />
        <button class="lightbox-close" @click="closeLightbox" aria-label="关闭">&times;</button>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { AI_PLATFORM_DEFINITIONS } from '../../config/ai-platform-capabilities'
import UploadStage from './components/UploadStage.vue'
import StoryboardStage from './components/StoryboardStage.vue'
import ComposeStage from './components/ComposeStage.vue'
import { useVideoProduction } from '../../composables/useVideoProduction'
import { clampTargetDuration } from '../../composables/useVideoProduction'
import type { CreationHandoff } from '../../types/ai-creation'
import { useVideoWorkspace } from './composables/useVideoWorkspace'
import WorkspaceSaveBadge from '../ai-center/creation/WorkspaceSaveBadge.vue'
import CreationBriefEditor from '../../components/CreationBriefEditor.vue'
import CreationDeclarations from '../../components/CreationDeclarations.vue'
import DeliveryPanel from '../ai-center/components/DeliveryPanel.vue'
import { useRoute } from 'vue-router'
import { useVideoReference } from './composables/useVideoReference'
import TakePickCard from './components/TakePickCard.vue'
import VideoHistorySection from './components/VideoHistorySection.vue'

const props = defineProps<{
  creationHandoff?: CreationHandoff | null
}>()

const route = useRoute()
const emit = defineEmits<{ 'open-view': [view: 'ai-center'] }>()

const production = useVideoProduction()
const {
  stage, images, form, shots, safetyReport, storyboardId,
  storyboardLoading, error, task, taskError, composeSubmitting,
  history, historyLoading, historyError,
  canProceedToStoryboard, canAddShot, totalPlannedSeconds, narrationText,
  isSlideshowMode, ttsUnavailable, selectionComplete, taskTerminal,
  isLandscape, verticalDurationHint, estimatedPriceCents,
  anchorGenerating, anchorErrors, generateAnchorImage,
  addImages, removeImage, reorderImage,
  generateStoryboard, updateShot, removeShot, addShot, referenceShotStructure,
  restoreStoryboard, restoredStoryboardId,
  goBackToUpload, beginGeneration, goBackToStoryboard,
  selectTake, useRecommendedSelection, regenerateShot, composeTask, cancelTask,
  downloadSubtitle, loadHistory,
} = production

/**
 * #69 卡C：画布「切换到快速模式」带的 ?storyboard= 挂载即恢复到分镜步（不自动生成——D3）。
 * handoff 优先：创作中心带入的会话不与 query 恢复竞争。
 */
onMounted(() => {
  const queryStoryboard = route?.query.storyboard
  const hasHandoff = !!props.creationHandoff
    && props.creationHandoff.targetView === 'video-production'
  if (hasHandoff || route?.query.draft || autosave.restoredProjectId.value || typeof queryStoryboard !== 'string' || !queryStoryboard.trim()) return
  void restoreStoryboard(queryStoryboard.trim())
})

const defaultTakeCount = 2

function goToCreationCenter(): void {
  emit('open-view', 'ai-center') // 共享视图双挂载（任务书 #76）：返回创作中心交给各壳路由
}

/** 滑杆输入钳制（#65 卡1：非 5 倍数就近取档、越界封顶 15-180）。 */
function handleDurationInput(raw: string): void {
  const parsed = Number(raw)
  if (Number.isFinite(parsed)) {
    form.value = { ...form.value, targetDurationSeconds: clampTargetDuration(parsed) }
  }
}

const {
  referencePlatform,
  referenceInput,
  hotTopicInput,
  referenceCards,
  referenceApplied,
  referenceParseLoading,
  hasSelectedReferenceCards,
  handleSwitchReferencePlatform,
  handleExtractReference,
  handleRetryDouyinAnalysis,
  handleRetryBilibiliAnalysis,
  handleClearReference,
  applyReferenceToPrompt,
  applyHotTopicToPrompt,
  toggleReferenceCard,
  clearOptionalInputState,
  douyinExtractedVideo,
  douyinParseLoading,
  douyinParseError,
  douyinVideoAnalysis,
  douyinAnalysisLoading,
  douyinAnalysisError,
  bilibiliExtractedVideo,
  bilibiliParseLoading,
  bilibiliParseError,
  bilibiliVideoAnalysis,
  bilibiliAnalysisLoading,
  bilibiliAnalysisError,
} = useVideoReference({ form, referenceShotStructure })

const autosave = useVideoWorkspace(production, route, () => props.creationHandoff, clearOptionalInputState)

// 任务书 #91 V1：上传区常量与拖放局部态（MAX_IMAGES/industryTypes/videoStyles 等）已随 UploadStage.vue 迁出。
// 朋友圈的视频形式是 video-text（PRD §4.4），同样落到视频制作。
const videoPlatforms = AI_PLATFORM_DEFINITIONS.filter((item) =>
  item.forms.some((form) => form.id === 'video' || form.id === 'video-text'))
const steps = [
  { key: 'upload' as const, label: '上传素材' },
  { key: 'storyboard' as const, label: '编辑分镜' },
  { key: 'generate' as const, label: '生成与挑选' },
  { key: 'compose' as const, label: '合成成片' },
]

const lightboxSrc = ref('')

function stepIndex(s: typeof stage.value): number {
  return steps.findIndex((step) => step.key === s)
}

function openLightbox(src: string): void {
  lightboxSrc.value = src
}

function closeLightbox(): void {
  lightboxSrc.value = ''
}

async function handleResetAll(): Promise<void> {
  await autosave.resetWorkspace()
}
</script>

<style scoped>
.video-production {
  max-width: 800px;
  margin: 0 auto;
  padding: var(--space-lg) var(--space-md);
}

.card-head-row {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  margin-bottom: var(--space-sm);
}

.btn-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 0.86rem;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.btn-back:hover {
  background: var(--surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
}

.btn-back:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.steps-bar {
  display: flex;
  justify-content: center;
  gap: var(--space-xl);
  margin-bottom: var(--space-lg);
}

.step-dot {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  opacity: 0.4;
  transition: opacity 0.3s;
}

.step-active {
  opacity: 1;
}

.step-done {
  opacity: 0.7;
}

.step-num {
  width: 28px;
  height: 28px;
  border-radius: var(--radius-pill);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  background: var(--color-surface-strong);
  border: 1px solid var(--color-border);
  transition: background 0.3s, border-color 0.3s, color 0.3s;
}

/* 激活态用淡底描边而非实心紫：与表单区的权重失衡来自步骤条过度抢眼（视觉审查 ⑮） */
.step-active .step-num {
  background: color-mix(in srgb, var(--color-accent) 16%, transparent);
  border-color: color-mix(in srgb, var(--color-accent) 45%, transparent);
  color: var(--color-accent);
}

.step-done .step-num {
  background: color-mix(in srgb, var(--color-success) 14%, transparent);
  border-color: color-mix(in srgb, var(--color-success) 35%, transparent);
  color: var(--color-success);
}

.step-label {
  font-size: 12px;
  color: var(--color-text-muted);
}

.step-active .step-label {
  color: var(--color-text);
  font-weight: 600;
}


.script-thumbnails {
  display: flex;
  gap: var(--space-xs);
  margin-bottom: var(--space-md);
  overflow-x: auto;
}

.script-thumb {
  width: 56px;
  height: 56px;
  object-fit: cover;
  border-radius: var(--radius-sm);
  cursor: pointer;
  flex-shrink: 0;
}

.stream-area {
  position: relative;
  margin-bottom: var(--space-md);
}




.stream-badge {
  position: absolute;
  top: 8px;
  right: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--color-accent);
}

.stream-dot {
  width: 6px;
  height: 6px;
  border-radius: var(--radius-pill);
  background: var(--color-accent);
  animation: pulse 1.2s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

.progress-area {
  margin-bottom: var(--space-md);
}

.progress-bar-track {
  height: 6px;
  border-radius: var(--radius-xs);
  background: var(--color-border-hover);
  overflow: hidden;
  margin-bottom: 8px;
}

.progress-bar-fill {
  height: 100%;
  border-radius: var(--radius-xs);
  background: var(--color-accent);
  transition: width 0.3s ease;
}

.result-area {
  text-align: center;
}

.result-video {
  width: 100%;
  max-width: 480px;
  border-radius: var(--radius-md);
  margin-bottom: var(--space-md);
}

.error-hint {
  color: var(--color-danger);
  font-size: 13px;
  margin-bottom: var(--space-sm);
}

.action-row {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.btn-primary,
.btn-secondary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 38px;
  padding: 0 var(--space-md);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  text-decoration: none;
}

.btn-back {
  display: flex;
  align-items: center;
  gap: 4px;
  background: none;
  border: none;
  color: var(--color-text-muted);
  font-size: 13px;
  cursor: pointer;
  padding: 0;
}

.btn-back:hover {
  color: inherit;
}

.card-head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.card-head {
  margin-bottom: var(--space-md);
}

.eyebrow {
  font-size: 12px;
  color: var(--color-accent);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 4px;
}

.card-title {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 4px;
}

.field-note {
  font-size: 13px;
  color: var(--color-text-muted);
}

.lightbox-overlay {
  position: fixed;
  inset: 0;
  background: var(--color-overlay);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  cursor: pointer;
}

.lightbox-img {
  max-width: 90vw;
  max-height: 90vh;
  border-radius: var(--radius-sm);
  cursor: default;
}

.lightbox-close {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-pill);
  background: var(--color-border-hover);
  color: var(--color-on-accent);
  border: none;
  font-size: 20px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.fade-in {
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}

/* 任务书 #64 卡4：逐镜卡片（参考 CardSeriesPanel 形态，样式走 .gl-field/.gl-tile 全局层 + 少量布局） */



.mode-notice {
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-md);
  background: color-mix(in srgb, var(--color-warning, var(--color-accent)) 14%, transparent);
  border: 1px solid color-mix(in srgb, var(--color-warning, var(--color-accent)) 35%, transparent);
}

/* 卡9：take 矩阵 / 成片结果 / 历史区（历史区样式已随 VideoHistorySection 迁出） */


</style>
