<template>
  <section class="admin-view">
    <header class="section-header">
      <h2 class="section-title">平台管理</h2>
      <p class="section-desc">处理用户积分与商户资质审核</p>
    </header>

    <div class="admin-tabs" role="tablist" aria-label="管理模块">
      <button type="button" role="tab" :aria-selected="activeSection === 'users'"
        :class="{ active: activeSection === 'users' }" @click="activeSection = 'users'">用户与积分</button>
      <button type="button" role="tab" :aria-selected="activeSection === 'kyb'"
        :class="{ active: activeSection === 'kyb' }" @click="activeSection = 'kyb'">
        KYB 审核 <span v-if="kybRequests.length" class="count-badge">{{ kybRequests.length }}</span>
      </button>
    </div>

    <div v-if="activeSection === 'users'" class="admin-panel" role="tabpanel">
      <p v-if="loadError" class="error-msg" role="alert">{{ loadError }}</p>
      <div v-if="loading" class="loading-state">加载中...</div>
      <div v-else class="table-card">
        <table class="user-table">
          <thead><tr><th>邮箱</th><th>昵称</th><th>角色</th><th>积分余额</th><th>累计获得</th>
            <th>累计使用</th><th>注册时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="user in users" :key="user.id">
              <td class="td-email">{{ user.email }}</td><td>{{ user.displayName || '-' }}</td>
              <td><span class="role-tag" :class="'role-' + user.role">{{ user.role }}</span></td>
              <td class="td-balance">{{ user.balance }}</td><td>{{ user.totalEarned }}</td>
              <td>{{ user.totalSpent }}</td><td class="td-time">{{ formatDate(user.createdAt) }}</td>
              <td><button class="adjust-btn" type="button" @click="openAdjust(user)">调整积分</button></td>
            </tr>
            <tr v-if="users.length === 0"><td colspan="8" class="td-empty">暂无用户</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-else class="admin-panel" role="tabpanel">
      <div class="panel-toolbar">
        <div><h3>待审核申请</h3><p>按提交时间顺序处理商户、门店和收款账户资料</p></div>
        <button class="refresh-btn" type="button" :disabled="kybLoading" @click="loadKybRequests">刷新</button>
      </div>
      <p v-if="kybError" class="error-msg" role="alert">{{ kybError }}</p>
      <div v-if="kybLoading" class="loading-state">加载中...</div>
      <div v-else class="table-card">
        <table class="user-table kyb-table">
          <thead><tr><th>类型</th><th>组织</th><th>目标</th><th>提交时间</th><th>审核时限</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="item in kybRequests" :key="item.id">
              <td><span class="type-tag">{{ verificationTypeLabels[item.verificationType] }}</span></td>
              <td class="id-cell" :title="item.organizationId">{{ item.organizationId }}</td>
              <td class="id-cell" :title="item.targetId || ''">{{ item.targetId || '-' }}</td>
              <td class="td-time">{{ formatDateTime(item.createdAt) }}</td>
              <td class="td-time" :class="{ overdue: isOverdue(item.reviewDeadline) }">
                {{ formatDateTime(item.reviewDeadline) }}
              </td>
              <td class="review-actions">
                <button class="approve-btn" type="button" @click="openReview(item, 'approve')">通过</button>
                <button class="reject-btn" type="button" @click="openReview(item, 'reject')">拒绝</button>
              </td>
            </tr>
            <tr v-if="kybRequests.length === 0"><td colspan="6" class="td-empty">暂无待审核申请</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <Teleport to="body">
      <div v-if="adjustTarget" class="modal-overlay" @click.self="adjustTarget = null">
        <div class="modal-card">
          <header class="modal-header">
            <h3 class="modal-title">调整积分 — {{ adjustTarget.email }}</h3>
            <button class="modal-close" type="button" @click="adjustTarget = null" aria-label="关闭">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
            </button>
          </header>

          <div class="modal-body">
            <p class="current-balance">当前积分：<strong>{{ adjustTarget.balance }}</strong></p>

            <label class="field-label">
              调整数量（正数增加，负数减少）
              <input v-model.number="adjustAmount" type="number" class="field-input" placeholder="例如：10 或 -5" />
            </label>

            <label class="field-label">
              备注
              <input v-model="adjustNote" type="text" class="field-input" placeholder="例如：手动充值" maxlength="200" />
            </label>

            <p v-if="adjustError" class="error-msg">{{ adjustError }}</p>

            <div class="modal-actions">
              <button class="btn-cancel" type="button" @click="adjustTarget = null">取消</button>
              <button class="btn-confirm" type="button" :disabled="adjusting" @click="handleAdjust">
                {{ adjusting ? '提交中...' : '确认调整' }}
              </button>
            </div>
          </div>
        </div>
      </div>
      <div v-if="reviewTarget" class="modal-overlay" @click.self="closeReview">
        <div class="modal-card review-modal" role="dialog" aria-modal="true" aria-labelledby="kyb-review-title">
          <header class="modal-header">
            <h3 id="kyb-review-title" class="modal-title">
              {{ reviewDecision === 'approve' ? '通过' : '拒绝' }}{{ verificationTypeLabels[reviewTarget.verificationType] }}
            </h3>
            <button class="modal-close" type="button" aria-label="关闭" @click="closeReview">关闭</button>
          </header>
          <div class="modal-body">
            <dl class="review-summary">
              <dt>组织</dt><dd>{{ reviewTarget.organizationId }}</dd>
              <dt>目标</dt><dd>{{ reviewTarget.targetId || '-' }}</dd>
            </dl>
            <div v-if="detailLoading" class="detail-loading">正在加载审核资料...</div>
            <section v-else-if="reviewDetail" class="review-detail" aria-label="审核资料">
              <dl v-if="reviewDetail.subject.type === 'merchant_profile'" class="detail-grid">
                <dt>法定名称</dt><dd>{{ reviewDetail.subject.legalName || '-' }}</dd>
                <dt>信用代码</dt><dd>{{ reviewDetail.subject.unifiedSocialCreditCode || '-' }}</dd>
                <dt>主体类型</dt><dd>{{ reviewDetail.subject.businessType || '-' }}</dd>
                <dt>法人</dt><dd>{{ reviewDetail.subject.legalPersonName || '-' }}</dd>
                <dt>法人证件</dt><dd>{{ reviewDetail.subject.legalPersonIdNumberMasked || '-' }}</dd>
                <dt>成立日期</dt><dd>{{ reviewDetail.subject.establishmentDate || '-' }}</dd>
                <dt>经营地址</dt><dd>{{ formatStructured(reviewDetail.subject.businessAddress) }}</dd>
                <dt>联系电话</dt><dd>{{ reviewDetail.subject.contactPhone || '-' }}</dd>
                <dt>联系邮箱</dt><dd>{{ reviewDetail.subject.contactEmail || '-' }}</dd>
              </dl>
              <dl v-else-if="reviewDetail.subject.type === 'withdrawal_account'" class="detail-grid">
                <dt>账户类型</dt><dd>{{ accountTypeLabels[reviewDetail.subject.accountType] }}</dd>
                <dt>账户名称</dt><dd>{{ reviewDetail.subject.accountName }}</dd>
                <dt>收款账号</dt><dd>{{ reviewDetail.subject.accountNumberMasked }}</dd>
                <dt>银行</dt><dd>{{ reviewDetail.subject.bankName || '-' }}</dd>
                <dt>支行</dt><dd>{{ reviewDetail.subject.branchName || '-' }}</dd>
              </dl>
              <dl v-else class="detail-grid">
                <dt>地址</dt><dd>{{ formatStructured(reviewDetail.subject.address) }}</dd>
                <dt>电话</dt><dd>{{ reviewDetail.subject.phone || '-' }}</dd>
                <dt>营业时间</dt><dd>{{ formatStructured(reviewDetail.subject.businessHours) }}</dd>
                <dt>说明</dt><dd>{{ reviewDetail.subject.description || '-' }}</dd>
              </dl>
              <div v-if="reviewDetail.attachments.length" class="review-materials">
                <h4>审核材料</h4>
                <div v-for="attachment in reviewDetail.attachments" :key="attachment.id" class="material-row">
                  <div>
                    <strong>{{ attachmentTypeLabels[attachment.attachmentType] }}</strong>
                    <span>{{ attachment.mimeType || '未知类型' }} · {{ formatBytes(attachment.sizeBytes) }}</span>
                  </div>
                  <button type="button" class="material-view" @click="openAttachment(attachment.id)">查看</button>
                </div>
              </div>
            </section>
            <label class="field-label">审核备注
              <textarea v-model="reviewNote" class="field-input field-textarea" maxlength="500"
                :placeholder="reviewDecision === 'reject' ? '请填写拒绝原因' : '选填审核说明'" />
            </label>
            <p v-if="reviewError" class="error-msg" role="alert">{{ reviewError }}</p>
            <div class="modal-actions">
              <button class="btn-cancel" type="button" @click="closeReview">取消</button>
              <button class="btn-confirm" :class="{ danger: reviewDecision === 'reject' }" type="button"
                :disabled="reviewing || detailLoading || !reviewDetail" @click="handleReview">
                {{ reviewing ? '提交中...' : '确认' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </Teleport>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useGrassland } from '../composables/useGrassland'
import type {
  KybVerificationDetail,
  KybVerificationRequest,
  KybVerificationType,
  MerchantAttachmentType,
  WithdrawalAccountType,
} from '../types/grassland'

interface UserItem {
  id: string
  email: string
  displayName: string | null
  role: string
  status: string
  createdAt: string
  balance: number
  totalEarned: number
  totalSpent: number
}

const users = ref<UserItem[]>([])
const activeSection = ref<'users' | 'kyb'>('users')
const loading = ref(false)
const loadError = ref('')

const adjustTarget = ref<UserItem | null>(null)
const adjustAmount = ref(0)
const adjustNote = ref('')
const adjusting = ref(false)
const adjustError = ref('')

const grassland = useGrassland()
const kybRequests = ref<KybVerificationRequest[]>([])
const kybLoading = ref(false)
const kybError = ref('')
const reviewTarget = ref<KybVerificationRequest | null>(null)
const reviewDecision = ref<'approve' | 'reject'>('approve')
const reviewNote = ref('')
const reviewing = ref(false)
const reviewError = ref('')
const reviewDetail = ref<KybVerificationDetail | null>(null)
const detailLoading = ref(false)
let reviewLoadVersion = 0

const verificationTypeLabels: Record<KybVerificationType, string> = {
  merchant_profile: '商户资料',
  store_profile: '门店资料',
  withdrawal_account: '收款账户',
}

const accountTypeLabels: Record<WithdrawalAccountType, string> = {
  bank_card: '银行卡',
  alipay: '支付宝',
  wechat: '微信',
}

const attachmentTypeLabels: Record<MerchantAttachmentType, string> = {
  business_license: '营业执照',
  legal_person_id_front: '法人证件正面',
  legal_person_id_back: '法人证件反面',
  store_photo: '门店照片',
  other: '其他材料',
}

onMounted(() => {
  void Promise.all([loadUsers(), loadKybRequests()])
})

async function loadUsers(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    const res = await fetch('/api/admin/users', { credentials: 'include' })
    if (!res.ok) {
      const data = await res.json().catch(() => null)
      throw new Error((data as Record<string, unknown>)?.error as string || '加载失败')
    }
    const data = await res.json() as { users: UserItem[] }
    users.value = data.users
  } catch (e: unknown) {
    loadError.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function loadKybRequests(): Promise<void> {
  kybLoading.value = true
  kybError.value = ''
  const result = await grassland.listKybVerifications()
  if (result) {
    kybRequests.value = [...result]
  } else {
    kybError.value = grassland.error.value || 'KYB 审核队列加载失败'
  }
  kybLoading.value = false
}

async function openReview(item: KybVerificationRequest, decision: 'approve' | 'reject'): Promise<void> {
  const loadVersion = ++reviewLoadVersion
  reviewTarget.value = item
  reviewDecision.value = decision
  reviewNote.value = ''
  reviewError.value = ''
  reviewDetail.value = null
  detailLoading.value = true
  const result = await grassland.getKybVerificationDetail(item.id)
  if (loadVersion !== reviewLoadVersion || reviewTarget.value?.id !== item.id) return
  if (result) {
    reviewDetail.value = result
  } else {
    reviewError.value = grassland.error.value || '审核详情加载失败'
  }
  detailLoading.value = false
}

function closeReview(): void {
  if (reviewing.value) return
  reviewLoadVersion += 1
  reviewTarget.value = null
  reviewDetail.value = null
  detailLoading.value = false
  reviewError.value = ''
}

async function handleReview(): Promise<void> {
  const target = reviewTarget.value
  if (!target || !reviewDetail.value || detailLoading.value) return
  if (reviewDecision.value === 'reject' && !reviewNote.value.trim()) {
    reviewError.value = '请填写拒绝原因'
    return
  }
  reviewing.value = true
  reviewError.value = ''
  const result = await grassland.reviewKybVerification(
    target.id, reviewDecision.value, reviewNote.value.trim() || undefined)
  if (result) {
    kybRequests.value = kybRequests.value.filter((item) => item.id !== target.id)
    reviewTarget.value = null
  } else {
    reviewError.value = grassland.error.value || '审核提交失败'
  }
  reviewing.value = false
}

async function openAttachment(attachmentId: string): Promise<void> {
  const target = reviewTarget.value
  if (!target) return
  reviewError.value = ''
  const result = await grassland.getKybAttachmentDownload(target.id, attachmentId)
  if (!result) {
    reviewError.value = grassland.error.value || '审核材料暂不可用'
    return
  }
  try {
    const url = new URL(result.downloadUrl, window.location.origin)
    if (!['http:', 'https:'].includes(url.protocol)) throw new Error('unsupported protocol')
    window.open(url.toString(), '_blank', 'noopener,noreferrer')
  } catch {
    reviewError.value = '审核材料地址无效'
  }
}

function openAdjust(user: UserItem): void {
  adjustTarget.value = user
  adjustAmount.value = 0
  adjustNote.value = ''
  adjustError.value = ''
}

async function handleAdjust(): Promise<void> {
  if (!adjustTarget.value) return
  if (adjustAmount.value === 0) {
    adjustError.value = '数量不能为 0'
    return
  }
  if (!adjustNote.value.trim()) {
    adjustError.value = '请输入备注'
    return
  }

  adjusting.value = true
  adjustError.value = ''

  try {
    const res = await fetch('/api/admin/adjust-credits', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({
        userId: adjustTarget.value.id,
        amount: adjustAmount.value,
        note: adjustNote.value.trim(),
      }),
    })
    if (!res.ok) {
      const data = await res.json().catch(() => null)
      throw new Error((data as Record<string, unknown>)?.error as string || '调整失败')
    }
    adjustTarget.value = null
    await loadUsers()
  } catch (e: unknown) {
    adjustError.value = e instanceof Error ? e.message : '调整失败'
  } finally {
    adjusting.value = false
  }
}

function formatDate(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function formatDateTime(iso: string | null): string {
  if (!iso) return '-'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '-'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(date)
}

function isOverdue(iso: string | null): boolean {
  return Boolean(iso && new Date(iso).getTime() < Date.now())
}

function formatStructured(raw: string | null): string {
  if (!raw) return '-'
  try {
    const value = JSON.parse(raw) as unknown
    if (Array.isArray(value)) {
      return value.map((item) => {
        if (!item || typeof item !== 'object') return String(item)
        const row = item as Record<string, unknown>
        return [row.dayOfWeek ? `周${row.dayOfWeek}` : null, row.openTime, row.closeTime]
          .filter(Boolean).join(' ')
      }).join('；') || '-'
    }
    if (value && typeof value === 'object') {
      const row = value as Record<string, unknown>
      return ['province', 'city', 'district', 'address']
        .map((key) => row[key]).filter((item) => typeof item === 'string' && item).join(' ') || raw
    }
    return String(value)
  } catch {
    return raw
  }
}

function formatBytes(value: number | null): string {
  if (value == null || value < 0) return '-'
  if (value < 1024) return `${value} B`
  return `${(value / 1024).toFixed(value < 10240 ? 1 : 0)} KB`
}
</script>

<style scoped>
.admin-view {
  display: grid;
  gap: var(--space-lg);
  max-width: 1180px;
  margin: 0 auto;
}

.admin-tabs {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid var(--color-border);
}

.admin-tabs button {
  min-height: 40px;
  padding: 0 14px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
}

.admin-tabs button.active {
  border-bottom-color: var(--color-accent);
  color: var(--color-text);
  font-weight: 600;
}

.count-badge {
  display: inline-flex;
  min-width: 20px;
  height: 20px;
  align-items: center;
  justify-content: center;
  margin-left: 4px;
  padding: 0 5px;
  border-radius: 10px;
  background: var(--color-danger);
  color: #fff;
  font-size: 0.72rem;
}

.admin-panel {
  display: grid;
  gap: var(--space-md);
}

.panel-toolbar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
}

.panel-toolbar h3,
.panel-toolbar p {
  margin: 0;
}

.panel-toolbar h3 {
  font-size: 1rem;
}

.panel-toolbar p {
  margin-top: 4px;
  color: var(--color-text-muted);
  font-size: 0.82rem;
}

.refresh-btn,
.approve-btn,
.reject-btn {
  min-height: 32px;
  padding: 0 12px;
  border-radius: 6px;
  font-size: 0.78rem;
  cursor: pointer;
}

.refresh-btn {
  border: 1px solid var(--color-border);
  background: transparent;
  color: var(--color-text-secondary);
}

.refresh-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.kyb-table {
  table-layout: fixed;
  min-width: 920px;
}

.id-cell {
  max-width: 190px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.76rem;
}

.type-tag {
  display: inline-block;
  padding: 3px 7px;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  background: var(--surface-muted);
  white-space: nowrap;
}

.overdue {
  color: var(--color-danger) !important;
  font-weight: 600;
}

.review-actions {
  display: flex;
  gap: 6px;
}

.approve-btn {
  border: 1px solid rgba(22, 163, 74, 0.35);
  background: rgba(22, 163, 74, 0.08);
  color: #15803d;
}

.reject-btn {
  border: 1px solid rgba(220, 38, 38, 0.3);
  background: rgba(220, 38, 38, 0.07);
  color: #b91c1c;
}

.section-header {
  display: grid;
  gap: var(--space-xs);
}

.section-title {
  font-size: 1.3rem;
  font-weight: 700;
  color: var(--color-text);
  margin: 0;
}

.section-desc {
  font-size: 0.88rem;
  color: var(--color-text-muted);
  margin: 0;
}

.loading-state {
  padding: var(--space-xl);
  text-align: center;
  color: var(--color-text-muted);
  font-size: 0.9rem;
}

.error-msg {
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-sm);
  background: rgba(239, 107, 107, 0.1);
  border: 1px solid rgba(239, 107, 107, 0.2);
  color: var(--color-danger);
  font-size: 0.86rem;
  margin: 0;
}

