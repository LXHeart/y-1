<script setup lang="ts">
import { computed, inject, onActivated, onMounted, onUnmounted, ref } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import { ADMIN_BADGE_BRIDGE } from '../ops/admin/adminTabs'
import { parsePermissionMaterials } from '../types/grassland'
import type {
  MaterialType, PermissionQueueFilter, PermissionRequest, PermissionRequestAudit,
  PermissionRequestDetail, PermissionTier, ReviewDecision,
} from '../types/grassland'

/**
 * 平台侧商家权限审核队列（HLD D-05 的审核侧，Slice 2H；任务书 #99 重构）。
 *
 * 后端门禁以 identity `backend_role=platform_admin` 为唯一授权权威，非 admin 调用 403。
 * 本组件由治理台 `TAB_REGISTRY` 注册（`permission-review`），但那只是**不给非 admin 看**，
 * 真正的授权在服务端。
 *
 * 批准 → 同时升级 org 的 `permission_tier` 并写 outbox `MerchantPermissionGranted`；
 * 驳回 → tier 不变，商家可据 `reviewNote` 补正后申诉。终态再审 409。
 *
 * 2026-09-10 任务书 #99（对齐 KYB 修复 f77efed0 三件套）：
 * ① 队列加 status 筛选（默认待审核；审结后不再「审完即消失」，可回看历史）；
 * ② 列表卡片收拢为单按钮「审核/详情」，组织名/材料/证照附件/自动核验/审计轨迹全部搬进详情弹窗
 *    （先看证据再决策）；③ KeepAlive 下重进页签重拉队列（首次激活跳过）。
 */
const emit = defineEmits<{ reviewed: [] }>()

const grassland = useGrassland()
const badgeBridge = inject(ADMIN_BADGE_BRIDGE)

const statusFilter = ref<PermissionQueueFilter>('pending')
const queue = ref<PermissionRequest[]>([])
const loaded = ref(false)
/** 徽标恒读待审数：筛选切到历史视图时不跟随当前列表。 */
const pendingBadgeTotal = ref(0)
const notice = ref('')

/** 详情/审核弹窗态（`detailLoadVersion` 防竞态：快速连开两条申请时丢弃过期响应）。 */
const detailTarget = ref<PermissionRequest | null>(null)
const detail = ref<PermissionRequestDetail | null>(null)
const detailLoading = ref(false)
const auditTrail = ref<PermissionRequestAudit[]>([])
const modalError = ref('')
let detailLoadVersion = 0

/** 每条申请的审核备注 / MFA 密码，key = requestId（不同申请互不串写）。 */
const notes = ref<Record<string, string>>({})
const mfaPasswords = ref<Record<string, string>>({})
const reviewing = ref(false)
const busy = computed(() => reviewing.value || grassland.loading.value)

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

const QUEUE_FILTER_LABEL: Record<PermissionQueueFilter, string> = {
  pending: '待审核',
  reviewed: '已审核',
  all: '全部',
}

const EMPTY_TEXT: Record<PermissionQueueFilter, string> = {
  pending: '当前没有待审核的申请。',
  reviewed: '暂无已审核记录。',
  all: '暂无申请记录。',
}

const STATUS_LABEL: Record<string, string> = {
  pending: '待审核',
  under_review: '审核中',
  approved: '已批准',
  rejected: '已拒绝',
}

const STATUS_CLASS: Record<string, string> = {
  approved: 'pr-status-ok',
  rejected: 'pr-status-bad',
}

const INDUSTRY_LABEL: Record<string, string> = {
  catering: '餐饮', retail: '零售', beauty: '美业', education: '教育培训',
  e_commerce: '电商', healthcare: '医疗健康', finance: '金融服务',
  real_estate: '房地产', travel: '旅游', children: '母婴儿童',
  gambling: '博彩', adult: '成人', other: '其他',
}

const ATTACHMENT_TYPE_LABEL: Record<string, string> = {
  business_license: '营业执照',
  legal_person_id_front: '法人证件正面',
  legal_person_id_back: '法人证件反面',
  industry_license: '行业许可证',
  financial_qualification: '财务资质',
  store_photo: '门店照片',
  other: '其他材料',
}

const OCR_STATUS_LABEL: Record<string, string> = {
  passed: 'OCR通过',
  failed: 'OCR失败',
  pending: '识别中',
  processing: '识别中',
  needs_review: '待人工复核',
  not_applicable: '不需识别',
}

