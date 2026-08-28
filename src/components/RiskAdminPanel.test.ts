// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import RiskAdminPanel from './RiskAdminPanel.vue'

const riskCase = {
  id: 'risk-1', subjectKind: 'account', subjectRef: 'merchant-1', organizationId: 'org-1',
  status: 'open', severity: 'high', score: 82, reason: 'merchant_cancelled_engagement@v1 score=82',
  resolutionNote: null, assignedTo: null, createdAt: '2026-08-15T00:00:00Z',
  updatedAt: '2026-08-15T00:00:00Z', resolvedAt: null,
}

const detail = {
  case: riskCase,
  signals: [{
    id: 'signal-1', sourceKind: 'marketplace', sourceRef: 'event-1', subjectKind: 'account',
    subjectRef: 'merchant-1', organizationId: 'org-1', ruleCode: 'merchant_cancelled_engagement',
    ruleVersion: 'v1', score: 82, severity: 'high', status: 'open',
    evidence: { applicationId: 'app-1' }, occurredAt: '2026-08-15T00:00:00Z',
    createdAt: '2026-08-15T00:00:00Z',
  }],
  audits: [{ id: 1, caseId: 'risk-1', action: 'signal_attached', actorAccountId: null,
    actorRole: 'service:marketplace', note: 'rule matched', createdAt: '2026-08-15T00:00:00Z' }],
}

enableAutoUnmount(afterEach)

function stubFetch() {
  const calls: { url: string; method?: string; body?: string }[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, method: init?.method, body: init?.body as string | undefined })
    const data = url.endsWith('/actions') ? { ...riskCase, status: 'resolved' }
      : url.includes('/risk/cases/risk-1') ? detail
      : { items: [riskCase], total: 1, limit: 50, offset: 0 }
    return { ok: true, headers: { get: () => 'application/json' },
      json: async () => ({ success: true, data }) }
  }))
  return calls
}

describe('RiskAdminPanel', () => {
  test('加载队列后展示证据和审计时间线，筛选参数可审计', async () => {
    const calls = stubFetch()
    const wrapper = mount(RiskAdminPanel)
    await flushPromises()

    expect(wrapper.text()).toContain('merchant_cancelled_engagement')
    expect(wrapper.text()).toContain('applicationId')
    expect(wrapper.text()).toContain('信号入案')

    await wrapper.find('select').setValue('in_review')
    await wrapper.findAll('button').find((button) => button.text() === '查询')!.trigger('click')
    await flushPromises()
    expect(calls.some((call) => call.url.includes('status=in_review'))).toBe(true)
  })

  test('解决案件要求备注并提交动作', async () => {
    const calls = stubFetch()
    const wrapper = mount(RiskAdminPanel)
    await flushPromises()

    const resolve = wrapper.findAll('button').find((button) => button.text() === '解决')!
    await resolve.trigger('click')
    expect(wrapper.get('[role="alert"]').text()).toContain('填写处置备注')
    expect(calls.some((call) => call.url.endsWith('/actions'))).toBe(false)

    await wrapper.get('textarea').setValue('已核对商家取消原因')
    await resolve.trigger('click')
    await flushPromises()
    const action = calls.find((call) => call.url.endsWith('/actions'))!
    expect(action.method).toBe('POST')
    expect(JSON.parse(action.body!)).toEqual({ action: 'resolve', note: '已核对商家取消原因' })
  })
})
