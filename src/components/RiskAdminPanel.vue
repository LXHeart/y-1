<template>
  <section class="risk-console">
    <header class="panel-head">
      <div><h3>风险调查</h3><p>按主体和严重度调查信号，所有处置动作写入审计时间线</p></div>
      <button type="button" :disabled="loading" @click="loadCases">刷新</button>
    </header>

    <div class="filters">
      <label>状态
        <select v-model="filters.status"><option value="">全部</option><option value="open">待调查</option><option value="in_review">调查中</option><option value="resolved">已解决</option><option value="dismissed">已排除</option></select>
      </label>
      <label>严重度
        <select v-model="filters.severity"><option value="">全部</option><option value="critical">紧急</option><option value="high">高</option><option value="medium">中</option><option value="low">低</option></select>
      </label>
      <label>主体类型<input v-model.trim="filters.subjectKind" placeholder="account / task / order" /></label>
      <label>主体标识<input v-model.trim="filters.subjectRef" placeholder="账号或业务 ID" @keyup.enter="loadCases" /></label>
      <button type="button" :disabled="loading" @click="loadCases">查询</button>
    </div>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div class="workspace">
      <div class="case-list" aria-label="风险案件列表">
        <div class="list-head"><strong>案件队列</strong><span>{{ cases.length }} 条</span></div>
        <button v-for="item in cases" :key="item.id" type="button" class="case-row"
          :class="{ selected: selectedId === item.id }" @click="selectCase(item.id)">
          <span class="severity" :data-severity="item.severity">{{ severityLabel(item.severity) }}</span>
          <strong>{{ item.subjectKind }} · {{ compact(item.subjectRef) }}</strong>
          <span>{{ statusLabel(item.status) }} · {{ item.score }} 分</span>
          <time>{{ formatDate(item.updatedAt) }}</time>
        </button>
        <p v-if="!loading && cases.length === 0" class="empty">没有符合条件的案件</p>
      </div>

      <div class="case-detail" aria-live="polite">
        <p v-if="detailLoading" class="empty">正在加载案件...</p>
        <p v-else-if="!detail" class="empty">从左侧选择案件查看证据与处置记录</p>
        <template v-else>
          <header class="detail-head">
            <div><span class="severity" :data-severity="detail.case.severity">{{ severityLabel(detail.case.severity) }}</span><h4>{{ detail.case.subjectKind }} · {{ detail.case.subjectRef }}</h4></div>
            <span class="status">{{ statusLabel(detail.case.status) }}</span>
          </header>
          <dl class="summary">
            <div><dt>风险分</dt><dd>{{ detail.case.score }}</dd></div>
            <div><dt>归属组织</dt><dd>{{ detail.case.organizationId || '平台级' }}</dd></div>
            <div><dt>调查人</dt><dd>{{ compact(detail.case.assignedTo) || '未分配' }}</dd></div>
            <div><dt>更新时间</dt><dd>{{ formatDate(detail.case.updatedAt) }}</dd></div>
          </dl>
          <div class="reason"><strong>立案原因</strong><p>{{ detail.case.reason }}</p></div>

          <section class="detail-section">
            <h5>关联信号</h5>
            <div v-for="signal in detail.signals" :key="signal.id" class="signal-row">
              <div><strong>{{ signal.ruleCode }}</strong><span>{{ signal.sourceKind }} · {{ compact(signal.sourceRef) }} · {{ signal.score }} 分</span></div>
              <pre>{{ formatEvidence(signal.evidence) }}</pre>
            </div>
            <p v-if="detail.signals.length === 0" class="empty">暂无关联信号</p>
          </section>

          <section class="detail-section">
            <h5>审计时间线</h5>
            <ol class="timeline">
              <li v-for="audit in detail.audits" :key="audit.id"><span></span><div><strong>{{ actionLabel(audit.action) }}</strong><p>{{ audit.note || '无备注' }}</p><time>{{ formatDate(audit.createdAt) }} · {{ audit.actorRole }}</time></div></li>
            </ol>
          </section>

          <div class="action-bar">
            <textarea v-model="actionNote" rows="2" maxlength="500" placeholder="处置备注（解决或排除时建议填写）" />
            <div>
              <button v-if="detail.case.status === 'open'" type="button" :disabled="acting" @click="act('start_review')">开始调查</button>
              <template v-if="detail.case.status === 'open' || detail.case.status === 'in_review'">
                <button type="button" :disabled="acting" class="resolve" @click="act('resolve')">解决</button>
                <button type="button" :disabled="acting" class="dismiss" @click="act('dismiss')">排除</button>
              </template>
              <button v-else type="button" :disabled="acting" @click="act('reopen')">重新打开</button>
            </div>
          </div>
        </template>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import type { RiskCase, RiskCaseAction, RiskCaseDetail, RiskSeverity } from '../types/grassland'

