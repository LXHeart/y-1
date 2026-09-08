// @vitest-environment happy-dom
import { describe, expect, test, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import RecommenderShareCard from './RecommenderShareCard.vue'

const { listMyPromotions, listMyReferralLinks, issuePromotionLink, endReferralLink, toDataURL } = vi.hoisted(() => ({
  listMyPromotions: vi.fn(),
  listMyReferralLinks: vi.fn(),
  issuePromotionLink: vi.fn(),
  endReferralLink: vi.fn(),
  toDataURL: vi.fn(async () => 'data:image/png;base64,QR'),
}))

vi.mock('../composables/useCommerce', () => ({
  useCommerce: () => ({
    listMyPromotions, listMyReferralLinks, issuePromotionLink, endReferralLink,
    error: { value: '' }, errorStatus: { value: null },
  }),
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

function referralLink(overrides: Record<string, unknown> = {}) {
  return {
    referralLinkId: 'rlAbCdEfGhIjKlMnOpQrStUv',
    taskId: 'task-1',
    packageId: 'pkg-1',
    url: '/?view=commerce&package=pkg-1&rlid=rlAbCdEfGhIjKlMnOpQrStUv',
    status: 'active',
    endedReason: null,
    createdAt: '2026-09-09T00:00:00Z',
    expiresAt: '2026-12-08T00:00:00Z',
    policyVersion: 'last_touch_7d_v1',
    ...overrides,
  }
}

describe('RecommenderShareCard（我的推广链接——任务书 #75 卡 B7 / #98 C98-01 rlid 化）', () => {
  beforeEach(() => {
    listMyPromotions.mockReset()
    listMyReferralLinks.mockReset()
    issuePromotionLink.mockReset()
    endReferralLink.mockReset()
    toDataURL.mockClear()
    listMyPromotions.mockResolvedValue([])
    listMyReferralLinks.mockResolvedValue([])
  })

  test('列出已接单的推广任务与漏斗，一键生成服务端 rlid 链接与二维码（不含裸账号 ID）', async () => {
    listMyPromotions.mockResolvedValue([promotion()])
    issuePromotionLink.mockResolvedValue(referralLink())
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

    expect(issuePromotionLink).toHaveBeenCalledWith('task-1')
    const url = (wrapper.find('[data-testid="promotion-link-input"]').element as HTMLInputElement).value
    // 任务书 #98 AC-98-04：前端不再生成裸账号 ID 链接——URL 只携不透明 rlid。
    expect(url).toContain('view=commerce')
    expect(url).toContain('package=pkg-1')
    expect(url).toContain('rlid=rlAbCdEfGhIjKlMnOpQrStUv')
    expect(url).not.toContain('recommender=')
    expect(url.startsWith('http')).toBe(true)
    expect(wrapper.find('img.share-qr').attributes('src')).toBe('data:image/png;base64,QR')
    expect(wrapper.text()).toContain('生效中')
  })

  test('本人失效链接后展示已终止状态；已有链接随列表恢复（含过期读时状态）', async () => {
    listMyPromotions.mockResolvedValue([promotion()])
    listMyReferralLinks.mockResolvedValue([
      referralLink({ status: 'expired', expiresAt: '2026-09-01T00:00:00Z' }),
    ])
    endReferralLink.mockResolvedValue(referralLink({ status: 'ended', endedReason: 'manual' }))
    const wrapper = mount(RecommenderShareCard)
    await flushPromises()

    expect(listMyReferralLinks).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('已过期')
    expect(wrapper.text()).toContain('可重新生成')
    expect(wrapper.find('[data-testid="promotion-link-end"]').exists()).toBe(false)

    // 重新生成 → 服务端发新 active 链接 → 可失效。
    issuePromotionLink.mockResolvedValue(referralLink({ referralLinkId: 'rlNewLink00000000000000',
      url: '/?view=commerce&package=pkg-1&rlid=rlNewLink00000000000000' }))
    await wrapper.find('[data-testid="promotion-generate"]').trigger('click')
    await flushPromises()

    await wrapper.find('[data-testid="promotion-link-end"]').trigger('click')
    await flushPromises()
    // 失效的是重新生成后的现行链接。
    expect(endReferralLink).toHaveBeenCalledWith('rlNewLink00000000000000')
    expect(wrapper.text()).toContain('已终止')
    expect(wrapper.text()).toContain('本人终止')
  })

  test('固定佣形态展示 ¥/单', async () => {
    listMyPromotions.mockResolvedValue([
      promotion({ commission: { form: 'fixed', shareBps: 0, fixedCents: 500 } }),
    ])
    const wrapper = mount(RecommenderShareCard)
    await flushPromises()
    expect(wrapper.text()).toContain('¥5.00 / 单')
  })

  test('没有接单任务时显示空态', async () => {
    listMyPromotions.mockResolvedValue([])
    const wrapper = mount(RecommenderShareCard)
    await flushPromises()
    expect(wrapper.text()).toContain('暂无可推广的套餐')
    expect(wrapper.find('[data-testid="promotion-generate"]').exists()).toBe(false)
  })

  test('生成失败展示错误', async () => {
    listMyPromotions.mockResolvedValue([promotion()])
    issuePromotionLink.mockRejectedValue(new Error('仅持有该推广任务接单资格的推荐官本人可生成推广链接'))
    const wrapper = mount(RecommenderShareCard)
    await flushPromises()

    await wrapper.find('[data-testid="promotion-generate"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('接单资格')
  })
})
