// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import DisputeEvidenceForm from './DisputeEvidenceForm.vue'
import type { DisputeEvidenceItemInput } from '../composables/useDisputeEvidenceActions'

enableAutoUnmount(afterEach)

const CASE_A = '11111111-1111-4111-8111-111111111111'
const CASE_B = '22222222-2222-4222-8222-222222222222'

function mountForm(props: Partial<InstanceType<typeof DisputeEvidenceForm>['$props']> = {}) {
  const submitted: DisputeEvidenceItemInput[][] = []
  const cancelled = vi.fn()
  const wrapper = mount(DisputeEvidenceForm, {
    props: {
      phase: 'answer',
      caseKey: CASE_A,
      caseLabel: CASE_A.slice(0, 8),
      submitting: false,
      contextCurrent: true,
      // VTU2 事件经 onX props 传递（listeners 选项已移除）
      onSubmit: (items: DisputeEvidenceItemInput[]) => { submitted.push(items) },
      onCancel: () => { cancelled() },
      ...props,
    },
    // GlModal Teleport 到 body——happy-dom 下 stub 掉保持内容在 wrapper 内可查
    global: { stubs: { teleport: true } },
  })
  // GlModal 的 useDialogFocus 在 onMounted 异步激活（焦点陷阱/inert 背景），
  // 先等一拍再查询，避免挂载期 DOM 变动窗口。
  void flushPromises()
  return { wrapper, submitted, cancelled }
}

const textOf = (wrapper: ReturnType<typeof mount>) => (wrapper.find('[data-testid="evidence-text"]').element as HTMLTextAreaElement)
const captionOf = (wrapper: ReturnType<typeof mount>) => (wrapper.find('[data-testid="evidence-caption"]').element as HTMLInputElement)

beforeEach(() => {
  document.body.innerHTML = ''
})
afterEach(() => {
  vi.restoreAllMocks()
})

test('TC103-12-01 表单展示绑定案号与阶段标题；空白正文不发', async () => {
  const { wrapper, submitted } = mountForm({ phase: 'rebuttal' })
  expect(wrapper.text()).toContain('补充质证')
  expect(wrapper.find('[data-testid="evidence-form-case"]').text()).toContain(CASE_A.slice(0, 8))

  await wrapper.find('[data-testid="evidence-submit"]').trigger('click')
  expect(submitted).toHaveLength(0)
  expect(wrapper.find('[data-testid="evidence-form-error"]').text()).toContain('请输入证据内容')
})

test('TC103-12-01 合法文本固定原案提交；caption 空白转 undefined', async () => {
  const { wrapper, submitted } = mountForm()
  textOf(wrapper).value = '  已按约定交付并验收  '
  textOf(wrapper).dispatchEvent(new Event('input'))
  captionOf(wrapper).value = '  '
  captionOf(wrapper).dispatchEvent(new Event('input'))
  await nextTick()
  await wrapper.find('[data-testid="evidence-submit"]').trigger('click')
  expect(submitted).toHaveLength(1)
  expect(submitted[0]).toEqual([{ kind: 'text', contentRef: '已按约定交付并验收', caption: undefined }])
})

test('TC103-12-05 长度边界 9,999/10,000 通过、10,001 拦截且不静默截断（caption 499/500/501 同口径）', async () => {
  const { wrapper, submitted } = mountForm()
  const setText = async (value: string) => {
    const textarea = textOf(wrapper)
    textarea.value = value
    textarea.dispatchEvent(new Event('input'))
    await nextTick()
  }
  await setText('a'.repeat(9_999))
  await wrapper.find('[data-testid="evidence-submit"]').trigger('click')
  await setText('a'.repeat(10_000))
  await wrapper.find('[data-testid="evidence-submit"]').trigger('click')
  expect(submitted).toHaveLength(2)
  expect(submitted[1][0]!.contentRef).toHaveLength(10_000)

  await setText('a'.repeat(10_001))
  await wrapper.find('[data-testid="evidence-submit"]').trigger('click')
  expect(submitted).toHaveLength(2) // 未新增
  expect(wrapper.find('[data-testid="evidence-form-error"]').text()).toContain('10000')
  // 输入框内容未被截断（保留原文供用户自行精简）
  expect(textOf(wrapper).value).toHaveLength(10_001)

  const setCaption = async (value: string) => {
    const input = captionOf(wrapper)
    input.value = value
    input.dispatchEvent(new Event('input'))
    await nextTick()
  }
  await setText('合法正文')
  await setCaption('c'.repeat(499))
  await wrapper.find('[data-testid="evidence-submit"]').trigger('click')
  await setCaption('c'.repeat(500))
  await wrapper.find('[data-testid="evidence-submit"]').trigger('click')
  expect(submitted).toHaveLength(4)
  await setCaption('c'.repeat(501))
  await wrapper.find('[data-testid="evidence-submit"]').trigger('click')
  expect(submitted).toHaveLength(4)
  expect(wrapper.find('[data-testid="evidence-form-error"]').text()).toContain('500')
})

test('TC103-12-06 切案清表单：caseKey 变化立即清空正文与说明', async () => {
  const { wrapper } = mountForm()
  textOf(wrapper).value = 'A 案的答辩正文'
  textOf(wrapper).dispatchEvent(new Event('input'))
  captionOf(wrapper).value = 'A 案说明'
  captionOf(wrapper).dispatchEvent(new Event('input'))
  await nextTick()

  await wrapper.setProps({ caseKey: CASE_B, caseLabel: CASE_B.slice(0, 8) })
  expect(textOf(wrapper).value).toBe('')
  expect(captionOf(wrapper).value).toBe('')
})

test('TC103-12-06 上下文失效：提示且不提交（不向旧案/新案发写）', async () => {
  const { wrapper, submitted } = mountForm()
  textOf(wrapper).value = '正文'
  textOf(wrapper).dispatchEvent(new Event('input'))
  await nextTick()
  await wrapper.setProps({ contextCurrent: false })
  await wrapper.find('[data-testid="evidence-submit"]').trigger('click')
  expect(submitted).toHaveLength(0)
  expect(wrapper.text()).toContain('案件目标已变化')
})

test('TC103-12-01 提交中不可取消（不显示写已取消）；提交按钮禁用', async () => {
  const { wrapper, cancelled } = mountForm({ submitting: true })
  const cancelBtn = wrapper.find('[data-testid="evidence-cancel"]')
  expect((cancelBtn.element as HTMLButtonElement).disabled).toBe(true)
  expect((wrapper.find('[data-testid="evidence-submit"]').element as HTMLButtonElement).disabled).toBe(true)
  cancelBtn.trigger('click')
  await flushPromises()
  expect(cancelled).not.toHaveBeenCalled()
})

test('TC103-12-01 取消（非提交中）发 cancel 事件', async () => {
  const { wrapper, cancelled } = mountForm()
  await wrapper.find('[data-testid="evidence-cancel"]').trigger('click')
  expect(cancelled).toHaveBeenCalledTimes(1)
})
