<template>
  <div v-show="active" class="ops-panel">
    <div class="ops-filters">
      <button type="button" class="ops-quiet" :disabled="grassland.loading.value" @click="refresh">刷新</button>
    </div>
    <p class="ops-hint">
      用户提交的通用举报（任务/交付物/内容/订单/用户）。交易争议仍走争议流程（审判官），
      这里是客服处置通道：受理 → 办结（附结论，举报人可见）/ 不成立。
    </p>
    <p v-if="loaded && complaints.length === 0" class="ops-hint">当前没有待受理的投诉。</p>

    <section v-for="row in complaints" :key="row.id" class="ops-item">
      <div class="ops-item-head">
        <strong>{{ TARGET_LABELS[row.targetType] || row.targetType }}{{ row.targetId ? ' · ' + row.targetId : '' }}</strong>
        <span class="ops-pos">举报人 <code>{{ shortId(row.reporterAccountId) }}</code> · {{ REASON_LABELS[row.reason] || row.reason }} · {{ time(row.createdAt) }}</span>
      </div>
      <p class="ops-hint">{{ row.description }}</p>
      <p v-if="row.resolutionNote" class="ops-hint">处置结论：{{ row.resolutionNote }}</p>
      <div class="ops-actions ops-review-actions">
        <input
          v-model="notes[row.id]"
          class="ops-review-note"
          type="text"
          maxlength="500"
          placeholder="处置结论（办结/不成立必填）"
        />
        <button
          type="button"
          :disabled="grassland.loading.value || row.status === 'resolved' || row.status === 'dismissed'"
          @click="handle(row, 'processing')"
        >受理</button>
        <button
          type="button"
          :disabled="grassland.loading.value"
          @click="handle(row, 'resolved')"
        >办结</button>
        <button
          type="button"
          class="ops-danger"
          :disabled="grassland.loading.value"
          @click="handle(row, 'dismissed')"
        >不成立</button>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
/**
 * 投诉工单面板（PRD §11.8；任务书 #94 自 OpsConsole 原样拆出，行为零变更：800 行门禁下的分层归位）。
 * 可见性与懒加载由父级 `active` 驱动（首次切入拉取一次）；提示统一 emit 给父级 say()。
 */
import { ref, watch } from 'vue'
import { OPS_COMPLAINT_REASON_LABELS, OPS_COMPLAINT_TARGET_LABELS } from '../../../types/grassland'
import type { useGrassland } from '../../../composables/useGrassland'
import type { OpsComplaint } from '../../../types/grassland'

const props = defineProps<{
  active: boolean
  grassland: ReturnType<typeof useGrassland>
}>()

const emit = defineEmits<{ notice: [message: string, bad?: boolean] }>()

const TARGET_LABELS = OPS_COMPLAINT_TARGET_LABELS
const REASON_LABELS = OPS_COMPLAINT_REASON_LABELS
const complaints = ref<OpsComplaint[]>([])
const loaded = ref(false)
const notes = ref<Record<string, string>>({})

watch(() => props.active, (on) => {
  if (on && !loaded.value) void refresh()
})

async function refresh(): Promise<void> {
  const result = await props.grassland.listOpsComplaints()
  if (result) complaints.value = [...result.items]
  loaded.value = true
}

async function handle(row: OpsComplaint, action: 'processing' | 'resolved' | 'dismissed'): Promise<void> {
  const note = (notes.value[row.id] || '').trim()
  if (action !== 'processing' && !note) {
    emit('notice', '办结/不成立必须填写结论', true)
    return
  }
  const result = await props.grassland.handleOpsComplaint(row.id, action, note || undefined)
  if (result) {
    notes.value[row.id] = ''
    emit('notice', action === 'processing' ? '已受理' : action === 'resolved' ? '已办结' : '已判定不成立')
    await refresh()
  } else {
    emit('notice', props.grassland.error.value || '投诉处置失败', true)
  }
}

function shortId(id: string | null): string {
  return id ? `${id.slice(0, 8)}…` : '—'
}

function time(value: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}
</script>

<style scoped>
@import '../ops-console-shared.css';
</style>
