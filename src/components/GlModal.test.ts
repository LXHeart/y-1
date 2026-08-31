// @vitest-environment happy-dom
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test } from 'vitest'
import GlModal from './GlModal.vue'

enableAutoUnmount(afterEach)

/** 弹窗类组件的 mount 必带 Teleport stub，否则内容渲染到 body、findAll 全空（项目实测坑）。 */
function mountModal(
  props: { title?: string; wide?: boolean; scroll?: boolean; persistent?: boolean } = {},
  actions = '',
) {
  return mount(GlModal, {
    props: { title: '示例', ...props },
    slots: { default: '<p class="content">弹窗内容</p>', ...(actions ? { actions } : {}) },
    global: { stubs: { Teleport: true } },
  })
}

describe('GlModal', () => {
  test('renders title into .modal-title and the default slot', () => {
    const wrapper = mountModal({ title: '删除平台凭据' })
    expect(wrapper.get('.modal-title').text()).toBe('删除平台凭据')
    expect(wrapper.get('.content').text()).toBe('弹窗内容')
    expect(wrapper.get('[role="dialog"]').attributes('aria-modal')).toBe('true')
    // 无 actions 插槽时不渲染 footer
    expect(wrapper.find('.modal-actions').exists()).toBe(false)
  })

  test('wide and scroll modifiers apply their classes', () => {
    const wrapper = mountModal({ title: '单价', wide: true, scroll: true })
    expect(wrapper.find('.modal-card--wide').exists()).toBe(true)
    expect(wrapper.find('.modal-body--scroll').exists()).toBe(true)
  })

  test('close button, overlay mousedown and Escape each emit close once', async () => {
    const wrapper = mountModal({ title: '示例' })
    await wrapper.get('button[aria-label="关闭弹窗"]').trigger('click')
    await wrapper.get('[data-testid="gl-modal-overlay"]').trigger('mousedown')
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(wrapper.emitted('close')).toHaveLength(3)
  })

  test('overlay mousedown inside the card does not close (only .self hits)', async () => {
    const wrapper = mountModal({ title: '示例' })
    await wrapper.get('.modal-card').trigger('mousedown')
    expect(wrapper.emitted('close')).toBeUndefined()
  })

  test('persistent: overlay and Escape do not close; the × button still does', async () => {
    const wrapper = mountModal({ title: '单价（可改）', persistent: true })
    await wrapper.get('[data-testid="gl-modal-overlay"]').trigger('mousedown')
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(wrapper.emitted('close')).toBeUndefined()
    await wrapper.get('button[aria-label="关闭弹窗"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  test('actions slot renders into the footer', () => {
    const wrapper = mountModal({ title: '示例' }, '<button class="btn-cancel">取消</button>')
    expect(wrapper.get('.modal-actions .btn-cancel').text()).toBe('取消')
  })

  test('Escape after unmount does not emit close (listener removed)', async () => {
    // 卸载后 emitted() 记录被清空，用自持数组观察 emit 是否再发生
    const closes: number[] = []
    const wrapper = mount(GlModal, {
      props: { title: '示例' },
      slots: { default: '<p />' },
      global: { stubs: { Teleport: true } },
      attrs: { onClose: () => closes.push(1) },
    })
    await wrapper.get('button[aria-label="关闭弹窗"]').trigger('click')
    expect(closes).toHaveLength(1)
    wrapper.unmount()

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(closes).toHaveLength(1)
  })
})
