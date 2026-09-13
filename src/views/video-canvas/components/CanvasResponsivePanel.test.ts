// @vitest-environment happy-dom
import { afterEach, describe, expect, test, vi } from 'vitest'
import { DOMWrapper, enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import CanvasResponsivePanel from './CanvasResponsivePanel.vue'

enableAutoUnmount(afterEach)
afterEach(() => { document.body.innerHTML = ''; document.body.style.overflow = '' })
describe('C102 responsive drawer focus and save guard', () => {
  test('traps Tab/Shift-Tab, Escape waits for saving, and closing returns focus to the trigger', async () => {
    const trigger = document.createElement('button'); trigger.textContent = '打开'; document.body.append(trigger); trigger.focus()
    const beforeClose = vi.fn(async () => true)
    const wrapper = mount(CanvasResponsivePanel, { attachTo: document.body,
      props: { open: false, docked: false, title: '镜头详情', beforeClose }, slots: { default: '<input aria-label="镜头标题"><button id="last">保存</button>' } })
    await wrapper.setProps({ open: true }); await flushPromises()
    const dialog = document.querySelector<HTMLElement>('[role="dialog"]')!
    const closeButton = dialog.querySelector<HTMLElement>('[data-action="close-modal"]')!
    expect(dialog.contains(document.activeElement)).toBe(true)
    closeButton.focus(); document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true }))
    expect(document.activeElement?.id).toBe('last')
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }))
    expect(document.activeElement).toBe(closeButton)
    trigger.focus(); expect(dialog.contains(document.activeElement)).toBe(true)
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })); await flushPromises()
    expect(beforeClose).toHaveBeenCalledOnce(); expect(wrapper.emitted('close')).toHaveLength(1)
    await wrapper.setProps({ open: false }); await flushPromises()
    expect(document.activeElement).toBe(trigger); expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(document.body.style.overflow).toBe(''); expect(trigger.inert).not.toBe(true)
  })
  test('failed and in-flight saves retain the drawer, input and a focused error without duplicate submissions', async () => {
    let finish!: (value: boolean) => void
    const beforeClose = vi.fn(() => new Promise<boolean>(resolve => { finish = resolve }))
    const wrapper = mount(CanvasResponsivePanel, { attachTo: document.body,
      props: { open: true, docked: false, title: '备注', beforeClose }, slots: { default: '<input value="未保存备注">' } })
    await flushPromises()
    const close = new DOMWrapper(document.querySelector<HTMLElement>('[data-action="close-modal"]')!)
    await close.trigger('click'); await close.trigger('click'); expect(beforeClose).toHaveBeenCalledOnce()
    finish(false); await flushPromises()
    expect(wrapper.emitted('close')).toBeUndefined()
    expect((document.querySelector('input') as HTMLInputElement).value).toBe('未保存备注')
    expect(document.activeElement?.getAttribute('data-test')).toBe('canvas-panel-close-error')
    await close.trigger('click'); finish(true); await flushPromises(); expect(wrapper.emitted('close')).toHaveLength(1)
  })
  test('switching between desktop and drawer moves the same editor instance and keeps unsaved input', async () => {
    let mounts = 0
    const editor = defineComponent({ setup() { mounts++; return { text: ref('原文') } }, template: '<textarea v-model="text" />' })
    const wrapper = mount(CanvasResponsivePanel, { attachTo: document.body,
      props: { open: true, docked: true, title: '属性', beforeClose: async () => true }, slots: { default: editor } })
    await wrapper.get('textarea').setValue('缩放窗口也保留的输入')
    const original = wrapper.get('textarea').element
    await wrapper.setProps({ docked: false }); await flushPromises()
    expect(document.querySelector('[role="dialog"] textarea')).toBe(original)
    expect((original as HTMLTextAreaElement).value).toBe('缩放窗口也保留的输入')
    await wrapper.setProps({ docked: true }); await flushPromises()
    expect(wrapper.get('textarea').element).toBe(original); expect(mounts).toBe(1)
  })
})
