<script setup lang="ts">
import { computed, defineAsyncComponent, inject, nextTick, ref, watch, type Ref } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw, type LocationQueryValue } from 'vue-router'
import EngagementRatingPanel from '../../components/EngagementRatingPanel.vue'
import EngagementSubmissionPanel from '../../components/EngagementSubmissionPanel.vue'
import RecommenderReputationBadge from '../../components/RecommenderReputationBadge.vue'
import OrgIdentityStrip from './components/OrgIdentityStrip.vue'
import OrgOverviewGrid, { type OrgSection } from './components/OrgOverviewGrid.vue'
import CommissionLadderSummary from './components/CommissionLadderSummary.vue'
import RecommenderRecommendations from './components/RecommenderRecommendations.vue'

// 任务书 #67 卡 I（2026-09-03 补齐）：非首屏页签（org/finance/hall/engagements/earnings）
// 的重资产卡片异步分包。首屏页签 tasks（商家侧默认）与 hall（推荐官侧默认）内的组件保持静态导入，
// 避免首屏占位闪烁。页签为 v-show 常驻 DOM：异步组件挂载时并行加载，此处仅是打包切分手段，
// 不改变懒加载语义。账号级内容（原 account/home 页签）自 #73 起收进个人设置弹窗，见 PersonalSettingsModal。
const MerchantKybCard = defineAsyncComponent(() => import('../../components/MerchantKybCard.vue'))
const StoreStaffCard = defineAsyncComponent(() => import('../../components/StoreStaffCard.vue'))
const MerchantCommerceCard = defineAsyncComponent(() => import('../../components/MerchantCommerceCard.vue'))
const MerchantPermissionCard = defineAsyncComponent(() => import('../../components/MerchantPermissionCard.vue'))
const MerchantMonthlyBillCard = defineAsyncComponent(() => import('../../components/MerchantMonthlyBillCard.vue'))
const PersonalSettingsModal = defineAsyncComponent(() => import('./components/PersonalSettingsModal.vue'))
const ComplaintModal = defineAsyncComponent(() => import('../../components/ComplaintModal.vue'))
const MyWalletCard = defineAsyncComponent(() => import('../../components/MyWalletCard.vue'))
const OrgTeamCard = defineAsyncComponent(() => import('../../components/OrgTeamCard.vue'))
const OrganizationBrandCard = defineAsyncComponent(() => import('../../components/OrganizationBrandCard.vue'))
const RecommenderTaskHall = defineAsyncComponent(() => import('./components/RecommenderTaskHall.vue'))
// 任务书 #77 卡 A：任务详情弹窗（含门店/品牌/媒体三公开面板——随弹窗迁移，工作台不再直挂）
const TaskDetailModal = defineAsyncComponent(() => import('./components/TaskDetailModal.vue'))
const MerchantTaskForm = defineAsyncComponent(() => import('./components/MerchantTaskForm.vue'))
const BusinessAnalyticsPanel = defineAsyncComponent(() => import('../../components/BusinessAnalyticsPanel.vue'))
const RecommenderIncomeStatsCard = defineAsyncComponent(() => import('../../components/RecommenderIncomeStatsCard.vue'))

import { useWorkbenchDisputes } from './composables/useWorkbenchDisputes'
import { useWorkbenchEngagements } from './composables/useWorkbenchEngagements'
import {
  APPLICATION_STATUS_BADGES, MY_TASK_FILTERS, MY_TASK_LIMIT_OPTIONS,
  useWorkbenchMyTasks, type MyTaskFilterId,
} from './composables/useWorkbenchMyTasks'
import { useWorkbenchSession } from './composables/useWorkbenchSession'
import { useWorkbenchTaskDrafts } from './composables/useWorkbenchTaskDrafts'
import { useWorkbenchTaskHall } from './composables/useWorkbenchTaskHall'
import { normalizeTaskCreationSelection, platformDisplayLabel } from '../../config/ai-platform-capabilities'
import { useAuth } from '../../composables/useAuth'
import { useGrassland } from '../../composables/useGrassland'
import { useAccountSessionStore } from '../../stores/account-session'
import type { ComplaintTargetType } from '../../composables/useComplaints'
import type { CreationEntry } from '../../types/ai-creation'
import type { NotificationLinkTarget } from '../../types/notification'
import type {
  MyApplication,
  OrgBrandSummary,
  OrgKybSummary,
  OrgPermissionSummary,
  OrgTeamSummary,
  Task,
} from '../../types/grassland'
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
// 账号会话票据（任务书 #84 C84-01）：watcher 本轮捕获的 ticket 贯穿初始化与 URL 恢复，
// A→B→A 时第一轮 A 的旧票（同 id 旧 epoch）不得恢复第三轮 A 的现场。
const session = useAccountSessionStore()
const emit = defineEmits<{
  'open-creation': [entry: CreationEntry]
}>()

const route = useRoute()
const router = useRouter()

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
  side, orgs, stores, managerStoreScopes,
  hasMerchantIdentity, hasRecommenderIdentity,
  activeOrgId, selectedStoreId, account, newOrgName, creditAmountYuan, walletBalanceCents,
  activeOrg, activeOrgHasOrganizationAccess, activeOrgStoreOnlyView, activeOrganizationRole,
  canPublishBounty,
  loadOrganizations, loadActiveOrganizationStores, initForAccount, createOrg, refreshAccount, changeOrganization,
  provision, credit, switchSide, reset: resetSession,
  pendingRename, renaming, requestRename,
} = useWorkbenchSession(grassland, {
  setNotice,
  // 任务书 #84：把初始化链的父级票据传给履约域（无票据入口由 engagements 自行 capture）。
  refreshTasks: (ticket) => engagements.refreshTasks(ticket),
})

const {
  deferredDisputeRequestId,
  disputePromptAppId, disputeChannel, dispute, cancelDispute, confirmDispute,
  reset: resetDisputes,
} = useWorkbenchDisputes(grassland, setNotice)

const {
  applyNote, feedItems, feedHasMore, feedLoading, feedFilters, feedPage, feedLimit, locating,
  myApplications,
  apply, loadFeed, loadFeedPrev, setFeedLimit, useCurrentLocation, handleFeedFilterUpdate,
  loadMyApplications,
  reset: resetTaskHall,
} = useWorkbenchTaskHall(grassland, side, setNotice)

// 任务书 #77 卡 D：「我的任务」主列表（my-applications 全量 + 四态筛选 + keyset 分页）
const {
  items: myTaskItems, loading: myTasksLoading, filter: myTaskFilter, limit: myTaskLimit,
  page: myTaskPage, hasMore: myTaskHasMore,
  load: loadMyTasksPage, setFilter: setMyTaskFilter, setLimit: setMyTaskLimit,
  loadPrev: loadMyTasksPrev, loadNext: loadMyTasksNext,
  reset: resetMyTasks,
} = useWorkbenchMyTasks(grassland, side)

/** 我的任务行徽标：已结算行（settledAt 非空）=「已完成」，其余按报名状态映射（卡 D 口径）。 */
function myTaskBadge(row: MyApplication): { label: string; cls: string } {
  if (row.settledAt) return { label: '已完成', cls: 'badge-success' }
  return APPLICATION_STATUS_BADGES[row.applicationStatus]
    ?? { label: row.applicationStatus, cls: 'badge-neutral' }
}

const engagements = useWorkbenchEngagements(grassland, setNotice, {
  side, activeOrgId, selectedStoreId, activeOrgStoreOnlyView, feedItems, refreshAccount,
})
const {
  tasks, applications, selectedTaskId, selectedTask,
  outcomes, taskContextLoadingAppId,
  contestReasons, confirmedMetricInputs,
  applicantReputation, applicantProfile, levelFilter, rateFilterPct,
  recommendations, recommendationsLoading, invitingAccountId, confirmedAppIds,
  selectedAppIds,
  filteredApplications, pendingFilteredApplications, allPendingSelected, batchButtonsDisabled,
  refreshTasks, publishDraft, closeTaskAction, cancelTaskAction,
  taskStatusLabel, isRejectedDraft, statusLabel, selectTask, toggleSelectTask, clearSelectedTask,
  loadRecommendations, inviteRecommended,
  accept, reject, toggleSelectAll, toggleSelectApp, batchAccept, batchReject,
  contest, selectedCommissionLadder, confirmedMetricResult, previewCommissionCents, confirm,
} = engagements

