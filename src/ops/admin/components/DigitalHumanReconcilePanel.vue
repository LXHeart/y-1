<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { formatYuan } from '../../../lib/money'
import { formatDateTime } from '../admin-format'
import {
  isReconcileFormSubmittable, type DhInvocationRow, type DhReconcileForm,
} from '../composables/useDigitalHumanAdmin'

/**
 * 数字人 unknown 调用人工核对（任务书 #105G C105G-04 / K10 ADMIN05-06）。
 *
 * 队列固定为 unknown / 结算未收口调用；unknown 一律以「需人工核对」警示呈现，绝不
 * 显示为正常。核对必须携带第三方证据（无证据禁提交）；金额不可填写——服务端按冻结
 * 价表与确认用量结算，本面板只读展示实际消耗。提交锁同调用（重复点击只发一次请求），
 * 待完成期间显示「核对中」而非成功，成功后以服务端回读为准（tc105g_04_03）。
 */
const props = defineProps<{
  invocations: DhInvocationRow[]
  loading: boolean
  error: string | null
  reconciling: string | null
  actionError: string | null
  lastReconciled: DhInvocationRow | null
}>()

const emit = defineEmits<{
  submit: [invocationId: string, expectedVersion: number, form: DhReconcileForm]
  refresh: []
}>()

const selectedId = ref<string | null>(null)
const outcome = ref<'succeeded' | 'failed'>('succeeded')
const providerEvidenceRef = ref('')
const reason = ref('')
/** 用量草稿恒为对象（表单可编辑态）；'' 是 v-model.number 的空输入，提交前归一为 null。 */
type UsageInput = number | '' | null
const usageDraft = ref<Record<'inputTokens' | 'outputTokens' | 'audioInputMs' | 'audioOutputMs' | 'textCodePoints' | 'renderMs', UsageInput>>({
  inputTokens: null, outputTokens: null, audioInputMs: null, audioOutputMs: null, textCodePoints: null, renderMs: null,
})

const selected = computed(() => props.invocations.find((row) => row.id === selectedId.value) ?? null)
const inFlight = computed(() => selectedId.value != null && props.reconciling === selectedId.value)

/** 空输入归一为 null（K08 未计量不是 0）；failed 不携带用量（K10）。 */
const normalizedForm = computed<DhReconcileForm>(() => ({
  outcome: outcome.value,
  providerEvidenceRef: providerEvidenceRef.value,
  reason: reason.value,
  confirmedUsage: outcome.value === 'failed' ? null
    : Object.fromEntries(Object.entries(usageDraft.value)
      .map(([key, value]) => [key, typeof value === 'number' && Number.isFinite(value) ? value : null])) as NonNullable<DhReconcileForm['confirmedUsage']>,
}))
const submittable = computed(() => !inFlight.value && props.reconciling == null
  && selected.value != null && isReconcileFormSubmittable(normalizedForm.value))

const USAGE_FIELDS = [
  { key: 'inputTokens', label: '输入 tokens' },
  { key: 'outputTokens', label: '输出 tokens' },
  { key: 'audioInputMs', label: '音频输入 ms' },
  { key: 'audioOutputMs', label: '音频输出 ms' },
  { key: 'textCodePoints', label: '文本码点' },
  { key: 'renderMs', label: '渲染 ms' },
] as const

function openForm(row: DhInvocationRow): void {
  selectedId.value = selectedId.value === row.id ? null : row.id
  outcome.value = 'succeeded'
  providerEvidenceRef.value = ''
  reason.value = ''
  usageDraft.value = { inputTokens: null, outputTokens: null, audioInputMs: null, audioOutputMs: null, textCodePoints: null, renderMs: null }
}

function submit(): void {
  if (selected.value == null || !submittable.value) return
  emit('submit', selected.value.id, selected.value.version, normalizedForm.value)
}

// 选中行从队列消失（核对收口/刷新）→ 收起表单，不残留下一行的版本号。
watch(() => props.invocations, (rows) => {
  if (selectedId.value != null && !rows.some((row) => row.id === selectedId.value)) selectedId.value = null
})
</script>

