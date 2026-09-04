// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import OpsApp from './OpsApp.vue'
import router from './router'
import { useAuth } from '../composables/useAuth'

// 治理视图体量大且各有测试：这里只测外壳分流，mock 掉两个路由视图
vi.mock('./admin/AdminView.vue', () => ({ __esModule: true, default: { template: '<div data-testid="ops-admin" />' } }))
vi.mock('./ops-console/OpsConsole.vue', () => ({ __esModule: true, default: { template: '<div data-testid="ops-console" />' } }))

/**
 * 治理台外壳特征测试：登录态按后端角色分流导航与路由可见性。
 * （原用户端「平台管理入口仅对 platform_admin 可见」的断言迁来并加强——
 * 治理台现在是这些入口的唯一宿主。）
 */

function response(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

function stubFetch(user: unknown): void {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/api/auth/me') {
      return user ? response({ success: true, data: { user } }) : response({ success: false }, 401)
    }
    return response({ success: true, data: [] })
  }))
}

async function mountOpsApp(): Promise<ReturnType<typeof mount>> {
  await router.push('/')
  await router.isReady()
  const wrapper = mount(OpsApp, { global: { plugins: [router], stubs: { Teleport: true } } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  useAuth().currentUser.value = null
  useAuth().loaded.value = false
})

afterEach(() => {
  useAuth().currentUser.value = null
  vi.unstubAllGlobals()
})

enableAutoUnmount(afterEach)

describe('治理台角色分流', () => {
  test('未登录只显示登录按钮，不暴露任何治理导航', async () => {
    stubFetch(null)
    const wrapper = await mountOpsApp()

    expect(wrapper.find('.ops-nav').exists()).toBe(false)
    expect(wrapper.get('.ops-button-primary').text()).toBe('登录')
  })

  test('platform_admin 可见管理后台与运营处置', async () => {
    stubFetch({ id: 'a-1', email: 'a@example.com', role: 'admin', roles: ['platform_admin'] })
    await useAuth().loadCurrentUser(true)
    const wrapper = await mountOpsApp()

    const nav = wrapper.get('.ops-nav').text()
    expect(nav).toContain('管理后台')
    expect(nav).toContain('运营处置')
    expect(wrapper.find('.ops-empty').exists()).toBe(false)
  })

  test('content_reviewer 只见管理后台；customer_service 双入口；risk 只见管理后台', async () => {
    stubFetch({ id: 'r-1', email: 'r@example.com', role: 'user', roles: ['content_reviewer'] })
    await useAuth().loadCurrentUser(true)
    const wrapper = await mountOpsApp()

    expect(wrapper.get('.ops-nav').text()).toContain('管理后台')
    expect(wrapper.get('.ops-nav').text()).not.toContain('运营处置')
    useAuth().currentUser.value = null

    // 任务书 #72 卡C D4：客服/风控进管理后台（AdminView 内只见「用户管理」页签）
    stubFetch({ id: 'c-1', email: 'c@example.com', role: 'customer_service', roles: ['customer_service'] })
    useAuth().loaded.value = false
    await useAuth().loadCurrentUser(true)
    await flushPromises()

    expect(wrapper.get('.ops-nav').text()).toContain('运营处置')
    expect(wrapper.get('.ops-nav').text()).toContain('管理后台')

    useAuth().currentUser.value = null
    stubFetch({ id: 'k-1', email: 'k@example.com', role: 'user', roles: ['risk'] })
    useAuth().loaded.value = false
    await useAuth().loadCurrentUser(true)
    await flushPromises()

    expect(wrapper.get('.ops-nav').text()).toContain('管理后台')
    expect(wrapper.get('.ops-nav').text()).not.toContain('运营处置')
  })

  test('无治理角色的账号落路由时显示无权限态', async () => {
    stubFetch({ id: 'u-1', email: 'u@example.com', role: 'user', roles: [] })
    await useAuth().loadCurrentUser(true)
    const wrapper = await mountOpsApp()

    // nav 容器对已登录用户渲染，但没有任何可见入口
    expect(wrapper.find('.ops-nav button').exists()).toBe(false)
    expect(wrapper.get('.ops-empty').text()).toContain('无访问权限')
  })

  test('登录弹窗隐藏注册入口（治理账号由平台开通）', async () => {
    stubFetch(null)
    const wrapper = await mountOpsApp()

    await wrapper.get('.ops-button-primary').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(wrapper.find('.login-mode-switch').exists()).toBe(false)
  })
})
