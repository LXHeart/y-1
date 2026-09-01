// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import VideoTaskMonitorPanel from './VideoTaskMonitorPanel.vue'

/**
 * 任务书 #65 卡7：视频任务监控面板——汇总卡片/供应商表渲染、窗口切换重拉、空态与错误态。
 */

const metricsPayload = {
  window: '7d',
  taskCount: 12,
  successRate: 0.8333,
  cancelRate: 0.0833,
  avgPipelineSeconds: 245.6,
  providers: [
    { provider: 'sandbox', taskCount: 8, avgSeconds: 15.2, failureRate: 0 },
    { provider: 'seedance', taskCount: 4, avgSeconds: 58.1, failureRate: 0.25 },
  ],
  costVsRevenue: { costCents: 420, revenueCents: 960 },
  degraded: { slideshowRatio: 0.1667, noVoiceRatio: 0.5 },
  retryRatio: 0.3333,
  rerollRatio: 0.0833,
}

const fetchUrls: string[] = []

beforeEach(() => {
  fetchUrls.length = 0
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    fetchUrls.push(url)
    return {
      ok: true,
      status: 200,
      json: async () => ({ success: true, data: metricsPayload }),
    }
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('VideoTaskMonitorPanel', () => {
  test('渲染汇总卡片与供应商表，指标取自 metrics 端点', async () => {
    const wrapper = mount(VideoTaskMonitorPanel)
    await flushPromises()

    expect(fetchUrls).toContain('/api/admin/video-production/metrics?window=7d')
    expect(wrapper.findAll('[data-test="metric-card"]').length).toBe(5)
    expect(wrapper.get('[data-test="metric-card"]').text()).toContain('12')
    expect(wrapper.text()).toContain('83.3%')
    expect(wrapper.findAll('[data-test="provider-row"]').length).toBe(2)
    expect(wrapper.text()).toContain('seedance')
    expect(wrapper.text()).toContain('25.0%')
  })

  test('窗口切换到 30d 重新拉取；同窗重复点击不重拉', async () => {
    const wrapper = mount(VideoTaskMonitorPanel)
    await flushPromises()
    expect(fetchUrls).toHaveLength(1)

    await wrapper.get('[data-test="window-30d"]').trigger('click')
    await flushPromises()
    expect(fetchUrls).toHaveLength(2)
    expect(fetchUrls[1]).toContain('window=30d')

    await wrapper.get('[data-test="window-30d"]').trigger('click')
    await flushPromises()
    expect(fetchUrls).toHaveLength(2)
  })

  test('空窗口显示空态；请求失败显示错误', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => ({ success: true, data: { ...metricsPayload, taskCount: 0, providers: [] } }),
    })))
    const wrapper = mount(VideoTaskMonitorPanel)
    await flushPromises()
    expect(wrapper.text()).toContain('窗口内还没有视频任务')
    expect(wrapper.find('[data-test="provider-table"]').exists()).toBe(false)

    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: false,
      status: 500,
      json: async () => ({ error: '指标服务暂不可用' }),
    })))
    const failed = mount(VideoTaskMonitorPanel)
    await flushPromises()
    expect(failed.text()).toContain('指标服务暂不可用')
  })
})