/**
 * 任务表单抽屉内的告警条（提交失败 / 本地校验错误）——失败信息必须出现在抽屉里，
 * 写到背景页会被抽屉整个盖住，表现就是「点了没反应」。
 */
const taskFormNotice = ref('')
/** 提交成功的结果弹窗文案；非空即弹（单按钮「知道了」）。 */
const taskFormResult = ref('')

/** 任务域通知路由：抽屉开着 → 抽屉内告警条；否则回落背景页通知条。 */
function setTaskFormNotice(message: string): void {
  if (taskFormOpen.value) taskFormNotice.value = message
  else setNotice(message)
}

const {
  taskForm, editingDraft, revisingTask,
  publishTask, saveDraft, editDraft, editPublished, resetTaskForm,
  updateCommissionLadder, handleTaskFormUpdate, handleTaskFormStoreChange, reset: resetTaskDrafts,
} = useWorkbenchTaskDrafts(grassland, setTaskFormNotice, { activeOrgId, selectedStoreId, refreshTasks })

/**
 * 任务表单抽屉开合（三种模式共用一个 MerchantTaskForm 实例）。
 *
 * 原先表单常驻「任务与报名」页签顶部：不管来干什么都占满首屏，且列表里的「编辑」按钮
 * 在表单下方——点了之后被改写的是已滚出视口的那张表单，唯一反馈是标题旁一行小字。
 * 收进抽屉后编辑是明确的模式切换，页签主体让给每天要看的任务与报名列表。
 */
const taskFormOpen = ref(false)

function openNewTaskForm(preset?: { commercePackageId?: string }): void {
  // 先清编辑上下文：从「编辑草稿」直接点「发布新任务」不能带着 editingDraft 进新建模式。
  // 任务书 #75：可预填套餐（MerchantCommerceCard「发推广任务」快捷入口）——预填即进套餐推广模式。
  resetTaskForm(preset?.commercePackageId ? { commercePackageId: preset.commercePackageId } : undefined)
  taskFormNotice.value = ''
  taskFormOpen.value = true
}

function openEditDraft(task: Task): void {
  editDraft(task)
  taskFormNotice.value = ''
  taskFormOpen.value = true
}

function openEditPublished(task: Task): void {
  editPublished(task)
  taskFormNotice.value = ''
  taskFormOpen.value = true
}

/** 关闭抽屉（×/取消/三选一确认的「直接退出」/干净表单直接关）：清表单并关抽屉。 */
function cancelTaskForm(): void {
  resetTaskForm()
  taskFormNotice.value = ''
  taskFormOpen.value = false
}

/**
 * 任务书 #78 卡 I：任务表单「去升级」——关表单并直达 org 页签的权限分节。
 * （basic_publish 主体在表单里看到赏金/押金灰死 + 解释条，这里给出路。）
 */
function goToPermissionUpgrade(): void {
  cancelTaskForm()
  subTab.value = 'org'
  orgSection.value = 'permission'
}

/**
 * 提交审核 / 存草稿（含修订）：成功 → 关抽屉 + 结果弹窗；失败 → 停留抽屉，
 * 错误显示在抽屉内告警条（本地校验错误经 setTaskFormNotice 已写入；后端 4xx 取 grassland.error）。
 */
async function publishTaskFromDrawer(): Promise<void> {
  taskFormNotice.value = ''
  const message = await publishTask()
  if (message != null) {
    taskFormOpen.value = false
    taskFormResult.value = message
  } else if (!taskFormNotice.value) {
    taskFormNotice.value = grassland.error.value || '提交失败，请稍后重试'
  }
}

async function saveDraftFromDrawer(): Promise<void> {
  taskFormNotice.value = ''
  const message = await saveDraft()
  if (message != null) {
    taskFormOpen.value = false
    taskFormResult.value = message
  } else if (!taskFormNotice.value) {
    taskFormNotice.value = grassland.error.value || '保存失败，请稍后重试'
  }
}

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

// 工作台子页签（任务书 #73 收敛）：两侧各留纯业务垄；账号级内容（主页与分享/账号与合规）
// 收进共享「个人设置」弹窗（两侧头部同一入口，组件 PersonalSettingsModal），页签不再承载。
// v-show 常驻 DOM（锚点滚动与既有断言不破坏）
// 任务书 #78 卡 A（D2）：`ai` 页签整体迁入 AI 创作中心「AI 与治理」板块，商家侧剩三签；
// `?wtab=ai` 深链因不在 activeTabs 自动回落 tasks。
type SubTabId = 'tasks' | 'org' | 'finance' | 'hall' | 'engagements' | 'earnings'
interface SubTab { id: SubTabId; label: string }
const MERCHANT_TABS: readonly SubTab[] = [
  { id: 'tasks', label: '任务与报名' },
  { id: 'org', label: '商家主体与门店' },
  { id: 'finance', label: '资金与经营' },
]
const RECOMMENDER_TABS: readonly SubTab[] = [
  { id: 'hall', label: '任务大厅' },
  // 任务书 #77 卡 D：「我的履约」改造为「我的任务」（全量 my-applications + 四态筛选）；
  // 用户拍板不新增页签，subTab id `engagements` 保留（URL ?wtab= 深链与锚点映射不破坏）。
  { id: 'engagements', label: '我的任务' },
  { id: 'earnings', label: '收益与结算' },
]
const subTab = ref<SubTabId>('tasks')
// 切进「我的任务」页签时刷新当前页：大厅报名后切回即可见最新报名——报名动作
// 不在此列表域内，若不刷新则停留在打开工作台时的快照（2026-09-06 e2e 实锤）。
watch(subTab, (tab) => {
  if (tab === 'engagements' && side.value === 'recommender' && !myTasksLoading.value) {
    void loadMyTasksPage(false)
  }
})
/** 个人设置弹窗（#73）：账号级内容（主页与分享/账号与合规）的共享入口，两侧头部同一按钮。 */
const personalSettingsOpen = ref(false)
/**
 * 个人设置弹窗活动分节（2026-09-04 反馈 6/7：左栏分节重构 + 判例库入驻）。
 * 深链 ?settings=<section> 落点；'1' 为旧 /complaints 深链的兼容值（默认节）。
 */
const personalSettingsSection = ref('complaints')

/**
 * 场景化举报弹窗（任务书 #74）：对象由业务卡带入并锁定（D5），两侧身份共享一份。
 * 大厅任务行 / 商家报名行 / 履约交付物块三个入口统一走 openComplaint 预填目标。
 */
interface ComplaintTarget {
  targetType: ComplaintTargetType
  targetId: string
  targetSummary: string
}
const complaintOpen = ref(false)
const pendingComplaintTarget = ref<ComplaintTarget | null>(null)
function openComplaint(target: ComplaintTarget): void {
  pendingComplaintTarget.value = target
  complaintOpen.value = true
}
const activeTabs = computed<readonly SubTab[]>(() =>
  side.value === 'merchant' ? MERCHANT_TABS : RECOMMENDER_TABS)
// 切换身份后当前页签可能不存在于另一侧的列表：回落到该侧首页签。
// immediate：工作台常在登录完成**之后**才挂载（side 已定型，无翻转事件可听）。
watch(activeTabs, (tabs) => {
  if (!tabs.some((tab) => tab.id === subTab.value)) subTab.value = tabs[0].id
}, { immediate: true })
/**
 * 「商家主体与门店」页签内的二级分节。
 *
 * 原先这一屏是 5 张全宽卡竖着堆（子组件合计 2600+ 行），认证状态与额度余量埋在第 2、
 * 第 5 张卡内部要滚屏才看到。改为「身份条 + 概览 + 左侧竖栏分节」：常驻页眉答「我是谁、
 * 现在什么状态」，概览答「哪项缺、去哪补」，五个域各自独立成节，首屏高度从五卡叠加降到一节。
 */
const ORG_SECTIONS: readonly { id: OrgSection; label: string }[] = [
  { id: 'overview', label: '概览' },
  { id: 'team', label: '成员与门店' },
  { id: 'brand', label: '品牌资料' },
  { id: 'kyb', label: '认证资料' },
  { id: 'permission', label: '权限与额度' },
]
const orgSection = ref<OrgSection>('overview')

