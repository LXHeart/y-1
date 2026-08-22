<script setup lang="ts">
import { computed, inject, nextTick, ref, watch, type Ref } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw, type LocationQueryValue } from 'vue-router'
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
import RecommenderShareCard from '../../components/RecommenderShareCard.vue'
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
import type { Task, TaskApplication } from '../../types/grassland'
import { formatYuan } from '../../lib/money'

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

const route = useRoute()
const router = useRouter()

/** 平台 admin 才看得到审核队列。真正的门禁在服务端（identity 查 app_users.role）。 */
const isPlatformAdmin = computed(() => currentUser.value?.role === 'admin')

const notice = ref('')

function setNotice(message: string): void {
  notice.value = message
}

/** 通知锚点滚动尊重系统「减弱动态效果」设置（prefers-reduced-motion 时退化为瞬时定位）。 */
function scrollBlockIntoView(elementId: string): void {
  const prefersReducedMotion = typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  document.getElementById(elementId)?.scrollIntoView({
    behavior: prefersReducedMotion ? 'auto' : 'smooth',
    block: 'start',
  })
}

// session 先建（taskHall/engagements/drafts 依赖其 side/orgId/storeId refs）。它对履约域
// refreshTasks 的依赖是**晚绑定 thunk**：engagements 在下方才创建，但该回调只在异步函数的
// await 之后被调用（setup 同步路径不触达），届时 const 必已完成初始化。
const {
  side, orgs, stores, organizationAccessIds, managerStoreScopes,
  hasMerchantIdentity, hasRecommenderIdentity,
  activeOrgId, selectedStoreId, account, newOrgName, creditAmountYuan, walletBalanceCents,
  activeOrg, activeOrgHasOrganizationAccess, activeOrganizationRole,
  canManageAiBudget, canPublishBounty,
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

/** 破坏性操作先经确认（Web Interface Guidelines：不可逆操作不得单击直发）。 */function confirmCancelTask(task: Task): void {
  const message = task.status === 'draft'
    ? `取消草稿「${task.title}」？草稿将被删除，不可恢复。`
    : task.status === 'pending_review'
      ? `取消审核中的任务「${task.title}」？已提交的审核将作废，不可恢复。`
      : `取消任务「${task.title}」？已提交的报名将一并作废，且不可恢复。`
  if (!window.confirm(message)) return
  void cancelTaskAction(task)
}

function confirmBatchReject(): void {
  if (!window.confirm(`批量拒绝已选的 ${selectedAppIds.value.size} 条报名？该操作不可恢复。`)) return
  void batchReject()
}

function confirmWithdraw(app: TaskApplication): void {
  if (!window.confirm('撤销该报名？撤销后商家将无法再接受它。')) return
  void withdrawApp(app)
}

/**
 * 开通另一身份（账号与合规区的引导入口）。复用 switchSide 的开通回退：
 * 推荐官无需材料直接开通；商家须已属于某个组织（或有门店管理范围），
 * 否则前端只给指引文案不开按钮——与后端 openIdentity(merchant 需 org) 的约束一致。
 */
const identityOpening = ref(false)
const canOpenMerchantIdentity = computed(() => Boolean(activeOrgId.value) || managerStoreScopes.value.length > 0)

async function openOtherIdentity(target: 'merchant' | 'recommender'): Promise<void> {
  if (identityOpening.value) return
  identityOpening.value = true
  try {
    await switchSide(target)
  } finally {
    identityOpening.value = false
  }
}

/** 生长刻度：任务生命周期五段（草稿 → 审核 → 招募 → 履约 → 结算）。 */
const TASK_STAGES = ['草稿', '审核', '招募', '履约', '结算'] as const

/** 任务状态 → 刻度当前段：closed 停在履约（招募关闭、确认结算进行中）；cancelled 由模板加 dead 态。 */
function taskStageIndex(task: Task): number {
  switch (task.status) {
    case 'draft': return 0
    case 'pending_review': return 1
    case 'published': return 2
    case 'closed': return 3
    default: return 2
  }
}

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

/**
 * 清空全部账号相关状态——否则上一个账号的组织/余额/任务会留在界面上。
 */
function resetAccountState(): void {
  notice.value = ''
  resetSession()
  engagements.reset()
  resetTaskHall()
  resetTaskDrafts()
  resetDisputes()
}

/**
 * 活动身份翻到商家视角时重拉任务列表。side 是全局状态（账号菜单也能切），
 * 不能只在工作台内的 switchSide 里刷新；初始化期间的初始翻角除外——
 * 那时 loadOrganizations 自己会走 refreshTasks，重复拉且时序上组织还没就绪。
 */
let initializingAccount = false
watch(side, async (next, previous) => {
  if (next === previous || initializingAccount) return
  if (next === 'merchant') await refreshTasks()
})

/**
 * 按**账号**初始化，而不是 onMounted 跑一次。
 *
 * 工作台在未登录时也已挂载，且 App.vue 用 `<component :is>` 复用组件、切标签页不重挂载:
 * 只在 mounted 初始化的话，同一页面内登录/换账号后，组织列表、余额、任务全是上一个账号的
 * （或空白），必须手动刷新整页才正确——浏览器实测发现。活动身份也按 session 存，
 * 换账号后必须重新激活，否则商家操作 403。
 */
watch(() => currentUser.value?.id, async (accountId) => {
  resetAccountState()
  if (accountId) {
    initializingAccount = true
    try {
      await initForAccount()
    } finally {
      initializingAccount = false
    }
    // 初始化期间可能又换了账号——旧账号的 URL 恢复直接放弃，避免上一个链接串数据。
    if (currentUser.value?.id === accountId) await restoreWorkbenchStateFromUrl()
  }
}, { immediate: true })

// ---------- URL 状态同步（Web Interface Guidelines：URL 反映视图状态） ----------
// 视角 / 选中任务 / 报名筛选 / 大厅筛选随 query 持久化——刷新、分享链接可恢复现场。
// 恢复必须在 initForAccount 之后：它按已开通身份重排 side，先恢复会被覆盖。

const OWNED_QUERY_KEYS = ['side', 'task', 'level', 'rate', 'q', 'platform', 'contentForm', 'minBounty', 'dist'] as const
const LEVEL_FILTER_VALUES = ['Lv2', 'Lv3', 'Lv4']
const RATE_FILTER_VALUES = [60, 70, 80, 90]
const DISTANCE_VALUES = [1, 3, 5, 10, 30]

/** query 值可能是数组（重复 key），只取首个；空串视为未提供。 */
function firstQueryParam(raw: LocationQueryValue | LocationQueryValue[]): string | null {
  const value = Array.isArray(raw) ? raw[0] : raw
  return typeof value === 'string' && value.length > 0 ? value : null
}

const urlQuerySnapshot = computed<Record<string, string>>(() => {
  const query: Record<string, string> = {}
  if (side.value === 'recommender') query.side = 'recommender'
  if (selectedTaskId.value) query.task = selectedTaskId.value
  if (levelFilter.value) query.level = levelFilter.value
  if (rateFilterPct.value > 0) query.rate = String(rateFilterPct.value)
  if (feedFilters.value.q.trim()) query.q = feedFilters.value.q.trim()
  if (feedFilters.value.platform.trim()) query.platform = feedFilters.value.platform.trim()
  if (feedFilters.value.contentForm.trim()) query.contentForm = feedFilters.value.contentForm.trim()
  if (feedFilters.value.minBountyYuan > 0) query.minBounty = String(feedFilters.value.minBountyYuan)
  if (feedFilters.value.maxDistanceKm > 0) query.dist = String(feedFilters.value.maxDistanceKm)
  return query
})

// 状态 → URL。工作台被 KeepAlive 保活，离开本路由后状态仍可能变化——只在本路由上写 query，
// 且保留非本组件拥有的参数（?anchor= 之类不被抹掉）。
watch(urlQuerySnapshot, (owned) => {
  if (router.currentRoute.value.name !== 'grassland') return
  const merged: LocationQueryRaw = { ...router.currentRoute.value.query }
  for (const key of OWNED_QUERY_KEYS) delete merged[key]
  void router.replace({ name: 'grassland', query: { ...merged, ...owned } })
})

/** URL → 状态：账号初始化完成后执行一次。无效值一律忽略，绝不因链接参数破坏白名单约束。 */
async function restoreWorkbenchStateFromUrl(): Promise<void> {
  const query = route.query
  const level = firstQueryParam(query.level)
  if (level && LEVEL_FILTER_VALUES.includes(level)) levelFilter.value = level
  const rate = Number(firstQueryParam(query.rate))
  if (RATE_FILTER_VALUES.includes(rate)) rateFilterPct.value = rate
  const q = firstQueryParam(query.q)
  if (q) feedFilters.value.q = q
  const platform = firstQueryParam(query.platform)
  if (platform) feedFilters.value.platform = platform
  const contentForm = firstQueryParam(query.contentForm)
  if (contentForm) feedFilters.value.contentForm = contentForm
  const minBounty = Number(firstQueryParam(query.minBounty))
  if (Number.isFinite(minBounty) && minBounty > 0) feedFilters.value.minBountyYuan = minBounty
  const distance = Number(firstQueryParam(query.dist))
  if (DISTANCE_VALUES.includes(distance)) feedFilters.value.maxDistanceKm = distance

  const sideParam = firstQueryParam(query.side)
  if ((sideParam === 'merchant' || sideParam === 'recommender') && sideParam !== side.value) {
    await switchSide(sideParam)
  }
  // side 未变化时（如换账号前后同为 recommender）composable 的 side watch 不触发，
  // feed 首页不会自动拉——这里补一次，保证恢复的筛选条件有数据可筛。
  if (side.value === 'recommender' && feedItems.value.length === 0) {
    await loadFeed(true)
  }
  const taskParam = firstQueryParam(query.task)
  if (taskParam) await selectTask(taskParam)
}

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
  scrollBlockIntoView(anchor)
  grasslandAnchor.value = ''
}, { immediate: true })