const grassland = useGrassland()
const filters = reactive({ status: 'open', severity: '', subjectKind: '', subjectRef: '' })
const cases = ref<RiskCase[]>([])
const detail = ref<RiskCaseDetail | null>(null)
const selectedId = ref('')
const loading = ref(false)
const detailLoading = ref(false)
const acting = ref(false)
const error = ref('')
const actionNote = ref('')

onMounted(() => void loadCases())

async function loadCases(): Promise<void> {
  loading.value = true
  error.value = ''
  const result = await grassland.listRiskCases({ ...filters, limit: 100 })
  loading.value = false
  if (!result) { error.value = grassland.error.value || '风险案件加载失败'; return }
  cases.value = result
  if (selectedId.value && !result.some(item => item.id === selectedId.value)) {
    selectedId.value = ''
    detail.value = null
  }
  if (!selectedId.value && result[0]) await selectCase(result[0].id)
}

async function selectCase(id: string): Promise<void> {
  selectedId.value = id
  detailLoading.value = true
  error.value = ''
  const result = await grassland.getRiskCase(id)
  detailLoading.value = false
  if (selectedId.value !== id) return
  if (result) detail.value = result
  else error.value = grassland.error.value || '案件详情加载失败'
}

async function act(action: RiskCaseAction): Promise<void> {
  if (!detail.value) return
  if ((action === 'resolve' || action === 'dismiss') && !actionNote.value.trim()) {
    error.value = '解决或排除案件前请填写处置备注'
    return
  }
  acting.value = true
  error.value = ''
  const updated = await grassland.actOnRiskCase(detail.value.case.id, action, actionNote.value.trim())
  acting.value = false
  if (!updated) { error.value = grassland.error.value || '案件处置失败'; return }
  actionNote.value = ''
  await Promise.all([loadCases(), selectCase(updated.id)])
}

function compact(value: string | null): string { return value ? (value.length > 18 ? `${value.slice(0, 8)}...${value.slice(-6)}` : value) : '' }
function formatDate(value: string | null): string { return value ? new Date(value).toLocaleString() : '-' }
function formatEvidence(value: Record<string, unknown>): string { return JSON.stringify(value, null, 2) }
function severityLabel(value: RiskSeverity): string { return ({ low: '低', medium: '中', high: '高', critical: '紧急' })[value] }
function statusLabel(value: string): string { return ({ open: '待调查', in_review: '调查中', resolved: '已解决', dismissed: '已排除' } as Record<string, string>)[value] || value }
function actionLabel(value: string): string { return ({ signal_attached: '信号入案', start_review: '开始调查', resolve: '案件解决', dismiss: '排除风险', reopen: '重新打开' } as Record<string, string>)[value] || value }
</script>

