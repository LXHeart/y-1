import { watch, type ComputedRef, type Ref } from 'vue'
import type { LocationQuery, LocationQueryValue } from 'vue-router'
import type { AccountTicket } from '../../../stores/account-session'

/**
 * 工作台账号级编排（任务书 #91 W3 自 GrasslandWorkbench.vue 整段迁入；纯搬运，
 * 注释与实现原样保留）：
 * - 页签回落 watch（activeTabs 变化后当前 subTab 不在另一侧列表时回落首页签）；
 * - 账号级重置 resetAccountState（六个域一起清）；
 * - side 翻到商家视角时重拉任务（初始化期除外）；
 * - 按账号初始化 watch(currentUser.id)：票据 capture/验票 + URL 恢复入口（W1 产物）。
 */
export function useWorkbenchAccountLifecycle(deps: {
  notice: Ref<string>
  side: Ref<'merchant' | 'recommender'>
  currentUser: Readonly<Ref<{ id: string } | null | undefined>>
  route: { query: LocationQuery }
  session: { capture: () => AccountTicket; isCurrent: (ticket: AccountTicket) => boolean }
  subTab: Ref<string>
  activeTabs: ComputedRef<readonly { id: string }[]>
  refreshTasks: () => Promise<unknown>
  initForAccount: (ticket: AccountTicket) => Promise<unknown>
  restoreWorkbenchStateFromUrl: (query: Record<string, LocationQueryValue | LocationQueryValue[]>) => Promise<unknown>
  resetSession: () => void
  resetEngagements: () => void
  resetTaskHall: () => void
  resetMyTasks: () => void
  resetTaskDrafts: () => void
  resetDisputes: () => void
}): void {
  const {
    notice, side, currentUser, route, session,
    subTab, activeTabs, refreshTasks, initForAccount, restoreWorkbenchStateFromUrl,
    resetSession, resetEngagements, resetTaskHall, resetMyTasks, resetTaskDrafts, resetDisputes,
  } = deps

  // 切换身份后当前页签可能不存在于另一侧的列表：回落到该侧首页签。
  // immediate：工作台常在登录完成**之后**才挂载（side 已定型，无翻转事件可听）。
  watch(activeTabs, (tabs) => {
    if (!tabs.some((tab) => tab.id === subTab.value)) subTab.value = tabs[0].id
  }, { immediate: true })

  // ---------- 账号级编排：重置 + 初始化 ----------

  /**
   * 清空全部账号相关状态——否则上一个账号的组织/余额/任务会留在界面上。
   */
  function resetAccountState(): void {
    notice.value = ''
    resetSession()
    resetEngagements()
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
    initializingAccount = false
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
        if (session.isCurrent(ticket)) initializingAccount = false
      }
      // 初始化期间可能又换了账号——旧账号的 URL 恢复直接放弃，避免上一个链接串数据。
      // 按 accountId+epoch 验票（任务书 #84）：A→B→A 时第一轮 A 与第三轮 A 同 id 不同票，
      // 只比 id 会让第一轮 A 的入口 query 污染第三轮现场。
      if (session.isCurrent(ticket)) await restoreWorkbenchStateFromUrl(entryQuery)
    }
  }, { immediate: true })
}
