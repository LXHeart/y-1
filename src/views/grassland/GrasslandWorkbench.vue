<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import AdjudicationPanel from '../../components/AdjudicationPanel.vue'
import EngagementRatingPanel from '../../components/EngagementRatingPanel.vue'
import EngagementSubmissionPanel from '../../components/EngagementSubmissionPanel.vue'
import MerchantKybCard from '../../components/MerchantKybCard.vue'
import MerchantCommerceCard from '../../components/MerchantCommerceCard.vue'
import MerchantPermissionCard from '../../components/MerchantPermissionCard.vue'
import MyInvitationsCard from '../../components/MyInvitationsCard.vue'
import MyRecommenderProfileCard from '../../components/MyRecommenderProfileCard.vue'
import MySessionsCard from '../../components/MySessionsCard.vue'
import MyWalletCard from '../../components/MyWalletCard.vue'
import BusinessAnalyticsPanel from '../../components/BusinessAnalyticsPanel.vue'
import OrgTeamCard from '../../components/OrgTeamCard.vue'
import PermissionReviewPanel from '../../components/PermissionReviewPanel.vue'
import RecommenderReputationBadge from '../../components/RecommenderReputationBadge.vue'
import MerchantTaskForm from './components/MerchantTaskForm.vue'
import RecommenderTaskHall from './components/RecommenderTaskHall.vue'
import RecommenderRecommendations from './components/RecommenderRecommendations.vue'
import StorePublicProfilePanel from './components/StorePublicProfilePanel.vue'
import { normalizeTaskCreationSelection } from '../../config/ai-platform-capabilities'
import { useAuth } from '../../composables/useAuth'
import { useGrassland } from '../../composables/useGrassland'
import type { CreationEntry } from '../../types/ai-creation'
import type { NotificationLinkTarget } from '../../types/notification'
import type {
  FinanceAccount,
  Organization,
  RecommenderProfile,
  RecommenderMatch,
  RecommenderRecommendationPage,
  RecommenderReputation,
  Store,
  StoreAccessScope,
  StorePublicProfile,
  Task,
  TaskApplication,
} from '../../types/grassland'

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
const emit = defineEmits<{
  'open-creation': [entry: CreationEntry]
}>()

/** 平台 admin 才看得到审核队列。真正的门禁在服务端（identity 查 app_users.role）。 */
const isPlatformAdmin = computed(() => currentUser.value?.role === 'admin')

type Side = 'merchant' | 'recommender'

const side = ref<Side>('merchant')
const orgs = ref<Organization[]>([])
const stores = ref<Store[]>([])
const storeScopes = ref<StoreAccessScope[]>([])
const organizationAccessIds = ref<Set<string>>(new Set())
const hasMerchantIdentity = ref(false)
const activeOrgId = ref('')
/** Empty means legacy organization-level task scope; otherwise the selected store. */
const selectedStoreId = ref('')
const account = ref<FinanceAccount | null>(null)
const tasks = ref<Task[]>([])
const applications = ref<TaskApplication[]>([])
const selectedTaskId = ref('')
const notice = ref('')
/** 当前查看的争议 id——即时开案或 deferred promotion 后挂载审判看板。 */
const activeDisputeId = ref('')
/** 待客服案终局的推荐官异议 request；requestId 与 disputeId 严格分离。 */
const deferredDisputeRequestId = ref('')
let deferredPollTimer: ReturnType<typeof setTimeout> | null = null
const DEFERRED_POLL_MS = 3000

/** 每个 application 的异步结局（accept 预留 / confirm 结算），key = applicationId。 */
const outcomes = ref<Record<string, string>>({})
const taskContextLoadingAppId = ref('')

const newOrgName = ref('')
const creditAmountYuan = ref(1000)
/** applicationDeadline 存 datetime-local 字符串（"YYYY-MM-DDTHH:mm"）；提交时转 ISO。 */
const taskForm = ref({
  title: '', description: '', platform: '', contentForm: '', maxSlots: 1, bountyYuan: 0,
  applicationDeadline: '', minRecommenderLevel: 1,
  productServiceInfo: '', mustInclude: '', forbiddenContent: '',
  publishStartAt: '', publishEndAt: '', metricRequirements: '', evidenceRequirements: '',
})
/** 编辑中的草稿 id/version；非空时「存草稿」走 PUT 更新，否则 POST 新建。 */
const editingDraft = ref<{ id: string; version: number } | null>(null)
/**
 * 待修订的已发布任务 id/version（GL-P1-TASK-001：编辑出新版本）。与 editingDraft 互斥——
 * 非空时「保存」走 POST /revise（只带非资金字段）。刻意独立于 editingDraft 而非复用：
 * 修订走不同端点、冻结资金字段、保存语义不同（已发布→出新版本，不是存草稿），混在一个标志里要靠 status 二次判分支，易错。
 */
