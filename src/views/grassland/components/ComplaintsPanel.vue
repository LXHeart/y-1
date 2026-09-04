<template>
  <!-- 任务书 #74 卡 C：原 /complaints 独立页的兜底表单与「我的投诉」列表迁入个人设置弹窗
       第三节（D7）。与场景化举报弹窗（ComplaintModal）共用 useComplaints 与原因映射表——
       本面板对象自由选（六值全量，含场景化未覆盖的 content/order/other），原因按所选对象联动。 -->
  <div class="complaints-panel">
    <!-- D8 分流文案：审判流程与客服受理的边界提示 -->
    <p class="dispute-note">
      履约被驳回、酬金有争议？请在「我的履约」开启争议，由审判流程处理；举报投诉由客服受理。
    </p>

    <div class="complaints-grid">
      <form class="panel complaint-form" @submit.prevent="submit">
        <h3>提交举报</h3>
        <label class="field">
          <span>举报对象</span>
          <select v-model="form.targetType">
            <option v-for="(label, key) in TARGET_LABELS" :key="key" :value="key">{{ label }}</option>
          </select>
        </label>
        <label class="field">
          <span>对象标识（选填）</span>
          <input v-model="form.targetId" maxlength="128" placeholder="任务 ID / 帖子链接 / 订单号等" />
        </label>
        <label class="field">
          <span>举报原因</span>
          <select v-model="form.reason" required>
            <option v-for="reason in reasonOptions" :key="reason" :value="reason">
              {{ REASON_LABELS[reason] }}
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
        <p v-if="notice" class="form-notice">{{ notice }}</p>
        <button type="submit" :disabled="loading">提交举报</button>
      </form>

      <div class="panel complaint-list">
        <div class="list-head">
          <h3>我的投诉</h3>
          <button type="button" :disabled="loading" @click="loadMine">刷新</button>
        </div>
        <p v-if="mineError" class="form-error" role="alert">{{ mineError }}</p>
        <p v-if="!loading && items.length === 0" class="hint">暂无投诉记录。</p>
        <ul v-if="items.length">
          <li v-for="item in items" :key="item.id" class="complaint-item">
            <div class="complaint-item-head">
              <strong>{{ TARGET_LABELS[item.targetType] || item.targetType }}</strong>
              <span class="status" :data-status="item.status">{{ STATUS_LABELS[item.status] || item.status }}</span>
            </div>
            <p class="meta">{{ REASON_LABELS[item.reason] || item.reason }} · {{ time(item.createdAt) }}</p>
            <p class="desc">{{ item.description }}</p>
            <p v-if="item.resolutionNote" class="resolution">处置结论：{{ item.resolutionNote }}</p>
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  COMPLAINT_REASON_LABELS,
  COMPLAINT_REASON_OPTIONS,
  COMPLAINT_STATUS_LABELS,
  COMPLAINT_TARGET_LABELS,
  useComplaints,
  type ComplaintReason,
  type ComplaintTargetType,
  type UserComplaint,
} from '../../../composables/useComplaints'

const TARGET_LABELS = COMPLAINT_TARGET_LABELS
const REASON_LABELS = COMPLAINT_REASON_LABELS
const STATUS_LABELS = COMPLAINT_STATUS_LABELS

const api = useComplaints()
const form = reactive<{ targetType: ComplaintTargetType; targetId: string; reason: ComplaintReason; description: string }>({
  targetType: 'task', targetId: '', reason: 'spam', description: '',
})
const loading = ref(false)
const error = ref('')
const notice = ref('')
const items = ref<UserComplaint[]>([])
const mineError = ref('')

/** D6：兜底表单与场景化弹窗消费同一份映射表——对象切换时联动原因选项。 */
const reasonOptions = computed(() => COMPLAINT_REASON_OPTIONS[form.targetType])