/**
 * 「资金与经营」页签内的二级分节（任务书 #78 卡 J，镜像 ORG_SECTIONS 左栏模式）：
 * 原先四块全宽卡竖着堆（钱包/账单/套餐/经营分析），经营分析埋在最底要滚屏才看到。
 */
type FinanceSection = 'account' | 'bill' | 'commerce' | 'analytics'
const FINANCE_SECTIONS: readonly { id: FinanceSection; label: string }[] = [
  { id: 'account', label: '资金账户' },
  { id: 'bill', label: '月度账单' },
  { id: 'commerce', label: '到店套餐与核销' },
  { id: 'analytics', label: '经营分析' },
]
const financeSection = ref<FinanceSection>('account')

/**
 * 四张子卡冒泡上来的摘要（概览与身份条的数据源）。
 *
 * 分节用 `v-show` 而非 `v-if`：子卡常驻挂载才能在进概览时就已经有摘要可显示，
 * 也保住锚点滚动与既有测试断言（隐藏元素仍在 DOM 里）。
 * 换主体时清空——旧主体的数字停留在概览上就是错的事实。
 */
const teamSummary = ref<OrgTeamSummary | null>(null)
const brandSummary = ref<OrgBrandSummary | null>(null)
const kybSummary = ref<OrgKybSummary | null>(null)
const permissionSummary = ref<OrgPermissionSummary | null>(null)

watch(activeOrgId, () => {
  teamSummary.value = null
  brandSummary.value = null
  kybSummary.value = null
  permissionSummary.value = null
  orgSection.value = 'overview'
  financeSection.value = 'account'
})

/**
 * 身份条把新 orgId 直接抛上来（原先是 select 的 v-model + @change 两步）。
 * `changeOrganization` 读的是 `activeOrgId`，所以先写值再调它。
 */
async function selectOrganization(orgId: string): Promise<void> {
  if (orgId === activeOrgId.value) return
  activeOrgId.value = orgId
  await changeOrganization()
}

/** 通知锚点 → 所属子页签（按身份侧）：滚动前先切页签（隐藏元素无法 scrollIntoView）。 */
const ANCHOR_TAB: Readonly<Record<'merchant' | 'recommender', Readonly<Record<string, SubTabId>>>> = {
  merchant: {
    'gl-engagements': 'tasks',
    'gl-organizations': 'org',
    'gl-wallet': 'finance',
  },
  recommender: {
    'gl-wallet': 'earnings',
    'gl-task-hall': 'hall',
    'gl-engagements': 'engagements',
  },
}