const OCR_STATUS_CLASS: Record<string, string> = {
  passed: 'pr-status-ok',
  failed: 'pr-status-bad',
  pending: 'pr-status-warn',
  processing: 'pr-status-warn',
  needs_review: 'pr-status-warn',
}

const AUDIT_ACTION_LABEL: Record<string, string> = {
  submitted: '提交申请',
  appeal_submitted: '提交申诉',
  claimed: '领取审核',
  approved: '批准',
  rejected: '驳回',
}

const AUTO_RESULT_LABEL: Record<string, string> = {
  attachmentCount: '附件数量',
  pendingTypes: '待处理类型',
  failedTypes: '核验失败类型',
  needsReviewTypes: '待人工复核类型',
  humanReviewRequired: '需人工复核',
  reason: '说明',
}

function isTerminal(status: string): boolean {
  return status === 'approved' || status === 'rejected'
}

function formatDateTime(value: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}

function truncateId(value: string | null): string {
  return value ? `${value.slice(0, 8)}…` : '—'
}

function formatBytes(bytes: number | null): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
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
function materialEntries(req: PermissionRequest | null): { label: string; value: string }[] {
  if (!req) return []
  return Object.entries(parsePermissionMaterials(req.materials)).map(([k, v]) => ({
    label: MATERIAL_LABEL[k as MaterialType] || k,
    value: v,
  }))
}

