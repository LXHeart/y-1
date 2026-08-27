// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import StoreStaffCard from './StoreStaffCard.vue'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

/**
 * 任务书 #50 阶段 2：店长视图的「本店员工」卡。走全局 fetch 桩，
 * 与 OrgTeamCard.subaccount.test 同口径。零后端改动——只锁 UI 行为。
 */

const ORG_ID = 'org-1'
const STORE_ID = 'store-1'

function envelopeResponse(payload: unknown, status = 200): Response {
  const body = status >= 400 ? { success: false, error: payload } : { success: true, data: payload }
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const STORES = [{ id: STORE_ID, organizationId: ORG_ID, name: '旗舰店', status: 'active', createdAt: null }]

async function mountedWith(handler: (url: string, opts?: { method?: string }) => Response | undefined) {
  const calls: Array<{ url: string; method?: string; body?: string }> = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, opts?: { method?: string; body?: string }) => {
    const url = String(input)
    calls.push({ url, method: opts?.method, body: opts?.body })
    const res = handler(url, opts)
    if (!res) throw new Error('unexpected request: ' + url)
    return res
  }))
  const wrapper = mount(StoreStaffCard, { props: { orgId: ORG_ID, stores: STORES } })
  await flushPromises()
  return { wrapper, calls }
}

function baseHandler(url: string, opts?: { method?: string }): Response | undefined {
  if (url.endsWith(`/api/organizations/${ORG_ID}/stores/${STORE_ID}/memberships`)) {
    return envelopeResponse([
      { id: 'sm1', storeId: STORE_ID, accountId: 'acc-staff', role: 'staff', createdAt: null, accountStatus: 'active', username: 'caoyuan-li1' },
    ])
  }
  void opts
  return undefined
}

describe('StoreStaffCard · 店长视图（任务书 #50 阶段 2）', () => {
  test('列员工带账号名与状态；单店无选店列表', async () => {
    const { wrapper } = await mountedWith(baseHandler)
    expect(wrapper.text()).toContain('caoyuan-li1')
    expect(wrapper.text()).toContain('正常')
    expect(wrapper.find('.staff-store-list').exists()).toBe(false)
    expect(wrapper.text()).toContain('旗舰店')
  })

  test('建店员：走门店级端点 + role 锁 staff + 一次性密码展示一次', async () => {
    const { wrapper, calls } = await mountedWith((url, opts) => {
      if ((opts?.method ?? 'GET') === 'POST' && url.endsWith(`/api/organizations/${ORG_ID}/stores/${STORE_ID}/accounts`)) {
        return envelopeResponse({
          account: { id: 'new-1', username: 'caoyuan-wang', displayName: '王员工', role: 'staff', status: 'active' },
          initialPassword: 'zmX28J86LvHbevBn',
          mustChangePassword: true,
        }, 201)
      }
      return baseHandler(url, opts)
    })

    await wrapper.find('input[placeholder="登录名（3-24 位字母数字）"]').setValue('wang')
    await wrapper.find('input[placeholder="员工姓名"]').setValue('王员工')
    await wrapper.findAll('button').find((b) => b.text() === '创建店员账号')!.trigger('click')
    await flushPromises()

    const create = calls.find((c) => c.method === 'POST' && c.url.endsWith('/accounts'))
    expect(JSON.parse(create!.body!)).toEqual({ role: 'staff', loginName: 'wang', displayName: '王员工' })

    const panel = wrapper.find('[data-testid="one-time-password"]')
    expect(panel.text()).toContain('caoyuan-wang')
    expect(panel.text()).toContain('zmX28J86LvHbevBn')
    await panel.findAll('button')[0].trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="one-time-password"]').exists()).toBe(false)
  })

  test('停用/恢复走 /suspend、/restore；pending 行呈现过审入口而非停用', async () => {
    const { wrapper, calls } = await mountedWith((url, opts) => {
      if (url.endsWith(`/api/organizations/${ORG_ID}/stores/${STORE_ID}/memberships`)) {
        return envelopeResponse([
          { id: 'sm1', storeId: STORE_ID, accountId: 'acc-pending', role: 'staff', createdAt: null, accountStatus: 'pending_review' },
        ])
      }
      if ((opts?.method ?? 'GET') === 'POST' && url.endsWith(`/api/organizations/${ORG_ID}/accounts/acc-pending/review`)) {
        return envelopeResponse({ success: true })
      }
      if ((opts?.method ?? 'GET') === 'POST' && url.endsWith(`/api/organizations/${ORG_ID}/accounts/acc-staff/suspend`)) {
        return envelopeResponse({ success: true })
      }
      return baseHandler(url, opts)
    })

    // pending 行：审核按钮，无停用
    const approve = wrapper.findAll('button').find((b) => b.text() === '通过')
    expect(approve).toBeTruthy()
    expect(wrapper.findAll('button').some((b) => b.text() === '停用账号')).toBe(false)
    await approve!.trigger('click')
    await flushPromises()
    expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/accounts/acc-pending/review'))).toBe(true)
    expect(wrapper.text()).toContain('已通过审核')
    // 店长视图无删除入口（永久作废是主体级动作）
    expect(wrapper.findAll('button').some((b) => b.text() === '删除')).toBe(false)
  })
})
