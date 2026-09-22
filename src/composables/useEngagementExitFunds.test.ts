// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { KeepAlive, defineComponent, h, nextTick, ref } from 'vue'
import { effectScope } from 'vue'
import { mount } from '@vue/test-utils'
import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { useEngagementExitFunds } from './useEngagementExitFunds'
import { useAuthStore } from '../stores/auth'
import type { EngagementExitFunds } from '../types/grassland/task'
import type { AuthUser } from '../types/auth'

/**
 * 任务书 #104 C104-05（§3 D04 / TC104-05-01～07）：资金轮询生命周期。
 * 真实 effectScope / KeepAlive 组件 / visibilitychange 驱动；假时钟推进 5s 轮询节拍。
 */

type FundsApi = ReturnType<typeof useEngagementExitFunds>
type Client = { fetchEngagementExitFunds: ReturnType<typeof vi.fn>; error: { value: string } }

function makeClient(): Client {
  return { fetchEngagementExitFunds: vi.fn(), error: { value: '' } }
}

function funds(state: EngagementExitFunds['state'], overrides: Partial<EngagementExitFunds> = {}): EngagementExitFunds {
  return {
    operationId: 'op-1',
    kind: 'no_fault',
    state,
    amounts: { deposit_refundCents: 100, bounty_releaseCents: 500 },
    blockedReason: state === 'needs_review' ? 'funds_reconciliation_required' : 'funds_pending',
    updatedAt: '2026-09-20T00:00:00Z',
    ...overrides,
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((settle) => { resolve = settle })
  return { promise, resolve }
}

async function flush(): Promise<void> {
  await Promise.resolve()
  await Promise.resolve()
  await nextTick()
}

function setHidden(hidden: boolean): void {
  Object.defineProperty(document, 'hidden', { value: hidden, configurable: true })
  document.dispatchEvent(new Event('visibilitychange'))
}

const userA: AuthUser = { id: 'user-a', email: 'a@qa.invalid', displayName: '甲', role: 'user' }

beforeEach(() => {
  vi.useFakeTimers()
  setHidden(false)
  // #106 D05：发起门闸要求有效非匿名账号票据——全部用例带登录态（无 Pinia 兼容路径单列用例）。
  setActivePinia(createPinia())
  useAuthStore().currentUser = userA
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('TC104-05-01 · dispose 迟到回包', () => {
  it('scope.stop 后回包落地与 15s 推进：timer=0、无追加请求、无状态回写、loading=false', async () => {
    const client = makeClient()
    const first = deferred<EngagementExitFunds | null>()
    client.fetchEngagementExitFunds.mockImplementation(() => first.promise)

    const scope = effectScope()
    let api!: FundsApi
    scope.run(() => { api = useEngagementExitFunds(client as never) })
    api.target('task-1', 'app-1')
    expect(api.loading.value).toBe(true)
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(1)

    scope.stop()
    expect(api.loading.value).toBe(false)

    first.resolve(funds('pending'))
    await vi.advanceTimersByTimeAsync(15_000)
    expect(api.funds.value).toBeNull()
    expect(api.error.value).toBe('')
    expect(api.loading.value).toBe(false)
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)
  })
})

describe('TC104-05-02 · hidden 与 KeepAlive 正交', () => {
  function mountKeepAlive(client: Client): { api: FundsApi; show: { value: boolean } } {
    let api!: FundsApi
    const Inner = defineComponent({
      setup() {
        api = useEngagementExitFunds(client as never)
        return () => null
      },
    })
    const show = ref(true)
    mount(defineComponent({
      setup: () => () => h(KeepAlive, () => (show.value ? h(Inner) : null)),
    }))
    return { api, show }
  }

  it('隐藏与失活都不轮询；显示仍失活不请求；两者都恢复才立即一次请求', async () => {
    const client = makeClient()
    const first = deferred<EngagementExitFunds | null>()
    client.fetchEngagementExitFunds.mockImplementation(() => first.promise)
    const { api, show } = mountKeepAlive(client)

    api.target('task-1', 'app-1')
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(1)

    // 请求中失活（KeepAlive）→ 隐藏（visibility）→ 迟到的 pending 回包不回写、不排轮询。
    show.value = false
    await nextTick()
    setHidden(true)
    first.resolve(funds('pending'))
    await vi.advanceTimersByTimeAsync(15_000)
    expect(api.funds.value).toBeNull()
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)

    // 恢复显示但组件仍 deactivated：不得请求（显示页面不能激活失活组件）。
    setHidden(false)
    await vi.advanceTimersByTimeAsync(100)
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(1)

    // 真正激活（KeepAlive re-activate）：立即一次请求（非等 5s）。
    show.value = true
    await nextTick()
    await vi.advanceTimersByTimeAsync(0)
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(2)
    expect(client.fetchEngagementExitFunds).toHaveBeenLastCalledWith('task-1', 'app-1', expect.anything())
  })
})

describe('TC104-05-03 · 目标与账号竞争', () => {
  const userA: AuthUser = { id: 'user-a', email: 'a@qa.invalid', displayName: '甲', role: 'user' }
  const userB: AuthUser = { id: 'user-b', email: 'b@qa.invalid', displayName: '乙', role: 'user' }

  it('旧目标迟到回包不写新目标；A→B→A 只最新票据生效；空 target 清资金/错误/loading', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.currentUser = userA

    const client = makeClient()
    const old = deferred<EngagementExitFunds | null>()
    const fresh = deferred<EngagementExitFunds | null>()
    client.fetchEngagementExitFunds.mockImplementationOnce(() => old.promise)
      .mockImplementationOnce(() => fresh.promise)

    const scope = effectScope()
    let api!: FundsApi
    scope.run(() => { api = useEngagementExitFunds(client as never) })
    api.target('task-1', 'app-1')
    // 换目标：旧请求作废（tuple 捕获，不拼分隔符）。
    api.target('task-2', 'app-2')
    old.resolve(funds('pending'))
    await flush()
    expect(api.funds.value).toBeNull()
    expect(client.fetchEngagementExitFunds).toHaveBeenNthCalledWith(2, 'task-2', 'app-2', expect.anything())

    // 新目标回包正常落地，再换号：资金快照（旧账号的）清空。
    fresh.resolve(funds('processing'))
    await flush()
    expect(api.funds.value?.state).toBe('processing')
    auth.currentUser = userB
    expect(api.funds.value).toBeNull()
    expect(api.loading.value).toBe(false)

    // A 票据发起 → 换 B 作废在途 → 迟到回包被票据/代次闸拒绝。
    const forA2 = deferred<EngagementExitFunds | null>()
    client.fetchEngagementExitFunds.mockImplementationOnce(() => forA2.promise)
    auth.currentUser = userA
    api.target('task-2b', 'app-2b')
    auth.currentUser = userB
    forA2.resolve(funds('needs_review'))
    await flush()
    expect(api.funds.value).toBeNull()

    // 回到 A：新票据新目标查询正常（A→B→A 不复活旧票）。
    auth.currentUser = userA
    client.fetchEngagementExitFunds.mockResolvedValueOnce(funds('succeeded'))
    api.target('task-3', 'app-3')
    await flush()
    expect(api.funds.value?.state).toBe('succeeded')

    // 空 target：资金/错误/loading 全清并停止一切。
    api.target(null, undefined)
    expect(api.funds.value).toBeNull()
    expect(api.error.value).toBe('')
    expect(api.loading.value).toBe(false)
    expect(vi.getTimerCount()).toBe(0)
    scope.stop()
    disposePinia(pinia)
    // 复位活动实例：后续无需 pinia 的用例不再命中已销毁实例（getActivePinia 仍指向它）。
    setActivePinia(createPinia())
  })
})