// 切对象后旧原因可能不在新选项里（如 order 只有 fraud/other）——校正到首个合法值
watch(reasonOptions, (options) => {
  if (!options.includes(form.reason)) form.reason = options[0]
})

onMounted(() => { void loadMine() })

async function submit(): Promise<void> {
  if (loading.value) return
  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    await api.submitComplaint(form)
    notice.value = '已提交，客服会尽快处理'
    form.description = ''
    form.targetId = ''
    await loadMine()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '提交失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function loadMine(): Promise<void> {
  mineError.value = ''
  try {
    const page = await api.listMyComplaints()
    items.value = page?.items ?? []
  } catch (cause) {
    mineError.value = cause instanceof Error ? cause.message : '投诉记录加载失败'
  }
}

function time(value: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
}
</script>

<!-- 自 ComplaintsView 原样随迁的 scoped 样式（任务书 #74 D7），token-only；
     独立页时的 section-header 样式随页签撤除一并弃用——节标题由弹窗的 gl-zone-head 承担。 -->
<style scoped>
.complaints-panel { display: grid; gap: var(--space-md); }
.dispute-note { margin: 0; font-size: var(--text-xs); color: var(--color-text-muted); }

.complaints-grid {
  display: grid;
  grid-template-columns: minmax(240px, 320px) minmax(0, 1fr);
  gap: var(--space-lg);
  align-items: start;
}

.panel {
  display: grid;
  gap: var(--space-md);
  padding: var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--surface-card);
  box-shadow: var(--shadow-card);
}
.panel h3 { margin: 0; font-size: 1rem; color: var(--color-text); }

.list-head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-sm); }

.field { display: grid; gap: var(--space-xs); font-size: 0.88rem; color: var(--color-text-secondary); }
.field select,
.field input,
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
  font-size: 0.86rem;
  color: var(--color-danger);
  background: color-mix(in srgb, var(--color-danger) 10%, transparent);
}
.form-notice {
  margin: 0;
  padding: var(--space-sm);
  border-radius: var(--radius-sm);
  font-size: 0.86rem;
  color: var(--color-success);
  background: color-mix(in srgb, var(--color-success) 10%, transparent);
}

.complaint-form button[type="submit"] {
  justify-self: start;
  min-height: 38px;
  padding: 0 var(--space-md);
  font-weight: 600;
}
.complaint-form button[type="submit"]:disabled { opacity: 0.55; cursor: not-allowed; }

.hint { margin: 0; color: var(--color-text-muted); font-size: 0.86rem; }

.complaint-list ul { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-md); }
.complaint-item {
  display: grid;
  gap: var(--space-xs);
  padding: var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--surface-page);
}
.complaint-item-head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-sm); }
.complaint-item-head strong { font-size: 0.94rem; }
.status { padding: 2px 8px; border-radius: var(--radius-pill); font-size: 0.72rem; font-weight: 600; }
.status[data-status="open"] { color: var(--color-warning); background: color-mix(in srgb, var(--color-warning) 14%, transparent); }
.status[data-status="processing"] { color: var(--color-info); background: color-mix(in srgb, var(--color-info) 12%, transparent); }
.status[data-status="resolved"] { color: var(--color-success); background: color-mix(in srgb, var(--color-success) 12%, transparent); }
.status[data-status="dismissed"] { color: var(--color-text-secondary); background: color-mix(in srgb, var(--color-text-secondary) 10%, transparent); }

.meta { margin: 0; font-size: 0.8rem; color: var(--color-text-muted); }
.desc { margin: 0; font-size: 0.9rem; color: var(--color-text); }
.resolution {
  margin: 0;
  padding: var(--space-xs) var(--space-sm);
  border-radius: var(--radius-sm);
  font-size: 0.84rem;
  color: var(--color-text-secondary);
  background: color-mix(in srgb, var(--color-success) 8%, transparent);
}

@media (max-width: 960px) {
  .complaints-grid { grid-template-columns: 1fr; }
}
</style>
