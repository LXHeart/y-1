// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import HumanizeSkillsAdminPanel from './HumanizeSkillsAdminPanel.vue'

/**
 * 去AI味 skill 治理台面板（任务书 #61）：3 行固定规则的列表、平台级单选激活（含关闭注入）、
 * 启停 change 即 PUT、编辑弹窗整行提交与乐观锁 409 统一口径。弹窗经 Teleport——mount 必带 teleport stub。
 */

function skill(overrides: Record<string, unknown> = {}) {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    code: 'shuorenhua',
    displayName: '说人话',
    description: '口语化直说，删套话',
    promptContent: '不要用「赋能」「闭环」这类词……',
    sourceRepo: 'https://github.com/example/shuorenhua',
    sourceLicense: 'MIT',
    enabled: true,
    version: 0,
    updatedAt: '2026-08-30T10:00:00Z',
    ...overrides,
  }
}

const SKILLS = [
  skill(),
  skill({
    id: '22222222-2222-2222-2222-222222222222',
    code: 'lieflat-11',
    displayName: '躺平十一条',
    description: '十一条硬约束，压缩形容词',
    promptContent: '每句不超过 25 字……',
  }),
  skill({
    id: '33333333-3333-3333-3333-333333333333',
    code: 'qu-ai-wei',
    displayName: '去AI味',
    description: '清除机器腔与排比结构',
    promptContent: '禁止三段排比……',
    enabled: false,
  }),
]

const fetchMock = vi.fn()

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function listBody(activeSkillCode = '', configVersion = 0) {
  return { success: true, data: { skills: SKILLS, activeSkillCode, configVersion } }
}

