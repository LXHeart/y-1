<script setup lang="ts">
import { ref } from 'vue'
import { formatYuan } from '../../../lib/money'
import { formatDateTime } from '../admin-format'
import type { DhAdminSessionRow } from '../composables/useDigitalHumanAdmin'

/**
 * 数字人会话治理表（任务书 #105G C105G-04 / K10 ADMIN03-04）。
 *
 * 仅脱敏元数据：账号标识/状态/时长/计数/计费汇总/阶段延迟——刻意不显示 prompt、
 * 字幕内容、头像原图、SDP 或媒体授权（K13 隐私口径）。终止是显式两步确认：先展开
 * 影响说明与原因输入，再提交；提交锁同会话（在途重复点击只发一次）。
 */
defineProps<{
  sessions: DhAdminSessionRow[]
  loading: boolean
  error: string | null
  terminating: string | null
  actionError: string | null
  notice: string | null
}>()

const emit = defineEmits<{
  terminate: [sessionId: string, reason: string]
  refresh: []
}>()

const confirmingId = ref<string | null>(null)
const reason = ref('')

const TERMINAL_STATES = ['ended', 'failed']

function runtimeMinutes(row: DhAdminSessionRow): string {
  const start = new Date(row.createdAt).getTime()
  const end = row.endedAt ? new Date(row.endedAt).getTime() : Date.now()
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return '—'
  const minutes = Math.round((end - start) / 60000)
  return `${minutes} 分钟`
}

function phaseSummary(row: DhAdminSessionRow): string {
  if (row.phaseMetrics.length === 0) return '—'
  return row.phaseMetrics
    .map((metric) => `${metric.phase} p95 ${metric.p95Ms ?? metric.lastDurationMs ?? '—'}ms`)
    .join('；')
}

function beginTerminate(row: DhAdminSessionRow): void {
  confirmingId.value = confirmingId.value === row.id ? null : row.id
  reason.value = ''
}

function confirmTerminate(): void {
  if (confirmingId.value == null || !reason.value.trim()) return
  emit('terminate', confirmingId.value, reason.value.trim())
  confirmingId.value = null
  reason.value = ''
}
</script>

<template>
  <section class="dh-session-table" data-test="dh-sessions" aria-label="数字人会话">
    <div class="panel-toolbar">
      <div>
        <h3>活跃与历史会话</h3>
        <p>仅脱敏元数据（状态/时长/计数/计费汇总）；不含对话内容、转写正文与媒体授权。</p>
      </div>
      <button type="button" class="refresh-btn" data-test="dh-sessions-refresh" :disabled="loading"
        @click="emit('refresh')">刷新</button>
    </div>

    <p v-if="error" class="error-msg" role="alert" data-test="dh-sessions-error">{{ error }}</p>
    <p v-if="actionError" class="error-msg" role="alert" data-test="dh-terminate-error">{{ actionError }}</p>
    <p v-if="notice" class="dh-notice" role="status" data-test="dh-terminate-notice">{{ notice }}</p>
    <p v-if="loading && sessions.length === 0" class="loading-state" data-test="dh-sessions-loading">正在读取会话…</p>
    <p v-else-if="!loading && sessions.length === 0" class="loading-state" data-test="dh-sessions-empty">窗口内暂无会话。</p>

    <div v-if="sessions.length > 0" class="table-scroll">
      <table class="user-table dh-session-grid" data-test="dh-sessions-table">
        <thead>
          <tr>
            <th>会话 ID</th>
            <th>形象（创建时名）</th>
            <th>状态</th>
            <th>开始 / 结束</th>
            <th class="num">时长</th>
            <th class="num">转写/录制/素材</th>
            <th class="num">已确认 / 待结算</th>
            <th>阶段延迟</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="row in sessions" :key="row.id">
            <tr data-test="dh-session-row">
              <td class="id-cell">{{ row.id }}</td>
              <td>{{ row.profileNameAtCreation }}</td>
              <td>
                <span class="type-tag" :class="{ overdue: row.state === 'failed' }" data-test="dh-session-state">
                  {{ row.state }}
                </span>
                <span v-if="row.cleanupPending" class="type-tag dh-tag-warn" data-test="dh-session-cleanup">清理未收口</span>
                <span v-if="row.errorCode" class="type-tag dh-tag-warn">{{ row.errorCode }}</span>
              </td>
              <td class="td-time">
                {{ formatDateTime(row.createdAt) }}<br />{{ row.endedAt ? formatDateTime(row.endedAt) : '进行中' }}
              </td>
              <td class="num td-time">{{ runtimeMinutes(row) }}</td>
              <td class="num">
                {{ row.hasSavedTranscript ? '转写✓' : '—' }} / {{ row.recordingCount }} / {{ row.savedAssetCount }}
              </td>
              <td class="num">
                {{ formatYuan(row.billing.confirmedCents) }} / {{ row.billing.pendingCount }}
              </td>
              <td class="td-muted">{{ phaseSummary(row) }}</td>
              <td>
                <button v-if="!TERMINAL_STATES.includes(row.state)" type="button" class="reject-btn"
                  :data-test="`dh-terminate-open-${row.id}`" :disabled="terminating != null"
                  @click="beginTerminate(row)">
                  {{ terminating === row.id ? '终止中…' : '紧急终止' }}
                </button>
                <span v-else class="td-muted" :data-test="`dh-terminate-done-${row.id}`">已终态</span>
              </td>
            </tr>
            <tr v-if="confirmingId === row.id" class="dh-confirm-row" data-test="dh-terminate-confirm">
              <td colspan="9">
                <div class="dh-confirm-box">
                  <strong>确认终止会话 {{ row.id }}？</strong>
                  <p data-test="dh-terminate-impact">
                    影响：立即断开该用户的在途连接并结束会话；此后不再产生新的计量与费用；
                    已确认费用与已保存素材保留（清理仍按生命周期收口）。原因将写入治理审计。
                  </p>
                  <label class="dh-field">
                    <span>终止原因（必填）</span>
                    <input class="field-input" type="text" data-test="dh-terminate-reason" v-model="reason"
                      maxlength="200" placeholder="例如：上游故障紧急止损" />
                  </label>
                  <div class="dh-form-actions">
                    <button type="button" class="reject-btn" data-test="dh-terminate-submit"
                      :disabled="!reason.trim() || terminating != null" @click="confirmTerminate">
                      {{ terminating === row.id ? '终止中…' : '确认终止' }}
                    </button>
                    <button type="button" class="refresh-btn" data-test="dh-terminate-cancel"
                      @click="confirmingId = null">取消</button>
                  </div>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped src="../admin-shared.css"></style>
<style scoped>
.dh-session-table { display: grid; gap: var(--space-sm); }
.dh-session-grid { min-width: 1080px; }
</style>
