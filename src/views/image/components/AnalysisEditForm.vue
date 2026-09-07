<script setup lang="ts">
import { computed } from 'vue'

/**
 * 结果编辑表单（任务书 #91 I2：原步审卡/结果卡两处重复的编辑区抽为一份，纯搬运）。
 * idSuffix 保持两处原本不同的 input id（edit-title vs edit-title-step），label for 同步。
 */
const props = defineProps<{
  idSuffix: string
  showTitle: boolean
  editTitle: string
  editReview: string
  editTags: ReadonlyArray<string>
  resultHasTags: boolean
  newTagInput: string
  savingStyle: boolean
  saveStyleError: string
  applyEditsLocally: () => void
  saveStyleMemory: () => Promise<unknown>
  removeEditTag: (index: number) => void
  cancelEditing: () => void
}>()

const emit = defineEmits<{
  'update:editTitle': [value: string]
  'update:editReview': [value: string]
  'update:newTagInput': [value: string]
  'add-tag': []
}>()

const editTitleModel = computed({ get: () => props.editTitle, set: (v: string) => emit('update:editTitle', v) })
const editReviewModel = computed({ get: () => props.editReview, set: (v: string) => emit('update:editReview', v) })
const newTagInputModel = computed({ get: () => props.newTagInput, set: (v: string) => emit('update:newTagInput', v) })

</script>

<template>
  <div class="result-block" :class="{ 'edit-saving': savingStyle }">
    <div v-if="showTitle" class="edit-field">
      <label class="result-label" :for="`edit-title${idSuffix}`">标题</label>
      <input :id="`edit-title${idSuffix}`" v-model="editTitleModel" class="field-input-sm edit-input-full" placeholder="输入标题" :disabled="savingStyle">
    </div>
    <div class="edit-field">
      <label class="result-label" :for="`edit-review${idSuffix}`">评价内容</label>
      <textarea :id="`edit-review${idSuffix}`" v-model="editReviewModel" class="field-textarea" rows="6" :disabled="savingStyle"></textarea>
    </div>
    <div v-if="editTags.length > 0 || resultHasTags" class="edit-field">
      <label class="result-label">标签</label>
      <div class="edit-tags">
        <span v-for="(tag, i) in editTags" :key="i" class="edit-tag-item">
          {{ tag }}
          <button class="edit-tag-remove" type="button" :disabled="savingStyle" @click="removeEditTag(i)">&times;</button>
        </span>
        <input
          v-model="newTagInputModel"
          class="field-input-sm edit-tag-input"
          placeholder="添加标签"
          :disabled="savingStyle"
          @keydown.enter.prevent="emit('add-tag')"
        >
      </div>
    </div>
    <div class="edit-actions">
      <button class="btn-primary gl-btn-primary" :disabled="savingStyle" @click="applyEditsLocally">直接保存</button>
      <button class="btn-save-style" :disabled="savingStyle" @click="saveStyleMemory">
        <svg v-if="savingStyle" class="spin-icon" width="14" height="14" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="2" stroke-dasharray="28" stroke-dashoffset="10" stroke-linecap="round"/></svg>
        {{ savingStyle ? '保存中…' : '记忆风格并保存' }}
      </button>
      <button class="btn-secondary" :disabled="savingStyle" @click="cancelEditing">取消</button>
    </div>
    <p v-if="saveStyleError" class="error-text">{{ saveStyleError }}</p>
  </div>
</template>

<style scoped>
/* ===== 自 ImageAnalysisView.vue 逐字随迁 ===== */
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

.btn-primary,
.btn-secondary,
.btn-copy {
  min-height: 38px;
  padding: 0 var(--space-md);
  border-radius: var(--radius-sm);
}

.spin-icon {
  animation: spin 1s linear infinite;
}

.btn-primary:disabled,
.btn-secondary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
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
  border-radius: var(--radius-pill);
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
  border-radius: var(--radius-pill);
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
