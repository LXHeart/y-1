<template>
  <section class="audit-console">
    <header class="panel-head">
      <div><h3>统一审计</h3><p>跨身份、审核、运营和信任域追查不可变操作记录</p></div>
      <span class="result-count">{{ rows.length }} 条</span>
    </header>

    <form class="filters" @submit.prevent="query">
      <label>审计域
        <select v-model="filters.source" @change="resetResults">
          <option v-for="option in sources" :key="option.value" :value="option.value">{{ option.label }}</option>
        </select>
      </label>
      <label v-if="filters.source !== 'evidence_access'">{{ currentSource.resourceLabel }}
        <input v-model.trim="filters.resourceId" :placeholder="currentSource.placeholder" />
      </label>
      <template v-else>
        <label>争议 ID<input v-model.trim="filters.resourceId" placeholder="dispute UUID（可选）" /></label>
        <label>证据 ID<input v-model.trim="filters.evidenceId" placeholder="evidence UUID（可选）" /></label>
      </template>
      <label>操作人 ID<input v-model.trim="filters.actorId" placeholder="account UUID（可选）" /></label>
      <label v-if="filters.source === 'evidence_access'">操作人角色
        <select v-model="filters.actorRole">
          <option value="">全部</option><option value="judge">审判官</option>
          <option value="customer_service">客服</option><option value="platform_admin">平台管理员</option>
        </select>
      </label>
      <label>起始时间<input v-model="filters.from" type="datetime-local" /></label>
      <label>结束时间<input v-model="filters.to" type="datetime-local" /></label>
      <label>动作筛选<input v-model.trim="filters.action" placeholder="动作或备注关键词" /></label>
      <button type="submit" :disabled="loading">{{ loading ? '查询中...' : '查询' }}</button>
    </form>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div class="table-wrap">
      <table class="audit-table">
        <thead><tr><th>时间 / 审计域</th><th>动作</th><th>资源</th><th>操作人</th><th>变更与备注</th></tr></thead>
        <tbody>
          <tr v-for="row in filteredRows" :key="`${row.source}-${row.id}`">
            <td><time>{{ formatDate(row.occurredAt) }}</time><span class="source">{{ sourceLabel(row.source) }}</span></td>
            <td><strong>{{ actionLabel(row.action) }}</strong><code>{{ row.action }}</code></td>
            <td><span>{{ row.resourceType }}</span><code :title="row.resourceId">{{ compact(row.resourceId) }}</code></td>
            <td><span>{{ roleLabel(row.actorRole) }}</span><code :title="row.actorId || ''">{{ compact(row.actorId) || 'system' }}</code></td>
            <td><span v-if="row.transition" class="transition">{{ row.transition }}</span><p>{{ row.detail || '-' }}</p></td>
          </tr>
          <tr v-if="!loading && filteredRows.length === 0"><td colspan="5" class="empty">暂无审计记录</td></tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import type {
  DisputeAudit, EvidenceAccessAudit, IdentityAdminAudit, OpsCaseAudit,
  PermissionRequestAudit, TaskReviewAudit, UnifiedAuditSource,
} from '../types/grassland'

interface AuditRow {
  id: string
  source: UnifiedAuditSource
  resourceType: string
  resourceId: string
  action: string
  actorId: string | null
  actorRole: string | null
  transition: string | null
  detail: string | null
  occurredAt: string | null
}

const sources: Array<{ value: UnifiedAuditSource; label: string; resourceLabel: string; placeholder: string }> = [
  { value: 'identity', label: '账号身份', resourceLabel: '账号 ID', placeholder: 'account UUID' },
  { value: 'permission', label: '权限申请', resourceLabel: '申请 ID', placeholder: 'permission request UUID' },
  { value: 'task_review', label: '任务审核', resourceLabel: '任务 ID', placeholder: 'task UUID' },
  { value: 'ops_case', label: '运营处置', resourceLabel: '处置单 ID', placeholder: 'ops case UUID' },
  { value: 'dispute', label: '争议流转', resourceLabel: '争议 ID', placeholder: 'dispute UUID' },
  { value: 'evidence_access', label: '证据访问', resourceLabel: '争议 ID', placeholder: 'dispute UUID（可选）' },
]

