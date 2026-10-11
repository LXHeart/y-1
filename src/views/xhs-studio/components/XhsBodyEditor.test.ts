// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { computed } from 'vue'
import XhsBodyEditor from './XhsBodyEditor.vue'
import { useArticleCreation } from '../../../composables/useArticleCreation'
import { useArticleFormatRule } from '../../article/composables/useArticleFormatRule'
import { XHS_STUDIO_CONTEXT_KEY } from '../types'
import type { XhsStudioContext } from '../types'

/**
 * 正文编辑+统计（方案 §6）：v-model 双向、字数（剥图片 md）/段落统计、流式 loading、
 * 【F3】stage=check 时仍渲染并显示「校对就绪·去校对」（不自动切步）、cancel/regenerate、
 * 大纲折叠区。
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

function stubFetch(impl: (url: string) => Response) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    calls.push(url)
    return impl(url)
  }))
}

function mockContext(readonly = false) {
  const engine = useArticleCreation()
  engine.platform.value = 'xiaohongshu'
  const format = useArticleFormatRule({
    platform: engine.platform,
    selectedTitle: engine.selectedTitle,
    content: engine.content,
  })
  const ctx = {
    engine,
    autosave: { readonly: computed(() => readonly) },
    format,
  } as unknown as XhsStudioContext
  return { engine, ctx }
}

function mountEditor(ctx: XhsStudioContext) {
  return mount(XhsBodyEditor, {
    global: { provide: { [XHS_STUDIO_CONTEXT_KEY]: ctx } },
  })
}

beforeEach(() => {
  calls.length = 0
  stubFetch(url => url === '/api/article-generation/outline'
    ? sseResponse('一、开头\n二、要点')
    : url === '/api/article-generation/content'
      ? sseResponse('重流的正文。')
      : new Response('{}', { status: 503 }))
})

afterEach(() => { vi.unstubAllGlobals() })

describe('统计（纯前端真实计算）', () => {
  test('字数剥图片 markdown 后计长（同 useArticleFormatRule 口径，含换行）；段落=非空行数', () => {
    const { engine, ctx } = mockContext()
    // 剥图片 md 后 '一二三\n\n\n四五六'：6 汉字 + 3 换行 = 9（同引擎口径含换行）；非空行 2 段（图片行不计段）。
    engine.content.value = '一二三\n![配图](https://example.com/a.png)\n\n四五六'
    const wrapper = mountEditor(ctx)
    expect(wrapper.get('.xhs-body-stats').text()).toContain('9 字')
    expect(wrapper.get('.xhs-body-stats').text()).toContain('2 段')
  })

  test('空正文统计为 0 字 0 段', () => {
    const { ctx } = mockContext()
    const wrapper = mountEditor(ctx)
    expect(wrapper.get('.xhs-body-stats').text()).toContain('0')
  })
})

describe('v-model 双向', () => {
  test('textarea 输入写回引擎 content 并 emit update:content', async () => {
    const { engine, ctx } = mockContext()
    const wrapper = mountEditor(ctx)

    await wrapper.get('#xhs-body-input').setValue('手工编辑的正文内容')
    expect(engine.content.value).toBe('手工编辑的正文内容')
    // tsconfig lib=ES2020 无 Array.prototype.at，用 slice 取末次事件。
    expect(wrapper.emitted('update:content')?.slice(-1)[0]).toEqual(['手工编辑的正文内容'])
  })
})

describe('流式与大纲', () => {
  test('loading：降透明 + 「生成中」提示；大纲折叠区展示流式大纲', () => {
    const { engine, ctx } = mockContext()
    engine.outline.value = '流式中的大纲'
    engine.contentLoading.value = true
    const wrapper = mountEditor(ctx)

    expect(wrapper.find('.xhs-body-streaming').exists()).toBe(true)
    expect(wrapper.get('.xhs-body-loading-note').text()).toContain('生成中')
    expect(wrapper.get('.xhs-body-outline').text()).toContain('流式中的大纲')
    expect(wrapper.get('.xhs-body-outline').attributes('open')).toBeDefined()
  })

  test('loading 中「取消」emit cancel 并中止引擎流', async () => {
    const { engine, ctx } = mockContext()
    const cancelSpy = vi.spyOn(engine, 'cancel')
    engine.contentLoading.value = true
    const wrapper = mountEditor(ctx)

    await wrapper.get('.xhs-body-actions button').trigger('click')
    expect(wrapper.emitted('cancel')).toHaveLength(1)
    expect(cancelSpy).toHaveBeenCalledTimes(1)
  })
})

describe('【F3】校对就绪入口（本地用户步）', () => {
  test('stage=check 时组件仍渲染（不自动切步）且显示「校对就绪·去校对」；点击 emit enterProof', async () => {
    const { engine, ctx } = mockContext()
    engine.content.value = '生成完成的正文。'
    engine.stage.value = 'check'
    const wrapper = mountEditor(ctx)

    expect(wrapper.find('.xhs-body-editor').exists()).toBe(true)
    const proofButton = wrapper.get('.xhs-body-actions .gl-btn-primary')
    expect(proofButton.text()).toContain('去校对')
    await proofButton.trigger('click')
    expect(wrapper.emitted('enterProof')).toHaveLength(1)
  })

  test('stage 仍在 content（流未收口）时不显示去校对入口', () => {
    const { engine, ctx } = mockContext()
    engine.content.value = '正文还在编辑。'
    engine.stage.value = 'content'
    const wrapper = mountEditor(ctx)
    expect(wrapper.find('.xhs-body-actions .gl-btn-primary').exists()).toBe(false)
  })
})

describe('重新生成', () => {
  test('goToOutline 清空后重流大纲→正文（真实 SSE）', async () => {
    const { engine, ctx } = mockContext()
    engine.topic.value = '选题主题'
    engine.selectedTitle.value = '选定标题'
    engine.outline.value = '旧大纲'
    engine.content.value = '旧正文，即将被清空重流。'
    const wrapper = mountEditor(ctx)

    await wrapper.get('.xhs-body-actions button').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('regenerate')).toHaveLength(1)
    expect(calls).toContain('/api/article-generation/outline')
    expect(calls).toContain('/api/article-generation/content')
    expect(engine.outline.value).toBe('一、开头\n二、要点')
    expect(engine.content.value).toBe('重流的正文。')
  })

  test('【F-05】正文空但大纲残留：显示「生成正文」入口，点击重流大纲→正文（不留死路）', async () => {
    const { engine, ctx } = mockContext()
    engine.topic.value = '选题主题'
    engine.selectedTitle.value = '选定标题'
    engine.outline.value = '流失败残留的大纲'
    // 正文为空：旧版此时无任何生成正文入口。
    const wrapper = mountEditor(ctx)

    const button = wrapper.get('.xhs-body-actions button')
    expect(button.text()).toContain('生成正文')
    await button.trigger('click')
    await flushPromises()

    expect(wrapper.emitted('regenerate')).toHaveLength(1)
    expect(calls).toContain('/api/article-generation/outline')
    expect(calls).toContain('/api/article-generation/content')
    expect(engine.outline.value).toBe('一、开头\n二、要点')
    expect(engine.content.value).toBe('重流的正文。')
  })
})

describe('只读（版本冲突）', () => {
  test('autosave.readonly=true：textarea 只读、重新生成禁用', async () => {
    const { engine, ctx } = mockContext(true)
    engine.content.value = '冲突中的正文。'
    const wrapper = mountEditor(ctx)

    expect(wrapper.get('#xhs-body-input').attributes('readonly')).toBeDefined()
    const buttons = wrapper.findAll('.xhs-body-actions button')
    const regenerate = buttons.find(b => b.text().includes('重新生成'))
    expect(regenerate?.attributes('disabled')).toBeDefined()
  })
})