describe('TC104-05-04 · 慢请求去重', () => {
  it('在途只 1 次请求；完成后 4999ms 不发、5000ms 才下一次（无 interval 堆积）', async () => {
    const client = makeClient()
    client.fetchEngagementExitFunds.mockImplementation(async () => {
      await new Promise((resolve) => { setTimeout(resolve, 12_000) })
      return funds('pending')
    })
    const scope = effectScope()
    let api!: FundsApi
    scope.run(() => { api = useEngagementExitFunds(client as never) })
    api.target('task-1', 'app-1')
    void api.refresh()  // 在途去重：同目标三次 refresh 共享同一 Promise
    void api.refresh()
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(12_000)
    expect(api.funds.value?.state).toBe('pending')
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(4_999)
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(2)
    expect(vi.getTimerCount()).toBe(1)  // 单次 timeout，非 interval 堆积
    scope.stop()
  })
})

describe('TC104-05-05 · 终态/空态', () => {
  it.each([
    ['pending', true], ['processing', true], ['retry_wait', true],
    ['succeeded', false], ['needs_review', false],
  ] as const)('state=%s → %s', async (state, shouldContinue) => {
    const client = makeClient()
    client.fetchEngagementExitFunds.mockResolvedValueOnce(funds(state))
      .mockResolvedValue(funds(state))
    const scope = effectScope()
    let api!: FundsApi
    scope.run(() => { api = useEngagementExitFunds(client as never) })
    api.target('task-1', 'app-1')
    await flush()
    expect(api.funds.value?.state).toBe(state)
    await vi.advanceTimersByTimeAsync(5_000)
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(shouldContinue ? 2 : 1)
    scope.stop()
  })

  it('响应无 operation（state=null）：不推断成功、不轮询、error 保持空', async () => {
    const client = makeClient()
    client.fetchEngagementExitFunds.mockResolvedValueOnce({ operationId: null, state: null } as never)
    const scope = effectScope()
    let api!: FundsApi
    scope.run(() => { api = useEngagementExitFunds(client as never) })
    api.target('task-1', 'app-1')
    await flush()
    expect(api.funds.value).toBeNull()
    expect(api.error.value).toBe('')
    expect(vi.getTimerCount()).toBe(0)
    scope.stop()
  })
})

