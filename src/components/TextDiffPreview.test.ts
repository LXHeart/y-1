// @vitest-environment happy-dom
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import TextDiffPreview from './TextDiffPreview.vue'

enableAutoUnmount(afterEach)

describe('TextDiffPreview', () => {
  it('段级 diff：改写段标红删绿增，未动段不变', () => {
    const wrapper = mount(TextDiffPreview, {
      props: {
        original: '第一段保持不变。\n\n这段有旧说法需要改。\n\n第三段也不变。',
        revised: '第一段保持不变。\n\n这段已经改成新说法。\n\n第三段也不变。',
      },
    })
    expect(wrapper.findAll('[data-test="tdp-unchanged"]')).toHaveLength(2)
    expect(wrapper.get('[data-test="tdp-removed"]').text()).toBe('这段有旧说法需要改。')
    expect(wrapper.get('[data-test="tdp-added"]').text()).toBe('这段已经改成新说法。')
  })

  it('全新增（原文为空）全部标绿', () => {
    const wrapper = mount(TextDiffPreview, {
      props: { original: '', revised: '全新的一段\n\n再来一段' },
    })
    expect(wrapper.findAll('[data-test="tdp-added"]')).toHaveLength(2)
    expect(wrapper.findAll('[data-test="tdp-removed"]')).toHaveLength(0)
    expect(wrapper.findAll('[data-test="tdp-unchanged"]')).toHaveLength(0)
  })

  it('全删除全部标红', () => {
    const wrapper = mount(TextDiffPreview, {
      props: { original: '第一段\n\n第二段', revised: '' },
    })
    expect(wrapper.findAll('[data-test="tdp-removed"]')).toHaveLength(2)
    expect(wrapper.findAll('[data-test="tdp-added"]')).toHaveLength(0)
  })

  it('内容全同时全部不变、变化计数为零', () => {
    const text = '完全一样的一段\n\n第二段也一样'
    const wrapper = mount(TextDiffPreview, { props: { original: text, revised: text } })
    expect(wrapper.findAll('[data-test="tdp-unchanged"]')).toHaveLength(2)
    expect(wrapper.findAll('[data-test="tdp-added"], [data-test="tdp-removed"]')).toHaveLength(0)
    expect(wrapper.get('.tdp-count').text()).toBe('共 0 个段落有变化')
  })

  it('应用/放弃各自 emit', async () => {
    const wrapper = mount(TextDiffPreview, {
      props: { original: '旧', revised: '新' },
    })
    await wrapper.get('[data-test="tdp-apply"]').trigger('click')
    await wrapper.get('[data-test="tdp-discard"]').trigger('click')
    expect(wrapper.emitted('apply')).toHaveLength(1)
    expect(wrapper.emitted('discard')).toHaveLength(1)
  })
})
