// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import StoreStaffCard from './StoreStaffCard.vue'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

/**
 * 店长视图的「本店员工」卡（任务书 #52 池模型收敛后）：纯管理面——列本店成员、
 * 停用/恢复；建号/分配/调度/移除/审核全部收归主体管理员。走全局 fetch 桩。
 */

const ORG_ID = 'org-1'
const STORE_ID = 'store-1'

function envelopeResponse(payload: unknown, status = 200): Response {
  const body = status >= 400 ? { success: false, error: payload } : { success: true, data: payload }
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
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

describe('StoreStaffCard · 店长视图（任务书 #52 池模型收敛）', () => {
  test('列员工带账号名与状态；单店无选店列表', async () => {
    const { wrapper } = await mountedWith(baseHandler)
    expect(wrapper.text()).toContain('caoyuan-li1')
    expect(wrapper.text()).toContain('正常')
    expect(wrapper.find('.staff-store-list').exists()).toBe(false)
    expect(wrapper.text()).toContain('旗舰店')
  })

  test('#52 决策 A：无建店员入口、无审核入口、无一次性密码区；说明指向主体', async () => {
    const { wrapper, calls } = await mountedWith(baseHandler)
    expect(wrapper.find('[data-testid="one-time-password"]').exists()).toBe(false)
    expect(wrapper.findAll('input').length).toBe(0)
    expect(wrapper.findAll('button').some((b) => b.text() === '创建店员账号')).toBe(false)
    expect(wrapper.findAll('button').some((b) => b.text() === '通过')).toBe(false)
    expect(wrapper.text()).toContain('由主体管理员统一创建与分配')
    // 纯读挂载只应发生一次成员列表请求
    expect(calls.filter((c) => !c.method || c.method === 'GET').length).toBe(1)
  })

  test('停用/恢复走 /suspend、/restore；无删除与调度入口（主体级动作）', async () => {
    const { wrapper, calls } = await mountedWith((url, opts) => {
      if ((opts?.method ?? 'GET') === 'POST' && url.endsWith(`/api/organizations/${ORG_ID}/accounts/acc-staff/suspend`)) {
        return envelopeResponse({ success: true })
      }
      if ((opts?.method ?? 'GET') === 'POST' && url.endsWith(`/api/organizations/${ORG_ID}/accounts/acc-staff/restore`)) {
        return envelopeResponse({ success: true })
      }
      return baseHandler(url, opts)
    })

    const suspend = wrapper.findAll('button').find((b) => b.text() === '停用账号')
    expect(suspend).toBeTruthy()
    await suspend!.trigger('click')
    await flushPromises()
    expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/accounts/acc-staff/suspend'))).toBe(true)
    expect(wrapper.text()).toContain('账号已停用')

    expect(wrapper.findAll('button').some((b) => b.text() === '删除')).toBe(false)
    expect(wrapper.findAll('button').some((b) => b.text() === '调度')).toBe(false)
    expect(wrapper.findAll('button').some((b) => b.text() === '移除')).toBe(false)
  })
})