/** Task invitations are the only notification route that intentionally selects a role and exact task. */
watch(grasslandNavigationTarget, async (target) => {
  if (target?.disputeId) {
    activeDisputeId.value = target.disputeId
    await nextTick()
    scrollBlockIntoView('gl-disputes')
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
      scrollBlockIntoView('gl-engagements')
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
      scrollBlockIntoView('gl-task-hall')
    setNotice('已打开邀请任务，可直接报名')
  } finally {
    grasslandNavigationTarget.value = null
  }
}, { immediate: true })
</script>

<template>
  <section class="gl-field grassland">
    <header class="gl-header">
      <div class="gl-head-copy">
        <h2 class="gl-title">{{ side === 'merchant' ? '商家工作台' : '推荐官工作台' }}</h2>
        <p class="gl-sub">工作台随活动身份切换——在右上角账号菜单或主页入口切换身份（经 edge-bff 调用 Java 微服务）</p>
      </div>
    </header>

    <!-- 地平线（signature）：紫=商家播种，苗绿=推荐官耕耘；当前身份侧点亮 -->
    <div class="gl-horizon" aria-hidden="true">
      <span class="gl-horizon-tag gl-horizon-merchant" :class="{ on: side === 'merchant' }">商家 · 播种</span>
      <span class="gl-horizon-line"></span>
      <span class="gl-horizon-tag gl-horizon-recommender" :class="{ on: side === 'recommender' }">推荐官 · 耕耘</span>
    </div>

    <p v-if="grassland.error.value" class="gl-alert gl-alert-error" role="alert">
      {{ grassland.error.value }}
    </p>
    <p v-if="notice" class="gl-alert gl-alert-ok" role="status">{{ notice }}</p>

    <!-- 田垄①：账号级能力（与身份无关） -->
    <section class="gl-zone" aria-label="账号与合规">
      <div class="gl-zone-head">
        <h3 class="gl-zone-title">账号与合规</h3>
      </div>
      <div class="gl-zone-body">
        <!-- 身份开通引导（PRD §一：首次切换到尚未开通的身份时引导开通；切换本身在账号菜单） -->
        <article v-if="side === 'merchant' && !hasRecommenderIdentity" class="gl-tile identity-open-tile">
          <h3>开通推荐官身份</h3>
          <p class="identity-open-copy">同一账号可同时开通商家与推荐官身份；开通后可在账号菜单随时切换。</p>
          <button type="button" class="identity-open-btn" :disabled="identityOpening" @click="openOtherIdentity('recommender')">
            {{ identityOpening ? '开通中…' : '开通推荐官身份' }}
          </button>
        </article>
        <article v-else-if="side === 'recommender' && !hasMerchantIdentity" class="gl-tile identity-open-tile">
          <h3>开通商家身份</h3>
          <p v-if="canOpenMerchantIdentity" class="identity-open-copy">用你当前的组织开通商家身份；开通后可在账号菜单随时切换。</p>
          <button v-if="canOpenMerchantIdentity" type="button" class="identity-open-btn" :disabled="identityOpening" @click="openOtherIdentity('merchant')">
            {{ identityOpening ? '开通中…' : '开通商家身份' }}
          </button>
          <p v-else class="identity-open-copy">开通商家身份需要一个商家组织：先接受组织邀请，或由商家将你加为门店成员后再来开通。</p>
        </article>

        <article id="gl-invitations" class="gl-tile">
          <MyInvitationsCard @joined="() => loadOrganizations()" />
        </article>

        <article class="gl-tile">
          <MySessionsCard />
        </article>

        <article class="gl-tile">
          <PersonalDataComplianceCard />
        </article>
      </div>
    </section>

    <!-- ============ 商家工作台 ============ -->
    <div v-if="side === 'merchant'" id="gl-panel-merchant" aria-label="商家工作台" tabindex="0" class="gl-workbench" data-side="merchant">
      <!-- 田垄②：主操作区——每天干活的地方放最上面 -->
      <section class="gl-zone" aria-label="发布与撮合">
        <div class="gl-zone-head">
          <h3 class="gl-zone-title">发布与撮合</h3>
          <p class="gl-zone-note">任务沿 草稿 → 审核 → 招募 → 履约 → 结算 的生长线推进</p>
        </div>
        <div class="gl-zone-body">
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

          <article id="gl-engagements" class="gl-tile gl-tile-wide">
            <h3>任务与报名</h3>
            <p v-if="tasks.length === 0" class="gl-empty">暂无任务</p>
            <ul class="gl-list">
              <li v-for="t in tasks" :key="t.id">
                <div class="gl-task-main">
                  <button type="button" class="gl-link" :class="{ active: selectedTaskId === t.id }" @click="selectTask(t.id)">
                    {{ t.title }}
                  </button>
                  <span class="badge badge-neutral">{{ taskStatusLabel(t.status) }}</span>
                  <span v-if="t.bountyCents" class="badge badge-success gl-num">{{ formatYuan(t.bountyCents) }}</span>
                  <!-- 任务书 #25：阶梯任务在状态/赏金标签旁展示 compact 档位摘要（赏金 = 最高档预留） -->
                  <CommissionLadderSummary v-if="t.requirements?.commissionLadder" :ladder="t.requirements.commissionLadder" compact />
                  <span v-if="t.minRecommenderLevel > 1" class="badge badge-neutral">Lv{{ t.minRecommenderLevel }}+</span>
                  <span v-if="t.autoAcceptMinLevel" class="badge badge-info">Lv{{ t.autoAcceptMinLevel }}+ 自动通过中</span>
                  <span v-if="t.storeId" class="badge badge-neutral">{{ stores.find((s) => s.id === t.storeId)?.name || '门店任务' }}</span>
                  <!-- 生长刻度：五段状态轨，当前段高亮；cancelled 整轨转 danger -->
                  <div
                    class="gl-growth" :class="{ dead: t.status === 'cancelled' }" role="img"
                    :aria-label="`生长进度：第 ${taskStageIndex(t) + 1}/5 段（${TASK_STAGES[taskStageIndex(t)]}）${t.status === 'cancelled' ? '，任务已取消' : ''}`"
                  >
                    <span
                      v-for="(stageLabel, i) in TASK_STAGES" :key="stageLabel" class="gl-growth-seg"
                      :class="[`s${i}`, { done: i < taskStageIndex(t), now: i === taskStageIndex(t) }]"
                    />
                  </div>
                </div>
                <div class="gl-task-actions">
                  <!-- 草稿：编辑 / 提交审核 / 取消 -->
                  <template v-if="t.status === 'draft'">
                    <button type="button" :disabled="grassland.loading.value" @click="editDraft(t)">编辑</button>
                    <button type="button" :disabled="grassland.loading.value" @click="publishDraft(t)">提交审核</button>
                    <button type="button" :disabled="grassland.loading.value" @click="confirmCancelTask(t)">取消</button>
                  </template>
                  <!-- 待审核：平台内容审核中，仅可取消（编辑需先驳回或取消重建） -->
                  <template v-else-if="t.status === 'pending_review'">
                    <span class="gl-hint">平台审核中</span>
                    <button type="button" :disabled="grassland.loading.value" @click="confirmCancelTask(t)">取消</button>
                  </template>
                  <!-- 已发布：编辑出新版本 / 关闭报名 / 取消 -->
                  <template v-else-if="t.status === 'published'">
                    <button type="button" :disabled="grassland.loading.value" @click="editPublished(t)">编辑</button>
                    <button type="button" :disabled="grassland.loading.value" @click="closeTaskAction(t)">关闭报名</button>
                    <button type="button" :disabled="grassland.loading.value" @click="confirmCancelTask(t)">取消任务</button>
                  </template>
                </div>
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
                      <input type="checkbox" aria-label="全选待处理报名" :checked="allPendingSelected" @change="toggleSelectAll" />
                      全选待处理（{{ pendingFilteredApplications.length }}）
                    </label>
                    <button type="button" :disabled="batchButtonsDisabled" @click="batchAccept">批量接受</button>
                    <button type="button" :disabled="batchButtonsDisabled" @click="confirmBatchReject">批量拒绝</button>
                    <span v-if="selectedAppIds.size > 0" class="gl-hint">已选 {{ selectedAppIds.size }} 条</span>
                  </div>

                  <table class="gl-table">
                    <thead><tr><th class="gl-th-check"><input type="checkbox" aria-label="全选待处理报名" :checked="allPendingSelected" @change="toggleSelectAll" /></th><th>推荐官</th><th>等级 / 声誉</th><th>状态</th><th>操作</th><th>结果</th></tr></thead>
                    <tbody>
                      <tr v-for="(a, index) in filteredApplications" :key="a.id">
                        <td>
                          <input v-if="a.status === 'pending'" type="checkbox" :aria-label="`选择第 ${index + 1} 行报名`" :checked="selectedAppIds.has(a.id)" @change="toggleSelectApp(a.id)" />
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
                              {{ taskContextLoadingAppId === a.id ? '加载上下文…' : '围绕任务创作' }}
                            </button>
                            <!-- 任务书 #25：阶梯任务确认履约须申报实际指标，实时预览预计结算 -->
                            <template v-if="selectedCommissionLadder()">
                              <input
                                v-model="confirmedMetricInputs[a.id]"
                                type="number"
                                min="0"
                                step="1"
                                class="gl-metric-input"
                                :aria-label="`第 ${index + 1} 行实际指标（${selectedCommissionLadder()?.metricKey ?? ''}）`"
                                placeholder="实际指标"
                              />
                              <span class="gl-hint">预计结算 <span class="gl-num">{{ formatYuan(previewCommissionCents(a.id)) }}</span></span>
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
                              :aria-label="`第 ${index + 1} 行拒绝理由`"
                              placeholder="拒绝理由（系统核实通过后转客服）"
                            />
                            <button
                              type="button"
                              :disabled="grassland.loading.value || !contestReasons[a.id]?.trim()"
                              @click="contest(a)"
                            >拒绝并转客服</button>
                          </template>
                        </td>
                        <td class="gl-outcome gl-num">{{ outcomes[a.id] || '—' }}</td>
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
      </section>

      <!-- 田垄③：低频资料与治理——组织、资金、KYB、AI 治理归拢一垄 -->
      <section class="gl-zone" aria-label="组织与门店">
        <div class="gl-zone-head">
          <h3 class="gl-zone-title">组织与门店</h3>
        </div>
        <div class="gl-zone-body">
          <article id="gl-organizations" class="gl-tile">
            <h3>我的组织</h3>
            <div class="gl-row">
              <select v-model="activeOrgId" aria-label="所属组织" name="organization" @change="changeOrganization">
                <option value="" disabled>选择组织</option>
                <option v-for="o in orgs" :key="o.id" :value="o.id">{{ o.name }}（{{ o.permissionTier }}）</option>
              </select>
            </div>
            <div v-if="organizationAccessIds.size > 0 || managerStoreScopes.length === 0" class="gl-row">
              <input v-model="newOrgName" aria-label="新组织名称" name="organization-name" autocomplete="off" placeholder="新组织名称" @keyup.enter="createOrg" />
              <button type="button" :disabled="grassland.loading.value" @click="createOrg">创建</button>
            </div>
            <p v-if="activeOrg" class="gl-hint">
              当前等级 <code>{{ activeOrg.permissionTier }}</code>
              <span v-if="!activeOrgHasOrganizationAccess">（仅门店经理权限）</span>
              <span v-if="!canPublishBounty">（非 finance_transaction 等级不可发布赏金任务）</span>
            </p>
          </article>

          <!-- id 与推荐官侧钱包卡同名：两侧是 v-if/v-else，同一时刻只有一个在 DOM 里 -->
          <article v-if="activeOrgHasOrganizationAccess || managerStoreScopes.length === 0"
            id="gl-wallet" class="gl-tile">
            <h3>资金账户</h3>
            <p class="gl-balance">余额 <strong class="gl-num">{{ account ? formatYuan(account.balanceCents) : '¥—' }}</strong></p>
            <div class="gl-row">
              <button type="button" :disabled="!activeOrgId || grassland.loading.value" @click="provision">开通账户</button>
            </div>
            <div class="gl-row">
              <input v-model.number="creditAmountYuan" aria-label="充值金额（元）" name="credit-amount" autocomplete="off" type="number" min="1" />
              <button type="button" :disabled="!account || grassland.loading.value" @click="credit">充值（sandbox）</button>
            </div>
          </article>

          <!-- 权限与额度：D-05 的商家侧入口（升级申请 / 申诉 / 额度已用-上限） -->
          <article v-if="activeOrg && activeOrgHasOrganizationAccess" class="gl-tile gl-tile-wide">
            <MerchantPermissionCard
              :org-id="activeOrg.id"
              :tier="activeOrg.permissionTier"
              :industry="activeOrg.industry"
              @changed="loadOrganizations"
            />
          </article>

          <!-- 成员与门店：Slice 2F/2G/2J 的三级权限自助管理 -->
          <article v-if="activeOrg && activeOrgHasOrganizationAccess" class="gl-tile gl-tile-wide">
            <OrgTeamCard :org-id="activeOrg.id" />
          </article>

          <!-- 组织 AI 预算与组织模型密钥仅 owner/admin 可见；服务端再次走 identity 权威判定。 -->
          <article v-if="activeOrg && canManageAiBudget" class="gl-tile gl-tile-wide">
            <AiOrgBudgetPanel :organization-id="activeOrg.id" />
          </article>

          <!-- 组织级 BYOK（ADR-D17）：组织密钥管理 + 回退策略开关，同款 owner/admin 门禁 -->
          <article v-if="activeOrg && canManageAiBudget" class="gl-tile gl-tile-wide">
            <AiOrgProviderKeysPanel :organization-id="activeOrg.id" />
          </article>

          <!-- 组织级创作审计视图（任务书 #44 登记）：谁在何时用哪个模型生成了什么；同款 owner/admin 门禁 -->
          <article v-if="activeOrg && canManageAiBudget" class="gl-tile gl-tile-wide">
            <h3>组织创作审计</h3>
            <OrgCreationAuditPanel :organization-id="activeOrg.id" />
          </article>

          <!-- 组织品牌资料（#32）：独立于门店资料（KYB 卡的门店 tab）；member 只读，owner/admin 可编辑 -->
          <article v-if="activeOrg && activeOrgHasOrganizationAccess" class="gl-tile gl-tile-wide">
            <OrganizationBrandCard :org-id="activeOrg.id" :role="activeOrganizationRole" />
          </article>

          <!-- KYB 商家资料：GL-P3-MERCHANT-001 -->
          <article v-if="activeOrg && activeOrgHasOrganizationAccess" class="gl-tile gl-tile-wide">
            <MerchantKybCard :org-id="activeOrg.id" @changed="() => loadOrganizations()" />
          </article>

          <!-- 独立门店 KYB：纯门店 MANAGER（无组织成员身份）也能维护自己门店的资料并走审核状态机。 -->
          <article
            v-else-if="activeOrg && managerStoreScopes.some((scope) => scope.organizationId === activeOrgId)"
            class="gl-tile gl-tile-wide"
          >
            <MerchantKybCard
              :org-id="activeOrgId"
              store-only
              :stores="stores.map((store) => ({ id: store.id, name: store.name }))"
              @changed="() => loadOrganizations()"
            />
          </article>

          <!-- 任务书 #29+#30 #30：商家月度账单（按月汇总资金流水） -->
          <article v-if="activeOrgId" class="gl-tile gl-tile-wide">
            <MerchantMonthlyBillCard :organization-id="activeOrgId" />
          </article>

          <article v-if="activeOrgId" class="gl-tile gl-tile-wide">
            <MerchantCommerceCard
              :organization-id="activeOrgId"
              :store-id="selectedStoreId || undefined"
            />
          </article>

          <article v-if="activeOrgId" class="gl-tile gl-tile-wide">
            <BusinessAnalyticsPanel :organization-id="activeOrgId" :store-id="selectedStoreId" />
          </article>
        </div>
      </section>
    </div>

    <!-- ============ 推荐官工作台 ============ -->
    <div v-else id="gl-panel-recommender" aria-label="推荐官工作台" tabindex="0" class="gl-workbench" data-side="recommender">
      <!-- 田垄②′：我的草场——主页、分享、收款 -->
      <section class="gl-zone" aria-label="我的草场">
        <div class="gl-zone-head">
          <h3 class="gl-zone-title">我的草场</h3>
        </div>
        <div class="gl-zone-body">
          <!-- 我的主页：画像编辑 + 自己的等级/声誉一览 -->
          <article class="gl-tile gl-tile-wide">
            <MyRecommenderProfileCard />
          </article>

          <!-- 推广链接/二维码生成（消费者归因闭环的推荐官侧入口） -->
          <article class="gl-tile gl-tile-wide">
            <RecommenderShareCard />
          </article>

          <!-- 收款侧出口：结算后的赏金到这里，可提现 -->
          <article id="gl-wallet" class="gl-tile gl-tile-wide">
            <MyWalletCard />
          </article>

          <!-- 任务书 #29+#30 #29：收入统计（按月/按任务）+ 历史任务 -->
          <article class="gl-tile gl-tile-wide">
            <RecommenderIncomeStatsCard />
          </article>
          <article class="gl-tile gl-tile-wide">
            <RecommenderHistoryCard />
          </article>
        </div>
      </section>

      <!-- 田垄③′：任务大厅——找活儿的地方 -->
      <section class="gl-zone" aria-label="任务大厅">
        <div class="gl-zone-head">
          <h3 class="gl-zone-title">任务大厅</h3>
          <p class="gl-zone-note">只显示已发布且未截止的任务</p>
        </div>
        <div class="gl-zone-body">
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
        </div>
      </section>

      <!-- 田垄④′：我的履约与争议 -->
      <section class="gl-zone" aria-label="我的履约与争议">
        <div class="gl-zone-head">
          <h3 class="gl-zone-title">我的履约与争议</h3>
        </div>
        <div class="gl-zone-body">
          <article id="gl-engagements" class="gl-tile gl-tile-wide">
            <h3>履约与争议</h3>
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
                        {{ taskContextLoadingAppId === a.id ? '加载上下文…' : '开始创作' }}
                      </button>
                      <button type="button" :disabled="grassland.loading.value" @click="dispute(a)">开启争议</button>
                    </template>
                    <button v-else-if="a.status === 'pending'" type="button" :disabled="grassland.loading.value" @click="confirmWithdraw(a)">
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
      </section>
    </div>

    <!-- 审判看板：开争议后自动挂载；也可手工填入争议 id 查看（商家/审判官视角） -->
    <section class="gl-zone" aria-label="争议与平台治理">
      <div class="gl-zone-head">
        <h3 class="gl-zone-title">争议与平台治理</h3>
      </div>
      <div class="gl-zone-body">
        <article id="gl-disputes" class="gl-tile">
          <h3>争议审判</h3>
          <div class="gl-row">
            <input v-model="activeDisputeId" aria-label="争议 ID" name="dispute-id" autocomplete="off" placeholder="争议 ID（开启争议后自动填入）" />
          </div>
          <AdjudicationPanel v-if="activeDisputeId" :dispute-id="activeDisputeId" />
          <p v-else-if="deferredDisputeRequestId" class="gl-hint" data-testid="deferred-dispute-status">
            异议已记录，客服案终局后自动开普通争议；系统将自动进入七官审判流程。
          </p>
          <p v-else class="gl-hint">开启争议后此处显示审判进度；审判官可在此报名入池与投票。</p>
        </article>

        <!-- 平台审核队列：仅 admin 可见（服务端另有 role 门禁），与商家/推荐官视角无关故放在切换之外 -->
        <article v-if="isPlatformAdmin" class="gl-tile">
          <PermissionReviewPanel @reviewed="loadOrganizations" />
        </article>
      </div>
    </section>
  </section>
