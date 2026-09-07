<script setup lang="ts">
import { inject, onMounted, onUnmounted, ref, watch } from 'vue'
import OpsPagination from '../components/OpsPagination.vue'
import { ADMIN_BADGE_BRIDGE, DEFAULT_TAB_ROLES, TAB_ROLES } from '../adminTabs'
import { useGrassland } from '../../../composables/useGrassland'
import { useAuth } from '../../../composables/useAuth'
import type {
  KybVerificationDetail,
  KybVerificationRequest,
  KybVerificationType,
  MerchantAttachmentType,
  WithdrawalAccountType,
} from '../../../types/grassland'
import { formatDateTime, formatStructured, formatBytes, isOverdue } from '../admin-format'

defineOptions({ name: 'AdminKybPanel' })

/**
 * KYB 审核面板（任务书 #91 A2 自 AdminView.vue 内联分支 + 审核弹窗整段迁出，纯搬运；
 * fetch=onMounted 一次，D-04）。徽标（kybTotal）经 ADMIN_BADGE_BRIDGE 注册回 AdminView
 * 导航——渲染期求值穿透闭包追踪本面板的 ref，等价原父级 refs 常驻语义。
 */
const grassland = useGrassland()
const { currentUser, hasBackendRole } = useAuth()
const badgeBridge = inject(ADMIN_BADGE_BRIDGE)

const kybRequests = ref<KybVerificationRequest[]>([])
const kybLoading = ref(false)
const kybError = ref('')
const kybOffset = ref(0)
const kybTotal = ref(0)
const kybLimit = ref(10)

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

const industryLabels: Record<string, string> = {
  catering: '餐饮', retail: '零售', beauty: '美业', education: '教育培训',
  e_commerce: '电商', healthcare: '医疗健康', finance: '金融服务',
  real_estate: '房地产', travel: '旅游', children: '母婴儿童', other: '其他',
}

const attachmentTypeLabels: Record<MerchantAttachmentType, string> = {
  business_license: '营业执照',
  legal_person_id_front: '法人证件正面',
  legal_person_id_back: '法人证件反面',
  industry_license: '行业许可证',
  financial_qualification: '财务资质',
  store_photo: '门店照片',
  other: '其他材料',
}

async function loadKybRequests(): Promise<void> {
  kybLoading.value = true
  kybError.value = ''
  const result = await grassland.listKybVerifications({ limit: kybLimit.value, offset: kybOffset.value })
  if (result) {
    kybRequests.value = [...result.items]
    kybTotal.value = result.total
  } else {
    kybError.value = grassland.error.value || 'KYB 审核队列加载失败'
  }
  kybLoading.value = false
}

function changeKybPage(offset: number): void {
  kybOffset.value = offset
  void loadKybRequests()
}

function changeKybLimit(limit: number): void {
  kybLimit.value = limit
  kybOffset.value = 0
  void loadKybRequests()
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
    // 审核成功后带当前筛选重载本页（替代本地删行）；越界由分页组件收敛兑底。
    reviewTarget.value = null
    await loadKybRequests()
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

badgeBridge?.register('kyb', () => kybTotal.value)
onUnmounted(() => badgeBridge?.unregister('kyb'))

onMounted(() => {
  void loadKybRequests()
})

/**
 * 冷会话直登治理台（原 AdminView watch(currentUser.id) 补拉语义随迁）：本面板在登录前
 * 挂载会 401，身份从无到有时补拉一次。门槛沿用原实现的 canSeeTab('users') 同源口径。
 */
function canSeeUsersTab(): boolean {
  if (!currentUser.value) return true
  const roles = TAB_ROLES.users ?? DEFAULT_TAB_ROLES
  return roles.some((role) => hasBackendRole(role))
}
watch(() => currentUser.value?.id, (id, prev) => {
  if (!id || id === prev || !canSeeUsersTab()) return
  void loadKybRequests()
})
</script>

<template>
  <div class="panel-toolbar">
    <div><h3>待审核申请</h3><p>按提交时间顺序处理商户、门店和收款账户资料</p></div>
    <button class="refresh-btn" type="button" :disabled="kybLoading" @click="loadKybRequests">刷新</button>
  </div>
  <p v-if="kybError" class="error-msg" role="alert">{{ kybError }}</p>
  <div v-if="kybLoading" class="loading-state">加载中...</div>
  <template v-else>
  <div class="table-card">
    <div class="table-scroll">
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
  <OpsPagination :total="kybTotal" :limit="kybLimit" :offset="kybOffset"
    @change="changeKybPage" @change-limit="changeKybLimit" />
  </template>

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
            <dt>行业类型</dt><dd>{{ industryLabels[reviewDetail.subject.industry || ''] || reviewDetail.subject.industry || '-' }}</dd>
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
</template>

<style scoped src="../admin-shared.css"></style>

<style scoped>
.review-modal {
  width: min(720px, 94vw);
  max-height: min(820px, 92vh);
  overflow-y: auto;
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
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-accent);
  cursor: pointer;
}

@media (max-width: 640px) {
  .detail-grid {
    grid-template-columns: 76px minmax(0, 1fr);
  }
}
</style>
