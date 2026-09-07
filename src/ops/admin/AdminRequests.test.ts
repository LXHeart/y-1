// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import AdminView from './AdminView.vue'
import { useAuth } from '../../composables/useAuth'

enableAutoUnmount(afterEach)
beforeEach(() => {
  useAuth().currentUser.value = { id: 'admin', email: 'ops@example.com', role: 'admin', roles: ['platform_admin'] }
})
afterEach(() => { vi.unstubAllGlobals(); useAuth().currentUser.value = null })
function response(data: unknown) {
  return new Response(JSON.stringify({ success: true, data }), { headers: { 'Content-Type': 'application/json' } })
}
function paged(items: unknown[]) { return { items, total: items.length, limit: 10, offset: 0 } }
function deferred() {
  let resolve!: (value: Response) => void
  const promise = new Promise<Response>((done) => { resolve = done })
  return { promise, resolve }
}
function mountSection(section: string) {
  window.history.replaceState(null, '', `/?section=${section}`)
  return mount(AdminView, { global: { stubs: { Teleport: true } } })
}

test('用户搜索逆序响应时保留最新查询结果', async () => {
  const older = deferred()
  const user = { id: 'new-user', email: 'new@example.com', displayName: '最新账号', role: 'user', status: 'active', balance: 10, createdAt: null }
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url.includes('q=old')) return older.promise
    return response(paged(url.includes('q=new') ? [user] : []))
  }))
  const wrapper = mountSection('users')
  await flushPromises()
  const input = wrapper.get('input[placeholder="搜索邮箱、昵称或账号 ID"]')
  await input.setValue('old')
  await wrapper.get('form.search-toolbar').trigger('submit')
  await input.setValue('new')
  await wrapper.get('form.search-toolbar').trigger('submit')
  await flushPromises()
  older.resolve(response(paged([{ ...user, id: 'old-user', displayName: '过期账号' }])))
  await flushPromises()
  expect(wrapper.text()).toContain('最新账号')
  expect(wrapper.text()).not.toContain('过期账号')
})

test('任务切换状态时迟到的待审响应不会覆盖已驳回结果', async () => {
  const older = deferred()
  const task = { id: 'task', title: '已驳回的任务', status: 'draft', organizationId: 'org', version: 2, lastReviewNote: '材料不足' }
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url.endsWith('/stats')) return response({ pending: 1, overdue: 0, approvedLast24Hours: 0, rejectedLast24Hours: 1 })
    if (url.includes('status=pending_review')) return older.promise
    return response(paged([task]))
  }))
  const wrapper = mountSection('tasks')
  await flushPromises()
  await wrapper.findAll('.status-pill').find((button) => button.text() === '已驳回')!.trigger('click')
  await flushPromises()
  older.resolve(response(paged([{ ...task, title: '旧的待审任务' }])))
  await flushPromises()
  expect(wrapper.text()).toContain('已驳回的任务')
  expect(wrapper.text()).not.toContain('旧的待审任务')
  expect(wrapper.find('.approve-btn').exists()).toBe(false)
})

test.each(['tasks', 'recommenders'])('%s 审批进行中锁定所有行操作，避免连续提交相反结论', async (section) => {
  const mutation = deferred()
  let posts = 0
  const item = { id: 'request', accountId: 'account', title: '审核对象', organizationId: 'org', version: 1 }
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === 'POST') { posts++; return mutation.promise }
    if (url.endsWith('/stats')) return response({ pending: 1, overdue: 0, approvedLast24Hours: 0, rejectedLast24Hours: 0 })
    return response(paged(posts ? [] : [item]))
  }))
  const wrapper = mountSection(section)
  await flushPromises()
  await wrapper.get('.approve-btn').trigger('click')
  expect(wrapper.get('.approve-btn').attributes('disabled')).toBeDefined()
  expect(wrapper.get('.reject-btn').attributes('disabled')).toBeDefined()
  await wrapper.get('.approve-btn').trigger('click')
  await wrapper.get('.reject-btn').trigger('click')
  expect(posts).toBe(1)
  mutation.resolve(response({ ...item, status: 'approved' }))
  await flushPromises()
  expect(wrapper.find('.approve-btn').exists()).toBe(false)
})
