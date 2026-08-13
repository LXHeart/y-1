<template>
  <div class="image-analysis">
    <section class="image-shell">
      <article class="control-card glass-card">
        <header class="section-head">
          <div>
            <p class="section-kicker">图片评价</p>
            <h2 class="section-title">上传图片后生成探店评价与消费体验文案</h2>
          </div>
          <p class="section-note">支持 JPG、PNG、WebP，最多 6 张，每张不超过 5 MB。</p>
        </header>

        <label
          class="drop-zone"
          :class="{ 'drop-zone-active': isDragging }"
          for="image-analysis-input"
          tabindex="0"
          @dragenter.prevent="isDragging = true"
          @dragover.prevent
          @dragleave.prevent="isDragging = false"
          @drop.prevent="handleDrop"
          @keydown.enter.prevent="openFilePicker"
          @keydown.space.prevent="openFilePicker"
        >
          <div class="drop-zone-icon" aria-hidden="true">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
              <path d="M12 5v14M5 12l7-7 7 7" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
          <div class="drop-zone-copy">
            <p class="drop-zone-title">点击上传或拖入图片</p>
            <p class="drop-zone-text">建议先放主图，再补充细节图，生成结果会更完整。</p>
          </div>
          <input
            id="image-analysis-input"
            ref="fileInput"
            type="file"
            accept="image/jpeg,image/png,image/webp"
            multiple
            class="sr-only"
            @change="handleFileInput"
          >
        </label>

        <p v-if="uploadError" class="error-text">{{ uploadError }}</p>

        <div v-if="images.length" class="selected-images">
          <div class="selected-images-head">
            <p class="selected-images-title">已选图片</p>
            <p class="selected-images-count">{{ images.length }}/6</p>
          </div>
          <ul class="thumb-list">
            <li v-for="(img, i) in images" :key="i" class="thumb-item" @click="previewImage(i)">
              <img :src="img.preview" :alt="`图片 ${i + 1}`" class="thumb-img" />
              <button class="thumb-remove" type="button" @click.stop="removeImage(i)" aria-label="删除图片">&times;</button>
            </li>
          </ul>
        </div>

        <div class="field-block">
          <div class="field-block-head">
            <p class="field-block-title">生成偏好</p>
            <p class="field-block-copy">先选目标平台，再决定大致字数。填 0 则不限制字数。</p>
          </div>

          <div class="settings-row">
            <div class="platform-toggle" role="tablist" aria-label="评价平台">
              <button
                type="button"
                class="platform-btn"
                :class="{ 'platform-btn-active': platform === 'taobao' }"
                :disabled="loading"
                @click="platform = 'taobao'"
              >淘宝</button>
              <button
                type="button"
                class="platform-btn"
                :class="{ 'platform-btn-active': platform === 'dianping' }"
                :disabled="loading"
                @click="platform = 'dianping'"
              >大众点评</button>
            </div>

            <label class="field-group-inline">
              <span class="field-label">目标字数</span>
              <input
                v-model.number="reviewLength"
                class="field-input-sm"
                type="number"
                min="0"
                max="300"
                step="1"
                inputmode="numeric"
                :disabled="loading"
              >
            </label>
          </div>

          <p v-if="platform === 'dianping'" class="platform-position-hint">
            大众点评探店定位：围绕探店评价、消费体验与推荐理由展开，覆盖门店环境、菜品与服务细节，可补充评分建议、到店提示与真实体验描述。
          </p>
          <p v-else class="platform-position-hint">
            淘宝评价定位：围绕商品体验与真实感受展开，突出包装、质量与使用细节。
          </p>
        </div>

        <div class="field-block">
          <div class="field-block-head">
            <p class="field-block-title">补充感受</p>
            <p class="field-block-copy">可补充你想强调的细节，比如包装、分量、口感、服务体验。</p>
          </div>

          <textarea
            v-model="feelings"
            class="field-textarea"
            rows="3"
            maxlength="200"
            placeholder="例如：包装挺干净、分量看着很足、实物比图片还精致…"
            :disabled="loading"
          ></textarea>
        </div>

        <div class="action-row">
          <button
            class="btn-primary"
            :disabled="loading || images.length === 0"
            @click="startGeneration"
          >
            {{ loading ? '生成中…' : '生成评价' }}
          </button>
          <button class="btn-secondary" :disabled="loading" @click="handleReset">
            清空
          </button>
          <button v-if="generationStage === 'drafting'" class="btn-secondary" @click="cancelAnalysis">
            取消
          </button>
        </div>
      </article>

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

        <section v-if="generationStage === 'drafting'" class="progress-card glass-card fade-in">
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

          <ol class="progress-list">
            <li
              v-for="(item, index) in progressEvents"
              :key="`${item.stage}-${item.attempt ?? index}-${index}`"
              class="progress-item"
            >
              <span class="progress-dot" aria-hidden="true"></span>
              <div class="progress-copy">
                <div class="progress-line">
                  <p class="progress-title">{{ getStageLabel(item.stage) }}</p>
                  <span v-if="getEventDurationLabel(item)" class="progress-duration">{{ getEventDurationLabel(item) }}</span>
                </div>
                <p class="progress-text">{{ item.message }}</p>
              </div>
            </li>
          </ol>
        </section>

        <section v-else-if="showStepLoading" class="progress-card glass-card fade-in">
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

        <section v-else-if="error && !result" class="status-card status-card-error glass-card fade-in">
          <p class="status-title">生成失败</p>
          <p class="status-copy">{{ error }}</p>
        </section>

        <section v-else-if="result && isStepReview" class="result-card glass-card fade-in">
          <header class="result-head">
            <div>
              <p class="section-kicker">{{ stepLabel }}</p>
              <h3 class="result-title">{{ stepDescription }}</h3>
            </div>
            <div v-if="!isEditing" class="result-actions">
              <button class="btn-secondary" type="button" @click="startEditing">编辑</button>
            </div>
          </header>

          <p v-if="error" class="error-text">{{ error }}</p>

          <div v-if="saveStyleSuccess && !isEditing" class="save-style-success">
            <p>风格偏好已保存，下次生成评价时会自动应用你的个人风格。</p>
          </div>

          <div v-if="isEditing" class="result-block" :class="{ 'edit-saving': savingStyle }">
            <div v-if="result.title !== undefined" class="edit-field">
              <label class="result-label" for="edit-title-step">标题</label>
              <input id="edit-title-step" v-model="editTitle" class="field-input-sm edit-input-full" placeholder="输入标题" :disabled="savingStyle">
            </div>
            <div class="edit-field">
              <label class="result-label" for="edit-review-step">评价内容</label>
              <textarea id="edit-review-step" v-model="editReview" class="field-textarea" rows="6" :disabled="savingStyle"></textarea>
            </div>
            <div v-if="editTags.length > 0 || result.tags" class="edit-field">
              <label class="result-label">标签</label>
              <div class="edit-tags">
                <span v-for="(tag, i) in editTags" :key="i" class="edit-tag-item">
                  {{ tag }}
                  <button class="edit-tag-remove" type="button" :disabled="savingStyle" @click="removeEditTag(i)">&times;</button>
                </span>
                <input
                  v-model="newTagInput"
                  class="field-input-sm edit-tag-input"
                  placeholder="添加标签"
                  :disabled="savingStyle"
                  @keydown.enter.prevent="addEditTag"
                >
              </div>
            </div>
            <div class="edit-actions">
              <button class="btn-primary" :disabled="savingStyle" @click="handleApplyEditsLocally">直接保存</button>
              <button class="btn-save-style" :disabled="savingStyle" @click="handleSaveStyleMemory">
                <svg v-if="savingStyle" class="spin-icon" width="14" height="14" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="2" stroke-dasharray="28" stroke-dashoffset="10" stroke-linecap="round"/></svg>
                {{ savingStyle ? '保存中…' : '记忆风格并保存' }}
              </button>
              <button class="btn-secondary" :disabled="savingStyle" @click="cancelEditing">取消</button>
            </div>
            <p v-if="saveStyleError" class="error-text">{{ saveStyleError }}</p>
          </div>

          <div v-else class="result-block">
            <h4 v-if="result.title" class="result-label">标题</h4>
            <p v-if="result.title" class="result-text result-emphasis">{{ result.title }}</p>
            <h4 class="result-label">评价内容</h4>
            <p class="result-text">{{ result.review }}</p>
          </div>

          <div v-if="!isEditing" class="step-actions">
            <div v-if="generationStage === 'draft-review'" class="step-nav">
              <button class="btn-primary" @click="proceedToOptimize">下一步：润色优化</button>
            </div>
            <div v-else-if="generationStage === 'optimize-review'" class="step-nav">
              <button class="btn-primary" @click="proceedToStyleRefine">下一步：风格偏好优化</button>
            </div>
          </div>

          <section v-if="!isEditing && hasGenerationSteps" class="result-steps">
            <div class="result-steps-head">
              <p class="result-label">生成步骤</p>
            </div>
            <ol class="progress-list">
              <li
                v-for="(item, index) in progressEvents"
                :key="`step-${item.stage}-${item.attempt ?? index}-${index}`"
                class="progress-item"
                :class="{ 'progress-item-clickable': !!stepResults[item.stage] }"
                @click="stepResults[item.stage] && selectStepResult(item.stage)"
              >
                <span class="progress-dot" aria-hidden="true"></span>
                <div class="progress-copy">
                  <div class="progress-line">
                    <p class="progress-title">{{ getStageLabel(item.stage) }}</p>
                    <span v-if="getEventDurationLabel(item)" class="progress-duration">{{ getEventDurationLabel(item) }}</span>
                  </div>
                  <p class="progress-text">{{ item.message }}</p>
                </div>
              </li>
            </ol>
          </section>

          <div v-if="!isEditing && result.tags && result.tags.length" class="result-tags-wrap">
            <h4 class="result-label">标签</h4>
            <div class="result-tags">
              <span v-for="tag in result.tags" :key="tag" class="result-tag">{{ tag }}</span>
            </div>
          </div>
        </section>

        <section v-else-if="result" class="result-card glass-card fade-in">
          <header class="result-head">
            <div>
              <p class="section-kicker">输出结果</p>
              <h3 class="result-title">已生成可直接复制的评价文案</h3>
            </div>
            <div class="result-actions">
              <button v-if="!isEditing" class="btn-copy" @click="copyReview">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                {{ copyLabel }}
              </button>
              <button v-if="!isEditing && hasGenerationSteps" class="btn-secondary" type="button" @click="toggleGenerationSteps">
                {{ generationStepToggleLabel }}
              </button>
              <button v-if="!isEditing" class="btn-secondary" type="button" @click="startEditing">编辑</button>
              <button class="btn-export-feishu" :disabled="exporting || isEditing" @click="handleExportToFeishu">
                <svg v-if="exporting" class="spin-icon" width="14" height="14" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="2" stroke-dasharray="28" stroke-dashoffset="10" stroke-linecap="round"/></svg>
                {{ exporting ? '导出中…' : '导出到飞书' }}
              </button>
              <button v-if="!isEditing" class="btn-secondary" type="button" :disabled="loadingPreferences" @click="toggleStylePreferences">
                {{ showStylePreferences ? '收起风格偏好' : '查看风格偏好' }}
              </button>
              <button v-if="!isEditing" class="btn-secondary" type="button" :disabled="loading" @click="saveVersionSnapshot()">
                保存为对比版本
              </button>
            </div>
          </header>

          <div v-if="exportError" class="export-error">
            <p>{{ exportError }}</p>
          </div>

          <div v-if="exportedDocUrl" class="export-success">
            <p>已导出到飞书文档：<a :href="exportedDocUrl" target="_blank" rel="noopener">{{ exportedDocTitle }}</a></p>
            <button class="btn-copy-link" @click="copyDocLink">{{ copyLinkLabel }}</button>
          </div>

          <!-- Edit mode -->
          <div v-if="isEditing" class="result-block" :class="{ 'edit-saving': savingStyle }">
            <div v-if="result.title !== undefined" class="edit-field">
              <label class="result-label" for="edit-title">标题</label>
              <input id="edit-title" v-model="editTitle" class="field-input-sm edit-input-full" placeholder="输入标题" :disabled="savingStyle">
            </div>
            <div class="edit-field">
              <label class="result-label" for="edit-review">评价内容</label>
              <textarea id="edit-review" v-model="editReview" class="field-textarea" rows="6" :disabled="savingStyle"></textarea>
            </div>
            <div v-if="editTags.length > 0 || result.tags" class="edit-field">
              <label class="result-label">标签</label>
              <div class="edit-tags">
                <span v-for="(tag, i) in editTags" :key="i" class="edit-tag-item">
                  {{ tag }}
                  <button class="edit-tag-remove" type="button" :disabled="savingStyle" @click="removeEditTag(i)">&times;</button>
                </span>
                <input
                  v-model="newTagInput"
                  class="field-input-sm edit-tag-input"
                  placeholder="添加标签"
                  :disabled="savingStyle"
                  @keydown.enter.prevent="addEditTag"
                >
              </div>
            </div>
            <div class="edit-actions">
              <button class="btn-primary" :disabled="savingStyle" @click="handleApplyEditsLocally">直接保存</button>
              <button class="btn-save-style" :disabled="savingStyle" @click="handleSaveStyleMemory">
                <svg v-if="savingStyle" class="spin-icon" width="14" height="14" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="2" stroke-dasharray="28" stroke-dashoffset="10" stroke-linecap="round"/></svg>
                {{ savingStyle ? '保存中…' : '记忆风格并保存' }}
              </button>
              <button class="btn-secondary" :disabled="savingStyle" @click="cancelEditing">取消</button>
            </div>
            <p v-if="saveStyleError" class="error-text">{{ saveStyleError }}</p>
          </div>

          <!-- Display mode -->
          <div v-else class="result-block">
            <h4 v-if="result.title" class="result-label">标题</h4>
            <p v-if="result.title" class="result-text result-emphasis">{{ result.title }}</p>

            <h4 class="result-label">评价内容</h4>
            <p class="result-text">{{ result.review }}</p>
          </div>

          <section v-if="showGenerationSteps && hasGenerationSteps" class="result-steps">
            <div class="result-steps-head">
              <p class="result-label">本次生成步骤</p>
              <p v-if="result.runId" class="result-steps-run-id">运行 ID：{{ result.runId }}</p>
            </div>
            <ol class="progress-list">
              <li
                v-for="(item, index) in progressEvents"
                :key="`result-${item.stage}-${item.attempt ?? index}-${index}`"
                class="progress-item"
                :class="{ 'progress-item-clickable': !!stepResults[item.stage] }"
                @click="stepResults[item.stage] && selectStepResult(item.stage)"
              >
                <span class="progress-dot" aria-hidden="true"></span>
                <div class="progress-copy">
                  <div class="progress-line">
                    <p class="progress-title">{{ getStageLabel(item.stage) }}</p>
                    <span v-if="getEventDurationLabel(item)" class="progress-duration">{{ getEventDurationLabel(item) }}</span>
                  </div>
                  <p class="progress-text">{{ item.message }}</p>
                </div>
              </li>
            </ol>
          </section>

          <div v-if="saveStyleSuccess && !isEditing" class="save-style-success">
            <p>风格偏好已保存，下次生成评价时会自动应用你的个人风格。</p>
          </div>

          <div v-if="!isEditing && result.tags && result.tags.length" class="result-tags-wrap">
            <h4 class="result-label">标签</h4>
            <div class="result-tags">
              <span v-for="tag in result.tags" :key="tag" class="result-tag">{{ tag }}</span>
            </div>
          </div>
        </section>

        <section v-else class="empty-card glass-card">
          <p class="section-kicker">等待生成</p>
          <h2 class="empty-title">上传图片后，这里会显示评价结果</h2>
          <p class="empty-copy">生成完成后可直接复制标题、正文和标签，用于淘宝或大众点评发布。</p>
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
import type { GenerationStage, ImageAnalysisProgressEvent, ImageAnalysisProgressStage, ImageAnalysisResult } from '../../types/image-analysis'
import ImageLightbox from './components/ImageLightbox.vue'
import OversizedImageDialog from './components/OversizedImageDialog.vue'
import SessionVersionsCard from './components/SessionVersionsCard.vue'
import StepResultOverlay from './components/StepResultOverlay.vue'
import StylePreferencesModal from './components/StylePreferencesModal.vue'

