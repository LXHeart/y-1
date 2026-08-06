// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import VideoAnalysisView from './VideoAnalysisView.vue'

/**
 * VideoAnalysisView 特征测试。
 *
 * 锁定：重定位后的「视频参考提取」文案（作为视频制作的可选参考输入手段）、
 * 「去视频制作」引导按钮通过 open-view 事件切视图、双平台入口保留。
 * 全部网络请求通过 mock fetch 拦截，无真实网络调用。
 */

function jsonResponse(data: unknown) {
  return {
    ok: true,
    status: 200,
    headers: { get: () => 'application/json' },
    json: async () => data,
  }
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    // 挂载时刷新抖音登录态
    if (url === '/api/douyin/session') {
      return jsonResponse({ success: true, data: { status: 'missing', hasPersistedSession: false } })
    }
    return jsonResponse({ success: true, data: null })
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
})

enableAutoUnmount(afterEach)

describe('VideoAnalysisView 重定位文案', () => {
  test('页面标题与说明强调「视频制作的可选参考输入手段」', async () => {
    const wrapper = mount(VideoAnalysisView)
    await flushPromises()

    expect(wrapper.get('.view-title').text()).toBe('视频参考提取')
    expect(wrapper.get('.view-kicker').text()).toContain('视频制作')
    expect(wrapper.get('.view-copy').text()).toContain('可选参考输入')
    expect(wrapper.get('.view-copy').text()).toContain('只产生创作建议')
  })

  test('「去视频制作」引导链接通过 open-view 切到 video-production', async () => {
    const wrapper = mount(VideoAnalysisView)
    await flushPromises()

    const goButton = wrapper.findAll('button').find((btn) => btn.text().includes('去视频制作'))
    expect(goButton?.exists()).toBe(true)

    await goButton!.trigger('click')
    expect(wrapper.emitted('open-view')).toEqual([['video-production']])
  })

  test('保留抖音 / B 站双平台入口与提取交互结构', async () => {
    const wrapper = mount(VideoAnalysisView)
    await flushPromises()

    const tabs = wrapper.findAll('.platform-tab')
    expect(tabs.map((tab) => tab.text())).toEqual(['抖音', 'B 站'])
    expect(wrapper.find('#video-input').exists()).toBe(true)
    expect(wrapper.findAll('button').some((btn) => btn.text().includes('提取视频'))).toBe(true)
  })
})
