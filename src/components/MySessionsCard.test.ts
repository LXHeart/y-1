// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import MySessionsCard from './MySessionsCard.vue'
import { useAuth } from '../composables/useAuth'
import type { AuthUser } from '../types/auth'

/**
 * 「登录设备」卡片。重点锁两条**安全语义**，说错比不做更糟：
 * - 撤销「本机」= 把自己登出，必须二次确认，且撤销后要同步清掉前端登录态
 *   （否则界面还显示已登录、后续请求全 401）；
 * - 撤销别的设备不能顺手把自己也登出。
 */

const { currentUser } = useAuth()

function asUser(id: string, email: string): AuthUser {
  return { id, email, displayName: email, role: 'user' }
}

const OTHER_DEVICE = {
  sessionToken: 'sid-other',
  activeIdentityType: 'recommender',
  deviceId: 'abcdef1234567890',
  deviceLabel: null,
  ipAddress: '10.0.0.2',
  lastSeenAt: new Date().toISOString(),
  current: false,
}

const THIS_DEVICE = {
  sessionToken: 'sid-current',
  activeIdentityType: 'merchant',
  deviceId: 'fedcba0987654321',
  deviceLabel: null,
  ipAddress: '10.0.0.1',
  lastSeenAt: new Date().toISOString(),
  expiresAt: new Date(Date.now() + 7 * 86400_000).toISOString(),
  current: true,
}

/**
 * 桩：GET /api/me/sessions 回给定设备列表，其余端点回 `{success:true}` 不带 data 键
 * （DELETE / logout 的真实形状；回 `data: null` 会被 `run()` 当成失败）。
 */
function stubFetch(devices: unknown[]): { calls: { url: string; method: string }[] } {
  const calls: { url: string; method: string }[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, method: init?.method || 'GET' })
    const method = init?.method || 'GET'
    const isList = url === '/api/me/sessions' && method === 'GET'
    const isRevokeOthers = url === '/api/me/sessions' && method === 'DELETE'
    return {
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => (isList
        ? { success: true, data: devices }
        : isRevokeOthers
          ? { success: true, data: { revoked: devices.filter((d) => !((d as { current: boolean }).current)).length } }
          : { success: true }),
    }
  }))
  return { calls }
}

enableAutoUnmount(afterEach)

beforeEach(() => {
  currentUser.value = null
})

afterEach(() => {
  vi.unstubAllGlobals()
  currentUser.value = null
})

describe('MySessionsCard 列表', () => {
  test('登录后自动拉设备列表并标出本机', async () => {
    const { calls } = stubFetch([THIS_DEVICE, OTHER_DEVICE])
    const wrapper = mount(MySessionsCard)
    await flushPromises()
    expect(calls).toEqual([])

    currentUser.value = asUser('acct-1', 'a@test.local')
    await flushPromises()

    expect(calls.map((c) => c.url)).toEqual(['/api/me/sessions'])
    expect(wrapper.text()).toContain('本机')
    expect(wrapper.text()).toContain('商家')
    expect(wrapper.text()).toContain('推荐官')
  })

  test('如实说明撤销即登出', async () => {
    stubFetch([THIS_DEVICE])
    const wrapper = mount(MySessionsCard)
    currentUser.value = asUser('acct-1', 'a@test.local')
    await flushPromises()

    expect(wrapper.text()).toContain('立即登出')
  })

  /** 只登录、没切过身份的设备：右表无行，各字段为 null，UI 不能因此崩或显示 undefined。 */
  test('未激活身份的设备显示为「未知设备 / 消费者」', async () => {
    stubFetch([{
      sessionToken: 'sid-plain', activeIdentityType: null, deviceId: null,
      deviceLabel: null, ipAddress: null, lastSeenAt: null, current: true,
    }])
    const wrapper = mount(MySessionsCard)
    currentUser.value = asUser('acct-1', 'a@test.local')
    await flushPromises()

    expect(wrapper.text()).toContain('未知设备')
    expect(wrapper.text()).toContain('消费者')
    expect(wrapper.text()).toContain('本机')
    expect(wrapper.text()).not.toContain('undefined')
  })

  test('登出清空列表', async () => {
    stubFetch([THIS_DEVICE])
    const wrapper = mount(MySessionsCard)
    currentUser.value = asUser('acct-1', 'a@test.local')
    await flushPromises()
    expect(wrapper.text()).toContain('本机')

    currentUser.value = null
    await flushPromises()

    expect(wrapper.text()).toContain('暂无记录')
  })
})

