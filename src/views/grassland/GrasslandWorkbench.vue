<script setup lang="ts">
import { computed, inject, nextTick, ref, watch, type Ref } from 'vue'
import AdjudicationPanel from '../../components/AdjudicationPanel.vue'
import EngagementRatingPanel from '../../components/EngagementRatingPanel.vue'
import EngagementSubmissionPanel from '../../components/EngagementSubmissionPanel.vue'
import MerchantKybCard from '../../components/MerchantKybCard.vue'
import MerchantCommerceCard from '../../components/MerchantCommerceCard.vue'
import MerchantPermissionCard from '../../components/MerchantPermissionCard.vue'
import MerchantMonthlyBillCard from '../../components/MerchantMonthlyBillCard.vue'
import MyInvitationsCard from '../../components/MyInvitationsCard.vue'
import MyRecommenderProfileCard from '../../components/MyRecommenderProfileCard.vue'
import MySessionsCard from '../../components/MySessionsCard.vue'
import PersonalDataComplianceCard from '../../components/PersonalDataComplianceCard.vue'
import MyWalletCard from '../../components/MyWalletCard.vue'
import RecommenderHistoryCard from '../../components/RecommenderHistoryCard.vue'
import RecommenderIncomeStatsCard from '../../components/RecommenderIncomeStatsCard.vue'
import BusinessAnalyticsPanel from '../../components/BusinessAnalyticsPanel.vue'
import OrgTeamCard from '../../components/OrgTeamCard.vue'
import AiOrgBudgetPanel from '../../components/AiOrgBudgetPanel.vue'
import OrgCreationAuditPanel from '../../components/OrgCreationAuditPanel.vue'
import AiOrgProviderKeysPanel from '../../components/AiOrgProviderKeysPanel.vue'
import OrganizationBrandCard from '../../components/OrganizationBrandCard.vue'
import PermissionReviewPanel from '../../components/PermissionReviewPanel.vue'
import RecommenderReputationBadge from '../../components/RecommenderReputationBadge.vue'
import MerchantTaskForm from './components/MerchantTaskForm.vue'
import CommissionLadderSummary from './components/CommissionLadderSummary.vue'
import RecommenderTaskHall from './components/RecommenderTaskHall.vue'
import RecommenderRecommendations from './components/RecommenderRecommendations.vue'
import BrandPublicProfilePanel from './components/BrandPublicProfilePanel.vue'
import StorePublicProfilePanel from './components/StorePublicProfilePanel.vue'
import StoreMediaGallery from './components/StoreMediaGallery.vue'
import { useWorkbenchDisputes } from './composables/useWorkbenchDisputes'
import { useWorkbenchEngagements } from './composables/useWorkbenchEngagements'
import { useWorkbenchSession } from './composables/useWorkbenchSession'
import { useWorkbenchTaskDrafts } from './composables/useWorkbenchTaskDrafts'
import { useWorkbenchTaskHall } from './composables/useWorkbenchTaskHall'
import { normalizeTaskCreationSelection } from '../../config/ai-platform-capabilities'
import { useAuth } from '../../composables/useAuth'
import { useGrassland } from '../../composables/useGrassland'
import type { CreationEntry } from '../../types/ai-creation'
import type { NotificationLinkTarget } from '../../types/notification'
import type { TaskApplication } from '../../types/grassland'

/**
 * 草场工作台——Java 微服务域的第一个前端驱动（P0-1）。
 *
 * 双视角演示完整撮合闭环：
 *   商家：开通组织 → 充值 → 发布任务 → 查看报名 → 接受（资金 Saga，202+轮询）→ 确认履约 → 结算轮询
 *   推荐官：浏览任务大厅 → 报名 → 查看自己的报名 → 对已接受的履约开争议
 *
 * 交互要点：accept/confirm 是**异步 202**，UI 必须轮询到终态才能给结论（这是与旧 Express 同步端点的关键差异）。
 *
 * 结构：五个视图 composable（./composables/）按域持有状态与操作，本组件只做跨域编排——
 * 账号级重置/初始化 watch、通知锚点滚动与导航落点（会跨视角调用 switchSide/selectTask）。
 * session → engagements 的「换组织/切视角后重拉任务」以回调注入（见 useWorkbenchSession 头注）。
 */

const grassland = useGrassland()
const { currentUser } = useAuth()
const emit = defineEmits<{
  'open-creation': [entry: CreationEntry]
}>()

/** 平台 admin 才看得到审核队列。真正的门禁在服务端（identity 查 app_users.role）。 */
const isPlatformAdmin = computed(() => currentUser.value?.role === 'admin')

const notice = ref('')

function setNotice(message: string): void {
  notice.value = message
}

