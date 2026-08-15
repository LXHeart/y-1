import { afterEach, describe, expect, it, vi } from 'vitest'
import { useCreditsPackages } from './useCreditsPackages'

afterEach(() => vi.unstubAllGlobals())

function stubFetch(data: unknown, ok = true) {
  const fn = vi.fn(async (_url: string, _init?: RequestInit) =>
    new Response(JSON.stringify(ok ? { success: true, data } : data), {
      status: ok ? 200 : 409,
      headers: { 'Content-Type': 'application/json' },
    }))
  vi.stubGlobal('fetch', fn)
  return fn
}

describe('useCreditsPackages', () => {
  it('loadPackages：GET /api/credits/packages，承载 active 列表', async () => {
    const fn = stubFetch([
      { id: 'p1', name: '体验包', description: '', priceCents: 990, creditsAmount: 10 },
    ])
    const packages = useCreditsPackages()
    await packages.loadPackages()

    expect(fn.mock.calls[0][0]).toBe('/api/credits/packages')
    expect(packages.packages.value).toHaveLength(1)
    expect(packages.packages.value[0]).toMatchObject({ id: 'p1', priceCents: 990 })
    expect(packages.error.value).toBe('')
  })

  it('purchase：POST purchase-orders，body 带 packageId，成功返回余额', async () => {
    const fn = stubFetch({
      orderId: 'o1', status: 'paid', creditsAmount: 10, balance: 15,
    })
    const packages = useCreditsPackages()
    const balance = await packages.purchase('p1')

    const [url, init] = fn.mock.calls[0]
    expect(url).toBe('/api/credits/purchase-orders')
    expect(init!.method).toBe('POST')
    expect(JSON.parse(init!.body as string).packageId).toBe('p1')
    expect(balance).toBe(15)
    expect(packages.purchasing.value).toBe(false)
  })

  it('purchase：非 2xx 时错误消息进 error，余额返回 null', async () => {
    stubFetch({ success: false, error: '积分包不在售' }, false)
    const packages = useCreditsPackages()
    const balance = await packages.purchase('p1')

    expect(balance).toBeNull()
    expect(packages.error.value).toBe('积分包不在售')
  })

  it('loadOrders：GET purchase-orders，承载列表', async () => {
    const fn = stubFetch([
      { id: 'o1', packageId: 'p1', priceCents: 990, creditsAmount: 10, status: 'paid' },
    ])
    const packages = useCreditsPackages()
    await packages.loadOrders()

    expect(fn.mock.calls[0][0]).toBe('/api/credits/purchase-orders')
    expect(packages.orders.value[0].status).toBe('paid')
  })
})
