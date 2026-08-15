// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { useNotifications, resolveLinkTarget } from './useNotifications'
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
    return {
      ok: options.ok ?? true,
      status: options.ok === false ? 500 : 200,
      headers: { get: () => 'application/json' },
      json: async () => (options.ok === false ? { error: '后端炸了' } : { success: true, data }),
      text: async () => '',
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
    expect(resolveLinkTarget('/me/disputes')).toEqual({ view: 'grassland', anchor: 'gl-disputes' })
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
