import { ref, watch, type Ref } from 'vue'
import type { useGrassland } from '../../../composables/useGrassland'
import { yuanToCents } from '../../../lib/money'
import type { MyApplication, Task } from '../../../types/grassland'

/**
 * 工作台推荐官域：全局任务大厅 feed（GL-P1-TASK-001 Stage 2）。
 *
 * 关键词/平台/形式/赏金/距离筛选、浏览器定位、报名附言；切到推荐官视角时按需加载首页
 * （仅首次，避免来回切换重拉）。分页为页式（2026-09-04）：每页条数可选（10/20/50，
 * 默认 10）整页替换，游标链翻页（下一页消费 nextCursor、上一页用历史起始游标重拉，
 * keyset 分页不可随机跳页）。另维护「我的报名」映射（taskId → 最近一条报名），
 * 供大厅行与任务详情卡区分已报名/未报名。
 */

/** 每页条数档位（后端 feed limit 上限 50，两处档位须同步）。 */
export const FEED_LIMIT_OPTIONS: readonly number[] = [10, 20, 50]

export function useWorkbenchTaskHall(
  grassland: ReturnType<typeof useGrassland>,
  side: Ref<'merchant' | 'recommender'>,
  setNotice: (message: string) => void,
) {
  const applyNote = ref('')

  const feedItems = ref<Task[]>([])
  const feedCursor = ref('')
  const feedHasMore = ref(false)
  const feedLoading = ref(false)
  const feedFilters = ref({
    q: '', platform: '', contentForm: '', minBountyYuan: 0, maxDistanceKm: 0,
    latitude: null as number | null, longitude: null as number | null,
  })
  const locating = ref(false)

  /**
   * 游标链翻页（2026-09-04 分页需求）：feed 是 keyset 游标分页（不可随机跳页），
   * 用「每页起始游标历史」支持上一页——cursorHistory[N] 即第 N 页（0 起）的起始游标，
   * 首页为 ''。下一页消费当前 nextCursor；上一页用历史里前一页的起始游标重拉。
   */
  const feedPage = ref(0)
  const cursorHistory = ref<string[]>([''])

  /** 每页条数（默认 10；改档位回首页重拉——keyset 分页下不同页大小的游标不可复用）。 */
  const feedLimit = ref(10)

  async function apply(taskId: string): Promise<void> {
    const created = await grassland.applyToTask(taskId, applyNote.value.trim() || undefined)
    if (!created) return
    applyNote.value = ''
    setNotice('报名已提交，等待商家处理')
    await loadMyApplications()
  }

  async function requestFeedPage(cursor: string | undefined): Promise<void> {
    feedLoading.value = true
    const page = await grassland.listTaskFeed({
      q: feedFilters.value.q.trim() || undefined,
      platform: feedFilters.value.platform.trim() || undefined,
      contentForm: feedFilters.value.contentForm.trim() || undefined,
      minBountyCents: feedFilters.value.minBountyYuan > 0 ? yuanToCents(feedFilters.value.minBountyYuan) : undefined,
      latitude: feedFilters.value.maxDistanceKm > 0 ? feedFilters.value.latitude! : undefined,
      longitude: feedFilters.value.maxDistanceKm > 0 ? feedFilters.value.longitude! : undefined,
      maxDistanceKm: feedFilters.value.maxDistanceKm > 0 ? feedFilters.value.maxDistanceKm : undefined,
      cursor,
      limit: feedLimit.value,
    })
    feedLoading.value = false
    if (!page) return
    feedItems.value = page.items
    feedCursor.value = page.nextCursor || ''
    feedHasMore.value = page.hasMore
    grassland.clearError()
    await loadMyApplications()
  }

  /** 改每页条数：回首页重拉（游标链作废），失败时保留新档位待用户手点「查询」。 */
  async function setFeedLimit(limit: number): Promise<void> {
    if (!FEED_LIMIT_OPTIONS.includes(limit) || limit === feedLimit.value) return
    feedLimit.value = limit
    cursorHistory.value = ['']
    feedPage.value = 0
    await requestFeedPage(undefined)
  }

  /** 加载全局大厅 feed：reset=true 重新查首页（查询按钮/筛选刷新）；false=下一页。 */
  async function loadFeed(reset = false): Promise<void> {
    if (feedLoading.value) return
    if (feedFilters.value.maxDistanceKm > 0
        && (feedFilters.value.latitude == null || feedFilters.value.longitude == null)) {
      setNotice('请先允许获取当前位置，再使用距离筛选')
      return
    }
    if (reset) {
      cursorHistory.value = ['']
      feedPage.value = 0
      await requestFeedPage(undefined)
      return
    }
    if (!feedCursor.value) return
    cursorHistory.value.push(feedCursor.value)
    feedPage.value += 1
    await requestFeedPage(feedCursor.value)
  }

  /** 翻页：下一页已由 loadFeed(false) 承载；此处仅上一页（用历史起始游标重拉）。 */
  async function loadFeedPrev(): Promise<void> {
    if (feedLoading.value || feedPage.value === 0) return
    feedPage.value -= 1
    await requestFeedPage(cursorHistory.value[feedPage.value] || undefined)
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

  function handleFeedFilterUpdate(field: string, value: string | number): void {
    ;(feedFilters.value as Record<string, string | number>)[field] = value
  }

  // ---------- 我的报名映射（大厅行/详情卡的「已报名」标识） ----------

  /**
   * taskId → 最近一条报名。跨任务走 my-applications 游标翻页（每页 50、至多 3 页），
   * 后到的覆盖先到的即「最近一条」；普通用户报名量远小于该量级，截断只影响极端历史。
   */
  const myApplications = ref<Record<string, MyApplication>>({})

  async function loadMyApplications(): Promise<void> {
    const latest: Record<string, MyApplication> = {}
    let cursor: string | undefined
    for (let page = 0; page < 3; page += 1) {
      const result = await grassland.listMyApplications(undefined, cursor, 50)
      // 非分页对象（异常响应/旧网关）按「本页无数据」收场，不让标识位拖垮大厅主流程
      if (!result || !Array.isArray(result.items)) return
      for (const item of result.items) latest[item.taskId] = item
      if (!result.hasMore || !result.nextCursor) break
      cursor = result.nextCursor
    }
    myApplications.value = latest
  }

  function myApplicationFor(taskId: string): MyApplication | null {
    return myApplications.value[taskId] ?? null
  }

  /** 报名处于占用态（pending/reserving/accepted）——大厅行据此禁用重复报名。 */
  const ACTIVE_APPLICATION_STATUSES: ReadonlySet<string> = new Set(['pending', 'reserving', 'accepted'])

  function hasActiveApplication(taskId: string): boolean {
    const application = myApplications.value[taskId]
    return Boolean(application && ACTIVE_APPLICATION_STATUSES.has(application.applicationStatus))
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

  /** 账号切换清空（原 resetAccountState 的 feed 字段；刻意不清筛选/附言/定位态，与原实现一致）。 */
  function reset(): void {
    feedItems.value = []
    feedCursor.value = ''
    feedHasMore.value = false
    feedPage.value = 0
    cursorHistory.value = ['']
    myApplications.value = {}
  }

  return {
    applyNote, feedItems, feedHasMore, feedLoading, feedFilters, feedPage, feedLimit, locating,
    myApplications, hasActiveApplication,
    apply, loadFeed, loadFeedPrev, setFeedLimit, useCurrentLocation, handleFeedFilterUpdate,
    myApplicationFor, loadMyApplications, reset,
  }
}
