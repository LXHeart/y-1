// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import GrasslandWorkbench from './GrasslandWorkbench.vue'
import { useAuth } from '../composables/useAuth'
import type { AuthUser } from '../types/auth'

/**
 * 工作台**登录态**回归测试。
 *
 * 与 `MyInvitationsCard.test.ts` 同源缺陷：工作台此前只在 `onMounted` 初始化
 * （激活活动身份 + 拉组织/余额/任务），而它在未登录时就已挂载、切标签页又不重挂载——
 * 同一页面内登录或换账号后，界面上还是上一个账号的数据（或空白），必须刷新整页。
 * 活动身份按 session 存，换账号不重新激活还会让商家操作 403。
 */

const { currentUser } = useAuth()

function asUser(id: string, email: string): AuthUser {
  return { id, email, displayName: email, role: 'user' }
}

const ORG = {
  id: 'org-1',
  ownerAccountId: 'acct-1',
  name: '示例商家',
  status: 'active',
  permissionTier: 'finance_transaction',
  industry: 'other',
  createdAt: null,
}

function dataFor(url: string): unknown {
  if (url === '/api/organizations') return [ORG]
  if (url.startsWith('/api/tasks')) return []
  if (url.startsWith('/api/finance/accounts')) return { organizationId: 'org-1', balanceCents: 100000 }
  return {}
}

function stubFetch(): { urls: string[] } {
  const urls: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    urls.push(url)
    return {
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: true, data: dataFor(url) }),
    }
  }))
  return { urls }
}

// 必须自动卸载：组件监听 useAuth 的模块级 currentUser，残留实例会响应后续用例的登录事件。
enableAutoUnmount(afterEach)

beforeEach(() => {
  currentUser.value = null
})

afterEach(() => {
  vi.unstubAllGlobals()
  currentUser.value = null
})

describe('GrasslandWorkbench 登录态', () => {
  test('未登录时不发任何请求', async () => {
    const { urls } = stubFetch()

    mount(GrasslandWorkbench)
    await flushPromises()

    expect(urls).toEqual([])
  })

  test('同一页面内登录后自动激活身份并拉组织（原缺陷：需刷新整页）', async () => {
    const { urls } = stubFetch()
    const wrapper = mount(GrasslandWorkbench)
    await flushPromises()

    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()

    // 顺序关键：先激活活动身份，再拉数据，否则商家接口 403
    expect(urls[0]).toBe('/api/me/active-identity')
    expect(urls).toContain('/api/organizations')
    expect(wrapper.text()).toContain('示例商家')
  })

  test('登出清空组织/余额/任务，不留上一个账号的数据', async () => {
    stubFetch()
    const wrapper = mount(GrasslandWorkbench)

    currentUser.value = asUser('acct-1', 'merchant@test.local')
    await flushPromises()
    expect(wrapper.text()).toContain('示例商家')

    currentUser.value = null
    await flushPromises()

    expect(wrapper.text()).not.toContain('示例商家')
    expect(wrapper.text()).toContain('¥—')  // 余额回到未知态
  })

  test('换账号重新激活身份并重拉（不沿用上一个账号的组织）', async () => {
    const { urls } = stubFetch()
    mount(GrasslandWorkbench)

    currentUser.value = asUser('acct-1', 'a@test.local')
    await flushPromises()
    const firstRound = urls.length
    currentUser.value = asUser('acct-2', 'b@test.local')
    await flushPromises()

    expect(urls.slice(firstRound)).toContain('/api/me/active-identity')
    expect(urls.slice(firstRound)).toContain('/api/organizations')
  })
})
