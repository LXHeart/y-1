// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ref } from 'vue'
import ArticleCreationView from './ArticleCreationView.vue'
import type { CreationHandoff } from '../types/ai-creation'

/**
 * ArticleCreationView 特征测试（重构安全网）。
 *
 * 锁定：五步导航骨架、主题阶段渲染、平台选择器默认值、
 * 「生成标题」按钮的可用条件与请求契约（URL/method/payload）、
 * 成功后进入标题选择阶段。
 * 组件依赖 inject('articleInitialTopic')（内部做非空断言），必须 provide。
 */

type FetchCall = { url: string; init?: RequestInit }

const calls: FetchCall[] = []

function stubFetch(impl: (call: FetchCall) => Partial<Response>) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const call = { url, init }
    calls.push(call)
    return impl(call)
  }))
}

beforeEach(() => {
  calls.length = 0
})

afterEach(() => {
  vi.unstubAllGlobals()
})

enableAutoUnmount(afterEach)

function mountView(handoff?: CreationHandoff | null) {
  return mount(ArticleCreationView, {
    props: { creationHandoff: handoff ?? null },
    global: {
      provide: { articleInitialTopic: ref('') },
    },
  })
}

describe('ArticleCreationView 渲染骨架与初始状态', () => {
  test('锁定五步导航与主题阶段标题', () => {
    const wrapper = mountView()

    expect(wrapper.findAll('.step-label').map((el) => el.text())).toEqual(['主题', '标题', '大纲', '正文', '配图'])
    expect(wrapper.get('.card-title').text()).toBe('先确定主题和发布平台')
    expect(wrapper.find('textarea.topic-input').exists()).toBe(true)
  })

  test('平台选择器锁定：微信/知乎/小红书/抖音，默认选中微信公众号', () => {
    const wrapper = mountView()

    const tablist = wrapper.get('[aria-label="文章平台"]')
    const buttons = tablist.findAll('button')
    expect(buttons.map((b) => b.text())).toEqual(['微信公众号', '知乎', '小红书', '抖音'])
    expect(buttons[0].classes()).toContain('platform-btn-active')
    expect(buttons[1].classes()).not.toContain('platform-btn-active')
    expect(buttons[2].classes()).not.toContain('platform-btn-active')
    expect(buttons[3].classes()).not.toContain('platform-btn-active')
  })

  test('生成标题按钮初始禁用，输入主题后启用', async () => {
    const wrapper = mountView()
    const primaryBtn = wrapper.get('.action-row .btn-primary')

    expect(primaryBtn.text()).toBe('生成标题')
    expect(primaryBtn.attributes('disabled')).toBe('')

    await wrapper.find('textarea.topic-input').setValue('职场沟通技巧')
    expect(primaryBtn.attributes('disabled')).toBeUndefined()
  })
})

describe('ArticleCreationView 标题生成交互', () => {
  test('点击生成标题：POST 正确的 URL 与 payload，成功后进入标题选择', async () => {
    stubFetch(() => ({
      ok: true,
      json: async () => ({
        success: true,
        data: {
          titles: [
            { title: '候选标题一', hook: 'hook-1' },
            { title: '候选标题二', hook: '' },
          ],
        },
      }),
    }))
    const wrapper = mountView()

    await wrapper.find('textarea.topic-input').setValue('职场沟通技巧')
    await wrapper.get('[aria-label="文章平台"]').findAll('button')[1].trigger('click') // 切到知乎
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    expect(calls).toHaveLength(1)
    expect(calls[0].url).toBe('/api/article-generation/titles')
    expect(calls[0].init?.method).toBe('POST')
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({ topic: '职场沟通技巧', platform: 'zhihu' })

    // 进入第二步：候选标题列表 + 自定义标题输入
    expect(wrapper.get('.card-title').text()).toBe('从候选标题里选一个方向')
    expect(wrapper.findAll('.title-item')).toHaveLength(2)
    expect(wrapper.find('#custom-title').exists()).toBe(true)
    const outlineBtn = wrapper.get('.action-row .btn-primary')
    expect(outlineBtn.text()).toBe('生成大纲')
    // selectedTitle 为空时「生成大纲」禁用
    expect(outlineBtn.attributes('disabled')).toBe('')
  })

  test('标题生成失败时展示错误卡，不切换阶段', async () => {
    stubFetch(() => ({
      ok: false,
      status: 500,
      json: async () => ({ success: false, error: '标题服务暂不可用' }),
    }))
    const wrapper = mountView()

    await wrapper.find('textarea.topic-input').setValue('餐饮创业复盘')
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    expect(wrapper.find('.error-card').exists()).toBe(true)
    expect(wrapper.find('.error-text').text()).toBe('标题服务暂不可用')
    expect(wrapper.get('.card-title').text()).toBe('先确定主题和发布平台')
  })

  test('挂载时不发起任何网络请求', async () => {
    stubFetch(() => ({ ok: true, json: async () => ({ success: true, data: {} }) }))
    mountView()
    await flushPromises()

    expect(calls).toEqual([])
  })
})