const revisingTask = ref<{ id: string; version: number } | null>(null)
const applyNote = ref('')
/** 商家拒绝理由按 application 独立保存，避免多条报名共用输入串值。 */
const contestReasons = ref<Record<string, string>>({})

// ---------- 推荐官：全局任务大厅 feed（GL-P1-TASK-001 Stage 2）----------
const feedItems = ref<Task[]>([])
const feedCursor = ref('')
const feedHasMore = ref(false)
const feedLoading = ref(false)
const feedFilters = ref({
  platform: '', contentForm: '', minBountyYuan: 0, maxDistanceKm: 0,
  latitude: null as number | null, longitude: null as number | null,
})
const locating = ref(false)

// ---------- 任务书 #24：任务详情携带门店公开块 ----------
const storePublicProfile = ref<StorePublicProfile | null>(null)
const storePublicProfileLoading = ref(false)
const storePublicProfileError = ref('')

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
const recommendations = ref<RecommenderRecommendationPage | null>(null)
const recommendationsLoading = ref(false)
const invitingAccountId = ref('')
/** 已确认履约的 applicationId 集合——评分前置（确认后才显示评分表单）。内存态。 */
const confirmedAppIds = ref<Set<string>>(new Set())

/** Lv 字符串 → 序号，用于「等级 ≥」比较。 */
const LEVEL_ORDER: Record<string, number> = { Lv1: 1, Lv2: 2, Lv3: 3, Lv4: 4, Lv5: 5 }

const activeOrg = computed(() => orgs.value.find((o) => o.id === activeOrgId.value) || null)
const managerStoreScopes = computed(() => storeScopes.value.filter((scope) => scope.role === 'manager'))
const activeOrgHasOrganizationAccess = computed(() => organizationAccessIds.value.has(activeOrgId.value))
const canPublishBounty = computed(() => activeOrg.value?.permissionTier === 'finance_transaction')
const balanceYuan = computed(() =>
  account.value ? (account.value.balanceCents / 100).toFixed(2) : '—')
const selectedTask = computed(() => {
  return [...tasks.value, ...feedItems.value].find((task) => task.id === selectedTaskId.value) ?? null
})

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

