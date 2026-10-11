// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { computed, nextTick, ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, test } from 'vitest'
import type { Ref } from 'vue'
import XhsPhonePreview from './XhsPhonePreview.vue'
import { XHS_STUDIO_CONTEXT_KEY } from '../types'
import type { XhsStudioContext } from '../types'
import { useAuth } from '../../../composables/useAuth'
import { parseHashtagTopics } from '../../../lib/creation-delivery'
import type { AuthUser } from '../../../types/auth'
import type { GeneratedCard, PlannedCard } from '../../../composables/useCardSeries'

/**
 * 右栏手机实时预览（方案 §4.5/§6）：inject 只读派生标题/正文/话题/封面/作者/保存态，
 * props 仅测试覆写口；封面空态、点击放大、无演示字段、同步中呼吸灯。
 */

interface Harness {
  context: XhsStudioContext
  engine: { topic: Ref<string>; selectedTitle: Ref<string>; content: Ref<string> }
  cards: { cards: Ref<PlannedCard[]>; results: Ref<GeneratedCard[]> }
  /** 用户编辑过的话题（覆盖正文派生，等价 deliveryDraft.topics）。 */
  deliveryTopics: Ref<string[] | undefined>
  saveState: Ref<string>
}

/** 构造可变 mock 上下文：字段形态对齐真实 engine/cards/autosave（deliveryValue 语义同源）。 */
function createHarness(): Harness {
  const engine = { topic: ref(''), selectedTitle: ref(''), content: ref('') }
  const cards = { cards: ref<PlannedCard[]>([]), results: ref<GeneratedCard[]>([]) }
  const deliveryTopics = ref<string[] | undefined>(undefined)
  const saveState = ref('idle')
  const autosave = {
    // 缺省从正文派生（对齐 useArticleWorkspace deliveryValue.topics 口径）。
    deliveryValue: computed(() => ({ topics: deliveryTopics.value ?? parseHashtagTopics(engine.content.value) })),
    saveState,
  }
  const context = { engine, cards, autosave } as unknown as XhsStudioContext
  return { context, engine, cards, deliveryTopics, saveState }
}

let pinia: ReturnType<typeof createPinia>

function mountPreview(harness: Harness, props: Record<string, unknown> = {}) {
  return mount(XhsPhonePreview, {
    props,
    global: { plugins: [pinia], provide: { [XHS_STUDIO_CONTEXT_KEY as symbol]: harness.context } },
  })
}

function login(displayName?: string): void {
  const user: AuthUser = { id: 'u-1', email: 'creator@example.com', role: 'user', roles: [], ...(displayName !== undefined ? { displayName } : {}) }
  useAuth().currentUser.value = user
}

beforeEach(() => {
  pinia = createPinia()
  setActivePinia(pinia)
  login('草原推荐官')
})

describe('inject 派生实时同步（标题/正文/话题）', () => {
  test('空会话：未命名笔记占位，无正文无话题', () => {
    const wrapper = mountPreview(createHarness())
    expect(wrapper.get('.xhs-phone-title').text()).toBe('未命名笔记')
    expect(wrapper.find('.xhs-phone-body').exists()).toBe(false)
    expect(wrapper.find('.xhs-phone-topics').exists()).toBe(false)
  })

  test('pick 步显选题；选定标题后优先显示所选标题（含空白标题回落）', async () => {
    const harness = createHarness()
    const wrapper = mountPreview(harness)
    harness.engine.topic.value = '周末露营装备清单'
    await nextTick()
    expect(wrapper.get('.xhs-phone-title').text()).toBe('周末露营装备清单')

    harness.engine.selectedTitle.value = '露营新手不踩坑的 8 件装备'
    await nextTick()
    expect(wrapper.get('.xhs-phone-title').text()).toBe('露营新手不踩坑的 8 件装备')

    // 空白 selectedTitle 视为未选，回落选题。
    harness.engine.selectedTitle.value = '   '
    await nextTick()
    expect(wrapper.get('.xhs-phone-title').text()).toBe('周末露营装备清单')
  })

  test('正文与话题随流式正文实时更新；用户编辑话题后覆盖派生值', async () => {
    const harness = createHarness()
    const wrapper = mountPreview(harness)
    harness.engine.content.value = '今天的通勤穿搭分享 #通勤穿搭 #OOTD'
    await nextTick()
    expect(wrapper.get('.xhs-phone-body').text()).toBe('今天的通勤穿搭分享 #通勤穿搭 #OOTD')
    expect(wrapper.findAll('.xhs-phone-tag').map(tag => tag.text())).toEqual(['#通勤穿搭', '#OOTD'])

    harness.deliveryTopics.value = ['职场穿搭']
    await nextTick()
    expect(wrapper.findAll('.xhs-phone-tag').map(tag => tag.text())).toEqual(['#职场穿搭'])
  })

  test('props 覆写口优先于 inject 派生（测试注入口）', () => {
    const harness = createHarness()
    harness.engine.topic.value = '选题'
    const wrapper = mountPreview(harness, { title: '覆写标题', body: '覆写正文', topics: ['覆写'], saving: true })
    expect(wrapper.get('.xhs-phone-title').text()).toBe('覆写标题')
    expect(wrapper.get('.xhs-phone-body').text()).toBe('覆写正文')
    expect(wrapper.get('.xhs-phone-tag').text()).toBe('#覆写')
    expect(wrapper.find('.xhs-phone-saving').exists()).toBe(true)
  })
})

