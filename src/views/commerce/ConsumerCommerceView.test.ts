// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ConsumerCommerceView from './ConsumerCommerceView.vue'
import { useAuth } from '../../composables/useAuth'
import type { AuthUser } from '../../types/auth'

/**
 * D-07 消费者侧收尾的组件回归：时段选择下单、部分退款、售后争议、归因换绑。
 * 这些路径的共同风险是「请求体字段与后端 record 契约不对称」（本项目历史坑），
 * 故每个用例都锁死实际发出的 body。
 */

const { currentUser } = useAuth()

function asUser(): AuthUser {
  return { id: 'consumer-1', email: 'consumer@test.local', displayName: 'c', role: 'user' }
}

function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}

interface FetchCall { url: string; init?: RequestInit }

function stubFetch(handler: (url: string, init?: RequestInit) => unknown): FetchCall[] {
  const calls: FetchCall[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, init })
    const data = handler(url, init)
    if (data === undefined) return jsonResponse(null)
    return jsonResponse(data)
  }))
  return calls
}

function baseOrder(overrides: Record<string, unknown>): Record<string, unknown> {
  return {
    id: 'order-1', consumerAccountId: 'consumer-1', organizationId: 'org-1',
    packageId: 'pkg-1', packageVersion: 1, packageTitle: '双人套餐',
    priceCents: 10000, recommenderAmountCents: 1000, merchantAmountCents: 8500,
    platformFeeCents: 500, status: 'paid', redeemDeadline: '2026-09-30T10:00:00Z',
    createdAt: '2026-08-16T02:00:00Z', ...overrides,
  }
}

enableAutoUnmount(afterEach)

