// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import NotificationPanel from './NotificationPanel.vue'
import { useNotifications } from '../composables/useNotifications'
import type { Notification, NotificationPage } from '../types/notification'

/**
 * 通知面板。锁住用户直接看得见的几处：
 * - 按 category 分组渲染（组内顺序不重排）；
 * - 正文之外要把 payload 的标的/金额渲染出来（后端刻意不把标的写进 body）；
 * - 点击 = 标已读 + 可解析时才发 navigate；不可解析的 linkPath 只标已读不跳。
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
    payload: overrides.payload || {},
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

function stubFetch(responses: unknown[]): Call[] {
  const calls: Call[] = []
  let index = 0
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, method: init?.method || 'GET', body: init?.body as string | undefined })
    const data = responses[Math.min(index++, responses.length - 1)]
    return {
      ok: true,
      status: 200,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: true, data }),
      text: async () => '',
    }
  }))
  return calls
}

enableAutoUnmount(afterEach)

beforeEach(() => {
  useNotifications().reset()
})

afterEach(() => {
  useNotifications().reset()
  vi.unstubAllGlobals()
})

describe('渲染', () => {
  test('按分类分组，组标题用中文', async () => {
    stubFetch([page({
      items: [
        notification({ id: 'n1', category: 'wallet', title: '佣金已到账' }),
        notification({ id: 'n2', category: 'invitation', title: '你收到了一份组织邀请' }),
      ],
      unreadCount: 2,
    })])
    const wrapper = mount(NotificationPanel)
    await flushPromises()

    const titles = wrapper.findAll('.nt-group-title').map((n) => n.text())
    expect(titles).toEqual(['组织邀请', '钱包'])  // 固定顺序，不按后端返回序
    expect(wrapper.text()).toContain('佣金已到账')
  })

  test('payload 的标的与金额渲染进摘要（body 里没有）', async () => {
    stubFetch([page({
      items: [notification({
        id: 'n1', category: 'wallet', linkPath: '/me/wallet',
        payload: { engagementRef: 'app-12345678', payoutCents: 50000 },
      })],
    })])
    const wrapper = mount(NotificationPanel)
    await flushPromises()

    const meta = wrapper.find('.nt-meta').text()
    expect(meta).toContain('履约 app-1234…')
    expect(meta).toContain('¥500.00')
  })

  test('退回原因渲染出来（推荐官要知道为什么被退）', async () => {
    stubFetch([page({
      items: [notification({
        id: 'n1', eventType: 'DeliverableRejected',
        payload: { taskId: 'task-abcdefgh', reason: '链接打不开' },
      })],
    })])
    const wrapper = mount(NotificationPanel)
    await flushPromises()

    expect(wrapper.find('.nt-meta').text()).toContain('原因：链接打不开')
  })

  test('空列表按「只看未读」给不同文案', async () => {
    stubFetch([page({ items: [], unreadCount: 0 })])
    const wrapper = mount(NotificationPanel)
    await flushPromises()

    expect(wrapper.find('.nt-empty').text()).toBe('暂无通知')
  })
})

describe('交互', () => {
  test('点击未读条目：标已读并按 linkPath 发 navigate', async () => {
    const calls = stubFetch([page(), { updated: 1 }])
    const wrapper = mount(NotificationPanel)
    await flushPromises()

    await wrapper.find('.nt-item').trigger('click')
    await flushPromises()

    expect(calls[1].url).toBe('/api/me/notifications/read')
    expect(JSON.parse(calls[1].body as string)).toEqual({ ids: ['n1'] })
    expect(wrapper.emitted('navigate')?.[0]).toEqual([{ view: 'grassland', anchor: 'gl-engagements' }])
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  test('linkPath 为 null 时只标已读，不发 navigate 也不关面板', async () => {
    const calls = stubFetch([
      page({ items: [notification({ id: 'n1', category: 'invitation', linkPath: null })] }),
      { updated: 1 },
    ])
    const wrapper = mount(NotificationPanel)
    await flushPromises()

    await wrapper.find('.nt-item').trigger('click')
    await flushPromises()

    expect(calls[1].url).toBe('/api/me/notifications/read')
    expect(wrapper.emitted('navigate')).toBeUndefined()
    expect(wrapper.emitted('close')).toBeUndefined()
  })

  test('已读条目点击不再发 read 请求', async () => {
    const calls = stubFetch([page({ items: [notification({ id: 'n1', read: true })], unreadCount: 0 })])
    const wrapper = mount(NotificationPanel)
    await flushPromises()

    await wrapper.find('.nt-item').trigger('click')
    await flushPromises()

    expect(calls.filter((c) => c.url.endsWith('/read'))).toHaveLength(0)
    expect(wrapper.emitted('navigate')).toHaveLength(1)  // 已读也照样能跳
  })

  test('全部已读：未读为 0 时按钮禁用，有未读时打 read-all', async () => {
    const calls = stubFetch([page({ unreadCount: 1 }), { updated: 1 }, page({ items: [], unreadCount: 0 })])
    const wrapper = mount(NotificationPanel)
    await flushPromises()

    const allRead = wrapper.findAll('.nt-quiet').find((b) => b.text() === '全部已读')!
    expect(allRead.attributes('disabled')).toBeUndefined()
    await allRead.trigger('click')
    await flushPromises()

    expect(calls[1].url).toBe('/api/me/notifications/read-all')
    expect(allRead.attributes('disabled')).toBeDefined()
  })

  test('有下一页才显示「加载更多」，点击后追加', async () => {
    const calls = stubFetch([
      page({ items: [notification({ id: 'n1' })], nextBefore: '2026-07-31T10:00:00Z', nextBeforeId: 'n1' }),
      page({ items: [notification({ id: 'n2', title: '履约已结算' })] }),
    ])
    const wrapper = mount(NotificationPanel)
    await flushPromises()

    await wrapper.find('.nt-more').trigger('click')
    await flushPromises()

    expect(calls[1].url).toContain('beforeId=n1')
    expect(wrapper.text()).toContain('履约已结算')
    expect(wrapper.find('.nt-more').exists()).toBe(false)
  })

  test('勾选「只看未读」重拉并带 unreadOnly', async () => {
    const calls = stubFetch([page(), page({ items: [], unreadCount: 0 })])
    const wrapper = mount(NotificationPanel)
    await flushPromises()

    await wrapper.find('.nt-toggle input').setValue(true)
    await flushPromises()

    expect(calls[1].url).toContain('unreadOnly=true')
    expect(wrapper.find('.nt-empty').text()).toBe('没有未读通知')
  })
})
