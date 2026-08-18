// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import CommissionLadderSummary from './CommissionLadderSummary.vue'
import type { CommissionLadder } from '../../../types/grassland'

/** 任务书 #25 Stage D：任务展示共享摘要——标签、metricKey、佣金范围、<details> 档位明细与不累加说明。 */

/** 两档逆序：摘要必须按阈值升序展示（后端快照可能未排序，展示端自行排序）。 */
function unsortedLadder(): CommissionLadder {
  return {
    policyVersion: 'ladder-v1',
    metricKey: 'douyin.play_count',
    tiers: [
      { threshold: 50000, payoutCents: 10000 },
      { threshold: 10000, payoutCents: 5000 },
    ],
  }
}

function mountSummary(props: Partial<{ ladder: CommissionLadder; compact: boolean }> = {}) {
  return mount(CommissionLadderSummary, {
    props: { ladder: unsortedLadder(), ...props },
  })
}

describe('CommissionLadderSummary 共享摘要（任务书 #25）', () => {
  test('展示阶梯佣金标签、指标标识与最低-最高档佣金范围', () => {
    const wrapper = mountSummary()
    expect(wrapper.text()).toContain('阶梯佣金')
    expect(wrapper.text()).toContain('douyin.play_count')
    expect(wrapper.text()).toContain('¥50.00–¥100.00')
  })

  test('<details> 展开档位明细「阈值 → 固定佣金」并按阈值升序', () => {
    const wrapper = mountSummary()
    expect(wrapper.find('details').exists()).toBe(true)
    expect(wrapper.text()).toContain('10,000 → ¥50.00')
    expect(wrapper.text()).toContain('50,000 → ¥100.00')
    // 升序：低阈值一行出现在高阈值之前
    const detailText = wrapper.get('details').text()
    expect(detailText.indexOf('10,000 → ¥50.00')).toBeLessThan(detailText.indexOf('50,000 → ¥100.00'))
  })

  test('说明固定佣金不累加，避免误解为档位累加', () => {
    const wrapper = mountSummary()
    expect(wrapper.text()).toContain('固定佣金，不累加')
  })

  test('不展示内部 policyVersion', () => {
    const wrapper = mountSummary()
    expect(wrapper.text()).not.toContain('ladder-v1')
  })

  test('compact 模式语义相同（同标签/范围/明细）且挂 compact 修饰类', () => {
    const normal = mountSummary()
    const compact = mountSummary({ compact: true })
    for (const wrapper of [normal, compact]) {
      expect(wrapper.text()).toContain('阶梯佣金')
      expect(wrapper.text()).toContain('douyin.play_count')
      expect(wrapper.text()).toContain('¥50.00–¥100.00')
      expect(wrapper.text()).toContain('10,000 → ¥50.00')
      expect(wrapper.text()).toContain('50,000 → ¥100.00')
      expect(wrapper.text()).toContain('固定佣金，不累加')
    }
    expect(normal.classes()).not.toContain('gl-ladder-compact')
    expect(compact.classes()).toContain('gl-ladder-compact')
  })
})