const props = defineProps<{
  creationHandoff?: CreationHandoff | null
}>()

const {
  images,
  result,
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
  hasStylePreferences,
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
  completeWithoutStyleRefine,
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

watch(() => props.creationHandoff, (handoff) => {
  if (!handoff || handoff.targetView !== 'image' || hydratedCreationRevision.value === handoff.revision) return
  hydratedCreationRevision.value = handoff.revision
  reset()
  bindCreationContext(handoff.source.type === 'task', handoff.contextSnapshotId)
  platform.value = 'dianping'
  feelings.value = [handoff.prefill?.topic, handoff.prefill?.instructions].filter(Boolean).join('\n')
}, { immediate: true })

const isDragging = ref(false)
const uploadError = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const copyLabel = ref('复制文案')
const copyLinkLabel = ref('复制链接')
const showGenerationSteps = ref(false)
const newTagInput = ref('')
const now = ref(Date.now())
const previewIndex = ref<number | null>(null)
let nowTimer: number | null = null

// --- 本次会话多版本对比（纯前端内存态，不引入持久化） ---

interface SessionVersion {
  id: string
  label: string
  platformLabel: string
  savedAt: string
  data: ImageAnalysisResult
}

const sessionVersions = ref<SessionVersion[]>([])
const selectedVersionId = ref<string | null>(null)
let versionCounter = 0

function versionLabelForStage(stage: GenerationStage): string {
  if (stage === 'draft-review') return '初稿'
  if (stage === 'complete') {
    if (stepResults.value['style-refine']) return '风格优化版'
    if (stepResults.value.optimize) return '润色版'
    return '终版'
  }
  return '草稿'
}

function saveVersionSnapshot(label?: string): void {
  if (!result.value) return
  versionCounter += 1
  const snapshot: SessionVersion = {
    id: `session-version-${versionCounter}`,
    label: label ?? versionLabelForStage(generationStage.value),
    platformLabel: platform.value === 'dianping' ? '大众点评' : '淘宝',
    savedAt: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
    data: {
      ...result.value,
      tags: result.value.tags ? [...result.value.tags] : undefined,
    },
  }
  sessionVersions.value = [...sessionVersions.value, snapshot]
  selectedVersionId.value = snapshot.id
}

function selectVersion(id: string): void {
  selectedVersionId.value = selectedVersionId.value === id ? null : id
}

function removeVersion(id: string): void {
  sessionVersions.value = sessionVersions.value.filter((v) => v.id !== id)
  if (selectedVersionId.value === id) selectedVersionId.value = null
}

watch(generationStage, (stage) => {
  if ((stage === 'draft-review' || stage === 'complete') && result.value) {
    saveVersionSnapshot()
  }
})

function previewImage(index: number): void {
  previewIndex.value = index
}

function closePreview(): void {
  previewIndex.value = null
}

async function handleExportToFeishu(): Promise<void> {
  await exportToFeishu()
}

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

function openFilePicker(): void {
  fileInput.value?.click()
}

function handleFileInput(event: Event): void {
  const input = event.target as HTMLInputElement
  if (!input.files?.length) return
  uploadError.value = addFiles(Array.from(input.files)) ?? ''
  input.value = ''
}

function handleDrop(event: DragEvent): void {
  isDragging.value = false
  const files = event.dataTransfer?.files
  if (!files?.length) return
  uploadError.value = addFiles(Array.from(files)) ?? ''
}

function handleReset(): void {
  isDragging.value = false
  uploadError.value = ''
  showGenerationSteps.value = false
  sessionVersions.value = []
  selectedVersionId.value = null
  reset()
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
.image-analysis {
  display: grid;
  gap: var(--space-lg);
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
  gap: 16px;
}

.control-card {
  position: sticky;
  top: var(--space-md);
}

.section-head,
.field-block,
.field-block-head,
.result-head,
.result-block,
.result-tags-wrap,
.result-steps,
.result-steps-head {
  display: grid;
  gap: 10px;
}

.section-head {
  gap: 8px;
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

.drop-zone {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 14px;
  min-height: 112px;
  padding: 18px;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--surface-page);
  cursor: pointer;
  transition: border-color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out);
}

.drop-zone:hover,
.drop-zone-active {
  border-color: var(--color-border-accent);
  background: var(--surface-card);
  box-shadow: var(--focus-ring);
}

.drop-zone-icon {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  border-radius: 14px;
  border: 1px solid var(--color-border);
  background: var(--surface-card);
  color: var(--color-text-secondary);
}

.drop-zone-copy {
  display: grid;
  gap: 4px;
}

.drop-zone-title {
  margin: 0;
  color: var(--color-text);
  font-size: 0.96rem;
  font-weight: 600;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  border: 0;
}

.selected-images {
  display: grid;
  gap: 10px;
  padding: 14px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.selected-images-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.thumb-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.thumb-item {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: 14px;
  overflow: hidden;
  border: 1px solid var(--color-border);
  background: var(--surface-card);
}

.thumb-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumb-remove {
  position: absolute;
  top: 6px;
  right: 6px;
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(11, 14, 20, 0.72);
  color: white;
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
}

.settings-row {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.platform-toggle {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.platform-btn {
  min-height: 36px;
  padding: 0 14px;
  border: none;
  border-radius: calc(var(--radius-md) - 4px);
  background: transparent;
  color: var(--color-text-secondary);
  font: inherit;
  font-size: 0.84rem;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.platform-btn-active {
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text);
}

.platform-btn:not(.platform-btn-active):hover {
  background: var(--color-surface-hover);
}

.platform-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.field-group-inline {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.field-label {
  font-size: 0.82rem;
  font-weight: 600;
  color: var(--color-text-secondary);
}

.field-input-sm,
.field-textarea {
  border: 1px solid var(--color-border);
  background: var(--surface-muted);
  color: var(--color-text);
  font: inherit;
  transition: border-color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out);
}

.field-input-sm {
  width: 86px;
  min-height: 38px;
  padding: 0 10px;
  border-radius: var(--radius-md);
}

.field-textarea {
  width: 100%;
  min-height: 88px;
  padding: 12px 14px;
  resize: vertical;
  line-height: 1.6;
  border-radius: var(--radius-lg);
}

.field-input-sm:focus,
.field-textarea:focus {
  outline: none;
  border-color: var(--color-border-accent);
  background: var(--surface-card);
  box-shadow: var(--focus-ring);
}

.action-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.btn-primary,
.btn-secondary,
.btn-copy {
  min-height: 40px;
  padding: 0 16px;
  border-radius: var(--radius-md);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  cursor: pointer;
  font-size: 0.84rem;
  font-weight: 600;
  transition: transform var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), opacity var(--duration-fast) var(--ease-out);
}

.btn-primary {
  background: var(--color-accent);
  color: white;
  border: none;
}

.btn-primary:hover:not(:disabled) {
  background: var(--color-accent-2);
  transform: translateY(-1px);
}

.btn-secondary,
.btn-copy {
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text-secondary);
}

.btn-secondary:hover:not(:disabled),
.btn-copy:hover {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
}

.result-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.btn-export-feishu {
  min-height: 40px;
  padding: 0 16px;
  border-radius: var(--radius-md);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  cursor: pointer;
  font-size: 0.84rem;
  font-weight: 600;
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text-secondary);
  transition: transform var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out);
}