/** 通知锚点 → 二级分节（任务书 #78 卡 J）：先切 subTab、再切分节、最后滚动。 */
const ANCHOR_FINANCE_SECTION: Readonly<Record<string, FinanceSection>> = {
  'gl-wallet': 'account',
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

/**
 * 已报名成功人数（accepted + reserving，reserving 是资金预留中的在途态）。
 * 有人报名成功后任务不可再修订（PRD §2.3）；前端禁用「编辑」按钮 + 行内原因，
 * 后端 revise 端点另有 409 守卫兜底（API 直调也堵死）。列表行没带 progress 时取 0——
 * 容许编辑，提交时由后端拦下。
 */
function acceptedApplicationCount(task: Task): number {
  return task.progress?.acceptedApplicationCount ?? 0
}

/**
 * 已报名成功（accepted）→ 任务创作快照链（任务书 #23 R6 / #76）。
 * #77 卡 D：参数化 application + task——商家报名列表（选中任务上下文）、「我的任务」列表/详情弹窗
 * （无选中任务上下文，task 由调用方补齐）三处复用，不再依赖 selectedTaskId。
 */
async function openAcceptedTaskCreation(
  application: { id: string; taskId: string; status: string },
  task: Task | null,
): Promise<void> {
  if (!task || application.status !== 'accepted' || application.taskId !== task.id
      || taskContextLoadingAppId.value) return
  // 任务书 #23 R6：点赞互动任务无内容交付，「围绕任务创作」入口隐藏。
  if (task.contentForm === 'interaction') {
    setNotice('点赞互动任务无需内容创作，直接提交互动截图即可')
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

/** 「我的任务」行内开始创作：列表投影行无任务详情，先拉任务再走快照链（#77 卡 D）。 */
async function openMyTaskCreation(app: MyApplication): Promise<void> {
  if (taskContextLoadingAppId.value) return
  taskContextLoadingAppId.value = app.applicationId
  const task = await grassland.getTask(app.taskId)
  taskContextLoadingAppId.value = ''
  if (!task) {
    setNotice(grassland.error.value || '任务详情加载失败，请稍后重试')
    return
  }
  await openAcceptedTaskCreation({ id: app.applicationId, taskId: app.taskId, status: app.applicationStatus }, task)
}

/**
 * 推荐官侧打开任务详情弹窗（#77 卡 A）：只设选中 id（详情/公开资料由弹窗自取），
 * 不走 selectTask 的报名列表/画像加载（推荐官侧用不上，白拉两次请求）。
 * from='my-tasks' 时隐藏「报名」入口——列表行本身就是一条报名（#77 卡 D）。
 */
const detailShowApply = ref(true)
function openTaskDetail(taskId: string, options?: { from?: 'hall' | 'my-tasks' }): void {
  detailShowApply.value = options?.from !== 'my-tasks'
  selectedTaskId.value = taskId
}

/** 弹窗关闭：清选中态 + 收起未确认的争议通道选择（防残留到下一次打开）。 */
function closeTaskDetail(): void {
  cancelDispute()
  clearSelectedTask()
}

/** 弹窗 footer「开启争议」：dispute() 只读 application id。 */
function openDetailDispute(applicationId: string): void {
  dispute({ id: applicationId })
}

/** 弹窗 footer「开始创作」：task/application 整包由弹窗抛出（大厅与我的任务两挂载共用）。 */
function startCreationFromDetail(payload: { task: Task; application: MyApplication | null }): void {
  if (!payload.application) return
  void openAcceptedTaskCreation(
    { id: payload.application.applicationId, taskId: payload.task.id, status: payload.application.applicationStatus },
    payload.task,
  )
}

/**
 * #77 卡 D3：pending 报名取消（大厅行内/详情弹窗/我的任务列表三入口共用）。
 * 确认文案必须警示「撤销后不可重新报名该任务」——V2 全表 UNIQUE 阻断重报是刻意设计。
 */
function confirmWithdrawMyApplication(app: MyApplication): void {
  if (!window.confirm(`撤销对任务「${app.taskTitle ?? ''}」的报名？撤销后不可重新报名该任务。`)) return
  void withdrawMyApplication(app)
}

async function withdrawMyApplication(app: MyApplication): Promise<void> {
  const withdrawn = await grassland.withdrawApplication(app.taskId, app.applicationId)
  if (!withdrawn) return
  setNotice('已撤销报名')
  // 我的报名映射驱动大厅行徽标与详情弹窗操作态；列表页签刷新当前页（撤销后状态就地更新）
  await loadMyApplications()
  await loadMyTasksPage(false)
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
  resetMyTasks()
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
    // 留存进入时的原始 query：初始化期间 urlQuerySnapshot watcher 会按默认态重写 URL，
    // 直接读 route.query 会丢深链（?wtab= 曾被这样吃掉）。
    const entryQuery: Record<string, LocationQueryValue | LocationQueryValue[]> = { ...route.query }
    // 本轮初始化票据（任务书 #84 C84-01，D84-01/D84-03）：account-session 的 sync watcher
    // 先于本回调递增 epoch，这里 capture 到的必是新轮回的票；同一张票传进初始化链。
    const ticket = session.capture()
    initializingAccount = true
    try {
      await initForAccount(ticket)
    } finally {
      initializingAccount = false
    }
    // 初始化期间可能又换了账号——旧账号的 URL 恢复直接放弃，避免上一个链接串数据。
    // 按 accountId+epoch 验票（任务书 #84）：A→B→A 时第一轮 A 与第三轮 A 同 id 不同票，
    // 只比 id 会让第一轮 A 的入口 query 污染第三轮现场。
    if (session.isCurrent(ticket)) await restoreWorkbenchStateFromUrl(entryQuery)
  }
}, { immediate: true })

// ---------- URL 状态同步（Web Interface Guidelines：URL 反映视图状态） ----------
// 视角 / 选中任务 / 报名筛选 / 大厅筛选随 query 持久化——刷新、分享链接可恢复现场。
// 恢复必须在 initForAccount 之后：它按已开通身份重排 side，先恢复会被覆盖。

const OWNED_QUERY_KEYS = ['side', 'wtab', 'task', 'level', 'rate', 'q', 'platform', 'contentForm', 'minBounty', 'dist', 'settings'] as const
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
  if (subTab.value !== activeTabs.value[0].id) query.wtab = subTab.value
  if (selectedTaskId.value) query.task = selectedTaskId.value
  if (levelFilter.value) query.level = levelFilter.value
  if (rateFilterPct.value > 0) query.rate = String(rateFilterPct.value)
  if (feedFilters.value.q.trim()) query.q = feedFilters.value.q.trim()
  if (feedFilters.value.platform.trim()) query.platform = feedFilters.value.platform.trim()
  if (feedFilters.value.contentForm.trim()) query.contentForm = feedFilters.value.contentForm.trim()
  if (feedFilters.value.minBountyYuan > 0) query.minBounty = String(feedFilters.value.minBountyYuan)
  if (feedFilters.value.maxDistanceKm > 0) query.dist = String(feedFilters.value.maxDistanceKm)
  // 任务书 #74 D3：settings 是「弹窗开合不进 query」的唯一例外（外部深链入口：旧 /complaints
  // 深链、首页「平台治理」卡、/precedents 改道），需要可分享；关闭弹窗时随快照变化从 URL 移除。
  // 值为分节 id（2026-09-04 反馈 6/7）——'1' 是旧 /complaints 深链的兼容值。
  if (personalSettingsOpen.value) query.settings = personalSettingsSection.value
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

/** URL → 状态：账号初始化完成后执行一次（读进入时的原始 query，见调用点注释）。无效值一律忽略。 */
async function restoreWorkbenchStateFromUrl(
  query: Record<string, LocationQueryValue | LocationQueryValue[]>,
): Promise<void> {
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

  // 深链 ?side= 只在目标侧已开通时切换；未开通侧回落本侧（自助开口已关，D9）
  const sideParam = firstQueryParam(query.side)
  const sideOpened = sideParam === 'merchant' ? hasMerchantIdentity.value
    : sideParam === 'recommender' ? hasRecommenderIdentity.value : false
  if ((sideParam === 'merchant' || sideParam === 'recommender') && sideParam !== side.value && sideOpened) {
    await switchSide(sideParam)
  }
  const wtabParam = firstQueryParam(query.wtab)
  if (wtabParam && activeTabs.value.some((tab) => tab.id === wtabParam)) {
    subTab.value = wtabParam as SubTabId
  }
  // 任务书 #74 D3：?settings=<section> 深链自动打开个人设置弹窗并定位分节（旧 /complaints、
  // /precedents 改道落点）；'1' 为旧兼容值（默认节=举报与投诉）。
  // 关闭时的 URL 清除不需要这里管——personalSettingsOpen 翻 false 后快照 watcher 会移除该参数。
  const settingsParam = firstQueryParam(query.settings)
  if (settingsParam === '1' || settingsParam === 'complaints') {
    personalSettingsSection.value = 'complaints'
    personalSettingsOpen.value = true
  } else if (settingsParam === 'precedents') {
    personalSettingsSection.value = 'precedents'
    personalSettingsOpen.value = true
  } else if (settingsParam === 'account') {
    personalSettingsSection.value = 'account'
    personalSettingsOpen.value = true
  } else if (settingsParam === 'profile' && side.value === 'recommender') {
    personalSettingsSection.value = 'profile'
    personalSettingsOpen.value = true
  }
  // side 未变化时（如换账号前后同为 recommender）composable 的 side watch 不触发，
  // feed 首页不会自动拉——这里补一次，保证恢复的筛选条件有数据可筛。
  if (side.value === 'recommender' && feedItems.value.length === 0) {
    await loadFeed(true)
  }
  // 我的任务同坑（#77 卡 D 落地于 CI 红灯期未被 e2e 验证，2026-09-06 实测 tab 点击
  // 不触发加载、side 恒为 recommender 时首屏永远空态）——与 feed 同款补拉。
  if (side.value === 'recommender' && myTaskItems.value.length === 0) {
    await loadMyTasksPage(true)
  }
  const taskParam = firstQueryParam(query.task)
  if (taskParam) {
    // #77 卡 A：推荐官侧 ?task= 深链 = 打开详情弹窗（轻量设选中 id，详情弹窗自取）；
    // 商家侧维持 selectTask（任务与报名列表的行内展开依赖选中任务的报名全量）。
    if (side.value === 'merchant') await selectTask(taskParam)
    else openTaskDetail(taskParam)
  }
}

// 注：未开通的侧不再自动开通（自助开口已关，2026-09-04 身份模型改版）——
// 深链/通知落点对未开通侧一律回落本侧，切侧入口对未开通侧隐藏。

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
  const tabForAnchor = ANCHOR_TAB[side.value][anchor]
  if (tabForAnchor) subTab.value = tabForAnchor
  // 任务书 #78 卡 J：finance 页签有二级分栏——钱包锚点落「资金账户」分节再滚（v-show 隐藏元素滚不动）。
  if (tabForAnchor === 'finance' && ANCHOR_FINANCE_SECTION[anchor]) {
    financeSection.value = ANCHOR_FINANCE_SECTION[anchor]
  }
  await nextTick()
  scrollBlockIntoView(anchor)
  grasslandAnchor.value = ''
}, { immediate: true })

/** Task invitations are the only notification route that intentionally selects a role and exact task.
 *  争议通知自 2026-09-04 起在 DefaultLayout 直达 /me/disputes/:id，不再进工作台。 */
