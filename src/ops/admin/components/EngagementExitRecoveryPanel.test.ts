// @vitest-environment happy-dom
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import EngagementExitRecoveryPanel from './EngagementExitRecoveryPanel.vue'
import { useGrassland } from '../../../composables/useGrassland'

/**
 * 任务书 #103 C103-04：治理财务区退出资金队列面板（TC103-04-01/04 契约侧）。
 * FINANCE 重排理由必填；RISK 只读由服务端闸门兜底（前端按行渲染重排入口，无金额输入）。
 */

vi.mock('../../../composables/useGrassland', () => ({
  useGrassland: vi.fn(),
}))

const { __mocks } = vi.hoisted(() => ({
  __mocks: {
    listEngagementExitOperations: vi.fn(),
    retryEngagementExitOperation: vi.fn(),
    error: { value: '' },
  },
}))
;(useGrassland as unknown as ReturnType<typeof vi.fn>).mockReturnValue({
  listEngagementExitOperations: __mocks.listEngagementExitOperations,
  retryEngagementExitOperation: __mocks.retryEngagementExitOperation,
  error: __mocks.error,
})

function sampleRow(state: string) {
  return {
    operationId: 'op-0000000000000001',
    applicationId: 'app-000000000000001',
    taskId: 'task-1',
    kind: 'no_fault',
    state,
    attempts: 2,
    version: 5,
    lastErrorCode: null,
    nextAttemptAt: null,
    updatedAt: '2026-09-15T00:00:00Z',
    completedAt: null,
    legs: [
      { legKind: 'deposit_refund', economicKey: 'freebie-refund:app-1', amountCents: 10000, state: 'succeeded', financeReference: 'x' },
      { legKind: 'bounty_capture', economicKey: 'reservation-capture:app-1', amountCents: 0, state: 'not_required', financeReference: null },
      { legKind: 'bounty_release', economicKey: 'reservation-release:app-1', amountCents: 50000, state: state === 'succeeded' ? 'succeeded' : 'pending', financeReference: null },
    ],
  }
}

describe('EngagementExitRecoveryPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    __mocks.error.value = ''
  })

  it('renders queue rows with leg states and per-row retry for non-succeeded operations', async () => {
    __mocks.listEngagementExitOperations.mockResolvedValueOnce({
      items: [sampleRow('needs_review'), sampleRow('succeeded')],
      nextCursor: null,
    })
    const wrapper = mount(EngagementExitRecoveryPanel, {
      global: { stubs: { teleport: true } },
    })
    await flushPromises()
    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('待核对')
    expect(rows[0].text()).toContain('押金退还')
    expect(rows[0].find('[data-action="retry-exit-operation"]').exists()).toBe(true)
    expect(rows[1].text()).toContain('已收口')
    expect(rows[1].find('[data-action="retry-exit-operation"]').exists()).toBe(false)
  })

  it('requires a 5-500 char reason before submitting a retry with expectedVersion', async () => {
    __mocks.listEngagementExitOperations.mockResolvedValueOnce({ items: [sampleRow('retry_wait')], nextCursor: null })
    const wrapper = mount(EngagementExitRecoveryPanel, {
      global: { stubs: { teleport: true } },
    })
    await flushPromises()
    await wrapper.find('[data-action="retry-exit-operation"]').trigger('click')
    const modal = wrapper.find('[data-testid="exit-retry-reason"]')
    expect(modal.exists()).toBe(true)
    await wrapper.find('[data-testid="exit-retry-submit"]').trigger('click')
    expect(__mocks.retryEngagementExitOperation).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('理由长度')
    await modal.setValue('核对完成，恢复原键排队')
    __mocks.retryEngagementExitOperation.mockResolvedValueOnce({ state: 'pending' })
    await wrapper.find('[data-testid="exit-retry-submit"]').trigger('click')
    await flushPromises()
    expect(__mocks.retryEngagementExitOperation).toHaveBeenCalledWith(
      'op-0000000000000001', '核对完成，恢复原键排队', 5)
  })

  it('shows a service error without faking an empty queue', async () => {
    __mocks.listEngagementExitOperations.mockResolvedValueOnce(null)
    __mocks.error.value = '无权限'
    const wrapper = mount(EngagementExitRecoveryPanel, {
      global: { stubs: { teleport: true } },
    })
    await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toContain('无权限')
  })
})
