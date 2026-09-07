<script setup lang="ts">
import { computed } from 'vue'
import SafetyFindingsPanel from '../../../components/SafetyFindingsPanel.vue'
import AnalysisEditForm from './AnalysisEditForm.vue'
import GenerationStepsList from './GenerationStepsList.vue'
import type { SafetyReport } from '../../../types/content-safety'
import type { ImageAnalysisProgressEvent, ImageAnalysisProgressStage } from '../../../types/image-analysis'

/**
 * 结果卡（任务书 #91 I2 自 ImageAnalysisView.vue 模板 391–517 整段迁入，纯搬运；
 * 编辑表单与生成步骤列表改用去重组件；飞书导出经 guardedExport（视图从控制卡上抛接线））。
 */
const props = defineProps<{
  result: { title?: string; review: string; tags?: string[]; runId?: string }
  copyLabel: string
  copyLinkLabel: string
  isEditing: boolean
  savingStyle: boolean
  saveStyleError: string
  saveStyleSuccess: boolean
  editTitle: string
  editReview: string
  editTags: string[]
  newTagInput: string
  safetyReport: SafetyReport | null
  exporting: boolean
  exportError: string
  exportedDocUrl: string
  exportedDocTitle: string
  showGenerationSteps: boolean
  hasGenerationSteps: boolean
  generationStepToggleLabel: string
  loading: boolean
  loadingPreferences: boolean
  showStylePreferences: boolean
  progressEvents: ReadonlyArray<ImageAnalysisProgressEvent>
  stepResults: Record<string, unknown>
  getStageLabel: (stage: ImageAnalysisProgressStage) => string
  getEventDurationLabel: (event: ImageAnalysisProgressEvent) => string
  selectStepResult: (stage: ImageAnalysisProgressStage) => void
  copyReview: () => void
  toggleGenerationSteps: () => void
  startEditing: () => void
  cancelEditing: () => void
  applyEditsLocally: () => void
  saveStyleMemory: () => Promise<unknown>
  removeEditTag: (index: number) => void
  guardedExport: (action: () => Promise<void>) => void
  exportToFeishu: () => Promise<unknown>
  toggleStylePreferences: () => void
  saveVersionSnapshot: () => void
  copyDocLink: () => void
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
        <button class="btn-export-feishu" :disabled="exporting || isEditing" @click="guardedExport(async () => { await exportToFeishu() })">
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

    <AnalysisEditForm
      v-if="isEditing"
      id-suffix=""
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

    <section v-if="showGenerationSteps && hasGenerationSteps" class="result-steps">
      <div class="result-steps-head">
        <p class="result-label">本次生成步骤</p>
        <p v-if="result.runId" class="result-steps-run-id">运行 ID：{{ result.runId }}</p>
      </div>
      <GenerationStepsList
        :items="progressEvents" key-prefix="result-" :clickable="true"
        :step-results="stepResults" :get-stage-label="getStageLabel"
        :get-event-duration-label="getEventDurationLabel" :select-step-result="selectStepResult"
      />
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

.result-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.result-tag {
  display: inline-flex;
  align-items: center;
  padding: 5px 10px;
  border-radius: var(--radius-pill);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  color: var(--color-text-secondary);
  font-size: 0.8rem;
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
  border: 1px solid color-mix(in srgb, var(--color-danger) 28%, transparent);
  background: color-mix(in srgb, var(--color-danger) 8%, transparent);
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
  border: 1px solid color-mix(in srgb, var(--color-success) 28%, transparent);
  background: color-mix(in srgb, var(--color-success) 8%, transparent);
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
</style>