</template>

<style scoped>
.grassland { display: flex; flex-direction: column; gap: var(--space-lg); }

/* ---------- 地平线头区（signature：紫=播种 / 苗绿=耕耘） ---------- */
.gl-header { display: flex; justify-content: space-between; align-items: flex-end; gap: var(--space-md); flex-wrap: wrap; }
.gl-head-copy { min-width: 0; }
.gl-title { margin: 0; font-size: var(--text-xl); font-weight: 800; letter-spacing: -0.02em; line-height: 1.2; }
.gl-sub { margin: 4px 0 0; font-size: var(--text-sm); color: var(--color-text-muted); }

/* ---------- 身份开通引导（账号与合规区内） ---------- */
.identity-open-tile { display: grid; gap: 6px; align-content: start; border-color: var(--color-border-accent); }
.identity-open-tile h3 { margin: 0; font-size: var(--text-base); font-weight: 700; }
.identity-open-copy { margin: 0; font-size: var(--text-sm); color: var(--color-text-muted); line-height: 1.6; }
.identity-open-btn {
  justify-self: start; margin-top: 4px; min-height: 36px; padding: 0 16px;
  border: 1px solid var(--color-border-accent); border-radius: var(--radius-sm);
  background: var(--color-surface-highlight); color: var(--color-accent-2);
  font-size: var(--text-sm); font-weight: 600; cursor: pointer;
}

