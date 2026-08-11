// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import AiCreationCenter from '../views/ai-center/AiCreationCenter.vue'
import CreationAssistantPanel from './CreationAssistantPanel.vue'
import type { CreationEntry } from '../types/ai-creation'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

function buttons(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('button')
}

function button(wrapper: ReturnType<typeof mount>, label: string) {
  if (label === '开始创作') return wrapper.get('button.primary-command')
  return buttons(wrapper).find((item) => item.text().includes(label))!
}

/** 按标签取顶部分栏，不按下标 —— 新增分栏（如「创作助手」）会平移下标并让断言错位。 */
function sectionTab(wrapper: ReturnType<typeof mount>, label: string) {
  return wrapper.findAll('[role="tab"]').find((item) => item.text().trim() === label)!
}

function choiceButton(wrapper: ReturnType<typeof mount>, groupLabel: string, label: string) {
  return wrapper
    .get(`[aria-label="${groupLabel}"]`)
    .findAll('button')
    .find((item) => {
      const strong = item.find('strong')
      return strong.exists() ? strong.text().trim() === label : item.text().trim() === label
    })!
}

function sse(frames: Array<Record<string, unknown>>): Response {
  const lines = frames.flatMap((frame) => [`data: ${JSON.stringify(frame)}`, ''])
  lines.push('data: [DONE]', '')
  return new Response(lines.join('\n'), { headers: { 'Content-Type': 'text/event-stream' } })
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((next) => { resolve = next })
  return { promise, resolve }
}

