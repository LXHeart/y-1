// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { effectScope, ref, type EffectScope } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { useAuthStore } from '../../../stores/auth'
import { useGrassland } from '../../../composables/useGrassland'
import type { ApplicationPage, ApplicationSettlement, Task, TaskApplication, Wallet } from '../../../types/grassland'
import { useWorkbenchMyTasks } from './useWorkbenchMyTasks'
import { useWorkbenchEngagements } from './useWorkbenchEngagements'
import { useWorkbenchApplicationDetail } from './useWorkbenchApplicationDetail'
import { useWorkbenchWallet } from './useWorkbenchWallet'

const scopes: EffectScope[] = []
const account = (id: string) => ({ id, email: `${id}@test.local`, displayName: id, role: 'user' as const })
function scoped<T>(factory: () => T): T {
  const scope = effectScope()
  scopes.push(scope)
  return scope.run(factory)!
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (cause: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}
const app = (id: string, taskId = 'task-1', status = 'accepted') => ({
  id, taskId, status, recommenderAccountId: 'rec-1', createdAt: null,
} as TaskApplication)
const page = (items: TaskApplication[], nextCursor: string | null = null): ApplicationPage => ({ items, nextCursor, hasMore: !!nextCursor })
const state = (id: string, overrides: Partial<ApplicationSettlement> = {}): ApplicationSettlement => ({
  applicationId: id, taskId: 'task-1', confirmedAt: null, settlementEligibleAt: null,
  settlementStatus: 'not_confirmed', holdReason: null, allowedActions: ['confirm_after_submission'], ...overrides,
})

beforeEach(() => { setActivePinia(createPinia()); useAuthStore().currentUser = account('a') })
afterEach(() => { scopes.splice(0).forEach((scope) => scope.stop()); vi.unstubAllGlobals() })

function api(overrides: Record<string, unknown> = {}) {
  return {
    loading: ref(false), error: ref(''), clearError: vi.fn(), listTasks: vi.fn(async () => []),
    getReputation: vi.fn(async () => null), getRecommenderProfile: vi.fn(async () => null),
    listApplicationsPage: vi.fn(async () => page([])),
    getApplicationSettlement: vi.fn(async (id: string) => state(id)),
    ...overrides,
  } as unknown as ReturnType<typeof useGrassland>
}
function engagements(grassland: ReturnType<typeof useGrassland>) {
  const refreshAccount = vi.fn(async () => {})
  const domain = scoped(() => useWorkbenchEngagements(grassland, vi.fn(), {
    side: ref('merchant'), activeOrgId: ref('org-1'), selectedStoreId: ref(''), activeOrgStoreOnlyView: ref(false),
    feedItems: ref([]), refreshAccount,
  }))
  return { domain, refreshAccount }
}

describe('C90-06 工作台恢复与异步隔离', () => {
  test('A -> B -> A 的旧列表不能覆盖新页或清除新加载态', async () => {
    const old = deferred<unknown>()
    const fresh = deferred<unknown>()
    const listMyApplications = vi.fn().mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise)
    const domain = scoped(() => useWorkbenchMyTasks(api({ listMyApplications }), ref('merchant')))
    const first = domain.load(true)
    useAuthStore().currentUser = account('b')
    useAuthStore().currentUser = account('a')
    domain.reset()
    const latest = domain.load(true)
    old.resolve({ items: [{ applicationId: 'old' }], hasMore: false, nextCursor: null })
    await first
    expect(domain.items.value).toEqual([])
    expect(domain.loading.value).toBe(true)
    fresh.resolve({ items: [{ applicationId: 'new' }], hasMore: false, nextCursor: null })
    await latest
    expect(domain.items.value[0].applicationId).toBe('new')
    expect(domain.loading.value).toBe(false)
  })

  test('筛选变化可以替换在途请求，旧筛选响应被丢弃', async () => {
    const old = deferred<unknown>()
    const listMyApplications = vi.fn().mockReturnValueOnce(old.promise)
      .mockResolvedValue({ items: [{ applicationId: 'pending' }], hasMore: false, nextCursor: null })
    const domain = scoped(() => useWorkbenchMyTasks(api({ listMyApplications }), ref('merchant')))
    const first = domain.load(true)
    await domain.setFilter('pending')
    old.resolve({ items: [{ applicationId: 'all' }], hasMore: false, nextCursor: null })
    await first
    expect(domain.items.value[0].applicationId).toBe('pending')
  })

  test('快速切换任务后旧报名不写入、不续拉画像或结算', async () => {
    const old = deferred<ApplicationPage>()
    const grassland = api({ listApplicationsPage: vi.fn().mockReturnValueOnce(old.promise).mockResolvedValue(page([app('new', 'task-2')])) })
    const { domain } = engagements(grassland)
    const first = domain.selectTask('task-1')
    await domain.selectTask('task-2')
    old.resolve(page([app('old')]))
    await first
    expect(domain.applications.value.map((item) => item.id)).toEqual(['new'])
    expect(grassland.getApplicationSettlement).toHaveBeenCalledTimes(1)
    expect(grassland.getApplicationSettlement).toHaveBeenCalledWith('new')
  })

  test('报名第 201 条可通过服务端游标到达，切页清空批量选择', async () => {
    const listApplicationsPage = vi.fn(async (_taskId: string, cursor?: string) => {
      const offset = Number(cursor ?? '0')
      return page(Array.from({ length: offset === 200 ? 1 : 20 }, (_, i) => app(`app-${offset + i + 1}`, 'task-1', 'pending')),
        offset < 200 ? String(offset + 20) : null)
    })
    const { domain } = engagements(api({ listApplicationsPage }))
    await domain.selectTask('task-1')
    domain.toggleSelectAll()
    expect(domain.selectedAppIds.value.size).toBe(20)
    for (let i = 1; i <= 10; i++) await domain.loadApplicationPage(i)
    expect(domain.applications.value[0].id).toBe('app-201')
    expect(domain.selectedAppIds.value.size).toBe(0)
    expect(domain.applicationsHasMore.value).toBe(false)
    expect(listApplicationsPage).toHaveBeenLastCalledWith('task-1', '200', 20)
  })

  test('确认 T+2 履约只回读持久状态，重新选中仍能评分，不轮询到超时', async () => {
    const contract = state('app-1', {
      confirmedAt: '2026-09-07T10:00:00Z', settlementEligibleAt: '2026-09-09T10:00:00Z',
      settlementStatus: 'settling', allowedActions: [],
    })
    const grassland = api({
      listApplicationsPage: vi.fn(async () => page([app('app-1')])),
      getApplicationSettlement: vi.fn(async () => contract), confirmEngagement: vi.fn(async () => true), pollSettlement: vi.fn(),
    })
    const { domain } = engagements(grassland)
    await domain.selectTask('task-1')
    await domain.confirm(app('app-1'))
    expect(grassland.pollSettlement).not.toHaveBeenCalled()
    expect(domain.settlementStatusLabel(app('app-1'))).toContain('预计')
    domain.clearSelectedTask()
    await domain.selectTask('task-1')
    expect(domain.confirmedAppIds.value.has('app-1')).toBe(true)
    expect(domain.canAct(app('app-1'), 'confirm_after_submission')).toBe(false)
  })

  test('详情无历史缓存时按任务查询本人报名；有报名 ID 时直接读取', async () => {
    const grassland = api({
      listApplicationsPage: vi.fn(async () => page([app('app-151')])),
      getApplication: vi.fn(async () => app('app-201')),
      listMyApplications: vi.fn(),
    })
    const task = { id: 'task-1', title: '很早的任务', status: 'published' } as Task
    const detail = scoped(() => useWorkbenchApplicationDetail(grassland, { taskId: task.id, task, myApplication: null }))
    await flushPromises()
    expect(detail.application.value?.applicationId).toBe('app-151')
    expect(grassland.listMyApplications).not.toHaveBeenCalled()
    const own = scoped(() => useWorkbenchApplicationDetail(grassland, {
      taskId: task.id, task, myApplication: { applicationId: 'app-201', taskId: task.id } as never,
    }))
    await flushPromises()
    expect(own.application.value?.applicationId).toBe('app-201')
    expect(grassland.getApplication).toHaveBeenCalledWith('task-1', 'app-201')
  })

  test('旧账号的 HTTP 失败不污染新账号错误或结束新请求的 loading', async () => {
    const old = deferred<Response>()
    const fresh = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn().mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise))
    const grassland = scoped(useGrassland)
    const first = grassland.getMyWallet()
    useAuthStore().currentUser = account('b')
    useAuthStore().currentUser = account('a')
    const latest = grassland.getMyWallet()
    old.reject(new Error('old account error'))
    await first
    expect(grassland.error.value).toBe('')
    expect(grassland.loading.value).toBe(true)
    fresh.resolve(new Response(JSON.stringify({ success: true, data: { balanceCents: 123 } })))
    await latest
    expect(grassland.loading.value).toBe(false)
  })

  test('资金分组只使用完整托管头寸，争议押金不被重复统计', async () => {
    const wallet: Wallet = { accountId: 'a', balanceCents: 500, updatedAt: null, entries: [], withdrawingCents: 0,
      positions: [
        { engagementRef: 'deposit', kind: 'deposit', amountCents: 200 },
        { engagementRef: 'pending', kind: 'bounty', amountCents: 300 },
        { engagementRef: 'held', kind: 'deposit', amountCents: 400 },
      ] }
    const grassland = api({ getMyWallet: vi.fn(async () => wallet),
      getApplicationSettlement: vi.fn(async (id) => state(id, { settlementStatus: id === 'held' ? 'held' : 'settling' })) })
    const domain = scoped(() => useWorkbenchWallet(grassland))
    await flushPromises()
    expect(domain.groups.value).toEqual({ deposit: 200, pending: 300, held: 400 })
    expect(Object.values(domain.groups.value!).reduce((a, b) => a + b, domain.wallet.value!.balanceCents)).toBe(1400)
  })

  test('提现回包丢失后同一操作号与金额重试，输入变化不产生第二笔扣款', async () => {
    const wallet = { accountId: 'a', balanceCents: 10000, updatedAt: null, entries: [], positions: [] }
    const withdrawFromWallet = vi.fn().mockResolvedValueOnce(null).mockResolvedValueOnce({ ...wallet, balanceCents: 8000 })
    const domain = scoped(() => useWorkbenchWallet(api({ getMyWallet: vi.fn(async () => wallet), withdrawFromWallet })))
    await flushPromises()
    domain.withdrawYuan.value = 20
    await domain.withdraw()
    domain.withdrawYuan.value = 50
    await domain.withdraw()
    expect(withdrawFromWallet).toHaveBeenCalledTimes(2)
    expect(withdrawFromWallet.mock.calls[0]).toEqual(withdrawFromWallet.mock.calls[1])
    expect(withdrawFromWallet.mock.calls[1][0]).toBe(2000)
    expect(domain.wallet.value?.balanceCents).toBe(8000)
  })
})
