// @vitest-environment happy-dom
import { computed, ref } from 'vue'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import XhsPublishPanel from './XhsPublishPanel.vue'
import { XHS_STUDIO_CONTEXT_KEY } from '../types'
import type { XhsStudioContext } from '../types'
import type { GeneratedCard, PlannedCard } from '../../../composables/useCardSeries'

/**
 * 发布导出面板（方案 §6 XhsPublishPanel）：① 平台约束警示文案；② DeliveryPanel
 * 挂载与 mediaExpected（有已存图卡=true）；③【F6】before-export 透传 context.beforeExport
 * （flush 后以 draftVersion 为版本源）；④ update:model-value→updateDelivery；
 * ⑤ 复制按钮（clipboard+execCommand 兜底）+notify；⑥ 图卡排序接线与禁用。
 */

const fetchMock = vi.fn()
beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => {
  vi.unstubAllGlobals()
  delete (navigator as { clipboard?: unknown }).clipboard
})
enableAutoUnmount(afterEach)

function card(id: string, position: number, role: PlannedCard['role']): PlannedCard {
  return { cardId: id, position, role, title: `卡${id}`, bullets: [], illustration: '', caption: '' }
}

interface MountOptions {
  persistedMediaIds?: Record<string, string>
  draftId?: string
  draftVersion?: number
  readonly?: boolean
  generating?: boolean
  delivery?: Record<string, unknown>
  beforeExport?: () => Promise<number | false>
}

/** 最小可装配的 XhsStudioContext mock（组件只读消费的字段齐全即可）。 */
function buildContext(options: MountOptions = {}) {
  const updateDelivery = vi.fn()
  const notify = vi.fn()
  const beforeExport = options.beforeExport ?? vi.fn(async () => 1)
  const context = {
    engine: { selectedTitle: ref('已选标题') },
    cards: {
      cards: ref([card('a', 1, 'cover'), card('b', 2, 'content')]),
      results: ref<GeneratedCard[]>([
        { index: 0, cardId: 'a', title: '卡a', ok: true, url: 'https://img.example/a.png' },
        { index: 1, cardId: 'b', title: '卡b', ok: false },
      ]),
      generating: ref(options.generating ?? false),
      persistedMediaIds: ref(options.persistedMediaIds ?? {}),
    },
    autosave: {
      deliveryValue: computed(() => ({
        titleOrOpening: '已选标题',
        bodyOrDescription: '正文内容',
        topics: ['探店', '攻略'],
        mediaRefs: options.persistedMediaIds && Object.keys(options.persistedMediaIds).length
          ? [{ id: 'm-1', refType: 'media', role: 'card', cardId: 'a', position: 1 }]
          : undefined,
        ...options.delivery,
      })),
      updateDelivery,
      draftId: ref(options.draftId ?? ''),
      draftVersion: ref(options.draftVersion ?? 0),
      readonly: ref(options.readonly ?? false),
    },
    beforeExport,
    notify,
  } as unknown as XhsStudioContext
  return { context, updateDelivery, notify, beforeExport }
}

function mountPanel(options: MountOptions = {}) {
  const spies = buildContext(options)
  const wrapper = mount(XhsPublishPanel, {
    global: { provide: { [XHS_STUDIO_CONTEXT_KEY as symbol]: spies.context } },
  })
  return { wrapper, ...spies }
}

describe('① 平台约束警示', () => {
  test('固定文案：未开放发布 API、只负责生成/校对/导出、App 内上传', () => {
    const { wrapper } = mountPanel()
    const notice = wrapper.get('[data-test="xhs-publish-notice"]').text()
    expect(notice).toContain('小红书未开放内容发布 API')
    expect(notice).toContain('生成、校对与导出')
    expect(notice).toContain('小红书 App 内')
    // 发布账号/时间/笔记链接 defer：不渲染相关假控件。
    expect(wrapper.text()).not.toContain('发布账号')
    expect(wrapper.text()).not.toContain('笔记链接')
  })
})

describe('② DeliveryPanel 挂载与 mediaExpected', () => {
  test('原样挂载：标题/话题字段来自 deliveryValue', () => {
    const { wrapper } = mountPanel()
    expect(wrapper.find('[data-test="delivery-panel"]').exists()).toBe(true)
    expect((wrapper.get('[data-test="delivery-title"]').element as HTMLInputElement).value).toBe('已选标题')
    // DeliveryPanel topicsText getter 无 # 前缀（输入时才带 #，setter 归一剥离）。
    expect((wrapper.get('[data-test="delivery-topics"]').element as HTMLInputElement).value).toBe('探店 攻略')
    // 小红书无 summary 入口（省略 suggest-summary 链）。
    expect(wrapper.find('[data-test="delivery-summary"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="studio-export"]').exists()).toBe(false)
  })

  test('有已存图卡 → mediaExpected=true，交付检查含「选定媒体」且就绪', () => {
    const { wrapper } = mountPanel({ persistedMediaIds: { a: 'm-1' } })
    const items = wrapper.findAll('[data-test="delivery-readiness"] li')
    expect(items.some((item) => item.text().includes('选定媒体'))).toBe(true)
    expect(items.some((item) => item.text().includes('选定媒体') && item.text().includes('待补'))).toBe(false)
  })

  test('无已存图卡 → 交付检查不含媒体项', () => {
    const { wrapper } = mountPanel()
    const items = wrapper.findAll('[data-test="delivery-readiness"] li')
    expect(items.some((item) => item.text().includes('选定媒体'))).toBe(false)
  })
})