.table-card {
  padding: var(--space-lg);
  background: var(--gradient-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  overflow-x: auto;
}

.user-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.86rem;
}

.user-table th {
  text-align: left;
  padding: 10px 12px;
  font-weight: 600;
  color: var(--color-text-muted);
  border-bottom: 1px solid var(--color-border);
  white-space: nowrap;
}

.user-table td {
  padding: 10px 12px;
  color: var(--color-text);
  border-bottom: 1px solid var(--color-border);
}

.user-table tbody tr:last-child td {
  border-bottom: none;
}

.td-email {
  font-weight: 500;
}

.td-balance {
  font-weight: 700;
  color: var(--color-accent);
}

.td-time {
  white-space: nowrap;
  color: var(--color-text-muted);
}

.td-empty {
  text-align: center;
  padding: var(--space-xl) !important;
  color: var(--color-text-muted);
}

.role-tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 0.78rem;
  font-weight: 600;
  text-transform: uppercase;
}

.role-admin {
  background: rgba(245, 158, 11, 0.15);
  color: #d97706;
}

.role-user {
  background: var(--surface-muted);
  color: var(--color-text-muted);
}

.adjust-btn {
  padding: 4px 12px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: transparent;
  color: var(--color-accent);
  font-size: 0.78rem;
  cursor: pointer;
  transition: all 0.15s ease-out;
}

