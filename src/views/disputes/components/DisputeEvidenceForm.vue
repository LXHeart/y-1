<template>
  <GlModal
    :title="phase === 'answer' ? '提交答辩' : '补充质证'"
    :persistent="submitting"
    @close="requestClose"
  >
    <p class="case-binding" data-testid="evidence-form-case">
      案件编号：<span class="case-ref">{{ caseLabel }}</span>
      <span v-if="!contextCurrent" class="case-stale">（案件目标已变化，提交将被拒绝）</span>
    </p>

    <div class="form-group">
      <label for="evidence-text">证据内容 <span class="required">*</span></label>
      <textarea
        id="evidence-text"
        ref="textRef"
        v-model="text"
        class="form-textarea"
        rows="6"
        placeholder="请详细描述您的证据和理由..."
        :disabled="submitting"
        data-testid="evidence-text"
      ></textarea>
      <p class="field-meta">
        <span>{{ text.trim().length }} / {{ EVIDENCE_CONTENT_MAX }}</span>
      </p>
      <p v-if="validationError" class="field-error" role="alert" data-testid="evidence-form-error">{{ validationError }}</p>
    </div>

    <div class="form-group">
      <label for="evidence-caption">说明</label>
      <input
        id="evidence-caption"
        v-model="caption"
        type="text"
        class="form-input"
        placeholder="可选：简短说明"
        :disabled="submitting"
        data-testid="evidence-caption"
      />
      <p class="field-meta">
        <span>{{ caption.trim().length }} / {{ EVIDENCE_CAPTION_MAX }}</span>
      </p>
    </div>

    <template #actions>
      <button
        class="gl-btn-secondary"
        type="button"
        :disabled="submitting"
        data-testid="evidence-cancel"
        @click="requestClose"
      >
        取消
      </button>
      <button
        class="gl-btn-primary form-submit"
        type="button"
        :disabled="submitting"
        data-testid="evidence-submit"
        @click="attemptSubmit"
      >
        {{ submitting ? '提交中...' : '提交' }}
      </button>
    </template>
  </GlModal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import GlModal from '../../../components/GlModal.vue'
import {
  EVIDENCE_CAPTION_MAX,
  EVIDENCE_CONTENT_MAX,
} from '../dispute-presentation'
import type { DisputeEvidenceItemInput } from '../composables/useDisputeEvidenceActions'

/**
 * 争议证据表单（任务书 #103 C103-12：证据输入/确认/关闭从大视图拆出）。
 *
 * - 展示绑定打开时的案号；caseKey 变化（切案/换号）立即清空正文与说明；
 * - 长度校验沿用服务端 Trust EvidenceItem 上限（10,000/500），超限提示并阻止提交，
 *   绝不静默截断后提交；空白正文不发；
 * - 提交中（persistent）不可误关；错误后同案可恢复输入（正文不清空）。
 */
const props = defineProps<{
  phase: 'answer' | 'rebuttal'
  /** 绑定案件 id：变化即清表单（防旧案正文串到新案）。 */
  caseKey: string
  /** 展示案号（脱敏 8 位）。 */
  caseLabel: string
  submitting: boolean
  /** 打开时捕获的写上下文仍有效（失效时提示并阻止提交）。 */
  contextCurrent: boolean
}>()

const emit = defineEmits<{
  submit: [items: DisputeEvidenceItemInput[]]
  cancel: []
}>()

const text = ref('')
const caption = ref('')
const attempted = ref(false)
const textRef = ref<HTMLTextAreaElement | null>(null)

// 切案/换号清表单（含未提交正文——私有输入不得串到新案件）。
watch(() => props.caseKey, () => {
  text.value = ''
  caption.value = ''
  attempted.value = false
})

const trimmedText = computed(() => text.value.trim())
const trimmedCaption = computed(() => caption.value.trim())

const validationError = computed(() => {
  if (!attempted.value) return ''
  if (trimmedText.value === '') return '请输入证据内容'
  if (trimmedText.value.length > EVIDENCE_CONTENT_MAX) {
    return `证据内容不能超过 ${EVIDENCE_CONTENT_MAX} 字（当前 ${trimmedText.value.length} 字），请精简后再提交`
  }
  if (trimmedCaption.value.length > EVIDENCE_CAPTION_MAX) {
    return `说明不能超过 ${EVIDENCE_CAPTION_MAX} 字（当前 ${trimmedCaption.value.length} 字）`
  }
  if (!props.contextCurrent) return '案件目标已变化，请关闭后重新确认案件'
  return ''
})

const canSubmit = computed(() =>
  trimmedText.value !== ''
  && trimmedText.value.length <= EVIDENCE_CONTENT_MAX
  && trimmedCaption.value.length <= EVIDENCE_CAPTION_MAX
  && props.contextCurrent,
)

function attemptSubmit(): void {
  attempted.value = true
  if (!canSubmit.value) return
  // 冻结提交时刻的输入快照（与动作层冻结 URL/phase 同协议）。
  const items: DisputeEvidenceItemInput[] = [{
    kind: 'text',
    contentRef: trimmedText.value,
    caption: trimmedCaption.value === '' ? undefined : trimmedCaption.value,
  }]
  emit('submit', items)
}

function requestClose(): void {
  if (props.submitting) return // 提交中不允许取消（不显示「写已取消」）
  emit('cancel')
}
</script>

<style scoped>
.case-binding {
  margin: 0 0 1rem;
  font-size: var(--type-body-sm);
  color: var(--color-text-secondary);
}

.case-ref {
  font-family: var(--font-mono);
  color: var(--color-text);
}

.case-stale {
  color: var(--color-warning);
}

.form-group {
  margin-bottom: 1.25rem;
}

.form-group:last-child {
  margin-bottom: 0;
}

.form-group label {
  display: block;
  font-size: var(--type-body-sm);
  font-weight: var(--weight-label);
  margin-bottom: 0.5rem;
  color: var(--color-text);
}

.required {
  color: var(--color-danger);
}

.form-textarea,
.form-input {
  width: 100%;
  padding: 0.75rem;
  background: var(--surface-hover);
  border: 1px solid var(--color-border-control);
  border-radius: var(--radius-md);
  color: var(--color-text);
  font-size: var(--type-body-sm);
  font-family: var(--font-body);
  resize: vertical;
}

.form-textarea:focus,
.form-input:focus {
  outline: var(--focus-width) solid var(--focus-color);
  border-color: var(--color-accent);
}

.form-textarea:disabled,
.form-input:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.field-meta {
  margin: 0.375rem 0 0;
  font-size: var(--type-caption);
  color: var(--color-text-muted);
  text-align: right;
}

.field-error {
  margin: 0.375rem 0 0;
  font-size: var(--type-caption);
  color: var(--color-danger);
}

.form-submit {
  margin-left: auto;
}
</style>