describe('TC104-05-06 · 失败/取消/恢复', () => {
  it('失败保留快照并停止自动轮询；手动重试成功恢复；abort 不污染共享 error', async () => {
    const client = makeClient()
    client.fetchEngagementExitFunds.mockResolvedValueOnce(funds('pending'))
    const scope = effectScope()
    let api!: FundsApi
    scope.run(() => { api = useEngagementExitFunds(client as never) })
    api.target('task-1', 'app-1')
    await flush()
    expect(api.funds.value?.state).toBe('pending')

    // 轮询失败（run 返回 null + client.error）：保留快照、显示错误、停止自动轮询。
    client.fetchEngagementExitFunds.mockResolvedValueOnce(null)
    client.error.value = '网络错误'
    await vi.advanceTimersByTimeAsync(5_000)
    await flush()
    expect(api.funds.value?.state).toBe('pending')
    expect(api.error.value).toBe('网络错误')
    expect(api.loading.value).toBe(false)
    expect(vi.getTimerCount()).toBe(0)

    // 手动重试成功：错误清除、轮询恢复。
    client.error.value = ''
    client.fetchEngagementExitFunds.mockResolvedValueOnce(funds('processing'))
    await api.refresh()
    expect(api.error.value).toBe('')
    expect(api.funds.value?.state).toBe('processing')
    expect(vi.getTimerCount()).toBe(1)

    // 换目标中止在途只读：app-9 的悬挂回包迟到落地，不得写新目标 state 也不得写 error。
    const hanging = deferred<EngagementExitFunds | null>()
    client.fetchEngagementExitFunds.mockImplementationOnce(() => hanging.promise)
      .mockResolvedValueOnce(funds('succeeded'))
    api.target('task-1', 'app-9')       // app-9 请求悬挂在途
    api.target('task-1', 'app-8')       // 立即换目标：作废在途（abort 只读）
    await flush()
    expect(api.funds.value?.state).toBe('succeeded')
    hanging.resolve(funds('needs_review'))
    await flush()
    expect(api.error.value).toBe('')     // abort/作废不显示业务错误、不污染 client.error
    expect(api.funds.value?.state).toBe('succeeded')
    scope.stop()
  })
})