.btn-export-feishu:hover:not(:disabled) {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
  transform: translateY(-1px);
}

.btn-export-feishu:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
}

.btn-copy-link {
  padding: 4px 12px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-card);
  color: var(--color-text-secondary);
  font-size: 0.78rem;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out);
}

.btn-copy-link:hover {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
}

.export-error {
  padding: 12px 16px;
  border-radius: var(--radius-lg);
  border: 1px solid rgba(239, 107, 107, 0.28);
  background: rgba(239, 107, 107, 0.08);
}

.export-error p {
  margin: 0;
  color: var(--color-danger);
  font-size: 0.85rem;
}

.export-success {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  border-radius: var(--radius-lg);
  border: 1px solid rgba(72, 187, 120, 0.28);
  background: rgba(72, 187, 120, 0.08);
  flex-wrap: wrap;
}

.export-success p {
  margin: 0;
  color: var(--color-text);
  font-size: 0.85rem;
}

.export-success a {
  color: var(--color-accent);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.export-success a:hover {
  color: var(--color-accent-2);
}

.spin-icon {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.btn-primary:disabled,
.btn-secondary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
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

.result-block {
  padding: 16px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.result-text {
  margin: 0;
  color: var(--color-text);
  line-height: 1.75;
  white-space: pre-wrap;
}

.result-emphasis {
  font-weight: 600;
}

.result-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.result-tag {
  display: inline-flex;
  align-items: center;
  padding: 5px 10px;
  border-radius: 999px;
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  color: var(--color-text-secondary);
  font-size: 0.8rem;
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
  border-color: rgba(239, 107, 107, 0.28);
  background: rgba(239, 107, 107, 0.08);
}

.progress-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 48px;
  min-height: 32px;
  padding: 0 10px;
  border-radius: 999px;
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  color: var(--color-text-secondary);
  font-size: 0.8rem;
  font-weight: 600;
}

.progress-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 10px;
}

.progress-item {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 10px;
  align-items: start;
  padding: 12px 14px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.progress-dot {
  width: 9px;
  height: 9px;
  margin-top: 6px;
  border-radius: 999px;
  background: var(--color-accent);
  box-shadow: 0 0 0 6px rgba(114, 132, 248, 0.12);
}

.progress-copy {
  display: grid;
  gap: 4px;
}

.progress-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.progress-title,
.progress-text {
  margin: 0;
}

.progress-duration,
.result-steps-run-id {
  color: var(--color-text-muted);
  font-size: 0.78rem;
  line-height: 1.4;
}

.progress-title {
  color: var(--color-text);
  font-size: 0.9rem;
  font-weight: 600;
}

.progress-text {
  color: var(--color-text-secondary);
  font-size: 0.84rem;
  line-height: 1.55;
}

.error-text {
  margin: 0;
  color: var(--color-danger);
  font-size: 0.85rem;
}

.edit-field {
  display: grid;
  gap: 6px;
}

.edit-input-full {
  width: 100%;
}

.edit-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.edit-tag-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 10px;
  border-radius: 999px;
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  color: var(--color-text-secondary);
  font-size: 0.8rem;
}

.edit-tag-remove {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: var(--color-text-muted);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  padding: 0;
}

.edit-tag-remove:hover {
  color: var(--color-danger);
}

.edit-tag-input {
  width: 100px;
  min-height: 32px;
  font-size: 0.8rem;
}

.edit-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
}