.adjust-btn:hover {
  background: var(--surface-hover);
  border-color: var(--color-border-accent);
}

.modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(4px);
}

.modal-card {
  width: min(440px, 92vw);
  background: var(--color-surface, #fff);
  border-radius: var(--radius-lg, 12px);
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
}

.review-modal {
  width: min(720px, 94vw);
  max-height: min(820px, 92vh);
  overflow-y: auto;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
}

.modal-title {
  font-size: 0.95rem;
  font-weight: 600;
  margin: 0;
  color: var(--color-text, #111);
}

.modal-close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
}

.modal-close:hover {
  background: var(--surface-hover, rgba(0, 0, 0, 0.05));
}

.modal-body {
  padding: 20px;
  display: grid;
  gap: 16px;
}

.current-balance {
  margin: 0;
  font-size: 0.88rem;
  color: var(--color-text-secondary);
}

.current-balance strong {
  color: var(--color-accent);
  font-size: 1.1rem;
}

.field-label {
  display: grid;
  gap: 6px;
  font-size: 0.84rem;
  color: var(--color-text-secondary);
}

.field-input {
  width: 100%;
  height: 40px;
  padding: 0 12px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--surface-muted);
  color: var(--color-text);
  font-size: 0.88rem;
  box-sizing: border-box;
}

