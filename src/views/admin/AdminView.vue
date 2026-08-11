<template>
  <section class="admin-view">
    <header class="section-header">
      <h2 class="section-title">平台管理</h2>
      <p class="section-desc">处理用户、审核、等级权益、信任准入与平台配置</p>
    </header>

    <div class="admin-tabs" role="tablist" aria-label="管理模块">
      <button type="button" role="tab" :aria-selected="activeSection === 'users'"
        :class="{ active: activeSection === 'users' }" @click="activeSection = 'users'">用户与积分</button>
      <button type="button" role="tab" :aria-selected="activeSection === 'kyb'"
        :class="{ active: activeSection === 'kyb' }" @click="activeSection = 'kyb'">
        KYB 审核 <span v-if="kybRequests.length" class="count-badge">{{ kybRequests.length }}</span>
      </button>
      <button type="button" role="tab" :aria-selected="activeSection === 'recommenders'"
        :class="{ active: activeSection === 'recommenders' }"
        @click="activeSection = 'recommenders'; void loadRecommenderRequests()">
        推荐官认证 <span v-if="recommenderRequests.length" class="count-badge">{{ recommenderRequests.length }}</span>
      </button>
      <button type="button" role="tab" :aria-selected="activeSection === 'tasks'"
        :class="{ active: activeSection === 'tasks' }"
        @click="activeSection = 'tasks'; void loadReviewTasks()">
        任务审核 <span v-if="reviewTasks.length" class="count-badge">{{ reviewTasks.length }}</span>
      </button>
      <button type="button" role="tab" :aria-selected="activeSection === 'reputation'"
        :class="{ active: activeSection === 'reputation' }"
        @click="activeSection = 'reputation'">等级与权益</button>
      <button type="button" role="tab" :aria-selected="activeSection === 'judges'"
        :class="{ active: activeSection === 'judges' }"
        @click="activeSection = 'judges'">审判官准入</button>
      <button type="button" role="tab" :aria-selected="activeSection === 'finance'"
        :class="{ active: activeSection === 'finance' }"
        @click="activeSection = 'finance'; void loadJournals()">财务对账</button>
      <button type="button" role="tab" :aria-selected="activeSection === 'risk'"
        :class="{ active: activeSection === 'risk' }" @click="activeSection = 'risk'">风险调查</button>
      <button type="button" role="tab" :aria-selected="activeSection === 'analytics'"
        :class="{ active: activeSection === 'analytics' }" @click="activeSection = 'analytics'">经营分析</button>
      <button type="button" role="tab" :aria-selected="activeSection === 'commerce'"
        :class="{ active: activeSection === 'commerce' }"
        @click="activeSection = 'commerce'">订单核销</button>
      <button type="button" role="tab" :aria-selected="activeSection === 'ai-models'"
        :class="{ active: activeSection === 'ai-models' }" @click="activeSection = 'ai-models'">AI 模型</button>
      <button type="button" role="tab" :aria-selected="activeSection === 'audit'"
        :class="{ active: activeSection === 'audit' }" @click="activeSection = 'audit'">统一审计</button>
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

    <div v-else-if="activeSection === 'kyb'" class="admin-panel" role="tabpanel">
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

    <div v-else-if="activeSection === 'recommenders'" class="admin-panel" role="tabpanel">
      <div class="panel-toolbar">
        <div><h3>推荐官平台认证</h3><p>自助开通不受影响，认证通过后获得平台认证标识</p></div>
        <button class="refresh-btn" type="button" :disabled="recommenderLoading" @click="loadRecommenderRequests">刷新</button>
      </div>
      <p v-if="recommenderError" class="error-msg" role="alert">{{ recommenderError }}</p>
      <div v-if="recommenderLoading" class="loading-state">加载中...</div>
      <div v-else class="table-card">
        <table class="user-table kyb-table">
          <thead><tr><th>账号</th><th>材料</th><th>提交时间</th><th>审核时限</th><th>审核原因</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="item in recommenderRequests" :key="item.id">
              <td class="id-cell" :title="item.accountId">{{ item.accountId }}</td>
              <td class="materials-cell"><code>{{ item.materials || '—' }}</code></td>
              <td class="td-time">{{ formatDateTime(item.createdAt || null) }}</td>
              <td class="td-time">{{ formatDateTime(item.reviewDeadline || null) }}</td>
              <td>
                <input v-model="recommenderNotes[item.id]" class="field-input" type="text" maxlength="500" placeholder="拒绝原因（拒绝必填）" />
              </td>
              <td class="review-actions">
                <button class="approve-btn" type="button" @click="reviewRecommender(item, 'approve')">通过</button>
                <button class="reject-btn" type="button" @click="reviewRecommender(item, 'reject')">拒绝</button>
              </td>
            </tr>
            <tr v-if="recommenderRequests.length === 0"><td colspan="6" class="td-empty">暂无待审核认证</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-else-if="activeSection === 'tasks'" class="admin-panel" role="tabpanel">
      <div class="panel-toolbar">
        <div><h3>待审核任务</h3><p>全审政策：所有任务提交后需审核通过才在大厅上架</p></div>
        <button class="refresh-btn" type="button" :disabled="taskReviewLoading" @click="loadReviewTasks">刷新</button>
      </div>
      <p v-if="taskReviewError" class="error-msg" role="alert">{{ taskReviewError }}</p>
      <div v-if="taskReviewLoading" class="loading-state">加载中...</div>
      <div v-else class="table-card">
        <table class="user-table kyb-table">
          <thead><tr><th>标题</th><th>平台</th><th>赏金</th><th>组织</th><th>驳回原因</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="t in reviewTasks" :key="t.id">
              <td>{{ t.title }}</td>
              <td><span class="type-tag">{{ t.platform || '—' }}</span></td>
              <td class="td-balance">{{ t.bountyCents ? '¥' + (t.bountyCents / 100).toFixed(2) : '—' }}</td>
              <td class="id-cell" :title="t.organizationId">{{ t.organizationId }}</td>
              <td>
                <input v-model="taskReviewNotes[t.id]" class="field-input" type="text" maxlength="500" placeholder="驳回原因（驳回必填）" />
              </td>
              <td class="review-actions">
                <button class="approve-btn" type="button" @click="reviewTask(t, 'approve')">通过</button>
                <button class="reject-btn" type="button" @click="reviewTask(t, 'reject')">驳回</button>
              </td>
            </tr>
            <tr v-if="reviewTasks.length === 0"><td colspan="6" class="td-empty">暂无待审核任务</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-else-if="activeSection === 'reputation'" class="admin-panel" role="tabpanel">
      <ReputationAdminPanel />
    </div>

    <div v-else-if="activeSection === 'judges'" class="admin-panel" role="tabpanel">
      <JudgeAdminPanel />
    </div>

    <div v-else-if="activeSection === 'finance'" class="admin-panel" role="tabpanel">
      <div class="panel-toolbar">
        <div><h3>账本流水</h3><p>双录账本（journal/posting），按组织筛选。真实 PSP 接入前仅 sandbox 流水。</p></div>
        <button class="refresh-btn" type="button" :disabled="journalLoading" @click="loadJournals">刷新</button>
      </div>
      <div class="ops-filters" style="margin-bottom: 12px;">
        <label>组织 ID
          <input v-model="journalOrgFilter" type="text" placeholder="留空 = 全量" @keyup.enter="loadJournals" />
        </label>
        <button type="button" class="refresh-btn" :disabled="journalLoading" @click="loadJournals">查询</button>
      </div>
      <p v-if="journalError" class="error-msg" role="alert">{{ journalError }}</p>
      <div v-if="journalLoading" class="loading-state">加载中...</div>
      <div v-else class="table-card">
        <table class="user-table kyb-table">
          <thead><tr><th>类型</th><th>组织</th><th>关联</th><th>备注</th><th>幂等键</th><th>时间</th></tr></thead>
          <tbody>
            <tr v-for="j in journals" :key="j.id">
              <td><span class="type-tag">{{ JOURNAL_TYPE_LABELS[j.type] || j.type }}</span></td>
              <td class="id-cell" :title="j.organizationId || ''">{{ j.organizationId || '—' }}</td>
              <td class="id-cell" :title="j.engagementRef || ''">{{ j.engagementRef || '—' }}</td>
              <td>{{ j.memo || '—' }}</td>
              <td class="id-cell" :title="j.operationId || ''">{{ j.operationId ? j.operationId.slice(0, 16) + '…' : '—' }}</td>
              <td class="td-time">{{ formatDateTime(j.createdAt) }}</td>
            </tr>
            <tr v-if="journals.length === 0"><td colspan="6" class="td-empty">暂无流水</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-else-if="activeSection === 'risk'" class="admin-panel" role="tabpanel">
      <RiskAdminPanel />
    </div>

    <div v-else-if="activeSection === 'analytics'" class="admin-panel" role="tabpanel">
      <BusinessAnalyticsPanel admin />
    </div>

    <div v-else-if="activeSection === 'commerce'" class="admin-panel" role="tabpanel">
      <CommerceAdminPanel />
    </div>

    <div v-else-if="activeSection === 'ai-models'" class="admin-panel" role="tabpanel">
      <AiPlatformModelsPanel />
    </div>

    <div v-else class="admin-panel" role="tabpanel">
      <UnifiedAuditPanel />
    </div>

    <Teleport to="body">
      <AdjustCreditsDialog
        :target="adjustTarget"
        :amount="adjustAmount"
        :note="adjustNote"
        :error="adjustError"
        :adjusting="adjusting"
        @close="adjustTarget = null"
        @update:amount="adjustAmount = $event"
        @update:note="adjustNote = $event"
        @confirm="handleAdjust"
      />
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
import AiPlatformModelsPanel from '../../components/AiPlatformModelsPanel.vue'
import CommerceAdminPanel from '../../components/CommerceAdminPanel.vue'
import JudgeAdminPanel from '../../components/JudgeAdminPanel.vue'
import ReputationAdminPanel from '../../components/ReputationAdminPanel.vue'
import RiskAdminPanel from '../../components/RiskAdminPanel.vue'
import BusinessAnalyticsPanel from '../../components/BusinessAnalyticsPanel.vue'
import UnifiedAuditPanel from '../../components/UnifiedAuditPanel.vue'
import AdjustCreditsDialog from './components/AdjustCreditsDialog.vue'
import { useGrassland } from '../../composables/useGrassland'
import type {
  KybVerificationDetail,
  KybVerificationRequest,
  KybVerificationType,
  RecommenderVerificationRequest,
  Task,
  MerchantAttachmentType,
  WithdrawalAccountType,
} from '../../types/grassland'

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
const activeSection = ref<
  'users' | 'kyb' | 'recommenders' | 'tasks' | 'reputation' | 'judges' | 'finance' | 'risk' | 'analytics' | 'commerce' | 'ai-models' | 'audit'
