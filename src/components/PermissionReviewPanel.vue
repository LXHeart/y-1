<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import { parsePermissionMaterials } from '../types/grassland'
import type { MaterialType, PermissionRequest, PermissionRequestAudit, PermissionTier, ReviewDecision } from '../types/grassland'

/**
 * 平台侧商家权限审核队列（HLD D-05 的审核侧，Slice 2H）。
 *
 * 后端门禁以 identity `backend_role=platform_admin` 为唯一授权权威，非 admin 调用 403。
 * 本组件由 `GrasslandWorkbench` 按 `useAuth().currentUser.role` 决定是否挂载——
 * 但那只是**不给非 admin 看**，真正的授权在服务端。
 *
 * 批准 → 同时升级 org 的 `permission_tier` 并写 outbox `MerchantPermissionGranted`；
 * 驳回 → tier 不变，商家可据 `reviewNote` 补正后申诉。终态再审 409。
 */

const emit = defineEmits<{ reviewed: [] }>()

const grassland = useGrassland()

const queue = ref<PermissionRequest[]>([])
const notice = ref('')
/** 每条申请的审核备注，key = requestId（不同申请互不串写）。 */
const notes = ref<Record<string, string>>({})
const mfaPasswords = ref<Record<string, string>>({})
const auditRows = ref<Record<string, PermissionRequestAudit[]>>({})
const loaded = ref(false)

const TIER_LABEL: Record<PermissionTier, string> = {
  draft: '草稿',
  basic_publish: '基础发布',
  finance_transaction: '资金交易',
}

const MATERIAL_LABEL: Record<MaterialType, string> = {
  business_license: '营业执照',
  legal_representative: '法定代表人',
  financial_qualification: '财务资质',
  industry_license: '行业许可证',
  contact_info: '联系方式',
}

const SLA_LABEL: Record<string, string> = {
  within: '审核中',
  at_risk: '临近超时',
  overdue: '已超时',
  completed: '已完成',
}

const AUTO_LABEL: Record<string, string> = {
  not_run: '未运行',
  pending: '核验中',
  passed: '自动核验通过',
  failed: '自动核验失败',
  needs_review: '需人工复核',
}

const RISK_LABEL: Record<string, string> = {
  standard: '标准风险',
  elevated: '较高风险',
  high: '高风险',
}

async function refresh(): Promise<void> {
  const list = await grassland.listPendingPermissionRequests()
  if (list) queue.value = list
  loaded.value = true
}

onMounted(refresh)

async function review(req: PermissionRequest, decision: ReviewDecision): Promise<void> {
  notice.value = ''
  const reviewed = await grassland.reviewPermissionRequest(
    req.id, decision, notes.value[req.id]?.trim() || undefined, req.version)
  if (!reviewed) return
  notice.value = decision === 'approve'
    ? `已批准：组织升级为「${TIER_LABEL[req.requestedTier]}」`
    : '已驳回，商家可补正材料后申诉'
  delete notes.value[req.id]
  emit('reviewed')
  await refresh()
}

async function claim(req: PermissionRequest): Promise<void> {
  const claimed = await grassland.claimPermissionRequest(req.id)
  if (!claimed) return
  await refresh()
}

async function reauthenticate(req: PermissionRequest): Promise<void> {
  const password = mfaPasswords.value[req.id]?.trim()
  if (!password) return
  const result = await grassland.reauthenticate(password)
  if (!result) return
  mfaPasswords.value[req.id] = ''
  notice.value = '重认证已完成，可在 10 分钟内执行高风险批准'
}

async function toggleAudit(req: PermissionRequest): Promise<void> {
  if (auditRows.value[req.id]) {
    delete auditRows.value[req.id]
    return
  }
  const rows = await grassland.listPermissionRequestAudit(req.id)
  if (rows) auditRows.value[req.id] = rows
}

function needsReauthentication(req: PermissionRequest): boolean {
  return req.requestedTier === 'finance_transaction' || req.autoReviewStatus === 'failed'
}

/**
 * 文本材料保留兼容；证照附件通过 attachmentIds 固化，OCR 结果由准入自动复核器持续同步。
 *
 * ⚠️ 必须先 `parsePermissionMaterials`：响应里 materials 是 **JSON 字符串**，
 * 直接 `Object.entries` 会把它逐字符展开（浏览器实测踩到过，审核卡片显示成一列单字）。
 */
function materialEntries(req: PermissionRequest): { label: string; value: string }[] {
  return Object.entries(parsePermissionMaterials(req.materials)).map(([k, v]) => ({
    label: MATERIAL_LABEL[k as MaterialType] || k,
    value: v,
  }))
}
</script>

