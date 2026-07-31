// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import NotificationBell from './NotificationBell.vue'
import { useAuth } from '../composables/useAuth'
import { useNotifications } from '../composables/useNotifications'
import type { AuthUser } from '../types/auth'

/**
 * 顶栏通知入口。锁住两件容易回归的事：
 * - 按**账号**启停轮询（顶栏不随视图卸载：同页面内登录后必须自动开始轮询——`MyInvitationsCard`
 *   踩过同样的坑；登出必须清空，不能把上一个账号的未读数留在徽标上）；
 * - 面板的 navigate 要原样冒泡给 App。
 */

const { currentUser } = useAuth()

function asUser(id: string): AuthUser {
  return { id, email: `${id}@test.local`, displayName: id, role: 'user' }
}

type Call = { url: string }

function stubFetch(unreadCount: number): Call[] {
  const calls: Call[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    calls.push({ url })
    const data = url.includes('unread-count')
      ? { unreadCount }
      : { items: [], unreadCount, nextBefore: null, nextBeforeId: null }
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
  currentUser.value = null
  useNotifications().reset()
})

afterEach(() => {
  currentUser.value = null
  useNotifications().reset()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('未读徽标', () => {
  test('登录后自动拉未读数并显示徽标', async () => {
    const calls = stubFetch(3)
    currentUser.value = asUser('u1')
    const wrapper = mount(NotificationBell)
    await flushPromises()

    expect(calls.some((c) => c.url.includes('unread-count'))).toBe(true)
    expect(wrapper.find('.nt-bell-badge').text()).toBe('3')
  })

  test('未读为 0 不显示徽标', async () => {
    stubFetch(0)
    currentUser.value = asUser('u1')
    const wrapper = mount(NotificationBell)
    await flushPromises()

    expect(wrapper.find('.nt-bell-badge').exists()).toBe(false)
  })

  test('超过 99 显示 99+', async () => {
    stubFetch(120)
    currentUser.value = asUser('u1')
    const wrapper = mount(NotificationBell)
    await flushPromises()

    expect(wrapper.find('.nt-bell-badge').text()).toBe('99+')
  })

  test('挂载后再登录也会开始轮询（顶栏不随视图卸载）', async () => {
    const calls = stubFetch(2)
    const wrapper = mount(NotificationBell)
    await flushPromises()
    expect(calls).toHaveLength(0)

    currentUser.value = asUser('u1')
    await flushPromises()
    expect(calls.some((c) => c.url.includes('unread-count'))).toBe(true)
    expect(wrapper.find('.nt-bell-badge').text()).toBe('2')
  })

  test('登出清空徽标并停止轮询', async () => {
    vi.useFakeTimers()
    const calls = stubFetch(4)
    currentUser.value = asUser('u1')
    const wrapper = mount(NotificationBell)
    await vi.advanceTimersByTimeAsync(0)
    expect(wrapper.find('.nt-bell-badge').text()).toBe('4')

    currentUser.value = null
    await vi.advanceTimersByTimeAsync(0)
    expect(wrapper.find('.nt-bell-badge').exists()).toBe(false)

    const settled = calls.length
    await vi.advanceTimersByTimeAsync(180_000)
    expect(calls).toHaveLength(settled)
  })
})

describe('面板开合', () => {
  test('点击展开面板并刷新未读数，再点收起', async () => {
    const calls = stubFetch(1)
    currentUser.value = asUser('u1')
    const wrapper = mount(NotificationBell)
    await flushPromises()
    const initial = calls.length

    await wrapper.find('.nt-bell').trigger('click')
    await flushPromises()
    expect(wrapper.find('.nt-panel').exists()).toBe(true)
    expect(wrapper.find('.nt-bell').attributes('aria-expanded')).toBe('true')
    expect(calls.length).toBeGreaterThan(initial)

    await wrapper.find('.nt-bell').trigger('click')
    expect(wrapper.find('.nt-panel').exists()).toBe(false)
  })

  test('点遮罩关闭面板', async () => {
    stubFetch(1)
    currentUser.value = asUser('u1')
    const wrapper = mount(NotificationBell)
    await flushPromises()

    await wrapper.find('.nt-bell').trigger('click')
    await flushPromises()
    await wrapper.find('.nt-backdrop').trigger('click')

    expect(wrapper.find('.nt-panel').exists()).toBe(false)
  })

  test('面板的 navigate 原样冒泡给上层', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      const data = url.includes('unread-count')
        ? { unreadCount: 1 }
        : url.endsWith('/read') || init?.method === 'POST'
          ? { updated: 1 }
          : {
              items: [{
                id: 'n1', category: 'dispute', eventType: 'DisputeAssigned',
                title: '争议已进入审判', body: '你参与的争议已组建审判庭并开始投票',
                linkPath: '/me/disputes', read: false,
                payload: { disputeId: 'dsp-1234abcd' }, createdAt: '2026-07-31T10:00:00Z',
              }],
              unreadCount: 1, nextBefore: null, nextBeforeId: null,
            }
      return {
        ok: true, status: 200,
        headers: { get: () => 'application/json' },
        json: async () => ({ success: true, data }),
        text: async () => '',
      }
    }))
    currentUser.value = asUser('u1')
    const wrapper = mount(NotificationBell)
    await flushPromises()

    await wrapper.find('.nt-bell').trigger('click')
    await flushPromises()
    await wrapper.find('.nt-item').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('navigate')?.[0]).toEqual([{ view: 'grassland', anchor: 'gl-disputes' }])
    expect(wrapper.find('.nt-panel').exists()).toBe(false)  // 跳转后自动收起
  })
})
