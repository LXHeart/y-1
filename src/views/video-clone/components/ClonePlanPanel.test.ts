// @vitest-environment happy-dom
// ClonePlanPanel.test.ts — C107-21 (TC107-21-01 面板状态投影)：加载/空/缺口
// 状态如实展示；WAITING_INPUT 时生成按钮禁用。
import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import ClonePlanPanel from './ClonePlanPanel.vue'
import type { HypitClonePlan } from '../../../types/hypit'

const readyPlan: HypitClonePlan = {
  planId: 'plan-1',
  status: 'READY',
  steps: [{ index: 0, capability: 'clone.badge', boundSystemId: 'sys-1', anchorSeconds: 0, description: '复刻徽标' }],
  materialGaps: [],
}

const waitingPlan: HypitClonePlan = {
  ...readyPlan,
  status: 'WAITING_INPUT',
  materialGaps: [{ kind: 'image', description: '缺少产品图', suggestedSource: '素材库' }],
}

describe('ClonePlanPanel 状态投影', () => {
  test('loading 态显示加载提示', () => {
    const wrapper = mount(ClonePlanPanel, { props: { plan: null, planLoading: true, planError: null, generating: false } })
    expect(wrapper.find('[data-testid="clone-plan-loading"]').exists()).toBe(true)
  })

  test('error 态显示原始错误且保留空态行动指引（C107F2-37 缺陷 R 回归）', () => {
    // 独占式 error 分支曾把 plan=null 的面板锁死在早期 404——空态（先完成参考分析）
    // 是可行动真相，必须与错误并存，否则空/等待判据永久不可见、面板无法恢复。
    const wrapper = mount(ClonePlanPanel, { props: { plan: null, planLoading: false, planError: 'hypit_disabled: 引擎未启用', generating: false } })
    const error = wrapper.find('[data-testid="clone-plan-error"]')
    expect(error.text()).toContain('引擎未启用')
    expect(wrapper.find('[data-testid="clone-plan-empty"]').exists()).toBe(true)
  })

  test('空方案给创建入口', () => {
    const wrapper = mount(ClonePlanPanel, { props: { plan: null, planLoading: false, planError: null, generating: false } })
    expect(wrapper.find('[data-testid="clone-plan-empty"]').exists()).toBe(true)
  })

  test('READY 方案可生成；WAITING_INPUT 禁用并显示缺口', () => {
    const ready = mount(ClonePlanPanel, { props: { plan: readyPlan, planLoading: false, planError: null, generating: false } })
    expect(ready.find('[data-testid="clone-generate"]').attributes('disabled')).toBeUndefined()
    const waiting = mount(ClonePlanPanel, { props: { plan: waitingPlan, planLoading: false, planError: null, generating: false } })
    expect(waiting.find('[data-testid="clone-generate"]').attributes('disabled')).toBeDefined()
    expect(waiting.find('[data-testid="clone-plan-gaps"]').text()).toContain('缺少产品图')
  })
})

