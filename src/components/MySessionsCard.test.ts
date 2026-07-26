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
    const isList = url === '/api/me/sessions' && (init?.method || 'GET') === 'GET'
    return {
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => (isList ? { success: true, data: devices } : { success: true }),
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
