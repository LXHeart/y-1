// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import CardSeriesPanel from './CardSeriesPanel.vue'

/**
 * CardSeriesPanel 特征测试（任务书 #54 2026-08-30 修订）：拆卡对象是 prop 传入的正文——
 * 面板不再有主题输入；计划请求带 content 与模板描述词；单卡重试带 styleAnchor；保存两步链。
 */

function sse(frames: Array<Record<string, unknown>>): Response {
  return new Response(new ReadableStream({
    start(controller) {
      const lines = frames.flatMap((frame) => [`data: ${JSON.stringify(frame)}`, ''])
      lines.push('data: [DONE]', '')
      controller.enqueue(new TextEncoder().encode(lines.join('\n')))
      controller.close()
    },
  }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

function json(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}

const fetchMock = vi.fn()

const CONTENT = '新店开业，全场八折。招牌烤鱼 68 元一份，手工酸辣粉 22 元。'
  + '营业时间周一至周日 10:00-22:00，地铁 2 号线 A 口步行 300 米。开业前三天到店打卡送甜品。'

const planFrames = [
  { type: 'progress', message: '正在拆解卡片计划…' },
  { type: 'result', cards: [
    { title: '封面：开业福利', bullets: ['全场 8 折'], illustration: '门头插画', caption: '开业啦' },
    { title: '招牌菜', bullets: ['镇店烤鱼'], illustration: '菜品特写', caption: '必点' },
  ] },
]

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

enableAutoUnmount(afterEach)

function mountPanel() {
  return mount(CardSeriesPanel, {
    props: { platform: 'xiaohongshu', content: CONTENT },
  })
}

describe('CardSeriesPanel（小红书图文流内嵌）', () => {
  test('初始折叠，展开后渲染 12/8/3 模板与拆卡按钮', async () => {
    const wrapper = mountPanel()
    expect(wrapper.find('[data-test="card-series-plan"]').exists()).toBe(false)
    await wrapper.find('[data-test="card-series-toggle"]').trigger('click')
    expect(wrapper.findAll('input[name="card-style"]')).toHaveLength(12)
    expect(wrapper.findAll('input[name="card-layout"]')).toHaveLength(8)
    expect(wrapper.findAll('input[name="card-palette"]')).toHaveLength(3)
    expect(wrapper.find('[data-test="card-series-theme"]').exists()).toBe(false) // 无主题输入——正文由 prop 提供
  })

  test('拆卡请求带 prop 正文 content 与模板描述词，计划进入编辑段', async () => {
    fetchMock.mockResolvedValueOnce(sse(planFrames))
    const wrapper = mountPanel()
    await wrapper.find('[data-test="card-series-toggle"]').trigger('click')
    await wrapper.find('[data-test="card-series-plan"]').trigger('click')
    await flushPromises()

    const request = fetchMock.mock.calls[0]
    expect(request[0]).toBe('/api/card-series/plan')
    const body = JSON.parse(request[1].body as string)
    expect(body.content).toContain('全场八折')
    expect(body.platform).toBe('xiaohongshu')
    expect(body.styleText).toContain('圆润造型')
    expect(wrapper.findAll('[data-test="card-series-plan-card"]')).toHaveLength(2)
  })

  test('拆卡请求剥离正文末尾话题标签行（任务书 #60）', async () => {
    fetchMock.mockResolvedValueOnce(sse(planFrames))
    const wrapper = mount(CardSeriesPanel, {
      props: { platform: 'xiaohongshu', content: `${CONTENT}\n\n#探店 #开业酬宾` },
    })
    await wrapper.find('[data-test="card-series-toggle"]').trigger('click')
    await wrapper.find('[data-test="card-series-plan"]').trigger('click')
    await flushPromises()

    const body = JSON.parse(fetchMock.mock.calls[0][1].body as string)
    expect(body.content).toBe(CONTENT)
    expect(body.content).not.toContain('#探店')
  })

  test('生成与单卡重试：cards 回传、失败卡可重试带 styleAnchor', async () => {
    fetchMock.mockResolvedValueOnce(sse(planFrames))
    fetchMock.mockResolvedValueOnce(json({
      success: true,
      data: { cards: [
        { index: 0, title: '封面：开业福利', ok: true, url: '/api/article-generation/generated-images/a', revisedPrompt: '首图锚' },
        { index: 1, title: '招牌菜', ok: false, errorReason: 'provider down' },
      ] },
    }))
    const wrapper = mountPanel()
    await wrapper.find('[data-test="card-series-toggle"]').trigger('click')
    await wrapper.find('[data-test="card-series-plan"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="card-series-generate"]').trigger('click')
    await flushPromises()

    const body = JSON.parse(fetchMock.mock.calls[1][1].body as string)
    expect(body.cards).toHaveLength(2)
    expect(wrapper.find('[data-test="card-series-failed"]').text()).toContain('provider down')

    fetchMock.mockResolvedValueOnce(json({
      success: true,
      data: { cards: [{ index: 0, title: '招牌菜', ok: true, url: '/api/article-generation/generated-images/b' }] },
    }))
    await wrapper.find('[data-test="card-series-retry"]').trigger('click')
    await flushPromises()
    const retryBody = JSON.parse(fetchMock.mock.calls[2][1].body as string)
    expect(retryBody.cards).toHaveLength(1)
    expect(retryBody.styleAnchor).toBe('首图锚')
    expect(wrapper.find('[data-test="card-series-failed"]').exists()).toBe(false)
  })

  test('保存到素材库：persist + content-assets 两步链', async () => {
    fetchMock.mockResolvedValueOnce(sse(planFrames))
    fetchMock.mockResolvedValueOnce(json({
      success: true,
      data: { cards: [
        { index: 0, title: '封面', ok: true, url: '/api/article-generation/generated-images/x', revisedPrompt: '锚' },
      ] },
    }))
    fetchMock.mockResolvedValueOnce(json({ success: true, data: { mediaId: 'media-1' } }))
    fetchMock.mockResolvedValueOnce(json({ success: true, data: { id: 'asset-1' } }))

    const wrapper = mountPanel()
    await wrapper.find('[data-test="card-series-toggle"]').trigger('click')
    await wrapper.find('[data-test="card-series-plan"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="card-series-generate"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="card-series-save"]').trigger('click')
    await flushPromises()

    expect(fetchMock.mock.calls[2][0]).toBe('/api/card-series/cards/x/persist')
    expect(fetchMock.mock.calls[3][0]).toBe('/api/content-assets')
    expect(JSON.parse(fetchMock.mock.calls[3][1].body as string).mediaId).toBe('media-1')
    expect(wrapper.text()).toContain('已保存')
  })

  test('成功卡放大（任务书 #57）：放大按钮与点缩略图均 emit open-lightbox 带 url；失败卡无放大', async () => {
    fetchMock.mockResolvedValueOnce(sse(planFrames))
    fetchMock.mockResolvedValueOnce(json({
      success: true,
      data: { cards: [
        { index: 0, title: '封面：开业福利', ok: true, url: '/api/article-generation/generated-images/zoom-a' },
        { index: 1, title: '招牌菜', ok: false, errorReason: 'provider down' },
      ] },
    }))
    const wrapper = mountPanel()
    await wrapper.find('[data-test="card-series-toggle"]').trigger('click')
    await wrapper.find('[data-test="card-series-plan"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="card-series-generate"]').trigger('click')
    await flushPromises()

    // 仅成功卡有一个放大入口；失败卡只有重试
    expect(wrapper.findAll('[data-test="card-series-zoom"]')).toHaveLength(1)
    await wrapper.get('[data-test="card-series-zoom"]').trigger('click')
    await wrapper.find('[data-test="card-series-image"]').trigger('click')

    expect(wrapper.emitted('open-lightbox')).toEqual([
      ['/api/article-generation/generated-images/zoom-a'],
      ['/api/article-generation/generated-images/zoom-a'],
    ])
  })
})
