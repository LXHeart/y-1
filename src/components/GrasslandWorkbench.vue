<script setup lang="ts">
import { computed, inject, nextTick, ref, watch, type Ref } from 'vue'
import AdjudicationPanel from './AdjudicationPanel.vue'
import EngagementRatingPanel from './EngagementRatingPanel.vue'
import EngagementSubmissionPanel from './EngagementSubmissionPanel.vue'
import MerchantPermissionCard from './MerchantPermissionCard.vue'
import MyInvitationsCard from './MyInvitationsCard.vue'
import MyRecommenderProfileCard from './MyRecommenderProfileCard.vue'
import MySessionsCard from './MySessionsCard.vue'
import MyWalletCard from './MyWalletCard.vue'
import OrgTeamCard from './OrgTeamCard.vue'
import PermissionReviewPanel from './PermissionReviewPanel.vue'
import RecommenderReputationBadge from './RecommenderReputationBadge.vue'
import { useAuth } from '../composables/useAuth'
import { useGrassland } from '../composables/useGrassland'
import type {
  FinanceAccount,
  Organization,
  RecommenderProfile,
  RecommenderReputation,
  Task,
  TaskApplication,
} from '../types/grassland'

/**
 * 草场工作台——Java 微服务域的第一个前端驱动（P0-1）。
 *
 * 双视角演示完整撮合闭环：
 *   商家：开通组织 → 充值 → 发布任务 → 查看报名 → 接受（资金 Saga，202+轮询）→ 确认履约 → 结算轮询
 *   推荐官：浏览任务大厅 → 报名 → 查看自己的报名 → 对已接受的履约开争议
 *
 * 交互要点：accept/confirm 是**异步 202**，UI 必须轮询到终态才能给结论（这是与旧 Express 同步端点的关键差异）。
 */

const grassland = useGrassland()
const { currentUser } = useAuth()

/** 平台 admin 才看得到审核队列。真正的门禁在服务端（identity 查 app_users.role）。 */
const isPlatformAdmin = computed(() => currentUser.value?.role === 'admin')

type Side = 'merchant' | 'recommender'

const side = ref<Side>('merchant')
const orgs = ref<Organization[]>([])
const activeOrgId = ref('')
const account = ref<FinanceAccount | null>(null)
const tasks = ref<Task[]>([])
const applications = ref<TaskApplication[]>([])
const selectedTaskId = ref('')
const notice = ref('')
/** 当前查看的争议 id——开争议后挂载审判看板。 */
const activeDisputeId = ref('')

/** 每个 application 的异步结局（accept 预留 / confirm 结算），key = applicationId。 */
const outcomes = ref<Record<string, string>>({})

const newOrgName = ref('')
const creditAmountYuan = ref(1000)
const taskForm = ref({ title: '', description: '', platform: '', maxSlots: 1, bountyYuan: 0 })
const applyNote = ref('')

// ---------- 商家筛选报名者（PRD 五等级 + 完成率）----------
//
// 声誉/画像是**按报名者**并发拉的——后端刻意不提供「按条件搜人」入口（那会把平台变成
// 人肉数据库），故筛选在拉到全量报名后于前端做。key = recommenderAccountId。
const applicantReputation = ref<Record<string, RecommenderReputation>>({})
const applicantProfile = ref<Record<string, RecommenderProfile>>({})
/** 等级筛选下限（'' = 不限）。Lv 是邀请制、永不自动授予，故筛选项到 Lv4。 */
const levelFilter = ref('')
/** 完成率筛选下限（0-100 百分比；0 = 不限）。 */
const rateFilterPct = ref(0)
/** 已确认履约的 applicationId 集合——评分前置（确认后才显示评分表单）。内存态。 */
const confirmedAppIds = ref<Set<string>>(new Set())

/** Lv 字符串 → 序号，用于「等级 ≥」比较。 */
const LEVEL_ORDER: Record<string, number> = { Lv1: 1, Lv2: 2, Lv3: 3, Lv4: 4, Lv5: 5 }

const activeOrg = computed(() => orgs.value.find((o) => o.id === activeOrgId.value) || null)
const canPublishBounty = computed(() => activeOrg.value?.permissionTier === 'finance_transaction')
const balanceYuan = computed(() =>
  account.value ? (account.value.balanceCents / 100).toFixed(2) : '—')

/**
 * 报名列表按等级 / 完成率筛选。
 *
 * 无声誉数据的报名者（还在拉取）在有筛选时**不展示**——筛选语义是「只看达标的」，
 * 数据没回来不能默认达标。无筛选时全量展示。
 */
