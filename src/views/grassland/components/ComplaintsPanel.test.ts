// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, test, vi, beforeEach } from 'vitest'
import ComplaintsPanel from './ComplaintsPanel.vue'

vi.mock('../../../composables/grassland-http', () => ({ request: vi.fn() }))

const { request } = await import('../../../composables/grassland-http')

function complaint(overrides: Record<string, unknown> = {}) {
  return {
    id: 'c-1', targetType: 'task', targetId: 'task-9', reason: 'spam',
    description: '任务涉嫌刷单', status: 'resolved', resolutionNote: '已核实并处理',
    createdAt: '2026-08-21T02:00:00Z', handledAt: '2026-08-21T06:00:00Z', ...overrides,
  }
}

describe('ComplaintsPanel（个人设置弹窗·举报与投诉，任务书 #74）', () => {
  beforeEach(() => {
    vi.mocked(request).mockReset()
  })

  test('挂载即拉「我的投诉」并展示状态与结论；分流文案可见', async () => {
    vi.mocked(request).mockResolvedValueOnce({ items: [complaint()] })
    const wrapper = mount(ComplaintsPanel, { global: { stubs: { Teleport: true } } })
    await flushPromises()

    expect(request).toHaveBeenCalledWith('/api/complaints/mine')
    expect(wrapper.text()).toContain('任务涉嫌刷单')
    expect(wrapper.text()).toContain('已办结')
    expect(wrapper.text()).toContain('处置结论：已核实并处理')
    // D8 分流文案：兜底表单头部固定展示
    expect(wrapper.text()).toContain('开启争议，由审判流程处理；举报投诉由客服受理')
  })

  test('兜底表单对象自由选（六值全量）；原因选项按所选对象联动', async () => {
    vi.mocked(request).mockResolvedValueOnce({ items: [] })
    const wrapper = mount(ComplaintsPanel, { global: { stubs: { Teleport: true } } })
    await flushPromises()

    const targetSelect = wrapper.get('select')
    // 六值全量：含场景化入口未覆盖的 content/order/other
    expect(targetSelect.findAll('option').map((o) => o.element.value))
      .toEqual(['task', 'submission', 'content', 'order', 'user', 'other'])

    // order → 只剩 fraud/other；旧原因「垃圾信息」被校正为「涉嫌欺诈」
    await targetSelect.setValue('order')
    let reasonOptions = wrapper.findAll('select')[1].findAll('option')
    expect(reasonOptions.map((o) => o.text())).toEqual(['涉嫌欺诈', '其他'])
    expect((wrapper.findAll('select')[1].element as HTMLSelectElement).value).toBe('fraud')

    // 换回 submission → 五值全量
    await targetSelect.setValue('submission')
    reasonOptions = wrapper.findAll('select')[1].findAll('option')
    expect(reasonOptions.map((o) => o.text())).toEqual(['侵权', '违规内容', '涉嫌欺诈', '垃圾信息', '其他'])
  })

  test('提交走 POST /api/complaints，成功后清空描述与标识并刷新列表', async () => {
    vi.mocked(request).mockResolvedValueOnce({ items: [] })
    const wrapper = mount(ComplaintsPanel, { global: { stubs: { Teleport: true } } })
    await flushPromises()
    vi.mocked(request).mockClear()

    vi.mocked(request).mockResolvedValueOnce(complaint({ status: 'open', resolutionNote: null }))
    vi.mocked(request).mockResolvedValueOnce({ items: [complaint()] })
    await wrapper.get('select').setValue('user')
    await wrapper.findAll('select')[1].setValue('fraud')
    await wrapper.find('input[maxlength="128"]').setValue('acct-xyz')
    await wrapper.find('textarea').setValue('账号冒充他人')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const post = vi.mocked(request).mock.calls.find((call) => String(call[0]).includes('/api/complaints'))
    expect(post?.[0]).toBe('/api/complaints')
    expect(post?.[1]).toMatchObject({ method: 'POST' })
    expect(JSON.parse(String(post?.[1]?.body))).toMatchObject({
      targetType: 'user', targetId: 'acct-xyz', reason: 'fraud', description: '账号冒充他人',
    })
    expect(wrapper.text()).toContain('已提交，客服会尽快处理')
    // 提交成功清空草稿字段
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('')
    expect((wrapper.find('input[maxlength="128"]').element as HTMLInputElement).value).toBe('')
  })

  test('提交失败（409 重复）展示错误不炸 UI', async () => {
    vi.mocked(request).mockResolvedValueOnce({ items: [] })
    const wrapper = mount(ComplaintsPanel, { global: { stubs: { Teleport: true } } })
    await flushPromises()
    vi.mocked(request).mockClear()

    vi.mocked(request).mockRejectedValueOnce(new Error('已有同对象的在办投诉，请等待处理或补充新的原因'))
    await wrapper.find('textarea').setValue('重复举报')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('已有同对象的在办投诉')
  })
})
