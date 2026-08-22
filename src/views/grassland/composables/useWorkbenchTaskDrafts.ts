import { ref, type Ref } from 'vue'
import type { useGrassland } from '../../../composables/useGrassland'
import { yuanToCents } from '../../../lib/money'
import type { Task } from '../../../types/grassland'
import {
  buildCommissionLadderPayload,
  commissionLadderFormFromTask,
  emptyCommissionLadderForm,
  getCommissionLadderValidationError,
} from '../components/commission-ladder'
import type { CommissionLadderFormData } from '../components/commission-ladder'

/**
 * 工作台任务表单域：发布 / 存草稿 / 修订已发布任务三条提交链路 + 快照回填。
 *
 * 从 GrasslandWorkbench.vue 原样迁出（行为不变）。editingDraft（PUT 草稿）与
 * revisingTask（POST /revise 出新版本）刻意互斥独立——修订走不同端点、冻结资金
 * 字段、保存语义不同（已发布→出新版本，不是存草稿），混在一个标志里要靠 status
 * 二次判分支，易错。
 */
export function useWorkbenchTaskDrafts(
  grassland: ReturnType<typeof useGrassland>,
  setNotice: (message: string) => void,
  refs: {
    activeOrgId: Ref<string>
    selectedStoreId: Ref<string>
    refreshTasks: () => Promise<void>
  },
) {
  const { activeOrgId, selectedStoreId, refreshTasks } = refs

  /** applicationDeadline 存 datetime-local 字符串（"YYYY-MM-DDTHH:mm"）；提交时转 ISO。 */
  const taskForm = ref({
    title: '', description: '', platform: '', contentForm: '', interactionTargetUrl: '', interactionActionType: 'like', maxSlots: 1, bountyYuan: 0, freebieDepositYuan: 0,
    /** 付费方式三选一（PRD §2.2）：commission=任务量佣金（达标即给/阶梯），freebie=霸王餐/实物兑换。 */
    paymentMode: 'commission' as 'commission' | 'freebie',
    applicationDeadline: '', minRecommenderLevel: 1, autoAcceptMinLevel: null as number | null,
    productServiceInfo: '', mustInclude: '', forbiddenContent: '',
    publishStartAt: '', publishEndAt: '', metricRequirements: '', evidenceRequirements: '',
    /** 任务书 #25：阶梯佣金表单元数据（内部 policyVersion 不进 UI）。 */
    commissionLadder: emptyCommissionLadderForm(),
  })
  /** 编辑中的草稿 id/version；非空时「存草稿」走 PUT 更新，否则 POST 新建。 */
  const editingDraft = ref<{ id: string; version: number } | null>(null)
  /** 待修订的已发布任务 id/version（GL-P1-TASK-001：编辑出新版本）。与 editingDraft 互斥。 */
  const revisingTask = ref<{ id: string; version: number } | null>(null)

  async function publishTask(): Promise<void> {
    if (!activeOrgId.value || !taskForm.value.title.trim()) return
    // 付费方式三选一：未选中模式的资金字段一律归零（表单互斥切换 + payload 双保险）
    const bountyCents = taskForm.value.paymentMode === 'freebie'
      ? 0 : yuanToCents(taskForm.value.bountyYuan)
    const freebieDepositCents = taskForm.value.paymentMode === 'commission'
      ? 0 : yuanToCents(taskForm.value.freebieDepositYuan)
    // 任务书 #25：validate-then-build——本地校验失败 setNotice 后不发请求。
    if (!validateTaskCommissionLadder(bountyCents, freebieDepositCents)) return
    const created = await grassland.createTask({
      organizationId: activeOrgId.value,
      storeId: selectedStoreId.value || undefined,
      title: taskForm.value.title.trim(),
      description: taskForm.value.description.trim() || undefined,
      platform: taskForm.value.platform.trim() || undefined,
      contentForm: taskForm.value.contentForm.trim() || undefined,
      maxSlots: taskForm.value.maxSlots > 0 ? taskForm.value.maxSlots : undefined,
      bountyCents: bountyCents > 0 ? bountyCents : undefined,
      freebieDepositCents: freebieDepositCents > 0 ? freebieDepositCents : undefined,
      applicationDeadline: deadlineIso(),
      minRecommenderLevel: taskForm.value.minRecommenderLevel,
      autoAcceptMinLevel: taskForm.value.autoAcceptMinLevel ?? undefined,
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
    // 任务书 #25：禁用阶梯时省略 commissionLadder 键；启用时按阈值升序发送。
    // 付费方式三选一：霸王餐模式下阶梯一律不带（即使表单残留 enabled）。
    const commissionLadder = taskForm.value.paymentMode === 'commission'
      ? buildCommissionLadderPayload(taskForm.value.commissionLadder) : null
    return {
      productServiceInfo: taskForm.value.productServiceInfo.trim() || undefined,
      mustInclude: lines(taskForm.value.mustInclude),
      forbiddenContent: lines(taskForm.value.forbiddenContent),
      publishStartAt: localDateTimeIso(taskForm.value.publishStartAt),
      publishEndAt: localDateTimeIso(taskForm.value.publishEndAt),
      metricRequirements: lines(taskForm.value.metricRequirements),
      evidenceRequirements: lines(taskForm.value.evidenceRequirements),
      // 任务书 #23：仅互动任务带块（后端交叉校验：contentForm=interaction ⇔ interaction 非空）。
      ...(taskForm.value.contentForm === 'interaction' && taskForm.value.interactionTargetUrl.trim()
        ? { interaction: {
            targetUrl: taskForm.value.interactionTargetUrl.trim(),
            actionType: taskForm.value.interactionActionType,
          } }
        : {}),
      ...(commissionLadder ? { commissionLadder } : {}),
    }
  }

  /** 任务书 #25：阶梯佣金整值事件（MerchantTaskForm 以不可变更新发出完整元数据）。 */
  function updateCommissionLadder(value: CommissionLadderFormData): void {
    taskForm.value.commissionLadder = value
  }

  /**
   * 任务书 #25：提交审核 / 存草稿 / 保存修订共用的阶梯校验入口。
   * 失败用 setNotice 展示单一错误且不发请求（后端 400/409 仍走统一错误路径）。
   */
  function validateTaskCommissionLadder(bountyCents: number, freebieDepositCents: number): boolean {
    const error = getCommissionLadderValidationError(
      taskForm.value.commissionLadder,
      bountyCents,
      freebieDepositCents,
    )
    if (error) setNotice(error)
    return error == null
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
    taskForm.value = { title: '', description: '', platform: '', contentForm: '', interactionTargetUrl: '', interactionActionType: 'like', maxSlots: 1, bountyYuan: 0, freebieDepositYuan: 0, paymentMode: 'commission',
      applicationDeadline: '', minRecommenderLevel: 1, autoAcceptMinLevel: null, productServiceInfo: '', mustInclude: '',
      forbiddenContent: '', publishStartAt: '', publishEndAt: '', metricRequirements: '', evidenceRequirements: '',
      commissionLadder: emptyCommissionLadderForm() }
    editingDraft.value = null
    revisingTask.value = null
  }

  /** 存草稿 / 保存修订：revisingTask → POST /revise，editingDraft → PUT 草稿，否则 POST 新建草稿。 */
  async function saveDraft(): Promise<void> {
    if (!activeOrgId.value || !taskForm.value.title.trim()) return
    const bountyCents = taskForm.value.paymentMode === 'freebie'
      ? 0 : yuanToCents(taskForm.value.bountyYuan)
    const freebieDepositCents = taskForm.value.paymentMode === 'commission'
      ? 0 : yuanToCents(taskForm.value.freebieDepositYuan)
    // 任务书 #25：三条提交链路（revise / update / createDraft）共用同一阶梯校验入口。
    if (!validateTaskCommissionLadder(bountyCents, freebieDepositCents)) return
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
    freebieDepositCents: freebieDepositCents > 0 ? freebieDepositCents : undefined,
      applicationDeadline: deadlineIso(),
      minRecommenderLevel: taskForm.value.minRecommenderLevel,
      autoAcceptMinLevel: taskForm.value.autoAcceptMinLevel ?? undefined,
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
    freebieDepositCents: freebieDepositCents > 0 ? freebieDepositCents : undefined,
      applicationDeadline: deadlineIso(),
      minRecommenderLevel: taskForm.value.minRecommenderLevel,
      autoAcceptMinLevel: taskForm.value.autoAcceptMinLevel ?? undefined,
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
    freebieDepositCents: freebieDepositCents > 0 ? freebieDepositCents : undefined,
      applicationDeadline: deadlineIso(),
      minRecommenderLevel: taskForm.value.minRecommenderLevel,
      autoAcceptMinLevel: taskForm.value.autoAcceptMinLevel ?? undefined,
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
    taskForm.value = taskFormFromTask(task)
  }

  /**
   * 把已发布任务载入表单供修订（出新版本，全字段可改）。改赏金/平台只影响新报名——
   * 已接受的履约按其接受时的金额结算（accept 时冻了 bounty_cents 快照）。
   */
  function editPublished(task: Task): void {
    revisingTask.value = { id: task.id, version: task.version }
    editingDraft.value = null
    selectedStoreId.value = task.storeId || ''
    taskForm.value = taskFormFromTask(task)
  }

  /** 任务快照 → 表单值（编辑草稿 / 修订共用回填）。 */
  function taskFormFromTask(task: Task) {
    return {
      title: task.title,
      description: task.description || '',
      platform: task.platform || '',
      contentForm: task.contentForm || '',
      interactionTargetUrl: task.requirements?.interaction?.targetUrl || '',
      interactionActionType: task.requirements?.interaction?.actionType || 'like',
      maxSlots: task.maxSlots ?? 1,
      bountyYuan: task.bountyCents ? task.bountyCents / 100 : 0,
      freebieDepositYuan: task.freebieDepositCents ? task.freebieDepositCents / 100 : 0,
      paymentMode: (task.freebieDepositCents && task.freebieDepositCents > 0 ? 'freebie' : 'commission') as 'commission' | 'freebie',
      applicationDeadline: isoToLocalInput(task.applicationDeadline),
      minRecommenderLevel: task.minRecommenderLevel ?? 1,
      autoAcceptMinLevel: task.autoAcceptMinLevel ?? null,
      productServiceInfo: task.requirements?.productServiceInfo || '',
      mustInclude: (task.requirements?.mustInclude || []).join('\n'),
      forbiddenContent: (task.requirements?.forbiddenContent || []).join('\n'),
      publishStartAt: isoToLocalInput(task.requirements?.publishStartAt || null),
      publishEndAt: isoToLocalInput(task.requirements?.publishEndAt || null),
      metricRequirements: (task.requirements?.metricRequirements || []).join('\n'),
      evidenceRequirements: (task.requirements?.evidenceRequirements || []).join('\n'),
      // 任务书 #25：从任务快照回填阶梯表单；policyVersion 原样保留（未知版本也不擅自升级）。
      commissionLadder: commissionLadderFormFromTask(task.requirements?.commissionLadder),
    }
  }

  function handleTaskFormUpdate(field: string, value: string | number | null): void {
    // commissionLadder 是嵌套对象、走独立的整值事件（updateCommissionLadder），这里只写标量字段。
    ;(taskForm.value as unknown as Record<string, string | number | null>)[field] = value
  }

  async function handleTaskFormStoreChange(storeId: string): Promise<void> {
    selectedStoreId.value = storeId
    await refreshTasks()
  }

  /** 账号切换清空编辑目标（原 resetAccountState 只清两个标志，不清表单本体——保持一致）。 */
  function reset(): void {
    editingDraft.value = null
    revisingTask.value = null
  }

  return {
    taskForm, editingDraft, revisingTask,
    publishTask, saveDraft, editDraft, editPublished, resetTaskForm,
    updateCommissionLadder, handleTaskFormUpdate, handleTaskFormStoreChange, reset,
  }
}
