// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import UnifiedAuditPanel from './UnifiedAuditPanel.vue'

enableAutoUnmount(afterEach)

afterEach(() => vi.unstubAllGlobals())

function response(data: unknown): Response {
  return {
    ok: true,
    headers: { get: () => 'application/json' },
    json: async () => ({ success: true, data }),
  } as unknown as Response
}

describe('UnifiedAuditPanel', () => {
  test('按争议查询并归一展示生命周期审计', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response([{
      id: 7, disputeId: 'dispute-1', action: 'opened', actorAccountId: 'account-1',
      actorRole: 'merchant', note: '发起争议', createdAt: '2026-08-11T08:00:00Z',
    }]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(UnifiedAuditPanel)

    await wrapper.find('select').setValue('dispute')
    await wrapper.find('input[placeholder="dispute UUID"]').setValue('dispute-1')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('/api/trust/disputes/dispute-1/audit', expect.anything())
    expect(wrapper.text()).toContain('争议流转')
    expect(wrapper.text()).toContain('创建争议')
    expect(wrapper.text()).toContain('发起争议')
    expect(wrapper.text()).toContain('商家')
  })

  test('证据访问支持组合筛选并展示查看目的', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response([{
      id: 9, evidenceId: 'evidence-1', disputeId: 'dispute-2', viewerAccountId: 'viewer-1',
      viewerRole: 'customer_service', purpose: 'adjudication', viewedAt: '2026-08-11T09:00:00Z',
    }]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(UnifiedAuditPanel)

    await wrapper.find('select').setValue('evidence_access')
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('dispute-2')
    await inputs[1].setValue('evidence-1')
    await inputs[2].setValue('viewer-1')
    await wrapper.findAll('select')[1].setValue('customer_service')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const url = String(fetchMock.mock.calls[0]?.[0])
    expect(url).toContain('/api/admin/trust/evidence-access-audits?')
    expect(url).toContain('disputeId=dispute-2')
    expect(url).toContain('evidenceId=evidence-1')
    expect(url).toContain('viewerAccountId=viewer-1')
    expect(wrapper.text()).toContain('查看证据')
    expect(wrapper.text()).toContain('客服')
    expect(wrapper.text()).toContain('adjudication')
  })

  test('资源型审计缺少 ID 时不发请求', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(UnifiedAuditPanel)

    await wrapper.find('form').trigger('submit')

    expect(wrapper.text()).toContain('请输入账号 ID')
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
