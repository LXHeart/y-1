import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'
import type { AuthUser } from '../types/auth'
import { useAccountSessionStore } from './account-session'
import { useCreditsStore } from './credits'

/**
 * TC79-02A/02B（任务书 #79 C79-02）积分部分：余额按账号隔离。
 * 余额为整数积分；null=未加载/失败，成功 0 仍显示 0（E08/E14）。
 * fixture 自任务书 #87 起为 {success,data} 信封——与 CreditsControllerIT 契约用例同形
 * （TC-C03-001；200 裸形态=格式错误，见 E13 护栏用例）。
 */
const userA: AuthUser = { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', email: 'a@qa.invalid', displayName: '甲', role: 'user' }
const userB: AuthUser = { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', email: 'b@qa.invalid', displayName: '乙', role: 'user' }

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (reason?: unknown) => void } {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function makeStore() {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  const session = useAccountSessionStore()
  const store = useCreditsStore()
  return { auth, session, store }
}

function balanceEnvelope(balance: number, totalEarned: number, totalSpent: number): Response {
  return new Response(JSON.stringify({ success: true, data: { balance, totalEarned, totalSpent } }))
}

function historyEnvelope(history: unknown[]): Response {
  return new Response(JSON.stringify({ success: true, data: { history } }))
}

const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('credits 隔离（TC79-02A/B）', () => {
  it('E01：匿名不加载余额与历史', async () => {
    const { store } = makeStore()
    await store.loadBalance()
    await store.loadHistory()
    expect(fetchMock).not.toHaveBeenCalled()
    expect(store.balance).toBeNull()
  })

  it('02A/E14：B 余额 7 与 0 两组；null=未加载，成功 0 显示 0', async () => {
    const first = makeStore()
    first.auth.currentUser = userB
    fetchMock.mockResolvedValueOnce(balanceEnvelope(7, 10, 3))
    await first.store.loadBalance()
    expect(first.store.balance).toMatchObject({ balance: 7, totalEarned: 10, totalSpent: 3 })
    expect(first.store.currentBalance).toBe(7)

    const second = makeStore()
    second.auth.currentUser = userB
    fetchMock.mockResolvedValueOnce(balanceEnvelope(0, 0, 0))
    await second.store.loadBalance()
    expect(second.store.balance?.balance).toBe(0)
    expect(second.store.currentBalance).toBe(0)
  })

  it('02A：history 空数组正常返回；loadBalance 同 owner 并发合并', async () => {
    const { auth, store } = makeStore()
    auth.currentUser = userB
    fetchMock.mockResolvedValueOnce(historyEnvelope([]))
    await expect(store.loadHistory()).resolves.toEqual([])

    fetchMock.mockResolvedValueOnce(balanceEnvelope(7, 10, 3))
    await Promise.all([store.loadBalance(), store.loadBalance()])
    // 去重生效：两次并发 loadBalance 只发一次请求（历史 1 + 余额 1）
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('02B：A 余额 123 挂起 → 切 B → B=7 完成后释放 A（200/401/403/500/断网）：B 不被污染', async () => {
    for (const releaseKind of [200, 401, 403, 500, 'network'] as const) {
      const { auth, store } = makeStore()
      auth.currentUser = userA
      const aLoad = deferred<Response>()
      fetchMock.mockImplementationOnce(() => aLoad.promise)

      const oldLoad = store.loadBalance()
      expect(store.loading).toBe(true)

      auth.currentUser = userB
      // 同步清空：余额回 null（未加载），不显示 A 的数值
      expect(store.balance).toBeNull()
      expect(store.loading).toBe(false)
      expect(store.error).toBe('')

      fetchMock.mockImplementationOnce(() => Promise.resolve(balanceEnvelope(7, 7, 0)))
      await store.loadBalance()
      expect(store.balance?.balance).toBe(7)

      if (releaseKind === 200) aLoad.resolve(balanceEnvelope(123, 200, 77))
      else if (releaseKind === 'network') aLoad.reject(new TypeError('Failed to fetch'))
      else aLoad.resolve(new Response(JSON.stringify({ success: false, error: 'x' }), { status: releaseKind }))
      await oldLoad

      expect(store.balance?.balance).toBe(7)
      expect(store.loading).toBe(false)
      expect(store.error).toBe('')
    }
  })

  it('E06：当前账号 401 不留旧余额（置 null）；500 保留原文案', async () => {
    const { auth, store } = makeStore()
    auth.currentUser = userB
    fetchMock.mockResolvedValueOnce(balanceEnvelope(7, 10, 3))
    await store.loadBalance()
    expect(store.balance?.balance).toBe(7)

    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ success: false, error: '未登录' }), { status: 401 }))
    await store.loadBalance()
    expect(store.balance).toBeNull()
    expect(store.error).toBe('')

    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ success: false, error: 'x' }), { status: 500 }))
    await store.loadBalance()
    expect(store.balance).toBeNull()
    expect(store.error).toBe('获取积分失败')
  })

  it('02B：迟到的旧历史请求返回空，不污染当前结果', async () => {
    const { auth, store } = makeStore()
    auth.currentUser = userA
    const aHistory = deferred<Response>()
    fetchMock.mockImplementationOnce(() => aHistory.promise)
    const oldHistory = store.loadHistory()

    auth.currentUser = userB
    fetchMock.mockImplementationOnce(() => Promise.resolve(historyEnvelope([
      { id: 'h1', amount: 1, balanceAfter: 7, type: 'earn', feature: null, note: null, createdAt: '2026-09-05T16:00:00.000Z' },
    ])))
    const currentHistory = await store.loadHistory()
    expect(currentHistory).toHaveLength(1)

    aHistory.resolve(historyEnvelope([
      { id: 'h-a', amount: 99, balanceAfter: 123, type: 'earn', feature: null, note: null, createdAt: '2026-09-05T16:00:00.000Z' },
    ]))
    await expect(oldHistory).resolves.toEqual([])
  })

  it('E13：200 裸形态（旧后端残局/漂移回归）→ 拒绝：balance null + 获取积分失败；history 返回 []（任务书 #87 TC-C03-002）', async () => {
    const { auth, store } = makeStore()
    auth.currentUser = userB
    // 旧后端裸体 200：不静默显示 123，按格式错误处理
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ balance: 123, totalEarned: 200, totalSpent: 77 })))
    await store.loadBalance()
    expect(store.balance).toBeNull()
    expect(store.error).toBe('获取积分失败')

    // 变体：裸 history 200 → loadHistory 静默返回 []
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({
      history: [{ id: 'h', amount: 1, balanceAfter: 1, type: 'earn', feature: null, note: null, createdAt: null }],
    })))
    await expect(store.loadHistory()).resolves.toEqual([])
  })
})