function formatAutoValue(value: unknown): string {
  if (Array.isArray(value)) return value.length ? value.join('、') : '—'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

/** 自动核验明细：`autoReviewResult` 也是 JSON 字符串；坏 JSON / 非对象则不展示明细区块。 */
function autoReviewEntries(req: PermissionRequest | null): { label: string; value: string }[] {
  if (!req?.autoReviewResult) return []
  let parsed: unknown
  try {
    parsed = JSON.parse(req.autoReviewResult)
  } catch {
    return []
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return []
  const record = parsed as Record<string, unknown>
  return Object.keys(AUTO_RESULT_LABEL)
    .filter((key) => {
      const value = record[key]
      if (value === undefined || value === null || value === '') return false
      return !(Array.isArray(value) && value.length === 0)
    })
    .map((key) => ({ label: AUTO_RESULT_LABEL[key], value: formatAutoValue(record[key]) }))
}

const auditRows = computed(() => [...auditTrail.value].sort((a, b) => {
  const left = a.createdAt ? Date.parse(a.createdAt) : 0
  const right = b.createdAt ? Date.parse(b.createdAt) : 0
  return right - left
}))

async function refresh(): Promise<void> {
  const list = await grassland.listPendingPermissionRequests(statusFilter.value)
  if (list) {
    queue.value = list
    if (statusFilter.value === 'pending') pendingBadgeTotal.value = list.length
  }
  loaded.value = true
}

function changeStatus(): void {
  queue.value = []
  void refresh()
}

async function openDetail(req: PermissionRequest): Promise<void> {
  const loadVersion = ++detailLoadVersion
  detailTarget.value = req
  detail.value = null
  auditTrail.value = []
  modalError.value = ''
  detailLoading.value = true
  const [detailResult, auditResult] = await Promise.all([
    grassland.getPermissionRequestDetail(req.id),
    grassland.listPermissionRequestAudit(req.id),
  ])
  if (loadVersion !== detailLoadVersion || detailTarget.value?.id !== req.id) return
  if (detailResult) {
    detail.value = detailResult
  } else {
    modalError.value = grassland.error.value || '申请详情加载失败'
  }
  if (auditResult) auditTrail.value = auditResult
  detailLoading.value = false
}

function closeDetail(): void {
  if (reviewing.value) return
  detailLoadVersion += 1
  detailTarget.value = null
  detail.value = null
  auditTrail.value = []
  detailLoading.value = false
  modalError.value = ''
}

async function review(decision: ReviewDecision): Promise<void> {
  const target = detailTarget.value
  if (!target) return
  notice.value = ''
  modalError.value = ''
  reviewing.value = true
  const result = await grassland.reviewPermissionRequest(
    target.id, decision, notes.value[target.id]?.trim() || undefined, target.version)
  reviewing.value = false
  if (!result) return
  notice.value = decision === 'approve'
    ? `已批准：组织升级为「${TIER_LABEL[target.requestedTier]}」`
    : '已驳回，商家可补正材料后申诉'
  delete notes.value[target.id]
  emit('reviewed')
  closeDetail()
  await refresh()
}

async function claim(): Promise<void> {
  const target = detailTarget.value
  if (!target) return
  const claimed = await grassland.claimPermissionRequest(target.id)
  if (!claimed) return
  await refresh()
  await openDetail(claimed)
}

async function reauthenticate(): Promise<void> {
  const target = detailTarget.value
  if (!target) return
  const password = mfaPasswords.value[target.id]?.trim()
  if (!password) return
  const result = await grassland.reauthenticate(password)
  if (!result) return
  mfaPasswords.value[target.id] = ''
  notice.value = '重认证已完成，可在 10 分钟内执行高风险批准'
}

async function openAttachment(attachmentId: string): Promise<void> {
  const target = detailTarget.value
  if (!target) return
  modalError.value = ''
  const result = await grassland.getPermissionAttachmentDownload(target.id, attachmentId)
  if (!result) {
    modalError.value = grassland.error.value || '审核材料暂不可用'
    return
  }
  try {
    const url = new URL(result.downloadUrl, window.location.origin)
    if (!['http:', 'https:'].includes(url.protocol)) throw new Error('unsupported protocol')
    window.open(url.toString(), '_blank', 'noopener,noreferrer')
  } catch {
    modalError.value = '审核材料地址无效'
  }
}

badgeBridge?.register('permission-review', () => pendingBadgeTotal.value)
onUnmounted(() => badgeBridge?.unregister('permission-review'))

onMounted(() => {
  void refresh()
})

/**
 * KeepAlive 重进页签即重拉队列：商家提交不会推送到治理台，此前只在首挂载拉一次。
 * 首次激活紧跟 onMounted（已拉过），跳过；重复点击已激活页签不触发激活周期。
 */
let activatedOnce = false
onActivated(() => {
  if (!activatedOnce) {
    activatedOnce = true
    return
  }
  void refresh()
})
</script>

<template>
  <article class="pr">
    <header class="pr-head">
      <h3>平台审核 · 商家权限申请</h3>
      <div class="pr-tools">
        <label class="pr-filter" for="permission-status-filter">状态
          <select id="permission-status-filter" v-model="statusFilter" data-testid="permission-status-filter"
            :disabled="grassland.loading.value" @change="changeStatus">
            <option v-for="(label, value) in QUEUE_FILTER_LABEL" :key="value" :value="value">{{ label }}</option>
          </select>
        </label>
        <button type="button" class="pr-quiet" :disabled="grassland.loading.value" @click="refresh">刷新</button>
      </div>
    </header>

    <p v-if="grassland.error.value" class="pr-alert pr-err" role="alert">{{ grassland.error.value }}</p>
    <p v-if="notice && !detailTarget" class="pr-alert pr-ok">{{ notice }}</p>

    <p v-if="!loaded && grassland.loading.value" class="pr-hint">加载中…</p>
    <p v-else-if="loaded && queue.length === 0" class="pr-hint">{{ EMPTY_TEXT[statusFilter] }}</p>

    <section v-for="req in queue" :key="req.id" class="pr-item">
      <div class="pr-item-head">
        <span class="pr-target">申请升级至 <strong>{{ TIER_LABEL[req.requestedTier] }}</strong></span>
        <span v-if="req.originalRequestId" class="pr-tag">申诉件</span>
        <span class="pr-tag pr-status" :class="STATUS_CLASS[req.status] || ''">
          {{ STATUS_LABEL[req.status] || req.status }}
        </span>
        <span class="pr-tag">{{ AUTO_LABEL[req.autoReviewStatus] || req.autoReviewStatus }}</span>
        <span class="pr-tag" :class="{ 'pr-overdue': req.riskLevel === 'high' }">
          {{ RISK_LABEL[req.riskLevel] || req.riskLevel }}
        </span>
        <span class="pr-sla" :class="{ 'pr-overdue': req.slaStatus === 'overdue' }">
          {{ isTerminal(req.status) ? SLA_LABEL.completed : (SLA_LABEL[req.slaStatus] || req.slaStatus) }}
        </span>
      </div>

      <dl class="pr-meta">
        <div><dt>组织</dt><dd><code :title="req.organizationId">{{ truncateId(req.organizationId) }}</code></dd></div>
        <div><dt>申请人</dt><dd><code :title="req.requesterAccountId">{{ truncateId(req.requesterAccountId) }}</code></dd></div>
        <div><dt>行业</dt><dd>{{ INDUSTRY_LABEL[req.industry || ''] || req.industry || '—' }}</dd></div>
        <div><dt>提交时间</dt><dd>{{ formatDateTime(req.createdAt) }}</dd></div>
        <div><dt>审核截止</dt><dd>{{ formatDateTime(req.reviewDeadline) }}</dd></div>
      </dl>

      <div v-if="isTerminal(req.status)" class="pr-verdict">
        <p class="pr-verdict-line">
          审核结论：{{ req.status === 'approved' ? '已批准' : '已拒绝' }} · {{ formatDateTime(req.decisionAt) }}
        </p>
        <p v-if="req.reviewNote" class="pr-verdict-note">审核备注：{{ req.reviewNote }}</p>
      </div>

      <div class="pr-actions">
        <button type="button" data-testid="permission-open-btn" :disabled="grassland.loading.value" @click="openDetail(req)">
          {{ isTerminal(req.status) ? '详情' : '审核' }}
        </button>
      </div>
    </section>

    <div v-if="detailTarget" class="modal-overlay" @click.self="closeDetail">
      <div class="modal-card pr-modal" role="dialog" aria-modal="true" aria-labelledby="permission-detail-title">
        <header class="modal-header">
          <h3 id="permission-detail-title" class="modal-title">
            {{ isTerminal(detailTarget.status) ? '权限申请详情' : '审核 · 权限申请' }}
          </h3>
          <button class="modal-close" type="button" aria-label="关闭" :disabled="reviewing" @click="closeDetail">关闭</button>
        </header>
        <div class="modal-body">
          <p v-if="notice" class="pr-alert pr-ok">{{ notice }}</p>
          <p v-if="modalError" class="pr-alert pr-err" role="alert">{{ modalError }}</p>
          <div v-if="detailLoading" class="pr-detail-loading">正在加载申请详情…</div>

          <template v-else>
            <section class="pr-block" aria-label="申请信息">
              <h4 class="pr-block-title">申请信息</h4>
              <dl class="pr-detail-grid">
                <dt>申请等级</dt><dd>{{ TIER_LABEL[detailTarget.requestedTier] }}</dd>
                <dt>状态</dt>
                <dd>
                  <span class="pr-tag pr-status" :class="STATUS_CLASS[detailTarget.status] || ''">
                    {{ STATUS_LABEL[detailTarget.status] || detailTarget.status }}
                  </span>
                </dd>
                <dt>行业</dt><dd>{{ INDUSTRY_LABEL[detailTarget.industry || ''] || detailTarget.industry || '—' }}</dd>
                <dt>组织</dt>
                <dd>
                  <template v-if="detail?.organization.name">{{ detail.organization.name }} · </template>
                  <code :title="detailTarget.organizationId">{{ detailTarget.organizationId }}</code>
                </dd>
                <dt>申请人</dt><dd><code :title="detailTarget.requesterAccountId">{{ detailTarget.requesterAccountId }}</code></dd>
                <dt>提交时间</dt><dd>{{ formatDateTime(detailTarget.createdAt) }}</dd>
                <dt>审核截止</dt><dd>{{ formatDateTime(detailTarget.reviewDeadline) }}</dd>
                <dt>SLA</dt><dd>{{ SLA_LABEL[detailTarget.slaStatus] || detailTarget.slaStatus }}</dd>
                <dt>申请编号</dt><dd><code>{{ detailTarget.id }}</code></dd>
              </dl>
            </section>

            <section v-if="detailTarget.originalRequestId" class="pr-block" aria-label="申诉信息">
              <h4 class="pr-block-title">申诉信息</h4>
              <dl class="pr-detail-grid">
                <dt>申诉说明</dt><dd>{{ detailTarget.appealNote || '—' }}</dd>
                <dt>申诉轮次</dt><dd>第 {{ detailTarget.appealCount }} 次</dd>
                <dt>原申请编号</dt><dd><code>{{ detailTarget.originalRequestId }}</code></dd>
              </dl>
            </section>

            <section class="pr-block" aria-label="文本材料">
              <h4 class="pr-block-title">文本材料</h4>
              <p v-if="materialEntries(detailTarget).length === 0" class="pr-hint">未提交文本材料</p>
              <ul v-else class="pr-materials">
                <li v-for="m in materialEntries(detailTarget)" :key="m.label">
                  <span class="pr-mat-label">{{ m.label }}</span>
                  <span class="pr-mat-value">{{ m.value }}</span>
                </li>
              </ul>
            </section>

            <section class="pr-block" aria-label="证照附件">
              <h4 class="pr-block-title">证照附件</h4>
              <p v-if="!detail" class="pr-hint">附件清单加载失败</p>
              <p v-else-if="detail.attachments.length === 0" class="pr-hint">未提交证照附件</p>
              <div v-for="attachment in detail?.attachments || []" :key="attachment.id" class="pr-attachment">
                <div class="pr-attachment-main">
                  <strong>{{ ATTACHMENT_TYPE_LABEL[attachment.attachmentType] || attachment.attachmentType }}</strong>
                  <span class="pr-attachment-meta">
                    <span class="pr-tag" :class="OCR_STATUS_CLASS[attachment.ocrStatus || ''] || ''">
                      {{ OCR_STATUS_LABEL[attachment.ocrStatus || ''] || attachment.ocrStatus || '未知' }}
                    </span>
                    {{ attachment.mimeType || '未知类型' }} · {{ formatBytes(attachment.sizeBytes) }}
                  </span>
                </div>
                <button type="button" class="pr-material-view" :disabled="busy" @click="openAttachment(attachment.id)">
                  查看
                </button>
              </div>
            </section>

            <section class="pr-block" aria-label="自动核验">
              <h4 class="pr-block-title">自动核验</h4>
              <p class="pr-hint">{{ AUTO_LABEL[detailTarget.autoReviewStatus] || detailTarget.autoReviewStatus }}</p>
              <dl v-if="autoReviewEntries(detailTarget).length" class="pr-detail-grid">
                <template v-for="item in autoReviewEntries(detailTarget)" :key="item.label">
                  <dt>{{ item.label }}</dt><dd>{{ item.value }}</dd>
                </template>
              </dl>
            </section>

            <section class="pr-block" aria-label="审计轨迹">
              <h4 class="pr-block-title">审计轨迹</h4>
              <p v-if="auditRows.length === 0" class="pr-hint">暂无审计记录</p>
              <ol v-else class="pr-audit">
                <li v-for="item in auditRows" :key="item.id">
                  <span>{{ AUDIT_ACTION_LABEL[item.action] || item.action }}</span>
                  <time>{{ formatDateTime(item.createdAt) }}</time>
                </li>
              </ol>
            </section>

            <section class="pr-block" aria-label="审核操作">
              <h4 class="pr-block-title">{{ isTerminal(detailTarget.status) ? '审核结果' : '审核操作' }}</h4>

              <dl v-if="isTerminal(detailTarget.status)" class="pr-detail-grid">
                <dt>审核人</dt><dd><code>{{ truncateId(detailTarget.reviewerAccountId) }}</code></dd>
                <dt>审核备注</dt><dd>{{ detailTarget.reviewNote || '—' }}</dd>
                <dt>决定时间</dt><dd>{{ formatDateTime(detailTarget.decisionAt) }}</dd>
              </dl>

              <template v-else>
                <div v-if="needsReauthentication(detailTarget)" class="pr-actions">
                  <input v-model="mfaPasswords[detailTarget.id]" type="password" autocomplete="current-password"
                    placeholder="管理员密码" />
                  <button type="button" class="pr-quiet" data-testid="permission-reauth-btn"
                    :disabled="busy || !mfaPasswords[detailTarget.id]" @click="reauthenticate">
                    重认证
                  </button>
                </div>

                <div v-if="detailTarget.status === 'pending'" class="pr-actions">
                  <button type="button" class="pr-quiet" data-testid="permission-claim-btn" :disabled="busy" @click="claim">
                    领取审核
                  </button>
                </div>

                <div class="pr-actions">
                  <input v-model="notes[detailTarget.id]" data-testid="permission-review-note"
                    placeholder="审核备注（驳回时建议写明原因）" />
                  <button type="button" data-testid="permission-approve-btn"
                    :disabled="busy || detailTarget.autoReviewStatus === 'pending'" @click="review('approve')">
                    批准
                  </button>
                  <button type="button" class="pr-reject" data-testid="permission-reject-btn"
                    :disabled="busy || !notes[detailTarget.id]?.trim()" @click="review('reject')">
                    驳回
                  </button>
                </div>
              </template>
            </section>
          </template>
        </div>
      </div>
    </div>
  </article>
</template>

<style scoped>
.pr { border: 1px solid var(--color-border); border-radius: var(--radius-lg); padding: 14px; display: flex; flex-direction: column; gap: 12px; }
.pr-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.pr-head h3 { margin: 0; font-size: 15px; }
.pr-tools { display: flex; align-items: center; gap: 8px; }
.pr-filter { display: flex; align-items: center; gap: 6px; font-size: var(--text-xs); color: var(--color-text-muted); }
.pr-filter select { min-height: 32px; padding: 0 var(--space-xs); border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: var(--radius-sm); font-size: var(--text-xs); cursor: pointer; }
.pr-filter select:focus-visible { outline: none; border-color: var(--color-accent); }
.pr-alert { margin: 0; padding: 7px 11px; border-radius: var(--radius-sm); font-size: 13px; }
.pr-err { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.pr-ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
.pr-item { display: flex; flex-direction: column; gap: 8px; padding: 10px; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.pr-item-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; }
.pr-tag { font-size: 11px; padding: 1px 6px; border-radius: var(--radius-xs); background: var(--color-surface-strong); }
.pr-status-ok { color: var(--color-success); background: var(--surface-success); }
.pr-status-bad { color: var(--color-danger); background: var(--surface-danger); }
.pr-status-warn { color: var(--color-warning); background: var(--surface-warning); }
.pr-sla { font-size: 11px; opacity: 0.7; margin-left: auto; }
.pr-overdue { color: var(--color-danger); opacity: 1; }
.pr-meta { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 8px; margin: 0; }
.pr-meta div { display: flex; flex-direction: column; gap: 2px; }
.pr-meta dt { font-size: 11px; opacity: 0.6; }
.pr-meta dd { margin: 0; font-size: 12px; }
.pr-verdict { display: flex; flex-direction: column; gap: 3px; padding: 6px 10px; border-radius: var(--radius-sm); background: var(--color-surface-strong); }
.pr-verdict-line { margin: 0; font-size: 12px; }
.pr-verdict-note { margin: 0; font-size: 12px; opacity: 0.75; word-break: break-all; }
.pr-materials { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.pr-materials li { display: flex; gap: 8px; font-size: 12px; }
.pr-mat-label { flex: 0 0 88px; opacity: 0.65; }
.pr-mat-value { flex: 1; word-break: break-all; }
.pr-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.pr-actions input { flex: 1; min-width: 160px; }
.pr-audit { margin: 0; padding-left: 20px; font-size: 12px; }
.pr-audit li { display: flex; justify-content: space-between; gap: 12px; padding: 3px 0; }
.pr-audit time { opacity: 0.62; }
.pr-hint { margin: 0; font-size: 12px; opacity: 0.62; }
.pr-modal { width: min(720px, 94vw); max-height: min(860px, 92vh); overflow-y: auto; }
.pr-detail-loading { min-height: 140px; display: grid; place-items: center; font-size: 13px; color: var(--color-text-muted); }
.pr-block { display: flex; flex-direction: column; gap: 8px; padding-block: 12px; border-bottom: 1px solid var(--color-border); }
.pr-block:last-of-type { border-bottom: none; }
.pr-block-title { margin: 0; font-size: 13px; }
.pr-detail-grid { display: grid; grid-template-columns: 84px minmax(0, 1fr); gap: 6px 12px; margin: 0; font-size: 12px; }
.pr-detail-grid dt { color: var(--color-text-muted); }
.pr-detail-grid dd { margin: 0; word-break: break-all; }
.pr-attachment { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 8px 0; border-top: 1px solid var(--color-border); }
.pr-attachment-main { display: flex; flex-direction: column; gap: 3px; font-size: 12px; }
.pr-attachment-meta { display: flex; align-items: center; gap: 6px; color: var(--color-text-muted); }
.pr-material-view { min-width: 56px; padding: 5px 12px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: transparent; color: var(--color-accent); cursor: pointer; font-size: 12px; }
.pr-material-view:disabled { opacity: 0.5; cursor: not-allowed; }
input { padding: 6px 10px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: var(--radius-sm); font-size: 13px; }
button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: var(--radius-sm); cursor: pointer; font-size: 13px; }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.pr-reject { color: var(--color-danger); }
.pr-quiet { opacity: 0.75; font-size: 12px; padding: 4px 10px; }
</style>
