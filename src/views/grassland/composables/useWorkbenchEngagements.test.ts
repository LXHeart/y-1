// @vitest-environment happy-dom
import { describe, expect, test, vi } from 'vitest'
import { ref } from 'vue'
import { useWorkbenchEngagements } from './useWorkbenchEngagements'
import type { useGrassland } from '../../../composables/useGrassland'

/**
 * refreshTasks 的列表视角规则（问题一③）：
 * 发布表单的「资源范围」（selectedStoreId）是纯发布语义，不得隐式过滤任务列表——
 * owner/admin 全量视角不传 storeId（后端返回 org 级 + 全部门店）；
 * 仅店长 store-only 视图（activeOrgStoreOnlyView）锁本店。
 */
function harness(options: { storeOnlyView: boolean, selectedStoreId?: string }) {
  const listTasks = vi.fn(async () => [])
  const grassland = { listTasks } as unknown as ReturnType<typeof useGrassland>
  const side = ref<'merchant' | 'recommender'>('merchant')
  const activeOrgId = ref('org-1')
  const selectedStoreId = ref(options.selectedStoreId ?? '')
  const activeOrgStoreOnlyView = ref(options.storeOnlyView)
  const feedItems = ref([])
  const refreshAccount = vi.fn(async () => {})
  const engagements = useWorkbenchEngagements(grassland, () => {}, {
    side, activeOrgId, selectedStoreId, activeOrgStoreOnlyView, feedItems, refreshAccount,
  })
  return { engagements, listTasks }
}

describe('refreshTasks 列表视角不被资源范围隐式过滤', () => {
  test('owner/admin 全量视角：资源范围选了门店也不传 storeId', async () => {
    const { engagements, listTasks } = harness({ storeOnlyView: false, selectedStoreId: 'store-1' })
    await engagements.refreshTasks()
    // 五态全取，且每次调用的 storeId 参数都是 undefined（不含资源范围）
    expect(listTasks).toHaveBeenCalledTimes(5)
    for (const call of listTasks.mock.calls) {
      expect(call[2]).toBeUndefined()
    }
  })

  test('store-only 视图：仅传自己负责的门店', async () => {
    const { engagements, listTasks } = harness({ storeOnlyView: true, selectedStoreId: 'store-1' })
    await engagements.refreshTasks()
    expect(listTasks).toHaveBeenCalledTimes(5)
    for (const call of listTasks.mock.calls) {
      expect(call[2]).toBe('store-1')
    }
  })
})
