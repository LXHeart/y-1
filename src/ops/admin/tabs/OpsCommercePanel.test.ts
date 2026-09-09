// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import OpsCommercePanel from './OpsCommercePanel.vue'

const api = {
  loading: { value: false },
  error: { value: '' },
  adminOpsDashboard: vi.fn(),
  listAdminOrderHolds: vi.fn(),
  confirmOrderHold: vi.fn(),
  releaseOrderHold: vi.fn(),
  dismissOrderHold: vi.fn(),
}

vi.mock('../../../composables/useCommerce', () => ({ useCommerce: () => api }))

const dashboard = {
  windowDays: 30,
  from: '2026-08-10T00:00:00Z',
  to: '2026-09-09T00:00:00Z',
  timezone: 'Asia/Shanghai',
  computedAt: '2026-09-09T00:00:00Z',
  metrics: [{ key: 'attributedSales', label: '归因销售额', valueCents: 12800, source: 'consumer_order_attribution', window: '近 30 天' }],
}

const flagged = {
  id: 'hold-1', orderId: 'order-1', rule: 'rlid_order_burst', reason: '短窗订单激增', status: 'flagged',
  flaggedAt: '2026-09-09T00:00:00Z', holdDeadlineAt: null,
}

const held = {
  ...flagged, status: 'held', holdDeadlineAt: '2099-09-10T00:00:00Z', confirmedAt: '2026-09-09T00:00:00Z',
}

beforeEach(() => {
  vi.clearAllMocks()
  api.adminOpsDashboard.mockResolvedValue(dashboard)
  api.listAdminOrderHolds.mockResolvedValue([flagged])
  api.confirmOrderHold.mockResolvedValue(held)
  api.releaseOrderHold.mockResolvedValue({ ...held, status: 'released' })
  vi.stubGlobal('confirm', vi.fn(() => true))
})

describe('OpsCommercePanel', () => {
  it('loads metrics and shows flagged holds without a settlement countdown', async () => {
    const wrapper = mount(OpsCommercePanel)
    await nextTick()
    await nextTick()
    expect(wrapper.get('[data-testid="ops-metrics"]').text()).toContain('归因销售额')
    expect(wrapper.text()).toContain('统计区间')
    expect(wrapper.get('[data-testid="hold-row-flagged"]').text()).toContain('确认后 72 小时')
    expect(wrapper.get('[data-testid="hold-row-flagged"]').text()).not.toContain('剩余')
    expect(api.listAdminOrderHolds).toHaveBeenCalledWith('flagged')
  })

  it('confirms a flagged hold through the explicit confirmation action', async () => {
    const wrapper = mount(OpsCommercePanel)
    await nextTick()
    await nextTick()
    await wrapper.get('[data-testid="hold-row-flagged"] button').trigger('click')
    expect(api.confirmOrderHold).toHaveBeenCalledWith('hold-1')
  })

  it('requires a release note and renders the held countdown', async () => {
    api.listAdminOrderHolds.mockResolvedValue([held])
    const wrapper = mount(OpsCommercePanel)
    await nextTick()
    await wrapper.find('select').setValue('held')
    await nextTick()
    expect(wrapper.get('[data-testid="hold-row-held"]').text()).toContain('剩余')
    await wrapper.get('[data-testid="hold-row-held"] button').trigger('click')
    expect(api.releaseOrderHold).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('解除说明必填')
  })
})