describe('MySessionsCard 分页', () => {
  function manySessions(n: number) {
    return Array.from({ length: n }, (_, i) => ({
      sessionToken: `sid-${i}`,
      activeIdentityType: null,
      deviceId: null,
      deviceLabel: null,
      ipAddress: null,
      lastSeenAt: new Date(Date.now() - i * 60000).toISOString(),
      current: i === 0,
    }))
  }

  test('超过 5 条分页：默认首页 5 条，翻页可见其余，页码如实', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: true, data: manySessions(12) }),
    })))
    const wrapper = mount(MySessionsCard)
    currentUser.value = asUser('acct-sess', 'sess@test.local')
    await flushPromises()

    // 首页 5 条；分页态锁定满页高度（末页条数少也不塌陷）
    expect(wrapper.findAll('.sess-list li')).toHaveLength(5)
    expect(wrapper.find('.sess-list-paged').exists()).toBe(true)
    expect(wrapper.get('.sess-page').text()).toBe('第 1 / 3 页 · 共 12 条')

    await wrapper.get('.sess-pager button:last-child').trigger('click')
    expect(wrapper.findAll('.sess-list li')).toHaveLength(5)
    await wrapper.get('.sess-pager button:last-child').trigger('click')
    expect(wrapper.findAll('.sess-list li')).toHaveLength(2)
    expect(wrapper.get('.sess-page').text()).toBe('第 3 / 3 页 · 共 12 条')
    // 末页「下一页」禁用
    expect(wrapper.get('.sess-pager button:last-child').attributes('disabled')).toBe('')
  })

  test('5 条及以下不渲染分页条', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: true, data: manySessions(4) }),
    })))
    const wrapper = mount(MySessionsCard)
    currentUser.value = asUser('acct-sess2', 'sess2@test.local')
    await flushPromises()

    expect(wrapper.findAll('.sess-list li')).toHaveLength(4)
    expect(wrapper.find('.sess-pager').exists()).toBe(false)
    expect(wrapper.find('.sess-list-paged').exists()).toBe(false)
  })
})

describe('MySessionsCard 撤销', () => {
  test('撤销其它设备：DELETE 该 token，且不动本机登录态', async () => {
    const { calls } = stubFetch([THIS_DEVICE, OTHER_DEVICE])
    const wrapper = mount(MySessionsCard)
    currentUser.value = asUser('acct-1', 'a@test.local')
    await flushPromises()

    const otherRow = wrapper.findAll('li').find((li) => li.text().includes('abcdef12'))!
    await otherRow.find('button').trigger('click')
    await flushPromises()

    expect(calls).toContainEqual({ url: '/api/me/sessions/sid-other', method: 'DELETE' })
    expect(calls.some((c) => c.url === '/api/auth/logout')).toBe(false)
    expect(currentUser.value).not.toBeNull()
    expect(wrapper.text()).toContain('该设备已登出')
  })

  test('撤销本机需二次确认：首次点击只变按钮，不发请求', async () => {
    const { calls } = stubFetch([THIS_DEVICE])
    const wrapper = mount(MySessionsCard)
    currentUser.value = asUser('acct-1', 'a@test.local')
    await flushPromises()
    const before = calls.length

    await wrapper.findAll('li')[0].find('button').trigger('click')
    await flushPromises()

    expect(calls.length).toBe(before)
    expect(wrapper.text()).toContain('确认登出本机')
  })

  test('列表展示登录会话的自然过期日', async () => {
    const { calls } = stubFetch([THIS_DEVICE])
    void calls
    const wrapper = mount(MySessionsCard)
    currentUser.value = asUser('acct-1', 'a@test.local')
    await flushPromises()

    const expected = new Date(THIS_DEVICE.expiresAt as string).toLocaleDateString()
    expect(wrapper.text()).toContain(`有效期至 ${expected}`)
  })

  test('一键登出其它设备：二次确认后发 DELETE 集合端点且本机保留', async () => {
    const { calls } = stubFetch([THIS_DEVICE, OTHER_DEVICE])
    const wrapper = mount(MySessionsCard)
    currentUser.value = asUser('acct-1', 'a@test.local')
    await flushPromises()

    const bulkButton = wrapper.findAll('button').find((b) => b.text().includes('登出其它设备'))!
    await bulkButton.trigger('click')   // 第一次：进入确认态，不发请求
    await flushPromises()
    expect(calls.some((c) => c.url === '/api/me/sessions' && c.method === 'DELETE')).toBe(false)

    const confirmButton = wrapper.findAll('button').find((b) => b.text().includes('确认登出其它'))!
    await confirmButton.trigger('click')
    await flushPromises()

    expect(calls).toContainEqual({ url: '/api/me/sessions', method: 'DELETE' })
    expect(wrapper.text()).toContain('已登出其它 1 台设备')
    // 本机不被登出：没有 logout、没有 loggedOut 事件。
    expect(calls.some((c) => c.url === '/api/auth/logout')).toBe(false)
    expect(wrapper.emitted('loggedOut')).toBeUndefined()
    expect(currentUser.value).not.toBeNull()
  })

  test('仅一台设备时不显示「登出其它设备」入口', async () => {
    stubFetch([THIS_DEVICE])
    const wrapper = mount(MySessionsCard)
    currentUser.value = asUser('acct-1', 'a@test.local')
    await flushPromises()
    expect(wrapper.text()).not.toContain('登出其它设备')
  })

  test('确认后撤销本机：发 DELETE 并同步清掉前端登录态 + 抛 loggedOut', async () => {
    const { calls } = stubFetch([THIS_DEVICE])
    const wrapper = mount(MySessionsCard)
    currentUser.value = asUser('acct-1', 'a@test.local')
    await flushPromises()

    await wrapper.findAll('li')[0].find('button').trigger('click')   // 第一次：进入确认态
    await flushPromises()
    await wrapper.findAll('li')[0].find('button').trigger('click')   // 第二次：真的撤销
    await flushPromises()

    expect(calls).toContainEqual({ url: '/api/me/sessions/sid-current', method: 'DELETE' })
    expect(calls.some((c) => c.url === '/api/auth/logout')).toBe(true)
    expect(currentUser.value).toBeNull()
    expect(wrapper.emitted('loggedOut')).toHaveLength(1)
  })
})
