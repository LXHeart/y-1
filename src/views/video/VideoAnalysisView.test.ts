// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import VideoAnalysisView from '../../views/video/VideoAnalysisView.vue'
import type { AiContentFormId, AiPlatformId, CreationHandoff } from '../../types/ai-creation'

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
    text: async () => JSON.stringify(data),
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

  test('分析结果按视频、长文与点评图文分发完整 handoff', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url === '/api/douyin/session') {
        return jsonResponse({ success: true, data: { status: 'missing', hasPersistedSession: false } })
      }
      if (url === '/api/douyin/extract-video') {
        return jsonResponse({ success: true, data: {
          sourceUrl: 'https://v.douyin.com/example', proxyVideoUrl: '/api/douyin/proxy/example',
          downloadVideoUrl: '/api/douyin/download/example', downloadAudioUrl: '/api/douyin/audio/example',
          usedSession: false, fetchStage: 'page_json', title: '夏日饮品实拍',
        } })
      }
      if (url === '/api/douyin/analyze-video') {
        return jsonResponse({ success: true, data: {
          video_script: '开场展示饮品，随后介绍口感',
          characters_description: '年轻顾客',
          scene_description: '明亮门店',
        } })
      }
      if (url.startsWith('/api/creation-generations')) {
        return jsonResponse({ success: true, data: { items: [] } })
      }
      return jsonResponse({ success: true, data: null })
    }))

    const cases: Array<{
      platformId: AiPlatformId
      contentFormId: AiContentFormId
      workflowId: string
      targetView: string
    }> = [
      { platformId: 'xiaohongshu', contentFormId: 'video', workflowId: 'video-script', targetView: 'video-production' },
      { platformId: 'zhihu', contentFormId: 'graphic', workflowId: 'longform', targetView: 'article' },
      { platformId: 'dianping', contentFormId: 'graphic', workflowId: 'review-copy', targetView: 'image' },
    ]

    for (const item of cases) {
      const handoff: CreationHandoff = {
        revision: 1,
        platformId: item.platformId,
        contentFormId: item.contentFormId,
        source: { type: 'reference', sourceUrl: 'https://v.douyin.com/example' },
        workflowId: 'reference-analyze',
        targetView: 'video',
        prefill: { referencePlatform: 'douyin' },
      }
      const wrapper = mount(VideoAnalysisView, { props: { creationHandoff: handoff } })
      await flushPromises()
      await wrapper.get('button.btn-primary').trigger('click')
      await flushPromises()
      const analyze = wrapper.findAll('button').find((button) => button.text() === '分析视频')
      expect(analyze).toBeDefined()
      await analyze!.trigger('click')
      await flushPromises()
      const continueButton = wrapper.findAll('button').find((button) => button.text() === '带入创作')
      expect(continueButton).toBeDefined()
      await continueButton!.trigger('click')
      const emitted = wrapper.emitted('start-workflow')?.[0]?.[0] as CreationHandoff
      expect(emitted).toMatchObject({
        platformId: item.platformId,
        contentFormId: item.contentFormId,
        workflowId: item.workflowId,
        targetView: item.targetView,
        source: { type: 'reference', sourceUrl: 'https://v.douyin.com/example' },
        prefill: {
          topic: '夏日饮品实拍',
          referencePlatform: 'douyin',
        },
      })
      expect(emitted.prefill?.instructions).toContain('脚本与字幕：开场展示饮品')
      wrapper.unmount()
    }
  })
})

describe('视频复刻分镜接线（PRD §4.4）', () => {
  function stubRecreationFlow() {
    const analyzeBodies: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: { body?: string }) => {
      if (url === '/api/douyin/session') {
        return jsonResponse({ success: true, data: { status: 'missing', hasPersistedSession: false } })
      }
      if (url === '/api/douyin/extract-video') {
        return jsonResponse({ success: true, data: {
          sourceUrl: 'https://v.douyin.com/example', proxyVideoUrl: '/api/douyin/proxy/example',
          downloadVideoUrl: '/api/douyin/download/example', downloadAudioUrl: '/api/douyin/audio/example',
          usedSession: false, fetchStage: 'page_json', title: '夏日饮品实拍',
        } })
      }
      if (url === '/api/douyin/analyze-video') {
        analyzeBodies.push(init?.body ?? '')
        return jsonResponse({ success: true, data: {
          scenes: [
            {
              shotDescription: '中景正面视角，镜头缓慢推进',
              characterDescription: '年轻女性博主',
              actionMovement: '举起饮品',
              dialogueVoiceover: '这杯太好喝了',
              sceneEnvironment: '明亮门店吧台',
            },
          ],
          overallStyle: '日系清新',
          runId: 'chatcmpl-r',
        } })
      }
      if (url.startsWith('/api/creation-generations')) {
        return jsonResponse({ success: true, data: { items: [] } })
      }
      return jsonResponse({ success: true, data: null })
    }))
    return analyzeBodies
  }

  test('提取成功后出现复刻入口；分镜分析以 mode=recreation 调用并渲染分镜面板', async () => {
    const analyzeBodies = stubRecreationFlow()

    const wrapper = mount(VideoAnalysisView)
    await flushPromises()

    expect(wrapper.find('.recreation-card').exists()).toBe(false)

    await wrapper.get('#video-input').setValue('7.54 复制打开抖音 https://v.douyin.com/example')
    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()

    expect(wrapper.find('.recreation-card').exists()).toBe(true)
    expect(wrapper.find('.recreation-panel').exists()).toBe(false)

    const recreationButton = wrapper.findAll('button')
      .find((button) => button.text().includes('生成复刻分镜'))
    expect(recreationButton).toBeDefined()
    await recreationButton!.trigger('click')
    await flushPromises()

    expect(analyzeBodies).toHaveLength(1)
    expect(JSON.parse(analyzeBodies[0])).toMatchObject({
      proxyVideoUrl: '/api/douyin/proxy/example',
      mode: 'recreation',
    })
    expect(wrapper.find('.recreation-panel').exists()).toBe(true)
    expect(wrapper.get('.style-text').text()).toBe('日系清新')
    expect(wrapper.get('.scene-field .field-value').text())
      .toBe('中景正面视角，镜头缓慢推进')
    wrapper.unmount()
  })

  test('分镜分析失败展示错误且不渲染分镜面板', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url === '/api/douyin/session') {
        return jsonResponse({ success: true, data: { status: 'missing', hasPersistedSession: false } })
      }
      if (url === '/api/douyin/extract-video') {
        return jsonResponse({ success: true, data: {
          sourceUrl: 'https://v.douyin.com/example', proxyVideoUrl: '/api/douyin/proxy/example',
          downloadVideoUrl: '/api/douyin/download/example', downloadAudioUrl: '/api/douyin/audio/example',
          usedSession: false, fetchStage: 'page_json', title: '夏日饮品实拍',
        } })
      }
      if (url === '/api/douyin/analyze-video') {
        return jsonResponse({ success: false, error: '复刻分析暂不支持分段视频' })
      }
      return jsonResponse({ success: true, data: null })
    }))

    const wrapper = mount(VideoAnalysisView)
    await flushPromises()

    await wrapper.get('#video-input').setValue('7.54 复制打开抖音 https://v.douyin.com/example')
    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()

    const recreationButton = wrapper.findAll('button')
      .find((button) => button.text().includes('生成复刻分镜'))
    await recreationButton!.trigger('click')
    await flushPromises()

    expect(wrapper.get('.recreation-error').text()).toBe('复刻分析暂不支持分段视频')
    expect(wrapper.find('.recreation-panel').exists()).toBe(false)
    wrapper.unmount()
  })
})
