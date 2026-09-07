<script setup lang="ts">
import { computed, defineAsyncComponent, inject, provide, ref, watch, type Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import EngagementRatingPanel from '../../components/EngagementRatingPanel.vue'
import EngagementSubmissionPanel from '../../components/EngagementSubmissionPanel.vue'
import MerchantTasksPanel from './components/MerchantTasksPanel.vue'
import MerchantOrgPanel from './components/MerchantOrgPanel.vue'
import MerchantFinancePanel from './components/MerchantFinancePanel.vue'
import RecommenderEngagementsPanel from './components/RecommenderEngagementsPanel.vue'
import { WORKBENCH_TASKS_CTX, WORKBENCH_ENGAGEMENTS_CTX } from './workbench-keys'
import {
  MERCHANT_TABS, RECOMMENDER_TABS,
  type SubTab, type SubTabId, type FinanceSection,
} from './workbench-tabs'
import type { OrgSection } from './components/OrgOverviewGrid.vue'

// 任务书 #67 卡 I（2026-09-03 补齐）：非首屏页签（org/finance/hall/engagements/earnings）
// 的重资产卡片异步分包。首屏页签 tasks（商家侧默认）与 hall（推荐官侧默认）内的组件保持静态导入，
// 避免首屏占位闪烁。页签为 v-show 常驻 DOM：异步组件挂载时并行加载，此处仅是打包切分手段，
// 不改变懒加载语义。账号级内容（原 account/home 页签）自 #73 起收进个人设置弹窗，见 PersonalSettingsModal。
// 任务书 #91 W5 增补：org/finance 页签面板化后，其异步组件定义随迁 MerchantOrgPanel/MerchantFinancePanel。
const PersonalSettingsModal = defineAsyncComponent(() => import('./components/PersonalSettingsModal.vue'))
const ComplaintModal = defineAsyncComponent(() => import('../../components/ComplaintModal.vue'))
const MyWalletCard = defineAsyncComponent(() => import('../../components/MyWalletCard.vue'))
const RecommenderTaskHall = defineAsyncComponent(() => import('./components/RecommenderTaskHall.vue'))
// 任务书 #77 卡 A：任务详情弹窗（含门店/品牌/媒体三公开面板——随弹窗迁移，工作台不再直挂）
const TaskDetailModal = defineAsyncComponent(() => import('./components/TaskDetailModal.vue'))
const RecommenderIncomeStatsCard = defineAsyncComponent(() => import('../../components/RecommenderIncomeStatsCard.vue'))

import { useWorkbenchDisputes } from './composables/useWorkbenchDisputes'
import { useWorkbenchEngagements } from './composables/useWorkbenchEngagements'
import {
  APPLICATION_STATUS_BADGES,
  useWorkbenchMyTasks,
} from './composables/useWorkbenchMyTasks'
import { useWorkbenchNavigation } from './composables/useWorkbenchNavigation'
import { useWorkbenchSession } from './composables/useWorkbenchSession'
import { useWorkbenchTaskHall } from './composables/useWorkbenchTaskHall'
import { useWorkbenchUrlState } from './composables/useWorkbenchUrlState'
import { useTaskFormDrawer } from './composables/useTaskFormDrawer'
import { useWorkbenchAccountLifecycle } from './composables/useWorkbenchAccountLifecycle'
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
} from '../../types/grassland'

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

// session 先建（taskHall/engagements/drafts 依赖其 side/orgId/storeId refs）。它对履约域
// refreshTasks 的依赖是**晚绑定 thunk**：engagements 在下方才创建，但该回调只在异步函数的
// await 之后被调用（setup 同步路径不触达），届时 const 必已完成初始化。
// W5 增补：返回对象具名持有（sessionDomain），经 WORKBENCH_TASKS_CTX 注入商家侧面板。
const sessionDomain = useWorkbenchSession(grassland, {
  setNotice,
  // 任务书 #84：把初始化链的父级票据传给履约域（无票据入口由 engagements 自行 capture）。
  refreshTasks: (ticket) => engagements.refreshTasks(ticket),
})
const {
  side, orgs,
  hasMerchantIdentity, hasRecommenderIdentity,
  activeOrgId, selectedStoreId, newOrgName, walletBalanceCents,
  activeOrgStoreOnlyView,
  initForAccount, createOrg, refreshAccount,
  switchSide, reset: resetSession,
} = sessionDomain

// W5：返回对象具名持有（disputesDomain），经 WORKBENCH_ENGAGEMENTS_CTX 注入面板。
const disputesDomain = useWorkbenchDisputes(grassland, setNotice)
const {
  disputePromptAppId, disputeChannel, dispute, cancelDispute, confirmDispute,
  reset: resetDisputes,
} = disputesDomain

