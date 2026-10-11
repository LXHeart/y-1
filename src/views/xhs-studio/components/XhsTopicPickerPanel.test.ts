// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { computed, ref } from 'vue'
import XhsTopicPickerPanel from './XhsTopicPickerPanel.vue'
import { useArticleCreation } from '../../../composables/useArticleCreation'
import { useAuth } from '../../../composables/useAuth'
import { XHS_STUDIO_CONTEXT_KEY } from '../types'
import type { XhsStudioContext, XhsStudioStep } from '../types'

/**
 * 选题面板（方案 §6）：校验失败不触发 generate、「用此选题生成」写真实入参并进创作步、
 * HotTopicPicker 透传（pick 预填/refresh/filter）、热榜空错态、未登录守卫、重新选题确认。
 */

const calls: string[] = []

// 非 Response 返回值按 JSON 载荷处理（构造为 { ok, json } 假响应）。
function stubFetch(impl: (url: string) => Response | Record<string, unknown>) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    calls.push(url)
    const result = impl(url)
    if (result instanceof Response) return result
    return { ok: true, json: async () => result } as Response
  }))
}

const HOT_OK = () => ({
  success: true,
  data: {
    provider: '60s',
    items: [{ rank: 1, title: '热点一' }],
    fetchedAt: '2026-10-10T08:00:00Z',
  },
})
const TITLES_OK = () => ({
  success: true,
  data: { titles: [{ title: '候选标题', hook: '' }] },
})

function defaultStub() {
  stubFetch(url => {
    if (url === '/api/homepage/hot-items') return HOT_OK()
    if (url === '/api/article-generation/titles') return TITLES_OK()
    return new Response('{}', { status: 503 })
  })
}

/** 与 useXhsStudioSteps.reached 同款数据派生（titles⇒generate；content⇒proof）。 */
function mockContext() {
  const engine = useArticleCreation()
  engine.platform.value = 'xiaohongshu'
  const steps = {
    current: ref<XhsStudioStep>('pick'),
    reached: computed<XhsStudioStep>(() => engine.completed.value ? 'publish'
      : engine.content.value.trim() ? 'proof'
        : engine.titles.value.length > 0 ? 'generate' : 'pick'),
    canGo: vi.fn(() => true),
    go: vi.fn(),
  }
  const notify = vi.fn()
  const resetSession = vi.fn()
  // F-02：换选题分支会触达 autosave.resetCards 与 deliveryDraft 清理，mock 提供对应面。
  const resetCards = vi.fn()
  const deliveryDraft = ref<Record<string, unknown>>({})
  const autosave = { readonly: computed(() => false), resetCards, deliveryDraft }
  const ctx = { engine, steps, notify, resetSession, autosave } as unknown as XhsStudioContext
  return { engine, steps, notify, resetSession, autosave, ctx }
}

let pinia: ReturnType<typeof createPinia>
let auth: ReturnType<typeof useAuth>

function mountPanel(ctx: XhsStudioContext) {
  return mount(XhsTopicPickerPanel, {
    global: {
      provide: { [XHS_STUDIO_CONTEXT_KEY]: ctx },
      plugins: [pinia],
    },
  })
}

beforeEach(() => {
  calls.length = 0
  defaultStub()
  pinia = createPinia()
  setActivePinia(pinia)
  auth = useAuth()
  auth.currentUser.value = { id: 'u-1', email: 'creator@example.com', role: 'user', roles: [] }
})

afterEach(() => {
  vi.unstubAllGlobals()
  auth.currentUser.value = null
})

