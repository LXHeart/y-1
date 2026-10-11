// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { defineComponent } from 'vue'
import { useArticleCreation } from '../../../composables/useArticleCreation'
import { useCardSeries } from '../../../composables/useCardSeries'
import { useArticleWorkspace } from './useArticleWorkspace'
import type { RouteLocationNormalizedLoaded } from 'vue-router'

/**
 * 交付字段保存语义（F-03 回归）：updateDelivery 收到部分对象时只更新传入字段——
 * 未传（undefined）字段保留草稿原值，不得整体替换导致其他面板已编辑字段
 * （发布描述/摘要等）被清空并回退成自动派生值；空串/空数组仍是显式编辑结果。
 */

const route = { query: {} } as RouteLocationNormalizedLoaded

function harness() {
  const engine = useArticleCreation()
  engine.platform.value = 'xiaohongshu'
  const cards = useCardSeries('xiaohongshu')
  let workspace!: ReturnType<typeof useArticleWorkspace>
  const Host = defineComponent({
    setup() {
      workspace = useArticleWorkspace(engine, route, () => null, cards)
      return () => null
    },
  })
  mount(Host)
  return { engine, workspace }
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })))
})

afterEach(() => { vi.unstubAllGlobals() })

describe('updateDelivery 部分对象更新（F-03）', () => {
  test('话题标签编辑（仅传 topics）不回退用户已编辑的发布描述与摘要', () => {
    const { engine, workspace } = harness()
    engine.topic.value = '选题'
    engine.selectedTitle.value = '选定标题'
    engine.content.value = '生成的正文内容。'
    // 先在交付面板编辑过发布描述与摘要（旧流 DeliveryPanel 全量写入）。
    workspace.updateDelivery({ bodyOrDescription: '用户手写的发布描述', summary: '用户摘要' })

    // 话题标签编辑器只传 topics（部分对象）。
    workspace.updateDelivery({ topics: ['通勤穿搭'] })

    expect(workspace.deliveryValue.value.bodyOrDescription).toBe('用户手写的发布描述')
    expect(workspace.deliveryValue.value.summary).toBe('用户摘要')
    expect(workspace.deliveryValue.value.topics).toEqual(['通勤穿搭'])
  })

  test('空串与空数组是显式编辑：清空发布描述/话题不被误当作「未传」保留', () => {
    const { engine, workspace } = harness()
    engine.topic.value = '选题'
    engine.content.value = '生成的正文内容。'
    workspace.updateDelivery({ bodyOrDescription: '旧描述', topics: ['旧话题'] })

    workspace.updateDelivery({ bodyOrDescription: '', topics: [] })

    expect(workspace.deliveryValue.value.bodyOrDescription).toBe('')
    expect(workspace.deliveryValue.value.topics).toEqual([])
  })
})