describe('③【F6】before-export 透传（flush 后以 draftVersion 为版本源）', () => {
  test('导出经 context.beforeExport 取版本并 POST /exports', async () => {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ success: true, data: {
      draftId: 'd-1', version: 5, expiresAt: '2026-10-10T00:00:00Z', manifest: {}, downloads: [],
    } }), { headers: { 'Content-Type': 'application/json' } }))
    const beforeExport = vi.fn(async () => 5)
    const { wrapper, notify } = mountPanel({ draftId: 'd-1', beforeExport })
    await wrapper.get('[data-test="delivery-export"]').trigger('click')
    await flushPromises()

    expect(beforeExport).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/creation-drafts/d-1/exports')
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toMatchObject({ version: 5 })
    expect(wrapper.find('[data-test="delivery-export-error"]').exists()).toBe(false)
    expect(notify).not.toHaveBeenCalled()
  })
})

describe('④ update:model-value → autosave.updateDelivery', () => {
  test('话题输入 patch 上抛后写回工作区交付字段', async () => {
    const { wrapper, updateDelivery } = mountPanel()
    await wrapper.get('[data-test="delivery-topics"]').setValue('#新话题')
    const calls = updateDelivery.mock.calls
    expect(calls.length).toBeGreaterThan(0)
    expect(calls[calls.length - 1][0]).toMatchObject({ topics: ['新话题'] })
  })
})

describe('⑤ 复制按钮（双路径剪贴板）+ notify', () => {
  test('clipboard API 成功：写入选题/正文/话题并 notify 成功', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const { wrapper, notify } = mountPanel()
    await wrapper.get('[data-test="xhs-publish-copy"]').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith('已选标题\n\n正文内容\n\n#探店 #攻略')
    expect(notify).toHaveBeenCalledWith('已复制笔记内容')
  })

  test('clipboard API 失败 → execCommand 兜底成功仍 notify 成功', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) }, configurable: true,
    })
    document.execCommand = vi.fn().mockReturnValue(true)
    const { wrapper, notify } = mountPanel()
    await wrapper.get('[data-test="xhs-publish-copy"]').trigger('click')
    await flushPromises()
    expect(document.execCommand).toHaveBeenCalledWith('copy')
    expect(notify).toHaveBeenCalledWith('已复制笔记内容')
  })

  test('双路径都失败 → notify 失败（不假装已复制）', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) }, configurable: true,
    })
    document.execCommand = vi.fn().mockReturnValue(false)
    const { wrapper, notify } = mountPanel()
    await wrapper.get('[data-test="xhs-publish-copy"]').trigger('click')
    await flushPromises()
    expect(notify).toHaveBeenCalledWith('复制失败，请手动选择文本复制')
  })
})

describe('⑥ 图卡排序接线', () => {
  test('列表渲染真实卡片与结果；move 后 position 重编（经 useXhsImageOrder 写回 refs）', async () => {
    const { wrapper } = mountPanel()
    const rows = wrapper.findAll('[data-test^="xhs-order-row-"]')
    expect(rows).toHaveLength(2)
    // 第一张成功卡缩略可点、放大上抛给视图 lightbox。
    await wrapper.get('[data-test="xhs-order-thumb-0"]').trigger('click')
    expect(wrapper.emitted('open-lightbox')).toEqual([['https://img.example/a.png']])

    await wrapper.get('[data-test="xhs-order-up-1"]').trigger('click')
    const moved = wrapper.findAll('[data-test^="xhs-order-row-"]')
    expect(moved[0].find('.xhs-order-title').text()).toBe('卡b')
    expect(moved[0].find('.xhs-order-pos').text()).toBe('1')
  })

  test('generating 或只读态排序按钮禁用', () => {
    const busy = mountPanel({ generating: true })
    expect(busy.wrapper.get('[data-test="xhs-order-up-1"]').attributes('disabled')).toBeDefined()
    const locked = mountPanel({ readonly: true })
    expect(locked.wrapper.get('[data-test="xhs-order-up-1"]').attributes('disabled')).toBeDefined()
  })
})