<template>
  <section class="dh-reconcile" data-test="dh-reconcile" aria-label="调用核对">
    <div class="panel-toolbar">
      <div>
        <h3>待核对调用（unknown / 结算未收口）</h3>
        <p>仅凭确凿的第三方证据按原结果结算；金额由服务端按冻结价表计算，治理面不可填写。</p>
      </div>
      <button type="button" class="refresh-btn" data-test="dh-reconcile-refresh" :disabled="loading"
        @click="emit('refresh')">刷新</button>
    </div>

    <p v-if="error" class="error-msg" role="alert" data-test="dh-reconcile-error">{{ error }}</p>
    <p v-if="actionError" class="error-msg" role="alert" data-test="dh-reconcile-action-error">{{ actionError }}</p>
    <p v-if="lastReconciled" class="dh-notice" role="status" data-test="dh-reconcile-result">
      服务端回读：调用 {{ lastReconciled.id }} → 状态 {{ lastReconciled.state }}、结算 {{ lastReconciled.settlementState }}
      <template v-if="lastReconciled.confirmedCents != null">
        （实际消耗 {{ formatYuan(lastReconciled.confirmedCents) }}）
      </template>
    </p>
    <p v-if="loading && invocations.length === 0" class="loading-state" data-test="dh-reconcile-loading">正在读取核对队列…</p>
    <p v-else-if="!loading && invocations.length === 0" class="loading-state" data-test="dh-reconcile-empty">
      队列为空：没有待人工核对的调用。
    </p>

    <div v-if="invocations.length > 0" class="table-scroll">
      <table class="user-table dh-invocation-grid" data-test="dh-reconcile-table">
        <thead>
          <tr>
            <th>调用 ID</th>
            <th>阶段</th>
            <th>状态</th>
            <th>结算</th>
            <th class="num">版本</th>
            <th>供应商模型</th>
            <th>创建时间</th>
            <th class="num">实际消耗</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in invocations" :key="row.id" data-test="dh-reconcile-row">
            <td class="id-cell">{{ row.id }}</td>
            <td>{{ row.stage }}</td>
            <td>
              <span v-if="row.state === 'unknown'" class="type-tag dh-tag-warn" data-test="dh-unknown-badge">
                未知 · 需人工核对
              </span>
              <span v-else class="type-tag">{{ row.state }}</span>
            </td>
            <td>
              <span class="type-tag" :class="{ 'dh-tag-warn': row.settlementState === 'pending' || row.settlementState === 'failed' }">
                {{ row.settlementState }}
              </span>
            </td>
            <td class="num">{{ row.version }}</td>
            <td class="td-muted">{{ row.providerModelLabel }}</td>
            <td class="td-time">{{ formatDateTime(row.createdAt) }}</td>
            <td class="num">{{ row.confirmedCents == null ? '—' : formatYuan(row.confirmedCents) }}</td>
            <td>
              <button type="button" class="approve-btn" :data-test="`dh-reconcile-open-${row.id}`"
                :disabled="reconciling != null" @click="openForm(row)">
                {{ reconciling === row.id ? '核对中…' : '核对' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <form v-if="selected" class="dh-form table-card" data-test="dh-reconcile-form" @submit.prevent="submit">
      <h4>核对 {{ selected.id }}（基于版本 {{ selected.version }}）</h4>
      <p class="td-muted" data-test="dh-reconcile-hint">
        依据供应商后台/账单等确凿证据选择结果；金额不可填写——服务端按调用时冻结价表与下列确认用量结算。
      </p>

      <fieldset class="dh-toggle-list">
        <legend>核对结果</legend>
        <label class="dh-check">
          <input type="radio" value="succeeded" data-test="dh-outcome-succeeded" v-model="outcome" />
          <span>实际成功（按确认用量结算）</span>
        </label>
        <label class="dh-check">
          <input type="radio" value="failed" data-test="dh-outcome-failed" v-model="outcome" />
          <span>实际失败（按失败补偿处理，不携带用量）</span>
        </label>
      </fieldset>

      <label class="dh-field">
        <span>第三方证据引用（必填，如供应商请求 ID / 工单号）</span>
        <input class="field-input" type="text" data-test="dh-evidence" v-model="providerEvidenceRef"
          maxlength="128" placeholder="provider-req-…" />
      </label>

      <template v-if="outcome === 'succeeded'">
        <div class="dh-field-grid">
          <label v-for="field in USAGE_FIELDS" :key="field.key" class="dh-field">
            <span>{{ field.label }}（未计量的项留空，不填 0）</span>
            <input class="field-input" type="number" min="0" :data-test="`dh-usage-${field.key}`"
              v-model.number="usageDraft[field.key]" />
          </label>
        </div>
      </template>

      <label class="dh-field">
        <span>核对原因（必填，写入治理审计）</span>
        <textarea class="field-input field-textarea" data-test="dh-reconcile-reason" v-model="reason"
          maxlength="200" placeholder="例如：供应商后台确认该请求成功"></textarea>
      </label>

      <div class="dh-form-actions">
        <button type="submit" class="approve-btn" data-test="dh-reconcile-submit" :disabled="!submittable">
          {{ inFlight ? '核对中…' : '提交核对' }}
        </button>
        <button type="button" class="refresh-btn" data-test="dh-reconcile-cancel" :disabled="inFlight"
          @click="selectedId = null">收起</button>
        <span v-if="inFlight" class="dh-pending" data-test="dh-reconcile-pending">请求处理中，结果以服务端回读为准…</span>
      </div>
    </form>
  </section>
</template>

<style scoped src="../admin-shared.css"></style>
<style scoped>
.dh-reconcile { display: grid; gap: var(--space-sm); }
.dh-invocation-grid { min-width: 980px; }
.dh-form h4 { margin: 0; font-size: var(--type-body-sm); }
</style>