/* 地平线：全宽紫→绿渐变细线，两端身份标签，激活侧点亮 */
.gl-horizon { display: flex; align-items: center; gap: var(--space-sm); }
.gl-horizon-line { flex: 1; height: 2px; border-radius: 999px; background: var(--gradient-field); opacity: 0.85; }
.gl-horizon-tag {
  font-size: var(--text-xs); font-weight: 600; letter-spacing: 0.04em;
  color: var(--color-text-muted); white-space: nowrap;
  transition: color var(--duration-normal) var(--ease-out);
}
.gl-horizon-merchant.on { color: var(--color-accent-2); }
.gl-horizon-recommender.on { color: var(--color-grass); }
@media (max-width: 640px) { .gl-horizon-tag { display: none; } }

/* ---------- 提示条 ---------- */
.gl-alert { margin: 0; padding: var(--space-xs) var(--space-sm); border-radius: var(--radius-sm); font-size: var(--text-sm); border: 1px solid transparent; }
.gl-alert-error {
  background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger);
  border-color: color-mix(in srgb, var(--color-danger) 28%, transparent);
}
.gl-alert-ok {
  background: color-mix(in srgb, var(--color-success) 12%, transparent); color: var(--color-success);
  border-color: color-mix(in srgb, var(--color-success) 24%, transparent);
}