// session 先建（taskHall/engagements/drafts 依赖其 side/orgId/storeId refs）。它对履约域
// refreshTasks 的依赖是**晚绑定 thunk**：engagements 在下方才创建，但该回调只在异步函数的
// await 之后被调用（setup 同步路径不触达），届时 const 必已完成初始化。
const {
  side, orgs, stores, organizationAccessIds, managerStoreScopes,
  activeOrgId, selectedStoreId, account, newOrgName, creditAmountYuan, walletBalanceCents,
  activeOrg, activeOrgHasOrganizationAccess, activeOrganizationRole,
  canManageAiBudget, canPublishBounty, balanceYuan,
  loadOrganizations, initForAccount, createOrg, refreshAccount, changeOrganization,
  provision, credit, switchSide, reset: resetSession,
} = useWorkbenchSession(grassland, {
  setNotice,
  refreshTasks: () => engagements.refreshTasks(),
})

const { activeDisputeId, deferredDisputeRequestId, dispute, reset: resetDisputes } = useWorkbenchDisputes(
  grassland, setNotice,
)

const {
  applyNote, feedItems, feedHasMore, feedLoading, feedFilters, locating,
  apply, loadFeed, useCurrentLocation, handleFeedFilterUpdate, reset: resetTaskHall,
} = useWorkbenchTaskHall(grassland, side, setNotice)

const engagements = useWorkbenchEngagements(grassland, setNotice, {
  side, activeOrgId, selectedStoreId, feedItems, refreshAccount,
})
const {
  tasks, applications, selectedTaskId, selectedTask,
  outcomes, taskContextLoadingAppId,
  contestReasons, confirmedMetricInputs,
  storePublicProfile, storePublicProfileLoading, storePublicProfileError,
  applicantReputation, applicantProfile, levelFilter, rateFilterPct,
  recommendations, recommendationsLoading, invitingAccountId, confirmedAppIds,
  selectedAppIds,
  filteredApplications, pendingFilteredApplications, allPendingSelected, batchButtonsDisabled,
  refreshTasks, publishDraft, closeTaskAction, cancelTaskAction,
  taskStatusLabel, statusLabel, selectTask, loadRecommendations, inviteRecommended,
  accept, reject, toggleSelectAll, toggleSelectApp, batchAccept, batchReject,
  contest, selectedCommissionLadder, confirmedMetricResult, previewCommissionCents, confirm,
  withdrawApp,
} = engagements

const {
  taskForm, editingDraft, revisingTask,
  publishTask, saveDraft, editDraft, editPublished, resetTaskForm,
  updateCommissionLadder, handleTaskFormUpdate, handleTaskFormStoreChange, reset: resetTaskDrafts,
} = useWorkbenchTaskDrafts(grassland, setNotice, { activeOrgId, selectedStoreId, refreshTasks })

async function openAcceptedTaskCreation(application: TaskApplication): Promise<void> {
  const task = selectedTask.value
  if (!task || application.status !== 'accepted' || application.taskId !== task.id
      || taskContextLoadingAppId.value) return
  // 任务书 #23 R6：点赞互动任务无内容交付，「围绕任务创作」入口隐藏。
  if (task.contentForm === 'interaction') {
    setNotice('点赞互动任务无需内容创作，直接在下方提交互动截图即可')
    return
  }
  taskContextLoadingAppId.value = application.id
  const snapshot = await grassland.getTaskContext(task.id, application.id)
  taskContextLoadingAppId.value = ''
  if (!snapshot) {
    setNotice(grassland.error.value || '任务上下文加载失败，请稍后重试')
    return
  }
  const selection = normalizeTaskCreationSelection(snapshot.platform, snapshot.contentForm)
  emit('open-creation', {
    revision: Date.now(),
    ...selection,
    source: {
      type: 'task',
      taskId: snapshot.taskId,
      applicationId: snapshot.applicationId,
      taskVersion: snapshot.taskVersion,
    },
    prefill: {
      topic: snapshot.title,
      instructions: snapshot.description || undefined,
    },
    taskContext: snapshot,
  })
}

// ---------- 账号级编排：重置 + 初始化 ----------

/** 清空全部账号相关状态——否则上一个账号的组织/余额/任务会留在界面上。 */
function resetAccountState(): void {
  notice.value = ''
  resetSession()
  engagements.reset()
  resetTaskHall()
  resetTaskDrafts()
  resetDisputes()
}

/**
 * 按**账号**初始化，而不是 onMounted 跑一次。
 *
 * 工作台在未登录时也已挂载，且 App.vue 用 `<component :is>` 复用组件、切标签页不重挂载：
 * 只在 mounted 初始化的话，同一页面内登录/换账号后，组织列表、余额、任务全是上一个账号的
 * （或空白），必须手动刷新整页才正确——浏览器实测发现。活动身份也按 session 存，
 * 换账号后必须重新激活，否则商家操作 403。
 */
