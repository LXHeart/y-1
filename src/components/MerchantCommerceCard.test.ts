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
})
