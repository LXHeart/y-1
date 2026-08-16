import { afterEach, describe, expect, it, vi } from 'vitest'
import { useCommerce } from './useCommerce'

afterEach(() => vi.restoreAllMocks())

describe('useCommerce', () => {
  it('freezes referral attribution in the create-order request contract', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      success: true,
      data: { id: 'order-1', status: 'paid' },
    }), { status: 201, headers: { 'Content-Type': 'application/json' } }))
    const commerce = useCommerce()

    const result = await commerce.createOrder('package-1', 'recommender-1')

    expect(result).toMatchObject({ id: 'order-1', status: 'paid' })
    expect(fetchMock).toHaveBeenCalledWith('/api/v2/orders', expect.objectContaining({
      method: 'POST',
      credentials: 'include',
      body: JSON.stringify({ packageId: 'package-1', recommenderAccountId: 'recommender-1' }),
    }))
  })

  it('surfaces redemption business errors without throwing into the component', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      success: false, error: '该核销码已使用',
    }), { status: 409, headers: { 'Content-Type': 'application/json' } }))
    const commerce = useCommerce()

    expect(await commerce.redeem('GL-USED')).toBeNull()
    expect(commerce.error.value).toBe('该核销码已使用')
  })

  it('loads the dedicated admin redemption monitor endpoint', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      success: true, data: [],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    const commerce = useCommerce()

    expect(await commerce.listAdminRedemptions()).toEqual([])
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/commerce/redemptions', expect.objectContaining({
      credentials: 'include',
    }))
  })

  it('keeps loading true until all parallel commerce requests settle', async () => {
    let resolveFirst!: (value: Response) => void
    let resolveSecond!: (value: Response) => void
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve }))
      .mockImplementationOnce(() => new Promise(resolve => { resolveSecond = resolve }))
    const commerce = useCommerce()

    const first = commerce.listAdminOrders()
    const second = commerce.listAdminRedemptions()
    expect(commerce.loading.value).toBe(true)

    resolveFirst(new Response(JSON.stringify({ success: true, data: [] }), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }))
    await first
    expect(commerce.loading.value).toBe(true)

    resolveSecond(new Response(JSON.stringify({ success: true, data: [] }), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }))
    await second
    expect(commerce.loading.value).toBe(false)
  })

  it('sends the booked inventory slot when ordering a time-slotted package', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      success: true, data: { id: 'order-1', status: 'paid' },
    }), { status: 201, headers: { 'Content-Type': 'application/json' } }))
    const commerce = useCommerce()

    await commerce.createOrder('package-1', undefined, 'slot-1')

    expect(fetchMock).toHaveBeenCalledWith('/api/v2/orders', expect.objectContaining({
      body: JSON.stringify({ packageId: 'package-1', inventorySlotId: 'slot-1' }),
    }))
  })

  it('reads the after-sales dispute detail from the dedicated endpoint', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      success: true, data: { id: 'dispute-1', orderId: 'order-1', status: 'open', reason: '到店后无法提供服务' },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    const commerce = useCommerce()

    const dispute = await commerce.getAfterSalesDispute('order-1')

    expect(dispute).toMatchObject({ id: 'dispute-1', status: 'open' })
    expect(fetchMock).toHaveBeenCalledWith('/api/v2/orders/order-1/after-sales-dispute', expect.objectContaining({
      credentials: 'include',
    }))
  })

  it('omits amountCents when resolving a dispute without an explicit amount', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      success: true, data: { id: 'order-1', status: 'partially_refunded' },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    const commerce = useCommerce()

    await commerce.resolveAfterSalesDispute('order-1', 'refund', undefined, '协商退款')

    expect(fetchMock).toHaveBeenCalledWith('/api/v2/orders/order-1/after-sales-dispute/resolve', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ resolution: 'refund', reason: '协商退款' }),
    }))
  })

  it('carries the partial refund amount in cents', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      success: true, data: { id: 'order-1', status: 'partially_refunded', refundedAmountCents: 2000 },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    const commerce = useCommerce()

    await commerce.refundOrder('order-1', 'consumer_request', 2000)

    expect(fetchMock).toHaveBeenCalledWith('/api/v2/orders/order-1/refund', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ reason: 'consumer_request', amountCents: 2000 }),
    }))
  })
})