const {
  applyNote, feedItems, feedHasMore, feedLoading, feedFilters, feedPage, feedLimit, locating,
  myApplications,
  apply, loadFeed, loadFeedPrev, setFeedLimit, useCurrentLocation, handleFeedFilterUpdate,
  loadMyApplications,
  reset: resetTaskHall,
} = useWorkbenchTaskHall(grassland, side, setNotice)

// 任务书 #77 卡 D：「我的任务」主列表（my-applications 全量 + 四态筛选 + keyset 分页）
// W5：返回对象具名持有（myTasksDomain），经 WORKBENCH_ENGAGEMENTS_CTX 注入面板。
const myTasksDomain = useWorkbenchMyTasks(grassland, side)
const {
  items: myTaskItems, loading: myTasksLoading,
  load: loadMyTasksPage,
  reset: resetMyTasks,
} = myTasksDomain

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
  tasks, selectedTaskId, selectedTask,
  taskContextLoadingAppId,
  levelFilter, rateFilterPct,
  selectedAppIds,
  refreshTasks, cancelTaskAction,
  selectTask, clearSelectedTask,
  batchReject,
} = engagements

// 任务书 #91 W3：抽屉 notice 路由/开合/提交动作/confirm 包装 + TaskDetailModal plumbing 与
// 撤回申请，整段迁入 ./composables/useTaskFormDrawer（useWorkbenchTaskDrafts 亦在其中实例化）。
// 实例化置于 subTab/orgSection 声明之后（deps 同步求值，见下方 detail plumbing 原位）。

// 工作台子页签（任务书 #73 收敛）：两侧各留纯业务垄；账号级内容（主页与分享/账号与合规）
// 收进共享「个人设置」弹窗（两侧头部同一入口，组件 PersonalSettingsModal），页签不再承载。
// v-show 常驻 DOM（锚点滚动与既有断言不破坏）
// 任务书 #91 W4：页签常量（SubTabId/MERCHANT_TABS/RECOMMENDER_TABS 等）纯数据外移 workbench-tabs.ts。
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
// 页签回落 watch（immediate）已随任务书 #91 W3 迁入 useWorkbenchAccountLifecycle。
const orgSection = ref<OrgSection>('overview')

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

// 任务表单抽屉 + 任务详情弹窗编排（任务书 #91 W3）：见文件头部说明——原 166–271 与
// 480–527 两段合并迁入 useTaskFormDrawer，实例化放在本处（subTab/orgSection 已声明）。
// W4：返回对象具名持有（taskDrawer），经 WORKBENCH_TASKS_CTX 注入 MerchantTasksPanel。
const taskDrawer = useTaskFormDrawer({
  grassland, setNotice,
  activeOrgId, selectedStoreId, refreshTasks,
  subTab, orgSection,
  selectedAppIds, batchReject, cancelTaskAction,
  selectedTaskId, clearSelectedTask, cancelDispute, dispute,
  loadMyApplications, loadMyTasksPage,
})
const {
  taskFormResult,
  detailShowApply, openTaskDetail, closeTaskDetail, openDetailDispute,
  confirmWithdrawMyApplication, resetTaskDrafts,
} = taskDrawer

// ---------- 账号级编排：重置 + 初始化（任务书 #91 W3：整段迁入 useWorkbenchAccountLifecycle）----------

useWorkbenchAccountLifecycle({
  notice, side, currentUser,
  route, session,
  subTab, activeTabs,
  refreshTasks,
  initForAccount,
  // 闭包转发：URL 恢复入口（W1 产物）在下方实例化；本 watch 的调用点在 await 之后，
  // 运行时 const 必已就绪（原实现为函数声明提升，语义等价）。
  restoreWorkbenchStateFromUrl: (query) => restoreWorkbenchStateFromUrl(query),
  resetSession,
  resetEngagements: () => engagements.reset(),
  resetTaskHall, resetMyTasks, resetTaskDrafts, resetDisputes,
})

// ---------- URL 状态同步（Web Interface Guidelines：URL 反映视图状态） ----------
// 视角 / 选中任务 / 报名筛选 / 大厅筛选随 query 持久化——刷新、分享链接可恢复现场。
// 恢复必须在 initForAccount 之后：它按已开通身份重排 side，先恢复会被覆盖。
// 任务书 #91 W1：实现整段迁入 ./composables/useWorkbenchUrlState（快照 watch 在工厂内注册，
// 注册时序与迁出前一致）；此处仅保留实例化与解构。
const { restoreWorkbenchStateFromUrl } = useWorkbenchUrlState({
  router,
  side, activeTabs, subTab,
  selectedTaskId, levelFilter, rateFilterPct,
  feedFilters, feedItems, loadFeed,
  myTaskItems, loadMyTasksPage,
  selectTask, openTaskDetail,
  switchSide, hasMerchantIdentity, hasRecommenderIdentity,
  personalSettingsOpen, personalSettingsSection,
})

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