watch(grasslandNavigationTarget, async (target) => {
  if (target?.disputeId) {
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
      subTab.value = 'tasks'
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
    // #77 卡 A：邀请任务落点 = 打开详情弹窗（feed 已插入任务本体，弹窗免拉详情）
    openTaskDetail(task.id)
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
        <p class="gl-sub">{{ side === 'merchant' ? '发布任务、筛选推荐官报名、确认履约与资金结算' : '浏览任务大厅、报名接单、提交凭证与查看收益' }}</p>
      </div>
      <div class="gl-head-actions">
        <button type="button" aria-label="打开个人设置" @click="personalSettingsOpen = true">个人设置</button>
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

    <!-- ============ 商家工作台 ============ -->
    <div v-if="side === 'merchant'" id="gl-panel-merchant" aria-label="商家工作台" tabindex="0" class="gl-workbench" data-side="merchant">
      <!-- 无任何主体关联时的入驻引导（自建或被邀请加入都算有关联） -->
      <section v-if="orgs.length === 0" class="gl-zone" aria-label="创建商家主体">
        <div class="gl-zone-head">
          <h3 class="gl-zone-title">创建你的商家主体</h3>
          <p class="gl-zone-note">商家主体是门店、成员、任务、素材与资金的归属（PRD §2.1）</p>
        </div>
        <div class="gl-zone-body">
          <article class="gl-tile gl-tile-wide">
            <h3>第一步：填写主体名称</h3>
            <div class="gl-row">
              <input v-model="newOrgName" aria-label="商家主体名称" name="onboarding-org-name" autocomplete="off" placeholder="如：云朵餐饮 / 张三甜品店" @keyup.enter="createOrg" />
              <button type="button" :disabled="grassland.loading.value" @click="createOrg">创建商家主体</button>
            </div>
            <p class="gl-hint">创建后即可添加门店、邀请成员并发布推广任务；认证资料与品牌信息可在「商家主体与门店」页签继续完善。</p>
          </article>
        </div>
      </section>

      <nav class="gl-subtabs" role="tablist" aria-label="商家工作台模块">
        <button
          v-for="tab in MERCHANT_TABS"
          :key="tab.id"
          type="button"
          role="tab"
          class="gl-subtab"
          :class="{ 'gl-subtab-active': subTab === tab.id }"
          :aria-selected="subTab === tab.id"
          :tabindex="subTab === tab.id ? 0 : -1"
          @click="subTab = tab.id"
        >{{ tab.label }}</button>
      </nav>

      <!-- 子页签① 任务与报名：田垄②主操作区——每天干活的地方 -->
      <section v-show="subTab === 'tasks'" class="gl-zone" aria-label="发布与撮合">
        <div class="gl-zone-head">
          <h3 class="gl-zone-title">发布与撮合</h3>
          <p class="gl-zone-note">任务沿 草稿 → 审核 → 招募 → 履约 → 结算 的生长线推进</p>
          <!-- 发布是偶发动作：入口收进垄眉，表单在抽屉里填（页签主体让给任务与报名列表） -->
          <button type="button" class="gl-btn-primary gl-zone-action" :disabled="!activeOrgId || grassland.loading.value" @click="openNewTaskForm()">发布新任务</button>
          <!-- 2026-09-04 反馈 5：商家侧争议（含「拒绝并转客服」生成的客服案）在 /me/disputes 查看 -->
          <button type="button" class="gl-zone-action" @click="router.push('/me/disputes')">我的争议 →</button>
        </div>
        <div class="gl-zone-body">
          <MerchantTaskForm
            :open="taskFormOpen"
            :form="taskForm"
            :editing-draft="editingDraft"
            :revising-task="revisingTask"
            :stores="stores"
            :selected-store-id="selectedStoreId"
            :active-org-id="activeOrgId"
            :can-publish-bounty="canPublishBounty"
            :loading="grassland.loading.value"
            :notice="taskFormNotice"
            @update:field="handleTaskFormUpdate"
            @update:commission-ladder="updateCommissionLadder"
            @change-store="handleTaskFormStoreChange"
            @publish="publishTaskFromDrawer"
            @save-draft="saveDraftFromDrawer"
            @close="cancelTaskForm"
            @go-upgrade-permission="goToPermissionUpgrade"
          />

          <article id="gl-engagements" class="gl-tile gl-tile-wide">
            <h3>任务与报名</h3>
            <p v-if="tasks.length === 0" class="gl-empty">暂无任务</p>
            <ul class="gl-list">
              <li v-for="t in tasks" :key="t.id">
                <div class="gl-task-main">
                  <button
                    type="button"
                    class="gl-link"
                    :class="{ active: selectedTaskId === t.id }"
                    :aria-expanded="selectedTaskId === t.id"
                    @click="toggleSelectTask(t.id)"
                  >
                    {{ t.title }}
                  </button>
                  <!-- 任务书 #53：被驳回退回的草稿标「已驳回·待修改」（danger），其余按状态取 neutral -->
                  <span class="badge" :class="isRejectedDraft(t) ? 'badge-danger' : 'badge-neutral'">{{
                    isRejectedDraft(t) ? '已驳回·待修改' : taskStatusLabel(t.status)
                  }}</span>
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
                <!-- 任务书 #53：被驳回的草稿在行下展示驳回原因（重新提交/通过后后端置 null，不再显示） -->
                <p v-if="isRejectedDraft(t)" class="gl-hint gl-reject-hint">
                  驳回原因：{{ t.lastRejectedNote || '平台未填写原因' }}
                </p>
                <div class="gl-task-actions">
                  <!-- 草稿：编辑 / 提交审核 / 取消 -->
                  <template v-if="t.status === 'draft'">
                    <button type="button" :disabled="grassland.loading.value" @click="openEditDraft(t)">编辑</button>
                    <button type="button" :disabled="grassland.loading.value" @click="publishDraft(t)">提交审核</button>
                    <button type="button" :disabled="grassland.loading.value" @click="confirmCancelTask(t)">取消</button>
                  </template>
                  <!-- 待审核：平台内容审核中，仅可取消（编辑需先驳回或取消重建） -->
                  <template v-else-if="t.status === 'pending_review'">
                    <span class="gl-hint">平台审核中</span>
                    <button type="button" :disabled="grassland.loading.value" @click="confirmCancelTask(t)">取消</button>
                  </template>
                  <!-- 已发布：编辑出新版本（有人报名成功即禁用，PRD §2.3）/ 关闭报名 / 取消 -->
                  <template v-else-if="t.status === 'published'">
                    <button
                      type="button"
                      :disabled="grassland.loading.value || acceptedApplicationCount(t) > 0"
                      @click="openEditPublished(t)"
                    >编辑</button>
                    <span v-if="acceptedApplicationCount(t) > 0" class="gl-hint gl-reject-hint">
                      已有 {{ acceptedApplicationCount(t) }} 名推荐官报名成功，任务不可再修改
                    </span>
                    <button type="button" :disabled="grassland.loading.value" @click="closeTaskAction(t)">关闭报名</button>
                    <button type="button" :disabled="grassland.loading.value" @click="confirmCancelTask(t)">取消任务</button>
                  </template>
                </div>
              </li>
            </ul>

            <div v-if="selectedTaskId" class="gl-apps">
              <!-- 展开块头部：给一个明确的「收起」出口——selectTask 此前只设不清，块一旦展开永远开着 -->
              <div class="gl-apps-head">
                <span class="gl-apps-caption">「{{ selectedTask?.title ?? '' }}」的推荐官排序与报名</span>
                <button
                  type="button"
                  class="gl-apps-collapse"
                  :aria-label="`收起 ${selectedTask?.title ?? ''} 的推荐官排序与报名列表`"
                  @click="clearSelectedTask"
                >收起</button>
              </div>
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
                            <button type="button" :disabled="Boolean(taskContextLoadingAppId)" @click="openAcceptedTaskCreation(a, selectedTask)">
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
                          <!-- 任务书 #74：场景化举报——对象是这名推荐官（user），全状态行可见 -->
                          <button
                            type="button"
                            :aria-label="`举报推荐官 ${a.recommenderAccountId.slice(0, 8)}`"
                            @click="openComplaint({
                              targetType: 'user',
                              targetId: a.recommenderAccountId,
                              targetSummary: `推荐官 ${a.recommenderAccountId.slice(0, 8)}…`,
                            })"
                          >举报</button>
                        </td>
                        <td class="gl-outcome gl-num">{{ outcomes[a.id] || '—' }}</td>
                      </tr>
                    </tbody>
                  </table>
                </template>

                <!-- 交付物 + 评分：确认履约前必须有一份待核验的（后端 409 守卫）；评分须先确认履约。 -->
                <template v-for="a in applications" :key="`sub-${a.id}`">
                  <div v-if="a.status === 'accepted'" class="gl-sub-block">
                    <h5>
                      履约交付物 · <code>{{ a.recommenderAccountId.slice(0, 8) }}…</code>
                      <!-- 任务书 #74：场景化举报——对象是这份交付物（submission=applicationId） -->
                      <button
                        type="button"
                        :aria-label="`举报履约交付物 ${a.id.slice(0, 8)}`"
                        @click="openComplaint({
                          targetType: 'submission',
                          targetId: a.id,
                          targetSummary: `任务「${selectedTask?.title ?? ''}」的履约交付物（${a.recommenderAccountId.slice(0, 8)}…）`,
                        })"
                      >举报</button>
                    </h5>
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

      <!-- 子页签② 组织与门店：身份条常驻 + 左栏五分节（概览 / 成员门店 / 品牌 / 认证 / 权限） -->
      <section v-show="subTab === 'org'" class="gl-zone" aria-label="商家主体与门店">
        <div class="gl-zone-head">
          <h3 class="gl-zone-title">商家主体与门店</h3>
          <p class="gl-zone-note">商家主体、成员、品牌与认证资料</p>
        </div>

        <!-- 身份条：取代原「我的商家主体」磁贴；id 保留给通知锚点 -->
        <div id="gl-organizations">
          <OrgIdentityStrip
            :orgs="orgs"
            :active-org-id="activeOrgId"
            :active-org="activeOrg"
            :has-organization-access="activeOrgHasOrganizationAccess"
            :can-publish-bounty="canPublishBounty"
            :can-rename="Boolean(activeOrg) && activeOrgHasOrganizationAccess"
            :pending-rename="pendingRename"
            :renaming="renaming"
            :loading="grassland.loading.value"
            :kyb="kybSummary"
            :permission="permissionSummary"
            :team="teamSummary"
            @change-org="selectOrganization"
            @rename="requestRename"
          />
        </div>

        <!-- 门店工作台（#52 决策 H）：挂店 member（店长）视图——组织角色为 member 且有门店范围。
             #52 后建号一律入池，店长也有组织身份，故不再以「无组织身份」判流。 -->
        <div
          v-if="activeOrgStoreOnlyView"
          class="gl-zone-body"
        >
          <article
            v-if="activeOrg && managerStoreScopes.some((scope) => scope.organizationId === activeOrgId)"
            class="gl-tile gl-tile-wide"
          >
            <MerchantKybCard
              :org-id="activeOrgId"
              store-only
              :stores="stores.map((store) => ({ id: store.id, name: store.name }))"
              @changed="() => loadOrganizations()"
            />
          </article>
          <article class="gl-tile gl-tile-wide">
            <StoreStaffCard :org-id="activeOrgId" :stores="stores" />
          </article>
        </div>

        <div v-else-if="activeOrg" class="org-split">
          <!-- 左栏：竖向分节（与一级横向 pill 正交，避免两行横导航叠着） -->
          <nav class="org-rail" role="tablist" aria-label="商家主体分节">
            <button
              v-for="section in ORG_SECTIONS"
              :key="section.id"
              type="button"
              role="tab"
              class="org-rail-item"
              :class="{ 'org-rail-active': orgSection === section.id }"
              :aria-selected="orgSection === section.id"
              :tabindex="orgSection === section.id ? 0 : -1"
              @click="orgSection = section.id"
            >{{ section.label }}</button>
          </nav>

          <!-- 右栏：分节内容。v-show 常驻 —— 子卡保持挂载，摘要才能在概览就绪 -->
          <div class="org-panel">
            <div v-show="orgSection === 'overview'" class="org-panel-section">
              <OrgOverviewGrid
                :kyb="kybSummary"
                :permission="permissionSummary"
                :team="teamSummary"
                :brand="brandSummary"
                @open="(section: OrgSection) => orgSection = section"
              />
            </div>

            <!-- 成员与门店：Slice 2F/2G/2J 的三级权限自助管理 -->
            <div v-show="orgSection === 'team'" class="org-panel-section">
              <OrgTeamCard
                :org-id="activeOrg.id"
                @stores-changed="loadActiveOrganizationStores"
                @summary="(summary: OrgTeamSummary) => teamSummary = summary"
              />
            </div>

            <!-- 组织品牌资料（#32）：独立于门店资料（KYB 卡的门店 tab）；member 只读，owner/admin 可编辑 -->
            <div v-show="orgSection === 'brand'" class="org-panel-section">
              <OrganizationBrandCard
                :org-id="activeOrg.id"
                :role="activeOrganizationRole"
                @summary="(summary: OrgBrandSummary) => brandSummary = summary"
              />
            </div>

            <!-- KYB 商家资料：GL-P3-MERCHANT-001 -->
            <div v-show="orgSection === 'kyb'" class="org-panel-section">
              <MerchantKybCard
                :org-id="activeOrg.id"
                :stores="stores.map((store) => ({ id: store.id, name: store.name }))"
                @changed="() => loadOrganizations()"
                @summary="(summary: OrgKybSummary) => kybSummary = summary"
              />
            </div>

            <!-- 权限与额度：D-05 的商家侧入口（升级申请 / 申诉 / 额度已用-上限） -->
            <div v-show="orgSection === 'permission'" class="org-panel-section">
              <MerchantPermissionCard
                :org-id="activeOrg.id"
                :tier="activeOrg.permissionTier"
                :industry="activeOrg.industry"
                @changed="loadOrganizations"
                @summary="(summary: OrgPermissionSummary) => permissionSummary = summary"
              />
            </div>
          </div>
        </div>
      </section>

      <!-- 子页签③ 资金与经营：二级分栏（任务书 #78 卡 J，org 左栏同款）——资金账户 / 月度账单 / 到店套餐与核销 / 经营分析 -->
      <section v-show="subTab === 'finance'" class="gl-zone" aria-label="资金与经营">
        <div class="gl-zone-head">
          <h3 class="gl-zone-title">资金与经营</h3>
          <p class="gl-zone-note">余额与充值、月度账单、核销订单与营收分析</p>
        </div>
        <div class="gl-zone-body">
          <div class="org-split">
            <nav class="org-rail" role="tablist" aria-label="资金与经营分节">
              <button
                v-for="section in FINANCE_SECTIONS"
                :key="section.id"
                type="button"
                role="tab"
                class="org-rail-item"
                :class="{ 'org-rail-active': financeSection === section.id }"
                :aria-selected="financeSection === section.id"
                :tabindex="financeSection === section.id ? 0 : -1"
                @click="financeSection = section.id"
              >{{ section.label }}</button>
            </nav>

            <!-- 右栏：分节内容。v-show 常驻 —— #gl-wallet 通知锚点断言 + 组件不重挂载不重拉数据 -->
            <div class="org-panel">
              <div v-show="financeSection === 'account'" class="org-panel-section">
                <!-- id 与推荐官侧钱包卡同名：两侧是 v-if/v-else，同一时刻只有一个在 DOM 里 -->
                <article v-if="(activeOrgHasOrganizationAccess && !activeOrgStoreOnlyView) || managerStoreScopes.length === 0"
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
              </div>

              <div v-show="financeSection === 'bill'" class="org-panel-section">
                <article v-if="activeOrgId" class="gl-tile gl-tile-wide">
                  <MerchantMonthlyBillCard :organization-id="activeOrgId" />
                </article>
              </div>

              <div v-show="financeSection === 'commerce'" class="org-panel-section">
                <article v-if="activeOrgId" class="gl-tile gl-tile-wide">
                  <MerchantCommerceCard
                    :organization-id="activeOrgId"
                    :store-id="selectedStoreId || undefined"
                    @create-promotion-task="openNewTaskForm({ commercePackageId: $event })"
                    @go-tasks="subTab = 'tasks'"
                  />
                </article>
              </div>

              <div v-show="financeSection === 'analytics'" class="org-panel-section">
                <article v-if="activeOrgId" class="gl-tile gl-tile-wide">
                  <BusinessAnalyticsPanel :organization-id="activeOrgId" :store-id="selectedStoreId" />
                </article>
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>

    <!-- ============ 推荐官工作台 ============ -->
    <div v-else id="gl-panel-recommender" aria-label="推荐官工作台" tabindex="0" class="gl-workbench" data-side="recommender">
      <nav class="gl-subtabs" role="tablist" aria-label="推荐官工作台模块">
        <button
          v-for="tab in RECOMMENDER_TABS"
          :key="tab.id"
          type="button"
          role="tab"
          class="gl-subtab"
          :class="{ 'gl-subtab-active': subTab === tab.id }"
          :aria-selected="subTab === tab.id"
          :tabindex="subTab === tab.id ? 0 : -1"
          @click="subTab = tab.id"
        >{{ tab.label }}</button>
      </nav>

      <!-- 子页签 主页与分享（#73 起收进个人设置弹窗，页签位不再渲染） -->

      <!-- 田垄③′：任务大厅——找活儿的地方（#77 卡 A：详情改弹窗，zone 内不再挂公开面板） -->
      <section id="gl-task-hall" v-show="subTab === 'hall'" class="gl-zone" aria-label="任务大厅">
        <div class="gl-zone-head">
          <h3 class="gl-zone-title">任务大厅</h3>
          <p class="gl-zone-note">只显示已发布且未截止的任务</p>
        </div>
        <div class="gl-zone-body">
          <RecommenderTaskHall
            :feed-items="feedItems"
            :feed-has-more="feedHasMore"
            :feed-loading="feedLoading"
            :feed-page="feedPage"
            :feed-limit="feedLimit"
            :feed-filters="feedFilters"
            :apply-note="applyNote"
            :selected-task-id="selectedTaskId"
            :loading="grassland.loading.value"
            :locating="locating"
            :wallet-balance-cents="walletBalanceCents"
            :my-applications="myApplications"
            @update:feed-filter="handleFeedFilterUpdate"
            @load-feed="loadFeed"
            @load-feed-prev="loadFeedPrev"
            @update:feed-limit="setFeedLimit"
            @update:apply-note="applyNote = $event"
            @select-task="openTaskDetail"
            @apply="apply"
            @withdraw="confirmWithdrawMyApplication"
            @start-creation="startCreationFromDetail"
            @report-task="openComplaint({ targetType: 'task', targetId: $event.id, targetSummary: $event.title })"
            @use-location="useCurrentLocation"
          />
        </div>
      </section>

      <!-- 田垄④′：我的任务（任务书 #77 卡 D：原「我的履约与争议」页签改造——不新增页签，
           主列表 = my-applications 全量 + 四态筛选 + 分页；履约操作收进详情弹窗，与选中任务解绑） -->
      <section v-show="subTab === 'engagements'" class="gl-zone" aria-label="我的任务">
        <div class="gl-zone-head">
          <h3 class="gl-zone-title">我的任务</h3>
          <p class="gl-zone-note">报名、履约与完成记录——按状态筛选我的全部任务</p>
          <!-- 2026-09-04 反馈 5：审判看板撤出工作台后，当事方争议的常驻入口 -->
          <button type="button" class="gl-zone-action" @click="router.push('/me/disputes')">我的争议 →</button>
        </div>
        <div class="gl-zone-body">
          <article id="gl-engagements" class="gl-tile gl-tile-wide">
            <h3>我的任务</h3>
            <p class="gl-hint">对已接受的履约，如商家未按约定处理，可在任务详情弹窗开启争议——结算将被暂停直至审判终局。异议须在核实结果公布后 48 小时内提出。</p>
            <!-- deferred 客服案升格等待提示（升格后自动跳案件详情页） -->
            <p v-if="deferredDisputeRequestId" class="gl-hint" data-testid="deferred-dispute-status">
              异议已记录，客服案终局后自动开普通争议；系统将自动进入七官审判流程。
            </p>

            <div class="gl-row">
              <label>状态
                <select
                  :value="myTaskFilter"
                  aria-label="我的任务状态筛选"
                  name="my-task-filter"
                  :disabled="myTasksLoading"
                  @change="setMyTaskFilter(($event.target as HTMLSelectElement).value as MyTaskFilterId)"
                >
                  <option v-for="f in MY_TASK_FILTERS" :key="f.id" :value="f.id">{{ f.label }}</option>
                </select>
              </label>
            </div>

            <p v-if="myTaskItems.length === 0 && !myTasksLoading" class="gl-empty">
              {{ myTaskFilter === 'all' ? '还没有报名过任务——去任务大厅看看吧' : '该筛选下暂无任务' }}
            </p>
            <table v-else class="gl-table">
              <thead><tr><th>任务</th><th>门店</th><th>平台</th><th>赏金</th><th>状态</th><th>申请时间</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="row in myTaskItems" :key="row.applicationId">
                  <td>
                    <button type="button" class="gl-link" aria-haspopup="dialog"
                            @click="openTaskDetail(row.taskId, { from: 'my-tasks' })">{{ row.taskTitle || '未命名任务' }}</button>
                  </td>
                  <td>{{ [row.storeName, row.city].filter(Boolean).join(' · ') || '—' }}</td>
                  <!-- 卡 C/D：平台列中文映射，与大厅同源 -->
                  <td>{{ platformDisplayLabel(row.platform) || '—' }}</td>
                  <td class="gl-num">{{ row.bountyCents ? formatYuan(row.bountyCents) : '—' }}</td>
                  <td><span class="badge" :class="myTaskBadge(row).cls">{{ myTaskBadge(row).label }}</span></td>
                  <td>{{ row.appliedAt ? new Date(row.appliedAt).toLocaleString('zh-CN', { hour12: false }) : '—' }}</td>
                  <td>
                    <!-- pending → 取消报名（口径同大厅）；accepted 未结算 → 开始创作；其余 → 详情
                         （终态不可重报——V2 UNIQUE 阻断，操作列只给详情） -->
                    <button
                      v-if="row.applicationStatus === 'pending'"
                      type="button"
                      :disabled="grassland.loading.value"
                      @click="confirmWithdrawMyApplication(row)"
                    >取消报名</button>
                    <button
                      v-else-if="row.applicationStatus === 'accepted' && !row.settledAt"
                      type="button"
                      :disabled="grassland.loading.value || Boolean(taskContextLoadingAppId)"
                      @click="openMyTaskCreation(row)"
                    >
                      {{ taskContextLoadingAppId === row.applicationId ? '加载上下文…' : '开始创作' }}
                    </button>
                    <button v-else type="button" @click="openTaskDetail(row.taskId, { from: 'my-tasks' })">详情</button>
                  </td>
                </tr>
              </tbody>
            </table>

            <nav v-if="myTaskItems.length > 0" class="gl-row gl-feed-pager" aria-label="我的任务分页">
              <button type="button" :disabled="myTasksLoading || myTaskPage === 0" @click="loadMyTasksPrev()">上一页</button>
              <span class="gl-feed-page">第 {{ myTaskPage + 1 }} 页</span>
              <button type="button" :disabled="myTasksLoading || !myTaskHasMore" @click="loadMyTasksNext()">下一页</button>
              <label class="gl-feed-limit">每页
                <select
                  :value="myTaskLimit"
                  aria-label="每页条数"
                  name="my-task-limit"
                  :disabled="myTasksLoading"
                  @change="setMyTaskLimit(Number(($event.target as HTMLSelectElement).value))"
                >
                  <option v-for="option in MY_TASK_LIMIT_OPTIONS" :key="option" :value="option">{{ option }} 条</option>
                </select>
              </label>
            </nav>
          </article>
        </div>
      </section>

      <!-- 任务详情弹窗（任务书 #77 卡 A/C/D）：大厅与我的任务共用一份实例（单实现），
           开合 = selectedTaskId（?task= 深链与通知落点同走此态）。accepted 的履约动作
           （提交凭证/商家评分/争议双通道）以插槽注入——推荐官在任一入口都能交履约。 -->
      <TaskDetailModal
        v-if="side === 'recommender' && selectedTaskId"
        :task="selectedTask"
        :task-id="selectedTaskId"
        :my-application="myApplications[selectedTaskId] ?? null"
        :loading="grassland.loading.value"
        :wallet-balance-cents="walletBalanceCents"
        :show-apply="detailShowApply"
        @close="closeTaskDetail"
        @apply="apply"
        @withdraw="confirmWithdrawMyApplication"
        @start-creation="startCreationFromDetail"
        @report="openComplaint({ targetType: 'task', targetId: $event.id, targetSummary: $event.title })"
      >
        <template #accepted-actions="{ task, application }">
          <template v-if="application">
            <div class="gl-sub-block">
              <h5>提交履约 · <code>{{ application.applicationId.slice(0, 8) }}…</code></h5>
              <EngagementSubmissionPanel
                :task-id="task.id" :application-id="application.applicationId" role="recommender"
                :task-content-form="task.contentForm ?? null"
                :interaction-action-type="task.requirements?.interaction?.actionType ?? null"
              />
              <!-- 商家给本次合作的评分（只读；未评时提示「商家尚未评分」） -->
              <EngagementRatingPanel
                :task-id="task.id" :application-id="application.applicationId" role="recommender"
              />
            </div>
            <!-- 争议双通道选择流（原「我的履约」行内块整体迁入弹窗，#77 卡 D） -->
            <div v-if="disputePromptAppId === application.applicationId" class="gl-sub-block" data-testid="dispute-channel-prompt">
              <h5>选择争议处理通道（提交后不可更改）</h5>
              <label class="gl-row">
                <input v-model="disputeChannel" type="radio" value="court" />
                <span>小法庭——双方 48 小时举证质证 + 七官投票，通常一周内出结果</span>
              </label>
              <label class="gl-row">
                <input v-model="disputeChannel" type="radio" value="cs_direct" />
                <span>客服直裁——平台客服 5 天内直接裁决，不进入审判面板</span>
              </label>
              <div class="gl-row">
                <button type="button" class="btn-confirm" :disabled="grassland.loading.value" @click="confirmDispute">
                  确认开启
                </button>
                <button type="button" @click="cancelDispute">取消</button>
              </div>
            </div>
          </template>
        </template>
        <template #actions-extra="{ application }">
          <button
            v-if="application?.applicationStatus === 'accepted'"
            type="button"
            :disabled="grassland.loading.value"
            @click="openDetailDispute(application.applicationId)"
          >开启争议</button>
        </template>
      </TaskDetailModal>

      <!-- 子页签 收益与结算：钱包余额与收入统计（通知锚点 gl-wallet 在此） -->
      <section v-show="subTab === 'earnings'" class="gl-zone" aria-label="收益与结算">
        <div class="gl-zone-head">
          <h3 class="gl-zone-title">收益与结算</h3>
          <p class="gl-zone-note">钱包余额与收入统计；佣金按任务结算进钱包</p>
        </div>
        <div class="gl-zone-body">
                    <article id="gl-wallet" class="gl-tile gl-tile-wide">
            <MyWalletCard />
          </article>
                    <article class="gl-tile gl-tile-wide">
            <RecommenderIncomeStatsCard />
          </article>
        </div>
      </section>
    </div>

    <!-- 任务表单提交结果弹窗：成功后的单一确认出口（失败不出这里——失败留在抽屉内改） -->
    <div v-if="taskFormResult" class="modal-overlay" @click.self="taskFormResult = ''">
      <div class="modal-card" role="dialog" aria-modal="true" aria-label="任务表单提交结果">
        <div class="modal-body">
          <p class="task-form-result-copy">{{ taskFormResult }}</p>
          <div class="modal-actions">
            <button type="button" class="btn-confirm" @click="taskFormResult = ''">知道了</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 个人设置弹窗（#73）：原「主页与分享/账号与合规」两页签的账号级内容收进此处，两侧共享 -->
    <PersonalSettingsModal
      :open="personalSettingsOpen"
      :side="side"
      :section="personalSettingsSection"
      @update:section="personalSettingsSection = $event"
      @close="personalSettingsOpen = false"
    />

    <!-- 场景化举报弹窗（#74）：两侧身份共享一份，对象由三个业务入口预填并锁定 -->
    <ComplaintModal
      v-if="pendingComplaintTarget"
      :open="complaintOpen"
      :target-type="pendingComplaintTarget.targetType"
      :target-id="pendingComplaintTarget.targetId"
      :target-summary="pendingComplaintTarget.targetSummary"
      @close="complaintOpen = false"
    />

    <!-- 2026-09-04 反馈 5：原「争议与平台治理」区撤除——审判看板迁 /me/disputes/:id 案件详情页，
         平台权限审核队列迁治理台 AdminView「权限审核」页签；当事方入口=两侧工作台的「我的争议」链接。 -->
  </section>
