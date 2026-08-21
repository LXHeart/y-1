// @vitest-environment happy-dom
import { describe, expect, test, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import RecommenderShareCard from './RecommenderShareCard.vue'

const { getPackage, toDataURL } = vi.hoisted(() => ({
  getPackage: vi.fn(),
  toDataURL: vi.fn(async () => 'data:image/png;base64,QR'),
}))

vi.mock('../composables/useCommerce', () => ({
  useCommerce: () => ({ getPackage }),
}))
vi.mock('../composables/useAuth', () => ({
  useAuth: () => ({ currentUser: { value: { id: 'rec-account-1' } } }),
}))
vi.mock('qrcode', () => ({ default: { toDataURL } }))


function pkg(overrides: Record<string, unknown> = {}) {
  return {
    id: 'pkg-1', title: '双人下午茶套餐', priceCents: 12800, recommenderShareBps: 1000, ...overrides,
  }
}

describe('RecommenderShareCard（推荐官推广链接/二维码）', () => {
  beforeEach(() => {
    getPackage.mockReset()
    toDataURL.mockClear()
  })

  test('输入套餐 ID 生成带 recommender 归因的链接与二维码', async () => {
    getPackage.mockResolvedValue(pkg())
    const wrapper = mount(RecommenderShareCard)
    await wrapper.find('input[placeholder*="套餐 ID"]').setValue('pkg-1')
    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(getPackage).toHaveBeenCalledWith('pkg-1')
    const url = (wrapper.find('.copy-row input').element as HTMLInputElement).value
    expect(url).toContain('view=commerce')
    expect(url).toContain('package=pkg-1')
    expect(url).toContain('recommender=rec-account-1')
    expect(wrapper.find('img.share-qr').attributes('src')).toBe('data:image/png;base64,QR')
    expect(wrapper.text()).toContain('双人下午茶套餐')
    expect(wrapper.text()).toContain('10.0%')
  })

  test('套餐不存在展示错误，不出链接/二维码', async () => {
    getPackage.mockRejectedValue(new Error('套餐不存在'))
    const wrapper = mount(RecommenderShareCard)
    await wrapper.find('input[placeholder*="套餐 ID"]').setValue('pkg-404')
    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('套餐不存在')
    expect(wrapper.find('.copy-row').exists()).toBe(false)
    expect(wrapper.find('img.share-qr').exists()).toBe(false)
  })
})
