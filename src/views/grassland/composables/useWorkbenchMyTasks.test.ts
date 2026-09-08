// @vitest-environment happy-dom
import { createPinia, setActivePinia } from 'pinia'
import { effectScope, ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { useWorkbenchMyTasks } from './useWorkbenchMyTasks'
import { useAuthStore } from '../../../stores/auth'
import type { useGrassland } from '../../../composables/useGrassland'
import type { MyApplication } from '../../../types/grassland'

describe('下一步待办分组', () => {
  it('同为 accepted 的报名按服务端动作分组，缺失契约不自行推断', async () => {
    setActivePinia(createPinia())
    useAuthStore().currentUser = { id: 'rec', email: 'rec@example.test', role: 'user', displayName: '推荐官' }
    const items = ['a', 'b', 'c'].map((applicationId) => ({ applicationId, applicationStatus: 'accepted' })) as MyApplication[]
    const labels: Record<string, string> = { a: '待修改', b: '观察期' }
    const grassland = {
      listMyApplications: vi.fn(async () => ({ items, hasMore: false, nextCursor: null })),
      getApplicationSettlement: vi.fn(async (id: string) => labels[id] ? { applicationId: id, nextActionLabel: labels[id] } : null),
      clearError: vi.fn(),
    } as unknown as ReturnType<typeof useGrassland>
    const scope = effectScope()
    const tasks = scope.run(() => useWorkbenchMyTasks(grassland, ref('recommender')))!
    await tasks.load()
    expect(tasks.groupedItems.value.map((group) => group.label)).toEqual(['待修改', '观察期', '查看状态'])
    tasks.reset()
    expect(tasks.groupedItems.value).toEqual([])
    expect(tasks.settlements.value).toEqual({})
    scope.stop()
  })
})
