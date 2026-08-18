// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import MerchantTaskForm from './MerchantTaskForm.vue'
import { emptyCommissionLadderForm } from './commission-ladder'
import type { CommissionLadderFormData } from './commission-ladder'

/** 任务书 #25 Stage B：商家阶梯佣金编辑器——开关整值事件、档位增删、20 档封顶、与霸王餐押金互斥。 */

const baseForm = {
  title: '阶梯任务', description: '', platform: '', contentForm: '', maxSlots: 1,
  interactionTargetUrl: '', interactionActionType: 'like',
  bountyYuan: 100, freebieDepositYuan: 0, applicationDeadline: '', minRecommenderLevel: 1,
  autoAcceptMinLevel: null as number | null, productServiceInfo: '', mustInclude: '',
  forbiddenContent: '', publishStartAt: '', publishEndAt: '', metricRequirements: '',
  evidenceRequirements: '',
  commissionLadder: emptyCommissionLadderForm(),
}

function mountForm(form: Partial<typeof baseForm> = {}) {
  return mount(MerchantTaskForm, {
    props: {
      form: { ...baseForm, ...form },
      editingDraft: null, revisingTask: null, stores: [], selectedStoreId: '',
      activeOrgId: 'org-1', hasOrganizationAccess: true, canPublishBounty: true, loading: false,
    },
  })
}

function enabledLadderForm(overrides: Partial<CommissionLadderFormData> = {}): CommissionLadderFormData {
  return {
    enabled: true,
    policyVersion: 'ladder-v1',
    metricKey: 'douyin.play_count',
    tiers: [{ threshold: 0, payoutYuan: 50 }],
    ...overrides,
  }
}

/** tsconfig lib=ES2020 无 Array.prototype.at，用下标取最近一次整值事件。 */
function lastLadderEvent(wrapper: ReturnType<typeof mountForm>): unknown {
  const events = wrapper.emitted('update:commission-ladder') ?? []
  return events[events.length - 1]?.[0]
}

describe('MerchantTaskForm 阶梯佣金编辑器（任务书 #25）', () => {
  test('未启用时渲染「阶梯佣金」开关，不展示内部 policyVersion；开启发出整值事件', async () => {
    const wrapper = mountForm()
    expect(wrapper.text()).toContain('阶梯佣金')
    expect(wrapper.text()).not.toContain('ladder-v1')
    expect(wrapper.find('[aria-label="阶梯佣金指标标识"]').exists()).toBe(false)

    await wrapper.get('[aria-label="启用阶梯佣金"]').setValue(true)
    expect(wrapper.emitted('update:commission-ladder')?.[0]?.[0]).toEqual(
      expect.objectContaining({ enabled: true }),
    )
  })

  test('启用后展示指标输入与档位输入；阈值变更发出整值 update:commission-ladder', async () => {
    const wrapper = mountForm({ commissionLadder: enabledLadderForm() })
    expect(wrapper.text()).not.toContain('ladder-v1')

    await wrapper.get('[aria-label="第 1 档阈值"]').setValue('10000')
    expect(lastLadderEvent(wrapper)).toEqual({
      enabled: true,
      policyVersion: 'ladder-v1',
      metricKey: 'douyin.play_count',
      tiers: [{ threshold: 10000, payoutYuan: 50 }],
    })

    await wrapper.get('[aria-label="阶梯佣金指标标识"]').setValue('douyin.like_count')
    expect(lastLadderEvent(wrapper)).toEqual(
      expect.objectContaining({ metricKey: 'douyin.like_count' }),
    )
  })

  test('展示「不累加 / 足额预留」业务说明', () => {
    const wrapper = mountForm({ commissionLadder: enabledLadderForm() })
    expect(wrapper.text()).toContain('达到最高档只发该档固定佣金、不累加')
    expect(wrapper.text()).toContain('最高档佣金由任务赏金足额预留')
  })

  test('添加档位基于末档递增阈值、沿用末档金额（整值事件）', async () => {
    const wrapper = mountForm({ commissionLadder: enabledLadderForm({
      tiers: [{ threshold: 100, payoutYuan: 5 }],
    }) })

    await wrapper.get('[aria-label="添加档位"]').trigger('click')
    expect(lastLadderEvent(wrapper)).toEqual(
      expect.objectContaining({ tiers: [{ threshold: 100, payoutYuan: 5 }, { threshold: 101, payoutYuan: 5 }] }),
    )
  })

  test('删除档位发出过滤后的整值事件', async () => {
    const wrapper = mountForm({ commissionLadder: enabledLadderForm({
      tiers: [{ threshold: 100, payoutYuan: 5 }, { threshold: 200, payoutYuan: 8 }],
    }) })

    await wrapper.get('[aria-label="删除第 1 档"]').trigger('click')
    expect(lastLadderEvent(wrapper)).toEqual(
      expect.objectContaining({ tiers: [{ threshold: 200, payoutYuan: 8 }] }),
    )
  })

  test('仅一档时删除按钮禁用且不发出事件', async () => {
    const wrapper = mountForm({ commissionLadder: enabledLadderForm() })
    const remove = wrapper.get('[aria-label="删除第 1 档"]')
    expect(remove.attributes('disabled')).toBeDefined()

    await remove.trigger('click')
    expect(wrapper.emitted('update:commission-ladder')).toBeUndefined()
  })

  test('19 档可添加至 20 档；20 档时添加按钮消失', async () => {
    const nineteen = Array.from({ length: 19 }, (_, i) => ({ threshold: (i + 1) * 100, payoutYuan: i + 1 }))
    const wrapper = mountForm({ commissionLadder: enabledLadderForm({ tiers: nineteen }) })
    expect(wrapper.find('[aria-label="添加档位"]').exists()).toBe(true)

    await wrapper.get('[aria-label="添加档位"]').trigger('click')
    const emitted = lastLadderEvent(wrapper) as CommissionLadderFormData
    expect(emitted.tiers).toHaveLength(20)

    const capped = mountForm({ commissionLadder: enabledLadderForm({
      tiers: [...nineteen, { threshold: 2000, payoutYuan: 20 }],
    }) })
    expect(capped.find('[aria-label="添加档位"]').exists()).toBe(false)
  })

  test('霸王餐押金 >0 禁用阶梯开关；阶梯启用禁用霸王餐押金输入', () => {
    const freebieWrapper = mountForm({ bountyYuan: 0, freebieDepositYuan: 66 })
    expect(freebieWrapper.get('[aria-label="启用阶梯佣金"]').attributes('disabled')).toBeDefined()

    const ladderWrapper = mountForm({ bountyYuan: 0, commissionLadder: enabledLadderForm() })
    const depositLabel = ladderWrapper.findAll('label').find((l) => l.text().includes('霸王餐押金'))!
    expect(depositLabel.find('input').attributes('disabled')).toBeDefined()
  })

  test('两者都未启用时互不禁用（回归 #22 XOR 保持）', () => {
    const wrapper = mountForm({ bountyYuan: 0 })
    expect(wrapper.get('[aria-label="启用阶梯佣金"]').attributes('disabled')).toBeUndefined()
    const depositLabel = wrapper.findAll('label').find((l) => l.text().includes('霸王餐押金'))!
    expect(depositLabel.find('input').attributes('disabled')).toBeUndefined()
  })
})