<style scoped>
.risk-console { display: grid; gap: 16px; }
.panel-head, .list-head, .detail-head, .action-bar, .action-bar > div { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.panel-head h3, .panel-head p, .detail-head h4, .reason p { margin: 0; }
.panel-head h3 { font-size: 1rem; }
.panel-head p { margin-top: 4px; color: var(--color-text-muted); font-size: .82rem; }
button, select, input, textarea { font: inherit; letter-spacing: 0; }
button { min-height: 34px; padding: 0 12px; border: 1px solid var(--color-border); border-radius: 6px; background: var(--color-surface); color: var(--color-text); cursor: pointer; }
button:disabled { opacity: .5; cursor: not-allowed; }
.filters { display: grid; grid-template-columns: 150px 130px 1fr 1fr auto; gap: 10px; align-items: end; }
.filters label { display: grid; gap: 5px; color: var(--color-text-muted); font-size: .75rem; }
select, input, textarea { box-sizing: border-box; width: 100%; border: 1px solid var(--color-border); border-radius: 6px; background: var(--color-surface); color: var(--color-text); padding: 8px 10px; }
.workspace { display: grid; grid-template-columns: minmax(260px, .72fr) minmax(0, 1.6fr); min-height: 560px; border: 1px solid var(--color-border); border-radius: 8px; overflow: hidden; }
.case-list { border-right: 1px solid var(--color-border); background: var(--color-surface); }
.list-head { padding: 12px 14px; border-bottom: 1px solid var(--color-border); }
.list-head span { color: var(--color-text-muted); font-size: .78rem; }
.case-row { width: 100%; min-height: 88px; display: grid; grid-template-columns: auto 1fr; justify-content: initial; gap: 5px 9px; padding: 12px 14px; border: 0; border-bottom: 1px solid var(--color-border); border-radius: 0; text-align: left; }
.case-row.selected { background: color-mix(in srgb, var(--color-accent) 9%, var(--color-surface)); box-shadow: inset 3px 0 var(--color-accent); }
.case-row strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.case-row > span:not(.severity), .case-row time { grid-column: 2; color: var(--color-text-muted); font-size: .75rem; }
.severity { width: fit-content; align-self: start; padding: 2px 6px; border-radius: 4px; color: #fff; font-size: .7rem; font-weight: 700; background: #64748b; }
.severity[data-severity="medium"] { background: #b7791f; }.severity[data-severity="high"] { background: #c2410c; }.severity[data-severity="critical"] { background: #b91c1c; }
.case-detail { min-width: 0; padding: 18px; background: var(--color-surface); overflow: auto; }
.detail-head > div { display: flex; align-items: center; gap: 9px; min-width: 0; }.detail-head h4 { overflow-wrap: anywhere; font-size: .95rem; }
.status { padding: 3px 8px; border: 1px solid var(--color-border); border-radius: 4px; white-space: nowrap; font-size: .75rem; }
.summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin: 18px 0; }
.summary dt { color: var(--color-text-muted); font-size: .72rem; }.summary dd { margin: 4px 0 0; overflow-wrap: anywhere; }
.reason { padding: 12px 0; border-block: 1px solid var(--color-border); }.reason p { margin-top: 6px; color: var(--color-text-secondary); }
.detail-section { margin-top: 18px; }.detail-section h5 { margin: 0 0 10px; font-size: .86rem; }
.signal-row { display: grid; grid-template-columns: minmax(150px, .4fr) 1fr; gap: 12px; padding: 10px 0; border-top: 1px solid var(--color-border); }.signal-row > div { display: grid; gap: 4px; align-content: start; }.signal-row span { color: var(--color-text-muted); font-size: .75rem; }.signal-row pre { margin: 0; max-height: 130px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font-size: .72rem; }
.timeline { margin: 0; padding: 0; list-style: none; }.timeline li { display: grid; grid-template-columns: 10px 1fr; gap: 9px; padding: 7px 0; }.timeline li > span { width: 7px; height: 7px; margin-top: 6px; border-radius: 50%; background: var(--color-accent); }.timeline p, .timeline time { margin: 2px 0 0; color: var(--color-text-muted); font-size: .75rem; }
.action-bar { align-items: end; margin-top: 18px; padding-top: 14px; border-top: 1px solid var(--color-border); }.action-bar textarea { min-height: 60px; resize: vertical; }.action-bar > div { flex: 0 0 auto; justify-content: flex-end; }.action-bar .resolve { border-color: #15803d; color: #15803d; }.action-bar .dismiss { border-color: #b91c1c; color: #b91c1c; }
.error { margin: 0; padding: 9px 12px; border: 1px solid color-mix(in srgb, var(--color-danger) 30%, transparent); color: var(--color-danger); }.empty { margin: 0; padding: 24px; text-align: center; color: var(--color-text-muted); }
@media (max-width: 850px) { .filters { grid-template-columns: 1fr 1fr; }.workspace { grid-template-columns: 1fr; }.case-list { border-right: 0; border-bottom: 1px solid var(--color-border); max-height: 360px; overflow: auto; }.summary { grid-template-columns: 1fr 1fr; }.signal-row { grid-template-columns: 1fr; }.action-bar { align-items: stretch; flex-direction: column; } }
</style>