</template>

<style scoped>
.grassland { display: flex; flex-direction: column; gap: var(--space-lg); }

/* ---------- 地平线头区（signature：紫=播种 / 苗绿=耕耘） ---------- */
.gl-header { display: flex; justify-content: space-between; align-items: flex-end; gap: var(--space-md); flex-wrap: wrap; }
.gl-head-copy { min-width: 0; }
.gl-title { margin: 0; font-size: var(--text-xl); font-weight: 800; letter-spacing: -0.02em; line-height: 1.2; }
.gl-sub { margin: 4px 0 0; font-size: var(--text-sm); color: var(--color-text-muted); }

/* 地平线：全宽紫→绿渐变细线，两端身份标签，激活侧点亮 */
.gl-horizon { display: flex; align-items: center; gap: var(--space-sm); }
.gl-horizon-line { flex: 1; height: 2px; border-radius: var(--radius-pill); background: var(--gradient-field); opacity: 0.85; }
.gl-horizon-tag {
  font-size: var(--text-xs); font-weight: 600; letter-spacing: 0.04em;
  color: var(--color-text-muted); white-space: nowrap;
  transition: color var(--duration-normal) var(--ease-out);
}
.gl-horizon-merchant.on { color: var(--color-accent-2); }
.gl-horizon-recommender.on { color: var(--color-grass); }
@media (max-width: 640px) { .gl-horizon-tag { display: none; } }

