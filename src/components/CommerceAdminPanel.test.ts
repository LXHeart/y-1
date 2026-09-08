// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CommerceAdminPanel from './CommerceAdminPanel.vue'

enableAutoUnmount(afterEach)

afterEach(() => vi.unstubAllGlobals())

function response(data: unknown): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

const redeemedOrder = {
  id: 'order-redeemed-1', consumerAccountId: 'consumer-1', organizationId: 'org-1', storeId: 'store-1',
  packageId: 'package-1', packageVersion: 2, packageTitle: '双人到店套餐', priceCents: 12800,
  recommenderAmountCents: 1280, merchantAmountCents: 10880, platformFeeCents: 640,
  status: 'redeemed', redeemDeadline: '2026-09-01T00:00:00Z', createdAt: '2026-08-11T00:00:00Z',
  paidAt: '2026-08-11T00:01:00Z', redeemedAt: '2026-08-11T00:05:00Z',
}

const openAppeal = {
  id: 'appeal-1', orderId: 'order-appeal-1', consumerAccountId: 'consumer-2',
  claimedRecommenderAccountId: 'recommender-claimed-1', reason: '实际经另一位推荐官的链接购买',
  status: 'open', createdAt: '2026-09-08T00:00:00Z',
}

function stubAll(handlers: Record<string, (url: string, init?: RequestInit) => unknown>) {
  return vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
    for (const [prefix, handler] of Object.entries(handlers)) {
      if (String(url).startsWith(prefix)) return handler(String(url), init)
    }
    throw new Error(`unexpected request: ${url}`)
  })
}

const emptyPage = response({ items: [], total: 0, limit: 50, offset: 0 })