watch(() => currentUser.value?.id, (accountId) => {
  resetAccountState()
  if (accountId) {
    initForAccount()
  }
}, { immediate: true })

// 注：openIdentity 对商家需带 org。挂载时 org 尚未加载，故此处只做激活；
// 未开通的情况留给 switchSide（那时 activeOrgId 已就绪）。

// ---------- 通知落点编排（跨视角，须在组件层组合各域）----------

/**
 * 通知落点（草场 Slice 12 Stage 4）。`App.vue` provide 一个锚点 id，本组件滚到对应卡片后置空
 * （置空才能让同一锚点被再次点击时重新触发 watch）。
 *
 * **不切换商家/推荐官视角**：`switchSide()` 会重置组织/任务/争议选择。故 `/me/engagements`、
 * `/me/wallet` 这类两侧都有的锚点，落在当前视角自己那张卡上（两侧是 v-if/v-else，
 * 同一时刻 DOM 里只有一个同名 id）。
 */
const grasslandAnchor = inject<Ref<string>>('grasslandAnchor', ref(''))
const grasslandNavigationTarget = inject<Ref<NotificationLinkTarget | null>>(
  'grasslandNavigationTarget', ref(null),
)

// `immediate`：点通知时 App.vue 在同一 tick 内既切 currentView='grassland' 又置锚点，
// 工作台此刻才挂载——锚点 ref 在 watch 注册前就已是目标值。非 immediate 的 watch 只响应
// 注册之后的变化，于是「首次从别的视图点通知进来」不滚动（真浏览器 e2e 抓到）。immediate 让
// 挂载时若锚点非空就补滚一次；空值由 `if (!anchor) return` 兜住，正常进草场视图不会误滚。
watch(grasslandAnchor, async (anchor) => {
  if (!anchor) return
  await nextTick()
  const element = document.getElementById(anchor)
  element?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  grasslandAnchor.value = ''
}, { immediate: true })

/** Task invitations are the only notification route that intentionally selects a role and exact task. */
watch(grasslandNavigationTarget, async (target) => {
  if (target?.disputeId) {
    activeDisputeId.value = target.disputeId
    await nextTick()
    document.getElementById('gl-disputes')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    grasslandNavigationTarget.value = null
    return
  }
  if (target?.taskId && target.side === 'merchant') {
    try {
      if (side.value !== 'merchant') await switchSide('merchant')
      if (side.value !== 'merchant') return
      const task = await grassland.getTask(target.taskId)
      if (!task) {
        setNotice(grassland.error.value || '审核任务当前不可查看')
        return
      }
      tasks.value = [task, ...tasks.value.filter((item) => item.id !== task.id)]
      await selectTask(task.id)
      await nextTick()
      document.getElementById('gl-engagements')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
      setNotice('已打开审核任务，可修改后重新提交')
    } finally {
      grasslandNavigationTarget.value = null
    }
    return
  }
  if (!target?.taskId || target.side !== 'recommender') return
  try {
    if (side.value !== 'recommender') await switchSide('recommender')
    if (side.value !== 'recommender') return
    const task = await grassland.getTask(target.taskId)
    if (!task) {
      setNotice(grassland.error.value || '邀请任务当前不可查看')
      return
    }
    feedItems.value = [task, ...feedItems.value.filter((item) => item.id !== task.id)]
    await selectTask(task.id)
    await nextTick()
    document.getElementById('gl-task-hall')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    setNotice('已打开邀请任务，可直接报名')
  } finally {
    grasslandNavigationTarget.value = null
  }
}, { immediate: true })
</script>

