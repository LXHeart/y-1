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

  test('error 态显示原始错误且无空工程假象', () => {
    const wrapper = mount(ClonePlanPanel, { props: { plan: null, planLoading: false, planError: 'hypit_disabled: 引擎未启用', generating: false } })
    const error = wrapper.find('[data-testid="clone-plan-error"]')
    expect(error.text()).toContain('引擎未启用')
    expect(wrapper.find('[data-testid="clone-plan-empty"]').exists()).toBe(false)
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