/* ---------- 提示条（规则本体已收口 src/style.css 的 .gl-field 全局层——任务表单抽屉 Teleport 到 body 后也用得上） ---------- */

/* 垄眉：micro-caps，颜色随视角（商家紫 / 推荐官苗绿），切侧时交叉淡入 */
.gl-subtabs { display: flex; gap: 4px; padding: 4px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--surface-card); overflow-x: auto; scrollbar-width: none; width: fit-content; max-width: 100%; }
.gl-subtabs::-webkit-scrollbar { display: none; }
.gl-subtab { min-height: 36px; padding: 0 16px; border: none; border-radius: var(--radius-xs); background: transparent; color: var(--color-text-muted); font-size: var(--text-sm); font-weight: 600; white-space: nowrap; cursor: pointer; transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out); }
.gl-subtab:hover { color: var(--color-text-secondary); }
.gl-subtab-active { background: var(--gradient-accent); color: var(--color-on-accent); }
.gl-workbench[data-side="recommender"] .gl-subtab-active { background: linear-gradient(135deg, var(--color-grass), color-mix(in srgb, var(--color-grass) 70%, var(--color-info))); }
/* 垄眉右端的主操作（「发布新任务」）：标题/说明占左，按钮靠右 */
.gl-zone-action { margin-left: auto; }

