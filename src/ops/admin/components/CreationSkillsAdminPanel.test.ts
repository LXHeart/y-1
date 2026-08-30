// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import CreationSkillsAdminPanel from './CreationSkillsAdminPanel.vue'

/**
 * 创作风格 skill 治理台面板（任务书 #57 决策 G）：列表+分类过滤、启停 change 即 PUT、
 * 编辑弹窗整行提交、乐观锁 409 统一口径。弹窗经 Teleport——mount 必带 teleport stub。
 */

function skill(overrides: Record<string, unknown> = {}) {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    category: 'TITLE_FORMULA',
    code: 'number',
    name: '数字型',
    description: '数字量化收获，阅读门槛低',
    promptContent: '全部候选标题都必须包含具体数字……',
    enabled: true,
    sortOrder: 1,
    version: 0,
    updatedAt: '2026-08-30T10:00:00Z',
    ...overrides,
  }
}

const SKILLS = [
  skill(),
  skill({
    id: '22222222-2222-2222-2222-222222222222',
    category: 'GENRE',
    code: 'practical_guide',
    name: '干货攻略型',
    description: '分步保姆级教程，收藏率高',
    promptContent: '正文按保姆级教程组织……',
  }),
  skill({
    id: '33333333-3333-3333-3333-333333333333',
    category: 'STYLE',
    code: 'professional',
    name: '专业博主风',
    description: '数据依据，克制冷静',
    promptContent: '全文克制冷静……',
  }),
]

const fetchMock = vi.fn()

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => {
  fetchMock.mockReset()
  fetchMock.mockResolvedValue(jsonResponse({ success: true, data: { skills: SKILLS } }))
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

enableAutoUnmount(afterEach)

function mountPanel() {
  return mount(CreationSkillsAdminPanel, {
    global: { stubs: { Teleport: true } },
  })
}

describe('CreationSkillsAdminPanel', () => {
  test('渲染全量列表与分类过滤 chips；行含 code/描述/启停/编辑', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/creation-style-skills')
    expect(wrapper.findAll('.skills-table tbody tr')).toHaveLength(3)
    expect(wrapper.get('[data-test="creation-skills-toggle-number"]').attributes('checked')).toBeDefined()

    await wrapper.get('[data-test="creation-skills-filter-GENRE"]').trigger('click')
    expect(wrapper.findAll('.skills-table tbody tr')).toHaveLength(1)
    expect(wrapper.text()).toContain('干货攻略型')

    await wrapper.get('[data-test="creation-skills-filter-ALL"]').trigger('click')
    expect(wrapper.findAll('.skills-table tbody tr')).toHaveLength(3)
  })

  test('启用开关 change 即 PUT 整行（带 expectedVersion），成功后行内生效', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ success: true, data: { skills: SKILLS } }))
    fetchMock.mockResolvedValueOnce(jsonResponse({
      success: true,
      data: { skill: skill({ enabled: false, version: 1 }) },
    }))
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('[data-test="creation-skills-toggle-number"]').setValue(false)
    await flushPromises()

    const put = fetchMock.mock.calls[1]
    expect(put[0]).toBe('/api/admin/creation-style-skills/11111111-1111-1111-1111-111111111111')
    expect(put[1].method).toBe('PUT')
    expect(JSON.parse(put[1].body)).toEqual({
      name: '数字型',
      description: '数字量化收获，阅读门槛低',
      promptContent: '全部候选标题都必须包含具体数字……',
      enabled: false,
      expectedVersion: 0,
    })
    expect(wrapper.text()).toContain('已停用')
  })

  test('编辑弹窗：改 prompt 保存整行 PUT；409 → 统一提示', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ success: true, data: { skills: SKILLS } }))
    fetchMock.mockResolvedValueOnce(jsonResponse({ success: false, error: '该风格已被他人修改，请刷新后重试' }, 409))
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('[data-test="creation-skills-edit-number"]').trigger('click')
    expect(wrapper.find('[data-test="creation-skills-modal"]').exists()).toBe(true)
    expect((wrapper.get('[data-test="creation-skills-modal-prompt"]').element as HTMLTextAreaElement).value)
      .toBe('全部候选标题都必须包含具体数字……')

    await wrapper.get('[data-test="creation-skills-modal-prompt"]').setValue('修订后的数字套路指令')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const put = JSON.parse(fetchMock.mock.calls[1][1].body)
    expect(put.promptContent).toBe('修订后的数字套路指令')
    expect(put.expectedVersion).toBe(0)
    // 409：弹窗保留，统一口径提示
    expect(wrapper.get('[data-test="creation-skills-modal-error"]').text()).toBe('已被他人修改，请刷新后重试')
    expect(wrapper.find('[data-test="creation-skills-modal"]').exists()).toBe(true)
  })

  test('编辑保存成功：行更新、弹窗关闭', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ success: true, data: { skills: SKILLS } }))
    fetchMock.mockResolvedValueOnce(jsonResponse({
      success: true,
      data: { skill: skill({ description: '改过的描述', version: 1 }) },
    }))
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('[data-test="creation-skills-edit-number"]').trigger('click')
    await wrapper.get('[data-test="creation-skills-modal-desc"]').setValue('改过的描述')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[data-test="creation-skills-modal"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('改过的描述')
  })

  test('加载失败：内联错误 + 可刷新重试', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ success: false, error: '需要平台管理员权限' }, 403))
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.get('.error-msg').text()).toContain('需要平台管理员权限')

    fetchMock.mockResolvedValueOnce(jsonResponse({ success: true, data: { skills: SKILLS } }))
    await wrapper.get('[data-test="creation-skills-refresh"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.skills-table tbody tr')).toHaveLength(3)
  })
})
