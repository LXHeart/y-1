<template>
  <section class="complaints-view">
    <header class="section-header">
      <h2 class="section-title">举报与投诉</h2>
      <p class="section-desc">
        对任务、履约交付物、内容、订单或用户提交投诉，客服会在处置台受理；交易争议仍走争议流程。
      </p>
    </header>

    <div class="complaints-grid">
      <form class="panel complaint-form" @submit.prevent="submit">
        <h3>提交投诉</h3>
        <label class="field">
          <span>举报对象</span>
          <select v-model="form.targetType" required>
            <option v-for="(label, key) in TARGET_LABELS" :key="key" :value="key">{{ label }}</option>
          </select>
        </label>
        <label class="field">
          <span>对象标识（选填）</span>
          <input v-model="form.targetId" maxlength="128" placeholder="任务 ID / 帖子链接 / 订单号等" />
        </label>
        <label class="field">
          <span>投诉原因</span>
          <select v-model="form.reason" required>
            <option v-for="(label, key) in REASON_LABELS" :key="key" :value="key">{{ label }}</option>
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
        <button type="submit" :disabled="loading">提交投诉</button>
      </form>

      <div class="panel complaint-list">
        <h3>我的投诉</h3>
        <button type="button" class="quiet" :disabled="loading" @click="loadMine">刷新</button>
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
  </section>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  COMPLAINT_REASON_LABELS,
  COMPLAINT_STATUS_LABELS,
  COMPLAINT_TARGET_LABELS,
  useComplaints,
  type ComplaintReason,
  type ComplaintTargetType,
  type UserComplaint,
} from '../../composables/useComplaints'

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
