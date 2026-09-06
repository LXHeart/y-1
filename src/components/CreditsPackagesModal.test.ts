// @vitest-environment happy-dom
import { nextTick } from 'vue'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import CreditsPackagesModal from './CreditsPackagesModal.vue'
import { useAuthStore } from '../stores/auth'

/**
 * 积分与套餐弹窗特征测试：SKU 卡片渲染、购买确认与不可退提示、
 * 购买成功余额刷新事件、购买记录、错误态。
 * 任务书 #82 C82-04：购买成功响应迟到（换号）不 emit 余额刷新、不显示成功、不重拉订单。
 */

const fetchMock = vi.fn()

function envelope(data: unknown, ok = true): Response {
  return new Response(JSON.stringify(ok ? { success: true, data } : data), {
    status: ok ? 200 : 409,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  useAuthStore().currentUser = null
  vi.unstubAllGlobals()
})

enableAutoUnmount(afterEach)

function mountModal() {
  return mount(CreditsPackagesModal, {
    props: { balance: 5, open: true },
    attachTo: document.body,
  })
}

describe('CreditsPackagesModal', () => {
  test('打开时加载 active SKU 与购买记录并渲染卡片', async () => {
    fetchMock.mockImplementation(async (url: string) => {
      if (url === '/api/credits/packages') {
        return envelope([
          { id: 'p1', name: '体验包', description: '新用户体验', priceCents: 990, creditsAmount: 10 },
          { id: 'p2', name: '创作包', description: '', priceCents: 4900, creditsAmount: 60 },
        ])
      }
      if (url === '/api/credits/purchase-orders') {
        return envelope([{ id: 'o1', packageId: 'p1', priceCents: 990, creditsAmount: 10, status: 'paid' }])
      }
      return envelope([])
    })
    const wrapper = mountModal()
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('体验包')
    expect(wrapper.text()).toContain('¥9.9')
    expect(wrapper.text()).toContain('10 积分')
    expect(wrapper.text()).toContain('当前余额')
    expect(wrapper.text()).toContain('购买记录')
    expect(wrapper.text()).toContain('paid')
  })

  test('购买成功：emit balance-refreshed 并显示成功提示', async () => {
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        expect(url).toBe('/api/credits/purchase-orders')
        return envelope({ orderId: 'o9', status: 'paid', creditsAmount: 10, balance: 15 })
      }
      if (url === '/api/credits/purchase-orders') return envelope([])
      return envelope([{ id: 'p1', name: '体验包', description: '', priceCents: 990, creditsAmount: 10 }])
    })
    const wrapper = mountModal()
    await flushPromises()

    await wrapper.find('[data-test="buy-p1"]').trigger('click')
    await wrapper.find('button.primary:not([data-test])').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('balance-refreshed')?.[0]).toEqual([15])
    expect(wrapper.text()).toContain('购买成功')
  })

  test('购买失败：显示后端错误消息', async () => {
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        return envelope({ success: false, error: '积分包不在售' }, false)
      }
      if (url === '/api/credits/purchase-orders') return envelope([])
      return envelope([{ id: 'p1', name: '体验包', description: '', priceCents: 990, creditsAmount: 10 }])
    })
    const wrapper = mountModal()
    await flushPromises()

    await wrapper.find('[data-test="buy-p1"]').trigger('click')
    await wrapper.find('button.primary:not([data-test])').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('balance-refreshed')).toBeUndefined()
    expect(wrapper.text()).toContain('积分包不在售')
  })

  test('卡片显示不可自助退款提示', async () => {
    fetchMock.mockImplementation(async (url: string) => {
      if (url === '/api/credits/packages') {
        return envelope([{ id: 'p1', name: '体验包', description: '', priceCents: 990, creditsAmount: 10 }])
      }
      return envelope([])
    })
    const wrapper = mountModal()
    await flushPromises()

    expect(wrapper.text()).toContain('购买后暂不支持自助退款')
  })

  test('购买成功响应迟到（换号）：不 emit 余额刷新、不显示成功、不重拉订单（任务书 #82 C82-04 E02）', async () => {
    const auth = useAuthStore()
    let resolvePurchase!: (response: Response) => void
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        return new Promise<Response>((resolve) => { resolvePurchase = resolve })
      }
      if (url === '/api/credits/purchase-orders') return envelope([])
      return envelope([{ id: 'p1', name: '体验包', description: '', priceCents: 990, creditsAmount: 10 }])
    })
    const wrapper = mountModal()
    await flushPromises()
    const ordersCallsBefore = fetchMock.mock.calls.filter(([url, init]) =>
      url === '/api/credits/purchase-orders' && !init?.method).length

    await wrapper.find('[data-test="buy-p1"]').trigger('click')
    await wrapper.find('button.primary:not([data-test])').trigger('click')
    auth.currentUser = { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', email: 'b@qa.invalid', displayName: '乙', role: 'user' }
    resolvePurchase(envelope({ orderId: 'o9', status: 'paid', creditsAmount: 10, balance: 15 }))
    await flushPromises()

    expect(wrapper.emitted('balance-refreshed')).toBeUndefined() // A 的成功不 emit B 的余额
    expect(wrapper.text()).not.toContain('购买成功')              // 不显示 A 的成功文案
    const ordersCallsAfter = fetchMock.mock.calls.filter(([url, init]) =>
      url === '/api/credits/purchase-orders' && !init?.method).length
    expect(ordersCallsAfter).toBe(ordersCallsBefore)             // 不为 A 的成功重拉订单
  })

  test('账号变化：弹窗常驻（open 不变）也清确认态与成功提示（任务书 #82 C82-04）', async () => {
    const auth = useAuthStore()
    fetchMock.mockImplementation(async (url: string) => {
      if (url === '/api/credits/packages') {
        return envelope([{ id: 'p1', name: '体验包', description: '', priceCents: 990, creditsAmount: 10 }])
      }
      return envelope([])
    })
    const wrapper = mountModal()
    await flushPromises()

    await wrapper.find('[data-test="buy-p1"]').trigger('click')
    expect(wrapper.text()).toContain('确认支付') // 二次确认已打开

    auth.currentUser = { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', email: 'b@qa.invalid', displayName: '乙', role: 'user' }
    await nextTick()
    expect(wrapper.text()).not.toContain('确认支付') // 换号即清确认态
  })
})
