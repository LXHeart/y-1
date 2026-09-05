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

describe('BusinessAnalyticsPanel 营收趋势本地分页（任务书 #78 卡 J）', () => {
  function stubBuckets(count: number) {
    const buckets = Array.from({ length: count }, (_, index) => {
      const day = String(index + 1).padStart(2, '0')
      return { bucket: `2026-08-${day}`, orders: index, paidOrders: index, redeemedOrders: 0,
        refundedOrders: 0, grossGmvCents: index * 100, refundedGmvCents: 0,
        netGmvCents: index * 100, merchantRevenueCents: index * 90,
        recommenderRevenueCents: index * 10, exposures: index, interactions: index, conversions: index }
    })
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      const path = url.split('?')[0]
      if (path === '/api/tasks/analytics') return jsonResponse({ success: true, data: MERCHANT_DASHBOARD })
      if (path === '/api/analytics/series') return jsonResponse({ success: true, data: seriesPayload('day', buckets) })
      return jsonResponse(null)
    }))
  }

  test('25 桶按每页 10 条切页：首页 10 条、翻页到底 5 条，页码与总数可见', async () => {
    stubBuckets(25)
    const wrapper = mount(BusinessAnalyticsPanel, { props: { organizationId: 'org-1' } })
    await flushPromises()

    expect(wrapper.findAll('.series-table tbody tr')).toHaveLength(10)
    const pager = wrapper.get('.series-pager')
    expect(pager.text()).toContain('第 1 / 3 页 · 共 25 条')

    await wrapper.findAll('.series-pager button').find((b) => b.text() === '下一页')!.trigger('click')
    expect(wrapper.get('.series-pager').text()).toContain('第 2 / 3 页')
    expect(wrapper.get('.series-table tbody tr').text()).toContain('2026-08-11')

    // 末页：只有 5 条，下一页按钮禁用
    await wrapper.findAll('.series-pager button').find((b) => b.text() === '下一页')!.trigger('click')
    expect(wrapper.findAll('.series-table tbody tr')).toHaveLength(5)
    expect(wrapper.findAll('.series-pager button').find((b) => b.text() === '下一页')!.attributes('disabled')).toBeDefined()
  })

  test('每页档位 10/20/50 可切换；换档后页码夹回有效范围', async () => {
    stubBuckets(25)
    const wrapper = mount(BusinessAnalyticsPanel, { props: { organizationId: 'org-1' } })
    await flushPromises()
    await wrapper.findAll('.series-pager button').find((b) => b.text() === '下一页')!.trigger('click')
    expect(wrapper.get('.series-pager').text()).toContain('第 2 / 3 页')

    const select = wrapper.get('select[aria-label="营收趋势每页条数"]')
    await select.setValue('50')
    await flushPromises()
    // 25 条 ÷ 50 = 1 页：原页码 2 越界，夹回第 1 页且分页条隐藏
    expect(wrapper.findAll('.series-table tbody tr')).toHaveLength(25)
    expect(wrapper.find('.series-pager').exists()).toBe(false)
  })

  test('重新查询与粒度切换页码归零', async () => {
    stubBuckets(25)
    const wrapper = mount(BusinessAnalyticsPanel, { props: { organizationId: 'org-1' } })
    await flushPromises()
    await wrapper.findAll('.series-pager button').find((b) => b.text() === '下一页')!.trigger('click')
    expect(wrapper.get('.series-pager').text()).toContain('第 2 / 3 页')

    // 粒度切换：页码归零
    const weekTab = wrapper.findAll('.granularity-switch button').find((b) => b.text() === '按周')!
    await weekTab.trigger('click')
    await flushPromises()
    expect(wrapper.get('.series-pager').text()).toContain('第 1 / 3 页')

    // 重新查询：页码归零
    await wrapper.findAll('.series-pager button').find((b) => b.text() === '下一页')!.trigger('click')
    const queryButton = wrapper.findAll('button').find((b) => b.text() === '查询')!
    await queryButton.trigger('click')
    await flushPromises()
    expect(wrapper.get('.series-pager').text()).toContain('第 1 / 3 页')
  })
})
