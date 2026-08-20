import { ref, watch, type Ref } from 'vue'
import type { useGrassland } from '../../../composables/useGrassland'
import { yuanToCents } from '../../../lib/money'
import type { Task } from '../../../types/grassland'

/**
 * 工作台推荐官域：全局任务大厅 feed（GL-P1-TASK-001 Stage 2）。
 *
 * 从 GrasslandWorkbench.vue 原样迁出（行为不变）：游标分页、关键词/平台/形式/赏金/距离
 * 筛选、浏览器定位、报名附言。切到推荐官视角时按需加载首页（仅首次，避免来回切换重拉）。
 */
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
      q: feedFilters.value.q.trim() || undefined,
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

  function handleFeedFilterUpdate(field: string, value: string | number): void {
    ;(feedFilters.value as Record<string, string | number>)[field] = value
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
  }

  return {
    applyNote, feedItems, feedHasMore, feedLoading, feedFilters, locating,
    apply, loadFeed, useCurrentLocation, handleFeedFilterUpdate, reset,
  }
}
