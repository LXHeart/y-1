// @vitest-environment happy-dom
import { describe, expect, test, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import OrgCreationAuditPanel from './OrgCreationAuditPanel.vue'

vi.mock('../composables/grassland-http', () => ({ request: vi.fn() }))
vi.mock('../composables/useCreationGenerations', async (importOriginal) => {
  const original = await importOriginal<typeof import('../composables/useCreationGenerations')>()
  return { ...original, listOrgCreationGenerations: vi.fn() }
})

import type { OrgCreationGenerationSummary } from '../types/grassland/creation-generation'

const { listOrgCreationGenerations } = await import('../composables/useCreationGenerations')

function row(overrides: Partial<OrgCreationGenerationSummary> = {}): OrgCreationGenerationSummary {
  return {
    id: 'g-1', kind: 'article', mode: 'independent', provider: 'qwen', model: 'qwen-plus',
    resultTitle: '文章正文 100 字', createdAt: '2026-08-21T10:00:00Z',
    ownerAccountId: 'account-owner-1', ...overrides,
  }
}

describe('OrgCreationAuditPanel（组织级创作审计视图）', () => {
  beforeEach(() => {
    vi.mocked(listOrgCreationGenerations).mockReset()
  })

  test('挂载即加载组织产出并展示成员/类型/模型', async () => {
    vi.mocked(listOrgCreationGenerations).mockResolvedValue({
      items: [row(), row({ id: 'g-2', kind: 'moments_copy', mode: 'task', ownerAccountId: 'account-owner-2' })],
      nextBefore: null,
    })
    const wrapper = mount(OrgCreationAuditPanel, { props: { organizationId: 'org-1' } })
    await flushPromises()

    expect(listOrgCreationGenerations).toHaveBeenCalledWith('org-1', expect.objectContaining({ limit: 20 }))
    expect(wrapper.text()).toContain('account-owner-1'.slice(0, 8))
    expect(wrapper.text()).toContain('文章正文')
    expect(wrapper.text()).toContain('朋友圈文案')
    expect(wrapper.text()).toContain('任务')
    expect(wrapper.text()).toContain('qwen · qwen-plus')
    expect(wrapper.find('button.audit-more').exists()).toBe(false)
  })

  test('kind 过滤变化触发重新加载（reset）', async () => {
    vi.mocked(listOrgCreationGenerations).mockResolvedValue({ items: [row()], nextBefore: null })
    const wrapper = mount(OrgCreationAuditPanel, { props: { organizationId: 'org-1' } })
    await flushPromises()

    await wrapper.find('select').setValue('comedy_script')
    await flushPromises()

    expect(listOrgCreationGenerations).toHaveBeenLastCalledWith('org-1',
      expect.objectContaining({ kind: 'comedy_script', limit: 20 }))
  })

  test('游标分页：nextBefore 存在时展示加载更多并续页', async () => {
    vi.mocked(listOrgCreationGenerations)
      .mockResolvedValueOnce({ items: [row()], nextBefore: 'g-1' })
      .mockResolvedValueOnce({ items: [row({ id: 'g-2', resultTitle: '喜剧脚本 200 字' })], nextBefore: null })
    const wrapper = mount(OrgCreationAuditPanel, { props: { organizationId: 'org-1' } })
    await flushPromises()

    await wrapper.find('button.audit-more').trigger('click')
    await flushPromises()

    expect(listOrgCreationGenerations).toHaveBeenLastCalledWith('org-1',
      expect.objectContaining({ before: 'g-1' }))
    expect(wrapper.text()).toContain('喜剧脚本 200 字')
    expect(wrapper.find('button.audit-more').exists()).toBe(false)
  })

  test('加载失败展示错误且不炸 UI', async () => {
    vi.mocked(listOrgCreationGenerations).mockRejectedValue(new Error('生成记录加载失败'))
    const wrapper = mount(OrgCreationAuditPanel, { props: { organizationId: 'org-1' } })
    await flushPromises()

    expect(wrapper.text()).toContain('生成记录加载失败')
    expect(wrapper.text()).toContain('该组织暂无创作产出记录')
  })
})