.btn-save-style {
  min-height: 40px;
  padding: 0 16px;
  border-radius: var(--radius-md);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  cursor: pointer;
  font-size: 0.84rem;
  font-weight: 600;
  background: var(--color-accent);
  color: white;
  border: none;
  transition: transform var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out);
}

.btn-save-style:hover:not(:disabled) {
  background: var(--color-accent-2);
  transform: translateY(-1px);
}

.btn-save-style:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
}

.save-style-success {
  display: flex;
  padding: 12px 16px;
  border-radius: var(--radius-lg);
  border: 1px solid rgba(72, 187, 120, 0.28);
  background: rgba(72, 187, 120, 0.08);
}

.save-style-success p {
  margin: 0;
  color: var(--color-text);
  font-size: 0.85rem;
}

.edit-saving {
  position: relative;
  pointer-events: none;
  opacity: 0.7;
}

.edit-saving .edit-actions {
  pointer-events: auto;
  opacity: 1;
}

.step-loading-copy {
  display: flex;
  align-items: center;
  gap: 10px;
}

.step-actions {
  display: grid;
  gap: 12px;
}

.step-nav {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.progress-item-clickable {
  cursor: pointer;
  transition: border-color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out);
}

.progress-item-clickable:hover {
  border-color: var(--color-border-accent);
  background: var(--surface-card);
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

.thumb-item {
  cursor: pointer;
}

.thumb-item:hover .thumb-img {
  opacity: 0.85;
  transition: opacity var(--duration-fast) var(--ease-out);
}

.platform-position-hint {
  margin: 0;
  padding: 10px 14px;
  border-radius: var(--radius-md);
  border: 1px solid rgba(114, 132, 248, 0.24);
  background: rgba(114, 132, 248, 0.07);
  color: var(--color-text-secondary);
  font-size: 0.84rem;
  line-height: 1.6;
}

</style>