const filteredApplications = computed<TaskApplication[]>(() => {
  const levelMin = levelFilter.value ? LEVEL_ORDER[levelFilter.value] : 0
  const rateMin = rateFilterPct.value / 100
  return applications.value.filter((a) => {
    const rep = applicantReputation.value[a.recommenderAccountId]
    if (levelMin > 0 && (!rep || (LEVEL_ORDER[rep.level] || 0) < levelMin)) return false
    if (rateMin > 0 && (!rep || rep.completionRate < rateMin)) return false
    return true
  })
})

function yuanToCents(yuan: number): number {
  return Math.round(yuan * 100)
}

function setNotice(message: string): void {
  notice.value = message
}

// ---------- 初始化 ----------

async function loadOrganizations(): Promise<void> {
  const list = await grassland.listOrganizations()
  if (!list) return
  orgs.value = list
  if (!activeOrgId.value && list.length > 0) {
    activeOrgId.value = list[0].id
  }
  // 无条件刷新：此前只在「首次选中组织」时拉数据，导致重新进入草场标签页时
  // 列表仍是旧的（App.vue 用 <component :is> 复用组件，onMounted 不必然重跑，
  // 且期间可能有新任务）。浏览器实测发现：后端 3 个任务、UI 只显示 2 个。
  if (activeOrgId.value) {
    await refreshAccount()
    await refreshTasks()
  }
}

/**
 * 初始化先读取已开通身份，再激活与之对应的当前 session 身份。
 *
 * 推荐官-only 账号若沿用默认 merchant，会产生一个可预期却会出现在浏览器 console 的 409；
 * 无身份账号保留 merchant onboarding 界面，但不因打开工作台暗中开通/激活 merchant。
 */
async function initForAccount(): Promise<void> {
  // 只激活已开通的身份：推荐官-only 账号不应因默认 merchant 视图收到可预期的 409。
  // merchant 优先保留双身份账号的既有工作台入口；无身份则留在 merchant onboarding，但不暗中开户/激活。
  const identities = await grassland.listIdentities()
  if (identities === null) return

  const initialIdentity = identities.some((identity) => identity.identityType === 'merchant')
    ? 'merchant'
    : identities.some((identity) => identity.identityType === 'recommender')
      ? 'recommender'
      : null
  if (initialIdentity) {
    side.value = initialIdentity
    await grassland.activateIdentity(initialIdentity)
    grassland.clearError()  // 已知身份的激活失败由后续具体操作给出更明确的错误
  }
  await loadOrganizations()
}