describe('TC104-05-07 · 监听释放与无组件 scope', () => {  it('反复挂载销毁无监听遗留；dispose 不可逆；裸 effectScope 不装 document 监听', async () => {
    const addSpy = vi.spyOn(document, 'addEventListener')
    const removeSpy = vi.spyOn(document, 'removeEventListener')

    // 裸 effectScope（无组件实例）：不安装 visibility 监听。
    const client = makeClient()
    client.fetchEngagementExitFunds.mockResolvedValue(funds('pending'))
    const scope = effectScope()
    let api!: FundsApi
    scope.run(() => { api = useEngagementExitFunds(client as never) })
    expect(addSpy).not.toHaveBeenCalledWith('visibilitychange', expect.anything())
    api.target('task-1', 'app-1')
    await flush()
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(1)

    // dispose 不可逆：销毁后 target/refresh 都不再发请求。
    scope.stop()
    api.target('task-9', 'app-9')
    await api.refresh()
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(1)

    // 组件挂载/卸载五个循环：visibility 监听装拆数量相等，无遗留。
    const mounted = { count: 0 }
    const Inner = defineComponent({
      setup() { useEngagementExitFunds(client as never); return () => null },
    })
    for (let index = 0; index < 5; index += 1) {
      const show = ref(true)
      const wrapper = mount(defineComponent({
        setup: () => () => h(KeepAlive, () => (show.value ? h(Inner) : null)),
      }))
      show.value = false
      await nextTick()
      wrapper.unmount()
      mounted.count += 1
    }
    const added = addSpy.mock.calls.filter(([type]) => type === 'visibilitychange').length
    const removed = removeSpy.mock.calls.filter(([type]) => type === 'visibilitychange').length
    expect(added).toBe(mounted.count)
    expect(removed).toBe(mounted.count)
  })
})

// ---------- 任务书 #106 C106-05（TC106-05-01～05）：发起门闸与失败停止 ----------

describe('TC106-05-01/F07 · 手动失败撤销已排 timer', () => {
  it('pending 已排 timer，5 秒内手动 refresh 失败——旧 timer 撤销：推进 15s 总请求 2、timer 0、快照/错误保留；手动恢复才再发', async () => {
    const client = makeClient()
    client.fetchEngagementExitFunds.mockResolvedValueOnce(funds('pending'))
      .mockResolvedValueOnce(null)
    client.error.value = ''
    const scope = effectScope()
    let api!: FundsApi
    scope.run(() => { api = useEngagementExitFunds(client as never) })
    api.target('task-1', 'app-1')
    await flush()
    expect(api.funds.value?.state).toBe('pending')
    expect(vi.getTimerCount()).toBe(1)

    // 5 秒前手动 refresh（返回失败）：旧 timer 必须撤销，不得再发第三次。
    client.error.value = '网络错误'
    await api.refresh()
    expect(api.error.value).toBe('网络错误')
    expect(api.funds.value?.state).toBe('pending')
    await vi.advanceTimersByTimeAsync(15_000)
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(2)
    expect(vi.getTimerCount()).toBe(0)

    // 手动恢复才再发。
    client.error.value = ''
    client.fetchEngagementExitFunds.mockResolvedValueOnce(funds('processing'))
    await api.refresh()
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(3)
    expect(api.funds.value?.state).toBe('processing')
    scope.stop()
  })
})