const grassland = useGrassland()
const filters = reactive({
  source: 'identity' as UnifiedAuditSource,
  resourceId: '', evidenceId: '', actorId: '', actorRole: '', from: '', to: '', action: '',
})
const rows = ref<AuditRow[]>([])
const loading = ref(false)
const error = ref('')
const currentSource = computed(() => sources.find(item => item.value === filters.source) || sources[0])
const filteredRows = computed(() => rows.value.filter((row) => {
  if (filters.source !== 'evidence_access' && filters.actorId && row.actorId !== filters.actorId) return false
  if (filters.from && timestamp(row.occurredAt) < timestamp(filters.from)) return false
  if (filters.to && timestamp(row.occurredAt) > timestamp(filters.to)) return false
  const needle = filters.action.toLowerCase()
  return !needle || `${row.action} ${row.detail || ''} ${row.transition || ''}`.toLowerCase().includes(needle)
}))

function resetResults(): void {
  rows.value = []
  error.value = ''
  filters.resourceId = ''
  filters.evidenceId = ''
  filters.actorRole = ''
}

async function query(): Promise<void> {
  if (filters.source !== 'evidence_access' && !filters.resourceId) {
    error.value = `请输入${currentSource.value.resourceLabel}`
    return
  }
  if (filters.from && filters.to && timestamp(filters.from) > timestamp(filters.to)) {
    error.value = '起始时间不能晚于结束时间'
    return
  }
  loading.value = true
  error.value = ''
  let next: AuditRow[] | null
  if (filters.source === 'identity') {
    const data = await grassland.listAdminUserAudit(filters.resourceId)
    next = data?.map(normalizeIdentity) ?? null
  } else if (filters.source === 'permission') {
    const data = await grassland.listPermissionRequestAudit(filters.resourceId)
    next = data?.map(item => normalizePermission(item, filters.resourceId)) ?? null
  } else if (filters.source === 'task_review') {
    const data = await grassland.listTaskReviewAudit(filters.resourceId)
    next = data?.map(normalizeTaskReview) ?? null
  } else if (filters.source === 'ops_case') {
    const data = await grassland.getOpsCase(filters.resourceId)
    next = data?.audits.map(item => normalizeOpsCase(item, filters.resourceId)) ?? null
  } else if (filters.source === 'dispute') {
    const data = await grassland.listDisputeAudit(filters.resourceId)
    next = data?.map(normalizeDispute) ?? null
  } else {
    const data = await grassland.listEvidenceAccessAudit({
      disputeId: filters.resourceId || undefined,
      evidenceId: filters.evidenceId || undefined,
      viewerAccountId: filters.actorId || undefined,
      viewerRole: filters.actorRole || undefined,
      from: toIso(filters.from), to: toIso(filters.to), limit: 100,
    })
    next = data?.map(normalizeEvidenceAccess) ?? null
  }
  loading.value = false
  if (next === null) {
    rows.value = []
    error.value = grassland.error.value || '审计记录查询失败'
    return
  }
  rows.value = next.sort((a, b) => timestamp(b.occurredAt) - timestamp(a.occurredAt))
}

function base(source: UnifiedAuditSource, id: string | number, resourceType: string, resourceId: string,
  action: string, actorId: string | null, actorRole: string | null, transition: string | null,
  detail: string | null, occurredAt: string | null): AuditRow {
  return { source, id: String(id), resourceType, resourceId, action, actorId, actorRole, transition, detail, occurredAt }
}
function transition(from: string | null, to: string | null): string | null {
  return from || to ? `${from || '-'} -> ${to || '-'}` : null
}
function normalizeIdentity(item: IdentityAdminAudit): AuditRow {
  const detail = [item.deviceId ? `设备 ${item.deviceId}` : '', item.ipAddress ? `IP ${item.ipAddress}` : ''].filter(Boolean).join(' · ')
  return base('identity', item.id, 'account', item.accountId, item.action, item.accountId, 'account',
    transition(item.fromIdentityType, item.toIdentityType), detail || null, item.occurredAt)
}
function normalizePermission(item: PermissionRequestAudit, requestId: string): AuditRow {
  return base('permission', item.id, 'permission_request', requestId, item.action, item.actorAccountId,
    item.actorKind, transition(item.fromStatus, item.toStatus), item.details, item.createdAt)
}
function normalizeTaskReview(item: TaskReviewAudit): AuditRow {
  return base('task_review', item.id, 'task', item.taskId, item.action, item.reviewerAccountId,
    'content_reviewer', null, item.note, item.createdAt)
}
function normalizeOpsCase(item: OpsCaseAudit, caseId: string): AuditRow {
  return base('ops_case', item.id, 'ops_case', caseId, item.action, item.actorAccountId,
    item.actorRole, transition(item.fromStatus, item.toStatus), item.note, item.createdAt)
}
function normalizeDispute(item: DisputeAudit): AuditRow {
  return base('dispute', item.id, 'dispute', item.disputeId, item.action, item.actorAccountId,
    item.actorRole, null, item.note, item.createdAt)
}
function normalizeEvidenceAccess(item: EvidenceAccessAudit): AuditRow {
  return base('evidence_access', item.id, 'evidence', item.evidenceId, 'viewed', item.viewerAccountId,
    item.viewerRole, null, `${item.purpose} · 争议 ${compact(item.disputeId)}`, item.viewedAt)
}

