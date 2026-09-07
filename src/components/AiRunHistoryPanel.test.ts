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

describe('AiRunHistoryPanel（任务书 #92 C-06）', () => {
  function runFixture(overrides: Record<string, unknown> = {}) {
    return {
      runId: 'run-c6', capability: 'text', provider: 'qwen', model: 'qwen-plus', status: 'failed',
      actualCents: null, startedAt: '2026-09-07T01:02:03Z', completedAt: null,
      taskContext: {
        runId: 'run-c6', capability: 'text', provider: 'qwen', model: 'qwen-plus',
        resolutionType: 'BYOK', priceTableVersion: 'v1', platformModelVersion: null,
        fallbackAuthorized: false, startedAt: '2026-09-07T01:02:03Z',
      },
      content: null, inputTokens: null, outputTokens: null, ...overrides,
    }
  }

  test('TC-C06-002 失败行提供重试/继续编辑出口；重试载荷带 runId（沿用幂等键）后刷新状态', async () => {
    let failedNow = true
    vi.stubGlobal('fetch', vi.fn(async () => json(failedNow
      ? [runFixture()]
      : [runFixture({ status: 'completed', actualCents: 5, inputTokens: 120, outputTokens: 80 })])))
    const wrapper = mount(AiRunHistoryPanel)
    await flushPromises()
    expect(wrapper.find('[data-testid="retry-run"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="continue-edit"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="token-summary"]').text()).toContain('暂无数据')

    await wrapper.get('[data-testid="retry-run"]').trigger('click')
    const retryEvent = wrapper.emitted('retry-run')
    expect(retryEvent?.[0]?.[0]).toMatchObject({ runId: 'run-c6' })   // 幂等键随载荷
    failedNow = false
    await wrapper.get('button[aria-label="刷新运行记录"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已完成')
    expect(wrapper.get('[data-testid="token-summary"]').text()).toContain('120 入 / 80 出')
    // 重试成功后费用只记一次口径（单条记录 5 分），面板不重复累计
    expect(wrapper.text()).toContain('消耗合计 5 分')
  })

  test('TC-C06-003 项目关联过滤 + 权限撤销隐藏详情；乱序响应以最后一次为准', async () => {
    const runs = [
      runFixture({ runId: 'run-in', status: 'completed', actualCents: 3, inputTokens: 10, outputTokens: 5 }),
      runFixture({ runId: 'run-out', status: 'completed', actualCents: 7 }),
    ]
    vi.stubGlobal('fetch', vi.fn(async () => json(runs)))
    const wrapper = mount(AiRunHistoryPanel, {
      props: { draftId: 'draft-abcdef123456', associatedRunIds: ['run-in'] },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('关联项目 draft-ab…')
    expect(wrapper.text()).toContain('1 条记录')
    expect(wrapper.text()).toContain('消耗合计 3 分')
    expect(wrapper.text()).not.toContain('消耗合计 10 分')

    // 权限撤销（401/403 信封）：只显错误文案，不渲染表格详情
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ success: false, error: '未授权' }), {
      status: 403, headers: { 'Content-Type': 'application/json' },
    })))
    await wrapper.get('button[aria-label="刷新运行记录"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('未授权')
    expect(wrapper.find('.data-table').exists()).toBe(false)
  })
})
