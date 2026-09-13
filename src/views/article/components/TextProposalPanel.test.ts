// @vitest-environment happy-dom
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test } from 'vitest'
import TextProposalPanel from './TextProposalPanel.vue'
import type { TextProposal } from '../../../types/creation-studio'

/**
 * 任务书 #101 C101-04：建议面板——无隐式应用、字段选择、过期与错误行为。
 */
enableAutoUnmount(afterEach)

function proposal(overrides: Partial<TextProposal> = {}): TextProposal {
  return {
    id: '11111111-1111-4111-8111-111111111111',
    draftId: 'draft-1',
    requestId: 'req-1',
    action: 'adapt-body',
    status: 'ready',
    baseDraftVersion: 2,
    baseContentHash: 'a'.repeat(64),
    source: { id: '22222222-2222-4222-8222-222222222222', contentHash: 'b'.repeat(64) },
    result: { title: '新标题', body: '改编后的正文', summary: null, changes: ['保留事实，调整结构'], sourceBlockIds: [] },
    runId: '33333333-3333-4333-8333-333333333333',
    safety: null,
    appliedDraftVersion: null,
    error: null,
    createdAt: '2026-09-13T00:00:00Z',
    expiresAt: new Date(Date.now() + 30 * 60_000).toISOString(),
    ...overrides,
  }
}

function mountPanel(props: Record<string, unknown> = {}) {
  return mount(TextProposalPanel, {
    props: {
      action: 'adapt-body', visible: true, preparing: false, applying: false, error: '',
      current: proposal(), ...props,
    },
  })
}

describe('TextProposalPanel', () => {
  test('ready 建议展示变更说明与候选正文；默认全选可应用字段', () => {
    const wrapper = mountPanel()
    expect(wrapper.get('[data-testid="proposal-result"]').text()).toContain('保留事实，调整结构')
    expect((wrapper.get('[data-testid="proposal-field-body"]').element as HTMLInputElement).checked).toBe(true)
    expect((wrapper.get('[data-testid="proposal-field-title"]').element as HTMLInputElement).checked).toBe(true)
    expect(wrapper.text()).toContain('候选正文')
    expect(wrapper.text()).toContain('建议基于草稿 v2')
  })

  test('未勾选任何字段时应用禁用；取消预览 emit dismiss（原稿不动）', async () => {
    const wrapper = mountPanel()
    await wrapper.get('[data-testid="proposal-field-body"]').setValue(false)
    await wrapper.get('[data-testid="proposal-field-title"]').setValue(false)
    expect((wrapper.get('[data-testid="proposal-apply"]').element as HTMLButtonElement).disabled).toBe(true)
    await wrapper.get('[data-testid="proposal-dismiss"]').trigger('click')
    expect(wrapper.emitted('dismiss')).toHaveLength(1)
  })

  test('应用 emit 建议ID与所选字段；applying 中禁用', async () => {
    const wrapper = mountPanel()
    await wrapper.get('[data-testid="proposal-field-title"]').setValue(false)
    await wrapper.get('[data-testid="proposal-apply"]').trigger('click')
    expect(wrapper.emitted('apply')).toEqual([[
      '11111111-1111-4111-8111-111111111111', ['body'],
    ]])
    const busy = mountPanel({ applying: true, current: proposal() })
    expect((busy.get('[data-testid="proposal-apply"]').element as HTMLButtonElement).disabled).toBe(true)
    expect(busy.get('[data-testid="proposal-apply"]').text()).toContain('应用中')
  })

  test('过期建议禁用应用并提示重新生成（TC101-019 前端侧）', () => {
    const wrapper = mountPanel({
      current: proposal({ expiresAt: new Date(Date.now() - 1000).toISOString() }),
    })
    expect((wrapper.get('[data-testid="proposal-apply"]').element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.text()).toContain('建议已过期')
  })

  test('unknown 状态只显示待核实文案，无重发入口；failed 显示错误', () => {
    const unknown = mountPanel({ current: proposal({ status: 'unknown', result: null }) })
    expect(unknown.text()).toContain('结果待核实')
    expect(unknown.find('[data-testid="proposal-apply"]').exists()).toBe(false)
    const failed = mountPanel({
      current: proposal({ status: 'failed', result: null, error: { code: 'STUDIO_INVALID_PLAN', message: '模型返回不合法建议' } }),
    })
    expect(failed.get('[data-testid="proposal-error"], .proposal-error').text()).toContain('模型返回不合法建议')
  })

  test('visible=false 不渲染面板', () => {
    const wrapper = mountPanel({ visible: false })
    expect(wrapper.find('[data-testid="text-proposal-panel"]').exists()).toBe(false)
  })
})