beforeEach(() => {
  fetchMock.mockReset()
  fetchMock.mockResolvedValue(jsonResponse(listBody()))
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

enableAutoUnmount(afterEach)

function mountPanel() {
  return mount(HumanizeSkillsAdminPanel, {
    global: { stubs: { teleport: true } },
  })
}

describe('HumanizeSkillsAdminPanel', () => {
  test('渲染 3 行规则与激活单选：默认「不注入」选中，停用行不可激活', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/humanize-skills')
    expect(wrapper.findAll('.skills-table tbody tr')).toHaveLength(3)
    expect(wrapper.find('[data-test="humanize-skill-row-shuorenhua"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="humanize-skill-row-qu-ai-wei"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('MIT')

    // 未激活：activeSkillCode 空串 → 「不注入」选中
    expect(wrapper.get('[data-test="humanize-activate-off"]').attributes('checked')).toBeDefined()
    expect(wrapper.get('[data-test="humanize-activate-shuorenhua"]').attributes('checked')).toBeUndefined()
    // 停用的 skill 不能被激活
    expect(wrapper.get('[data-test="humanize-activate-qu-ai-wei"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="humanize-skill-toggle-qu-ai-wei"]').attributes('checked')).toBeUndefined()
    expect(wrapper.text()).toContain('修改即刻生效')
  })

  test('激活切换 change 即 PUT（带 expectedConfigVersion）；切「不注入」下发 null', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(listBody()))
    fetchMock.mockResolvedValueOnce(jsonResponse({
      success: true,
      data: { activeSkillCode: 'shuorenhua', configVersion: 1 },
    }))
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('[data-test="humanize-activate-shuorenhua"]').setValue()
    await flushPromises()

    const activate = fetchMock.mock.calls[1]
    expect(activate[0]).toBe('/api/admin/humanize-skills/active')
    expect(activate[1].method).toBe('PUT')
    expect(JSON.parse(activate[1].body)).toEqual({ activeSkillCode: 'shuorenhua', expectedConfigVersion: 0 })
    expect(wrapper.get('[data-test="humanize-activate-shuorenhua"]').attributes('checked')).toBeDefined()

    // 关闭注入：activeSkillCode 下发 null，版本号用服务端回传值（1）而非本地自增猜测
    fetchMock.mockResolvedValueOnce(jsonResponse({ success: true, data: { activeSkillCode: '', configVersion: 2 } }))
    await wrapper.get('[data-test="humanize-activate-off"]').setValue()
    await flushPromises()

    expect(JSON.parse(fetchMock.mock.calls[2][1].body))
      .toEqual({ activeSkillCode: null, expectedConfigVersion: 1 })
    expect(wrapper.get('[data-test="humanize-activate-off"]').attributes('checked')).toBeDefined()
  })

  test('编辑弹窗：整行 PUT 带 expectedVersion；409 → 统一提示且弹窗保留，重试成功后关闭', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(listBody()))
    fetchMock.mockResolvedValueOnce(jsonResponse({ success: false, error: '激活配置已被他人修改，请刷新后重试' }, 409))
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('[data-test="humanize-skill-edit-shuorenhua"]').trigger('click')
    expect((wrapper.get('[data-test="humanize-skill-modal-prompt"]').element as HTMLTextAreaElement).value)
      .toBe('不要用「赋能」「闭环」这类词……')

    await wrapper.get('[data-test="humanize-skill-modal-prompt"]').setValue('改写后的规则正文')
    await wrapper.get('[data-test="humanize-skill-modal-save"]').trigger('click')
    await flushPromises()

    const put = fetchMock.mock.calls[1]
    expect(put[0]).toBe('/api/admin/humanize-skills/11111111-1111-1111-1111-111111111111')
    expect(put[1].method).toBe('PUT')
    expect(JSON.parse(put[1].body)).toEqual({
      displayName: '说人话',
      description: '口语化直说，删套话',
      promptContent: '改写后的规则正文',
      enabled: true,
      expectedVersion: 0,
    })
    expect(wrapper.get('[data-test="humanize-skill-modal-error"]').text()).toBe('已被他人修改，请刷新后重试')
    expect(wrapper.find('[data-test="humanize-skill-modal-prompt"]').exists()).toBe(true)

    fetchMock.mockResolvedValueOnce(jsonResponse({
      success: true,
      data: { skill: skill({ promptContent: '改写后的规则正文', description: '改过的说明', version: 1 }) },
    }))
    await wrapper.get('[data-test="humanize-skill-modal-save"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="humanize-skill-modal-prompt"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('改过的说明')
  })

  test('启停 change 即整行 PUT；加载失败内联报错且可刷新重试', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(listBody()))
    fetchMock.mockResolvedValueOnce(jsonResponse({
      success: true,
      data: { skill: skill({ enabled: false, version: 1 }) },
    }))
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('[data-test="humanize-skill-toggle-shuorenhua"]').setValue(false)
    await flushPromises()

    const put = fetchMock.mock.calls[1]
    expect(put[0]).toBe('/api/admin/humanize-skills/11111111-1111-1111-1111-111111111111')
    expect(JSON.parse(put[1].body)).toMatchObject({ enabled: false, expectedVersion: 0 })
    expect(wrapper.get('[data-test="humanize-skill-row-shuorenhua"]').text()).toContain('已停用')

    // 加载失败：内联错误 + 刷新重试
    const failing = mount(HumanizeSkillsAdminPanel, { global: { stubs: { teleport: true } } })
    fetchMock.mockReset()
    fetchMock.mockResolvedValueOnce(jsonResponse({ success: false, error: '需要平台管理员权限' }, 403))
    await failing.get('[data-test="humanize-skills-refresh"]').trigger('click')
    await flushPromises()
    expect(failing.get('.error-msg').text()).toContain('需要平台管理员权限')

    fetchMock.mockResolvedValueOnce(jsonResponse(listBody()))
    await failing.get('[data-test="humanize-skills-refresh"]').trigger('click')
    await flushPromises()
    expect(failing.findAll('.skills-table tbody tr')).toHaveLength(3)
  })
})