<template>
  <section class="grassland">
    <header class="gl-header">
      <div>
        <h2>草场工作台</h2>
        <p class="gl-sub">商家与推荐官的撮合闭环（经 edge-bff 调用 Java 微服务）</p>
      </div>
      <div class="gl-side-switch" role="tablist" aria-label="角色切换">
        <button
          type="button" role="tab" :aria-selected="side === 'merchant'"
          :class="{ active: side === 'merchant' }" @click="switchSide('merchant')"
        >商家视角</button>
        <button
          type="button" role="tab" :aria-selected="side === 'recommender'"
          :class="{ active: side === 'recommender' }" @click="switchSide('recommender')"
        >推荐官视角</button>
      </div>
    </header>

    <p v-if="grassland.error.value" class="gl-alert gl-alert-error" role="alert">
      {{ grassland.error.value }}
    </p>
    <p v-if="notice" class="gl-alert gl-alert-ok">{{ notice }}</p>

    <!-- 我的邀请 / 登录设备：都是账号级能力，与商家/推荐官视角无关，故在切换之外 -->
    <article id="gl-invitations" class="gl-card gl-card-wide">
      <MyInvitationsCard @joined="() => loadOrganizations()" />
    </article>

    <article class="gl-card gl-card-wide">
      <MySessionsCard />
    </article>

    <article class="gl-card gl-card-wide">
      <PersonalDataComplianceCard />
    </article>

    <!-- ============ 商家视角 ============ -->
    <div v-if="side === 'merchant'" class="gl-grid">
      <article id="gl-organizations" class="gl-card">
        <h3>1. 我的组织</h3>
        <div class="gl-row">
          <select v-model="activeOrgId" @change="changeOrganization">
            <option value="" disabled>选择组织</option>
            <option v-for="o in orgs" :key="o.id" :value="o.id">{{ o.name }}（{{ o.permissionTier }}）</option>
          </select>
        </div>
        <div v-if="organizationAccessIds.size > 0 || managerStoreScopes.length === 0" class="gl-row">
          <input v-model="newOrgName" placeholder="新组织名称" @keyup.enter="createOrg" />
          <button type="button" :disabled="grassland.loading.value" @click="createOrg">创建</button>
        </div>
        <p v-if="activeOrg" class="gl-hint">
          当前等级 <code>{{ activeOrg.permissionTier }}</code>
          <span v-if="!activeOrgHasOrganizationAccess">（仅门店经理权限）</span>
          <span v-if="!canPublishBounty">（非 finance_transaction 等级不可发布赏金任务）</span>
        </p>
      </article>

      <!-- 权限与额度：D-05 的商家侧入口（升级申请 / 申诉 / 额度已用-上限） -->
      <article v-if="activeOrg && activeOrgHasOrganizationAccess" class="gl-card gl-card-wide">
        <MerchantPermissionCard
          :org-id="activeOrg.id"
          :tier="activeOrg.permissionTier"
          :industry="activeOrg.industry"
          @changed="loadOrganizations"
        />
      </article>

      <!-- 成员与门店：Slice 2F/2G/2J 的三级权限自助管理 -->
      <article v-if="activeOrg && activeOrgHasOrganizationAccess" class="gl-card gl-card-wide">
        <OrgTeamCard :org-id="activeOrg.id" />
      </article>

      <!-- 组织 AI 预算与组织模型密钥仅 owner/admin 可见；服务端再次走 identity 权威判定。 -->
      <article v-if="activeOrg && canManageAiBudget" class="gl-card gl-card-wide">
        <AiOrgBudgetPanel :organization-id="activeOrg.id" />
      </article>

      <!-- 组织级 BYOK（ADR-D17）：组织密钥管理 + 回退策略开关，同款 owner/admin 门禁 -->
      <article v-if="activeOrg && canManageAiBudget" class="gl-card gl-card-wide">
        <AiOrgProviderKeysPanel :organization-id="activeOrg.id" />
      </article>

      <!-- 组织级创作审计视图（任务书 #44 登记）：谁在何时用哪个模型生成了什么；同款 owner/admin 门禁 -->
      <article v-if="activeOrg && canManageAiBudget" class="gl-card gl-card-wide">
        <h3>组织创作审计</h3>
        <OrgCreationAuditPanel :organization-id="activeOrg.id" />
      </article>

      <!-- 组织品牌资料（#32）：独立于门店资料（KYB 卡的门店 tab）；member 只读，owner/admin 可编辑 -->
      <article v-if="activeOrg && activeOrgHasOrganizationAccess" class="gl-card gl-card-wide">
        <OrganizationBrandCard :org-id="activeOrg.id" :role="activeOrganizationRole" />
      </article>

      <!-- KYB 商家资料：GL-P3-MERCHANT-001 -->
      <article v-if="activeOrg && activeOrgHasOrganizationAccess" class="gl-card gl-card-wide">
        <MerchantKybCard :org-id="activeOrg.id" @changed="() => loadOrganizations()" />
      </article>

      <!-- 独立门店 KYB：纯门店 MANAGER（无组织成员身份）也能维护自己门店的资料并走审核状态机。 -->
      <article
        v-else-if="activeOrg && managerStoreScopes.some((scope) => scope.organizationId === activeOrgId)"
        class="gl-card gl-card-wide"
      >
        <MerchantKybCard
          :org-id="activeOrgId"
          store-only
          :stores="stores.map((store) => ({ id: store.id, name: store.name }))"
          @changed="() => loadOrganizations()"
        />
      </article>

      <!-- id 与推荐官侧钱包卡同名：两侧是 v-if/v-else，同一时刻只有一个在 DOM 里 -->
      <article v-if="activeOrgHasOrganizationAccess || managerStoreScopes.length === 0"
        id="gl-wallet" class="gl-card">
        <h3>2. 资金账户</h3>
        <p class="gl-balance">余额 <strong>¥{{ balanceYuan }}</strong></p>
        <div class="gl-row">
          <button type="button" :disabled="!activeOrgId || grassland.loading.value" @click="provision">开通账户</button>
        </div>
        <div class="gl-row">
          <input v-model.number="creditAmountYuan" type="number" min="1" />
          <button type="button" :disabled="!account || grassland.loading.value" @click="credit">充值（sandbox）</button>
        </div>
      </article>

      <!-- 任务书 #29+#30 #30：商家月度账单（按月汇总资金流水） -->
      <MerchantMonthlyBillCard v-if="activeOrgId" :organization-id="activeOrgId" />

      <MerchantCommerceCard
        v-if="activeOrgId"
        :organization-id="activeOrgId"
        :store-id="selectedStoreId || undefined"
      />

      <article v-if="activeOrgId" class="gl-card gl-card-wide">
        <BusinessAnalyticsPanel :organization-id="activeOrgId" :store-id="selectedStoreId" />
      </article>

      <MerchantTaskForm
        :form="taskForm"
        :editing-draft="editingDraft"
        :revising-task="revisingTask"
        :stores="stores"
        :selected-store-id="selectedStoreId"
        :active-org-id="activeOrgId"
        :has-organization-access="activeOrgHasOrganizationAccess"
        :can-publish-bounty="canPublishBounty"
        :loading="grassland.loading.value"
        @update:field="handleTaskFormUpdate"
        @update:commission-ladder="updateCommissionLadder"
        @change-store="handleTaskFormStoreChange"
        @publish="publishTask"
        @save-draft="saveDraft"
        @reset-form="resetTaskForm"
      />

      <article id="gl-engagements" class="gl-card gl-card-wide">
        <h3>4. 任务与报名</h3>
        <p v-if="tasks.length === 0" class="gl-empty">暂无任务</p>
        <ul class="gl-list">
          <li v-for="t in tasks" :key="t.id">
            <button type="button" class="gl-link" :class="{ active: selectedTaskId === t.id }" @click="selectTask(t.id)">
              {{ t.title }}
            </button>
            <span class="gl-tag">{{ taskStatusLabel(t.status) }}</span>
            <span v-if="t.bountyCents" class="gl-tag gl-tag-money">¥{{ (t.bountyCents / 100).toFixed(2) }}</span>
            <!-- 任务书 #25：阶梯任务在状态/赏金标签旁展示 compact 档位摘要（赏金 = 最高档预留） -->
            <CommissionLadderSummary v-if="t.requirements?.commissionLadder" :ladder="t.requirements.commissionLadder" compact />
            <span v-if="t.minRecommenderLevel > 1" class="gl-tag">Lv{{ t.minRecommenderLevel }}+</span>
            <span v-if="t.autoAcceptMinLevel" class="gl-tag gl-tag-auto-accept">Lv{{ t.autoAcceptMinLevel }}+ 自动通过中</span>
            <span v-if="t.storeId" class="gl-tag">{{ stores.find((s) => s.id === t.storeId)?.name || '门店任务' }}</span>
            <!-- 草稿：编辑 / 提交审核 / 取消 -->
            <template v-if="t.status === 'draft'">
              <button type="button" :disabled="grassland.loading.value" @click="editDraft(t)">编辑</button>
              <button type="button" :disabled="grassland.loading.value" @click="publishDraft(t)">提交审核</button>
              <button type="button" :disabled="grassland.loading.value" @click="cancelTaskAction(t)">取消</button>
            </template>
            <!-- 待审核：平台内容审核中，仅可取消（编辑需先驳回或取消重建） -->
            <template v-else-if="t.status === 'pending_review'">
              <span class="gl-hint">平台审核中</span>
              <button type="button" :disabled="grassland.loading.value" @click="cancelTaskAction(t)">取消</button>
            </template>
            <!-- 已发布：编辑出新版本 / 关闭报名 / 取消 -->
            <template v-else-if="t.status === 'published'">
              <button type="button" :disabled="grassland.loading.value" @click="editPublished(t)">编辑</button>
              <button type="button" :disabled="grassland.loading.value" @click="closeTaskAction(t)">关闭报名</button>
              <button type="button" :disabled="grassland.loading.value" @click="cancelTaskAction(t)">取消任务</button>
            </template>
          </li>
        </ul>

        <div v-if="selectedTaskId" class="gl-apps">
          <RecommenderRecommendations
            v-if="selectedTask?.status === 'published'"
            :items="recommendations?.items || []"
            :eligible-count="recommendations?.eligibleCount || 0"
            :scoring-version="recommendations?.scoringVersion || 'deterministic-v1'"
            :loading="recommendationsLoading"
            :inviting-account-id="invitingAccountId"
            @refresh="loadRecommendations()"
            @invite="inviteRecommended"
          />
          <h4>报名列表</h4>
          <p v-if="applications.length === 0" class="gl-empty">该任务暂无报名</p>
          <template v-else>
            <!-- 筛选：等级 ≥ / 完成率 ≥（前端对全量报名筛选，后端无搜人入口） -->
            <div class="gl-filter">
              <label>等级 ≥
                <select v-model="levelFilter">
                  <option value="">不限</option>
                  <option v-for="lv in ['Lv2','Lv3','Lv4']" :key="lv" :value="lv">{{ lv }}</option>
                </select>
              </label>
              <label>完成率 ≥
                <select v-model.number="rateFilterPct">
                  <option :value="0">不限</option>
                  <option v-for="p in [60,70,80,90]" :key="p" :value="p">{{ p }}%</option>
                </select>
              </label>
            </div>

            <p v-if="filteredApplications.length === 0" class="gl-empty">无符合筛选条件的报名</p>
            <template v-else>
              <!-- 任务书 #27：批量操作栏 -->
              <div class="gl-batch-bar">
                <label class="gl-batch-select-all">
                  <input type="checkbox" :checked="allPendingSelected" @change="toggleSelectAll" />
                  全选待处理（{{ pendingFilteredApplications.length }}）
                </label>
                <button type="button" :disabled="batchButtonsDisabled" @click="batchAccept">批量接受</button>
                <button type="button" :disabled="batchButtonsDisabled" @click="batchReject">批量拒绝</button>
                <span v-if="selectedAppIds.size > 0" class="gl-hint">已选 {{ selectedAppIds.size }} 条</span>
              </div>

              <table class="gl-table">
                <thead><tr><th class="gl-th-check"><input type="checkbox" :checked="allPendingSelected" @change="toggleSelectAll" /></th><th>推荐官</th><th>等级 / 声誉</th><th>状态</th><th>操作</th><th>结果</th></tr></thead>
                <tbody>
                  <tr v-for="a in filteredApplications" :key="a.id">
                    <td>
                      <input v-if="a.status === 'pending'" type="checkbox" :checked="selectedAppIds.has(a.id)" @change="toggleSelectApp(a.id)" />
                    </td>
                  <td><code>{{ a.recommenderAccountId.slice(0, 8) }}…</code></td>
                  <td>
                    <RecommenderReputationBadge
                      compact
                      :reputation="applicantReputation[a.recommenderAccountId] || null"
                      :profile="applicantProfile[a.recommenderAccountId] || null"
                    />
                  </td>
                  <td>{{ statusLabel(a.status) }}</td>
                  <td class="gl-actions">
                    <button v-if="a.status === 'pending'" type="button" :disabled="grassland.loading.value" @click="accept(a)">接受</button>
                    <button v-if="a.status === 'pending'" type="button" :disabled="grassland.loading.value" @click="reject(a)">拒绝</button>
                    <template v-if="a.status === 'accepted'">
                      <button type="button" :disabled="Boolean(taskContextLoadingAppId)" @click="openAcceptedTaskCreation(a)">
                        {{ taskContextLoadingAppId === a.id ? '加载上下文...' : '围绕任务创作' }}
                      </button>
                      <!-- 任务书 #25：阶梯任务确认履约须申报实际指标，实时预览预计结算 -->
                      <template v-if="selectedCommissionLadder()">
                        <input
                          v-model="confirmedMetricInputs[a.id]"
                          type="number"
                          min="0"
                          step="1"
                          class="gl-metric-input"
                          :aria-label="`实际指标 ${selectedCommissionLadder()?.metricKey ?? ''} ${a.id}`"
                          placeholder="实际指标"
                        />
                        <span class="gl-hint">预计结算 ¥{{ (previewCommissionCents(a.id) / 100).toFixed(2) }}</span>
                        <span v-if="confirmedMetricResult(a.id).error" class="gl-hint gl-metric-error">
                          {{ confirmedMetricResult(a.id).error }}
                        </span>
                      </template>
                      <button
                        type="button"
                        :disabled="grassland.loading.value
                          || (selectedCommissionLadder() != null && confirmedMetricResult(a.id).error != null)"
                        @click="confirm(a)"
                      >确认履约</button>
                      <input
                        v-model="contestReasons[a.id]"
                        class="gl-contest-reason"
                        :aria-label="`拒绝理由 ${a.id}`"
                        placeholder="拒绝理由（系统核实通过后转客服）"
                      />
                      <button
                        type="button"
                        :disabled="grassland.loading.value || !contestReasons[a.id]?.trim()"
                        @click="contest(a)"
                      >拒绝并转客服</button>
                    </template>
                  </td>
                  <td class="gl-outcome">{{ outcomes[a.id] || '—' }}</td>
                </tr>
              </tbody>
            </table>
            </template>

            <!-- 交付物 + 评分：确认履约前必须有一份待核验的（后端 409 守卫）；评分须先确认履约。 -->
            <template v-for="a in applications" :key="`sub-${a.id}`">
              <div v-if="a.status === 'accepted'" class="gl-sub-block">
                <h5>履约交付物 · <code>{{ a.recommenderAccountId.slice(0, 8) }}…</code></h5>
                <EngagementSubmissionPanel
                  :task-id="selectedTaskId" :application-id="a.id" role="merchant"
                  :task-content-form="selectedTask?.contentForm ?? null"
                  :interaction-action-type="selectedTask?.requirements?.interaction?.actionType ?? null"
                />
                <EngagementRatingPanel
                  :task-id="selectedTaskId" :application-id="a.id" role="merchant"
                  :can-rate="confirmedAppIds.has(a.id)"
                />
              </div>
            </template>
          </template>
        </div>
      </article>
    </div>

    <!-- ============ 推荐官视角 ============ -->
    <div v-else class="gl-grid">
      <!-- 我的主页：画像编辑 + 自己的等级/声誉一览 -->
      <article class="gl-card gl-card-wide">
        <MyRecommenderProfileCard />
      </article>

      <!-- 收款侧出口：结算后的赏金到这里，可提现 -->
      <article id="gl-wallet" class="gl-card gl-card-wide">
        <MyWalletCard />
      </article>

      <!-- 任务书 #29+#30 #29：收入统计（按月/按任务）+ 历史任务 -->
      <article class="gl-card gl-card-wide">
        <RecommenderIncomeStatsCard />
      </article>
      <article class="gl-card gl-card-wide">
        <RecommenderHistoryCard />
      </article>

      <RecommenderTaskHall
        :feed-items="feedItems"
        :feed-has-more="feedHasMore"
        :feed-loading="feedLoading"
        :feed-filters="feedFilters"
        :apply-note="applyNote"
        :selected-task-id="selectedTaskId"
        :loading="grassland.loading.value"
        :locating="locating"
        :wallet-balance-cents="walletBalanceCents"
        @update:feed-filter="handleFeedFilterUpdate"
        @load-feed="loadFeed"
        @update:apply-note="applyNote = $event"
        @select-task="selectTask"
        @apply="apply"
        @use-location="useCurrentLocation"
      />

      <!-- 任务书 #24：选中任务的门店公开详情页（只读白名单） -->
      <StorePublicProfilePanel
        :store-id="selectedTask?.storeId ?? null"
        :profile="storePublicProfile"
        :loading="storePublicProfileLoading"
        :error="storePublicProfileError"
      />

      <!-- 缺口清偿之六：选中任务的品牌公开资料（#32 D9 公开消费） -->
      <BrandPublicProfilePanel :organization-id="selectedTask?.organizationId ?? null" />

      <!-- 任务书 #42：门店公开媒体画廊（按需拉取，URL 过期 onerror 重拉一次） -->
      <StoreMediaGallery :store-id="selectedTask?.storeId ?? null" />

      <article id="gl-engagements" class="gl-card gl-card-wide">
        <h3>我的履约与争议</h3>
        <p class="gl-hint">对已接受的履约，如商家未按约定处理，可开启争议——结算将被暂停直至审判终局。</p>
        <p v-if="applications.length === 0" class="gl-empty">选择任务后可见相关报名</p>
        <table v-else class="gl-table">
          <thead><tr><th>报名</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="a in applications" :key="a.id">
              <td><code>{{ a.id.slice(0, 8) }}…</code></td>
              <td>{{ statusLabel(a.status) }}</td>
              <td>
                <template v-if="a.status === 'accepted'">
                  <button type="button" :disabled="Boolean(taskContextLoadingAppId)" @click="openAcceptedTaskCreation(a)">
                    {{ taskContextLoadingAppId === a.id ? '加载上下文...' : '开始创作' }}
                  </button>
                  <button type="button" :disabled="grassland.loading.value" @click="dispute(a)">开启争议</button>
                </template>
                <button v-else-if="a.status === 'pending'" type="button" :disabled="grassland.loading.value" @click="withdrawApp(a)">
                  撤销
                </button>
                <span v-else>—</span>
              </td>
            </tr>
          </tbody>
        </table>

        <!-- 提交履约凭证：商家确认前必须先有这一步 -->
        <template v-for="a in applications" :key="`mysub-${a.id}`">
          <div v-if="a.status === 'accepted'" class="gl-sub-block">
            <h5>提交履约 · <code>{{ a.id.slice(0, 8) }}…</code></h5>
            <EngagementSubmissionPanel
              :task-id="selectedTaskId" :application-id="a.id" role="recommender"
              :task-content-form="selectedTask?.contentForm ?? null"
              :interaction-action-type="selectedTask?.requirements?.interaction?.actionType ?? null"
            />
            <!-- 商家给本次合作的评分（只读；未评时提示「商家尚未评分」） -->
            <EngagementRatingPanel
              :task-id="selectedTaskId" :application-id="a.id" role="recommender"
            />
          </div>
        </template>
      </article>
    </div>

    <!-- 审判看板：开争议后自动挂载；也可手工填入争议 id 查看（商家/审判官视角） -->
    <article id="gl-disputes" class="gl-card gl-card-wide">
      <h3>争议审判</h3>
      <div class="gl-row">
        <input v-model="activeDisputeId" placeholder="争议 ID（开启争议后自动填入）" />
      </div>
      <AdjudicationPanel v-if="activeDisputeId" :dispute-id="activeDisputeId" />
      <p v-else-if="deferredDisputeRequestId" class="gl-hint" data-testid="deferred-dispute-status">
        异议已记录，客服案终局后自动开普通争议；系统将自动进入七官审判流程。
      </p>
      <p v-else class="gl-hint">开启争议后此处显示审判进度；审判官可在此报名入池与投票。</p>
    </article>

    <!-- 平台审核队列：仅 admin 可见（服务端另有 role 门禁），与商家/推荐官视角无关故放在切换之外 -->
    <article v-if="isPlatformAdmin" class="gl-card gl-card-wide">
      <PermissionReviewPanel @reviewed="loadOrganizations" />
    </article>
  </section>
