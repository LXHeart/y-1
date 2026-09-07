import { nextTick, watch, type Ref } from 'vue'
import type { useGrassland } from '../../../composables/useGrassland'
import { normalizeTaskCreationSelection } from '../../../config/ai-platform-capabilities'
import type { CreationEntry } from '../../../types/ai-creation'
import type { NotificationLinkTarget } from '../../../types/notification'
import type { MyApplication, Task } from '../../../types/grassland'

/**
 * 工作台导航编排（任务书 #91 W2 自 GrasslandWorkbench.vue 整段迁入；纯搬运，注释原样保留）：
 * - 通知锚点滚动（scrollBlockIntoView + ANCHOR_TAB/ANCHOR_FINANCE_SECTION 落点表 + 锚点 watch）；
 * - 通知任务路由（grasslandNavigationTarget watch：商家复核任务 / 推荐官邀请任务）；
 * - creation handoff（openAcceptedTaskCreation / openMyTaskCreation / startCreationFromDetail——
 *   emit 定义仍在 SFC，经 deps 传入回调）。
 *
 * inject 的两个键（grasslandAnchor/grasslandNavigationTarget）按卡内二选一记录：留在 SFC 层
 * inject 后传引用进来（子 composable 内不 inject，保持可测性不变）。
 */
export function useWorkbenchNavigation(deps: {
  grassland: ReturnType<typeof useGrassland>
  grasslandAnchor: Ref<string>
  grasslandNavigationTarget: Ref<NotificationLinkTarget | null>
  emit: (event: 'open-creation', entry: CreationEntry) => void
  setNotice: (message: string) => void
  side: Readonly<Ref<'merchant' | 'recommender'>>
  switchSide: (side: 'merchant' | 'recommender') => Promise<unknown>
  subTab: Ref<string>
  financeSection: Ref<string>
  tasks: Ref<Task[]>
  feedItems: Ref<Task[]>
  taskContextLoadingAppId: Ref<string>
  selectTask: (taskId: string) => Promise<unknown>
  openTaskDetail: (taskId: string) => void
}) {
  const {
    grassland, grasslandAnchor, grasslandNavigationTarget, emit, setNotice,
    side, switchSide, subTab, financeSection,
    tasks, feedItems, taskContextLoadingAppId,
    selectTask, openTaskDetail,
  } = deps

  /** 通知锚点滚动尊重系统「减弱动态效果」设置（prefers-reduced-motion 时退化为瞬时定位）。 */
  function scrollBlockIntoView(elementId: string): void {
    const prefersReducedMotion = typeof window.matchMedia === 'function'
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches
    document.getElementById(elementId)?.scrollIntoView({
      behavior: prefersReducedMotion ? 'auto' : 'smooth',
      block: 'start',
    })
  }

  /** 通知锚点 → 所属子页签（按身份侧）：滚动前先切页签（隐藏元素无法 scrollIntoView）。 */
  const ANCHOR_TAB: Readonly<Record<'merchant' | 'recommender', Readonly<Record<string, string>>>> = {
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
  const ANCHOR_FINANCE_SECTION: Readonly<Record<string, string>> = {
    'gl-wallet': 'account',
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

  /** 弹窗 footer「开始创作」：task/application 整包由弹窗抛出（大厅与我的任务两挂载共用）。 */
  function startCreationFromDetail(payload: { task: Task; application: MyApplication | null }): void {
    if (!payload.application) return
    void openAcceptedTaskCreation(
      { id: payload.application.applicationId, taskId: payload.task.id, status: payload.application.applicationStatus },
      payload.task,
    )
  }

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
   *  争议通知自 2026-09-04 起在 DefaultLayout 直达 /me/disputes，不再进工作台。 */
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

  return { openAcceptedTaskCreation, openMyTaskCreation, startCreationFromDetail }
}