beforeEach(() => {
  currentUser.value = null
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('ConsumerCommerceView D-07 收尾', () => {
  it('时段套餐未选时段时禁用下单，选中后请求体携带 inventorySlotId', async () => {
    currentUser.value = asUser()
    const calls = stubFetch((url) => {
      if (url === '/api/v2/packages/pkg-1') {
        return {
          id: 'pkg-1', organizationId: 'org-1', status: 'published', version: 1,
          title: '时段套餐', description: '', priceCents: 5000, totalStock: 3, remainingStock: 3,
          recommenderShareBps: 1000, platformFeeBps: 500, merchantShareBps: 8500,
          policyVersion: 'commerce-v1', promotionPath: '', createdAt: '', updatedAt: '',
          inventorySlots: [
            { id: 'slot-1', packageVersionId: 'v1', slotStart: '2026-09-01T10:00:00Z', slotEnd: '2026-09-01T11:00:00Z', totalStock: 1, remainingStock: 1 },
            { id: 'slot-2', packageVersionId: 'v1', slotStart: '2026-09-01T14:00:00Z', slotEnd: '2026-09-01T15:00:00Z', totalStock: 2, remainingStock: 2 },
          ],
        }
      }
      if (url === '/api/v2/orders') return []
      return undefined
    })
    const wrapper = mount(ConsumerCommerceView)
    await flushPromises()

    await wrapper.get('.lookup-row input').setValue('pkg-1')
    await wrapper.get('.lookup-row button').trigger('click')
    await flushPromises()

    const buyButton = wrapper.get('.buy-box button')
    expect(buyButton.attributes('disabled')).toBeDefined()
    expect(buyButton.text()).toContain('请先选择时段')

    await wrapper.get('.slot-picker input[type="radio"]').setValue(true)
    await buyButton.trigger('click')
    await flushPromises()

    const post = calls.find(call => call.url === '/api/v2/orders'
      && call.init?.method === 'POST'
      && (call.init?.body as string)?.includes('inventorySlotId'))
    expect(post).toBeDefined()
    expect(JSON.parse(post!.init!.body as string)).toEqual({
      packageId: 'pkg-1', inventorySlotId: 'slot-1',
    })
  })

  it('部分退款：展开表单输入金额后按分提交', async () => {
    currentUser.value = asUser()
    const calls = stubFetch((url, init) => {
      if (url === '/api/v2/orders' && !init?.method) return [baseOrder({ status: 'paid' })]
      if (url === '/api/v2/orders/order-1/refund') return baseOrder({ status: 'partially_refunded', refundedAmountCents: 1050 })
      return undefined
    })
    const wrapper = mount(ConsumerCommerceView)
    await flushPromises()

    await wrapper.get('.actions button').trigger('click')
    await wrapper.get('.subform input').setValue('10.5')
    await wrapper.get('.subform button').trigger('click')
    await flushPromises()

    const refundCall = calls.find(call => call.url === '/api/v2/orders/order-1/refund')
    expect(refundCall).toBeDefined()
    expect(JSON.parse(refundCall!.init!.body as string)).toEqual({
      reason: 'consumer_request', amountCents: 1050,
    })
    expect(wrapper.text()).toContain('部分退款成功')
  })

  it('已核销订单可发起售后争议，原因必填且逐字进入请求体', async () => {
    currentUser.value = asUser()
    const calls = stubFetch((url, init) => {
      if (url === '/api/v2/orders' && !init?.method) return [baseOrder({ status: 'redeemed', redeemedAt: '2026-08-16T03:00:00Z' })]
      if (url === '/api/v2/orders/order-1/after-sales-dispute' && init?.method === 'POST') {
        return baseOrder({ status: 'after_sales_disputed' })
      }
      if (url === '/api/v2/orders/order-1/after-sales-dispute') {
        return { id: 'dispute-1', orderId: 'order-1', status: 'open', reason: '到店后商家无法提供服务' }
      }
      return undefined
    })
    const wrapper = mount(ConsumerCommerceView)
    await flushPromises()

    await wrapper.get('.actions button').trigger('click')
    await wrapper.get('.subform textarea').setValue('到店后商家无法提供服务')
    await wrapper.get('.subform button').trigger('click')
    await flushPromises()

    const disputeCall = calls.find(call => call.url === '/api/v2/orders/order-1/after-sales-dispute' && call.init?.method === 'POST')
    expect(disputeCall).toBeDefined()
    expect(JSON.parse(disputeCall!.init!.body as string)).toEqual({
      reason: '到店后商家无法提供服务',
    })
    expect(wrapper.text()).toContain('售后争议已提交')
  })

  it('争议中的订单自动拉取并回显争议原因', async () => {
    currentUser.value = asUser()
    const calls = stubFetch((url) => {
      if (url === '/api/v2/orders') return [baseOrder({ status: 'after_sales_disputed' })]
      if (url === '/api/v2/orders/order-1/after-sales-dispute') {
        return { id: 'dispute-1', orderId: 'order-1', status: 'open', reason: '菜品与描述不符' }
      }
      return undefined
    })
    const wrapper = mount(ConsumerCommerceView)
    await flushPromises()

    expect(calls.some(call => call.url === '/api/v2/orders/order-1/after-sales-dispute')).toBe(true)
    expect(wrapper.get('.dispute-box').text()).toContain('菜品与描述不符')
    expect(wrapper.get('.dispute-box').text()).toContain('待商家裁定')
  })

  it('归因换绑：推荐官 ID 与百分比转换为 bps 请求体', async () => {
    currentUser.value = asUser()
    const calls = stubFetch((url, init) => {
      if (url === '/api/v2/orders' && !init?.method) {
        return [baseOrder({ recommenderAccountId: 'recommender-old', recommenderAmountCents: 1000 })]
      }
      if (url === '/api/v2/orders/order-1/attribution' && init?.method === 'POST') {
        return baseOrder({ recommenderAccountId: 'recommender-new' })
      }
      return undefined
    })
    const wrapper = mount(ConsumerCommerceView)
    await flushPromises()

    await wrapper.get('.attribution-line button').trigger('click')
    const inputs = wrapper.findAll('.subform input')
    await inputs[0].setValue('recommender-new')
    await inputs[1].setValue('20')
    await wrapper.get('.subform button').trigger('click')
    await flushPromises()

    const rebindCall = calls.find(call => call.url === '/api/v2/orders/order-1/attribution' && call.init?.method === 'POST')
    expect(rebindCall).toBeDefined()
    expect(JSON.parse(rebindCall!.init!.body as string)).toEqual({
      allocations: [{ recommenderAccountId: 'recommender-new', shareBps: 2000 }],
      source: 'manual', reason: 'consumer_rebind',
    })
    expect(wrapper.text()).toContain('归因已改绑')
  })
})

/** 任务书 #41：待支付单显示支付截止、超时单显示已关闭、payment_timeout 不再当「处理中」展示。 */
describe('ConsumerCommerceView TTL 关单展示', () => {
  it('待支付订单显示支付截止时间提示', async () => {
    currentUser.value = asUser()
    stubFetch((url) => {
      if (url === '/api/v2/orders') {
        return [baseOrder({
          status: 'pending_payment',
          paymentDeadline: '2026-08-17T12:30:00Z',
          lastError: 'gateway timeout',
        })]
      }
      return undefined
    })
    const wrapper = mount(ConsumerCommerceView)
    await flushPromises()

    expect(wrapper.text()).toContain('支付处理中')
    // formatTime 按运行环境本地时区渲染（12:30Z 在 +08 为 8:30 PM、在 UTC 为 12:30 PM），
    // 期望值同源计算，不写死偏移（CI runner 是 UTC）。
    expect(wrapper.get('.payment-hint').text()).toContain(new Date('2026-08-17T12:30:00Z').toLocaleTimeString('zh-CN', { hour12: false }))
    expect(wrapper.get('.payment-hint').text()).toContain('超时订单将自动关闭')
    // 未超时的失败单仍如实展示后台错误（重试仍在进行）
    expect(wrapper.text()).toContain('处理暂未完成')
  })

  it('超时关单订单显示已取消（超时自动关闭），payment_timeout 不再当处理中展示', async () => {
    currentUser.value = asUser()
    stubFetch((url) => {
      if (url === '/api/v2/orders') {
        return [baseOrder({
          status: 'cancelled',
          paymentDeadline: '2026-08-17T12:30:00Z',
          lastError: 'payment_timeout',
        })]
      }
      return undefined
    })
    const wrapper = mount(ConsumerCommerceView)
    await flushPromises()

    expect(wrapper.text()).toContain('已取消')
    expect(wrapper.text()).toContain('超时自动关闭')
    expect(wrapper.text()).toContain('占用的库存已释放')
    expect(wrapper.text()).not.toContain('处理暂未完成')
    expect(wrapper.text()).not.toContain('主动取消')
    expect(wrapper.find('.payment-hint').exists()).toBe(false)
  })

  it('主动取消订单显示已取消（主动取消）且待支付单展示取消按钮', async () => {
    currentUser.value = asUser()
    stubFetch((url) => {
      if (url === '/api/v2/orders') {
        return [baseOrder({ status: 'cancelled', lastError: 'consumer_cancelled' }),
                baseOrder({ status: 'pending_payment', paymentDeadline: '2026-08-17T12:30:00Z' })]
      }
      return undefined
    })
    const wrapper = mount(ConsumerCommerceView)
    await flushPromises()

    expect(wrapper.text()).toContain('主动取消')
    expect(wrapper.text()).toContain('取消订单')
  })

  it('下单返回待支付单时 notice 携带支付截止时间', async () => {
    currentUser.value = asUser()
    stubFetch((url, init) => {
      if (url === '/api/v2/packages/pkg-1') {
        return {
          id: 'pkg-1', organizationId: 'org-1', status: 'published', version: 1,
          title: '普通套餐', description: '', priceCents: 5000, totalStock: 3, remainingStock: 3,
          recommenderShareBps: 1000, platformFeeBps: 500, merchantShareBps: 8500,
          policyVersion: 'commerce-v1', promotionPath: '', createdAt: '', updatedAt: '',
        }
      }
      if (url === '/api/v2/orders' && !init?.method) return []
      if (url === '/api/v2/orders' && init?.method === 'POST') {
        return baseOrder({ status: 'pending_payment', paymentDeadline: '2026-08-17T13:00:00Z' })
      }
      return undefined
    })
    const wrapper = mount(ConsumerCommerceView)
    await flushPromises()

    await wrapper.get('.lookup-row input').setValue('pkg-1')
    await wrapper.get('.lookup-row button').trigger('click')
    await flushPromises()
    await wrapper.get('.buy-box button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('支付正在后台重试')
    expect(wrapper.text()).toContain(new Date('2026-08-17T13:00:00Z').toLocaleTimeString('zh-CN', { hour12: false }))
    expect(wrapper.text()).toContain('超时将自动关闭')
  })
})