describe('自定义选题表单', () => {
  test('校验失败不触发生成：题目空/概要不足时显示错误并聚焦，不发请求', async () => {
    const { ctx, steps } = mockContext()
    const wrapper = mountPanel(ctx)

    await wrapper.get('.xhs-topic-actions .gl-btn-primary').trigger('click')
    await flushPromises()
    expect(wrapper.get('.xhs-topic-error').text()).toContain('选题题目')
    expect(calls.filter(url => url === '/api/article-generation/titles')).toEqual([])
    expect(steps.go).not.toHaveBeenCalled()

    // 填题目但概要 9 字 → 仍拒绝。
    await wrapper.get('#xhs-topic-title').setValue('通勤穿搭')
    await wrapper.get('#xhs-topic-summary').setValue('一二三四五六七八九')
    await wrapper.get('.xhs-topic-actions .gl-btn-primary').trigger('click')
    await flushPromises()
    expect(wrapper.get('.xhs-topic-error').text()).toContain('概要')
    expect(calls.filter(url => url === '/api/article-generation/titles')).toEqual([])
  })

  test('「用此选题生成」→ 写 topic 与 brief.extraInstructions → fetchTitles → go(generate)', async () => {
    const { ctx, engine, steps } = mockContext()
    const wrapper = mountPanel(ctx)

    await wrapper.get('#xhs-topic-title').setValue('通勤穿搭')
    await wrapper.get('#xhs-topic-angle').setValue('打工人衣橱')
    await wrapper.get('#xhs-topic-summary').setValue('覆盖五套 look 与预算。')
    await wrapper.get('.xhs-topic-actions .gl-btn-primary').trigger('click')
    await flushPromises()

    expect(engine.topic.value).toBe('通勤穿搭')
    expect(engine.brief.value?.extraInstructions).toBe('切入角度：打工人衣橱\n内容概要：覆盖五套 look 与预算。')
    expect(calls).toContain('/api/article-generation/titles')
    expect(engine.titles.value).toHaveLength(1)
    expect(steps.go).toHaveBeenCalledWith('generate')
  })

  test('生成失败停留在选题步并 notify 引擎错误', async () => {
    stubFetch(url => url === '/api/article-generation/titles'
      ? new Response(JSON.stringify({ success: false, error: '标题服务暂不可用' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
      : HOT_OK())
    const { ctx, steps, notify } = mockContext()
    const wrapper = mountPanel(ctx)

    await wrapper.get('#xhs-topic-title').setValue('通勤穿搭')
    await wrapper.get('#xhs-topic-summary').setValue('概要内容足够十个字以上')
    await wrapper.get('.xhs-topic-actions .gl-btn-primary').trigger('click')
    await flushPromises()

    expect(steps.go).not.toHaveBeenCalled()
    expect(notify).toHaveBeenCalledWith('标题服务暂不可用')
  })

  test('已有生成物时改选题：先清生成物但保留平台/风格/人群（reset keepPlatform）；图卡与交付草稿同口径清空（F-02）', async () => {
    const { ctx, engine, steps, autosave } = mockContext()
    engine.titles.value = [{ title: '旧标题', hook: '' }]
    engine.stage.value = 'titles'
    engine.genre.value = 'practical_guide'
    // 用户编辑过的话题标签（持久于 deliveryDraft，不随 reset 回退）与图卡清理面。
    autosave.deliveryDraft.value = { topics: ['旧话题'] }
    const wrapper = mountPanel(ctx)

    await wrapper.get('#xhs-topic-title').setValue('新选题')
    await wrapper.get('#xhs-topic-summary').setValue('概要内容足够十个字以上')
    await wrapper.get('.xhs-topic-actions .gl-btn-primary').trigger('click')
    await flushPromises()

    expect(engine.platform.value).toBe('xiaohongshu')
    expect(engine.genre.value).toBe('practical_guide')
    expect(steps.go).toHaveBeenCalledWith('generate')
    // F-02：旧选题的图卡与已编辑交付字段不带进新选题。
    expect(autosave.resetCards).toHaveBeenCalledTimes(1)
    expect(autosave.deliveryDraft.value).toEqual({})
  })

  test('未登录点生成：emit request-login，不发请求', async () => {
    auth.currentUser.value = null
    const { ctx, steps } = mockContext()
    const wrapper = mountPanel(ctx)

    await wrapper.get('#xhs-topic-title').setValue('通勤穿搭')
    await wrapper.get('#xhs-topic-summary').setValue('概要内容足够十个字以上')
    await wrapper.get('.xhs-topic-actions .gl-btn-primary').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('request-login')).toHaveLength(1)
    expect(steps.go).not.toHaveBeenCalled()
    expect(calls.filter(url => url === '/api/article-generation/titles')).toEqual([])
  })

  test('已有生成物（reached>pick）显示「重新选题」，点击调 resetSession', async () => {
    const { ctx, resetSession } = mockContext()
    ctx.engine.titles.value = [{ title: '旧标题', hook: '' }]
    const wrapper = mountPanel(ctx)
    expect(wrapper.find('.xhs-topic-restart').exists()).toBe(true)

    await wrapper.get('.xhs-topic-restart').trigger('click')
    await flushPromises()
    expect(resetSession).toHaveBeenCalledTimes(1)
  })
})

describe('热榜透传（真实源组件原样挂载）', () => {
  test('挂载即拉热榜并渲染 HotTopicPicker；pick 事件预填表单并 emit pick-hot', async () => {
    const { ctx } = mockContext()
    const wrapper = mountPanel(ctx)
    await flushPromises()

    expect(calls).toContain('/api/homepage/hot-items')
    expect(wrapper.find('.hot-picker').exists()).toBe(true)
    expect(wrapper.text()).toContain('热点一')

    await wrapper.get('.hot-pick').trigger('click')
    expect((wrapper.get('#xhs-topic-title').element as HTMLInputElement).value).toBe('热点一')
    expect(wrapper.emitted('pick-hot')).toEqual([['热点一']])
    // pick 只预填不生成：不发起标题生成请求。
    expect(calls.filter(url => url === '/api/article-generation/titles')).toEqual([])
  })

  test('热榜加载失败：组件内显示真实错误态（不造假数据）', async () => {
    stubFetch(url => url === '/api/homepage/hot-items'
      ? new Response('{}', { status: 503 })
      : TITLES_OK())
    const { ctx } = mockContext()
    const wrapper = mountPanel(ctx)
    await flushPromises()

    expect(wrapper.get('.hot-picker .error-state').text()).not.toBe('')
    expect(wrapper.find('.hot-pick').exists()).toBe(false)
  })

  test('刷新热点 emit refresh-hot 并重新拉取', async () => {
    const { ctx } = mockContext()
    const wrapper = mountPanel(ctx)
    await flushPromises()
    const before = calls.filter(url => url === '/api/homepage/hot-items').length

    await wrapper.get('.hot-refresh').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('refresh-hot')).toHaveLength(1)
    expect(calls.filter(url => url === '/api/homepage/hot-items').length).toBe(before + 1)
  })
})
