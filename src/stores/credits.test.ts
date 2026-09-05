import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'
import type { AuthUser } from '../types/auth'
import { useAccountSessionStore } from './account-session'
import { useCreditsStore } from './credits'

/**
 * TC79-02A/02B（任务书 #79 C79-02）积分部分：裸 JSON 余额按账号隔离。
 * 余额为整数积分；null=未加载/失败，成功 0 仍显示 0（E08/E14）。
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
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ balance: 7, totalEarned: 10, totalSpent: 3 })))
    await first.store.loadBalance()
    expect(first.store.balance).toMatchObject({ balance: 7, totalEarned: 10, totalSpent: 3 })
    expect(first.store.currentBalance).toBe(7)

    const second = makeStore()
    second.auth.currentUser = userB
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ balance: 0, totalEarned: 0, totalSpent: 0 })))
    await second.store.loadBalance()
    expect(second.store.balance?.balance).toBe(0)
    expect(second.store.currentBalance).toBe(0)
  })

  it('02A：history 空数组正常返回；loadBalance 同 owner 并发合并', async () => {
    const { auth, store } = makeStore()
    auth.currentUser = userB
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ history: [] })))
    await expect(store.loadHistory()).resolves.toEqual([])

    fetchMock.mockResolvedValue(new Response(JSON.stringify({ balance: 7, totalEarned: 10, totalSpent: 3 })))
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

      fetchMock.mockImplementationOnce(() => Promise.resolve(new Response(JSON.stringify({ balance: 7, totalEarned: 7, totalSpent: 0 }))))
      await store.loadBalance()
      expect(store.balance?.balance).toBe(7)

      if (releaseKind === 200) aLoad.resolve(new Response(JSON.stringify({ balance: 123, totalEarned: 200, totalSpent: 77 })))
      else if (releaseKind === 'network') aLoad.reject(new TypeError('Failed to fetch'))
      else aLoad.resolve(new Response(JSON.stringify({ error: 'x' }), { status: releaseKind }))
      await oldLoad

      expect(store.balance?.balance).toBe(7)
      expect(store.loading).toBe(false)
      expect(store.error).toBe('')
    }
  })

  it('E06：当前账号 401 不留旧余额（置 null）；500 保留原文案', async () => {
    const { auth, store } = makeStore()
    auth.currentUser = userB
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ balance: 7, totalEarned: 10, totalSpent: 3 })))
    await store.loadBalance()
    expect(store.balance?.balance).toBe(7)

    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({}), { status: 401 }))
    await store.loadBalance()
    expect(store.balance).toBeNull()
    expect(store.error).toBe('')

    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({}), { status: 500 }))
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
    fetchMock.mockImplementationOnce(() => Promise.resolve(new Response(JSON.stringify({
      history: [{ id: 'h1', amount: 1, balanceAfter: 7, type: 'earn', feature: null, note: null, createdAt: '2026-09-05T16:00:00.000Z' }],
    }))))
    const currentHistory = await store.loadHistory()
    expect(currentHistory).toHaveLength(1)

    aHistory.resolve(new Response(JSON.stringify({
      history: [{ id: 'h-a', amount: 99, balanceAfter: 123, type: 'earn', feature: null, note: null, createdAt: '2026-09-05T16:00:00.000Z' }],
    })))
    await expect(oldHistory).resolves.toEqual([])
  })
})
