// @vitest-environment happy-dom
import { describe, expect, test, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import RecommenderShareCard from './RecommenderShareCard.vue'

const { listMyPromotions, toDataURL } = vi.hoisted(() => ({
  listMyPromotions: vi.fn(),
  toDataURL: vi.fn(async () => 'data:image/png;base64,QR'),
}))

vi.mock('../composables/useCommerce', () => ({
  useCommerce: () => ({ listMyPromotions }),
}))
vi.mock('../composables/useAuth', () => ({
  useAuth: () => ({ currentUser: { value: { id: 'rec-account-1' } } }),
}))
vi.mock('qrcode', () => ({ default: { toDataURL } }))

function promotion(overrides: Record<string, unknown> = {}) {
  return {
    taskId: 'task-1',
    taskTitle: '下午茶推广',
    taskStatus: 'published',
    packageId: 'pkg-1',
    packageTitle: '双人下午茶套餐',
    priceCents: 12800,
    commission: { form: 'ratio', shareBps: 1000 },
    stats: { orderCount: 3, redeemedCount: 2, pendingSettleCents: 1000, settledCents: 2000 },
    ...overrides,
  }
}

describe('RecommenderShareCard（我的推广链接——任务书 #75 卡 B7）', () => {
  beforeEach(() => {
    listMyPromotions.mockReset()
    toDataURL.mockClear()
  })

  test('列出已接单的推广任务与漏斗，生成带归因的链接与二维码', async () => {
    listMyPromotions.mockResolvedValue([promotion()])
    const wrapper = mount(RecommenderShareCard)
    await flushPromises()

    expect(listMyPromotions).toHaveBeenCalledOnce()
    // 手输套餐 ID 的自由分销入口已下线（D4 纯任务化）。
    expect(wrapper.find('input[placeholder*="套餐 ID"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('双人下午茶套餐')
    expect(wrapper.text()).toContain('10% / 单')
    expect(wrapper.text()).toContain('待结算')

    await wrapper.find('[data-testid="promotion-generate"]').trigger('click')
    await flushPromises()

    const url = (wrapper.find('.copy-row input').element as HTMLInputElement).value
    expect(url).toContain('view=commerce')
    expect(url).toContain('package=pkg-1')
    expect(url).toContain('recommender=rec-account-1')
    expect(wrapper.find('img.share-qr').attributes('src')).toBe('data:image/png;base64,QR')
  })

  test('固定佣形态展示 ¥/单', async () => {
    listMyPromotions.mockResolvedValue([
      promotion({ commission: { form: 'fixed', shareBps: 0, fixedCents: 500 } }),
    ])
    const wrapper = mount(RecommenderShareCard)
    await flushPromises()
    expect(wrapper.text()).toContain('¥5.00 / 单')
  })

  test('没有接单任务时空态引导去任务大厅', async () => {
    listMyPromotions.mockResolvedValue([])
    const wrapper = mount(RecommenderShareCard)
    await flushPromises()
    expect(wrapper.text()).toContain('任务大厅')
    expect(wrapper.find('[data-testid="promotion-generate"]').exists()).toBe(false)
  })

  test('加载失败展示错误', async () => {
    listMyPromotions.mockRejectedValue(new Error('推广任务加载失败'))
    const wrapper = mount(RecommenderShareCard)
    await flushPromises()
    expect(wrapper.text()).toContain('推广任务加载失败')
  })
})
