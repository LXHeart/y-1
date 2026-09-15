<script setup lang="ts">
import { onActivated, onMounted, ref } from 'vue'
import GlModal from '../../../components/GlModal.vue'
import { useGrassland } from '../../../composables/useGrassland'
import { formatDateTime } from '../admin-format'

defineOptions({ name: 'EngagementExitRecoveryPanel' })

/**
 * 任务书 #103 C103-04：退出资金恢复队列（治理财务区）。
 * FINANCE 可按 reason+expectedVersion 重排原经济键；RISK 只读（按钮按角色出现，
 * 服务端仍是最终闸门）。不提供任何金额修改输入（原键重排，§6.2）。
 */
const grassland = useGrassland()

interface ExitOperationRow {
  operationId: string
  applicationId: string
  taskId: string
  kind: string
  state: string
  attempts: number
  version: number
  lastErrorCode: string | null
  nextAttemptAt: string | null
  updatedAt: string | null
  completedAt: string | null
  legs: { legKind: string; economicKey: string; amountCents: number; state: string; financeReference: string | null }[]
}

const rows = ref<ExitOperationRow[]>([])
const loading = ref(false)
const error = ref('')
const stateFilter = ref('')
let requestVersion = 0

const STATE_LABELS: Record<string, string> = {
  pending: '待处理', processing: '处理中', retry_wait: '等待重试',
  needs_review: '待核对', succeeded: '已完成',
}
const KIND_LABELS: Record<string, string> = { no_fault: '无责退出', negotiated: '协商退出' }
const LEG_LABELS: Record<string, string> = {
  deposit_refund: '押金退还', bounty_capture: '赏金支付', bounty_release: '赏金释放',
}

async function load(): Promise<void> {
  const version = ++requestVersion
  loading.value = true
  error.value = ''
  const result = await grassland.listEngagementExitOperations({
    state: stateFilter.value || undefined,
    limit: 50,
  })
  if (version !== requestVersion) return
  if (result) {
    rows.value = result.items as unknown as ExitOperationRow[]
  } else {
    error.value = grassland.error.value || '退出资金队列加载失败'
  }
  loading.value = false
}

const retryOpen = ref(false)
const retryTarget = ref<ExitOperationRow | null>(null)
const retryReason = ref('')
const retryError = ref('')
const retrySubmitting = ref(false)

function openRetry(row: ExitOperationRow): void {
  retryTarget.value = row
  retryReason.value = ''
  retryError.value = ''
  retryOpen.value = true
}

async function submitRetry(): Promise<void> {
  const row = retryTarget.value
  if (!row) return
  const reason = retryReason.value.trim()
  if (reason.length < 5 || reason.length > 500) {
    retryError.value = '重排理由长度须在 5 到 500 字之间'
    return
  }
  retrySubmitting.value = true
  retryError.value = ''
  const done = await grassland.retryEngagementExitOperation(row.operationId, reason, row.version)
  retrySubmitting.value = false
  if (!done) {
    retryError.value = grassland.error.value || '重排未受理（版本或资金状态可能已变化）'
    return
  }
  retryOpen.value = false
  await load()
}

// KeepAlive 页签内由 onActivated 重拉；直接挂载（测试/复用）由 onMounted 兜底（requestVersion 去重）。
onMounted(() => { void load() })
onActivated(() => { void load() })
</script>

<template>
  <section class="exit-recovery" data-testid="engagement-exit-recovery-panel">
    <div class="panel-toolbar">
      <h3>退出资金恢复队列</h3>
      <div class="toolbar-actions">
        <label>状态
          <select v-model="stateFilter" @change="load">
            <option value="">全部</option>
            <option v-for="(label, value) in STATE_LABELS" :key="value" :value="value">{{ label }}</option>
          </select>
        </label>
        <button class="refresh-btn" type="button" :disabled="loading" @click="load">刷新</button>
      </div>
    </div>
    <p v-if="error" class="error-msg" role="alert">{{ error }}</p>
    <div v-if="loading" class="loading-state">加载中...</div>
    <div v-else class="table-card">
      <div class="table-scroll">
        <table class="user-table kyb-table">
          <thead>
            <tr><th>类型</th><th>状态</th><th>合作</th><th>资金腿</th><th>尝试</th><th>下次尝试</th><th>更新时间</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.operationId">
              <td>{{ KIND_LABELS[row.kind] || row.kind }}</td>
              <td><span class="type-tag">{{ STATE_LABELS[row.state] || row.state }}</span>
                <div v-if="row.lastErrorCode" class="gl-hint">{{ row.lastErrorCode }}</div></td>
              <td class="id-cell" :title="row.applicationId">{{ row.applicationId.slice(0, 12) }}…</td>
              <td class="legs-cell">
                <div v-for="leg in row.legs" :key="leg.legKind" class="leg-line">
                  {{ LEG_LABELS[leg.legKind] || leg.legKind }} {{ (leg.amountCents / 100).toFixed(2) }} 元
                  <span class="gl-hint">（{{ STATE_LABELS[leg.state] || leg.state }}）</span>
                </div>
              </td>
              <td class="td-time">{{ row.attempts }}</td>
              <td class="td-time">{{ row.nextAttemptAt ? formatDateTime(row.nextAttemptAt) : '—' }}</td>
              <td class="td-time">{{ row.updatedAt ? formatDateTime(row.updatedAt) : '—' }}</td>
              <td>
                <button
                  v-if="row.state !== 'succeeded'" type="button" class="refresh-btn"
                  :data-action="`retry-exit-operation`" @click="openRetry(row)"
                >重排</button>
                <span v-else class="gl-hint">已收口</span>
              </td>
            </tr>
            <tr v-if="rows.length === 0"><td colspan="8" class="td-empty">暂无退出资金操作</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <GlModal v-if="retryOpen" title="重排退出资金操作" persistent @close="retryOpen = false">
      <p v-if="retryTarget">
        将按<b>原经济键</b>重新排队「{{ KIND_LABELS[retryTarget.kind] || retryTarget.kind }}」操作
        <span class="gl-num">{{ retryTarget.operationId.slice(0, 16) }}…</span>；
        不接受金额或收款方修改。当前版本 {{ retryTarget.version }}。
      </p>
      <label class="gl-field-label">重排理由（必填，5–500 字）
        <textarea v-model="retryReason" rows="3" maxlength="500" data-testid="exit-retry-reason"
                  placeholder="说明核对结论，如：Finance 事实已回读确认，恢复排队" />
      </label>
      <p v-if="retryError" class="gl-hint" role="alert">{{ retryError }}</p>
      <template #actions>
        <button type="button" class="secondary" :disabled="retrySubmitting" @click="retryOpen = false">取消</button>
        <button type="button" class="gl-btn-primary" :disabled="retrySubmitting" data-testid="exit-retry-submit"
                @click="submitRetry">确认重排</button>
      </template>
    </GlModal>
  </section>
</template>

<style scoped src="../admin-shared.css"></style>

<style scoped>
.exit-recovery { margin-top: var(--space-lg); }
.toolbar-actions { display: flex; align-items: center; gap: var(--space-sm); }
.toolbar-actions label { display: flex; align-items: center; gap: var(--space-xs); font-size: var(--type-caption); color: var(--color-text-secondary); }
.toolbar-actions select { min-height: var(--control-height); padding: 4px var(--space-sm); border: 1px solid var(--color-border-control); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); font: inherit; }
.legs-cell { max-width: 260px; }
.leg-line { overflow-wrap: anywhere; }
</style>