async function openAcceptedTaskCreation(application: TaskApplication): Promise<void> {
  const task = selectedTask.value
  if (!task || application.status !== 'accepted' || application.taskId !== task.id
      || taskContextLoadingAppId.value) return
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

// ---------- 初始化 ----------

async function loadOrganizations(
  knownOrganizations?: Organization[], knownStoreScopes?: StoreAccessScope[],
): Promise<void> {
  const [organizationResult, scopeResult] = await Promise.all([
    knownOrganizations ?? grassland.listOrganizations(),
    knownStoreScopes ?? grassland.listMyStoreScopes(),
  ])
  if (organizationResult === null && scopeResult === null) return

  const organizationList = Array.isArray(organizationResult) ? organizationResult : []
  storeScopes.value = Array.isArray(scopeResult) ? scopeResult : []
  organizationAccessIds.value = new Set(organizationList.map((organization) => organization.id))

  const merged = [...organizationList]
  for (const scope of storeScopes.value.filter((item) => item.role === 'manager')) {
    if (merged.some((organization) => organization.id === scope.organizationId)) continue
    merged.push({
      id: scope.organizationId,
      name: scope.organizationName,
      ownerAccountId: '',
      permissionTier: scope.permissionTier,
      industry: null,
      createdAt: null,
    })
  }
  orgs.value = merged
  if (!merged.some((organization) => organization.id === activeOrgId.value)) {
    activeOrgId.value = merged[0]?.id ?? ''
  }
  // 无条件刷新：此前只在「首次选中组织」时拉数据，导致重新进入草场标签页时
  // 列表仍是旧的（App.vue 用 <component :is> 复用组件，onMounted 不必然重跑，
  // 且期间可能有新任务）。浏览器实测发现：后端 3 个任务、UI 只显示 2 个。
  if (activeOrgId.value) {
    await loadActiveOrganizationStores()
    await refreshAccount()
    await refreshTasks()
  }
}

async function loadActiveOrganizationStores(): Promise<void> {
  if (!activeOrgId.value) {
    stores.value = []
    selectedStoreId.value = ''
    return
  }
  if (activeOrgHasOrganizationAccess.value) {
    stores.value = (await grassland.listStores(activeOrgId.value)) ?? []
  } else {
    stores.value = managerStoreScopes.value
      .filter((scope) => scope.organizationId === activeOrgId.value)
      .map((scope) => ({
        id: scope.storeId,
        organizationId: scope.organizationId,
        name: scope.storeName,
        status: scope.storeStatus,
        createdAt: null,
      }))
  }
  if (!activeOrgHasOrganizationAccess.value
      && !stores.value.some((store) => store.id === selectedStoreId.value)) {
    selectedStoreId.value = stores.value[0]?.id ?? ''
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
  const [identities, organizations, scopes] = await Promise.all([
    grassland.listIdentities(),
    grassland.listOrganizations(),
    grassland.listMyStoreScopes(),
  ])
  if (identities === null) return

  hasMerchantIdentity.value = identities.some((identity) => identity.identityType === 'merchant')
  storeScopes.value = Array.isArray(scopes) ? scopes : []
  const hasManagerScope = storeScopes.value.some((scope) => scope.role === 'manager')
  const initialIdentity = hasMerchantIdentity.value
    ? 'merchant'
    : hasManagerScope
      ? null
      : identities.some((identity) => identity.identityType === 'recommender')
        ? 'recommender'
        : null
  if (hasManagerScope && !hasMerchantIdentity.value) side.value = 'merchant'
  if (initialIdentity) {
    side.value = initialIdentity
    await grassland.activateIdentity(initialIdentity)
    grassland.clearError()  // 已知身份的激活失败由后续具体操作给出更明确的错误
  }
  await loadOrganizations(
    Array.isArray(organizations) ? organizations : [],
    Array.isArray(scopes) ? scopes : [],
  )
}

/** 清空全部账号相关状态——否则上一个账号的组织/余额/任务会留在界面上。 */
function resetAccountState(): void {
  clearDeferredPoll()
  deferredDisputeRequestId.value = ''
  orgs.value = []
  stores.value = []
  storeScopes.value = []
  organizationAccessIds.value = new Set()
  hasMerchantIdentity.value = false
  activeOrgId.value = ''
  selectedStoreId.value = ''
  account.value = null
  tasks.value = []
  applications.value = []
  selectedTaskId.value = ''
  activeDisputeId.value = ''
  outcomes.value = {}
  contestReasons.value = {}
  notice.value = ''
  applicantReputation.value = {}
  applicantProfile.value = {}
  levelFilter.value = ''
  rateFilterPct.value = 0
  confirmedAppIds.value = new Set()
  recommendations.value = null
  recommendationsLoading.value = false
  invitingAccountId.value = ''
  feedItems.value = []
  feedCursor.value = ''
  feedHasMore.value = false
  editingDraft.value = null
  revisingTask.value = null
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
  if (!activeOrgId.value || !activeOrgHasOrganizationAccess.value) {
    account.value = null
    return
  }
  // 账户可能尚未开通（404）→ 静默，由「开通账户」按钮处理
  const existing = await grassland.getAccount(activeOrgId.value)
  account.value = existing
  if (existing) grassland.clearError()
}

async function changeOrganization(): Promise<void> {
  selectedStoreId.value = ''
  await loadActiveOrganizationStores()
  await refreshAccount()
  await refreshTasks()
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

/** 商家列表展示的任务状态；顺序即列表顺序（待处理的排前面）。 */
const MERCHANT_TASK_STATUSES = ['draft', 'pending_review', 'published', 'closed', 'cancelled'] as const

/**
 * 商家任务列表：四态全取。
 *
 * 后端 `GET /api/tasks?status=` 一次只收一个 status，所以并发取多次再合并。浏览器实测发现两处漏洞：
 * 只取 published 时刚存下的草稿不出现，「编辑 / 发布」入口无从触达；漏掉 closed 时**关闭报名后
 * 整条任务从列表消失**，商家再也无法处理已提交的报名（accept / reject）。cancelled 也一并显示，
 * 否则「取消任务」点完没有任何可见结果。
 */
async function refreshTasks(): Promise<void> {
  if (!activeOrgId.value) return
  const orgId = activeOrgId.value
  const groups = await Promise.all(
    MERCHANT_TASK_STATUSES.map((status) => grassland.listTasks(orgId, status, selectedStoreId.value || undefined)))
  if (groups.some((g) => g)) tasks.value = groups.flatMap((g) => g ?? [])
}

async function publishTask(): Promise<void> {
  if (!activeOrgId.value || !taskForm.value.title.trim()) return
  const bountyCents = yuanToCents(taskForm.value.bountyYuan)
  const created = await grassland.createTask({
    organizationId: activeOrgId.value,
    storeId: selectedStoreId.value || undefined,
    title: taskForm.value.title.trim(),
    description: taskForm.value.description.trim() || undefined,
    platform: taskForm.value.platform.trim() || undefined,
    contentForm: taskForm.value.contentForm.trim() || undefined,
    maxSlots: taskForm.value.maxSlots > 0 ? taskForm.value.maxSlots : undefined,
    bountyCents: bountyCents > 0 ? bountyCents : undefined,
    applicationDeadline: deadlineIso(),
    minRecommenderLevel: taskForm.value.minRecommenderLevel,
    requirements: taskRequirements(),
  })
  if (!created) return
  resetTaskForm()
  setNotice(`任务「${created.title}」已提交审核，审核通过后将在大厅上架`)
  await refreshTasks()
}

/** datetime-local 字符串 → ISO（给后端）；空或不可解析 → undefined（无截止）。 */
function deadlineIso(): string | undefined {
  const raw = taskForm.value.applicationDeadline
  if (!raw) return undefined
  const ms = Date.parse(raw)
  return Number.isNaN(ms) ? undefined : new Date(ms).toISOString()
}

function localDateTimeIso(value: string): string | undefined {
  if (!value) return undefined
  const ms = Date.parse(value)
  return Number.isNaN(ms) ? undefined : new Date(ms).toISOString()
}

function lines(value: string): string[] {
  return [...new Set(value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean))]
}

function taskRequirements() {
  return {
    productServiceInfo: taskForm.value.productServiceInfo.trim() || undefined,
    mustInclude: lines(taskForm.value.mustInclude),
    forbiddenContent: lines(taskForm.value.forbiddenContent),
    publishStartAt: localDateTimeIso(taskForm.value.publishStartAt),
    publishEndAt: localDateTimeIso(taskForm.value.publishEndAt),
    metricRequirements: lines(taskForm.value.metricRequirements),
    evidenceRequirements: lines(taskForm.value.evidenceRequirements),
  }
}

/** ISO → datetime-local（回填编辑草稿用）。 */
function isoToLocalInput(iso: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n: number): string => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function resetTaskForm(): void {
  taskForm.value = { title: '', description: '', platform: '', contentForm: '', maxSlots: 1, bountyYuan: 0,
    applicationDeadline: '', minRecommenderLevel: 1, productServiceInfo: '', mustInclude: '',
    forbiddenContent: '', publishStartAt: '', publishEndAt: '', metricRequirements: '', evidenceRequirements: '' }
  editingDraft.value = null
  revisingTask.value = null
}

/** 存草稿 / 保存修订：revisingTask → POST /revise，editingDraft → PUT 草稿，否则 POST 新建草稿。 */
async function saveDraft(): Promise<void> {
  if (!activeOrgId.value || !taskForm.value.title.trim()) return
  const bountyCents = yuanToCents(taskForm.value.bountyYuan)
  const revising = revisingTask.value
  if (revising) {
    // 全字段修订：accept/结算读 app 的 bounty 快照（snapshot-pinning），改 task 赏金只影响新报名。
    const revised = await grassland.reviseTask(revising.id, {
      expectedVersion: revising.version,
      title: taskForm.value.title.trim(),
      description: taskForm.value.description.trim() || undefined,
      platform: taskForm.value.platform.trim() || undefined,
      contentForm: taskForm.value.contentForm.trim() || undefined,
      maxSlots: taskForm.value.maxSlots > 0 ? taskForm.value.maxSlots : undefined,
      bountyCents: bountyCents > 0 ? bountyCents : undefined,
      applicationDeadline: deadlineIso(),
      minRecommenderLevel: taskForm.value.minRecommenderLevel,
      requirements: taskRequirements(),
    })
    if (!revised) return
    setNotice(`任务「${revised.title}」已修订出新版本（v${revised.version}）`)
    resetTaskForm()
    await refreshTasks()
    return
  }
  const editing = editingDraft.value
  if (editing) {
    const updated = await grassland.updateTask(editing.id, {
      expectedVersion: editing.version,
      title: taskForm.value.title.trim(),
      description: taskForm.value.description.trim() || undefined,
      platform: taskForm.value.platform.trim() || undefined,
      contentForm: taskForm.value.contentForm.trim() || undefined,
      maxSlots: taskForm.value.maxSlots > 0 ? taskForm.value.maxSlots : undefined,
      bountyCents: bountyCents > 0 ? bountyCents : undefined,
      applicationDeadline: deadlineIso(),
      minRecommenderLevel: taskForm.value.minRecommenderLevel,
      requirements: taskRequirements(),
    })
    if (!updated) return
    setNotice(`草稿「${updated.title}」已更新（v${updated.version}）`)
  } else {
    const created = await grassland.createDraft({
      organizationId: activeOrgId.value,
      storeId: selectedStoreId.value || undefined,
      title: taskForm.value.title.trim(),
      description: taskForm.value.description.trim() || undefined,
      platform: taskForm.value.platform.trim() || undefined,
      contentForm: taskForm.value.contentForm.trim() || undefined,
      maxSlots: taskForm.value.maxSlots > 0 ? taskForm.value.maxSlots : undefined,
      bountyCents: bountyCents > 0 ? bountyCents : undefined,
      applicationDeadline: deadlineIso(),
      minRecommenderLevel: taskForm.value.minRecommenderLevel,
      requirements: taskRequirements(),
    })
    if (!created) return
    setNotice(`草稿「${created.title}」已保存`)
  }
  resetTaskForm()
  await refreshTasks()
}

/** 把草稿载入表单供编辑。 */
function editDraft(task: Task): void {
  editingDraft.value = { id: task.id, version: task.version }
  revisingTask.value = null
  selectedStoreId.value = task.storeId || ''
  taskForm.value = {
    title: task.title,
    description: task.description || '',
    platform: task.platform || '',
    contentForm: task.contentForm || '',
    maxSlots: task.maxSlots ?? 1,
    bountyYuan: task.bountyCents ? task.bountyCents / 100 : 0,
    applicationDeadline: isoToLocalInput(task.applicationDeadline),
    minRecommenderLevel: task.minRecommenderLevel ?? 1,
    productServiceInfo: task.requirements?.productServiceInfo || '',
    mustInclude: (task.requirements?.mustInclude || []).join('\n'),
    forbiddenContent: (task.requirements?.forbiddenContent || []).join('\n'),
    publishStartAt: isoToLocalInput(task.requirements?.publishStartAt || null),
    publishEndAt: isoToLocalInput(task.requirements?.publishEndAt || null),
    metricRequirements: (task.requirements?.metricRequirements || []).join('\n'),
    evidenceRequirements: (task.requirements?.evidenceRequirements || []).join('\n'),
  }
}

/**
 * 把已发布任务载入表单供修订（出新版本，全字段可改）。改赏金/平台只影响新报名——
 * 已接受的履约按其接受时的金额结算（accept 时冻了 bounty_cents 快照）。
 */
function editPublished(task: Task): void {
  revisingTask.value = { id: task.id, version: task.version }
  editingDraft.value = null
  selectedStoreId.value = task.storeId || ''
  taskForm.value = {
    title: task.title,
    description: task.description || '',
    platform: task.platform || '',
    contentForm: task.contentForm || '',
    maxSlots: task.maxSlots ?? 1,
    bountyYuan: task.bountyCents ? task.bountyCents / 100 : 0,
    applicationDeadline: isoToLocalInput(task.applicationDeadline),
    minRecommenderLevel: task.minRecommenderLevel ?? 1,
    productServiceInfo: task.requirements?.productServiceInfo || '',
    mustInclude: (task.requirements?.mustInclude || []).join('\n'),
    forbiddenContent: (task.requirements?.forbiddenContent || []).join('\n'),
    publishStartAt: isoToLocalInput(task.requirements?.publishStartAt || null),
    publishEndAt: isoToLocalInput(task.requirements?.publishEndAt || null),
    metricRequirements: (task.requirements?.metricRequirements || []).join('\n'),
    evidenceRequirements: (task.requirements?.evidenceRequirements || []).join('\n'),
  }
}

async function publishDraft(task: Task): Promise<void> {
  const published = await grassland.publishDraft(task.id, task.version)
  if (!published) return
  setNotice(`任务「${published.title}」已提交审核，审核通过后将在大厅上架`)
  await refreshTasks()
}

async function closeTaskAction(task: Task): Promise<void> {
  const closed = await grassland.closeTask(task.id, task.version)
  if (!closed) return
  setNotice(`任务「${closed.title}」已关闭报名`)
  await refreshTasks()
}

async function cancelTaskAction(task: Task): Promise<void> {
  const cancelled = await grassland.cancelTask(task.id, task.version)
  if (!cancelled) return
  // 后端同时把「已接受未提交」的履约退款并置终态 refunded（D-03 §5），故必须连报名列表一起刷，
  // 否则当前选中任务仍显示「已接受 + 确认履约」（点下去必 409）。同时清掉 accept 轮询留下的过期结果文案。
  const refunded = cancelled.refundedCount ?? 0
  setNotice(refunded > 0
    ? `任务「${cancelled.title}」已取消，${refunded} 个已接受履约已全额退款`
    : `任务「${cancelled.title}」已取消`)
  await refreshTasks()
  if (selectedTaskId.value === task.id) {
    outcomes.value = {}
    await selectTask(task.id)
  }
}

function taskStatusLabel(status: string): string {
  const map: Record<string, string> = {
    draft: '草稿', pending_review: '待审核', published: '已发布',
    closed: '已关闭报名', cancelled: '已取消',
  }
  return map[status] || status
}

async function selectTask(taskId: string): Promise<void> {
  selectedTaskId.value = taskId
  applications.value = []
  recommendations.value = null
  // 切任务即清空上一份报名者的声誉/画像与已确认集合——否则筛选会串数据。
  applicantReputation.value = {}
  applicantProfile.value = {}
  confirmedAppIds.value = new Set()
  // 任务书 #24：切换任务同步拉门店公开资料（与报名列表并行，不阻塞）。
  void loadStorePublicProfile()
  const recommendationRequest = side.value === 'merchant'
    ? loadRecommendations(taskId)
    : Promise.resolve()
  const list = await grassland.listApplications(taskId)
  if (list) applications.value = list
  await recommendationRequest
  await loadApplicantProfiles()
}

/** 任务书 #24：拉选中任务的门店公开资料；组织级任务/404 → 面板空态。 */
async function loadStorePublicProfile(): Promise<void> {
  const requested = selectedTask.value?.storeId ?? null
  storePublicProfile.value = null
  storePublicProfileError.value = ''
  if (!requested) return
  storePublicProfileLoading.value = true
  try {
    const profile = await grassland.getStorePublicProfile(requested)
    // 快速切换任务时丢弃过期响应，避免串到别的任务上。
    if ((selectedTask.value?.storeId ?? null) !== requested) return
    storePublicProfile.value = profile
    if (!profile) storePublicProfileError.value = '该门店暂无公开资料'
  } finally {
    if ((selectedTask.value?.storeId ?? null) === requested) {
      storePublicProfileLoading.value = false
    }
  }
}

async function loadRecommendations(taskId = selectedTaskId.value): Promise<void> {
  const task = [...tasks.value, ...feedItems.value].find((item) => item.id === taskId)
  if (!taskId || side.value !== 'merchant' || task?.status !== 'published') {
    recommendations.value = null
    return
  }
  recommendationsLoading.value = true
  const page = await grassland.listRecommenderRecommendations(taskId, 50)
  recommendationsLoading.value = false
  if (page && selectedTaskId.value === taskId) recommendations.value = page
}

async function inviteRecommended(match: RecommenderMatch): Promise<void> {
  if (!selectedTaskId.value || invitingAccountId.value) return
  invitingAccountId.value = match.accountId
  const invitation = await grassland.inviteRecommender(selectedTaskId.value, match.accountId)
  invitingAccountId.value = ''
  if (!invitation || !recommendations.value) return
  recommendations.value = {
    ...recommendations.value,
    items: recommendations.value.items.map((item) => item.accountId === match.accountId
      ? { ...item, invitation }
      : item),
  }
  setNotice(invitation.created === false ? '该推荐官已收到过邀请' : '任务邀请已发送到推荐官通知中心')
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

/** 系统核实通过后，商家可在确认窗口内拒绝并转客服；后端门闩与确认 Timer 原子决胜。 */
async function contest(app: TaskApplication): Promise<void> {
  const reason = contestReasons.value[app.id]?.trim() || ''
  if (!reason) {
    setNotice('请先填写拒绝理由')
    return
  }
  outcomes.value = { ...outcomes.value, [app.id]: '正在转客服…' }
  const contested = await grassland.contestEngagement(app.taskId, app.id, reason)
  if (!contested) {
    outcomes.value = { ...outcomes.value, [app.id]: '' }
    return
  }
  outcomes.value = { ...outcomes.value, [app.id]: '已拒绝并转客服裁定' }
  setNotice('商家异议已提交，结算已暂停并转客服裁定')
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

/** 加载全局大厅 feed（reset=true 重新查首页；否则按游标加载更多）。 */
async function loadFeed(reset = false): Promise<void> {
  if (feedLoading.value) return
  if (feedFilters.value.maxDistanceKm > 0
      && (feedFilters.value.latitude == null || feedFilters.value.longitude == null)) {
    setNotice('请先允许获取当前位置，再使用距离筛选')
    return
  }
  feedLoading.value = true
  const page = await grassland.listTaskFeed({
    platform: feedFilters.value.platform.trim() || undefined,
    contentForm: feedFilters.value.contentForm.trim() || undefined,
    minBountyCents: feedFilters.value.minBountyYuan > 0 ? yuanToCents(feedFilters.value.minBountyYuan) : undefined,
    latitude: feedFilters.value.maxDistanceKm > 0 ? feedFilters.value.latitude! : undefined,
    longitude: feedFilters.value.maxDistanceKm > 0 ? feedFilters.value.longitude! : undefined,
    maxDistanceKm: feedFilters.value.maxDistanceKm > 0 ? feedFilters.value.maxDistanceKm : undefined,
    cursor: reset ? undefined : (feedCursor.value || undefined),
    limit: 20,
  })
  feedLoading.value = false
  if (!page) return
  feedItems.value = reset || !feedCursor.value ? page.items : [...feedItems.value, ...page.items]
  feedCursor.value = page.nextCursor || ''
  feedHasMore.value = page.hasMore
  grassland.clearError()
}

function useCurrentLocation(): void {
  if (!navigator.geolocation || locating.value) {
    if (!navigator.geolocation) setNotice('当前浏览器不支持定位')
    return
  }
  locating.value = true
  navigator.geolocation.getCurrentPosition((position) => {
    feedFilters.value.latitude = position.coords.latitude
    feedFilters.value.longitude = position.coords.longitude
    if (feedFilters.value.maxDistanceKm <= 0) feedFilters.value.maxDistanceKm = 5
    locating.value = false
    setNotice('已获取当前位置，可按距离查询任务')
  }, (positionError) => {
    locating.value = false
    setNotice(positionError.code === positionError.PERMISSION_DENIED
      ? '定位权限被拒绝，请在浏览器设置中允许定位'
      : '暂时无法获取位置，请稍后重试')
  }, { enableHighAccuracy: false, timeout: 10000, maximumAge: 300000 })
}

/** 推荐官撤销本人 pending 报名（GL-P1-TASK-001：前端原缺入口）。 */
async function withdrawApp(app: TaskApplication): Promise<void> {
  const withdrawn = await grassland.withdrawApplication(selectedTaskId.value, app.id)
  if (!withdrawn) return
  setNotice('已撤销报名')
  if (selectedTaskId.value) {
    const list = await grassland.listApplications(selectedTaskId.value)
    if (list) applications.value = list
  }
}

function clearDeferredPoll(): void {
  if (deferredPollTimer !== null) {
    clearTimeout(deferredPollTimer)
    deferredPollTimer = null
  }
}

function scheduleDeferredPoll(requestId: string): void {
  clearDeferredPoll()
  deferredPollTimer = setTimeout(async () => {
    deferredPollTimer = null
    // 账号/视角切换或新请求已替代旧请求时，不让过期回调继续更新 UI。
    if (deferredDisputeRequestId.value !== requestId) return
    const request = await grassland.getDisputeRequest(requestId)
    if (deferredDisputeRequestId.value !== requestId) return
    if (!request) {
      // 暂时失败保留 durable request，低频重试；error 同时给用户可见。
      scheduleDeferredPoll(requestId)
      return
    }
    if (request.status === 'promoted' && request.disputeId) {
      deferredDisputeRequestId.value = ''
      activeDisputeId.value = request.disputeId
      setNotice('普通争议已自动开启并进入七官审判流程')
      return
    }
    scheduleDeferredPoll(requestId)
  }, DEFERRED_POLL_MS)
}

onBeforeUnmount(clearDeferredPoll)

async function dispute(app: TaskApplication): Promise<void> {
  const opened = await grassland.openDispute(app.id, '履约存在争议')
  if (!opened) return
  if (opened.kind === 'deferred') {
    activeDisputeId.value = ''
    deferredDisputeRequestId.value = opened.request.requestId
    setNotice('异议已记录，客服案终局后自动开普通争议')
    scheduleDeferredPoll(opened.request.requestId)
    return
  }
  clearDeferredPoll()
  deferredDisputeRequestId.value = ''
  activeDisputeId.value = opened.dispute.id
  setNotice(`争议已开启（状态 ${opened.dispute.status}），结算将被暂停`)
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

  if (next === 'merchant' && !hasMerchantIdentity.value && managerStoreScopes.value.length > 0) {
    grassland.clearError()
    await refreshTasks()
    return
  }

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
 * 切到推荐官视角时加载全局任务大厅 feed 首页（GL-P1-TASK-001 Stage 2）。
 * 仅在尚未加载时触发，避免每次来回切换都重拉；用户可点「查询」强制刷新。
 */
watch(side, async (s) => {
  if (s === 'recommender' && feedItems.value.length === 0) {
    await loadFeed(true)
  }
})

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

function statusLabel(status: string): string {
  const map: Record<string, string> = {
    pending: '待处理',
    reserving: '预留中',
    accepted: '已接受',
    rejected: '已拒绝',
    withdrawn: '已撤销',
    // 商家取消任务且该履约未提交凭证 → 已全额退商家（D-03 §5），终态。
    refunded: '任务已取消（已退款）',
  }
  return map[status] || status
}

function handleTaskFormUpdate(field: string, value: string | number): void {
  ;(taskForm.value as Record<string, string | number>)[field] = value
}

async function handleTaskFormStoreChange(storeId: string): Promise<void> {
  selectedStoreId.value = storeId
  await refreshTasks()
}

function handleFeedFilterUpdate(field: string, value: string | number): void {
  ;(feedFilters.value as Record<string, string | number>)[field] = value
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
      <MyInvitationsCard @joined="() => loadOrganizations()" />
    </article>

    <article class="gl-card gl-card-wide">
      <MySessionsCard />
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
            <span v-if="t.minRecommenderLevel > 1" class="gl-tag">Lv{{ t.minRecommenderLevel }}+</span>
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
                    <template v-if="a.status === 'accepted'">
                      <button type="button" :disabled="Boolean(taskContextLoadingAppId)" @click="openAcceptedTaskCreation(a)">
                        {{ taskContextLoadingAppId === a.id ? '加载上下文...' : '围绕任务创作' }}
                      </button>
                      <button type="button" :disabled="grassland.loading.value" @click="confirm(a)">确认履约</button>
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

      <RecommenderTaskHall
        :feed-items="feedItems"
        :feed-has-more="feedHasMore"
        :feed-loading="feedLoading"
        :feed-filters="feedFilters"
        :apply-note="applyNote"
        :selected-task-id="selectedTaskId"
        :loading="grassland.loading.value"
        :locating="locating"
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
.gl-outcome { font-size: 12px; opacity: 0.8; }
.gl-filter { display: flex; gap: 14px; align-items: center; flex-wrap: wrap; font-size: 13px; }
.gl-filter label { display: flex; align-items: center; gap: 6px; opacity: 0.85; }
.gl-filter select { padding: 4px 8px; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text); border-radius: 6px; font-size: 13px; }
</style>