.gl-workbench .gl-zone-title { transition: color var(--duration-normal) var(--ease-out); }
.gl-workbench[data-side="merchant"] .gl-zone-title { color: var(--color-accent-2); }
.gl-workbench[data-side="recommender"] .gl-zone-title { color: var(--color-grass); }
/* ---------- 商家主体屏：左栏分节 + 右栏内容 ---------- */
/* 竖栏与一级横向 pill 正交，避免同屏两行横导航；窄屏塌成横向滚动条 */
.org-split { display: grid; grid-template-columns: 152px minmax(0, 1fr); gap: var(--space-md); align-items: start; }
.org-rail { display: flex; flex-direction: column; gap: 2px; position: sticky; top: var(--space-md); }
.org-rail-item {
  min-height: 34px; padding: 0 var(--space-sm);
  border: none; border-left: 2px solid transparent; border-radius: var(--radius-xs);
  background: transparent; color: var(--color-text-muted);
  font-size: var(--text-sm); font-weight: 600; text-align: left; white-space: nowrap; cursor: pointer;
  transition: color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out);
}
.org-rail-item:hover { color: var(--color-text-secondary); background: var(--surface-furrow); }
.org-rail-active {
  border-left-color: var(--color-accent-2); background: var(--color-surface-highlight);
  color: var(--color-accent-2);
}
.org-panel { min-width: 0; }
.org-panel-section { min-width: 0; }

@media (max-width: 720px) {
  .org-split { grid-template-columns: minmax(0, 1fr); }
  .org-rail {
    position: static; flex-direction: row; gap: 4px;
    overflow-x: auto; scrollbar-width: none;
    padding-bottom: 2px; border-bottom: 1px solid var(--color-border);
  }
  .org-rail::-webkit-scrollbar { display: none; }
  .org-rail-item {
    border-left: none; border-bottom: 2px solid transparent; border-radius: 0;
  }
  .org-rail-active { border-bottom-color: var(--color-accent-2); background: transparent; }
}

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
/* 任务书 #53：被驳回草稿的行下原因提示——muted 小字独占整行（li 是 wrap flex，不占满会挤进操作区） */
.gl-reject-hint { flex-basis: 100%; margin: 0; font-size: var(--text-xs); color: var(--color-text-muted); }
/* 生长刻度：五段轨（草稿/审核/招募/履约/结算），已完成=段色半透、当前=段色实心；
   段色映射状态 token：中性/警示/信息/强调/成功——结构即状态机，不新增色相 */
.gl-growth { display: inline-flex; align-items: center; gap: 3px; }
.gl-growth-seg { width: 18px; height: 3px; border-radius: var(--radius-pill); background: color-mix(in srgb, var(--color-text-muted) 30%, transparent); }
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

/* 展开块头部：选中任务的排序+报名块给出明确「收起」出口——此前一旦展开永远开着 */
.gl-apps-head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-sm); }
.gl-apps-caption { font-size: var(--text-sm); font-weight: 600; color: var(--color-text-secondary); min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 任务表单提交结果弹窗正文 */
.task-form-result-copy { margin: 0; font-size: var(--text-sm); color: var(--color-text); line-height: 1.6; }
</style>
