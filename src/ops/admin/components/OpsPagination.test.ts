// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import OpsPagination from './OpsPagination.vue'

/**
 * 治理台分页器行为契约（任务 #3）：
 * 首页/末页按钮 disabled、total=0 呈现、change 携带正确 offset、删行致页码漂移时的越界收敛。
 */

function pager(props: { total: number; limit: number; offset: number }) {
  return mount(OpsPagination, { props })
}

describe('OpsPagination', () => {
  test('首页禁用上一页；末页禁用下一页', () => {
    const first = pager({ total: 55, limit: 10, offset: 0 })
    const [prevFirst, nextFirst] = first.findAll('button')
    expect(prevFirst.element.disabled).toBe(true)
    expect(nextFirst.element.disabled).toBe(false)

    const last = pager({ total: 55, limit: 10, offset: 50 })
    const [prevLast, nextLast] = last.findAll('button')
    expect(prevLast.element.disabled).toBe(false)
    expect(nextLast.element.disabled).toBe(true)
  })

  test('文案对齐先例：第 x / y 页 · 共 N 条', () => {
    const wrapper = pager({ total: 55, limit: 10, offset: 20 })
    const info = wrapper.get('.ops-page-info')
    expect(info.classes()).toContain('gl-num')
    expect(info.text()).toBe('第 3 / 6 页 · 共 55 条')
  })

  test('total=0 显示共 0 条且两按钮均禁用，不发出收敛', () => {
    const wrapper = pager({ total: 0, limit: 50, offset: 0 })
    expect(wrapper.get('.ops-page-info').text()).toContain('共 0 条')
    const [prev, next] = wrapper.findAll('button')
    expect(prev.element.disabled).toBe(true)
    expect(next.element.disabled).toBe(true)
    expect(wrapper.emitted('change')).toBeUndefined()
  })

  test('翻页 change 事件携带正确 offset（父级持 offset 真源）', async () => {
    const wrapper = pager({ total: 250, limit: 50, offset: 100 })
    const [prev, next] = wrapper.findAll('button')

    await next.trigger('click')
    expect(wrapper.emitted('change')).toEqual([[150]])
    await wrapper.setProps({ offset: 150 })

    await prev.trigger('click')
    expect(wrapper.emitted('change')).toEqual([[150], [100]])
    await wrapper.setProps({ offset: 100 })

    await prev.trigger('click')
    await wrapper.setProps({ offset: 50 })
    await prev.trigger('click')
    expect(wrapper.emitted('change')).toEqual([[150], [100], [50], [0]])
  })

  test('offset 越界（删行致页码漂移）时发出 change 收敛到末页首行', () => {
    // 25 条 / 10 = 3 页（末页 offset=20）；停在 offset=40 → 收敛到 20
    const wrapper = pager({ total: 25, limit: 10, offset: 40 })
    expect(wrapper.emitted('change')).toEqual([[20]])
    // 收敛前展示页码夹在末页，不显示 0/负页
    expect(wrapper.get('.ops-page-info').text()).toContain('第 3 / 3 页')
  })

  test('offset 恰在末页边界时不误发收敛', () => {
    const wrapper = pager({ total: 25, limit: 10, offset: 20 })
    expect(wrapper.emitted('change')).toBeUndefined()
  })
})
