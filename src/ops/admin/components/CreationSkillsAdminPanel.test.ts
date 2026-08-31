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
    // 任务书 #62 P3：归属字段，空数组=通用
    applicablePlatforms: [] as string[],
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
  // 任务书 #62：知乎专属种子（治理台不按平台过滤，全量可见可编辑）
  skill({
    id: '44444444-4444-4444-4444-444444444444',
    category: 'STYLE',
    code: 'analytical',
    name: '理性分析流',
    description: '结论先行，论据分层',
    promptContent: '结论先行……',
    applicablePlatforms: ['zhihu'],
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
    expect(wrapper.findAll('.skills-table tbody tr')).toHaveLength(4)
    expect(wrapper.get('[data-test="creation-skills-toggle-number"]').attributes('checked')).toBeDefined()

    await wrapper.get('[data-test="creation-skills-filter-GENRE"]').trigger('click')
    expect(wrapper.findAll('.skills-table tbody tr')).toHaveLength(1)
    expect(wrapper.text()).toContain('干货攻略型')

    await wrapper.get('[data-test="creation-skills-filter-ALL"]').trigger('click')
    expect(wrapper.findAll('.skills-table tbody tr')).toHaveLength(4)
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
      // 任务书 #62 P3：归属总是显式发——省略键在后端表示「保持原样」，靠省略改不回通用
      applicablePlatforms: [],
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
    expect(wrapper.findAll('.skills-table tbody tr')).toHaveLength(4)
  })
})

/**
 * 任务书 #62 卡8：适用平台列 + 弹窗归属多选。治理台是**全量视角**——按平台过滤只发生在
 * 用户端创作流目录，面板不过滤（否则运营改不动别平台的条目）。
 */
describe('CreationSkillsAdminPanel 适用平台归属（任务书 #62 卡8）', () => {
  test('列表展示适用平台：空数组=通用，指定则显示平台名', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.get('[data-test="creation-skills-platforms-number"]').text()).toBe('通用')
    expect(wrapper.get('[data-test="creation-skills-platforms-analytical"]').text()).toBe('知乎')
  })

  test('弹窗按行现值回填勾选，多选保存整行 PUT 带归属数组', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ success: true, data: { skills: SKILLS } }))
    fetchMock.mockResolvedValueOnce(jsonResponse({
      success: true,
      data: { skill: skill({ applicablePlatforms: ['zhihu', 'xiaohongshu'], version: 1 }) },
    }))
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('[data-test="creation-skills-edit-number"]').trigger('click')
    // 通用条目：四档全不勾
    expect(wrapper.get('[data-test="creation-skills-modal-platform-zhihu"]').attributes('checked'))
      .toBeUndefined()
    expect(wrapper.get('[data-test="creation-skills-modal-platform-hint"]').text()).toContain('通用')

    await wrapper.get('[data-test="creation-skills-modal-platform-zhihu"]').trigger('change')
    await wrapper.get('[data-test="creation-skills-modal-platform-xiaohongshu"]').trigger('change')
    expect(wrapper.get('[data-test="creation-skills-modal-platform-hint"]').text()).toContain('知乎 / 小红书')

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(JSON.parse(fetchMock.mock.calls[1][1].body).applicablePlatforms)
      .toEqual(['zhihu', 'xiaohongshu'])
    expect(wrapper.get('[data-test="creation-skills-platforms-number"]').text()).toBe('知乎 / 小红书')
  })

  test('取消勾选到全空 → 显式发空数组改回通用（省略键在后端表示保持原样）', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ success: true, data: { skills: SKILLS } }))
    fetchMock.mockResolvedValueOnce(jsonResponse({
      success: true,
      data: { skill: skill({ id: '44444444-4444-4444-4444-444444444444', category: 'STYLE', code: 'analytical', name: '理性分析流', applicablePlatforms: [], version: 1 }) },
    }))
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('[data-test="creation-skills-edit-analytical"]').trigger('click')
    expect(wrapper.get('[data-test="creation-skills-modal-platform-zhihu"]').attributes('checked'))
      .toBeDefined()

    await wrapper.get('[data-test="creation-skills-modal-platform-zhihu"]').trigger('change')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(JSON.parse(fetchMock.mock.calls[1][1].body).applicablePlatforms).toEqual([])
    expect(wrapper.get('[data-test="creation-skills-platforms-analytical"]').text()).toBe('通用')
  })

  test('未知平台 id 原样显示（存量数据不被吞掉）', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({
      success: true,
      data: { skills: [skill({ applicablePlatforms: ['kuaishou'] })] },
    }))
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.get('[data-test="creation-skills-platforms-number"]').text()).toBe('kuaishou')
  })
})