describe('ArticleCreationView 抖音平台接入', () => {
  test('选中抖音后展示图集短文案定位提示', async () => {
    const wrapper = mountView()

    expect(wrapper.find('.platform-mode-hint').exists()).toBe(false)
    await wrapper.findAll('.platform-btn')[3].trigger('click')

    const douyinBtn = wrapper.findAll('.platform-btn')[3]
    expect(douyinBtn.classes()).toContain('platform-btn-active')
    expect(wrapper.find('.platform-mode-hint').text()).toContain('图集短文案')
    expect(wrapper.find('.platform-mode-hint').text()).toContain('话题')
  })

  test('选中抖音生成标题：payload platform 沿用既有契约值 xiaohongshu', async () => {
    stubFetch(() => ({
      ok: true,
      json: async () => ({
        success: true,
        data: { titles: [{ title: '图集标题一', hook: '' }] },
      }),
    }))
    const wrapper = mountView()

    await wrapper.find('textarea.topic-input').setValue('探店图文')
    await wrapper.findAll('.platform-btn')[3].trigger('click') // 切到抖音
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    expect(calls).toHaveLength(1)
    expect(calls[0].url).toBe('/api/article-generation/titles')
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({ topic: '探店图文', platform: 'xiaohongshu' })
  })

  test('抖音的规范提示跟随抖音规则；切回公众号后切换为公众号规则', async () => {
    stubFetch(() => ({
      ok: true,
      json: async () => ({
        success: true,
        data: { titles: [{ title: '图集标题', hook: '' }] },
      }),
    }))
    const wrapper = mountView()

    await wrapper.findAll('.platform-btn')[3].trigger('click')
    await wrapper.find('textarea.topic-input').setValue('探店图文')
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    expect(wrapper.find('.format-rule-bar').text()).toContain('正文 15-300 字')
    expect(wrapper.find('.format-rule-bar').text()).toContain('标题上限 55 字')

    // 返回主题阶段后切换到公众号，再次生成时规范提示切换为公众号规则
    await wrapper.find('.btn-back').trigger('click')
    await wrapper.get('[aria-label="文章平台"]').findAll('button')[0].trigger('click')
    expect(wrapper.find('.platform-mode-hint').exists()).toBe(false)
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    expect(wrapper.find('.format-rule-bar').text()).toContain('正文 300-3000 字')
  })
})