function toIso(value: string): string | undefined { return value ? new Date(value).toISOString() : undefined }
function timestamp(value: string | null): number { return value ? new Date(value).getTime() : 0 }
function compact(value: string | null): string { return value ? (value.length > 22 ? `${value.slice(0, 10)}...${value.slice(-8)}` : value) : '' }
function formatDate(value: string | null): string { return value ? new Date(value).toLocaleString('zh-CN') : '-' }
function sourceLabel(source: UnifiedAuditSource): string { return sources.find(item => item.value === source)?.label || source }
function roleLabel(role: string | null): string {
  return ({ account: '账号本人', admin: '管理员', system: '系统', content_reviewer: '内容审核员',
    customer_service: '客服', platform_admin: '平台管理员', judge: '审判官', merchant: '商家' } as Record<string, string>)[role || ''] || role || '系统'
}
function actionLabel(action: string): string {
  return ({ viewed: '查看证据', approved: '审核通过', rejected: '审核驳回', claimed: '领取审核',
    opened: '创建争议', submitted: '提交处置', resolved: '完成处置', activated: '切换身份' } as Record<string, string>)[action] || action
}
</script>

<style scoped>
.audit-console { display: grid; gap: 16px; }
.panel-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.panel-head h3, .panel-head p { margin: 0; }.panel-head h3 { font-size: 1rem; }.panel-head p { margin-top: 4px; color: var(--color-text-muted); font-size: .82rem; }
.result-count { color: var(--color-text-muted); font-size: .78rem; white-space: nowrap; }
button, select, input { font: inherit; letter-spacing: 0; }
.filters { display: grid; grid-template-columns: repeat(4, minmax(150px, 1fr)) auto; gap: 10px; align-items: end; }
.filters label { display: grid; gap: 5px; color: var(--color-text-muted); font-size: .75rem; }
select, input { box-sizing: border-box; width: 100%; min-height: 36px; border: 1px solid var(--color-border); border-radius: 6px; background: var(--color-surface); color: var(--color-text); padding: 7px 9px; }
button { min-height: 36px; padding: 0 16px; border: 1px solid var(--color-accent); border-radius: 6px; background: var(--color-accent); color: var(--color-on-accent); cursor: pointer; }button:disabled { opacity: .5; cursor: not-allowed; }
.error { margin: 0; padding: 9px 12px; border: 1px solid color-mix(in srgb, var(--color-danger) 30%, transparent); color: var(--color-danger); }
.table-wrap { overflow-x: auto; border: 1px solid var(--color-border); border-radius: 8px; }
.audit-table { width: 100%; min-width: 900px; border-collapse: collapse; table-layout: fixed; font-size: .78rem; }
.audit-table th { padding: 9px 11px; text-align: left; color: var(--color-text-muted); background: var(--surface-muted); font-weight: 600; }
.audit-table td { padding: 11px; border-top: 1px solid var(--color-border); vertical-align: top; overflow-wrap: anywhere; }
.audit-table th:nth-child(1) { width: 18%; }.audit-table th:nth-child(2) { width: 15%; }.audit-table th:nth-child(3) { width: 18%; }.audit-table th:nth-child(4) { width: 18%; }
.audit-table td > span, .audit-table td > code, .audit-table td > strong, .audit-table td > time { display: block; }
.audit-table code { margin-top: 3px; color: var(--color-text-muted); font-size: .7rem; }.audit-table p { margin: 5px 0 0; color: var(--color-text-secondary); }
.source { width: fit-content; margin-top: 5px; padding: 2px 5px; border-radius: 4px; background: color-mix(in srgb, var(--color-accent) 12%, var(--color-surface)); color: var(--color-accent); font-size: .68rem; }
.transition { font-family: ui-monospace, monospace; color: var(--color-text); }.empty { padding: 36px !important; text-align: center; color: var(--color-text-muted); }
@media (max-width: 900px) { .filters { grid-template-columns: 1fr 1fr; }.filters button { width: 100%; } }
@media (max-width: 560px) { .panel-head { align-items: flex-start; }.filters { grid-template-columns: 1fr; } }
</style>
