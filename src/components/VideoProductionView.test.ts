// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import VideoProductionView from '../views/video-production/VideoProductionView.vue'
import type { CreationHandoff } from '../types/ai-creation'

/**
 * VideoProductionView 特征测试（重构安全网）。
 *
 * 锁定：三步导航骨架、上传阶段的表单字段与默认值、
 * 「生成脚本」按钮的可用条件、挂载时拉取 capabilities。
 * 全部网络请求通过 mock fetch 拦截，无真实网络调用。
 */

const fetchUrls: string[] = []
const fetchCalls: Array<{ url: string; init?: RequestInit }> = []

function jsonResponse(data: unknown) {
  return {
    ok: true,
    status: 200,
    headers: { get: () => 'application/json' },
    json: async () => data,
  }
}

const douyinExtractPayload = {
  sourceUrl: 'https://v.douyin.com/xxxx/',
  videoId: 'video-1',
  author: '老王面馆',
  title: '深夜的烟火气',
  durationSeconds: 42,
  proxyVideoUrl: '/api/video-proxy/douyin/video-1',
  downloadVideoUrl: '/api/video-proxy/douyin/video-1/download',
  downloadAudioUrl: '/api/video-proxy/douyin/video-1/audio',
  usedSession: false,
  fetchStage: 'page_json',
}

const douyinAnalysisPayload = {
  video_script: '开场先拍锅气特写，再切到店主口播。',
  characters_description: '中年店主，围裙装扮。',
  scene_description: '夜市推位，暖黄灯光。',
  props_description: '炒锅、调料架。',
  voice_description: '中年男声，语速偏快。',
  run_id: 'run-1',
}

beforeEach(() => {
  fetchUrls.length = 0
  fetchCalls.length = 0
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    fetchUrls.push(url)
    fetchCalls.push({ url, init })
    if (url === '/api/douyin/extract-video') {
      return jsonResponse({ success: true, data: douyinExtractPayload })
    }
    if (url === '/api/douyin/analyze-video') {
      return jsonResponse({ success: true, data: douyinAnalysisPayload })
    }
    // 挂载时会拉取 /api/video-production/capabilities；fail-closed 语义下返回不可用即可
    return jsonResponse({ success: true, data: { videoGeneration: { available: false, reason: '视频生成服务即将上线' } } })
  }))
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

enableAutoUnmount(afterEach)

describe('VideoProductionView 渲染骨架与初始状态', () => {
  test('锁定三步导航与第一步标题', async () => {
    const wrapper = mount(VideoProductionView)
    await flushPromises()

    expect(wrapper.findAll('.step-label').map((el) => el.text())).toEqual(['上传素材', '编辑脚本', '生成视频'])
    expect(wrapper.find('.step-active .step-label').text()).toBe('上传素材')
    expect(wrapper.get('.card-title').text()).toBe('上传素材 & 填写店铺信息')
  })

  test('锁定上传入口与表单字段初始值', async () => {
    const wrapper = mount(VideoProductionView)
    await flushPromises()

    // 上传区：隐藏 file input + drop zone
    const fileInput = wrapper.find('input[type="file"]')
    expect(fileInput.exists()).toBe(true)
    expect(fileInput.attributes('accept')).toBe('image/*')
    expect(wrapper.find('.drop-zone').text()).toContain('拖拽图片到此处，或点击上传')
    expect(wrapper.find('.drop-zone').text()).toContain('0 / 9 张')

    // 行业类型：六个选项，默认「餐饮」
    const industrySelect = wrapper.get('#vp-industry')
    expect(industrySelect.findAll('option').map((o) => o.text())).toEqual(['餐饮', '零售', '美业', '健身', '教育培训', '其他'])
    expect((industrySelect.element as HTMLSelectElement).value).toBe('餐饮')

    // 发布平台：初始为空占位项
    const platformSelect = wrapper.get('#vp-platform')
    expect(platformSelect.findAll('option')[0].text()).toBe('请选择发布平台')
    expect((platformSelect.element as HTMLSelectElement).value).toBe('')
    expect(platformSelect.findAll('option').length).toBeGreaterThan(1)

    // 视频风格：默认「烟火纪实」
    const styleSelect = wrapper.get('#vp-style')
    expect((styleSelect.element as HTMLSelectElement).value).toBe('烟火纪实')

    // 店铺名称/地址/简介/自定义要求输入项存在
    expect(wrapper.find('#vp-shop-name').exists()).toBe(true)
    expect(wrapper.find('#vp-address').exists()).toBe(true)
    expect(wrapper.find('#vp-desc').exists()).toBe(true)
    expect(wrapper.find('#vp-prompt').exists()).toBe(true)
  })

  test('生成脚本按钮初始禁用（无图片/店铺名/平台）', async () => {
    const wrapper = mount(VideoProductionView)
    await flushPromises()

    const primaryBtn = wrapper.get('.action-row .btn-primary')
    expect(primaryBtn.text()).toBe('生成脚本')
    expect(primaryBtn.attributes('disabled')).toBe('')
  })

  test('挂载时拉取 capabilities', async () => {
    mount(VideoProductionView)
    await flushPromises()

    expect(fetchUrls).toEqual(['/api/video-production/capabilities'])
  })
})