.field-input:focus {
  outline: none;
  border-color: var(--color-accent);
}

.field-textarea {
  min-height: 96px;
  height: auto;
  padding: 10px 12px;
  resize: vertical;
}

.review-summary {
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr);
  gap: 8px 12px;
  margin: 0;
  font-size: 0.8rem;
}

.review-summary dt {
  color: var(--color-text-muted);
}

.review-summary dd {
  overflow-wrap: anywhere;
  margin: 0;
  color: var(--color-text-secondary);
}

.detail-loading {
  min-height: 120px;
  display: grid;
  place-items: center;
  color: var(--color-text-muted);
  font-size: 0.84rem;
}

.review-detail {
  display: grid;
  gap: 16px;
  padding-block: 14px;
  border-block: 1px solid var(--color-border);
}

.detail-grid {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr) 92px minmax(0, 1fr);
  gap: 10px 14px;
  margin: 0;
  font-size: 0.82rem;
}

.detail-grid dt {
  color: var(--color-text-muted);
}

.detail-grid dd {
  min-width: 0;
  margin: 0;
  color: var(--color-text);
  overflow-wrap: anywhere;
}

.review-materials {
  display: grid;
  gap: 8px;
}

.review-materials h4 {
  margin: 0;
  font-size: 0.84rem;
}

.material-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 44px;
  padding-block: 8px;
  border-top: 1px solid var(--color-border);
}

.material-row div {
  display: grid;
  gap: 3px;
}

.material-row strong,
.material-row span {
  font-size: 0.8rem;
}

.material-row span {
  color: var(--color-text-muted);
}

.material-view {
  min-width: 56px;
  height: 32px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: transparent;
  color: var(--color-accent);
  cursor: pointer;
}

.modal-actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
}

.btn-cancel {
  padding: 8px 16px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 0.86rem;
  cursor: pointer;
}

.btn-confirm {
  padding: 8px 20px;
  border: none;
  border-radius: 8px;
  background: var(--gradient-accent);
  color: #fff;
  font-size: 0.86rem;
  font-weight: 600;
  cursor: pointer;
}

.btn-confirm:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-confirm.danger {
  background: #b91c1c;
}

@media (max-width: 640px) {
  .panel-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .refresh-btn {
    align-self: flex-start;
  }

  .detail-grid {
    grid-template-columns: 76px minmax(0, 1fr);
  }
}
</style>