/* 垄眉：micro-caps，颜色随视角（商家紫 / 推荐官苗绿），切侧时交叉淡入 */
.gl-workbench .gl-zone-title { transition: color var(--duration-normal) var(--ease-out); }
.gl-workbench[data-side="merchant"] .gl-zone-title { color: var(--color-accent-2); }
.gl-workbench[data-side="recommender"] .gl-zone-title { color: var(--color-grass); }
/* 资金账户：余额走台账等宽大字 */
.gl-balance { margin: 0; font-size: var(--text-sm); color: var(--color-text-secondary); }
.gl-balance strong {
  display: block; margin-top: 2px; font-size: var(--text-xl); font-weight: 700;
  color: var(--color-text); letter-spacing: -0.01em;
}

/* ---------- 任务列表 + 生长刻度 ---------- */
.gl-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--space-sm); }
.gl-list li {
  display: flex; align-items: center; gap: var(--space-sm); flex-wrap: wrap;
  min-width: 0; padding: var(--space-sm); border-radius: var(--radius-md);
  background: var(--surface-furrow);
}
.gl-task-main { display: flex; align-items: center; gap: var(--space-xs); flex: 1 1 240px; min-width: 0; flex-wrap: wrap; }
.gl-task-actions { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
/* 生长刻度：五段轨（草稿/审核/招募/履约/结算），已完成=段色半透、当前=段色实心；
   段色映射状态 token：中性/警示/信息/强调/成功——结构即状态机，不新增色相 */
.gl-growth { display: inline-flex; align-items: center; gap: 3px; }
.gl-growth-seg { width: 18px; height: 3px; border-radius: 999px; background: color-mix(in srgb, var(--color-text-muted) 30%, transparent); }
.gl-growth-seg.s0.done { background: color-mix(in srgb, var(--color-text-secondary) 55%, transparent); }
.gl-growth-seg.s0.now { background: var(--color-text-secondary); }
.gl-growth-seg.s1.done { background: color-mix(in srgb, var(--color-warning) 55%, transparent); }
.gl-growth-seg.s1.now { background: var(--color-warning); }
.gl-growth-seg.s2.done { background: color-mix(in srgb, var(--color-info) 55%, transparent); }
.gl-growth-seg.s2.now { background: var(--color-info); }
.gl-growth-seg.s3.done { background: color-mix(in srgb, var(--color-accent) 55%, transparent); }
.gl-growth-seg.s3.now { background: var(--color-accent-2); }
.gl-growth-seg.s4.done { background: color-mix(in srgb, var(--color-success) 55%, transparent); }
.gl-growth-seg.s4.now { background: var(--color-success); }
.gl-growth.dead .gl-growth-seg { background: color-mix(in srgb, var(--color-danger) 40%, transparent); }

.gl-outcome { font-size: var(--text-xs); color: var(--color-text-secondary); white-space: nowrap; }
.gl-contest-reason, .gl-metric-input {
  min-height: 30px; padding: 4px var(--space-xs);
  border: 1px solid var(--color-border); background: var(--color-surface);
  color: var(--color-text); border-radius: var(--radius-sm); font-size: var(--text-xs);
}
.gl-contest-reason { min-width: 210px; flex: 1; }
.gl-metric-input { width: 110px; }
.gl-metric-error { color: var(--color-danger); white-space: nowrap; }

/* ---------- 筛选 / 批量 ---------- */
.gl-filter { display: flex; gap: var(--space-md); align-items: center; flex-wrap: wrap; font-size: var(--text-sm); }
.gl-filter label { display: flex; align-items: center; gap: 6px; color: var(--color-text-secondary); }
.gl-filter select {
  min-height: 30px; padding: 4px var(--space-xs);
  border: 1px solid var(--color-border); background: var(--color-surface);
  color: var(--color-text); border-radius: var(--radius-sm); font-size: var(--text-sm);
}
.gl-batch-bar { display: flex; gap: var(--space-xs); align-items: center; flex-wrap: wrap; padding: var(--space-xs) 0; font-size: var(--text-sm); }
.gl-batch-select-all { display: flex; align-items: center; gap: 6px; font-size: var(--text-sm); cursor: pointer; }
.gl-th-check { width: 32px; }

.gl-sub-block { margin-top: var(--space-sm); }
.gl-sub-block h5 { margin: 0; font-size: var(--text-xs); font-weight: 600; color: var(--color-text-muted); letter-spacing: 0.04em; }
</style>
