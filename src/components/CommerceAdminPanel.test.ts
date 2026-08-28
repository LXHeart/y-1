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

describe('CommerceAdminPanel', () => {
  it('同时加载订单列表与独立核销监控接口（分页信封）', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      // 任务 #3 分页契约：两个端点都带 limit/offset 查询串并返回 {items,total,limit,offset} 信封
      if (url.startsWith('/api/admin/commerce/orders')) return response({ items: [redeemedOrder], total: 1, limit: 50, offset: 0 })
      if (url.startsWith('/api/admin/commerce/redemptions')) return response({ items: [redeemedOrder], total: 1, limit: 50, offset: 0 })
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(CommerceAdminPanel)
    await flushPromises()

    expect(fetchMock.mock.calls.some(([url]) => String(url).startsWith('/api/admin/commerce/orders?limit=10&offset=0'))).toBe(true)
    expect(fetchMock.mock.calls.some(([url]) => String(url).startsWith('/api/admin/commerce/redemptions?limit=10&offset=0'))).toBe(true)
    expect(wrapper.text()).toContain('核销与分账流水')
    expect(wrapper.text()).toContain('已完成三方分账')
    expect(wrapper.text()).toContain('双人到店套餐')
  })
})