</template>

<style scoped>
.grassland { display: flex; flex-direction: column; gap: 16px; }
.gl-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; }
.gl-header h2 { margin: 0; font-size: 20px; }
.gl-sub { margin: 4px 0 0; font-size: 13px; opacity: 0.7; }
.gl-side-switch { display: flex; gap: 4px; }
.gl-side-switch button {
  padding: 6px 14px; border: 1px solid var(--color-border);
  background: transparent; border-radius: 6px; cursor: pointer; font-size: 13px;
}
.gl-side-switch button.active { background: var(--color-accent); color: #fff; border-color: transparent; }
.gl-alert { margin: 0; padding: 8px 12px; border-radius: 6px; font-size: 13px; }
.gl-alert-error { background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.gl-alert-ok { background: color-mix(in srgb, var(--color-success) 14%, transparent); color: var(--color-success); }
.gl-sub-block { margin-top: 10px; }
.gl-sub-block h5 { margin: 0; font-size: 12px; opacity: 0.75; }
.gl-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 14px; }
.gl-card {
  border: 1px solid var(--color-border); border-radius: 10px;
  padding: 14px; display: flex; flex-direction: column; gap: 10px;
}
.gl-card-wide { grid-column: 1 / -1; }
.gl-card h3 { margin: 0; font-size: 15px; }
.gl-card h4 { margin: 12px 0 6px; font-size: 14px; }
.gl-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.gl-row input, .gl-row select { flex: 1; min-width: 120px; padding: 6px 10px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: 6px; font-size: 13px; }
.gl-row input[type="number"] { flex: 0 0 90px; }
.gl-row label { display: flex; align-items: center; gap: 6px; font-size: 13px; }
button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; font-size: 13px; }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.gl-hint { margin: 0; font-size: 12px; opacity: 0.65; }
.gl-empty { margin: 0; font-size: 13px; opacity: 0.55; }
.gl-balance { margin: 0; font-size: 14px; }
.gl-balance strong { font-size: 18px; }
.gl-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.gl-list li { display: flex; align-items: center; gap: 8px; }
.gl-link { border: none; background: none; padding: 2px 0; cursor: pointer; text-align: left; font-size: 13px; text-decoration: underline; }
.gl-link.active { font-weight: 600; }
.gl-tag { font-size: 11px; padding: 1px 7px; border-radius: 10px; background: var(--color-surface-strong); }
.gl-tag-money { background: color-mix(in srgb, var(--color-success) 16%, transparent); color: var(--color-success); }
.gl-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.gl-table th, .gl-table td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--color-border); }
.gl-actions { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
.gl-contest-reason { min-width: 210px; padding: 6px 8px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: 6px; font-size: 12px; }
/* 任务书 #25：阶梯任务申报指标输入（同拒绝理由行的紧凑样式）与校验错误提示 */
.gl-metric-input { width: 110px; padding: 6px 8px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: 6px; font-size: 12px; }
.gl-metric-error { color: var(--color-danger); white-space: nowrap; }
.gl-outcome { font-size: 12px; opacity: 0.8; }
.gl-filter { display: flex; gap: 14px; align-items: center; flex-wrap: wrap; font-size: 13px; }
.gl-filter label { display: flex; align-items: center; gap: 6px; opacity: 0.85; }
.gl-filter select { padding: 4px 8px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: 6px; font-size: 13px; }
.gl-tag-auto-accept { background: color-mix(in srgb, var(--color-accent) 16%, transparent); color: var(--color-accent); }
.gl-batch-bar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; padding: 6px 0; font-size: 13px; }
.gl-batch-select-all { display: flex; align-items: center; gap: 6px; font-size: 13px; cursor: pointer; }
.gl-th-check { width: 32px; }
</style>
