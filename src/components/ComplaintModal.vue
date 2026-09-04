<template>
  <!-- 任务书 #74：场景化举报弹窗——对象从业务卡带入并锁定（D5），自由选择只留给
       个人设置弹窗里的兜底表单。persistent 防误触丢描述草稿（D10），关闭走右上角 ×。
       GlModal 会 Teleport 到 body：插槽内容必须包一层 gl-field 恢复田垄作用域（D9）。 -->
  <GlModal v-if="open" :title="`举报${COMPLAINT_TARGET_LABELS[targetType]}`" persistent @close="$emit('close')">
    <div class="gl-field complaint-modal">
      <!-- 对象摘要行：只读展示，targetId 截前 8 位（台账 code 样式，同报名表） -->
      <p class="complaint-target">
        <span class="complaint-target-summary">{{ targetSummary }}</span>
        <code v-if="targetId" class="gl-num">{{ targetId.slice(0, 8) }}…</code>
      </p>

      <form @submit.prevent="submit">
        <!-- 对象锁定不可改：不渲染对象下拉，原因选项按对象过滤（D5/D6） -->
        <label class="field">
          <span>投诉原因</span>
          <select v-model="form.reason" required>
            <option v-for="reason in reasonOptions" :key="reason" :value="reason">
              {{ COMPLAINT_REASON_LABELS[reason] }}
            </option>
          </select>
        </label>
        <label class="field">
          <span>问题描述（{{ form.description.length }}/500）</span>
          <textarea
            v-model="form.description"
            maxlength="500"
            rows="4"
            placeholder="说明发生了什么，便于客服核实"
            required
          ></textarea>
        </label>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      </form>

      <!-- D8 分流文案：推荐官被驳回的最高频愤怒时刻不灌客服队列 -->
      <p class="dispute-note">
        履约被驳回、酬金有争议？请在「我的履约」开启争议，由审判流程处理；举报投诉由客服受理。
      </p>
    </div>

    <template #actions>
      <button type="button" :disabled="loading" @click="submit">{{ loading ? '提交中…' : '提交' }}</button>
    </template>
  </GlModal>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import GlModal from './GlModal.vue'
import {
  COMPLAINT_REASON_LABELS,
  COMPLAINT_REASON_OPTIONS,
  COMPLAINT_TARGET_LABELS,
  useComplaints,
  type ComplaintReason,
  type ComplaintTargetType,
} from '../composables/useComplaints'

const props = defineProps<{
  open: boolean
  targetType: ComplaintTargetType
  targetId: string
  targetSummary: string
}>()

const emit = defineEmits<{ close: [] }>()

const api = useComplaints()
const form = reactive<{ reason: ComplaintReason; description: string }>({
  reason: COMPLAINT_REASON_OPTIONS[props.targetType][0],
  description: '',
})
const loading = ref(false)
const error = ref('')

const reasonOptions = computed(() => COMPLAINT_REASON_OPTIONS[props.targetType])

// 弹窗复用同一实例（父控 open），换目标打开时旧对象的原因可能不在新选项里——校正到首个合法值
watch(reasonOptions, (options) => {
  if (!options.includes(form.reason)) form.reason = options[0]
})

async function submit(): Promise<void> {
  if (loading.value) return
  if (!form.description.trim()) {
    error.value = '请填写问题描述'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await api.submitComplaint({
      targetType: props.targetType,
      targetId: props.targetId,
      reason: form.reason,
      description: form.description,
    })
    // D10：成功后清空描述并自动关闭（open 由父控，这里只 emit）
    form.description = ''
    emit('close')
  } catch (cause) {
    // 含 409（同对象同原因在办重复）：服务端 message 原样展示，提示而非报错轰炸
    error.value = cause instanceof Error ? cause.message : '提交失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.complaint-modal { display: grid; gap: var(--space-md); }

.complaint-target {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin: 0;
  padding: var(--space-sm) var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface-hover);
  font-size: var(--text-sm);
  color: var(--color-text);
}
.complaint-target-summary { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.complaint-target code { font-family: var(--font-mono); font-size: var(--text-xs); color: var(--color-text-secondary); }

form { display: grid; gap: var(--space-md); }
.field { display: grid; gap: var(--space-xs); font-size: var(--text-sm); color: var(--color-text-secondary); }
.field select,
.field textarea {
  min-height: 38px;
  padding: 8px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text);
  font: inherit;
}
.field textarea { resize: vertical; }

.form-error {
  margin: 0;
  padding: var(--space-sm);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  color: var(--color-danger);
  background: color-mix(in srgb, var(--color-danger) 10%, transparent);
}

.dispute-note {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--color-text-muted);
}
</style>