<template>
  <article class="pr">
    <header class="pr-head">
      <h3>平台审核 · 商家权限申请</h3>
      <button type="button" class="pr-quiet" :disabled="grassland.loading.value" @click="refresh">刷新</button>
    </header>

    <p v-if="grassland.error.value" class="pr-alert pr-err" role="alert">{{ grassland.error.value }}</p>
    <p v-if="notice" class="pr-alert pr-ok">{{ notice }}</p>

    <p v-if="loaded && queue.length === 0" class="pr-hint">当前没有待审核的申请。</p>

    <section v-for="req in queue" :key="req.id" class="pr-item">
      <div class="pr-item-head">
        <span class="pr-target">申请升级至 <strong>{{ TIER_LABEL[req.requestedTier] }}</strong></span>
        <span v-if="req.originalRequestId" class="pr-tag">申诉件</span>
        <span class="pr-tag">{{ AUTO_LABEL[req.autoReviewStatus] || req.autoReviewStatus }}</span>
        <span class="pr-tag" :class="{ 'pr-overdue': req.riskLevel === 'high' }">
          {{ RISK_LABEL[req.riskLevel] || req.riskLevel }}
        </span>
        <span class="pr-sla" :class="{ 'pr-overdue': req.slaStatus === 'overdue' }">
          {{ SLA_LABEL[req.slaStatus] || req.slaStatus }}
        </span>
      </div>

      <dl class="pr-meta">
        <div><dt>组织</dt><dd><code>{{ req.organizationId.slice(0, 8) }}…</code></dd></div>
        <div><dt>申请人</dt><dd><code>{{ req.requesterAccountId.slice(0, 8) }}…</code></dd></div>
        <div><dt>行业</dt><dd>{{ req.industry || '—' }}</dd></div>
        <div><dt>审核截止</dt><dd>{{ req.reviewDeadline ? new Date(req.reviewDeadline).toLocaleString() : '—' }}</dd></div>
      </dl>

      <ul class="pr-materials">
        <li v-for="m in materialEntries(req)" :key="m.label">
          <span class="pr-mat-label">{{ m.label }}</span>
          <span class="pr-mat-value">{{ m.value }}</span>
        </li>
      </ul>

      <p v-if="req.appealNote" class="pr-appeal">申诉说明：{{ req.appealNote }}</p>

      <div class="pr-actions">
        <button v-if="req.status === 'pending'" type="button" :disabled="grassland.loading.value" @click="claim(req)">
          领取审核
        </button>
        <button type="button" class="pr-quiet" @click="toggleAudit(req)">
          {{ auditRows[req.id] ? '收起审计' : '查看审计' }}
        </button>
      </div>

      <ol v-if="auditRows[req.id]" class="pr-audit">
        <li v-for="item in auditRows[req.id]" :key="item.id">
          <span>{{ item.action }}</span>
          <time>{{ item.createdAt ? new Date(item.createdAt).toLocaleString() : '—' }}</time>
        </li>
      </ol>

      <div v-if="needsReauthentication(req)" class="pr-actions">
        <input v-model="mfaPasswords[req.id]" type="password" autocomplete="current-password" placeholder="管理员密码" />
        <button type="button" class="pr-quiet" :disabled="!mfaPasswords[req.id]" @click="reauthenticate(req)">
          重认证
        </button>
      </div>

      <div class="pr-actions">
        <input v-model="notes[req.id]" placeholder="审核备注（驳回时建议写明原因）" />
        <button type="button" :disabled="grassland.loading.value || req.autoReviewStatus === 'pending'" @click="review(req, 'approve')">批准</button>
        <button type="button" class="pr-reject" :disabled="grassland.loading.value || !notes[req.id]?.trim()" @click="review(req, 'reject')">驳回</button>
      </div>
    </section>
  </article>
</template>

<style scoped>
.pr { border: 1px solid var(--color-border); border-radius: 10px; padding: 14px; display: flex; flex-direction: column; gap: 12px; }
.pr-head { display: flex; justify-content: space-between; align-items: center; }
.pr-head h3 { margin: 0; font-size: 15px; }
.pr-alert { margin: 0; padding: 7px 11px; border-radius: 6px; font-size: 13px; }
.pr-err { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.pr-ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
.pr-item { display: flex; flex-direction: column; gap: 8px; padding: 10px; border: 1px solid var(--color-border); border-radius: 8px; }
.pr-item-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; }
.pr-tag { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--color-surface-strong); }
.pr-sla { font-size: 11px; opacity: 0.7; margin-left: auto; }
.pr-overdue { color: var(--color-danger); opacity: 1; }
.pr-meta { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 8px; margin: 0; }
.pr-meta div { display: flex; flex-direction: column; gap: 2px; }
.pr-meta dt { font-size: 11px; opacity: 0.6; }
.pr-meta dd { margin: 0; font-size: 12px; }
.pr-materials { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.pr-materials li { display: flex; gap: 8px; font-size: 12px; }
.pr-mat-label { flex: 0 0 88px; opacity: 0.65; }
.pr-mat-value { flex: 1; word-break: break-all; }
.pr-appeal { margin: 0; font-size: 12px; padding: 6px 10px; border-radius: 6px; background: var(--color-surface-strong); }
.pr-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.pr-actions input { flex: 1; min-width: 160px; }
.pr-audit { margin: 0; padding-left: 20px; font-size: 12px; }
.pr-audit li { display: flex; justify-content: space-between; gap: 12px; padding: 3px 0; }
.pr-audit time { opacity: 0.62; }
.pr-hint { margin: 0; font-size: 12px; opacity: 0.62; }
input { padding: 6px 10px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: 6px; font-size: 13px; }
button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; font-size: 13px; }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.pr-reject { color: var(--color-danger); }
.pr-quiet { opacity: 0.75; font-size: 12px; padding: 4px 10px; }
</style>
