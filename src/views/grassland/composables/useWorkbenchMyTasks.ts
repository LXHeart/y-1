import { computed, ref, watch, type Ref } from 'vue'
import type { useGrassland } from '../../../composables/useGrassland'
import type { ApplicationSettlement, MyApplication } from '../../../types/grassland'
import { useAccountSessionStore } from '../../../stores/account-session'

/**
 * 工作台「我的任务」域（任务书 #77 卡 D）：my-applications 跨任务全量主列表。
 *
 * 由「我的履约与争议」页签改造而来：原报名表绑定大厅选中任务（selectedTaskId，空态提示
 * 「选择任务后可见相关报名」），改造为全量列表 + 四态筛选 + keyset 分页。
 * 筛选口径（定死）：「完成」= settledAt 非空（settling 在途不算完成、算「报名成功」）；
 * rejected/withdrawn/refunded 终态仅在「全部」出现。
 * 分页交互照大厅先例（useWorkbenchTaskHall）：每页条数 10/20/50、游标链翻页。
 */

/** 每页条数档位（后端 my-applications limit 上限 50，与大厅 feed 同档位）。 */
export const MY_TASK_LIMIT_OPTIONS: readonly number[] = [10, 20, 50]

export type MyTaskFilterId = 'all' | 'pending' | 'accepted' | 'settled'

/** 四态筛选 → 后端查询参数（status 支持逗号多值 / settled 布尔，#77 卡 D 后端扩展）。 */
export const MY_TASK_FILTERS: readonly { id: MyTaskFilterId; label: string; status?: string; settled?: boolean }[] = [
  { id: 'all', label: '全部' },
  { id: 'pending', label: '待处理', status: 'pending,reconsent,reserving' },
  { id: 'accepted', label: '报名成功', status: 'accepted', settled: false },
  { id: 'settled', label: '完成', settled: true },
]

/**
 * 报名状态徽标（列表口径；与 TaskDetailCard 的 APPLICATION_STATUS_LABELS 同表）。
 * 已结算行由模板改判「已完成」（settledAt 非空 = 完成，卡 D 口径）。
 */
export const APPLICATION_STATUS_BADGES: Readonly<Record<string, { label: string; cls: string }>> = {
  pending: { label: '待处理', cls: 'badge-info' },
  reconsent: { label: '待确认新条款', cls: 'badge-info' },
  cancelled: { label: '任务已取消', cls: 'badge-neutral' },
  reserving: { label: '预留中', cls: 'badge-info' },
  accepted: { label: '履约中', cls: 'badge-success' },
  rejected: { label: '曾报名 · 未通过', cls: 'badge-neutral' },
  withdrawn: { label: '曾报名 · 已撤销', cls: 'badge-neutral' },
  refunded: { label: '任务已取消（已退款）', cls: 'badge-neutral' },
}

export function useWorkbenchMyTasks(
  grassland: ReturnType<typeof useGrassland>,
  side: Ref<'merchant' | 'recommender'>,
) {
  const session = useAccountSessionStore()
  const items = ref<MyApplication[]>([])
  const settlements = ref<Record<string, ApplicationSettlement>>({})
  const loading = ref(false)
  const filter = ref<MyTaskFilterId>('all')
  const limit = ref(10)
  const page = ref(0)
  const hasMore = ref(false)
  /** 游标链翻页：cursorHistory[N] 即第 N 页（0 起）的起始游标，首页为 ''（同大厅 feed）。 */
  const cursorHistory = ref<string[]>([''])
  /** 请求序号：筛选/翻页并发时丢弃过期响应，防串页。 */
  let requestSeq = 0

  async function load(reset = false): Promise<void> {
    const ticket = session.capture()
    const seq = ++requestSeq
    loading.value = true
    if (reset) {
      cursorHistory.value = ['']
      page.value = 0
    }
    const target = MY_TASK_FILTERS.find((f) => f.id === filter.value)
    const cursor = reset ? undefined : cursorHistory.value[page.value] || undefined
    const result = await grassland.listMyApplications(
      target?.status,
      cursor,
      limit.value,
      target?.settled,
    )
    if (!session.isCurrent(ticket) || seq !== requestSeq) return
    if (!result || !Array.isArray(result.items)) { loading.value = false; return }
    items.value = result.items
    hasMore.value = result.hasMore
    // cursorHistory[N+1] = 第 N 页返回的下一页游标；截断重写保证同页刷新（如撤销后 reload）幂等
    if (result.nextCursor) {
      cursorHistory.value = [...cursorHistory.value.slice(0, page.value + 1), result.nextCursor]
    }
    grassland.clearError()
    settlements.value = {}
    const states = await Promise.all(result.items.filter((item) => item.applicationStatus === 'accepted' && !item.commercePackageId)
      .map((item) => grassland.getApplicationSettlement(item.applicationId)))
    if (!session.isCurrent(ticket) || seq !== requestSeq) return
    settlements.value = Object.fromEntries(states.filter((state) => state).map((state) => [state!.applicationId, state!]))
    loading.value = false
  }

  function nextActionLabel(item: MyApplication): string {
    if (item.settledAt) return '完成'
    if (item.applicationStatus === 'reconsent') return '确认条款'
    if (item.applicationStatus === 'pending' || item.applicationStatus === 'reserving') return '等待商家'
    if (item.applicationStatus !== 'accepted') return '已结束'
    if (item.commercePackageId) return '推广套餐'
    const state = settlements.value[item.applicationId]
    if (!state) return '查看状态'
    if (state.settlementStatus === 'held') return '处理争议'
    if (state.settlementStatus === 'settled') return '完成'
    return state.confirmedAt ? '等待到账' : '提交履约'
  }
  const groupedItems = computed(() => ['确认条款', '提交履约', '推广套餐', '处理争议', '等待到账', '等待商家', '完成', '已结束', '查看状态']
    .map((label) => ({ label, items: items.value.filter((item) => nextActionLabel(item) === label) }))
    .filter((group) => group.items.length))

  async function setFilter(id: MyTaskFilterId): Promise<void> {
    if (filter.value === id) return
    filter.value = id
    await load(true)
  }

  /** 改每页条数：回首页重拉（游标链作废，同大厅语义）。 */
  async function setLimit(next: number): Promise<void> {
    if (!MY_TASK_LIMIT_OPTIONS.includes(next) || next === limit.value) return
    limit.value = next
    await load(true)
  }

  async function loadPrev(): Promise<void> {
    if (loading.value || page.value === 0) return
    page.value -= 1
    await load(false)
  }

  async function loadNext(): Promise<void> {
    if (loading.value || !hasMore.value) return
    page.value += 1
    await load(false)
  }

  // 切到推荐官视角时按需加载首页（仅首次，同大厅——避免来回切换重拉）
  watch(side, async (s) => {
    if (s === 'recommender' && items.value.length === 0) await load(true)
  })

  /** 账号切换清空（筛选/档位保留，与大厅 reset 口径一致）。 */
  function reset(): void {
    requestSeq += 1
    loading.value = false
    settlements.value = {}
    items.value = []
    hasMore.value = false
    page.value = 0
    cursorHistory.value = ['']
  }

  return {
    items, loading, filter, limit, page, hasMore, groupedItems, settlements, nextActionLabel,
    load, setFilter, setLimit, loadPrev, loadNext, reset,
  }
}