describe('CommerceAdminPanel', () => {
  it('同时加载订单列表、独立核销监控与归因申诉队列（分页信封）', async () => {
    const fetchMock = stubAll({
      '/api/admin/commerce/orders': () => response({ items: [redeemedOrder], total: 1, limit: 50, offset: 0 }),
      '/api/admin/commerce/redemptions': () => response({ items: [redeemedOrder], total: 1, limit: 50, offset: 0 }),
      '/api/admin/commerce/attribution-appeals': () => response({ items: [openAppeal], total: 1, limit: 50, offset: 0 }),
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(CommerceAdminPanel)
    await flushPromises()

    expect(fetchMock.mock.calls.some(([url]) => String(url).startsWith('/api/admin/commerce/orders?limit=10&offset=0'))).toBe(true)
    expect(fetchMock.mock.calls.some(([url]) => String(url).startsWith('/api/admin/commerce/redemptions?limit=10&offset=0'))).toBe(true)
    expect(fetchMock.mock.calls.some(([url]) => String(url).startsWith('/api/admin/commerce/attribution-appeals?status=open&limit=10&offset=0'))).toBe(true)
    expect(wrapper.text()).toContain('核销与分账流水')
    expect(wrapper.text()).toContain('归因申诉队列')
    expect(wrapper.text()).toContain('实际经另一位推荐官的链接购买')
    expect(wrapper.text()).toContain('已完成三方分账')
    expect(wrapper.text()).toContain('双人到店套餐')
  })

  it('通过申诉=运营纠错端点（目标取主张推荐官，不传分成），驳回=独立端点', async () => {
    const correction = vi.fn().mockReturnValue(response(redeemedOrder))
    const rejection = vi.fn().mockReturnValue(response({ ...openAppeal, status: 'rejected' }))
    const fetchMock = stubAll({
      // 具体路径须在泛前缀之前声明（Object.entries 按插入顺序匹配）。
      '/api/admin/commerce/orders/order-appeal-1/attribution-correction': (url, init) => {
        correction(url, init)
        return response(redeemedOrder)
      },
      '/api/admin/commerce/attribution-appeals/appeal-1/reject': (url, init) => {
        rejection(url, init)
        return response({ ...openAppeal, status: 'rejected' })
      },
      '/api/admin/commerce/orders': () => emptyPage,
      '/api/admin/commerce/redemptions': () => emptyPage,
      '/api/admin/commerce/attribution-appeals?status=open': () => response({ items: [openAppeal], total: 1, limit: 50, offset: 0 }),
      '/api/admin/commerce/attribution-appeals?status=all': () => emptyPage,
      '/api/admin/commerce/attribution-appeals?status=rejected': () => emptyPage,
      '/api/admin/commerce/attribution-appeals?status=applied': () => emptyPage,
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(CommerceAdminPanel)
    await flushPromises()

    // 处置说明必填：空说明直接拦截，不发请求。
    await wrapper.find('td input').setValue('')
    await wrapper.findAll('button').find(b => b.text() === '按冻结规则改绑')!.trigger('click')
    await flushPromises()
    expect(correction).not.toHaveBeenCalled()

    await wrapper.find('td input').setValue('证据核实，改绑')
    await wrapper.findAll('button').find(b => b.text() === '按冻结规则改绑')!.trigger('click')
    await flushPromises()
    expect(correction).toHaveBeenCalledTimes(1)
    const body = JSON.parse((correction.mock.calls[0][1] as RequestInit).body as string)
    expect(body).toEqual({ recommenderAccountId: 'recommender-claimed-1', reason: '证据核实，改绑', appealId: 'appeal-1' })
    expect(body.recommenderShareBps).toBeUndefined()

    // 驳回走独立端点并回显说明。
    await wrapper.find('td input').setValue('证据不足')
    await wrapper.findAll('button').find(b => b.text() === '驳回')!.trigger('click')
    await flushPromises()
    expect(rejection).toHaveBeenCalledTimes(1)
    expect(JSON.parse((rejection.mock.calls[0][1] as RequestInit).body as string)).toEqual({ note: '证据不足' })
  })

  // ---------- 任务书 #98 C98-02：推广链接生命周期查询 ----------

  it('按 rlid 查询生命周期：状态/失效原因/触达数/归因订单', async () => {
    const lifecycleFetch = vi.fn().mockImplementation(async (url: string) => {
      if (String(url).startsWith('/api/admin/commerce/referral-links/rlLink98')) {
        return response({
          link: {
            referralLinkId: 'rlLink98', taskId: 'task-98', packageId: 'pkg-98',
            url: '/?view=commerce&package=pkg-98&rlid=rlLink98', status: 'ended', endedReason: 'manual',
            createdAt: '2026-09-01T00:00:00Z', expiresAt: '2026-11-30T00:00:00Z', policyVersion: 'last_touch_7d_v1',
          },
          touchCount: 3,
          recentTouches: [
            { touchedAt: '2026-09-08T00:00:00Z', consumerAccountId: 'consumer-98', context: 'landing' },
            { touchedAt: '2026-09-07T00:00:00Z', consumerAccountId: null, context: 'landing' },
          ],
          orders: [{ orderId: 'order-98', status: 'redeemed', priceCents: 12800, recommenderAmountCents: 1280,
            createdAt: '2026-09-08T03:00:00Z' }],
        })
      }
      if (String(url).startsWith('/api/admin/commerce/orders')) return emptyPage
      if (String(url).startsWith('/api/admin/commerce/redemptions')) return emptyPage
      if (String(url).startsWith('/api/admin/commerce/attribution-appeals')) return emptyPage
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', lifecycleFetch)
    const wrapper = mount(CommerceAdminPanel)
    await flushPromises()

    await wrapper.get('[data-testid="referral-link-query"]').setValue('rlLink98')
    await wrapper.findAll('button').find(b => b.text() === '查询生命周期')!.trigger('click')
    await flushPromises()

    const result = wrapper.get('[data-testid="referral-lifecycle"]')
    expect(result.text()).toContain('已终止')
    expect(result.text()).toContain('本人终止')
    expect(result.text()).toContain('3 次')
    expect(result.text()).toContain('¥128.00')
    expect(lifecycleFetch.mock.calls.some(([url]) => String(url).endsWith('/referral-links/rlLink98'))).toBe(true)
  })
})
