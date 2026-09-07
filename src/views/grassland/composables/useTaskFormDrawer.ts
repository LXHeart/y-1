import { ref, type Ref } from 'vue'
import type { useGrassland } from '../../../composables/useGrassland'
import type { MyApplication, Task } from '../../../types/grassland'
import { useWorkbenchTaskDrafts } from './useWorkbenchTaskDrafts'

/**
 * 任务表单抽屉 + 任务详情弹窗编排（任务书 #91 W3 自 GrasslandWorkbench.vue 整段迁入；
 * 纯搬运，注释与实现原样保留）：
 * - 抽屉 notice 路由（setTaskFormNotice：抽屉开着进抽屉告警条，否则回落背景页）；
 * - 抽屉开合三模式（新建/编辑草稿/修订已发布）与「去升级」直达；
 * - 发布/存草稿提交链与结果弹窗文案；
 * - confirm 包装（取消任务/批量拒绝/撤销报名——不可逆操作先经确认）；
 * - TaskDetailModal plumbing（openTaskDetail/closeTaskDetail/openDetailDispute）与撤回申请。
 *
 * 六域之一 useWorkbenchTaskDrafts 在此实例化（SFC 不再直接持有）。
 */
export function useTaskFormDrawer(deps: {
  grassland: ReturnType<typeof useGrassland>
  setNotice: (message: string) => void
  activeOrgId: Ref<string>
  selectedStoreId: Ref<string>
  refreshTasks: () => Promise<void>
  subTab: Ref<string>
  orgSection: Ref<string>
  selectedAppIds: Readonly<Ref<Set<string>>>
  batchReject: () => Promise<unknown> | void
  cancelTaskAction: (task: Task) => Promise<unknown> | void
  selectedTaskId: Ref<string>
  clearSelectedTask: () => void
  cancelDispute: () => void
  dispute: (app: { id: string }) => void
  loadMyApplications: () => Promise<unknown>
  loadMyTasksPage: (reset: boolean) => Promise<unknown>
}) {
  const {
    grassland, setNotice,
    activeOrgId, selectedStoreId, refreshTasks,
    subTab, orgSection,
    selectedAppIds, batchReject, cancelTaskAction,
    selectedTaskId, clearSelectedTask, cancelDispute, dispute,
    loadMyApplications, loadMyTasksPage,
  } = deps

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

  return {
    taskFormNotice, taskFormResult, taskFormOpen,
    taskForm, editingDraft, revisingTask,
    updateCommissionLadder, handleTaskFormUpdate, handleTaskFormStoreChange,
    openNewTaskForm, openEditDraft, openEditPublished, cancelTaskForm, goToPermissionUpgrade,
    publishTaskFromDrawer, saveDraftFromDrawer, confirmCancelTask, confirmBatchReject,
    detailShowApply, openTaskDetail, closeTaskDetail, openDetailDispute,
    confirmWithdrawMyApplication, resetTaskDrafts,
  }
}
