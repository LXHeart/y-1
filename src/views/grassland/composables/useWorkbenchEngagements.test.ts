// @vitest-environment happy-dom
import { describe, expect, test, vi } from 'vitest'
import { ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkbenchEngagements } from './useWorkbenchEngagements'
import type { useGrassland } from '../../../composables/useGrassland'
import { useAuthStore } from '../../../stores/auth'
import { useAccountSessionStore } from '../../../stores/account-session'
import type { AuthUser } from '../../../types/auth'
import type { Task } from '../../../types/grassland'

/**
 * refreshTasks 的列表视角规则（问题一③）：
 * 发布表单的「资源范围」（selectedStoreId）是纯发布语义，不得隐式过滤任务列表——
 * owner/admin 全量视角不传 storeId（后端返回 org 级 + 全部门店）；
 * 仅店长 store-only 视图（activeOrgStoreOnlyView）锁本店。
 */
function harness(options: { storeOnlyView: boolean, selectedStoreId?: string }) {
  // 任务书 #84：refreshTasks 验账号票需要 account-session store（Pinia 必须先就位）
  setActivePinia(createPinia())
  const auth = useAuthStore()
  useAccountSessionStore()
  auth.currentUser = { id: 'merch-1', email: 'm@qa.invalid', displayName: '商家', role: 'user' }
  // 形参照真实签名 listTasks(organizationId, status, storeId?) 写全：
  // 零参 mock 会被推断成空 tuple，下面断言 call[2] 时 vue-tsc 报 TS2493。
  const listTasks = vi.fn(async (_organizationId: string, _status?: string, _storeId?: string) => [])
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

/**
 * 任务书 #84 C84-02（D84-04/RULE-84-03）：任务五态并发回包后、写 tasks 前验账号票。
 * TC-84-005/007——旧账号的迟到任务不得覆盖当前（reset 后）列表。
 */
const MERCHANT_USER: AuthUser = { id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', email: 'c@qa.invalid', displayName: '丙', role: 'user' }
const OTHER_USER: AuthUser = { id: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd', email: 'd@qa.invalid', displayName: '丁', role: 'user' }

function deferredTasks<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => { resolve = res })
  return { promise, resolve }
}

function makeTask(id: string, status: Task['status']): Task {
  return {
    id, ownerAccountId: MERCHANT_USER.id, organizationId: 'org-1',
    title: `任务${id}`, description: null, status, contentForm: null, platform: null,
    maxSlots: null, bountyCents: null, freebieDepositCents: null, minRecommenderLevel: 0,
    createdAt: null, version: 1, applicationDeadline: null, publishedAt: null, cancelledAt: null,
    autoAcceptMinLevel: null,
    requirements: {
      mustInclude: [], forbiddenContent: [], metricRequirements: [], evidenceRequirements: [],
    },
  }
}

/** 五态请求全部可独立挂起/释放的夹具（per-status deferred）。 */
function ticketHarness() {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  useAccountSessionStore()
  auth.currentUser = MERCHANT_USER
  const perStatus = new Map<string, ReturnType<typeof deferredTasks<Task[] | null>>>()
  const clearError = vi.fn()
  const listTasks = vi.fn((_organizationId: string, status?: string, _storeId?: string) => {
    const d = deferredTasks<Task[] | null>()
    perStatus.set(status ?? '', d)
    return d.promise
  })
  const grassland = { listTasks, clearError } as unknown as ReturnType<typeof useGrassland>
  const side = ref<'merchant' | 'recommender'>('merchant')
  const activeOrgId = ref('org-1')
  const selectedStoreId = ref('')
  const activeOrgStoreOnlyView = ref(false)
  const feedItems = ref([])
  const refreshAccount = vi.fn(async () => {})
  const engagements = useWorkbenchEngagements(grassland, () => {}, {
    side, activeOrgId, selectedStoreId, activeOrgStoreOnlyView, feedItems, refreshAccount,
  })
  return { auth, session: useAccountSessionStore(), engagements, listTasks, clearError, perStatus }
}

describe('任务书 #84 C84-02：任务列表提交前验账号票', () => {
  test('TC-84-005：五态挂起→切号+reset→全部释放：不写 tasks、不 clearError、不续发', async () => {
    const h = ticketHarness()
    const refreshing = h.engagements.refreshTasks()
    expect(h.listTasks).toHaveBeenCalledTimes(5) // 前置：五态并发已发出（挂起中）

    h.auth.currentUser = OTHER_USER // 切号：旧票失效
    h.engagements.reset() // 组件 resetAccountState 语义：任务域清空
    // 迟到回包：成功与 null 混合也必须整体不写
    h.perStatus.get('draft')!.resolve([makeTask('t-draft', 'draft')])
    h.perStatus.get('pending_review')!.resolve(null)
    h.perStatus.get('published')!.resolve([makeTask('t-pub', 'published')])
    h.perStatus.get('closed')!.resolve(null)
    h.perStatus.get('cancelled')!.resolve([makeTask('t-cancel', 'cancelled')])
    await refreshing

    expect(h.engagements.tasks.value).toEqual([]) // reset 后的空列表未被旧回包覆盖
    expect(h.clearError).not.toHaveBeenCalled() // 失效提交不触发错误清理
    expect(h.listTasks).toHaveBeenCalledTimes(5) // 未续发任何后续请求
  })

  test('TC-84-006 票据契约：A→B→A 旧票显式传入被拦；当前票无参调用照常写入', async () => {
    const h = ticketHarness()
    const firstRoundTicket = h.session.capture()
    h.auth.currentUser = OTHER_USER
    h.auth.currentUser = MERCHANT_USER // A→B→A：同 id 新 epoch
    expect(h.session.isCurrent(firstRoundTicket)).toBe(false)

    // 初始化链把第一轮的旧票显式传入（C84-01 贯穿契约）
    const stale = h.engagements.refreshTasks(firstRoundTicket)
    h.perStatus.forEach((d) => d.resolve([makeTask('stale', 'draft')]))
    await stale
    expect(h.engagements.tasks.value).toEqual([])

    // 对照：无参调用自行 capture 当前票，照常写入
    const current = h.engagements.refreshTasks()
    h.perStatus.forEach((d) => d.resolve([makeTask('now', 'draft')]))
    await current
    expect(h.engagements.tasks.value).toHaveLength(5)
    expect(h.engagements.tasks.value.every((task) => task.id === 'now')).toBe(true)
  })

  test('TC-84-007：当前账号完整合并按状态序；全 null 不改既有列表；空数组写入空', async () => {
    const h = ticketHarness()
    // 部分成功 + 部分 null：flatMap 顺序 = 五态声明顺序
    const r1 = h.engagements.refreshTasks()
    h.perStatus.get('draft')!.resolve([makeTask('d1', 'draft')])
    h.perStatus.get('pending_review')!.resolve(null)
    h.perStatus.get('published')!.resolve([makeTask('p1', 'published')])
    h.perStatus.get('closed')!.resolve([makeTask('c1', 'closed')])
    h.perStatus.get('cancelled')!.resolve(null)
    await r1
    expect(h.engagements.tasks.value.map((task) => task.id)).toEqual(['d1', 'p1', 'c1'])

    // 全 null（run 失败语义）：既有列表保持，不视为失败清空、不 clearError
    const r2 = h.engagements.refreshTasks()
    h.perStatus.forEach((d) => d.resolve(null))
    await r2
    expect(h.engagements.tasks.value.map((task) => task.id)).toEqual(['d1', 'p1', 'c1'])
    expect(h.clearError).not.toHaveBeenCalled()

    // 真实空列表（全部 []）：写入空态
    const r3 = h.engagements.refreshTasks()
    h.perStatus.forEach((d) => d.resolve([]))
    await r3
    expect(h.engagements.tasks.value).toEqual([])
  })
})
