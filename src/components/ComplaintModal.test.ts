// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import ComplaintModal from './ComplaintModal.vue'
import type { ComplaintTargetType } from '../composables/useComplaints'

vi.mock('../composables/grassland-http', () => ({ request: vi.fn() }))

const { request } = await import('../composables/grassland-http')

enableAutoUnmount(afterEach)

function mountModal(props: {
  open?: boolean
  targetType?: ComplaintTargetType
  targetId?: string
  targetSummary?: string
} = {}) {
  return mount(ComplaintModal, {
    props: {
      open: true,
      targetType: 'task',
      targetId: 'task-9abcdef',
      targetSummary: '新春探店图文征集',
      ...props,
    },
    global: { stubs: { Teleport: true } },
  })
}

describe('ComplaintModal（场景化举报弹窗，任务书 #74）', () => {
  beforeEach(() => {
    vi.mocked(request).mockReset()
    vi.mocked(request).mockResolvedValue({ id: 'c-1' })
  })

  test('open=false 不渲染弹窗', () => {
    const wrapper = mountModal({ open: false })
    expect(wrapper.find('[data-testid="gl-modal-overlay"]').exists()).toBe(false)
  })

  test('标题带对象标签；对象摘要行展示 summary 与 id 前 8 位；无对象下拉（锁定不可改）', () => {
    const wrapper = mountModal()
    expect(wrapper.get('.modal-title').text()).toBe('举报任务')
    expect(wrapper.text()).toContain('新春探店图文征集')
    expect(wrapper.get('.complaint-target code').text()).toBe('task-9ab…')
    // 场景化弹窗只有原因一个 select——对象下拉不渲染（自由选择只留给兜底表单）
    expect(wrapper.findAll('select')).toHaveLength(1)
  })

  test.each([
    ['task', ['垃圾信息', '涉嫌欺诈', '违规内容', '其他']],
    ['order', ['涉嫌欺诈', '其他']],
    ['user', ['涉嫌欺诈', '违规内容', '垃圾信息', '其他']],
  ])('原因选项按对象过滤：%s → %j', async (targetType, labels) => {
    const wrapper = mountModal({ targetType: targetType as ComplaintTargetType })
    const options = wrapper.get('select').findAll('option')
    expect(options.map((o) => o.text())).toEqual(labels)
    // 默认选中首个合法选项
    expect((wrapper.get('select').element as HTMLSelectElement).value).toBe(options[0].element.value)
  })

  test('换目标打开时旧原因不在新选项里则校正到首个合法值', async () => {
    const wrapper = mountModal({ targetType: 'task' })
    // task 的「垃圾信息」在 order 的选项里不存在
    await wrapper.get('select').setValue('spam')
    await wrapper.setProps({ open: false, targetType: 'order' })
    await wrapper.setProps({ open: true })
    expect((wrapper.get('select').element as HTMLSelectElement).value).toBe('fraud')
  })

  test('空描述不提交，行内提示而非请求', async () => {
    const wrapper = mountModal()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(request).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请填写问题描述')
    expect(wrapper.emitted('close')).toBeUndefined()
  })

  test('提交成功走 POST /api/complaints 并 emit close（open 由父控）', async () => {
    const wrapper = mountModal()
    await wrapper.get('select').setValue('fraud')
    await wrapper.find('textarea').setValue('任务涉嫌刷单')
    await wrapper.findAll('button').find((b) => b.text() === '提交')!.trigger('click')
    await flushPromises()

    expect(request).toHaveBeenCalledWith('/api/complaints', {
      method: 'POST',
      body: JSON.stringify({
        targetType: 'task',
        targetId: 'task-9abcdef',
        reason: 'fraud',
        description: '任务涉嫌刷单',
      }),
    })
    expect(wrapper.emitted('close')).toHaveLength(1)
    // 成功后清空描述草稿
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('')
  })

  test('提交失败（409 重复）原样展示服务端文案，不炸 UI', async () => {
    vi.mocked(request).mockRejectedValueOnce(new Error('已有同对象的在办投诉，请等待处理或补充新的原因'))
    const wrapper = mountModal()
    await wrapper.find('textarea').setValue('重复举报')
    await wrapper.findAll('button').find((b) => b.text() === '提交')!.trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('已有同对象的在办投诉')
    expect(wrapper.emitted('close')).toBeUndefined()
  })
})