>('users')
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
const recommenderRequests = ref<RecommenderVerificationRequest[]>([])
const recommenderLoading = ref(false)
const recommenderError = ref('')
const recommenderNotes = ref<Record<string, string>>({})

interface JournalEntry {
  id: string
  type: string
  operationId: string | null
  currency: string
  organizationId: string | null
  engagementRef: string | null
  memo: string | null
  createdAt: string | null
}
const journals = ref<JournalEntry[]>([])
const journalLoading = ref(false)
const journalError = ref('')
const journalOrgFilter = ref('')

const reviewTasks = ref<Task[]>([])
const taskReviewLoading = ref(false)
const taskReviewError = ref('')
const taskReviewNotes = ref<Record<string, string>>({})
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

const JOURNAL_TYPE_LABELS: Record<string, string> = {
  DEPOSIT: '充值', RESERVE: '预留', RELEASE: '释放',
  CAPTURE: '结算', REVERSE: '冲正', WITHDRAW: '提现', OPENING: '期初',
  CONSUMER_PAYMENT: '消费支付', CONSUMER_REFUND: '消费退款', CONSUMER_SPLIT: '核销分账',
}

async function loadReviewTasks(): Promise<void> {
  taskReviewLoading.value = true
  taskReviewError.value = ''
  const result = await grassland.listPendingReviewTasks()
  if (result) reviewTasks.value = [...result]
  else taskReviewError.value = grassland.error.value || '待审核任务加载失败'
  taskReviewLoading.value = false
}