describe('credits owner 契约与 json 竞态（任务书 #82 C82-02）', () => {
  it('ownerAccountId 公开可观察：登出/换号镜像同步', () => {
    const { auth, store } = makeStore()
    expect(store.ownerAccountId).toBeNull()
    auth.currentUser = userA
    expect(store.ownerAccountId).toBe(userA.id)
    auth.currentUser = userB
    expect(store.ownerAccountId).toBe(userB.id)
    auth.currentUser = null
    expect(store.ownerAccountId).toBeNull()
  })

  it('json() 解析期间换号：A 的余额不写入 B（验票必须在最后一次 await 之后）', async () => {
    const { auth, store } = makeStore()
    auth.currentUser = userA
    // fetch 已 200、票据检查已过，卡在 body 解析这一步（信封形态——成功数据也不得写入）
    const body = deferred<unknown>()
    const stalledResponse = {
      ok: true,
      status: 200,
      json: () => body.promise.then((data) => data),
    } as unknown as Response
    fetchMock.mockImplementationOnce(() => Promise.resolve(stalledResponse))
    const oldLoad = store.loadBalance()
    // 冲掉全部微任务：确保已越过第一个 isCurrent 检查、停在 json() await 上
    await new Promise((resolve) => setTimeout(resolve, 0))

    auth.currentUser = userB
    expect(store.ownerAccountId).toBe(userB.id)
    expect(store.balance).toBeNull()

    body.resolve({ success: true, data: { balance: 123, totalEarned: 200, totalSpent: 77 } })
    await oldLoad
    expect(store.balance).toBeNull() // 修复点：A 的 123 不得写入 B
    expect(store.loading).toBe(false)
    expect(store.error).toBe('')
  })
})
