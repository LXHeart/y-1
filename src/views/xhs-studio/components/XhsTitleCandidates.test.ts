// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { computed } from 'vue'
import XhsTitleCandidates from './XhsTitleCandidates.vue'
import { useArticleCreation } from '../../../composables/useArticleCreation'
import { getPlatformFormatRule } from '../../../config/platform-format-rules'
import { XHS_STUDIO_CONTEXT_KEY } from '../types'
import type { XhsStudioContext } from '../types'

/**
 * 标题候选（方案 §6）：候选渲染（title+hook+字数）、radio 选用写 selectedTitle、
 * refresh→fetchTitles、loading 骨架、首次选定自动串联大纲→正文（真实 SSE 流）、
 * 安全徽标真实派生（不预置通过）。
 */

const calls: string[] = []

function sseResponse(content: string): Response {
  const payload = `data: ${JSON.stringify({ content })}\n\ndata: [DONE]\n\n`
  return new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(payload))
      controller.close()
    },
  }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

// 非 Response 返回值按 JSON 载荷处理（构造为 { ok, json } 假响应）。
function stubFetch(impl: (url: string) => Response | Record<string, unknown>) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    calls.push(url)
    const result = impl(url)
    if (result instanceof Response) return result
    return { ok: true, json: async () => result } as Response
  }))
}

function defaultStub() {
  stubFetch(url => {
    if (url === '/api/article-generation/outline') return sseResponse('一、开头\n二、要点')
    if (url === '/api/article-generation/content') return sseResponse('正文内容。')
    return new Response('{}', { status: 503 })
  })
}

function mockContext() {
  const engine = useArticleCreation()
  engine.platform.value = 'xiaohongshu'
  const format = {
    formatRule: computed(() => getPlatformFormatRule('xiaohongshu')),
  }
  const ctx = { engine, format } as unknown as XhsStudioContext
  return { engine, ctx }
}

function mountCandidates(ctx: XhsStudioContext) {
  return mount(XhsTitleCandidates, {
    global: { provide: { [XHS_STUDIO_CONTEXT_KEY]: ctx } },
  })
}

beforeEach(() => {
  calls.length = 0
  defaultStub()
})

afterEach(() => { vi.unstubAllGlobals() })

describe('候选渲染', () => {
  test('渲染 title+hook+字数（对照 20 字上限）', () => {
    const { engine, ctx } = mockContext()
    engine.titles.value = [
      { title: '短标题', hook: '钩子说明' },
      { title: '这是一个超过二十个字的长标题需要被计数并显示超限样式', hook: '' },
    ]
    const wrapper = mountCandidates(ctx)

    const cards = wrapper.findAll('.xhs-title-card')
    expect(cards).toHaveLength(2)
    expect(cards[0].text()).toContain('短标题')
    expect(cards[0].text()).toContain('钩子说明')
    expect(cards[0].get('.xhs-title-count').text()).toBe('3/20 字')
    // 超限候选计数标警示色（真实字数对照契约上限）。
    expect(cards[1].get('.xhs-title-count').text()).toContain('/20 字')
    expect(cards[1].get('.xhs-title-count').classes()).toContain('xhs-title-count-over')
  })

  test('loading 显示骨架不渲染假候选', () => {
    const { engine, ctx } = mockContext()
    engine.titlesLoading.value = true
    const wrapper = mountCandidates(ctx)
    expect(wrapper.find('.xhs-title-skeleton').exists()).toBe(true)
    expect(wrapper.find('.xhs-title-card').exists()).toBe(false)
  })

  test('空候选显示真实空态', () => {
    const { ctx } = mockContext()
    const wrapper = mountCandidates(ctx)
    expect(wrapper.text()).toContain('暂无候选标题')
  })
})