/** 清空全部账号相关状态——否则上一个账号的组织/余额/任务会留在界面上。 */
function resetAccountState(): void {
  orgs.value = []
  activeOrgId.value = ''
  account.value = null
  tasks.value = []
  applications.value = []
  selectedTaskId.value = ''
  activeDisputeId.value = ''
  outcomes.value = {}
  notice.value = ''
  applicantReputation.value = {}
  applicantProfile.value = {}
  levelFilter.value = ''
  rateFilterPct.value = 0
  confirmedAppIds.value = new Set()
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

// ---------- 商家：组织 / 账户 ----------

async function createOrg(): Promise<void> {
  if (!newOrgName.value.trim()) return
  const created = await grassland.createOrganization(newOrgName.value.trim())
  if (!created) return
  newOrgName.value = ''
  setNotice(`组织「${created.name}」已创建（等级 ${created.permissionTier}）`)
  await loadOrganizations()
}

async function refreshAccount(): Promise<void> {
  if (!activeOrgId.value) return
  // 账户可能尚未开通（404）→ 静默，由「开通账户」按钮处理
  const existing = await grassland.getAccount(activeOrgId.value)
  account.value = existing
  if (existing) grassland.clearError()
}

async function provision(): Promise<void> {
  const created = await grassland.provisionAccount()
  if (!created) return
  account.value = created
  setNotice('资金账户已开通')
}

async function credit(): Promise<void> {
  if (!activeOrgId.value) return
  const updated = await grassland.creditAccount(activeOrgId.value, yuanToCents(creditAmountYuan.value))
  if (!updated) return
  account.value = updated
  setNotice(`已充值 ¥${creditAmountYuan.value}`)
}

// ---------- 商家：任务 / 报名 ----------

async function refreshTasks(): Promise<void> {
  if (!activeOrgId.value) return
  const list = await grassland.listTasks(activeOrgId.value)
  if (list) tasks.value = list
}

async function publishTask(): Promise<void> {
  if (!activeOrgId.value || !taskForm.value.title.trim()) return
  const bountyCents = yuanToCents(taskForm.value.bountyYuan)
  const created = await grassland.createTask({
    organizationId: activeOrgId.value,
    title: taskForm.value.title.trim(),
    description: taskForm.value.description.trim() || undefined,
    platform: taskForm.value.platform.trim() || undefined,
    maxSlots: taskForm.value.maxSlots > 0 ? taskForm.value.maxSlots : undefined,
    bountyCents: bountyCents > 0 ? bountyCents : undefined,
  })
  if (!created) return
  taskForm.value = { title: '', description: '', platform: '', maxSlots: 1, bountyYuan: 0 }
  setNotice(`任务「${created.title}」已发布`)
  await refreshTasks()
}

async function selectTask(taskId: string): Promise<void> {
  selectedTaskId.value = taskId
  applications.value = []
  // 切任务即清空上一份报名者的声誉/画像与已确认集合——否则筛选会串数据。
  applicantReputation.value = {}
  applicantProfile.value = {}
  confirmedAppIds.value = new Set()
  const list = await grassland.listApplications(taskId)
  if (list) applications.value = list
  await loadApplicantProfiles()
}

/**
 * 并发拉取本任务所有报名者的声誉 + 画像。
 *
 * 按唯一 accountId 去重后 Promise.all——同一推荐官报多个任务时只拉一次。
 * 后端无「按条件搜人」，筛选只能在前端对全量报名做。
 */
async function loadApplicantProfiles(): Promise<void> {
  const accountIds = Array.from(new Set(applications.value.map((a) => a.recommenderAccountId)))
  if (accountIds.length === 0) return
  const results = await Promise.all(accountIds.map(async (id) => {
    const [rep, prof] = await Promise.all([
      grassland.getReputation(id),
      grassland.getRecommenderProfile(id),
    ])
    return { id, rep, prof }
  }))
  const repMap: Record<string, RecommenderReputation> = {}
  const profMap: Record<string, RecommenderProfile> = {}
  for (const r of results) {
    if (r.rep) repMap[r.id] = r.rep
    if (r.prof) profMap[r.id] = r.prof
  }
  applicantReputation.value = repMap
  applicantProfile.value = profMap
}

/** 接受报名：202 后立即轮询预留结局（资金型任务可能因余额不足被补偿）。 */
async function accept(app: TaskApplication): Promise<void> {
  outcomes.value = { ...outcomes.value, [app.id]: '处理中…' }
  const started = await grassland.acceptApplication(app.taskId, app.id)
  if (!started) {
    outcomes.value = { ...outcomes.value, [app.id]: '' }
    return
  }
  const outcome = await grassland.pollReservation(app.taskId, app.id)
  if (!outcome) {
    outcomes.value = { ...outcomes.value, [app.id]: '' }
    return
  }
  const label = outcome.status === 'accepted'
    ? '已接受（资金已预留）'
    : outcome.status === 'compensated'
      ? `未接受：${outcome.reason === 'insufficient_funds' ? '账户余额不足' : outcome.reason || '预留失败'}`
      : '处理中…'
  outcomes.value = { ...outcomes.value, [app.id]: label }
  await selectTask(app.taskId)
  await refreshAccount()
}

async function reject(app: TaskApplication): Promise<void> {
  const rejected = await grassland.rejectApplication(app.taskId, app.id)
  if (!rejected) return
  setNotice('已拒绝该报名')
  await selectTask(app.taskId)
}

/** 确认履约：202 后轮询结算结局（有未终局争议时为 held）。 */
async function confirm(app: TaskApplication): Promise<void> {
  outcomes.value = { ...outcomes.value, [app.id]: '结算中…' }
  const started = await grassland.confirmEngagement(app.taskId, app.id)
  if (!started) {
    outcomes.value = { ...outcomes.value, [app.id]: '' }
    return
  }
  const outcome = await grassland.pollSettlement(app.taskId, app.id)
  if (!outcome) {
    outcomes.value = { ...outcomes.value, [app.id]: '' }
    return
  }
  // settled / held 都意味着履约已确认（held 只是结算被争议暂扣）——此时商家可评分。
  if (outcome.status === 'settled' || outcome.status === 'held') {
    confirmedAppIds.value = new Set([...confirmedAppIds.value, app.id])
  }
  const label = outcome.status === 'settled'
    ? '已结算（资金已确认扣款）'
    : outcome.status === 'held'
      ? `结算暂停：${outcome.reason === 'open_dispute' ? '存在未终局争议' : outcome.reason || '被暂停'}`
      : outcome.status === 'not_confirmed'
        ? '尚未确认履约'
        : '结算中…'
  outcomes.value = { ...outcomes.value, [app.id]: label }
  await refreshAccount()
}

// ---------- 推荐官 ----------

async function apply(taskId: string): Promise<void> {
  const created = await grassland.applyToTask(taskId, applyNote.value.trim() || undefined)
  if (!created) return
  applyNote.value = ''
  setNotice('报名已提交，等待商家处理')
}

async function dispute(app: TaskApplication): Promise<void> {
  const opened = await grassland.openDispute(app.id, '履约存在争议')
  if (!opened) return
  activeDisputeId.value = opened.id  // 挂载审判看板
  setNotice(`争议已开启（状态 ${opened.status}），结算将被暂停`)
}

/**
 * 切换视角。活动身份按 session 隔离，必须同步切后端，否则 requireMerchant/requireRecommender 会 403。
 *
 * 关键：**激活失败必须回滚 UI**。此前无论成败都切视角，账号若未开通对应身份，
 * 会出现「UI 显示推荐官、后端仍是商家、所有操作 403」且用户看不出原因（浏览器实测发现）。
 * 未开通时后端返回 409，这里自动尝试开通一次（推荐官无需 org，可直接开通）。
 */
async function switchSide(next: Side): Promise<void> {
  const previous = side.value
  side.value = next

  let activated = await grassland.activateIdentity(next)
  if (activated === null) {
    // 多半是「未开通该身份」——推荐官不需要 org，可就地开通后重试
    const opened = await grassland.openIdentity(
      next, next === 'merchant' ? activeOrgId.value || undefined : undefined)
    if (opened !== null) {
      activated = await grassland.activateIdentity(next)
    }
  }

  if (activated === null) {
    side.value = previous  // 回滚，避免 UI 与后端身份不一致
    setNotice('')
    return
  }

  grassland.clearError()
  if (next === 'merchant') {
    await refreshTasks()
  }
}

/**
 * 通知落点（草场 Slice 12 Stage 4）。`App.vue` provide 一个锚点 id，本组件滚到对应卡片后置空
 * （置空才能让同一锚点被再次点击时重新触发 watch）。
 *
 * **不切换商家/推荐官视角**：`switchSide()` 会重置组织/任务/争议选择。故 `/me/engagements`、
 * `/me/wallet` 这类两侧都有的锚点，落在当前视角自己那张卡上（两侧是 v-if/v-else，
 * 同一时刻 DOM 里只有一个同名 id）。
 */
const grasslandAnchor = inject<Ref<string>>('grasslandAnchor', ref(''))

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

function statusLabel(status: string): string {
  const map: Record<string, string> = {
    pending: '待处理',
    reserving: '预留中',
    accepted: '已接受',
    rejected: '已拒绝',
    withdrawn: '已撤销',
  }
  return map[status] || status
}
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
      <MyInvitationsCard @joined="loadOrganizations" />
    </article>

    <article class="gl-card gl-card-wide">
      <MySessionsCard />
    </article>

    <!-- ============ 商家视角 ============ -->
    <div v-if="side === 'merchant'" class="gl-grid">
      <article id="gl-organizations" class="gl-card">
        <h3>1. 我的组织</h3>
        <div class="gl-row">
          <select v-model="activeOrgId" @change="refreshAccount(); refreshTasks()">
            <option value="" disabled>选择组织</option>
            <option v-for="o in orgs" :key="o.id" :value="o.id">{{ o.name }}（{{ o.permissionTier }}）</option>
          </select>
        </div>
        <div class="gl-row">
          <input v-model="newOrgName" placeholder="新组织名称" @keyup.enter="createOrg" />
          <button type="button" :disabled="grassland.loading.value" @click="createOrg">创建</button>
        </div>
        <p v-if="activeOrg" class="gl-hint">
          当前等级 <code>{{ activeOrg.permissionTier }}</code>
          <span v-if="!canPublishBounty">（非 finance_transaction 等级不可发布赏金任务）</span>
        </p>
      </article>

      <!-- 权限与额度：D-05 的商家侧入口（升级申请 / 申诉 / 额度已用-上限） -->
      <article v-if="activeOrg" class="gl-card gl-card-wide">
        <MerchantPermissionCard
          :org-id="activeOrg.id"
          :tier="activeOrg.permissionTier"
          :industry="activeOrg.industry"
          @changed="loadOrganizations"
        />
      </article>

      <!-- 成员与门店：Slice 2F/2G/2J 的三级权限自助管理 -->
      <article v-if="activeOrg" class="gl-card gl-card-wide">
        <OrgTeamCard :org-id="activeOrg.id" />
      </article>

      <!-- id 与推荐官侧钱包卡同名：两侧是 v-if/v-else，同一时刻只有一个在 DOM 里 -->
      <article id="gl-wallet" class="gl-card">
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

      <article class="gl-card gl-card-wide">
        <h3>3. 发布任务</h3>
        <div class="gl-row">
          <input v-model="taskForm.title" placeholder="任务标题" />
          <input v-model="taskForm.platform" placeholder="平台（可选）" />
        </div>
        <div class="gl-row">
          <input v-model="taskForm.description" placeholder="任务描述（可选）" />
        </div>
        <div class="gl-row">
          <label>名额 <input v-model.number="taskForm.maxSlots" type="number" min="1" /></label>
          <label>赏金 ¥<input v-model.number="taskForm.bountyYuan" type="number" min="0" :disabled="!canPublishBounty" /></label>
          <button type="button" :disabled="!activeOrgId || grassland.loading.value" @click="publishTask">发布</button>
        </div>
        <p class="gl-hint">赏金 &gt; 0 的任务为资金型：接受报名时会走资金预留 Saga（异步）。</p>
      </article>

      <article id="gl-engagements" class="gl-card gl-card-wide">
        <h3>4. 任务与报名</h3>
        <p v-if="tasks.length === 0" class="gl-empty">暂无任务</p>
        <ul class="gl-list">
          <li v-for="t in tasks" :key="t.id">
            <button type="button" class="gl-link" :class="{ active: selectedTaskId === t.id }" @click="selectTask(t.id)">
              {{ t.title }}
            </button>
            <span class="gl-tag">{{ t.status }}</span>
            <span v-if="t.bountyCents" class="gl-tag gl-tag-money">¥{{ (t.bountyCents / 100).toFixed(2) }}</span>
          </li>
        </ul>

        <div v-if="selectedTaskId" class="gl-apps">
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
            <table v-else class="gl-table">
              <thead><tr><th>推荐官</th><th>等级 / 声誉</th><th>状态</th><th>操作</th><th>结果</th></tr></thead>
              <tbody>
                <tr v-for="a in filteredApplications" :key="a.id">
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
                    <button v-if="a.status === 'accepted'" type="button" :disabled="grassland.loading.value" @click="confirm(a)">确认履约</button>
                  </td>
                  <td class="gl-outcome">{{ outcomes[a.id] || '—' }}</td>
                </tr>
              </tbody>
            </table>

            <!-- 交付物 + 评分：确认履约前必须有一份待核验的（后端 409 守卫）；评分须先确认履约。 -->
            <template v-for="a in applications" :key="`sub-${a.id}`">
              <div v-if="a.status === 'accepted'" class="gl-sub-block">
                <h5>履约交付物 · <code>{{ a.recommenderAccountId.slice(0, 8) }}…</code></h5>
                <EngagementSubmissionPanel
                  :task-id="selectedTaskId" :application-id="a.id" role="merchant"
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

      <article class="gl-card gl-card-wide">
        <h3>任务大厅</h3>
        <div class="gl-row">
          <input v-model="activeOrgId" placeholder="按组织 ID 浏览任务" />
          <button type="button" :disabled="grassland.loading.value" @click="refreshTasks">查询</button>
        </div>
        <div class="gl-row">
          <input v-model="applyNote" placeholder="报名留言（可选）" />
        </div>
        <p v-if="tasks.length === 0" class="gl-empty">暂无可报名任务</p>
        <table v-else class="gl-table">
          <thead><tr><th>任务</th><th>平台</th><th>赏金</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="t in tasks" :key="t.id">
              <!-- 点标题选中任务 → 下方「我的履约与争议」才能加载自己的报名（进而提交履约） -->
              <td>
                <button type="button" class="gl-link" :class="{ active: selectedTaskId === t.id }"
                        @click="selectTask(t.id)">{{ t.title }}</button>
              </td>
              <td>{{ t.platform || '—' }}</td>
              <td>{{ t.bountyCents ? `¥${(t.bountyCents / 100).toFixed(2)}` : '无' }}</td>
              <td>
                <button type="button" :disabled="grassland.loading.value" @click="apply(t.id)">报名</button>
              </td>
            </tr>
          </tbody>
        </table>
      </article>

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
                <button v-if="a.status === 'accepted'" type="button" :disabled="grassland.loading.value" @click="dispute(a)">
                  开启争议
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
.gl-actions { display: flex; gap: 6px; }
.gl-outcome { font-size: 12px; opacity: 0.8; }
.gl-filter { display: flex; gap: 14px; align-items: center; flex-wrap: wrap; font-size: 13px; }
.gl-filter label { display: flex; align-items: center; gap: 6px; opacity: 0.85; }
.gl-filter select { padding: 4px 8px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: 6px; font-size: 13px; }
</style>