async function reviewTask(task: Task, decision: 'approve' | 'reject'): Promise<void> {
  const note = (taskReviewNotes.value[task.id] || '').trim()
  if (decision === 'reject' && !note) {
    taskReviewError.value = '驳回任务必须填写原因'
    return
  }
  taskReviewError.value = ''
  const result = decision === 'approve'
    ? await grassland.approveTaskReview(task.id, task.version)
    : await grassland.rejectTaskReview(task.id, task.version, note)
  if (result) {
    reviewTasks.value = reviewTasks.value.filter(item => item.id !== task.id)
    delete taskReviewNotes.value[task.id]
  } else {
    taskReviewError.value = grassland.error.value || '审核失败'
  }
}

async function loadJournals(): Promise<void> {
  journalLoading.value = true
  journalError.value = ''
  const result = await grassland.listFinanceJournals({
    organizationId: journalOrgFilter.value || undefined,
    limit: 100,
  })
  if (result) journals.value = result as unknown as JournalEntry[]
  else journalError.value = grassland.error.value || '账本流水加载失败'
  journalLoading.value = false
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
    const data = await res.json() as { success: boolean; data: { users: UserItem[] } }
    users.value = data.data.users
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

async function loadRecommenderRequests(): Promise<void> {
  recommenderLoading.value = true
  recommenderError.value = ''
  const result = await grassland.listRecommenderVerifications()
  if (result) recommenderRequests.value = [...result]
  else recommenderError.value = grassland.error.value || '推荐官认证队列加载失败'
  recommenderLoading.value = false
}

async function reviewRecommender(request: RecommenderVerificationRequest, decision: 'approve' | 'reject'): Promise<void> {
  const note = (recommenderNotes.value[request.id] || '').trim()
  if (decision === 'reject' && !note) {
    recommenderError.value = '拒绝推荐官认证必须填写原因'
    return
  }
  recommenderError.value = ''
  const result = await grassland.reviewRecommenderVerification(request.id, decision, note || undefined)
  if (result) {
    recommenderRequests.value = recommenderRequests.value.filter(item => item.id !== request.id)
    delete recommenderNotes.value[request.id]
  } else {
    recommenderError.value = grassland.error.value || '审核失败'
  }
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
  overflow-x: auto;
}

.admin-tabs button {
  flex: 0 0 auto;
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