describe('AI 内容创作中心', () => {
  test('未登录访问运行记录或模型密钥时请求登录，并停留在创作入口', async () => {
    const wrapper = mount(AiCreationCenter, {
      props: { authenticated: false, entry: null },
      global: {
        stubs: {
          AiRunHistoryPanel: { template: '<div data-testid="run-history-panel" />' },
          AiProviderKeysPanel: { template: '<div data-testid="provider-keys-panel" />' },
        },
      },
    })
    const tabs = wrapper.findAll('[role="tab"]')

    expect(tabs.map((tab) => tab.text()))
      .toEqual(['开始创作', '创作助手', '运行记录', '素材库', '模型密钥'])

    // 除「开始创作」外每个分栏都要求登录（助手要按账号存草稿，同 runs/keys 口径）
    for (const label of ['创作助手', '运行记录', '素材库', '模型密钥']) {
      await sectionTab(wrapper, label).trigger('click')
    }

    expect(wrapper.emitted('request-login')).toHaveLength(4)
    expect(wrapper.findAll('[data-platform-id]')).toHaveLength(9)
    expect(wrapper.find('[data-testid="run-history-panel"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="provider-keys-panel"]').exists()).toBe(false)
    expect(tabs[0].attributes('aria-selected')).toBe('true')
  })

  test('登录后按 tab 懒挂载运行记录和模型密钥面板', async () => {
    const wrapper = mount(AiCreationCenter, {
      props: { authenticated: true, entry: null },
      global: {
        stubs: {
          AiRunHistoryPanel: { template: '<div data-testid="run-history-panel">运行记录面板</div>' },
          MediaLibraryPanel: { template: '<div data-testid="media-library-panel">素材库面板</div>' },
          AiProviderKeysPanel: { template: '<div data-testid="provider-keys-panel">模型密钥面板</div>' },
        },
      },
    })
    expect(wrapper.find('[data-testid="run-history-panel"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="provider-keys-panel"]').exists()).toBe(false)

    await sectionTab(wrapper, '运行记录').trigger('click')
    expect(wrapper.find('[data-testid="run-history-panel"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="provider-keys-panel"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-platform-id]')).toHaveLength(0)

    await sectionTab(wrapper, '模型密钥').trigger('click')
    expect(wrapper.find('[data-testid="run-history-panel"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="provider-keys-panel"]').exists()).toBe(true)

    await wrapper.setProps({ authenticated: false })
    expect(wrapper.find('[data-testid="provider-keys-panel"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-platform-id]')).toHaveLength(9)

    await wrapper.setProps({ authenticated: true })
    await sectionTab(wrapper, '运行记录').trigger('click')
    await wrapper.setProps({
      entry: {
        revision: 12,
        platformId: 'zhihu',
        contentFormId: 'graphic',
        source: { type: 'independent' },
        prefill: { topic: '任务带入的新创作' },
      },
    })
    expect(wrapper.find('[data-testid="run-history-panel"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-platform-id]')).toHaveLength(9)
  })

  test('第一步只展示九个发布平台，不先展示独立工具', () => {
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry: null } })

    expect(wrapper.findAll('[data-platform-id]')).toHaveLength(9)
    expect(wrapper.text()).toContain('选择发布平台')
    expect(wrapper.text()).not.toContain('爆款文章')
    expect(wrapper.text()).not.toContain('视频提取分析')
  })

  test('公众号图文独立创作发出文章工作流和完整 handoff', async () => {
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry: null } })

    await wrapper.get('[data-platform-id="wechat-official"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '图文').trigger('click')
    await choiceButton(wrapper, '创作来源', '独立创作').trigger('click')
    await wrapper.get('textarea[name="creation-topic"]').setValue('秋季新品发布')
    await button(wrapper, '开始创作').trigger('click')

    const handoff = wrapper.emitted('start-workflow')?.[0]?.[0] as Record<string, unknown>
    expect(handoff).toMatchObject({
      platformId: 'wechat-official', contentFormId: 'graphic', source: { type: 'independent' },
      workflowId: 'longform', targetView: 'article', prefill: { topic: '秋季新品发布' },
    })
    expect(typeof handoff.revision).toBe('number')
  })

  test('每次开始创作都签发新 revision，允许同一工作流接收新预填', async () => {
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry: null } })
    await wrapper.get('[data-platform-id="wechat-official"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '图文').trigger('click')
    await choiceButton(wrapper, '创作来源', '独立创作').trigger('click')
    await wrapper.get('textarea[name="creation-topic"]').setValue('第一版')
    await button(wrapper, '开始创作').trigger('click')
    await wrapper.get('textarea[name="creation-topic"]').setValue('第二版')
    await button(wrapper, '开始创作').trigger('click')

    const emitted = wrapper.emitted('start-workflow') as Array<[CreationEntry]>
    expect(emitted[1][0].revision).toBeGreaterThan(emitted[0][0].revision)
    expect(emitted[1][0].prefill?.topic).toBe('第二版')
  })

  test('未登录选择任务或门店来源时请求登录，不请求业务数据', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry: null } })

    await wrapper.get('[data-platform-id="xiaohongshu"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '图文').trigger('click')
    await choiceButton(wrapper, '创作来源', '从任务创作').trigger('click')

    expect(wrapper.emitted('request-login')).toHaveLength(1)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  test('抖音图文点亮后进入长图文工作流，不再提示尚未接入', async () => {
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry: null } })

    await wrapper.get('[data-platform-id="douyin"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '图文').trigger('click')
    await choiceButton(wrapper, '创作来源', '独立创作').trigger('click')
    await wrapper.get('textarea[name="creation-topic"]').setValue('门店探店')

    expect(wrapper.text()).not.toContain('该创作路径尚未接入')
    const start = button(wrapper, '开始创作')
    expect(start.attributes('disabled')).toBeUndefined()
    await start.trigger('click')

    const handoff = wrapper.emitted('start-workflow')?.[0]?.[0] as Record<string, unknown>
    expect(handoff).toMatchObject({
      platformId: 'douyin', contentFormId: 'graphic', source: { type: 'independent' },
      workflowId: 'longform', targetView: 'article',
    })
  })

  test('不支持的已规划组合显示尚未接入且不能开始', async () => {
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry: null } })

    await wrapper.get('[data-platform-id="moments"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '图片 + 文字').trigger('click')
    await choiceButton(wrapper, '创作来源', '独立创作').trigger('click')
    await wrapper.get('textarea[name="creation-topic"]').setValue('门店探店')

    expect(wrapper.text()).toContain('该创作路径尚未接入')
    expect(button(wrapper, '开始创作').attributes('disabled')).toBeDefined()
  })

  test('工作台传入任务入口后锁定任务平台和形式并带入引用', async () => {
    const entry: CreationEntry = {
      revision: 7,
      platformId: 'xiaohongshu',
      contentFormId: 'graphic',
      source: { type: 'task', taskId: 'task-1', applicationId: 'app-1', taskVersion: 3 },
      prefill: { topic: '新品探店', instructions: '突出招牌菜，不提竞品' },
    }
    const wrapper = mount(AiCreationCenter, { props: { authenticated: true, entry } })
    await flushPromises()

    expect(wrapper.text()).toContain('新品探店')
    expect(wrapper.text()).toContain('任务版本 3')
    expect(wrapper.text()).toContain('突出招牌菜，不提竞品')
    expect(wrapper.get('[data-testid="selection-summary"]').text()).toContain('小红书')

    await button(wrapper, '开始创作').trigger('click')
    const handoff = wrapper.emitted('start-workflow')?.[0]?.[0] as CreationEntry
    expect(handoff).toMatchObject({ ...entry, revision: expect.any(Number) })
    expect(handoff.revision).toBeGreaterThan(entry.revision)
  })

  test('创作助手收到完整任务来源和主题上下文', async () => {
    const entry: CreationEntry = {
      revision: 70,
      platformId: 'xiaohongshu',
      contentFormId: 'graphic',
      source: { type: 'task', taskId: 'task-70', applicationId: 'app-70', taskVersion: 6 },
      prefill: { topic: '新品探店', instructions: '必须出现门店名' },
    }
    const wrapper = mount(AiCreationCenter, {
      props: { authenticated: true, entry },
      global: {
        stubs: {
          CreationAssistantPanel: {
            props: ['source', 'topic', 'taskRequirements', 'platform', 'contentForm'],
            template: '<div data-testid="assistant-context" />',
          },
        },
      },
    })
    await flushPromises()
    await sectionTab(wrapper, '创作助手').trigger('click')

    const panel = wrapper.getComponent(CreationAssistantPanel)
    expect(panel.props('source')).toEqual(entry.source)
    expect(panel.props('topic')).toBe('新品探店')
    expect(panel.props('taskRequirements')).toBe('新品探店\n必须出现门店名')
  })

  test('创作助手分别保留原热点来源和结构化后的创作主题', async () => {
    const entry: CreationEntry = {
      revision: 71,
      platformId: 'douyin',
      contentFormId: 'video',
      source: { type: 'hot-topic', title: '城市夜经济升温', topicId: 'hot-71' },
      prefill: { topic: '夜经济里的小店经营机会' },
    }
    const wrapper = mount(AiCreationCenter, {
      props: { authenticated: true, entry },
      global: {
        stubs: {
          CreationAssistantPanel: {
            props: ['source', 'topic'],
            template: '<div data-testid="assistant-hot-context" />',
          },
        },
      },
    })
    await flushPromises()
    await sectionTab(wrapper, '创作助手').trigger('click')

    const panel = wrapper.getComponent(CreationAssistantPanel)
    expect(panel.props('source')).toEqual(entry.source)
    expect(panel.props('topic')).toBe('夜经济里的小店经营机会')
  })

  test('任务未提供内容形式时保留任务引用并要求用户显式选择', async () => {
    const entry: CreationEntry = {
      revision: 8,
      platformId: 'xiaohongshu',
      contentFormId: null,
      source: { type: 'task', taskId: 'task-2', applicationId: 'app-2', taskVersion: 1 },
      prefill: { topic: '新品试吃' },
    }
    const wrapper = mount(AiCreationCenter, { props: { authenticated: true, entry } })
    await flushPromises()

    expect(wrapper.get('[data-platform-id="xiaohongshu"]').attributes('disabled')).toBeDefined()
    expect(choiceButton(wrapper, '内容形式', '视频').attributes('disabled')).toBeUndefined()
    await choiceButton(wrapper, '内容形式', '视频').trigger('click')
    await button(wrapper, '开始创作').trigger('click')

    expect(wrapper.emitted('start-workflow')?.[0]?.[0]).toMatchObject({
      platformId: 'xiaohongshu',
      contentFormId: 'video',
      source: { type: 'task', taskId: 'task-2', applicationId: 'app-2', taskVersion: 1 },
      targetView: 'video-production',
    })
  })

  test('热点来源在补选平台和形式后仍保留热点上下文', async () => {
    const entry: CreationEntry = {
      revision: 9,
      platformId: null,
      contentFormId: null,
      source: { type: 'hot-topic', title: '城市夜经济升温', topicId: 'hot-1' },
      prefill: { topic: '城市夜经济升温' },
    }
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry } })

    await wrapper.get('[data-platform-id="zhihu"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '图文').trigger('click')
    await button(wrapper, '开始创作').trigger('click')

    expect(wrapper.emitted('start-workflow')?.[0]?.[0]).toMatchObject({
      platformId: 'zhihu',
      contentFormId: 'graphic',
      source: { type: 'hot-topic', title: '城市夜经济升温', topicId: 'hot-1' },
      prefill: { topic: '城市夜经济升温' },
    })
  })

  test('从热点创作可浏览热点、切换平台分组并选为选题', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url === '/api/homepage/hot-items') {
        return new Response(JSON.stringify({
          success: true,
          data: {
            provider: '60s',
            items: [],
            fetchedAt: '2026-08-06T06:00:00.000Z',
            groups: [
              { platform: 'douyin', label: '抖音', items: [{ rank: 1, title: '城市夜经济升温', hotValue: '999' }] },
              { platform: 'weibo', label: '微博', items: [{ rank: 1, title: '微博热搜话题', hotValue: '500' }] },
            ],
          },
        }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      return new Response(JSON.stringify({ success: true, data: [] }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }))
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry: null } })
    await wrapper.get('[data-platform-id="xiaohongshu"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '图文').trigger('click')
    await choiceButton(wrapper, '创作来源', '从热点创作').trigger('click')
    await flushPromises()

    expect(wrapper.get('.hot-picker-note').text()).toContain('来源 60s')
    expect(wrapper.get('.hot-picker-note').text()).toContain('抓取于')
    expect(wrapper.get('.hot-list').text()).toContain('城市夜经济升温')

    await wrapper.findAll('.hot-tabs button')[1].trigger('click')
    expect(wrapper.get('.hot-list').text()).toContain('微博热搜话题')
    await wrapper.findAll('.hot-tabs button')[0].trigger('click')

    await wrapper.get('button.hot-pick').trigger('click')
    expect((wrapper.get('textarea[name="creation-topic"]').element as HTMLTextAreaElement).value).toBe('城市夜经济升温')
    expect(wrapper.get('button.hot-pick').text()).toBe('已选')

    await button(wrapper, '开始创作').trigger('click')
    expect(wrapper.emitted('start-workflow')?.[0]?.[0]).toMatchObject({
      platformId: 'xiaohongshu',
      contentFormId: 'graphic',
      source: { type: 'hot-topic', title: '城市夜经济升温' },
      targetView: 'article',
    })
  })

  test('热点拆解迟到时不会覆盖后来选择的新热点', async () => {
    const topicResponse = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/topic-from-hot')) return topicResponse.promise
      return new Response(JSON.stringify({
        success: true,
        data: {
          provider: '60s',
          items: [
            { rank: 1, title: '热点甲' },
            { rank: 2, title: '热点乙' },
          ],
        },
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    const wrapper = mount(AiCreationCenter, { props: { authenticated: true, entry: null } })
    await wrapper.get('[data-platform-id="douyin"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '视频').trigger('click')
    await choiceButton(wrapper, '创作来源', '从热点创作').trigger('click')
    await flushPromises()
    await wrapper.findAll('button.hot-pick')[0].trigger('click')
    await button(wrapper, 'AI 拆解为结构化选题').trigger('click')
    await wrapper.findAll('button.hot-pick')[1].trigger('click')

    topicResponse.resolve(sse([{
      type: 'topic', topic: '热点甲的结构化选题', angle: '观察', thesis: '旧结果', audience: '用户',
    }]))
    await flushPromises()

    expect((wrapper.get('textarea[name="creation-topic"]').element as HTMLTextAreaElement).value)
      .toBe('热点乙')
  })

  test('热点拆解期间修改补充要求会使旧结果失效', async () => {
    const topicResponse = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/topic-from-hot')) return topicResponse.promise
      return new Response(JSON.stringify({
        success: true,
        data: { provider: '60s', items: [{ rank: 1, title: '城市夜经济' }] },
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    const wrapper = mount(AiCreationCenter, { props: { authenticated: true, entry: null } })
    await wrapper.get('[data-platform-id="douyin"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '视频').trigger('click')
    await choiceButton(wrapper, '创作来源', '从热点创作').trigger('click')
    await flushPromises()
    await wrapper.get('button.hot-pick').trigger('click')
    await wrapper.find('textarea[placeholder*="语气"]').setValue('原要求')
    await button(wrapper, 'AI 拆解为结构化选题').trigger('click')
    await wrapper.find('textarea[placeholder*="语气"]').setValue('新要求')

    topicResponse.resolve(sse([{
      type: 'topic', topic: '按原要求生成的旧选题', angle: '观察', thesis: '旧结果', audience: '用户',
    }]))
    await flushPromises()
    expect((wrapper.get('textarea[name="creation-topic"]').element as HTMLTextAreaElement).value)
      .toBe('城市夜经济')
  })

  test('离开热点来源会清理旧热点，迟到拆解也不会覆盖当前主题', async () => {
    const topicResponse = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/topic-from-hot')) return topicResponse.promise
      return new Response(JSON.stringify({
        success: true,
        data: { provider: '60s', items: [{ rank: 1, title: '旧热点' }] },
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    const wrapper = mount(AiCreationCenter, { props: { authenticated: true, entry: null } })
    await wrapper.get('[data-platform-id="douyin"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '视频').trigger('click')
    await choiceButton(wrapper, '创作来源', '从热点创作').trigger('click')
    await flushPromises()
    await wrapper.get('button.hot-pick').trigger('click')
    await button(wrapper, 'AI 拆解为结构化选题').trigger('click')
    await choiceButton(wrapper, '创作来源', '独立创作').trigger('click')
    await wrapper.get('textarea[name="creation-topic"]').setValue('用户的新主题')

    topicResponse.resolve(sse([{
      type: 'topic', topic: '旧热点的迟到结果', angle: '观察', thesis: '旧结果', audience: '用户',
    }]))
    await flushPromises()
    expect((wrapper.get('textarea[name="creation-topic"]').element as HTMLTextAreaElement).value)
      .toBe('用户的新主题')

    await choiceButton(wrapper, '创作来源', '从热点创作').trigger('click')
    expect(wrapper.text()).not.toContain('AI 拆解为结构化选题')
    expect(button(wrapper, '开始创作').attributes('disabled')).toBeDefined()
  })

  test('切换平台会失效热点拆解并清理旧热点上下文', async () => {
    const topicResponse = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/topic-from-hot')) return topicResponse.promise
      return new Response(JSON.stringify({
        success: true,
        data: { provider: '60s', items: [{ rank: 1, title: '旧平台热点' }] },
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    const wrapper = mount(AiCreationCenter, { props: { authenticated: true, entry: null } })
    await wrapper.get('[data-platform-id="douyin"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '视频').trigger('click')
    await choiceButton(wrapper, '创作来源', '从热点创作').trigger('click')
    await flushPromises()
    await wrapper.get('button.hot-pick').trigger('click')
    await button(wrapper, 'AI 拆解为结构化选题').trigger('click')

    await wrapper.get('[data-platform-id="wechat-channels"]').trigger('click')
    topicResponse.resolve(sse([{
      type: 'topic', topic: '旧平台迟到结果', angle: '观察', thesis: '旧结果', audience: '用户',
    }]))
    await flushPromises()
    await choiceButton(wrapper, '内容形式', '视频').trigger('click')
    await choiceButton(wrapper, '创作来源', '从热点创作').trigger('click')

    expect(wrapper.text()).not.toContain('AI 拆解为结构化选题')
    expect(button(wrapper, '开始创作').attributes('disabled')).toBeDefined()
  })

  test('参考素材要求输入链接，并把链接交给对应视频分析工作流', async () => {
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry: null } })
    await wrapper.get('[data-platform-id="bilibili"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '视频').trigger('click')
    await choiceButton(wrapper, '创作来源', '参考素材').trigger('click')

    expect(button(wrapper, '开始创作').attributes('disabled')).toBeDefined()
    await wrapper.get('textarea[name="reference-url"]').setValue('https://www.bilibili.com/video/BV1example')
    await button(wrapper, '开始创作').trigger('click')

    expect(wrapper.emitted('start-workflow')?.[0]?.[0]).toMatchObject({
      platformId: 'bilibili',
      source: { type: 'reference', sourceUrl: 'https://www.bilibili.com/video/BV1example' },
      targetView: 'video',
    })
  })

  test('抖音视频参考素材发出 reference-analyze 工作流并落到视频提取分析视图', async () => {
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry: null } })
    await wrapper.get('[data-platform-id="douyin"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '视频').trigger('click')
    await choiceButton(wrapper, '创作来源', '参考素材').trigger('click')
    await wrapper.get('textarea[name="reference-url"]').setValue('7.54 复制打开抖音 https://v.douyin.com/xxxx/')
    await button(wrapper, '开始创作').trigger('click')

    expect(wrapper.emitted('start-workflow')?.[0]?.[0]).toMatchObject({
      platformId: 'douyin',
      contentFormId: 'video',
      source: { type: 'reference', sourceUrl: '7.54 复制打开抖音 https://v.douyin.com/xxxx/' },
      workflowId: 'reference-analyze',
      targetView: 'video',
    })
  })

  test('非视频平台的参考素材组合保持尚未接入且不能开始', async () => {
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry: null } })
    await wrapper.get('[data-platform-id="xiaohongshu"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '图文').trigger('click')
    await choiceButton(wrapper, '创作来源', '参考素材').trigger('click')
    await wrapper.get('textarea[name="reference-url"]').setValue('https://example.com/share')

    expect(wrapper.text()).toContain('该创作路径尚未接入')
    expect(button(wrapper, '开始创作').attributes('disabled')).toBeDefined()
    await button(wrapper, '开始创作').trigger('click')
    expect(wrapper.emitted('start-workflow')).toBeUndefined()
  })

  test('大众点评图文进入点评文案工作流并落到图片评价文案视图', async () => {
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry: null } })
    await wrapper.get('[data-platform-id="dianping"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '图文').trigger('click')
    await choiceButton(wrapper, '创作来源', '独立创作').trigger('click')
    await wrapper.get('textarea[name="creation-topic"]').setValue('招牌牛肉面')
    await button(wrapper, '开始创作').trigger('click')

    expect(wrapper.emitted('start-workflow')?.[0]?.[0]).toMatchObject({
      platformId: 'dianping',
      contentFormId: 'graphic',
      source: { type: 'independent' },
      workflowId: 'review-copy',
      targetView: 'image',
      prefill: { topic: '招牌牛肉面' },
    })
  })

  test('快手与视频号视频进入视频脚本工作流并落到视频制作视图', async () => {
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry: null } })
    await wrapper.get('[data-platform-id="kuaishou"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '视频').trigger('click')
    await choiceButton(wrapper, '创作来源', '独立创作').trigger('click')
    await wrapper.get('textarea[name="creation-topic"]').setValue('后厨实拍')
    await button(wrapper, '开始创作').trigger('click')

    const emitted = wrapper.emitted('start-workflow') as Array<[Record<string, unknown>]>
    expect(emitted[0][0]).toMatchObject({
      platformId: 'kuaishou',
      contentFormId: 'video',
      workflowId: 'video-script',
      targetView: 'video-production',
    })

    await wrapper.get('[data-platform-id="wechat-channels"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '视频').trigger('click')
    await choiceButton(wrapper, '创作来源', '独立创作').trigger('click')
    await wrapper.get('textarea[name="creation-topic"]').setValue('门店日常')
    await button(wrapper, '开始创作').trigger('click')
    expect(emitted[1][0]).toMatchObject({
      platformId: 'wechat-channels',
      contentFormId: 'video',
      workflowId: 'video-script',
      targetView: 'video-production',
    })
  })

  test('从热点创作选中的热点主题进入视频制作工作流', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      success: true,
      data: {
        provider: '60s',
        items: [],
        groups: [
          { platform: 'douyin', label: '抖音', items: [{ rank: 1, title: '城市夜经济升温', hotValue: '999' }] },
        ],
      },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })))
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry: null } })
    await wrapper.get('[data-platform-id="douyin"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '视频').trigger('click')
    await choiceButton(wrapper, '创作来源', '从热点创作').trigger('click')
    await flushPromises()

    expect(button(wrapper, '开始创作').attributes('disabled')).toBeDefined()
    await wrapper.get('button.hot-pick').trigger('click')
    await button(wrapper, '开始创作').trigger('click')

    expect(wrapper.emitted('start-workflow')?.[0]?.[0]).toMatchObject({
      platformId: 'douyin',
      contentFormId: 'video',
      workflowId: 'video-script',
      targetView: 'video-production',
      source: { type: 'hot-topic', title: '城市夜经济升温' },
      prefill: { topic: '城市夜经济升温' },
    })
  })

  test('热点加载失败时显示错误提示，重新选择来源会自动重试', async () => {
    let callCount = 0
    vi.stubGlobal('fetch', vi.fn(async () => {
      callCount += 1
      if (callCount === 1) {
        return new Response(JSON.stringify({ success: false, error: '热点服务暂不可用' }), {
          status: 502,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      return new Response(JSON.stringify({
        success: true,
        data: { provider: '60s', items: [{ rank: 1, title: '重试后的热点' }] },
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    const wrapper = mount(AiCreationCenter, { props: { authenticated: false, entry: null } })
    await wrapper.get('[data-platform-id="zhihu"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '图文').trigger('click')
    await choiceButton(wrapper, '创作来源', '从热点创作').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('热点服务暂不可用')

    await choiceButton(wrapper, '创作来源', '独立创作').trigger('click')
    await choiceButton(wrapper, '创作来源', '从热点创作').trigger('click')
    await flushPromises()

    expect(callCount).toBe(2)
    expect(wrapper.get('.hot-list').text()).toContain('重试后的热点')
  })

  test('门店资料请求完成前禁止开始创作', async () => {
    let resolveProfile: ((value: Response) => void) | undefined
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      let data: unknown = null
      if (url === '/api/organizations') data = [{ id: 'org-1', name: '云朵餐饮' }]
      if (url === '/api/organizations/org-1/stores') data = [{ id: 'store-1', organizationId: 'org-1', name: '云朵面馆' }]
      if (url.endsWith('/profile')) {
        return new Promise<Response>((resolve) => { resolveProfile = resolve })
      }
      return new Response(JSON.stringify({ success: true, data }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }))
    const wrapper = mount(AiCreationCenter, { props: { authenticated: true, entry: null } })
    await wrapper.get('[data-platform-id="xiaohongshu"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '视频').trigger('click')
    await choiceButton(wrapper, '创作来源', '从门店创作').trigger('click')
    await flushPromises()
    await wrapper.get('select[name="organization"]').setValue('org-1')
    await flushPromises()
    await wrapper.get('select[name="store"]').setValue('store-1')

    expect(button(wrapper, '开始创作').attributes('disabled')).toBeDefined()
    resolveProfile?.(new Response(JSON.stringify({
      success: true,
      data: { storeId: 'store-1', address: null, description: null },
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))
    await flushPromises()
    expect(button(wrapper, '开始创作').attributes('disabled')).toBeUndefined()
  })

  test('门店来源只加载当前账号组织和其门店，选择后读取门店资料', async () => {
    const calls: Array<[string, RequestInit | undefined]> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      calls.push([url, init])
      let data: unknown = null
      if (url === '/api/organizations') data = [{ id: 'org-1', name: '云朵餐饮', ownerAccountId: 'acct-1', permissionTier: 'basic_publish', industry: 'catering', createdAt: null }]
      if (url === '/api/organizations/org-1/stores') data = [{ id: 'store-1', organizationId: 'org-1', name: '云朵面馆', status: 'active', createdAt: null }]
      if (url === '/api/organizations/org-1/stores/store-1/profile') data = { storeId: 'store-1', address: '{"address":"人民路 8 号"}', description: '手工面与现熬汤底', phone: null, businessHours: null, status: 'active', createdAt: null }
      return { ok: true, status: 200, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
    }))
    const wrapper = mount(AiCreationCenter, { props: { authenticated: true, entry: null } })

    await wrapper.get('[data-platform-id="xiaohongshu"]').trigger('click')
    await choiceButton(wrapper, '内容形式', '视频').trigger('click')
    await choiceButton(wrapper, '创作来源', '从门店创作').trigger('click')
    await flushPromises()
    await wrapper.get('select[name="organization"]').setValue('org-1')
    await flushPromises()
    await wrapper.get('select[name="store"]').setValue('store-1')
    await flushPromises()
    await button(wrapper, '开始创作').trigger('click')

    expect(calls.map(([url]) => url)).toEqual([
      '/api/organizations',
      '/api/organizations/org-1/stores',
      '/api/organizations/org-1/stores/store-1/profile',
    ])
    expect(calls.every(([, init]) => init?.credentials === 'include')).toBe(true)
    expect(wrapper.emitted('start-workflow')?.[0]?.[0]).toMatchObject({
      source: { type: 'store', organizationId: 'org-1', storeId: 'store-1' },
      targetView: 'video-production',
      prefill: { storeName: '云朵面馆', address: '人民路 8 号', storeDescription: '手工面与现熬汤底' },
    })
  })

  test('门店 handoff 自动恢复组织、门店和资料快照后允许继续创作', async () => {
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      calls.push(url)
      let data: unknown = null
      if (url === '/api/organizations') data = [{ id: 'org-1', name: '云朵餐饮' }]
      if (url === '/api/organizations/org-1/stores') {
        data = [{ id: 'store-1', organizationId: 'org-1', name: '云朵面馆' }]
      }
      if (url === '/api/organizations/org-1/stores/store-1/profile') {
        data = { storeId: 'store-1', address: '{"address":"人民路 8 号"}', description: '手工面' }
      }
      return new Response(JSON.stringify({ success: true, data }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }))
    const wrapper = mount(AiCreationCenter, {
      props: {
        authenticated: true,
        entry: {
          revision: 42,
          platformId: 'xiaohongshu',
          contentFormId: 'video',
          source: { type: 'store', organizationId: 'org-1', storeId: 'store-1' },
          prefill: { topic: '门店夏日新品' },
        },
      },
    })
    await flushPromises()

    expect(calls).toEqual([
      '/api/organizations',
      '/api/organizations/org-1/stores',
      '/api/organizations/org-1/stores/store-1/profile',
    ])
    expect(wrapper.get('select[name="organization"]').element).toHaveProperty('value', 'org-1')
    expect(wrapper.get('select[name="store"]').element).toHaveProperty('value', 'store-1')
    expect(button(wrapper, '开始创作').attributes('disabled')).toBeUndefined()

    await button(wrapper, '开始创作').trigger('click')
    expect(wrapper.emitted('start-workflow')?.[0]?.[0]).toMatchObject({
      source: { type: 'store', organizationId: 'org-1', storeId: 'store-1' },
      prefill: { topic: '门店夏日新品', storeName: '云朵面馆', address: '人民路 8 号' },
    })
  })

  test('切换非门店入口或登出后清除旧账号门店上下文', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      let data: unknown = null
      if (url === '/api/organizations') data = [{ id: 'org-old', name: '旧组织' }]
      if (url === '/api/organizations/org-old/stores') data = [{ id: 'store-old', organizationId: 'org-old', name: '旧门店' }]
      if (url.endsWith('/profile')) data = { storeId: 'store-old', description: '旧账号资料' }
      return new Response(JSON.stringify({ success: true, data }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }))
    const storeEntry: CreationEntry = {
      revision: 50,
      platformId: 'xiaohongshu',
      contentFormId: 'video',
      source: { type: 'store', organizationId: 'org-old', storeId: 'store-old' },
    }
    const wrapper = mount(AiCreationCenter, { props: { authenticated: true, entry: storeEntry } })
    await flushPromises()

    await wrapper.setProps({
      entry: {
        revision: 51,
        platformId: 'xiaohongshu',
        contentFormId: 'video',
        source: { type: 'independent' },
      },
    })
    await choiceButton(wrapper, '创作来源', '从门店创作').trigger('click')
    await flushPromises()
    expect(wrapper.get('select[name="organization"]').element).toHaveProperty('value', '')
    expect(wrapper.get('select[name="store"]').element).toHaveProperty('value', '')
    expect(button(wrapper, '开始创作').attributes('disabled')).toBeDefined()

    await wrapper.setProps({ authenticated: false })
    await wrapper.setProps({ authenticated: true, entry: storeEntry })
    await flushPromises()
    expect(fetch).toHaveBeenCalledTimes(7)
  })
})
