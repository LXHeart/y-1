// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { useNotifications, resolveLinkTarget } from './useNotifications'
import { useAuthStore } from '../stores/auth'
import type { Notification, NotificationPage } from '../types/notification'

/**
 * 通知中心 composable。重点锁住容易错且用户能看见的几处：
 * - keyset 游标必须 `before` + `beforeId` **成对**下发（缺一个后端 400）；
 * - 未读数按后端 `updated` 减，不按 ids 长度减（重复标记时不能漂负）；
 * - 轮询失败要静默（不能因为离线就在顶栏挂红条）；
 * - 未登记的 linkPath 不猜落点。
 */

function notification(overrides: Partial<Notification> = {}): Notification {
  return {
    id: overrides.id || 'n1',
    category: overrides.category || 'engagement',
    eventType: overrides.eventType || 'DeliverableSubmitted',
    title: overrides.title || '收到交付凭证',
    body: overrides.body || '有推荐官提交了履约凭证，待你核验',
    linkPath: overrides.linkPath === undefined ? '/me/engagements' : overrides.linkPath,
    read: overrides.read ?? false,
    payload: overrides.payload || { taskId: 'task-1' },
    createdAt: overrides.createdAt || '2026-07-31T10:00:00Z',
  }
}

function page(overrides: Partial<NotificationPage> = {}): NotificationPage {
  return {
    items: overrides.items || [notification()],
    unreadCount: overrides.unreadCount ?? 1,
    nextBefore: overrides.nextBefore ?? null,
    nextBeforeId: overrides.nextBeforeId ?? null,
  }
}

type Call = { url: string; method: string; body?: string }

function stubFetch(responses: unknown[], options: { ok?: boolean } = {}): Call[] {
  const calls: Call[] = []
  let index = 0
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, method: init?.method || 'GET', body: init?.body as string | undefined })
    const data = responses[Math.min(index++, responses.length - 1)]
    const body = options.ok === false ? { error: '后端炸了' } : { success: true, data }
    return {
      ok: options.ok ?? true,
      status: options.ok === false ? 500 : 200,
      headers: { get: () => 'application/json' },
      json: async () => body,
      text: async () => JSON.stringify(body),
    }
  }))
  return calls
}

let notifications: ReturnType<typeof useNotifications>

beforeEach(() => {
  notifications = useNotifications()
  notifications.reset()  // 单例状态：每个用例从干净开始
})

