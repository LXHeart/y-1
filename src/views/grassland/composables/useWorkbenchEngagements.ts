import { computed, ref, watch, type Ref } from 'vue'
import type { useGrassland } from '../../../composables/useGrassland'
import { useAccountSessionStore, type AccountTicket } from '../../../stores/account-session'
import type {
  ApplicationSettlement, BatchItemResult, RecommenderMatch, RecommenderProfile,
  RecommenderRecommendationPage, RecommenderReputation, StorePublicProfile, Task, TaskApplication,
} from '../../../types/grassland'
import { calculateCommissionPayoutCents, parseConfirmedMetricValue } from '../components/commission-ladder'
import { settlementLabel } from './useWorkbenchSettlement'

export function useWorkbenchEngagements(
  grassland: ReturnType<typeof useGrassland>,
  setNotice: (message: string) => void,
  refs: {
    side: Ref<'merchant' | 'recommender'>
    activeOrgId: Ref<string>
    selectedStoreId: Ref<string>
    activeOrgStoreOnlyView: Ref<boolean>
    feedItems: Ref<Task[]>
    refreshAccount: () => Promise<void>
  },
) {
  const { side, activeOrgId, selectedStoreId, activeOrgStoreOnlyView, feedItems, refreshAccount } = refs
  const session = useAccountSessionStore()
  const tasks = ref<Task[]>([])
  const applications = ref<TaskApplication[]>([])
  const selectedTaskId = ref('')
  const outcomes = ref<Record<string, string>>({})
  const settlements = ref<Record<string, ApplicationSettlement>>({})
  const taskContextLoadingAppId = ref('')
  const contestReasons = ref<Record<string, string>>({})
  const confirmedMetricInputs = ref<Record<string, string>>({})
  const storePublicProfile = ref<StorePublicProfile | null>(null)
  const storePublicProfileLoading = ref(false)
  const storePublicProfileError = ref('')
  const applicantReputation = ref<Record<string, RecommenderReputation>>({})
  const applicantProfile = ref<Record<string, RecommenderProfile>>({})
  const levelFilter = ref('')
  const rateFilterPct = ref(0)
  const recommendations = ref<RecommenderRecommendationPage | null>(null)
  const recommendationsLoading = ref(false)
  const invitingAccountId = ref('')
  const confirmedAppIds = computed(() => new Set(Object.values(settlements.value)
    .filter((item) => item.confirmedAt).map((item) => item.applicationId)))
  const selectedAppIds = ref<Set<string>>(new Set())
  const batchLoading = ref(false)
  const applicationsLoading = ref(false)
  const applicationPage = ref(0)
  const applicationsHasMore = ref(false)
  const applicationLimit = 20
  let cursors: string[] = ['']
  let contextRevision = 0
  let selectionRevision = 0
  let pageRevision = 0
  let tasksRevision = 0

  // 账号 epoch 和组织/选中任务序号共同防住 A -> B -> A 的迟到响应。
  function captureCurrent(selection = true) {
    const ticket = session.capture()
    const context = contextRevision
    const selected = selectionRevision
    return () => session.isCurrent(ticket) && context === contextRevision
      && (!selection || selected === selectionRevision)
  }

  const selectedTask = computed(() => [...tasks.value, ...feedItems.value]
    .find((task) => task.id === selectedTaskId.value) ?? null)
  const taskSummary = computed(() => tasks.value.reduce((summary, task) => {
    const progress = task.progress
    if (!progress) return summary
    summary.pending += progress.pendingApplications
    summary.review += Math.max(0, progress.submittedDeliverables - progress.confirmedDeliverables)
    summary.settling += Math.max(0, progress.confirmedDeliverables - progress.settledEngagements)
    return summary
  }, { pending: 0, review: 0, settling: 0 }))
  const LEVEL_ORDER: Record<string, number> = { Lv1: 1, Lv2: 2, Lv3: 3, Lv4: 4, Lv5: 5 }
  const filteredApplications = computed(() => applications.value.filter((app) => {
    const rep = applicantReputation.value[app.recommenderAccountId]
    const min = LEVEL_ORDER[levelFilter.value] ?? 0
    return !(min > 0 && (!rep || (LEVEL_ORDER[rep.level] ?? 0) < min))
      && !(rateFilterPct.value > 0 && (!rep || rep.completionRate < rateFilterPct.value / 100))
  }))
  const pendingFilteredApplications = computed(() => filteredApplications.value.filter((a) => a.status === 'pending'))
  const allPendingSelected = computed(() => pendingFilteredApplications.value.length > 0
    && pendingFilteredApplications.value.every((a) => selectedAppIds.value.has(a.id)))
  const batchButtonsDisabled = computed(() => batchLoading.value || applicationsLoading.value
    || !pendingFilteredApplications.value.some((a) => selectedAppIds.value.has(a.id)) || grassland.loading.value)

  async function refreshTasks(ticket: AccountTicket = session.capture()): Promise<void> {
    if (!activeOrgId.value || !session.isCurrent(ticket)) return
    const current = captureCurrent(false)
    const sequence = ++tasksRevision
    const orgId = activeOrgId.value
    const storeId = activeOrgStoreOnlyView.value ? selectedStoreId.value || undefined : undefined
    const groups = await Promise.all((['draft', 'pending_review', 'published', 'closed', 'cancelled'] as const)
      .map((status) => grassland.listTasks(orgId, status, storeId)))
    if (!current() || !session.isCurrent(ticket) || sequence !== tasksRevision) return
    if (groups.some((group) => group)) tasks.value = groups.flatMap((group) => group ?? [])
  }

  async function publishDraft(task: Task): Promise<void> {
    const current = captureCurrent(false)
    const published = await grassland.publishDraft(task.id, task.version)
    if (!current() || !published) return
    setNotice(`任务「${published.title}」已提交审核，审核通过后将在大厅上架`)
    await refreshTasks()
  }

  async function closeTaskAction(task: Task): Promise<void> {
    const current = captureCurrent(false)
    const closed = await grassland.closeTask(task.id, task.version)
    if (!current() || !closed) return
    setNotice(`任务「${closed.title}」已关闭报名`)
    await refreshTasks()
    if (current() && selectedTaskId.value === task.id) await selectTask(task.id)
  }

  async function cancelTaskAction(task: Task): Promise<void> {
    const current = captureCurrent(false)
    const cancelled = await grassland.cancelTask(task.id, task.version)
    if (!current() || !cancelled) return
    const refunded = cancelled.refundedCount ?? 0
    setNotice(refunded > 0 ? `任务「${cancelled.title}」已取消，${refunded} 个已接受履约已全额退款`
      : `任务「${cancelled.title}」已取消`)
    await refreshTasks()
    if (current() && selectedTaskId.value === task.id) {
      outcomes.value = {}
      await selectTask(task.id)
    }
  }

  async function endPromotionAction(task: Task): Promise<void> {
    if (!window.confirm(`结束「${task.title}」的推广？新订单将不再归因，已有订单佣金不变。`)) return
    const current = captureCurrent(false)
    const ended = await grassland.endPromotion(task.id, task.version)
    if (!current() || !ended) return
    setNotice(`「${task.title}」的推广已结束`)
    await refreshTasks()
    if (current() && selectedTaskId.value === task.id) await selectTask(task.id)
  }

  function taskStatusLabel(status: string): string {
    const labels: Record<string, string> = { draft: '草稿', pending_review: '待审核', published: '已发布', closed: '已关闭报名', cancelled: '已取消' }
    return labels[status] || status
  }
  function isRejectedDraft(task: Task): boolean {
    return task.status === 'draft' && (task.lastRejectedNote != null || task.lastRejectedAt != null)
  }
  function statusLabel(status: string): string {
    const labels: Record<string, string> = { pending: '待处理', reconsent: '待重新确认条款', reserving: '预留中', accepted: '已接受',
      rejected: '已拒绝', withdrawn: '已撤销', refunded: '任务已取消（已退款）', cancelled: '任务已取消' }
    return labels[status] || status
  }

  function clearSelectedTask(): void {
    selectionRevision += 1
    pageRevision += 1
    selectedTaskId.value = ''
    applications.value = []
    settlements.value = {}
    recommendations.value = null
    applicantReputation.value = {}
    applicantProfile.value = {}
    selectedAppIds.value = new Set()
    applicationPage.value = 0
    applicationsHasMore.value = false
    applicationsLoading.value = false
    recommendationsLoading.value = false
    invitingAccountId.value = ''
    batchLoading.value = false
    storePublicProfile.value = null
    storePublicProfileError.value = ''
    storePublicProfileLoading.value = false
    cursors = ['']
  }

  async function selectTask(taskId: string): Promise<void> {
    clearSelectedTask()
    selectedTaskId.value = taskId
    await Promise.all([loadApplicationPage(0), loadRecommendations(taskId), loadStorePublicProfile()])
  }

  async function toggleSelectTask(taskId: string): Promise<void> {
    if (selectedTaskId.value === taskId) clearSelectedTask()
    else await selectTask(taskId)
  }

  async function loadApplicationPage(page: number): Promise<void> {
    if (!selectedTaskId.value || page < 0 || (page > 0 && !cursors[page])) return
    const contextCurrent = captureCurrent()
    const sequence = ++pageRevision
    const current = () => contextCurrent() && sequence === pageRevision
    applicationsLoading.value = true
    selectedAppIds.value = new Set()
    try {
      const result = await grassland.listApplicationsPage(selectedTaskId.value, cursors[page] || undefined, applicationLimit)
      if (!current() || !result) return
      applicationPage.value = page
      applications.value = result.items
      applicationsHasMore.value = result.hasMore
      cursors = [...cursors.slice(0, page + 1), ...(result.nextCursor ? [result.nextCursor] : [])]
      await Promise.all([loadApplicantProfiles(current), ...result.items.map((app) => refreshSettlement(app, current))])
    } finally {
      if (current()) applicationsLoading.value = false
    }
  }

  async function refreshSettlement(app: TaskApplication, current = captureCurrent()): Promise<void> {
    if (!current()) return
    const state = await grassland.getApplicationSettlement(app.id)
    if (!current() || !state) return
    settlements.value = { ...settlements.value, [app.id]: state }
  }

  function canAct(app: TaskApplication, action: string): boolean {
    return settlements.value[app.id]?.allowedActions.includes(action) ?? false
  }
  function settlementStatusLabel(app: TaskApplication): string {
    return outcomes.value[app.id] || settlementLabel(settlements.value[app.id])
  }

  async function loadStorePublicProfile(): Promise<void> {
    const current = captureCurrent()
    const requested = selectedTask.value?.storeId
    if (!requested) return
    storePublicProfileLoading.value = true
    try {
      const profile = await grassland.getStorePublicProfile(requested)
      if (!current()) return
      storePublicProfile.value = profile
      if (!profile) storePublicProfileError.value = '该门店暂无公开资料'
    } finally {
      if (current()) storePublicProfileLoading.value = false
    }
  }

  async function loadRecommendations(taskId = selectedTaskId.value): Promise<void> {
    if (!taskId || side.value !== 'merchant' || selectedTask.value?.status !== 'published') return
    const current = captureCurrent()
    recommendationsLoading.value = true
    const page = await grassland.listRecommenderRecommendations(taskId, 50)
    if (!current()) return
    recommendationsLoading.value = false
    if (page) recommendations.value = page
  }

  async function inviteRecommended(match: RecommenderMatch): Promise<void> {
    if (!selectedTaskId.value || invitingAccountId.value) return
    const current = captureCurrent()
    invitingAccountId.value = match.accountId
    const invitation = await grassland.inviteRecommender(selectedTaskId.value, match.accountId)
    if (!current()) return
    invitingAccountId.value = ''
    if (!invitation || !recommendations.value) return
    recommendations.value = { ...recommendations.value, items: recommendations.value.items.map((item) =>
      item.accountId === match.accountId ? { ...item, invitation } : item) }
    setNotice(invitation.created === false ? '该推荐官已收到过邀请' : '任务邀请已发送到推荐官通知中心')
  }

  async function loadApplicantProfiles(current: () => boolean): Promise<void> {
    if (!current()) return
    const accountIds = [...new Set(applications.value.map((a) => a.recommenderAccountId))]
    const results = await Promise.all(accountIds.map(async (id) => {
      const [rep, prof] = await Promise.all([grassland.getReputation(id), grassland.getRecommenderProfile(id)])
      return { id, rep, prof }
    }))
    if (!current()) return
    applicantReputation.value = Object.fromEntries(results.filter((r) => r.rep).map((r) => [r.id, r.rep!]))
    applicantProfile.value = Object.fromEntries(results.filter((r) => r.prof).map((r) => [r.id, r.prof!]))
  }

  async function accept(app: TaskApplication): Promise<void> {
    const current = captureCurrent()
    const accountCurrent = captureCurrent(false)
    outcomes.value = { ...outcomes.value, [app.id]: '处理中…' }
    const started = await grassland.acceptApplication(app.taskId, app.id)
    if (!current()) return
    if (!started) { outcomes.value[app.id] = ''; return }
    const outcome = await grassland.pollReservation(app.taskId, app.id, current)
    if (!current()) return
    outcomes.value[app.id] = outcome?.status === 'accepted'
      ? `已接受${outcome.taskClosed ? '；任务名额已满，已自动关闭' : ''}`
      : outcome?.status === 'compensated'
        ? `未接受：${outcome.reason === 'insufficient_funds' ? '账户余额不足' : outcome.reason || '预留失败'}` : ''
    await selectTask(app.taskId)
    if (accountCurrent()) await refreshAccount()
  }

  async function reject(app: TaskApplication): Promise<void> {
    const current = captureCurrent()
    const rejected = await grassland.rejectApplication(app.taskId, app.id)
    if (!current() || !rejected) return
    setNotice('已拒绝该报名')
    await loadApplicationPage(applicationPage.value)
  }

  function toggleSelectAll(): void {
    selectedAppIds.value = allPendingSelected.value ? new Set()
      : new Set(pendingFilteredApplications.value.slice(0, 50).map((a) => a.id))
  }
  function toggleSelectApp(appId: string): void {
    const next = new Set(selectedAppIds.value)
    if (next.has(appId)) next.delete(appId)
    else if (next.size < 50) next.add(appId)
    else setNotice('每次最多处理 50 条报名')
    selectedAppIds.value = next
  }

  function buildBatchSummary(results: BatchItemResult[], action: 'accept' | 'reject'): string {
    const succeeded = results.filter((r) => r.outcome === 'accepted' || r.outcome === 'rejected').length
    const reserving = results.filter((r) => r.outcome === 'reserving').length
    const failed = results.filter((r) => r.outcome === 'failed')
    const parts = [`${succeeded} 条${action === 'accept' ? '已接受' : '已拒绝'}`]
    if (reserving) parts.push(`${reserving} 条资金预留中`)
    if (failed.length) parts.push(`${failed.length} 条失败（${failed.map((r) => r.reason || '未知').join('、')}）`)
    if (results.some((r) => r.taskClosed)) parts.push('任务名额已满，已自动关闭')
    return parts.join('；')
  }

  async function batch(action: 'accept' | 'reject'): Promise<void> {
    if (!selectedTaskId.value || batchButtonsDisabled.value) return
    const current = captureCurrent()
    const taskId = selectedTaskId.value
    const ids = pendingFilteredApplications.value.filter((a) => selectedAppIds.value.has(a.id)).map((a) => a.id)
    if (!ids.length || ids.length > 50) return
    batchLoading.value = true
    try {
      const response = await (action === 'accept' ? grassland.batchAcceptApplications : grassland.batchRejectApplications)(taskId, ids)
      if (!current() || !response) return
      for (const item of response.results) {
        if (item.outcome !== 'reserving') continue
        const outcome = await grassland.pollReservation(taskId, item.applicationId, current)
        if (!current()) return
        if (outcome?.status === 'accepted') Object.assign(item, { outcome: 'accepted', taskClosed: outcome.taskClosed })
        if (outcome?.status === 'compensated') Object.assign(item, { outcome: 'failed', reason: outcome.reason })
      }
      setNotice(`批量${action === 'accept' ? '接受' : '拒绝'}：${buildBatchSummary(response.results, action)}`)
      selectedAppIds.value = new Set()
      await loadApplicationPage(applicationPage.value)
      if (current() && action === 'accept') await refreshAccount()
    } finally {
      if (current()) batchLoading.value = false
    }
  }
  const batchAccept = () => batch('accept')
  const batchReject = () => batch('reject')

  async function contest(app: TaskApplication): Promise<void> {
    const reason = contestReasons.value[app.id]?.trim() || ''
    if (!reason) { setNotice('请先填写拒绝理由'); return }
    const current = captureCurrent()
    const contested = await grassland.contestEngagement(app.taskId, app.id, reason)
    if (!current() || !contested) return
    outcomes.value[app.id] = '已拒绝并转客服裁定'
    setNotice('商家异议已提交，结算已暂停并转客服裁定')
    await refreshSettlement(app, current)
  }

  function selectedCommissionLadder() { return selectedTask.value?.requirements?.commissionLadder ?? null }
  function confirmedMetricResult(applicationId: string) {
    return parseConfirmedMetricValue(String(confirmedMetricInputs.value[applicationId] ?? ''))
  }
  function previewCommissionCents(applicationId: string): number {
    const ladder = selectedCommissionLadder()
    const parsed = confirmedMetricResult(applicationId)
    return ladder && parsed.value != null ? calculateCommissionPayoutCents(ladder, parsed.value) : 0
  }

  async function confirm(app: TaskApplication): Promise<void> {
    const current = captureCurrent()
    const ladder = selectedCommissionLadder()
    const parsed = ladder ? confirmedMetricResult(app.id) : { value: null, error: null }
    if (parsed.error) { setNotice(parsed.error); return }
    const started = await grassland.confirmEngagement(app.taskId, app.id, ladder ? parsed.value! : undefined)
    if (!current() || !started) return
    delete confirmedMetricInputs.value[app.id]
    outcomes.value[app.id] = '确认已受理，等待结算'
    await refreshSettlement(app, current)
    if (!current()) return
    if (settlements.value[app.id]?.confirmedAt) delete outcomes.value[app.id]
    await refreshAccount()
  }

  async function withdrawApp(app: TaskApplication): Promise<void> {
    const current = captureCurrent()
    const withdrawn = await grassland.withdrawApplication(app.taskId, app.id)
    if (!current() || !withdrawn) return
    setNotice('已撤销报名')
    await loadApplicationPage(applicationPage.value)
  }

  function reset(): void {
    contextRevision += 1
    clearSelectedTask()
    tasks.value = []
    outcomes.value = {}
    contestReasons.value = {}
    confirmedMetricInputs.value = {}
    taskContextLoadingAppId.value = ''
    levelFilter.value = ''
    rateFilterPct.value = 0
  }
  watch([activeOrgId, side, () => activeOrgStoreOnlyView.value ? selectedStoreId.value : ''], reset, { flush: 'sync' })
  watch([levelFilter, rateFilterPct], () => { selectedAppIds.value = new Set() }, { flush: 'sync' })

  return {
    tasks, applications, selectedTaskId, selectedTask, taskSummary, outcomes, settlements, taskContextLoadingAppId,
    contestReasons, confirmedMetricInputs, storePublicProfile, storePublicProfileLoading, storePublicProfileError,
    applicantReputation, applicantProfile, levelFilter, rateFilterPct, recommendations, recommendationsLoading,
    invitingAccountId, confirmedAppIds, selectedAppIds, batchLoading, filteredApplications,
    pendingFilteredApplications, allPendingSelected, batchButtonsDisabled, refreshTasks, publishDraft,
    closeTaskAction, cancelTaskAction, endPromotionAction, taskStatusLabel, isRejectedDraft, statusLabel,
    selectTask, toggleSelectTask, clearSelectedTask, loadRecommendations, inviteRecommended,
    accept, reject, toggleSelectAll, toggleSelectApp, batchAccept, batchReject, contest,
    selectedCommissionLadder, confirmedMetricResult, previewCommissionCents, confirm, withdrawApp, reset,
    applicationPage, applicationsHasMore, applicationsLoading, loadApplicationPage,
    refreshSettlement, canAct, settlementStatusLabel,
  }
}
