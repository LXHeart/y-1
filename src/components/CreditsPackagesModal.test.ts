// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import CreditsPackagesModal from './CreditsPackagesModal.vue'

/**
 * 积分与套餐弹窗特征测试：SKU 卡片渲染、购买确认与不可退提示、
 * 购买成功余额刷新事件、购买记录、错误态。
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
})
