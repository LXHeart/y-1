import { computed, watch, type ComputedRef, type Ref } from 'vue'
import type { LocationQueryRaw, LocationQueryValue, Router } from 'vue-router'
import { useAccountSessionStore } from '../../../stores/account-session'

/**
 * 工作台 URL 状态双向同步（任务书 #91 W1 自 GrasslandWorkbench.vue 整段迁入，
 * #81 C81-02 的既定设计；纯搬运，注释与实现原样保留）。
 *
 * 状态 → URL：urlQuerySnapshot 快照 + router.replace watch（工厂内注册，
 * 与迁出前在 SFC 中的注册时序一致）；URL → 状态：restoreWorkbenchStateFromUrl。
 * 恢复必须在 initForAccount 之后：它按已开通身份重排 side，先恢复会被覆盖。
 */
export function useWorkbenchUrlState<TSubTab extends string>(deps: {
  router: Router
  side: Readonly<Ref<'merchant' | 'recommender'>>
  subTab: Ref<TSubTab>
  activeTabs: ComputedRef<readonly { id: string; label: string }[]>
  selectedTaskId: Readonly<Ref<string>>
  levelFilter: Ref<string>
  rateFilterPct: Ref<number>
  feedFilters: Ref<{ q: string; platform: string; contentForm: string; minBountyYuan: number; maxDistanceKm: number }>
  feedItems: Readonly<Ref<readonly unknown[]>>
  loadFeed: (reset: boolean) => Promise<unknown>
  myTaskItems: Readonly<Ref<readonly unknown[]>>
  loadMyTasksPage: (reset: boolean) => Promise<unknown>
  selectTask: (taskId: string) => Promise<unknown>
  openTaskDetail: (taskId: string, options?: { from?: 'hall' | 'my-tasks' }) => void
  switchSide: (side: 'merchant' | 'recommender') => Promise<unknown>
  hasMerchantIdentity: Readonly<Ref<boolean>>
  hasRecommenderIdentity: Readonly<Ref<boolean>>
  personalSettingsOpen: Ref<boolean>
  personalSettingsSection: Ref<string>
}) {
  const {
    router, side, subTab, activeTabs, selectedTaskId,
    levelFilter, rateFilterPct, feedFilters, feedItems, loadFeed,
    myTaskItems, loadMyTasksPage, selectTask, openTaskDetail,
    switchSide, hasMerchantIdentity, hasRecommenderIdentity,
    personalSettingsOpen, personalSettingsSection,
  } = deps
  const session = useAccountSessionStore()
  let restoreSequence = 0

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
    const ticket = session.capture()
    const sequence = ++restoreSequence
    const current = () => session.isCurrent(ticket) && sequence === restoreSequence
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
      if (!current()) return
    }
    const wtabParam = firstQueryParam(query.wtab)
    if (wtabParam && activeTabs.value.some((tab) => tab.id === wtabParam)) {
      subTab.value = wtabParam as TSubTab
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
      if (!current()) return
    }
    // 我的任务同坑（#77 卡 D 落地于 CI 红灯期未被 e2e 验证，2026-09-06 实测 tab 点击
    // 不触发加载、side 恒为 recommender 时首屏永远空态）——与 feed 同款补拉。
    // 非阻塞：列表不挡初始化链与首帧（CI 弱机上串行 await 会把工作台渲染顶出断言窗口）。
    if (side.value === 'recommender' && myTaskItems.value.length === 0) {
      void loadMyTasksPage(true)
    }
    const taskParam = firstQueryParam(query.task)
    if (taskParam) {
      // #77 卡 A：推荐官侧 ?task= 深链 = 打开详情弹窗（轻量设选中 id，详情弹窗自取）；
      // 商家侧维持 selectTask（任务与报名列表的行内展开依赖选中任务的报名全量）。
      if (side.value === 'merchant') await selectTask(taskParam)
      else openTaskDetail(taskParam)
    }
  }

  return { restoreWorkbenchStateFromUrl }
}
