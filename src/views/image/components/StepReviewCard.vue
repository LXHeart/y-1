<script setup lang="ts">
import { computed } from 'vue'
import SafetyFindingsPanel from '../../../components/SafetyFindingsPanel.vue'
import AnalysisEditForm from './AnalysisEditForm.vue'
import GenerationStepsList from './GenerationStepsList.vue'
import type { SafetyReport } from '../../../types/content-safety'
import type { ImageAnalysisProgressEvent, ImageAnalysisProgressStage } from '../../../types/image-analysis'

/**
 * 步审卡（任务书 #91 I2 自 ImageAnalysisView.vue 模板 283–389 整段迁入，纯搬运；
 * 编辑表单与生成步骤列表改用去重组件 AnalysisEditForm/GenerationStepsList）。
 */
const props = defineProps<{
  result: { title?: string; review: string; tags?: string[] }
  stepLabel: string
  stepDescription: string
  error: string
  isEditing: boolean
  savingStyle: boolean
  saveStyleError: string
  saveStyleSuccess: boolean
  editTitle: string
  editReview: string
  editTags: string[]
  newTagInput: string
  safetyReport: SafetyReport | null
  generationStage: string
  hasGenerationSteps: boolean
  progressEvents: ReadonlyArray<ImageAnalysisProgressEvent>
  stepResults: Record<string, unknown>
  getStageLabel: (stage: ImageAnalysisProgressStage) => string
  getEventDurationLabel: (event: ImageAnalysisProgressEvent) => string
  selectStepResult: (stage: ImageAnalysisProgressStage) => void
  startEditing: () => void
  cancelEditing: () => void
  applyEditsLocally: () => void
  saveStyleMemory: () => Promise<unknown>
  removeEditTag: (index: number) => void
  proceedToOptimize: () => void
  proceedToStyleRefine: () => void
}>()

const emit = defineEmits<{
  'update:editTitle': [value: string]
  'update:editReview': [value: string]
  'update:newTagInput': [value: string]
  'update:safetyReport': [value: SafetyReport]
  'add-tag': []
}>()

const editTitleModel = computed({ get: () => props.editTitle, set: (v: string) => emit('update:editTitle', v) })
const editReviewModel = computed({ get: () => props.editReview, set: (v: string) => emit('update:editReview', v) })
const newTagInputModel = computed({ get: () => props.newTagInput, set: (v: string) => emit('update:newTagInput', v) })

</script>

<template>
  <section class="result-card gl-zone fade-in">
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

    <AnalysisEditForm
      v-if="isEditing"
      id-suffix="-step"
      :show-title="result.title !== undefined"
      v-model:edit-title="editTitleModel"
      v-model:edit-review="editReviewModel"
      v-model:new-tag-input="newTagInputModel"
      :edit-tags="editTags" :result-has-tags="Boolean(result.tags)"
      :saving-style="savingStyle" :save-style-error="saveStyleError"
      :apply-edits-locally="applyEditsLocally" :save-style-memory="saveStyleMemory"
      :remove-edit-tag="removeEditTag" :cancel-editing="cancelEditing"
      @add-tag="$emit('add-tag')"
    />

    <div v-else class="result-block">
      <h4 v-if="result.title" class="result-label">标题</h4>
      <p v-if="result.title" class="result-text result-emphasis">{{ result.title }}</p>
      <h4 class="result-label">评价内容</h4>
      <p class="result-text">{{ result.review }}</p>
    </div>

    <SafetyFindingsPanel
      v-if="safetyReport"
      :report="safetyReport"
      :text="isEditing ? editReview : result.review"
      @updated="$emit('update:safetyReport', $event)"
    />

    <div v-if="!isEditing" class="step-actions">
      <div v-if="generationStage === 'draft-review'" class="step-nav">
        <button class="btn-primary gl-btn-primary" @click="proceedToOptimize">下一步：润色优化</button>
      </div>
      <div v-else-if="generationStage === 'optimize-review'" class="step-nav">
        <button class="btn-primary gl-btn-primary" @click="proceedToStyleRefine">下一步：风格偏好优化</button>
      </div>
    </div>

    <section v-if="!isEditing && hasGenerationSteps" class="result-steps">
      <div class="result-steps-head">
        <p class="result-label">生成步骤</p>
      </div>
      <GenerationStepsList
        :items="progressEvents" key-prefix="step-" :clickable="true"
        :step-results="stepResults" :get-stage-label="getStageLabel"
        :get-event-duration-label="getEventDurationLabel" :select-step-result="selectStepResult"
      />
    </section>

    <div v-if="!isEditing && result.tags && result.tags.length" class="result-tags-wrap">
      <h4 class="result-label">标签</h4>
      <div class="result-tags">
        <span v-for="tag in result.tags" :key="tag" class="result-tag">{{ tag }}</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
/* ===== 自 ImageAnalysisView.vue 逐字随迁 ===== */
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

.btn-primary,
.btn-secondary,
.btn-copy {
  min-height: 38px;
  padding: 0 var(--space-md);
  border-radius: var(--radius-sm);
}

.result-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.btn-primary:disabled,
.btn-secondary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
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

.error-text {
  margin: 0;
  color: var(--color-danger);
  font-size: 0.85rem;
}

.save-style-success {
  display: flex;
  padding: 12px 16px;
  border-radius: var(--radius-lg);
  border: 1px solid color-mix(in srgb, var(--color-success) 28%, transparent);
  background: color-mix(in srgb, var(--color-success) 8%, transparent);
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

.step-actions {
  display: grid;
  gap: 12px;
}

.step-nav {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
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
.result-tags-wrap { display: grid; gap: var(--space-xs); }
.result-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.result-tag {
  padding: 3px 10px;
  border-radius: var(--radius-pill);
  border: 1px solid var(--color-border);
  background: var(--surface-muted);
  font-size: 0.78rem;
}
</style>
