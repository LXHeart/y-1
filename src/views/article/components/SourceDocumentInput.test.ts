// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import SourceDocumentInput from './SourceDocumentInput.vue'
import type { SourceInputState } from '../composables/useSourceDocument'

/**
 * 任务书 #101 C101-03：原稿输入组件（§8.1/§8.2）。
 * 锁定：导入请求透传（编排层不在此）、保存中禁用、非 UTF-8 文件保留原输入、
 * 超限提示、错误状态展示与已导入态切换。
 */
enableAutoUnmount(afterEach)

function mountInput(state: SourceInputState = 'editing', error = '') {
  return mount(SourceDocumentInput, {
    props: {
      state,
      error,
      importedHash: null,
      note: '改编模式：原稿进入正文后，可按需发起改编建议',
    },
  })
}

describe('SourceDocumentInput', () => {
  beforeEach(() => { vi.unstubAllGlobals() })
  afterEach(() => { vi.unstubAllGlobals() })

  test('粘贴文本后点击导入，向父层透传 kind 与全文（不截断）', async () => {
    const wrapper = mountInput()
    const longText = '第一段。\n\n第二段：价格 32.5 元。'.repeat(50)
    await wrapper.get('[data-testid="source-text"]').setValue(longText)
    await wrapper.get('[data-testid="source-import"]').trigger('click')
    const events = wrapper.emitted('import')
    expect(events).toHaveLength(1)
    expect(events![0][0]).toEqual({ kind: 'plain-text', text: longText })
  })

  test('Markdown 模式切换生效', async () => {
    const wrapper = mountInput()
    await wrapper.get('[data-testid="source-text"]').setValue('# 标题\n\n正文')
    const buttons = wrapper.findAll('.segmented button')
    await buttons[1].trigger('click')
    await wrapper.get('[data-testid="source-import"]').trigger('click')
    expect(wrapper.emitted('import')![0][0]).toMatchObject({ kind: 'markdown' })
  })

  test('空文本禁用导入按钮', async () => {
    const wrapper = mountInput()
    const button = wrapper.get('[data-testid="source-import"]')
    expect((button.element as HTMLButtonElement).disabled).toBe(true)
    await wrapper.get('[data-testid="source-text"]').setValue('  ')
    expect((button.element as HTMLButtonElement).disabled).toBe(true)
  })

  test('超过 30,000 字符显示超限并禁用导入（不静默截断）', async () => {
    const wrapper = mountInput()
    await wrapper.get('[data-testid="source-text"]').setValue('字'.repeat(30_001))
    expect(wrapper.get('.source-counter').text()).toContain('30001')
    expect(wrapper.get('.source-counter').classes()).toContain('source-counter-over')
    expect((wrapper.get('[data-testid="source-import"]').element as HTMLButtonElement).disabled).toBe(true)
  })

  test('非 UTF-8 文件读取失败：报错且此前粘贴的正文保留（TC101-012）', async () => {
    const wrapper = mountInput()
    await wrapper.get('[data-testid="source-text"]').setValue('既有粘贴内容')
    const invalidBytes = new Uint8Array([0xd0, 0xcf, 0xd0, 0xb8, 0xff, 0xfe]) // 非 UTF-8 序列
    const file = new File([invalidBytes], 'gbk.txt', { type: 'text/plain' })
    const input = wrapper.get('[data-testid="source-file"]') as unknown as { element: HTMLInputElement }
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.element.dispatchEvent(new Event('change'))
    await flushPromises()
    expect(wrapper.get('[data-testid="source-file-error"]').text()).toContain('UTF-8')
    expect((wrapper.get('[data-testid="source-text"]').element as HTMLTextAreaElement).value).toBe('既有粘贴内容')
  })

  test('UTF-8 文件读取成功：填入文本并按扩展名选 Markdown', async () => {
    const wrapper = mountInput()
    const file = new File([new TextEncoder().encode('# 草稿\n\n正文')], 'draft.md', { type: 'text/markdown' })
    const input = wrapper.get('[data-testid="source-file"]') as unknown as { element: HTMLInputElement }
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.element.dispatchEvent(new Event('change'))
    await flushPromises()
    expect((wrapper.get('[data-testid="source-text"]').element as HTMLTextAreaElement).value).toContain('# 草稿')
    expect(wrapper.findAll('.segmented button')[1].classes()).toContain('active')
    expect(wrapper.emitted('edit')).toHaveLength(1)
  })

  test('saving 状态禁重复导入；error 状态展示服务端错误（保留输入）', async () => {
    const wrapper = mountInput('saving')
    expect(wrapper.get('[data-testid="source-import"]').text()).toContain('导入中…')
    expect((wrapper.get('[data-testid="source-import"]').element as HTMLButtonElement).disabled).toBe(true)
    const errorWrapper = mountInput('error', '同一 requestId 已用于不同内容的来源导入')
    expect(errorWrapper.get('[data-testid="source-error"]').text()).toContain('requestId')
  })

  test('saved 状态切换为已导入提示，不残留输入控件', () => {
    const wrapper = mount(SourceDocumentInput, {
      props: { state: 'saved', error: '', importedHash: 'a'.repeat(64), note: '排版模式' },
    })
    expect(wrapper.find('[data-testid="source-imported"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="source-text"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="source-imported"]').text()).toContain('aaaaaaaaaaaa')
  })
})