describe('ArticleCreationView 平台规范提示条', () => {
  test('主题阶段不渲染规范提示条（无结果内容），标题阶段渲染字数范围与标题上限', async () => {
    stubFetch(() => ({
      ok: true,
      json: async () => ({
        success: true,
        data: { titles: [{ title: '候选标题', hook: '' }] },
      }),
    }))
    const wrapper = mountView()

    expect(wrapper.find('.format-rule-bar').exists()).toBe(false)

    await wrapper.find('textarea.topic-input').setValue('探店图文')
    await wrapper.findAll('.platform-btn')[2].trigger('click') // 小红书：50-1000 字、标题 20 字
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    const bar = wrapper.find('.format-rule-bar')
    expect(bar.exists()).toBe(true)
    expect(bar.text()).toContain('正文 50-1000 字')
    expect(bar.text()).toContain('标题上限 20 字')
    expect(bar.classes()).not.toContain('format-rule-bar-warn')
  })

  test('标题超过平台上限：非阻断提示出现，但「生成大纲」仍可用', async () => {
    stubFetch(() => ({
      ok: true,
      json: async () => ({
        success: true,
        data: { titles: [{ title: '候选标题', hook: '' }] },
      }),
    }))
    const wrapper = mountView()

    await wrapper.find('textarea.topic-input').setValue('探店图文')
    await wrapper.findAll('.platform-btn')[2].trigger('click') // 小红书：标题上限 20 字
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    await wrapper.find('#custom-title').setValue('这是一个远远超过小红书二十个字符限制的超长标题文案啊')

    const bar = wrapper.find('.format-rule-bar')
    expect(bar.classes()).toContain('format-rule-bar-warn')
    expect(bar.text()).toContain('标题已超过 20 字建议上限')
    // 非阻断：生成大纲按钮保持可用
    expect(wrapper.get('.action-row .btn-primary').attributes('disabled')).toBeUndefined()
  })

  test('正文阶段：内容超限给出非阻断提示，复制按钮不被禁用', async () => {
    stubFetch(() => ({
      ok: true,
      json: async () => ({
        success: true,
        data: { titles: [{ title: '候选标题', hook: '' }] },
      }),
    }))
    const wrapper = mountView()
    const vm = wrapper.vm as unknown as { content: string; stage: string }

    await wrapper.find('textarea.topic-input').setValue('探店图文')
    await wrapper.findAll('.platform-btn')[2].trigger('click') // 小红书：正文上限 1000 字
    await wrapper.get('.action-row .btn-primary').trigger('click')
    await flushPromises()

    vm.stage = 'content'
    vm.content = '字'.repeat(1200)
    await flushPromises()

    const bar = wrapper.find('.format-rule-bar')
    expect(bar.exists()).toBe(true)
    expect(bar.text()).toContain('正文 50-1000 字')
    expect(bar.classes()).toContain('format-rule-bar-warn')
    expect(bar.text()).toContain('超过建议上限 1000 字')
    // 非阻断：复制正文按钮仍可用
    expect(wrapper.get('.btn-sm').attributes('disabled')).toBeUndefined()
  })
})

describe('ArticleCreationView creationHandoff 预填', () => {
  function buildHandoff(platformId: CreationHandoff['platformId']): CreationHandoff {
    return {
      revision: 1,
      platformId,
      contentFormId: 'graphic',
      workflowId: 'longform',
      targetView: 'article',
      source: { type: 'independent' },
      prefill: { topic: '餐饮创业复盘' },
    }
  }

  test('挂载时传入真实 handoff：watch 正常执行不抛错，预填主题并选中对应平台', async () => {
    const wrapper = mountView(buildHandoff('zhihu'))
    await flushPromises()

    expect((wrapper.find('textarea.topic-input').element as HTMLTextAreaElement).value).toBe('餐饮创业复盘')
    const buttons = wrapper.get('[aria-label="文章平台"]').findAll('button')
    expect(buttons[1].classes()).toContain('platform-btn-active')
    expect(wrapper.find('.platform-mode-hint').exists()).toBe(false)
  })

  test('handoff platformId 为 douyin：进入抖音图集短文案模式', async () => {
    const wrapper = mountView(buildHandoff('douyin'))
    await flushPromises()

    const buttons = wrapper.get('[aria-label="文章平台"]').findAll('button')
    expect(buttons[3].classes()).toContain('platform-btn-active')
    expect(wrapper.find('.platform-mode-hint').exists()).toBe(true)
  })
})