function findButtonByText(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((btn) => btn.text().includes(text))
}

describe('VideoProductionView 可选输入方式', () => {
  test('输入方式区渲染且默认折叠', async () => {
    const wrapper = mount(VideoProductionView)
    await flushPromises()

    const referenceToggle = findButtonByText(wrapper, '粘贴参考视频链接（可选）')
    const topicToggle = findButtonByText(wrapper, '从热点选主题（可选）')
    expect(referenceToggle?.exists()).toBe(true)
    expect(topicToggle?.exists()).toBe(true)

    // 默认折叠：参考输入区与主题输入区均未展开
    expect(wrapper.find('.reference-area').exists()).toBe(false)
    expect(wrapper.find('.topic-area').exists()).toBe(false)
  })

  test('展开参考视频链接入口后可选平台，默认抖音面板', async () => {
    const wrapper = mount(VideoProductionView)
    await flushPromises()

    await findButtonByText(wrapper, '粘贴参考视频链接（可选）')!.trigger('click')
    expect(wrapper.find('.reference-area').exists()).toBe(true)

    const tabs = wrapper.findAll('.reference-platform-tab')
    expect(tabs.map((tab) => tab.text())).toEqual(['抖音', 'B 站'])
    expect(wrapper.find('.reference-input').attributes('placeholder')).toContain('抖音')

    await tabs[1].trigger('click')
    expect(wrapper.find('.reference-input').attributes('placeholder')).toContain('bilibili')
  })

  test('提取并分析后，分析产出可勾选带入自定义要求', async () => {
    const wrapper = mount(VideoProductionView)
    await flushPromises()

    await findButtonByText(wrapper, '粘贴参考视频链接（可选）')!.trigger('click')
    await wrapper.find('.reference-input').setValue('https://v.douyin.com/xxxx/')
    await findButtonByText(wrapper, '提取并分析')!.trigger('click')
    await flushPromises()

    // 提取与 AI 分析都调用了既有服务端代理契约
    expect(fetchUrls).toContain('/api/douyin/extract-video')
    expect(fetchUrls).toContain('/api/douyin/analyze-video')

    // DouyinParsePanel 展示提取结果
    expect(wrapper.find('.result-title').text()).toBe('深夜的烟火气')

    // 分析产出以可勾选项呈现（非回退卡片：脚本/人物/场景道具/音色）
    const options = wrapper.findAll('.reference-card-option')
    expect(options.length).toBe(4)
    expect(options.map((option) => option.text())).toContain('视频脚本文案')

    // 带入自定义要求
    await findButtonByText(wrapper, '带入自定义要求')!.trigger('click')
    const customPrompt = (wrapper.get('#vp-prompt').element as HTMLTextAreaElement).value
    expect(customPrompt).toContain('参考视频分析产出（仅为创作建议）')
    expect(customPrompt).toContain('开场先拍锅气特写')
    expect(wrapper.find('.reference-applied-hint').exists()).toBe(true)
  })

  test('热点主题输入可带入自定义要求', async () => {
    const wrapper = mount(VideoProductionView)
    await flushPromises()

    await findButtonByText(wrapper, '从热点选主题（可选）')!.trigger('click')
    await wrapper.find('.topic-input').setValue('城市夜骑')
    await findButtonByText(wrapper, '带入主题')!.trigger('click')

    const customPrompt = (wrapper.get('#vp-prompt').element as HTMLTextAreaElement).value
    expect(customPrompt).toBe('创作主题：城市夜骑')
  })
})