describe('选用（radio 语义）', () => {
  test('点选写 engine.selectedTitle，aria-checked 联动，emit select', async () => {
    const { engine, ctx } = mockContext()
    engine.titles.value = [{ title: '候选一', hook: '' }, { title: '候选二', hook: '' }]
    const wrapper = mountCandidates(ctx)

    const cards = wrapper.findAll('.xhs-title-card')
    expect(cards[0].attributes('aria-checked')).toBe('false')
    await cards[0].trigger('click')
    expect(engine.selectedTitle.value).toBe('候选一')
    expect(wrapper.findAll('.xhs-title-card')[0].attributes('aria-checked')).toBe('true')
    expect(wrapper.emitted('select')).toEqual([['候选一']])
  })

  test('首次选定自动串联 streamOutline→streamContent（真实 SSE 流入）', async () => {
    const { engine, ctx } = mockContext()
    engine.titles.value = [{ title: '候选一', hook: '' }]
    const wrapper = mountCandidates(ctx)

    await wrapper.get('.xhs-title-card').trigger('click')
    await flushPromises()

    expect(calls).toContain('/api/article-generation/outline')
    expect(calls).toContain('/api/article-generation/content')
    expect(engine.outline.value).toBe('一、开头\n二、要点')
    expect(engine.content.value).toBe('正文内容。')
    // 流完成：引擎进入 check（enterCheck），大纲/正文真实落位。
    expect(engine.stage.value).toBe('check')
  })

  test('已有正文时换选只换标题不重流（重流走正文区「重新生成」）', async () => {
    const { engine, ctx } = mockContext()
    engine.titles.value = [{ title: '候选一', hook: '' }, { title: '候选二', hook: '' }]
    engine.content.value = '已有正文，不自动重流。'
    const wrapper = mountCandidates(ctx)

    await wrapper.findAll('.xhs-title-card')[1].trigger('click')
    await flushPromises()
    expect(engine.selectedTitle.value).toBe('候选二')
    expect(calls.filter(url => url === '/api/article-generation/outline')).toEqual([])
    expect(engine.content.value).toBe('已有正文，不自动重流。')
  })

  test('【F-05】正文流失败/取消后（outline 残留、正文为空）换选标题仍重流大纲→正文', async () => {
    const { engine, ctx } = mockContext()
    engine.titles.value = [{ title: '候选一', hook: '' }, { title: '候选二', hook: '' }]
    engine.selectedTitle.value = '候选一'
    engine.outline.value = '上次流失败残留的旧大纲'
    // 正文为空：streamContent 报错/取消后的死路起点。
    const wrapper = mountCandidates(ctx)

    await wrapper.findAll('.xhs-title-card')[1].trigger('click')
    await flushPromises()

    expect(engine.selectedTitle.value).toBe('候选二')
    expect(calls).toContain('/api/article-generation/outline')
    expect(calls).toContain('/api/article-generation/content')
    expect(engine.outline.value).toBe('一、开头\n二、要点')
    expect(engine.content.value).toBe('正文内容。')
  })
})

describe('换一批', () => {
  test('refresh → fetchTitles 重新拉取真实候选', async () => {
    stubFetch(url => url === '/api/article-generation/titles'
      ? { success: true, data: { titles: [{ title: '新一候选', hook: '' }] } }
      : new Response('{}', { status: 503 }))
    const { engine, ctx } = mockContext()
    engine.topic.value = '选题主题'
    engine.titles.value = [{ title: '旧候选', hook: '' }]
    const wrapper = mountCandidates(ctx)

    await wrapper.get('.xhs-title-refresh').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    expect(calls).toContain('/api/article-generation/titles')
    expect(engine.titles.value.map(t => t.title)).toEqual(['新一候选'])
  })
})

describe('安全徽标（真实派生，不预置通过）', () => {
  test('标题期报告通过/提醒两态；无报告不显示；有正文时不显示（语境属正文）', () => {
    const { engine, ctx } = mockContext()
    engine.titles.value = [{ title: '候选一', hook: '' }]

    engine.safetyReport.value = null
    expect(mountCandidates(ctx).find('.xhs-title-safety').exists()).toBe(false)

    engine.safetyReport.value = { findings: [], lexiconVersion: 'v1', deepCheck: false } as never
    expect(mountCandidates(ctx).get('.xhs-title-safety').text()).toContain('通过')

    engine.safetyReport.value = {
      findings: [{ category: 'absolute_claims', severity: 'medium', match: '最好', index: 0, advice: '', deep: false }],
      lexiconVersion: 'v1', deepCheck: false,
    } as never
    expect(mountCandidates(ctx).get('.xhs-title-safety').text()).toContain('1 项提醒')

    engine.content.value = '正文出现后报告语境切换：标题区不再显示。'
    expect(mountCandidates(ctx).find('.xhs-title-safety').exists()).toBe(false)
  })
})