// ── C107F3-10（§8 UI-01～04 / TC-F3-10-04）：再生成状态投影与键盘可达 ──────────
describe('ClonePlanPanel 再生成状态（TC-F3-10-04）', () => {
  test('TC-F3-10-04 UI-01 处理中：按钮禁用、文案「重新生成中…」、aria-busy，保留旧方案且不展示成功', () => {
    const wrapper = mount(ClonePlanPanel, {
      props: { plan: readyPlan, planLoading: false, planError: null, generating: false, regenerating: true, regenerateStatus: 'running', regenerateError: null },
      attachTo: document.body,
    })
    const button = wrapper.find('[data-testid="clone-plan-regenerate"]')
    expect(button.attributes('disabled')).toBeDefined()
    expect(button.text()).toBe('重新生成中…')
    expect(wrapper.find('[data-testid="clone-plan-panel"]').attributes('aria-busy')).toBe('true')
    // 202 不展示成功：处理中不出现「方案已更新」，旧方案步骤原样保留。
    expect(wrapper.find('[data-testid="clone-plan-updated"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('复刻徽标')
    wrapper.unmount()
  })

  test('TC-F3-10-04 UI-02 终态：succeeded 显示「方案已更新」；failed 保留旧方案并给可操作原因', () => {
    const updated = mount(ClonePlanPanel, {
      props: { plan: readyPlan, planLoading: false, planError: null, generating: false, regenerateStatus: 'succeeded', regenerateError: null },
    })
    const hint = updated.find('[data-testid="clone-plan-updated"]')
    expect(hint.attributes('role')).toBe('status')
    expect(hint.text()).toBe('方案已更新')

    const failed = mount(ClonePlanPanel, {
      props: { plan: readyPlan, planLoading: false, planError: null, generating: false, regenerateStatus: 'failed', regenerateError: '重新生成失败：引擎暂不可用' },
    })
    // 失败保留旧结果（不清面板）+ 可操作原因 role=alert。
    expect(failed.text()).toContain('复刻徽标')
    const alert = failed.find('[data-testid="clone-plan-regenerate-error"]')
    expect(alert.attributes('role')).toBe('alert')
    expect(alert.text()).toBe('工程已更新，请刷新后重试')
  })

  test('TC-F3-10-04 UI-03 前置失败三句映射：无分析 / 素材变化 / 版本冲突', () => {
    const mountWith = (message: string) => mount(ClonePlanPanel, {
      props: { plan: readyPlan, planLoading: false, planError: null, generating: false, regenerateStatus: 'failed', regenerateError: message },
    })
    expect(mountWith('当前工程没有可信的完整参考分析，请先完成分析').find('[data-testid="clone-plan-regenerate-error"]').text())
      .toBe('请先完成参考视频分析')
    // 真实服务端消息（HypitAuthorContextService）：尾缀「请重新分析」含「分析」二字，
    // 映射必须先判「素材」再判「分析」，否则误吞成第一句（截图自查暴露过该缺陷）。
    expect(mountWith('参考素材已变化（sha 不匹配或非 ready），请重新分析').find('[data-testid="clone-plan-regenerate-error"]').text())
      .toBe('参考素材已变化，请重新分析')
    expect(mountWith('工程已更新（baseRevision=2 ≠ head=3），请刷新后重试').find('[data-testid="clone-plan-regenerate-error"]').text())
      .toBe('工程已更新，请刷新后重试')
  })

  test('TC-F3-10-04 键盘与焦点：重新生成按钮可 Tab 聚焦、Enter 可触发；处理中不触发', async () => {
    const wrapper = mount(ClonePlanPanel, {
      props: { plan: readyPlan, planLoading: false, planError: null, generating: false },
      attachTo: document.body,
    })
    const button = wrapper.find('[data-testid="clone-plan-regenerate"]')
    ;(button.element as HTMLElement).focus()
    expect(document.activeElement).toBe(button.element) // Tab 可达（原生 button，无负 tabindex）

    button.trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('regenerate')).toHaveLength(1) // 聚焦后 Enter/点击触发再生成事件

    // 处理中禁用：点击与 Enter 均不再触发（UI-01 重复提交保护）。
    await wrapper.setProps({ regenerating: true, regenerateStatus: 'running' })
    await button.trigger('click')
    await button.trigger('keydown.enter')
    expect(wrapper.emitted('regenerate')).toHaveLength(1)
    expect(document.activeElement).toBe(button.element)
    wrapper.unmount()
  })

  test('TC-F3-10-04 空/加载态也有再生成入口（面板常驻，不因空态锁死）', () => {
    const empty = mount(ClonePlanPanel, { props: { plan: null, planLoading: false, planError: null, generating: false } })
    expect(empty.find('[data-testid="clone-plan-regenerate"]').exists()).toBe(true)
    expect(empty.find('[data-testid="clone-plan-empty"]').exists()).toBe(true)
  })
})
