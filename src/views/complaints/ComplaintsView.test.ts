// @vitest-environment happy-dom
import { describe, expect, test, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ComplaintsView from './ComplaintsView.vue'

vi.mock('../../composables/grassland-http', () => ({ request: vi.fn() }))

const { request } = await import('../../composables/grassland-http')

function complaint(overrides: Record<string, unknown> = {}) {
  return {
    id: 'c-1', targetType: 'task', targetId: 'task-9', reason: 'spam',
    description: '任务涉嫌刷单', status: 'resolved', resolutionNote: '已核实并处理',
    createdAt: '2026-08-21T02:00:00Z', handledAt: '2026-08-21T06:00:00Z', ...overrides,
  }
}

describe('ComplaintsView（用户举报/投诉）', () => {
  beforeEach(() => {
    vi.mocked(request).mockReset()
  })

  test('挂载即拉「我的投诉」并展示状态与结论', async () => {
    vi.mocked(request).mockResolvedValueOnce({ items: [complaint()] })
    const wrapper = mount(ComplaintsView)
    await flushPromises()

    expect(request).toHaveBeenCalledWith('/api/complaints/mine')
    expect(wrapper.text()).toContain('任务涉嫌刷单')
    expect(wrapper.text()).toContain('已办结')
    expect(wrapper.text()).toContain('处置结论：已核实并处理')
  })

  test('提交投诉走 POST /api/complaints 并刷新列表', async () => {
    vi.mocked(request).mockResolvedValueOnce({ items: [] })
    const wrapper = mount(ComplaintsView)
    await flushPromises()
    vi.mocked(request).mockClear()

    vi.mocked(request).mockResolvedValueOnce(complaint({ status: 'open', resolutionNote: null }))
    vi.mocked(request).mockResolvedValueOnce({ items: [complaint()] })
    await wrapper.find('select').setValue('user')
    await wrapper.findAll('select')[1].setValue('fraud')
    await wrapper.find('textarea').setValue('账号冒充他人')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const post = vi.mocked(request).mock.calls.find((call) => String(call[0]).includes('/api/complaints'))
    expect(post?.[0]).toBe('/api/complaints')
    expect(post?.[1]).toMatchObject({ method: 'POST' })
    expect(JSON.parse(String(post?.[1]?.body))).toMatchObject({
      targetType: 'user', reason: 'fraud', description: '账号冒充他人',
    })
    expect(wrapper.text()).toContain('已提交，客服会尽快处理')
  })

  test('提交失败（409 重复）展示错误不炸 UI', async () => {
    vi.mocked(request).mockResolvedValueOnce({ items: [] })
    const wrapper = mount(ComplaintsView)
    await flushPromises()
    vi.mocked(request).mockClear()

    vi.mocked(request).mockRejectedValueOnce(new Error('已有同对象的在办投诉，请等待处理或补充新的原因'))
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('已有同对象的在办投诉')
  })
})
