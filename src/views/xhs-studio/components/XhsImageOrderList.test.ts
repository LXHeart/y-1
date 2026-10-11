// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import XhsImageOrderList from './XhsImageOrderList.vue'
import type { GeneratedCard, PlannedCard } from '../../../composables/useCardSeries'

/**
 * 配图顺序列表（方案 §6 XhsImageOrderList）：① 行渲染 position/role/缩略（未生成
 * 文字占位）；② move emit（含方向）；③ disabled（prop 与首末边界）；④ 缩略点击
 * open 上抛（未生成不 emit、不放假图）。
 */

function card(id: string, position: number, role: PlannedCard['role']): PlannedCard {
  return { cardId: id, position, role, title: `卡${id}`, bullets: [], illustration: '', caption: '' }
}

function result(cardId: string, index: number, ok = true, url?: string): GeneratedCard {
  return { index, cardId, title: `卡${cardId}`, ok, url: ok ? url ?? `https://img.example/${cardId}.png` : undefined }
}

const CARDS = () => [card('a', 1, 'cover'), card('b', 2, 'content'), card('c', 3, 'summary')]
const RESULTS = () => [result('a', 0), result('b', 1, false), result('c', 2)]

function mountList(props: Record<string, unknown> = {}) {
  return mount(XhsImageOrderList, {
    props: { cards: CARDS(), results: RESULTS(), ...props },
  })
}

describe('① 行渲染', () => {
  test('序号取 position、角色取真实 role、标题渲染', () => {
    const wrapper = mountList()
    const rows = wrapper.findAll('[data-test^="xhs-order-row-"]')
    expect(rows).toHaveLength(3)
    expect(rows[0].find('.badge').text()).toBe('封面')
    expect(rows[1].find('.badge').text()).toBe('内容')
    expect(rows[2].find('.badge').text()).toBe('总结')
    expect(rows[0].find('.xhs-order-pos').text()).toBe('1')
    expect(rows[2].find('.xhs-order-title').text()).toBe('卡c')
  })

  test('缩略按 cardId 命中：成功卡渲染 img；失败/未生成显示文字占位（不放假图）', () => {
    const wrapper = mountList()
    const first = wrapper.get('[data-test="xhs-order-thumb-0"]')
    expect(first.find('img').attributes('src')).toBe('https://img.example/a.png')
    const failed = wrapper.get('[data-test="xhs-order-thumb-1"]')
    expect(failed.find('img').exists()).toBe(false)
    expect(failed.find('.xhs-order-placeholder').text()).toBe('未生成')
    expect(failed.attributes('disabled')).toBeDefined()
  })

  test('空卡片列表显示空态说明，不渲染行', () => {
    const wrapper = mountList({ cards: [], results: [] })
    expect(wrapper.get('[data-test="xhs-order-empty"]').text()).toContain('尚无图卡计划')
    expect(wrapper.findAll('[data-test^="xhs-order-row-"]').length).toBe(0)
  })
})

describe('② move emit', () => {
  test('上移/下移按钮 emit [index, direction]', async () => {
    const wrapper = mountList()
    await wrapper.get('[data-test="xhs-order-up-1"]').trigger('click')
    await wrapper.get('[data-test="xhs-order-down-1"]').trigger('click')
    expect(wrapper.emitted('move')).toEqual([[1, -1], [1, 1]])
  })

  test('aria-label 带方向与行号', () => {
    const wrapper = mountList()
    expect(wrapper.get('[data-test="xhs-order-up-1"]').attributes('aria-label')).toBe('上移第 2 张图卡')
    expect(wrapper.get('[data-test="xhs-order-down-2"]').attributes('aria-label')).toBe('下移第 3 张图卡')
  })
})

describe('③ disabled', () => {
  test('prop disabled=true 时全部上移/下移禁用', () => {
    const wrapper = mountList({ disabled: true })
    for (const selector of ['xhs-order-up-1', 'xhs-order-down-1']) {
      expect(wrapper.get(`[data-test="${selector}"]`).attributes('disabled')).toBeDefined()
    }
  })

  test('首行上移、末行下移因边界禁用；中间行两端可用', () => {
    const wrapper = mountList()
    expect(wrapper.get('[data-test="xhs-order-up-0"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="xhs-order-down-2"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="xhs-order-up-1"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('[data-test="xhs-order-down-0"]').attributes('disabled')).toBeUndefined()
  })
})

describe('④ 缩略点击 open 上抛', () => {
  test('成功卡点击 emit open(url)', async () => {
    const wrapper = mountList()
    await wrapper.get('[data-test="xhs-order-thumb-2"]').trigger('click')
    expect(wrapper.emitted('open')).toEqual([['https://img.example/c.png']])
  })

  test('未生成卡禁用不 emit', async () => {
    const wrapper = mountList()
    await wrapper.get('[data-test="xhs-order-thumb-1"]').trigger('click')
    expect(wrapper.emitted('open')).toBeUndefined()
  })
})
