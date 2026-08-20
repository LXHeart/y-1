import { computed, ref, type Ref } from 'vue'
import type { useGrassland } from '../../../composables/useGrassland'
import type {
  BatchItemResult,
  RecommenderMatch,
  RecommenderProfile,
  RecommenderRecommendationPage,
  RecommenderReputation,
  StorePublicProfile,
  Task,
  TaskApplication,
} from '../../../types/grassland'
import { calculateCommissionPayoutCents, parseConfirmedMetricValue } from '../components/commission-ladder'

/**
 * 工作台履约域：任务列表（五态全取）+ 选中任务的报名全生命周期。
 *
 * 从 GrasslandWorkbench.vue 原样迁出（行为不变）：
 * - 商家侧：报名筛选（等级/完成率）、批量接受/拒绝（含 reserving 逐个轮询）、
 *   接受（202→轮询预留）、拒绝、异议转客服、确认履约（阶梯任务申报指标+结算轮询）。
 * - 推荐官侧：撤销报名。
 * - 按报名者并发拉声誉/画像——后端刻意不提供「按条件搜人」入口（那会把平台变成
 *   人肉数据库），故筛选在拉到全量报名后于前端做。
 */
export function useWorkbenchEngagements(
  grassland: ReturnType<typeof useGrassland>,
  setNotice: (message: string) => void,
  refs: {
    side: Ref<'merchant' | 'recommender'>
    activeOrgId: Ref<string>
    selectedStoreId: Ref<string>
    feedItems: Ref<Task[]>
    refreshAccount: () => Promise<void>
  },
) {
  const { side, activeOrgId, selectedStoreId, feedItems, refreshAccount } = refs

  const tasks = ref<Task[]>([])
  const applications = ref<TaskApplication[]>([])
  const selectedTaskId = ref('')

  /** 每个 application 的异步结局（accept 预留 / confirm 结算），key = applicationId。 */
  const outcomes = ref<Record<string, string>>({})
  const taskContextLoadingAppId = ref('')

  /** 商家拒绝理由按 application 独立保存，避免多条报名共用输入串值。 */
  const contestReasons = ref<Record<string, string>>({})
  /**
   * 任务书 #25：商家按 application 申报的实际指标原始输入（key = applicationId）。
   * 确认成功即清理；失败保留以便修正重试。仅阶梯佣金任务渲染输入。
   */
  const confirmedMetricInputs = ref<Record<string, string>>({})

  // ---------- 任务书 #24：任务详情携带门店公开块 ----------
  const storePublicProfile = ref<StorePublicProfile | null>(null)
  const storePublicProfileLoading = ref(false)
  const storePublicProfileError = ref('')

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

  /** 任务书 #27：批量操作选中的 applicationId 集合。 */
  const selectedAppIds = ref<Set<string>>(new Set())
  /** 任务书 #27：批量操作进行中。 */
  const batchLoading = ref(false)

  /** Lv 字符串 → 序号，用于「等级 ≥」比较。 */
  const LEVEL_ORDER: Record<string, number> = { Lv1: 1, Lv2: 2, Lv3: 3, Lv4: 4, Lv5: 5 }

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

  /** 任务书 #27：筛选结果中可操作的 pending 报名。 */
  const pendingFilteredApplications = computed(() =>
    filteredApplications.value.filter((a) => a.status === 'pending'),
  )

  /** 任务书 #27：当前筛选的 pending 是否全部选中。 */
  const allPendingSelected = computed(() =>
    pendingFilteredApplications.value.length > 0
    && pendingFilteredApplications.value.every((a) => selectedAppIds.value.has(a.id)),
  )

  /** 任务书 #27：批量操作按钮是否禁用。 */
  const batchButtonsDisabled = computed(() =>
    batchLoading.value || selectedAppIds.value.size === 0 || grassland.loading.value,
  )

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

  async function selectTask(taskId: string): Promise<void> {
    selectedTaskId.value = taskId
    applications.value = []
    recommendations.value = null
    // 切任务即清空上一份报名者的声誉/画像与已确认集合——否则筛选会串数据。
    applicantReputation.value = {}
    applicantProfile.value = {}
    confirmedAppIds.value = new Set()
    selectedAppIds.value = new Set()
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
      ? `已接受（资金已预留）${outcome.taskClosed ? '；任务名额已满，已自动关闭' : ''}`
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

  // ---------- 任务书 #27：批量操作 ----------

  function toggleSelectAll(): void {
    const pending = pendingFilteredApplications.value
    if (allPendingSelected.value) {
      const next = new Set(selectedAppIds.value)
      for (const a of pending) next.delete(a.id)
      selectedAppIds.value = next
    } else {
      selectedAppIds.value = new Set([...selectedAppIds.value, ...pending.map((a) => a.id)])
    }
  }

  function toggleSelectApp(appId: string): void {
    const next = new Set(selectedAppIds.value)
    if (next.has(appId)) next.delete(appId)
    else next.add(appId)
    selectedAppIds.value = next
  }

  /** 构建批量操作结果汇总文案。 */
  function buildBatchSummary(results: BatchItemResult[], action: 'accept' | 'reject'): string {
    const succeeded = results.filter((r) => r.outcome === 'accepted' || r.outcome === 'rejected').length
    const reserving = results.filter((r) => r.outcome === 'reserving').length
    const failed = results.filter((r) => r.outcome === 'failed')
    const parts: string[] = []
    if (succeeded > 0) parts.push(`${succeeded} 条${action === 'accept' ? '已接受' : '已拒绝'}`)
    if (reserving > 0) parts.push(`${reserving} 条资金预留中`)
    if (failed.length > 0) {
      const reasons: Record<string, number> = {}
      for (const f of failed) reasons[f.reason || '未知'] = (reasons[f.reason || '未知'] || 0) + 1
      const reasonText = Object.entries(reasons).map(([r, c]) => `${r}×${c}`).join('、')
      parts.push(`${failed.length} 条失败（${reasonText}）`)
    }
    // #26 满员自动关闭（D12）：任一项接受触发关闭即汇总提示
    if (results.some((r) => r.taskClosed)) parts.push('任务名额已满，已自动关闭')
    return parts.join('；') || '操作完成'
  }

  async function batchAccept(): Promise<void> {
    if (!selectedTaskId.value || batchButtonsDisabled.value) return
    batchLoading.value = true
    try {
      const ids = filteredApplications.value
        .filter((a) => a.status === 'pending' && selectedAppIds.value.has(a.id))
        .map((a) => a.id)
      const response = await grassland.batchAcceptApplications(selectedTaskId.value, ids)
      if (response) {
        // reserving 项逐个轮询
        for (const r of response.results) {
          if (r.outcome === 'reserving') {
            outcomes.value = { ...outcomes.value, [r.applicationId]: '处理中…' }
            const outcome = await grassland.pollReservation(selectedTaskId.value, r.applicationId)
            const label = outcome?.status === 'accepted'
              ? `已接受（资金已预留）${outcome.taskClosed ? '；任务名额已满，已自动关闭' : ''}`
              : outcome?.status === 'compensated'
                ? `未接受：${outcome.reason === 'insufficient_funds' ? '账户余额不足' : outcome.reason || '预留失败'}`
                : '处理中…'
            outcomes.value = { ...outcomes.value, [r.applicationId]: label }
          }
        }
        setNotice(`批量接受：${buildBatchSummary(response.results, 'accept')}`)
      }
      selectedAppIds.value = new Set()
      await selectTask(selectedTaskId.value)
      await refreshAccount()
    } finally {
      batchLoading.value = false
    }
  }

  async function batchReject(): Promise<void> {
    if (!selectedTaskId.value || batchButtonsDisabled.value) return
    batchLoading.value = true
    try {
      const ids = filteredApplications.value
        .filter((a) => a.status === 'pending' && selectedAppIds.value.has(a.id))
        .map((a) => a.id)
      const response = await grassland.batchRejectApplications(selectedTaskId.value, ids)
      if (response) {
        setNotice(`批量拒绝：${buildBatchSummary(response.results, 'reject')}`)
      }
      selectedAppIds.value = new Set()
      await selectTask(selectedTaskId.value)
    } finally {
      batchLoading.value = false
    }
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

  /** 选中任务的冻结阶梯（无 ladder = 固定佣金任务，确认时无需申报指标）。 */
  function selectedCommissionLadder() {
    return selectedTask.value?.requirements?.commissionLadder ?? null
  }

  /** 解析某 application 的申报输入：未填/负数/非整数/超安全范围 → { value: null, error }。 */
  function confirmedMetricResult(applicationId: string) {
    // v-model 对 type="number" 自动做 .number 转换（'50000' → 50000；空串保持 ''），统一按字符串解析
    return parseConfirmedMetricValue(String(confirmedMetricInputs.value[applicationId] ?? ''))
  }

  /** 预计结算（分）：按冻结档位取已达最高档；未填/非法按 ¥0 展示。 */
  function previewCommissionCents(applicationId: string): number {
    const ladder = selectedCommissionLadder()
    const parsed = confirmedMetricResult(applicationId)
    return ladder && parsed.value != null
      ? calculateCommissionPayoutCents(ladder, parsed.value)
      : 0
  }

  /**
   * 确认履约：202 后轮询结算结局（有未终局争议时为 held）。
   * 阶梯任务先本地校验申报指标（失败 setNotice 不发请求），确认成功清理该输入。
   */
  async function confirm(app: TaskApplication): Promise<void> {
    const ladder = selectedCommissionLadder()
    const parsedMetric = ladder ? confirmedMetricResult(app.id) : { value: null, error: null }
    if (parsedMetric.error) {
      setNotice(parsedMetric.error)
      return
    }
    outcomes.value = { ...outcomes.value, [app.id]: '结算中…' }
    const started = await grassland.confirmEngagement(
      app.taskId,
      app.id,
      ladder ? parsedMetric.value! : undefined,
    )
    if (!started) {
      outcomes.value = { ...outcomes.value, [app.id]: '' }
      return
    }
    if (ladder) {
      const next = { ...confirmedMetricInputs.value }
      delete next[app.id]
      confirmedMetricInputs.value = next
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

  /** 账号切换清空（原 resetAccountState 的任务/报名字段；门店公开资料三态原实现不清，保持一致）。 */
  function reset(): void {
    tasks.value = []
    applications.value = []
    selectedTaskId.value = ''
    outcomes.value = {}
    contestReasons.value = {}
    confirmedMetricInputs.value = {}
    applicantReputation.value = {}
    applicantProfile.value = {}
    levelFilter.value = ''
    rateFilterPct.value = 0
    confirmedAppIds.value = new Set()
    selectedAppIds.value = new Set()
    recommendations.value = null
    recommendationsLoading.value = false
    invitingAccountId.value = ''
  }

  return {
    tasks, applications, selectedTaskId, selectedTask,
    outcomes, taskContextLoadingAppId,
    contestReasons, confirmedMetricInputs,
    storePublicProfile, storePublicProfileLoading, storePublicProfileError,
    applicantReputation, applicantProfile, levelFilter, rateFilterPct,
    recommendations, recommendationsLoading, invitingAccountId, confirmedAppIds,
    selectedAppIds, batchLoading,
    filteredApplications, pendingFilteredApplications, allPendingSelected, batchButtonsDisabled,
    refreshTasks, publishDraft, closeTaskAction, cancelTaskAction,
    taskStatusLabel, statusLabel, selectTask, loadRecommendations, inviteRecommended,
    accept, reject, toggleSelectAll, toggleSelectApp, batchAccept, batchReject,
    contest, selectedCommissionLadder, confirmedMetricResult, previewCommissionCents, confirm,
    withdrawApp, reset,
  }
}
