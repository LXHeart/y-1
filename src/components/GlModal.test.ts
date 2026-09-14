// @vitest-environment happy-dom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, test } from 'vitest'
import GlModal from './GlModal.vue'

const wrappers: VueWrapper[] = []
afterEach(async () => {
  wrappers.reverse().forEach(wrapper => wrapper.unmount())
  wrappers.length = 0
  await flushPromises()
  document.body.replaceChildren()
})
function show(title = '确认', slots = '<input aria-label="名称"><button>完成</button>', props = {}) {
  const wrapper = mount(GlModal, { props: { title, trapFocus: true, ...props },
    slots: { default: slots }, attachTo: document.body, global: { stubs: { teleport: true } } })
  wrappers.push(wrapper)
  return wrapper
}
function key(value: string, shiftKey = false) {
  window.dispatchEvent(new KeyboardEvent('keydown', { key: value, shiftKey, bubbles: true, cancelable: true }))
}

function mountModal(
  props: { title?: string; wide?: boolean; scroll?: boolean; persistent?: boolean } = {},
  actions = '',
) {
  const wrapper = mount(GlModal, {
    props: { title: '示例', ...props },
    slots: { default: '<p class="content">弹窗内容</p>', ...(actions ? { actions } : {}) },
    global: { stubs: { Teleport: true } },
  })
  wrappers.push(wrapper)
  return wrapper
}

