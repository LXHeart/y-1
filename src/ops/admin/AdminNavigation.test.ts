// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import AdminView from './AdminView.vue'
import { useAuth } from '../../composables/useAuth'

vi.mock('./adminTabs', async (importOriginal) => {
  const original = await importOriginal<typeof import('./adminTabs')>()
  return { ...original, TAB_REGISTRY: original.TAB_REGISTRY.map((tab) => ({
    ...tab, component: { template: '<div>业务面板</div>' },
  })) }
})
enableAutoUnmount(afterEach)
beforeEach(() => {
  useAuth().currentUser.value = { id: 'admin', email: 'admin@example.com', role: 'admin', roles: ['platform_admin'] }
})
afterEach(() => { useAuth().currentUser.value = null })
async function setup(path = '/admin?section=tasks&scope=all#queue') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/admin', component: AdminView }] })
  await router.push(path)
  const wrapper = mount(AdminView, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
  return { router, wrapper }
}
test('路由是深链接和前进后退的同一来源，保留其他 query 与 hash', async () => {
  const { router, wrapper } = await setup()
  expect(wrapper.get('[data-testid="admin-tab-tasks"]').attributes('aria-selected')).toBe('true')
  await wrapper.get('[data-testid="admin-group-users-org"]').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/admin?section=users&scope=all#queue')
  router.back()
  await flushPromises()
  expect(wrapper.get('[data-testid="admin-tab-tasks"]').attributes('aria-selected')).toBe('true')
  router.forward()
  await flushPromises()
  expect(wrapper.get('[data-testid="admin-tab-users"]').attributes('aria-selected')).toBe('true')
})
test('返回业务组时恢复上次使用的页签', async () => {
  const { wrapper } = await setup()
  await wrapper.get('[data-testid="admin-tab-recommenders"]').trigger('click')
  await flushPromises()
  await wrapper.get('[data-testid="admin-group-users-org"]').trigger('click')
  await flushPromises()
  await wrapper.get('[data-testid="admin-group-review"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[data-testid="admin-tab-recommenders"]').attributes('aria-selected')).toBe('true')
})
test('不可访问的页签规范为首个有权页签', async () => {
  useAuth().currentUser.value = { id: 'cs', email: 'cs@example.com', role: 'user', roles: ['customer_service'] }
  const { router, wrapper } = await setup('/admin?section=finance&scope=all')
  expect(router.currentRoute.value.query).toEqual({ section: 'users', scope: 'all' })
  expect(wrapper.find('[data-testid="admin-tab-finance"]').exists()).toBe(false)
})
test('侧栏页签支持方向键，移动导航可用 Escape 关闭并归还焦点', async () => {
  const { wrapper } = await setup('/admin?section=kyb')
  await wrapper.get('[data-testid="admin-tab-kyb"]').trigger('keydown', { key: 'ArrowDown' })
  await flushPromises()
  expect(wrapper.get('[data-testid="admin-tab-org-renames"]').attributes('tabindex')).toBe('0')
  await wrapper.get('[aria-label="展开管理导航"]').trigger('click')
  expect(wrapper.find('.admin-sidebar-open').exists()).toBe(true)
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
  await flushPromises()
  expect(wrapper.find('.admin-sidebar-open').exists()).toBe(false)
  expect(document.activeElement).toBe(wrapper.get('[aria-label="展开管理导航"]').element)
})