describe('VideoProductionView 任务上下文快照', () => {
  const snapshotId = '11111111-1111-1111-1111-111111111111'

  function handoff(taskMode: boolean): CreationHandoff {
    return {
      revision: 1,
      platformId: 'douyin',
      contentFormId: 'video',
      workflowId: 'video-script',
      targetView: 'video-production',
      source: taskMode
        ? { type: 'task', taskId: 'task-1', applicationId: 'application-1', taskVersion: 3 }
        : { type: 'independent' },
      contextSnapshotId: taskMode ? snapshotId : undefined,
      prefill: { storeName: '任务门店' },
    }
  }

  test('任务 handoff 的脚本请求携带冻结快照，独立模式不携带任务字段', async () => {
    const wrapper = mount(VideoProductionView, { props: { creationHandoff: handoff(true) } })
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      images: Array<{ id: string; dataUrl: string; name: string }>
      generateScript: () => Promise<void>
    }
    vm.images = [{ id: 'img-1', dataUrl: 'data:image/png;base64,AAAA', name: 'a.png' }]
    await vm.generateScript()

    const request = fetchCalls.find((call) => call.url === '/api/video-production/generate-script')
    expect(JSON.parse(String(request?.init?.body))).toMatchObject({
      targetPlatform: 'douyin',
      taskMode: true,
      contextSnapshotId: snapshotId,
    })

    await wrapper.setProps({ creationHandoff: { ...handoff(false), revision: 2 } })
    vm.images = [{ id: 'img-2', dataUrl: 'data:image/png;base64,BBBB', name: 'b.png' }]
    await vm.generateScript()
    const scriptCalls = fetchCalls.filter((call) => call.url === '/api/video-production/generate-script')
    const independent = JSON.parse(String(scriptCalls[scriptCalls.length - 1]?.init?.body))
    expect(independent).not.toHaveProperty('taskMode')
    expect(independent).not.toHaveProperty('contextSnapshotId')
  })

  test('脚本和视频创建始终复用同一个快照 ID', async () => {
    vi.stubGlobal('crypto', { randomUUID: () => 'operation-1' })
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      fetchCalls.push({ url, init })
      if (url === '/api/video-production/capabilities') {
        return jsonResponse({ available: true, reason: '' })
      }
      if (url === '/api/video-production/generate-script') {
        return new Response('data: {"content":"任务脚本"}\n\ndata: [DONE]\n\n', {
          status: 200, headers: { 'Content-Type': 'text/event-stream' },
        })
      }
      if (url === '/api/video-production/generate-video') {
        return jsonResponse({ success: true, data: { id: 'job-1' } })
      }
      if (url === '/api/video-production/jobs/job-1/download-url') {
        return jsonResponse({ downloadUrl: 'https://media.example.test/signed-video' })
      }
      return jsonResponse({ data: { status: 'succeeded', progress: 100, resultUrl: '/api/media/job-1' } })
    }))
    vi.spyOn(globalThis, 'setTimeout').mockImplementation(((handler: TimerHandler) => {
      if (typeof handler === 'function') handler()
      return 1
    }) as typeof setTimeout)

    const wrapper = mount(VideoProductionView, { props: { creationHandoff: handoff(true) } })
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      images: Array<{ id: string; dataUrl: string; name: string }>
      generateScript: () => Promise<void>
      startVideoGeneration: () => Promise<void>
    }
    vm.images = [{ id: 'img-1', dataUrl: 'data:image/png;base64,AAAA', name: 'a.png' }]
    await vm.generateScript()
    await vm.startVideoGeneration()

    const bodies = fetchCalls
      .filter((call) => call.url === '/api/video-production/generate-script'
        || call.url === '/api/video-production/generate-video')
      .map((call) => JSON.parse(String(call.init?.body)))
    expect(bodies).toHaveLength(2)
    expect(bodies.every((body) => body.taskMode === true)).toBe(true)
    expect(bodies.map((body) => body.contextSnapshotId)).toEqual([snapshotId, snapshotId])
    expect(fetchCalls.some((call) => call.url === '/api/video-production/jobs/job-1/download-url'))
      .toBe(true)
  })
})

describe('VideoProductionView 内容安全', () => {
  test('脚本流尾 safety 帧展示在可编辑脚本下方', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url === '/api/video-production/capabilities') {
        return jsonResponse({ available: false, reason: '测试环境不生成视频' })
      }
      const frames = [
        { content: '视频推广脚本' },
        { type: 'safety', safety: {
          findings: [{ category: 'diversion', severity: 'low', match: '私信我', index: 2, advice: '使用平台组件', deep: false }],
          lexiconVersion: 'lexicon-v1', deepCheck: false,
        } },
      ]
      const lines = frames.flatMap((frame) => [`data: ${JSON.stringify(frame)}`, ''])
      lines.push('data: [DONE]', '')
      return new Response(lines.join('\n'), {
        status: 200, headers: { 'Content-Type': 'text/event-stream' },
      })
    }))
    const wrapper = mount(VideoProductionView)
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      images: Array<{ id: string; dataUrl: string; name: string }>
      form: { shopName: string; targetPlatform: string }
      generateScript: () => Promise<void>
    }
    vm.images = [{ id: 'img-1', dataUrl: 'data:image/png;base64,AAAA', name: 'a.png' }]
    vm.form.shopName = '测试门店'
    vm.form.targetPlatform = 'douyin'

    await vm.generateScript()
    await flushPromises()

    expect(wrapper.get('.stream-textarea').element).toHaveProperty('value', '视频推广脚本')
    expect(wrapper.get('[aria-label="内容安全检查"]').text()).toContain('使用平台组件')
  })
})