describe('封面（成功卡 position 最小者）', () => {
  test('无成功卡：空态块「配图生成后在此预览首图」，无图片无按钮', () => {
    const harness = createHarness()
    harness.cards.results.value = [
      { index: 0, cardId: 'a', title: '', ok: false, errorReason: '生成失败' },
    ]
    const wrapper = mountPreview(harness)
    expect(wrapper.get('.xhs-phone-empty').text()).toBe('配图生成后在此预览首图')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('button').exists()).toBe(false)
  })

  test('多张成功卡取 position 最小者渲染，点击 emit open 放大', async () => {
    const harness = createHarness()
    harness.cards.cards.value = [
      { cardId: 'b', position: 2, title: '', bullets: [], illustration: '', caption: '' },
      { cardId: 'a', position: 1, title: '', bullets: [], illustration: '', caption: '' },
    ]
    harness.cards.results.value = [
      { index: 0, cardId: 'b', ok: true, url: 'https://img.example/b.png', title: '' },
      { index: 1, cardId: 'a', ok: true, url: 'https://img.example/a.png', title: '' },
    ]
    const wrapper = mountPreview(harness)
    const cover = wrapper.get('.xhs-phone-cover')
    expect(cover.attributes('src')).toBe('https://img.example/a.png')

    await wrapper.get('.xhs-phone-cover-btn').trigger('click')
    expect(wrapper.emitted('open')).toEqual([['https://img.example/a.png']])
  })
})

describe('作者行（useAuth 真实昵称）', () => {
  test('登录昵称展示；昵称缺失/空白回落「我」', async () => {
    const wrapper = mountPreview(createHarness())
    expect(wrapper.get('.xhs-phone-meta').text()).toBe('草原推荐官')

    login('')
    await nextTick()
    expect(wrapper.get('.xhs-phone-meta').text()).toBe('我')

    useAuth().currentUser.value = null
    await nextTick()
    expect(wrapper.get('.xhs-phone-meta').text()).toBe('我')
  })
})

describe('不渲染演示字段（宁缺毋假）', () => {
  test('无互动数/关注按钮/编辑时间属地等原型演示内容', () => {
    const wrapper = mountPreview(createHarness())
    const text = wrapper.text()
    for (const word of ['点赞', '收藏', '关注', '粉丝', '评论', '发布于', '编辑于', '来自']) {
      expect(text).not.toContain(word)
    }
    // 除封面放大外无任何交互按钮。
    expect(wrapper.findAll('button')).toHaveLength(0)
  })
})

describe('保存态呼吸灯', () => {
  test('saveState=saving 显示「同步中」呼吸点；idle 不显示', async () => {
    const harness = createHarness()
    const wrapper = mountPreview(harness)
    expect(wrapper.find('.xhs-phone-saving').exists()).toBe(false)

    harness.saveState.value = 'saving'
    await nextTick()
    const saving = wrapper.get('.xhs-phone-saving')
    expect(saving.text()).toBe('同步中')
    expect(saving.attributes('role')).toBe('status')
    expect(wrapper.find('.xhs-phone-dot').exists()).toBe(true)

    harness.saveState.value = 'saved'
    await nextTick()
    expect(wrapper.find('.xhs-phone-saving').exists()).toBe(false)
  })
})