// 任务书 #91 W2：锚点/导航 watch 与 creation handoff（openAcceptedTaskCreation 等）整段迁入
// ./composables/useWorkbenchNavigation；inject 留在本层取引用后传入。注册时序与迁出前一致。
// W5：返回对象具名持有（navigation），经 WORKBENCH_ENGAGEMENTS_CTX 注入面板。
const navigation = useWorkbenchNavigation({
  grassland,
  grasslandAnchor, grasslandNavigationTarget,
  emit, setNotice,
  side, switchSide,
  subTab, financeSection,
  tasks, feedItems, taskContextLoadingAppId,
  selectTask, openTaskDetail,
})
const { openAcceptedTaskCreation, startCreationFromDetail } = navigation

// 任务书 #91 W4（D-03）：商家侧面板经类型化 InjectionKey 注入六域实例与关键 refs，
// 不走 20+ props 钻透；面板（及其子面板）禁止自行 useGrassland()（非单例会分叉）。
// W5 增补（v1.1）：session 升格整实例 + subTab/orgSection/financeSection/summaries（org/finance 面板化）。
provide(WORKBENCH_TASKS_CTX, {
  grassland,
  router,
  session: sessionDomain,
  engagements,
  drawer: taskDrawer,
  openAcceptedTaskCreation,
  openComplaint,
  subTab, orgSection, financeSection,
  summaries: { team: teamSummary, brand: brandSummary, kyb: kybSummary, permission: permissionSummary },
})

// 任务书 #91 W5（D-03）：推荐官「我的任务」面板注入同一份六域实例与关键 refs。
provide(WORKBENCH_ENGAGEMENTS_CTX, {
  grassland,
  router,
  myTasks: myTasksDomain,
  disputes: disputesDomain,
  drawer: taskDrawer,
  navigation,
  engagements,
  myTaskBadge,
})

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

      <!-- 子页签① 任务与报名：田垄②主操作区——每天干活的地方（任务书 #91 W4：整段面板化，
           v-show 挂组件标签，DOM 结构与锚点逐字符保留——见 components/MerchantTasksPanel.vue） -->
      <MerchantTasksPanel v-show="subTab === 'tasks'" />


      <!-- 子页签② 组织与门店：身份条常驻 + 左栏五分节（概览 / 成员门店 / 品牌 / 认证 / 权限）。
           任务书 #91 W5 增补（v1.1 拍板）：面板化（components/MerchantOrgPanel.vue），v-show 挂组件标签 -->
      <MerchantOrgPanel v-show="subTab === 'org'" />

      <!-- 子页签③ 资金与经营：二级分栏（任务书 #78 卡 J，org 左栏同款）——资金账户 / 月度账单 / 到店套餐与核销 / 经营分析。
           任务书 #91 W5 增补（v1.1 拍板）：面板化（components/MerchantFinancePanel.vue），v-show 挂组件标签 -->
      <MerchantFinancePanel v-show="subTab === 'finance'" />
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
           主列表 = my-applications 全量 + 四态筛选 + 分页；履约操作收进详情弹窗，与选中任务解绑。
           任务书 #91 W5：整段面板化（components/RecommenderEngagementsPanel.vue），v-show 挂组件标签） -->
      <RecommenderEngagementsPanel v-show="subTab === 'engagements'" />


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
/* W5 增补：org/finance 分栏原语与 .gl-balance 已随面板迁入 MerchantOrgPanel/MerchantFinancePanel。 */

/* 任务书 #91 W4：任务列表/生长刻度/筛选/批量/展开块样式已随模板块迁入 MerchantTasksPanel.vue；
   .gl-sub-block 保留——TaskDetailModal 插槽（提交履约/争议通道）仍在 SFC 作用域渲染。 */

.gl-sub-block { margin-top: var(--space-sm); }
.gl-sub-block h5 { margin: 0; font-size: var(--text-xs); font-weight: 600; color: var(--color-text-muted); letter-spacing: 0.04em; }


/* 任务表单提交结果弹窗正文 */
.task-form-result-copy { margin: 0; font-size: var(--text-sm); color: var(--color-text); line-height: 1.6; }
</style>
