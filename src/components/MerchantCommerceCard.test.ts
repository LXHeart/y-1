// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import MerchantCommerceCard from './MerchantCommerceCard.vue'

enableAutoUnmount(afterEach)

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

function response(data: unknown): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('MerchantCommerceCard', () => {
  it('识别摄像头二维码后直接调用核销接口并关闭视频流', async () => {
    const stop = vi.fn()
    const stream = new MediaStream()
    Object.defineProperty(stream, 'getTracks', {
      configurable: true,
      value: () => [{ stop } as unknown as MediaStreamTrack],
    })
    vi.stubGlobal('navigator', {
      ...navigator,
      mediaDevices: { getUserMedia: vi.fn().mockResolvedValue(stream) },
      clipboard: { writeText: vi.fn() },
    })
    vi.stubGlobal('BarcodeDetector', class {
      async detect() { return [{ rawValue: 'GL-ABCDE-FGHIJ-KLMNO-PQRST' }] }
    })
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue()
    vi.spyOn(HTMLMediaElement.prototype, 'readyState', 'get').mockReturnValue(4)
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => {
      queueMicrotask(() => callback(0))
      return 1
    })
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => undefined)

    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.startsWith('/api/v2/merchant/packages?')) return response([])
      if (url.startsWith('/api/v2/merchant/orders?')) return response([])
      if (url === '/api/v2/merchant/redemptions' && init?.method === 'POST') {
        return response({ id: 'order-1', status: 'redeemed' })
      }
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(MerchantCommerceCard, { props: { organizationId: 'org-1' } })
    await flushPromises()
    await wrapper.get('.scanner-actions button').trigger('click')
    await flushPromises()
    await vi.waitFor(() => {
      expect(fetchMock.mock.calls.some(([url]) => url === '/api/v2/merchant/redemptions')).toBe(true)
    })

    const redemptionCall = fetchMock.mock.calls.find(([url]) => url === '/api/v2/merchant/redemptions')!
    expect(JSON.parse((redemptionCall?.[1] as RequestInit).body as string)).toEqual({
      code: 'GL-ABCDE-FGHIJ-KLMNO-PQRST',
    })
    expect(stop).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('核销成功，三方分账已完成')
  })

  it('分时段库存：保存时总库存取各时段之和并携带 inventorySlots', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.startsWith('/api/v2/merchant/packages?')) return response([])
      if (url.startsWith('/api/v2/merchant/orders?')) return response([])
      if (url === '/api/v2/merchant/packages' && init?.method === 'POST') {
        return response({ id: 'pkg-1', version: 1 })
      }
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(MerchantCommerceCard, { props: { organizationId: 'org-1' } })
    await flushPromises()

    await wrapper.get('input[placeholder="套餐名称"]').setValue('午市时段套餐')
    await wrapper.get('.slots-head button').trigger('click')
    await wrapper.get('.slots-head button').trigger('click')
    const rows = wrapper.findAll('.slot-row')
    await rows[0].findAll('input')[0].setValue('2026-09-01T10:00')
    await rows[0].findAll('input')[1].setValue('2026-09-01T11:00')
    await rows[0].findAll('input')[2].setValue('3')
    await rows[1].findAll('input')[0].setValue('2026-09-01T14:00')
    await rows[1].findAll('input')[1].setValue('2026-09-01T15:00')
    await rows[1].findAll('input')[2].setValue('2')

    const saveButton = wrapper.get('.actions button')
    expect(saveButton.attributes('disabled')).toBeUndefined()
    await saveButton.trigger('click')
    await flushPromises()

    const createCall = fetchMock.mock.calls.find(([url, init]) =>
      url === '/api/v2/merchant/packages' && init?.method === 'POST')
    expect(createCall).toBeDefined()
    const body = JSON.parse((createCall?.[1] as RequestInit).body as string)
    expect(body.totalStock).toBe(5)
    expect(body.inventorySlots).toHaveLength(2)
    expect(body.inventorySlots[0]).toMatchObject({ totalStock: 3 })
    // 组件按浏览器本地时区转 ISO（datetime input 语义）；期望值同源计算，
    // 不写死 +08 偏移（CI runner 是 UTC）。
    expect(body.inventorySlots[0].slotStart).toBe(new Date('2026-09-01T10:00').toISOString())
  })

  it('售后裁定：回显消费者申诉并按部分金额裁定退款', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.startsWith('/api/v2/merchant/packages?')) return response([])
      if (url.startsWith('/api/v2/merchant/orders?')) {
        return response([{
          id: 'order-1', consumerAccountId: 'consumer-1', organizationId: 'org-1',
          packageId: 'pkg-1', packageVersion: 1, packageTitle: '双人套餐',
          priceCents: 10000, recommenderAmountCents: 1000, merchantAmountCents: 8500,
          platformFeeCents: 500, status: 'after_sales_disputed',
          redeemDeadline: '2026-09-30T10:00:00Z', createdAt: '2026-08-16T02:00:00Z',
        }])
      }
      if (url === '/api/v2/orders/order-1/after-sales-dispute' && !init?.method) {
        return response({ id: 'dispute-1', orderId: 'order-1', status: 'open', reason: '到店后无法提供服务' })
      }
      if (url === '/api/v2/orders/order-1/after-sales-dispute/resolve' && init?.method === 'POST') {
        return response({ id: 'order-1', status: 'partially_refunded', refundedAmountCents: 3000 })
      }
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(MerchantCommerceCard, { props: { organizationId: 'org-1' } })
    await flushPromises()

    expect(wrapper.get('.compact-order.disputed').text()).toContain('售后争议')
    await wrapper.get('.dispute-handle button').trigger('click')
    await flushPromises()
    expect(wrapper.get('.resolve-form').text()).toContain('到店后无法提供服务')
    await wrapper.get('.resolve-row input').setValue('30')
    await wrapper.get('.resolve-row button').trigger('click')
    await flushPromises()

    const resolveCall = fetchMock.mock.calls.find(([url]) =>
      url === '/api/v2/orders/order-1/after-sales-dispute/resolve')
    expect(resolveCall).toBeDefined()
    expect(JSON.parse((resolveCall?.[1] as RequestInit).body as string)).toEqual({
      resolution: 'refund', amountCents: 3000, reason: 'reviewed',
    })
    expect(wrapper.text()).toContain('已裁定退款')
  })
})
