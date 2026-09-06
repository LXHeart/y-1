import { afterEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '../stores/auth'
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

describe('useCreditsPackages 账号边界（任务书 #82 C82-04）', () => {
  const userA = { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', email: 'a@qa.invalid', displayName: '甲', role: 'user' }
  const userB = { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', email: 'b@qa.invalid', displayName: '乙', role: 'user' }

  afterEach(() => {
    useAuthStore().currentUser = null
  })

  /** 手动放行式 fetch stub：每个请求挂起，直到测试按序 resolve。 */
  function stubDeferredFetch() {
    const resolvers: Array<(data: unknown, ok?: boolean) => void> = []
    vi.stubGlobal('fetch', vi.fn(() => new Promise((resolve) => {
      resolvers.push((data: unknown, ok = true) => resolve(
        new Response(JSON.stringify(ok ? { success: true, data } : data), {
          status: ok ? 200 : 500,
          headers: { 'Content-Type': 'application/json' },
        }),
      ))
    })))
    return { resolvers }
  }

  it('A 订单迟到不写入 B；换号清订单；ownerAccountId 可观察（E01）', async () => {
    const auth = useAuthStore()
    const { resolvers } = stubDeferredFetch()
    auth.currentUser = userA
    const pkgs = useCreditsPackages()
    expect(pkgs.ownerAccountId.value).toBe(userA.id)

    const aOrders = pkgs.loadOrders()
    auth.currentUser = userB
    expect(pkgs.ownerAccountId.value).toBe(userB.id)
    expect(pkgs.orders.value).toEqual([]) // 换号即清

    resolvers[0]([{ id: 'o1', packageId: 'p1', priceCents: 990, creditsAmount: 10, status: 'paid' }])
    await aOrders
    expect(pkgs.orders.value).toEqual([]) // A 的订单不写入 B
    expect(pkgs.loading.value).toBe(false)
    expect(pkgs.error.value).toBe('')
  })

  it('A 购买成功迟到：返回 null、不写 error、purchasing 已复位（E02）', async () => {
    const auth = useAuthStore()
    const { resolvers } = stubDeferredFetch()
    auth.currentUser = userA
    const pkgs = useCreditsPackages()
    const buying = pkgs.purchase('p1')

    auth.currentUser = userB
    resolvers[0]({ orderId: 'o9', status: 'paid', creditsAmount: 10, balance: 15 })
    await expect(buying).resolves.toBeNull()
    expect(pkgs.error.value).toBe('')
    expect(pkgs.purchasing.value).toBe(false) // B 可立即发起自己的购买
  })

  it('A 购买失败迟到：不把 A 的错误写给 B（E02）', async () => {
    const auth = useAuthStore()
    const { resolvers } = stubDeferredFetch()
    auth.currentUser = userA
    const pkgs = useCreditsPackages()
    const buying = pkgs.purchase('p1')

    auth.currentUser = userB
    resolvers[0]({ success: false, error: '积分包不在售' }, false)
    await expect(buying).resolves.toBeNull()
    expect(pkgs.error.value).toBe('')
  })

  it('换号清私有态但保留公共套餐目录（D82-02）', async () => {
    const auth = useAuthStore()
    auth.currentUser = userA
    const fn = vi.fn(async (url: string) => {
      const data = url === '/api/credits/packages'
        ? [{ id: 'p1', name: '体验包', description: '', priceCents: 990, creditsAmount: 10 }]
        : [{ id: 'o1', packageId: 'p1', priceCents: 990, creditsAmount: 10, status: 'paid' }]
      return new Response(JSON.stringify({ success: true, data }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fn)
    const pkgs = useCreditsPackages()
    await pkgs.loadPackages()
    await pkgs.loadOrders()
    expect(pkgs.packages.value).toHaveLength(1)
    expect(pkgs.orders.value).toHaveLength(1)

    auth.currentUser = userB
    expect(pkgs.orders.value).toEqual([])      // 私有订单清空
    expect(pkgs.packages.value).toHaveLength(1) // 公共目录保留
    expect(pkgs.ownerAccountId.value).toBe(userB.id)
  })
})