describe('GlModal', () => {
  test('renders title into .modal-title and the default slot', () => {
    const wrapper = mountModal({ title: '删除平台凭据' })
    expect(wrapper.get('.modal-title').text()).toBe('删除平台凭据')
    expect(wrapper.get('.content').text()).toBe('弹窗内容')
    expect(wrapper.get('[role="dialog"]').attributes('aria-modal')).toBe('true')
    expect(wrapper.find('.modal-actions').exists()).toBe(false)
  })

  test('wide and scroll modifiers apply their classes', () => {
    const wrapper = mountModal({ title: '单价', wide: true, scroll: true })
    expect(wrapper.find('.modal-card--wide').exists()).toBe(true)
    expect(wrapper.find('.modal-body--scroll').exists()).toBe(true)
  })

  test('close button, overlay mousedown and Escape each emit close once', async () => {
    const wrapper = mountModal()
    await wrapper.get('button[aria-label="关闭弹窗"]').trigger('click')
    await wrapper.get('[data-testid="gl-modal-overlay"]').trigger('mousedown')
    key('Escape')
    expect(wrapper.emitted('close')).toHaveLength(3)
  })

  test('overlay mousedown inside the card does not close (only .self hits)', async () => {
    const wrapper = mountModal()
    await wrapper.get('.modal-card').trigger('mousedown')
    expect(wrapper.emitted('close')).toBeUndefined()
  })

  test('persistent: overlay and Escape do not close; the × button still does', async () => {
    const wrapper = mountModal({ title: '单价（可改）', persistent: true })
    await wrapper.get('[data-testid="gl-modal-overlay"]').trigger('mousedown')
    key('Escape')
    expect(wrapper.emitted('close')).toBeUndefined()
    await wrapper.get('button[aria-label="关闭弹窗"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  test('actions slot renders into the footer', () => {
    const wrapper = mountModal({}, '<button class="btn-cancel">取消</button>')
    expect(wrapper.get('.modal-actions .btn-cancel').text()).toBe('取消')
  })

  test('Escape after unmount does not emit close (listener removed)', async () => {
    const closes: number[] = []
    const wrapper = mount(GlModal, {
      props: { title: '示例' },
      slots: { default: '<p />' },
      global: { stubs: { Teleport: true } },
      attrs: { onClose: () => closes.push(1) },
    })
    wrappers.push(wrapper)
    await wrapper.get('button[aria-label="关闭弹窗"]').trigger('click')
    expect(closes).toHaveLength(1)
    wrapper.unmount(); wrappers.pop()
    key('Escape')
    expect(closes).toHaveLength(1)
  })
})

describe('GlModal keyboard interaction', () => {
  test('collapsed details expose their summary but keep hidden fields out of the focus loop', async () => {
    const modal = show('详情', '<details><summary id="more">更多设置</summary><input id="hidden-field"></details>')
    await flushPromises()
    key('Tab', true)
    expect(document.activeElement).toBe(modal.get('#more').element)
    key('Tab')
    expect(document.activeElement).toBe(modal.get('[data-action="close-modal"]').element)
  })

  test('default modal locks background scrolling and returns focus when closed', async () => {
    const trigger = document.createElement('button'); document.body.append(trigger); trigger.focus()
    const modal = mount(GlModal, { props: { title: '默认弹窗' }, slots: { default: '<input>' }, attachTo: document.body })
    wrappers.push(modal); await flushPromises()
    expect(document.activeElement?.closest('[role="dialog"]')).not.toBeNull()
    expect(trigger.inert).toBe(true)
    expect(document.body.style.overflow).toBe('hidden')
    modal.unmount(); wrappers.pop(); await flushPromises()
    expect(trigger.inert).toBe(false)
    expect(document.body.style.overflow).toBe('')
    expect(document.activeElement).toBe(trigger)
  })

  test('focus enters, cycles past hidden/disabled controls, and returns to the trigger', async () => {
    const trigger = document.createElement('button'); document.body.append(trigger); trigger.focus()
    const modal = show('输入', '<button id="last">保存</button><button disabled>禁用</button><div hidden><input></div>')
    await flushPromises()
    const first = modal.get<HTMLButtonElement>('[data-action="close-modal"]').element
    const last = modal.get<HTMLButtonElement>('#last').element
    expect(document.activeElement).toBe(first)
    key('Tab', true); expect(document.activeElement).toBe(last)
    key('Tab'); expect(document.activeElement).toBe(first)
    trigger.focus(); expect(document.activeElement).toBe(first)
    modal.unmount(); wrappers.pop(); await flushPromises()
    expect(document.activeElement).toBe(trigger)
  })

  test('nested Escape closes only the front dialog and returns focus inside the parent', async () => {
    const parent = show('连接管理', '<button id="open-child">绑定</button>')
    await flushPromises()
    const trigger = parent.get<HTMLButtonElement>('#open-child').element; trigger.focus()
    const child = show('绑定'); await flushPromises()
    key('Escape')
    expect(child.emitted('close')).toHaveLength(1)
    expect(parent.emitted('close')).toBeUndefined()
    child.unmount(); wrappers.pop(); await flushPromises()
    expect(document.activeElement).toBe(trigger)
    key('Escape'); expect(parent.emitted('close')).toHaveLength(1)
  })

  test('persistent dialog consumes Escape while keeping keyboard focus inside', async () => {
    const parent = show('父层'); await flushPromises()
    const modal = show('提交中', '<button>等待</button>', { persistent: true }); await flushPromises()
    key('Escape')
    expect(modal.emitted('close')).toBeUndefined()
    expect(parent.emitted('close')).toBeUndefined()
    key('Tab', true)
    expect(modal.element.contains(document.activeElement)).toBe(true)
  })

  test('existing hosts can retain their own focus manager', async () => {
    const trigger = document.createElement('button'); document.body.append(trigger); trigger.focus()
    show('旧宿主', '<input>', { trapFocus: false }); await flushPromises()
    expect(document.activeElement).toBe(trigger)
  })

  test('background remains inert until all nested dialogs close, even when a parent unmounts first', async () => {
    const background = document.createElement('main'); document.body.append(background)
    const parent = show('父层'); await flushPromises()
    const child = show('子层'); await flushPromises()
    expect(background.inert).toBe(true)
    parent.unmount(); wrappers.splice(wrappers.indexOf(parent), 1)
    expect(background.inert).toBe(true)
    child.unmount(); wrappers.pop(); await flushPromises()
    expect(background.inert).toBe(false)
  })
})