describe('TC106-05-02/F08 · 发起前活动门闸（target/refresh 双入口）', () => {
  function mountKeepAlive(client: Client): { api: FundsApi; show: { value: boolean } } {
    let api!: FundsApi
    const Inner = defineComponent({
      setup() {
        api = useEngagementExitFunds(client as never)
        return () => null
      },
    })
    const show = ref(true)
    mount(defineComponent({
      setup: () => () => h(KeepAlive, () => (show.value ? h(Inner) : null)),
    }))
    return { api, show }
  }

  it('隐藏时换 target/refresh 均 0 请求且不置 loading；恢复显示仍失活仍 0；真激活才对最新 target 发 1 次', async () => {
    const client = makeClient()
    client.fetchEngagementExitFunds.mockResolvedValue(funds('pending'))
    const { api, show } = mountKeepAlive(client)

    setHidden(true)
    api.target('task-1', 'app-1')
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(0)
    expect(api.loading.value).toBe(false)
    await api.refresh()
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(0)
    expect(api.loading.value).toBe(false)
    // 隐藏期间 target 可更新（不请求，恢复时查最新目标）。
    api.target('task-2', 'app-2')
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(0)

    // 失活 + 恢复显示（仍失活）：两条件都恢复才查询。
    show.value = false
    await nextTick()
    setHidden(false)
    await vi.advanceTimersByTimeAsync(100)
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(0)

    // 真激活：立即一次、且只查最新目标 task-2。
    show.value = true
    await nextTick()
    await vi.advanceTimersByTimeAsync(0)
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(1)
    expect(client.fetchEngagementExitFunds).toHaveBeenLastCalledWith('task-2', 'app-2', expect.anything())
  })
})

describe('TC106-05-03 · 在途、晚回与并发', () => {
  it('同目标在途并发 refresh 只 1 次网络请求；旧目标失败晚到不清新 timer、不写错误', async () => {
    const client = makeClient()
    const inFlight = deferred<EngagementExitFunds | null>()
    client.fetchEngagementExitFunds.mockImplementationOnce(() => inFlight.promise)
      .mockResolvedValueOnce(funds('processing'))
    const scope = effectScope()
    let api!: FundsApi
    scope.run(() => { api = useEngagementExitFunds(client as never) })
    api.target('task-1', 'app-1')
    void api.refresh()
    void api.refresh()
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(1)

    // 换目标：旧请求作废；新目标成功并排 timer。
    api.target('task-2', 'app-2')
    await flush()
    expect(api.funds.value?.state).toBe('processing')
    expect(vi.getTimerCount()).toBe(1)

    // 旧目标失败晚到：不写错误、不清新目标的续排 timer。
    inFlight.resolve(null)
    await flush()
    expect(api.error.value).toBe('')
    expect(vi.getTimerCount()).toBe(1)
    await vi.advanceTimersByTimeAsync(5_000)
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(3)  // task-1 在途 + task-2 成功 + task-2 续排
    scope.stop()
  })
})

