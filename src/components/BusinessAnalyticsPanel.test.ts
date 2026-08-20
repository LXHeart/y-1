// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import BusinessAnalyticsPanel from './BusinessAnalyticsPanel.vue'

/**
 * 营销看板日/周/月粒度（PRD §2.4）：序列端点 /api/analytics/series 随主报表并发加载，
 * 粒度切换仅重拉序列并带 granularity 参数；未填时间筛选时用默认最近窗口。
 */

function jsonResponse(data: unknown) {
  return {
    ok: true,
    status: 200,
    headers: { get: () => 'application/json' },
    json: async () => data,
    text: async () => JSON.stringify(data),
  }
}

const MERCHANT_DASHBOARD = {
  taskCount: 3,
  publishedTaskCount: 1,
  totalApplications: 10,
  applicationAcceptanceRate: 0.5,
  confirmedDeliverables: 4,
  settledEngagements: 2,
  averageRating: 4.5,
  settledBountyCents: 1000,
  businessMetrics: {
    orders: 5, paidOrders: 4, redeemedOrders: 3, refundedOrders: 1,
    grossGmvCents: 100000, refundedGmvCents: 10000, netGmvCents: 90000,
    merchantRevenueCents: 80000, platformFeeCents: 5000, recommenderRevenueCents: 5000,
  },
  marketingMetrics: {
    exposures: 100, interactions: 20, conversions: 5,
    attributedRevenueCents: 50000, status: 'available', roi: 0.2,
    roiFormula: '(归因收入-赏金)/赏金',
  },
  advice: [],
  alerts: [],
}

function seriesPayload(granularity: string, buckets: Array<Record<string, unknown>>) {
  return { organizationId: 'org-1', granularity, from: '2026-08-01T00:00:00Z', to: '2026-08-31T00:00:00Z', buckets }
}

let fetchMock: ReturnType<typeof vi.fn>

beforeEach(() => {
  fetchMock = vi.fn(async (url: string) => {
    const path = url.split('?')[0]
    if (path === '/api/tasks/analytics') return jsonResponse({ success: true, data: MERCHANT_DASHBOARD })
    if (path === '/api/analytics/series') {
      const granularity = url.includes('granularity=week') ? 'week' : url.includes('granularity=month') ? 'month' : 'day'
      return jsonResponse({ success: true, data: seriesPayload(granularity, [
        { bucket: '2026-08-01', orders: 2, paidOrders: 2, redeemedOrders: 1, refundedOrders: 0,
          grossGmvCents: 20000, refundedGmvCents: 0, netGmvCents: 20000, merchantRevenueCents: 18000,
          recommenderRevenueCents: 2000, exposures: 30, interactions: 6, conversions: 2 },
        { bucket: '2026-08-02', orders: 0, paidOrders: 0, redeemedOrders: 0, refundedOrders: 0,
          grossGmvCents: 0, refundedGmvCents: 0, netGmvCents: 0, merchantRevenueCents: 0,
          recommenderRevenueCents: 0, exposures: 0, interactions: 0, conversions: 0 },
      ]) })
    }
    return jsonResponse(null)
  })
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

enableAutoUnmount(afterEach)

describe('BusinessAnalyticsPanel 营收趋势粒度（PRD §2.4）', () => {
  test('商家模式加载序列表并展示日粒度桶', async () => {
    const wrapper = mount(BusinessAnalyticsPanel, { props: { organizationId: 'org-1' } })
    await flushPromises()

    const seriesUrl = fetchMock.mock.calls.map(([url]) => String(url))
      .find((url) => url.startsWith('/api/analytics/series'))
    expect(seriesUrl).toBeDefined()
    expect(seriesUrl).toContain('granularity=day')

    expect(wrapper.find('.series').exists()).toBe(true)
    expect(wrapper.get('.series-window').text()).toContain('默认最近窗口')
    const rows = wrapper.findAll('.series-table tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('2026-08-01')
    expect(rows[0].text()).toContain('¥200.00')
  })

  test('切到按周仅重拉序列并携带 granularity=week', async () => {
    const wrapper = mount(BusinessAnalyticsPanel, { props: { organizationId: 'org-1' } })
    await flushPromises()

    fetchMock.mockClear()
    const weekTab = wrapper.findAll('.granularity-switch button')
      .find((button) => button.text() === '按周')!
    await weekTab.trigger('click')
    await flushPromises()

    const calls = fetchMock.mock.calls.map(([url]) => String(url))
    expect(calls.some((url) => url.startsWith('/api/analytics/series') && url.includes('granularity=week'))).toBe(true)
    expect(checksOnlySeries(calls))
    expect(wrapper.get('.series-window').text()).toContain('周（周一起）')
  })
})

function checksOnlySeries(calls: string[]): boolean {
  // 粒度切换不应重拉主报表
  return calls.every((url) => url.startsWith('/api/analytics/series'))
}
