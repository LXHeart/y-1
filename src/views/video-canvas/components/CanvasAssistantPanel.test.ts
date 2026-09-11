// @vitest-environment happy-dom
import { describe, expect, test } from 'vitest'
import { mount } from '@vue/test-utils'
import CanvasAssistantPanel from './CanvasAssistantPanel.vue'
import CanvasPlanPreview from './CanvasPlanPreview.vue'
import type { CanvasPlanResult } from '../../../types/video-canvas'

/**
 * 任务书 #100 C100-18：AI 助手面板交互（TC-035/036/039 界面）。
 */

function plan(overrides: Partial<CanvasPlanResult> = {}): CanvasPlanResult {
  return {
    id: 'plan-1', status: 'ready', draftId: 'draft-1', storyboardId: 'sb-1',
    baseDraftVersion: 1, baseEditVersion: 1, baseCanvasRevision: 1,
    summary: '已生成', clarification: null,
    action: {
      kind: 'edit',
      actions: [{ kind: 'update-shot', patch: { shotId: 'shot-1', visual: '新画面' } }],
    },
    runId: 'run-1', errorCode: null, expiresAt: '2026-09-12T01:00:00Z', ...overrides,
  }
}

describe('#100 C100-18：CanvasAssistantPanel', () => {
  test('无选择禁用提交并给具体引导（TC-035 界面）', () => {
    const wrapper = mount(CanvasAssistantPanel, {
      props: { selectedNodeLabels: [], instruction: '', plan: null, submitting: false,
        applying: false, error: '' },
    })
    expect((wrapper.find('[data-test="canvas-assistant-submit"]').element as HTMLButtonElement).disabled)
      .toBe(true)
    expect(wrapper.find('[data-test="canvas-assistant-empty-selection"]').text())
      .toContain('空选择不会修改任何内容')
  })

  test('ready 计划显示范围/差异/应用入口；「计划已生成」≠「视频已生成」', () => {
    const wrapper = mount(CanvasAssistantPanel, {
      props: { selectedNodeLabels: ['镜头 1'], instruction: 'x', plan: plan(), submitting: false,
        applying: false, error: '' },
    })
    expect(wrapper.find('[data-test="canvas-assistant-scope"]').text()).toContain('1 个选中节点')
    expect(wrapper.find('[data-test="canvas-assistant-status-ready"]').text())
      .toContain('待你确认应用')
    expect(wrapper.find('[data-test="canvas-assistant-status-ready"]').text()).not.toContain('视频')
    expect(wrapper.find('[data-test="canvas-plan-diff-0"]').text()).toContain('画面')
    expect(wrapper.find('[data-test="canvas-assistant-apply"]').exists()).toBe(true)
  })

  test('clarify 呈现服务端固定引导；prepare-generation 明示须点现有按钮（TC-039 界面）', () => {
    const clarify = mount(CanvasAssistantPanel, {
      props: { selectedNodeLabels: [], instruction: '', plan: plan({
        status: 'clarify', clarification: '请先选择镜头', action: null, runId: null }),
      submitting: false, applying: false, error: '' },
    })
    expect(clarify.find('[data-test="canvas-assistant-clarify"]').text()).toContain('请先选择镜头')
    expect(clarify.find('[data-test="canvas-assistant-apply"]').exists()).toBe(false)

    const prepare = mount(CanvasAssistantPanel, {
      props: { selectedNodeLabels: ['镜头 1'], instruction: 'x', plan: plan({
        action: { kind: 'prepare-generation', mode: 'regenerate', shotId: 'shot-1' } }),
      submitting: false, applying: false, error: '' },
    })
    expect(prepare.find('[data-test="canvas-plan-prepare"]').text()).toContain('发起制作')
    expect(prepare.find('[data-test="canvas-plan-prepare"]').text()).toContain('按钮启动生成')
  })

  test('错误保留提问（TC-036 界面）；原键重试入口只在 preparing 时出现', () => {
    const failed = mount(CanvasAssistantPanel, {
      props: { selectedNodeLabels: ['镜头 1'], instruction: '保留的提问', plan: plan({
        status: 'failed', action: null, errorCode: 'CANVAS_AGENT_INVALID_PLAN' }),
      submitting: false, applying: false, error: '模型返回不合法计划' },
    })
    expect(failed.find('[data-test="canvas-assistant-error"]').text()).toContain('不合法计划')
    expect((failed.find('textarea').element as HTMLTextAreaElement).value).toBe('保留的提问')
    expect(failed.find('[data-test="canvas-assistant-retry"]').exists()).toBe(false)

    const preparing = mount(CanvasAssistantPanel, {
      props: { selectedNodeLabels: ['镜头 1'], instruction: 'x', plan: plan({ status: 'preparing' }),
      submitting: false, applying: false, error: '超时' },
    })
    expect(preparing.find('[data-test="canvas-assistant-retry"]').exists()).toBe(true)
  })
})

describe('#100 C100-18：CanvasPlanPreview', () => {
  test('variant 预览展示派生影响（当前方案不受影响）', () => {
    const wrapper = mount(CanvasPlanPreview, {
      props: { planStatus: 'ready',
        action: { kind: 'variant', title: '方案B', shotIds: ['shot-1', 'shot-2'] } },
    })
    expect(wrapper.find('[data-test="canvas-plan-variant"]').text()).toContain('方案B')
    expect(wrapper.find('[data-test="canvas-plan-variant"]').text()).toContain('当前方案不受影响')
  })
})
