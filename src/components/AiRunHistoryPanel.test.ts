// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import AiRunHistoryPanel from './AiRunHistoryPanel.vue'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('AiRunHistoryPanel', () => {
  test('renders the real run summary fields and refreshes on demand', async () => {
    const run = {
      runId: 'run-1', capability: 'text', provider: 'qwen', model: 'qwen-plus', status: 'completed',
      actualCents: 2, startedAt: '2026-08-05T01:02:03Z', completedAt: '2026-08-05T01:02:05Z',
      taskContext: {
        runId: 'run-1', capability: 'text', provider: 'qwen', model: 'qwen-plus',
        resolutionType: 'PLATFORM', priceTableVersion: 'v1', platformModelVersion: 3,
        fallbackAuthorized: true, startedAt: '2026-08-05T01:02:03Z',
      },
      content: null, inputTokens: null, outputTokens: null,
    }
    const fetchMock = vi.fn().mockResolvedValue(json([run]))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AiRunHistoryPanel)
    await flushPromises()

    expect(wrapper.text()).toContain('qwen-plus')
    expect(wrapper.text()).toContain('平台模型')
    expect(wrapper.text()).toContain('v1')
    expect(wrapper.text()).toContain('2 分')
    await wrapper.get('button[aria-label="刷新运行记录"]').trigger('click')
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  test('shows empty and error states without stale rows', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json({ success: false, error: '运行记录暂不可用' }, 503))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiRunHistoryPanel)
    await flushPromises()
    expect(wrapper.text()).toContain('暂无运行记录')

    await wrapper.get('button[aria-label="刷新运行记录"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('运行记录暂不可用')
    expect(wrapper.text()).not.toContain('qwen-plus')
  })
})