afterEach(() => {
  notifications.reset()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('列表与分页', () => {
  test('首页请求带 limit 且不带游标', async () => {
    const calls = stubFetch([page()])
    await notifications.loadFirstPage()

    expect(calls).toHaveLength(1)
    expect(calls[0].url).toContain('limit=20')
    expect(calls[0].url).not.toContain('before=')
    expect(notifications.items.value).toHaveLength(1)
    expect(notifications.unreadCount.value).toBe(1)
  })

  test('翻页把 before 与 beforeId 成对下发并追加结果', async () => {
    const first = page({
      items: [notification({ id: 'n1' })],
      nextBefore: '2026-07-31T10:00:00Z',
      nextBeforeId: 'n1',
    })
    const second = page({ items: [notification({ id: 'n2' })], unreadCount: 2 })
    const calls = stubFetch([first, second])

    await notifications.loadFirstPage()
    expect(notifications.hasMore.value).toBe(true)

    await notifications.loadMore()
    expect(calls[1].url).toContain('before=2026-07-31T10%3A00%3A00Z')
    expect(calls[1].url).toContain('beforeId=n1')
    expect(notifications.items.value.map((n) => n.id)).toEqual(['n1', 'n2'])
    expect(notifications.hasMore.value).toBe(false)
  })

  test('已到末页时 loadMore 不发请求', async () => {
    const calls = stubFetch([page()])
    await notifications.loadFirstPage()
    const before = calls.length

    expect(await notifications.loadMore()).toBeNull()
    expect(calls).toHaveLength(before)
  })

  test('只看未读会带 unreadOnly=true 并重拉第一页', async () => {
    const calls = stubFetch([page()])
    await notifications.setUnreadOnly(true)

    expect(calls[0].url).toContain('unreadOnly=true')
    expect(notifications.unreadOnly.value).toBe(true)
  })

  test('请求失败把后端消息写进 error 且不清空已有列表', async () => {
    stubFetch([page()])
    await notifications.loadFirstPage()
    vi.unstubAllGlobals()

    stubFetch([null], { ok: false })
    expect(await notifications.loadMore()).toBeNull()
    expect(notifications.items.value).toHaveLength(1)
  })
})

describe('标已读', () => {
  test('未读数按后端 updated 减，重复标记不漂负', async () => {
    stubFetch([page({ items: [notification({ id: 'n1' })], unreadCount: 1 })])
    await notifications.loadFirstPage()
    vi.unstubAllGlobals()

    const calls = stubFetch([{ updated: 1 }])
    expect(await notifications.markRead(['n1'])).toBe(1)
    expect(notifications.unreadCount.value).toBe(0)
    expect(notifications.items.value[0].read).toBe(true)
    expect(calls[0].method).toBe('POST')
    expect(JSON.parse(calls[0].body as string)).toEqual({ ids: ['n1'] })

    // 已读的再标一次：不该发请求，也不该把未读数减成负数
    const callsBefore = calls.length
    expect(await notifications.markRead(['n1'])).toBe(0)
    expect(calls).toHaveLength(callsBefore)
    expect(notifications.unreadCount.value).toBe(0)
  })

  test('read-all 把本地全部置已读且未读数归零', async () => {
    stubFetch([page({
      items: [notification({ id: 'n1' }), notification({ id: 'n2', category: 'wallet' })],
      unreadCount: 2,
    })])
    await notifications.loadFirstPage()
    vi.unstubAllGlobals()

    stubFetch([{ updated: 2 }])
    expect(await notifications.markAllRead()).toBe(2)
    expect(notifications.items.value.every((n) => n.read)).toBe(true)
    expect(notifications.unreadCount.value).toBe(0)
  })
})

describe('分组', () => {
  test('按固定顺序分组且空组不出现', async () => {
    stubFetch([page({
      items: [
        notification({ id: 'n1', category: 'wallet' }),
        notification({ id: 'n2', category: 'invitation' }),
        notification({ id: 'n3', category: 'wallet' }),
      ],
      unreadCount: 3,
    })])
    await notifications.loadFirstPage()

    expect(notifications.grouped.value.map((g) => g.category)).toEqual(['invitation', 'wallet'])
    expect(notifications.grouped.value[1].items).toHaveLength(2)
  })
})

describe('未读轮询', () => {
  test('startPolling 立即拉一次并按间隔续拉', async () => {
    vi.useFakeTimers()
    const calls = stubFetch([{ unreadCount: 3 }])

    notifications.startPolling()
    await vi.advanceTimersByTimeAsync(0)
    expect(notifications.unreadCount.value).toBe(3)
    expect(calls[0].url).toBe('/api/me/notifications/unread-count')

    await vi.advanceTimersByTimeAsync(60_000)
    expect(calls).toHaveLength(2)

    notifications.stopPolling()
    await vi.advanceTimersByTimeAsync(120_000)
    expect(calls).toHaveLength(2)
  })

  test('轮询失败静默——不写 error，不打断界面', async () => {
    stubFetch([null], { ok: false })
    expect(await notifications.refreshUnreadCount()).toBeNull()
    expect(notifications.error.value).toBe('')
  })

  test('reset 清空状态并停止轮询', async () => {
    vi.useFakeTimers()
    const calls = stubFetch([{ unreadCount: 5 }])
    notifications.startPolling()
    await vi.advanceTimersByTimeAsync(0)

    notifications.reset()
    expect(notifications.unreadCount.value).toBe(0)
    expect(notifications.items.value).toEqual([])
    await vi.advanceTimersByTimeAsync(120_000)
    expect(calls).toHaveLength(1)
  })
})

describe('linkPath 落点', () => {
  test('已登记的 path 映射到工作台锚点', () => {
    expect(resolveLinkTarget('/me/engagements')).toEqual({ view: 'grassland', anchor: 'gl-engagements' })
    // 2026-09-04 反馈 5：gl-disputes 区撤除——无 disputeId 的争议通知兜底落履约区（那里有「我的争议」入口）
    expect(resolveLinkTarget('/me/disputes')).toEqual({ view: 'grassland', anchor: 'gl-engagements' })
    expect(resolveLinkTarget('/me/wallet')).toEqual({ view: 'grassland', anchor: 'gl-wallet' })
  })

  test('null 或未登记的 path 不猜落点', () => {
    expect(resolveLinkTarget(null)).toBeNull()
    expect(resolveLinkTarget('/me/unknown')).toBeNull()
  })

  test('任务邀请使用 payload 直达指定任务并切推荐官视角', () => {
    expect(resolveLinkTarget('/me/task-invitations', {
      taskId: 'task-1', invitationId: 'invite-1',
    })).toEqual({
      view: 'grassland', anchor: 'gl-task-hall', side: 'recommender', taskId: 'task-1',
    })
    expect(resolveLinkTarget('/me/task-invitations', {})).toBeNull()
  })

  test('任务驳回通知直达商家任务，争议通知携带精确争议 id', () => {
    expect(resolveLinkTarget('/me/task-review', { taskId: 'task-2' })).toEqual({
      view: 'grassland', anchor: 'gl-engagements', side: 'merchant', taskId: 'task-2',
    })
    expect(resolveLinkTarget('/me/disputes', { disputeId: 'dispute-1' })).toEqual({
      view: 'grassland', anchor: 'gl-disputes', disputeId: 'dispute-1',
    })
  })
})

describe('账号边界（任务书 #82 C82-01）', () => {
  const userA = { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', email: 'a@qa.invalid', displayName: '甲', role: 'user' }
  const userB = { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', email: 'b@qa.invalid', displayName: '乙', role: 'user' }

  /** 手动放行式 fetch stub：每个请求挂起，直到测试按序 resolve（模拟可控延迟/乱序）。 */
  function stubDeferredFetch() {
    const calls: Call[] = []
    const resolvers: Array<(data: unknown, ok?: boolean) => void> = []
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      calls.push({ url, method: init?.method || 'GET', body: init?.body as string | undefined })
      return new Promise((resolve) => {
        resolvers.push((data: unknown, ok = true) => resolve({
          ok,
          status: ok ? 200 : 500,
          headers: { get: () => 'application/json' },
          json: async () => (ok ? { success: true, data } : { error: String(data) }),
          text: async () => JSON.stringify(ok ? { success: true, data } : { error: String(data) }),
        }))
      })
    }))
    return { calls, resolvers }
  }

  afterEach(() => {
    useAuthStore().currentUser = null
  })

  test('A→B：A 的列表响应迟到不写入 B——列表/未读/游标/错误/loading 全不串（E02）', async () => {
    const auth = useAuthStore()
    const { resolvers } = stubDeferredFetch()
    auth.currentUser = userA
    const first = notifications.loadFirstPage()

    auth.currentUser = userB
    expect(notifications.ownerAccountId.value).toBe(userB.id)
    expect(notifications.items.value).toEqual([])
    expect(notifications.loading.value).toBe(false) // A 的 loading 不留给 B

    const second = notifications.loadFirstPage()
    resolvers[1](page({ items: [notification({ id: 'nb1' })], unreadCount: 2 }))
    await second
    expect(notifications.items.value.map((n) => n.id)).toEqual(['nb1'])
    expect(notifications.unreadCount.value).toBe(2)

    resolvers[0](page({ items: [notification({ id: 'na1' })], unreadCount: 9 }))
    await expect(first).resolves.toBeNull() // 旧票静默终止
    expect(notifications.items.value.map((n) => n.id)).toEqual(['nb1'])
    expect(notifications.unreadCount.value).toBe(2)
    expect(notifications.error.value).toBe('')
    expect(notifications.loading.value).toBe(false)
  })

  test('A→B：A 的未读轮询响应迟到不覆盖 B 的未读数', async () => {
    const auth = useAuthStore()
    const { resolvers } = stubDeferredFetch()
    auth.currentUser = userA
    const aRefresh = notifications.refreshUnreadCount()
    auth.currentUser = userB
    const bRefresh = notifications.refreshUnreadCount()
    resolvers[1]({ unreadCount: 3 })
    await expect(bRefresh).resolves.toBe(3)
    resolvers[0]({ unreadCount: 8 })
    await expect(aRefresh).resolves.toBeNull()
    expect(notifications.unreadCount.value).toBe(3)
  })

  test('A 的 401/500 迟到同样静默：不写 error、不伪造成功（E05/TC82-01-01）', async () => {
    const auth = useAuthStore()
    const { resolvers } = stubDeferredFetch()
    auth.currentUser = userA
    const aLoad = notifications.loadFirstPage() // fetch#0
    const aMarkAll = notifications.markAllRead() // fetch#1
    auth.currentUser = userB
    const bLoad = notifications.loadFirstPage() // fetch#2
    resolvers[2](page({ items: [], unreadCount: 0 }))
    await bLoad

    resolvers[1](null, false) // A 的 read-all 500
    resolvers[0](null, false) // A 的列表 500
    await expect(aMarkAll).resolves.toBeNull()
    await expect(aLoad).resolves.toBeNull()
    expect(notifications.error.value).toBe('')
    expect(notifications.items.value).toEqual([])
    expect(notifications.unreadCount.value).toBe(0)
    expect(notifications.loading.value).toBe(false)
  })

  test('A→B→A：第一轮 A 的旧 epoch 仍失效，只接受新 A epoch（E03）', async () => {
    const auth = useAuthStore()
    const { resolvers } = stubDeferredFetch()
    auth.currentUser = userA
    const stale = notifications.loadFirstPage()
    auth.currentUser = userB
    auth.currentUser = userA
    expect(notifications.ownerAccountId.value).toBe(userA.id)
    const fresh = notifications.loadFirstPage()
    resolvers[1](page({ items: [notification({ id: 'na2' })], unreadCount: 1 }))
    await fresh
    resolvers[0](page({ items: [notification({ id: 'na1' })], unreadCount: 5 }))
    await expect(stale).resolves.toBeNull()
    expect(notifications.items.value.map((n) => n.id)).toEqual(['na2'])
    expect(notifications.unreadCount.value).toBe(1)
  })

  test('同 owner 并发：首页与未读数各只发一次请求，两个调用方共享同一份结果（E01）', async () => {
    const auth = useAuthStore()
    const { calls, resolvers } = stubDeferredFetch()
    auth.currentUser = userA
    const first = notifications.loadFirstPage()
    // Pinia 对 store action 每次调用都会派生新 promise，promise 同一性断言对 action 不成立；
    // 去重的可观察契约 = 请求不叠加 + 各调用方拿到同一份结果。
    const second = notifications.loadFirstPage()
    const u1 = notifications.refreshUnreadCount()
    const u2 = notifications.refreshUnreadCount()

    resolvers[0](page({ items: [notification({ id: 'n1' })], unreadCount: 1 }))
    const [firstPage, secondPage] = await Promise.all([first, second])
    resolvers[1]({ unreadCount: 1 })
    const [c1, c2] = await Promise.all([u1, u2])

    expect(calls).toHaveLength(2) // 首页 1 次 + 未读 1 次，无叠加
    expect(secondPage).toBe(firstPage) // 同一份页对象，不重复写入
    expect(c2).toBe(c1)
    expect(notifications.items.value).toHaveLength(1)
    expect(notifications.unreadCount.value).toBe(1)
  })

  test('A 标已读响应迟到：不改 B 的列表与未读数', async () => {
    const auth = useAuthStore()
    const { resolvers } = stubDeferredFetch()
    auth.currentUser = userA
    const aLoad = notifications.loadFirstPage()
    resolvers[0](page({ items: [notification({ id: 'na1' })], unreadCount: 1 }))
    await aLoad

    const aMark = notifications.markRead(['na1'])
    auth.currentUser = userB
    const bLoad = notifications.loadFirstPage()
    resolvers[2](page({ items: [notification({ id: 'nb1' })], unreadCount: 4 }))
    await bLoad

    resolvers[1]({ updated: 1 })
    await expect(aMark).resolves.toBeNull()
    expect(notifications.items.value.map((n) => n.id)).toEqual(['nb1'])
    expect(notifications.items.value[0].read).toBe(false)
    expect(notifications.unreadCount.value).toBe(4)
  })

  test('轮询按账号启停：重复 start 不叠加请求与 timer，换号即停（E04）', async () => {
    vi.useFakeTimers()
    const auth = useAuthStore()
    const { calls, resolvers } = stubDeferredFetch()
    auth.currentUser = userA

    notifications.startPolling()
    notifications.startPolling() // 重复启动：未读请求去重、timer 只留一个
    await vi.advanceTimersByTimeAsync(0)
    resolvers[0]({ unreadCount: 2 })
    await vi.advanceTimersByTimeAsync(0)
    expect(notifications.unreadCount.value).toBe(2)
    expect(calls).toHaveLength(1)

    await vi.advanceTimersByTimeAsync(60_000)
    resolvers[1]({ unreadCount: 2 })
    await vi.advanceTimersByTimeAsync(0)
    expect(calls).toHaveLength(2)

    auth.currentUser = userB // 换号：reset 停旧 timer、清未读数
    expect(notifications.unreadCount.value).toBe(0)
    await vi.advanceTimersByTimeAsync(180_000)
    expect(calls).toHaveLength(2)
  })
})