describe('TC106-05-04 · 状态与时钟边界', () => {
  it.each([
    ['pending', 2], ['processing', 2], ['retry_wait', 2],
    ['succeeded', 1], ['needs_review', 1],
  ] as const)('state=%s 假钟 4999/5000ms：仅进行态完成后 5000ms 续排（4999 不发）', async (state, expected) => {
    const client = makeClient()
    client.fetchEngagementExitFunds.mockResolvedValue(funds(state))
    const scope = effectScope()
    let api!: FundsApi
    scope.run(() => { api = useEngagementExitFunds(client as never) })
    api.target('task-1', 'app-1')
    await flush()
    await vi.advanceTimersByTimeAsync(4_999)
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(expected)
    scope.stop()
  })

  it('null 结果与无 operationId：不排 timer、不推断成功；abort 晚回不写业务错误', async () => {
    const client = makeClient()
    client.fetchEngagementExitFunds.mockResolvedValueOnce(null)
    const scope = effectScope()
    let api!: FundsApi
    scope.run(() => { api = useEngagementExitFunds(client as never) })
    client.error.value = '上游超时'
    api.target('task-1', 'app-1')
    await flush()
    expect(api.funds.value).toBeNull()
    expect(api.error.value).toBe('上游超时')
    expect(vi.getTimerCount()).toBe(0)

    client.error.value = ''
    client.fetchEngagementExitFunds.mockResolvedValueOnce({ operationId: null, state: 'pending' } as never)
    await api.refresh()
    expect(api.funds.value).toBeNull()
    expect(api.error.value).toBe('')
    expect(vi.getTimerCount()).toBe(0)

    // 在途被换目标 abort：悬挂回包晚到不写状态/错误、不复活 timer。
    const hanging = deferred<EngagementExitFunds | null>()
    client.fetchEngagementExitFunds.mockImplementationOnce(() => hanging.promise)
      .mockResolvedValueOnce(funds('succeeded'))
    api.target('task-3', 'app-3')
    api.target('task-4', 'app-4')
    await flush()
    hanging.resolve(null)
    await flush()
    await vi.advanceTimersByTimeAsync(10_000)
    expect(api.error.value).toBe('')
    expect(api.funds.value?.state).toBe('succeeded')
    scope.stop()
  })
})

describe('TC106-05-05 · 匿名/无 Pinia/恢复/disposed', () => {
  it('匿名（未登录）不请求；登录后正常；失败后重新活动恢复一次查询', async () => {
    const client = makeClient()
    client.fetchEngagementExitFunds.mockResolvedValue(funds('pending'))
    const scope = effectScope()
    let api!: FundsApi
    // 当前 beforeEach 的登录态先注销：
    const auth = useAuthStore()
    auth.currentUser = null
    scope.run(() => { api = useEngagementExitFunds(client as never) })
    api.target('task-1', 'app-1')
    await api.refresh()
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(0)
    expect(api.loading.value).toBe(false)

    // 登录后（新 epoch）正常查询。
    auth.currentUser = userA
    client.fetchEngagementExitFunds.mockClear()
    api.target('task-2', 'app-2')
    await flush()
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(1)
    expect(api.funds.value?.state).toBe('pending')
    scope.stop()
  })

  it('无 Pinia（兼容路径）：session 未知不拦查询', async () => {
    setActivePinia(null as never)
    const client = makeClient()
    client.fetchEngagementExitFunds.mockResolvedValue(funds('pending'))
    const scope = effectScope()
    let api!: FundsApi
    scope.run(() => { api = useEngagementExitFunds(client as never) })
    api.target('task-1', 'app-1')
    await flush()
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(1)
    expect(api.funds.value?.state).toBe('pending')
    scope.stop()
    setActivePinia(createPinia())
  })

  it('失败后隐藏再恢复：onResume 恢复一次查询（失败停止不是永久停止）', async () => {
    const client = makeClient()
    client.fetchEngagementExitFunds.mockResolvedValueOnce(funds('pending'))
      .mockResolvedValueOnce(null)
      .mockResolvedValueOnce(funds('processing'))
    let api!: FundsApi
    const Inner = defineComponent({
      setup() {
        api = useEngagementExitFunds(client as never)
        return () => null
      },
    })
    const wrapper = mount(defineComponent({ setup: () => () => h(Inner) }))
    api.target('task-1', 'app-1')
    await flush()
    await vi.advanceTimersByTimeAsync(5_000)
    await flush()
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(2)
    expect(vi.getTimerCount()).toBe(0)

    setHidden(true)
    client.error.value = ''
    setHidden(false)
    await vi.advanceTimersByTimeAsync(0)
    expect(client.fetchEngagementExitFunds).toHaveBeenCalledTimes(3)
    expect(api.funds.value?.state).toBe('processing')
    wrapper.unmount()
  })
})
