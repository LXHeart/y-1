// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import MerchantTaskForm from './MerchantTaskForm.vue'
import RecommenderTaskHall from './RecommenderTaskHall.vue'
import TaskDetailCard from './TaskDetailCard.vue'
import type { Task } from '../../../types/grassland'

/**
 * 任务书 #22 Stage B4：霸王餐押金前端——表单 XOR 交互、押金↔cents 映射、大厅徽标与余额软提示。
 */

const baseForm = {
  title: '霸王餐任务', description: '', platform: '', contentForm: '', maxSlots: 1,
  interactionTargetUrl: '', interactionActionType: 'like',
  // 任务书 #78 卡 I：资金字段表单态为原始字符串
  bountyYuan: '', freebieDepositYuan: '', paymentMode: 'commission' as 'commission' | 'freebie',
  applicationDeadline: '', minRecommenderLevel: 1,
  autoAcceptMinLevel: null as number | null, productServiceInfo: '', mustInclude: '',
  forbiddenContent: '', publishStartAt: '', publishEndAt: '', metricRequirements: '',
  evidenceRequirements: '',
}

function mountForm(form: typeof baseForm) {
  return mount(MerchantTaskForm, {
    props: {
      form, open: true, editingDraft: null, revisingTask: null, stores: [], selectedStoreId: '',
      activeOrgId: 'org-1', canPublishBounty: true, loading: false,
    },
    // 表单已弹窗化并 Teleport 到 body：不 stub 的话内容落在 wrapper 之外，find 全查不到。
    global: { stubs: { Teleport: true } },
  })
}

function bountyInput(wrapper: ReturnType<typeof mountForm>) {
  return wrapper.findAll('input').find((i) => i.element.placeholder === undefined
    && i.attributes('type') === 'number' && i.element.closest('label')?.textContent?.includes('赏金'))!
}

describe('MerchantTaskForm 付费方式三选一（PRD §2.2，推翻 #46 组合）', () => {
  test('佣金模式：显示赏金与阶梯开关，不显示押金输入', () => {
    const wrapper = mountForm({ ...baseForm })
    expect(wrapper.text()).toContain('赏金')
    expect(wrapper.text()).toContain('阶梯佣金')
    expect(wrapper.findAll('label').some((l) => l.text().includes('霸王餐押金'))).toBe(false)
    // 无资金时无模式提示
    expect(wrapper.text()).not.toContain('霸王餐押金模式')
    void bountyInput
  })

  test('霸王餐模式：显示押金输入并隐藏赏金/阶梯，提示达标返还', () => {
    const wrapper = mountForm({ ...baseForm, paymentMode: 'freebie', freebieDepositYuan: '100' })
    expect(wrapper.findAll('label').some((l) => l.text().includes('霸王餐押金'))).toBe(true)
    expect(wrapper.findAll('label').some((l) => l.text().includes('赏金'))).toBe(false)
    expect(wrapper.find('[aria-label="启用阶梯佣金"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('霸王餐押金模式')
    expect(wrapper.text()).toContain('达标（核实+商家确认）全额返还')
  })

  test('模式切换不再清资金字段（任务书 #78 卡 I）：切回值保留，payload 双保险在提交链路', async () => {
    const wrapper = mountForm({ ...baseForm, bountyYuan: '50' })
    const radios = wrapper.findAll('input[name="task-payment-mode"]')

    await radios[1].trigger('change')
    const events = wrapper.emitted('update:field') ?? []
    expect(events.some((args) => args[0] === 'paymentMode' && args[1] === 'freebie')).toBe(true)
    // 切到霸王餐：赏金不清零（原 #75 行为，#78 卡 I 推翻——只发 paymentMode 一个事件）
    expect(events.some((args) => args[0] === 'bountyYuan')).toBe(false)

    // 切回佣金：押金同样不清零（测试 props 静态、emit 不回流，切回腿用霸王餐表单另挂验证）
    const back = mountForm({ ...baseForm, paymentMode: 'freebie', freebieDepositYuan: '80' })
    await back.findAll('input[name="task-payment-mode"]')[0].trigger('change')
    const events2 = back.emitted('update:field') ?? []
    expect(events2.some((args) => args[0] === 'paymentMode' && args[1] === 'commission')).toBe(true)
    expect(events2.some((args) => args[0] === 'freebieDepositYuan')).toBe(false)
  })

  test('押金输入变更发出 update:field 事件（原始字符串透传，父组件提交时才换算 cents）', async () => {
    const wrapper = mountForm({ ...baseForm, paymentMode: 'freebie' })
    const depositLabel = wrapper.findAll('label').find((l) => l.text().includes('霸王餐押金'))!
    await depositLabel.find('input').setValue('66')
    const events = wrapper.emitted('update:field') ?? []
    expect(events.some((args) => args[0] === 'freebieDepositYuan' && args[1] === '66')).toBe(true)
  })

  test('无资金交易权限：赏金/押金灰死 + 解释条 + 去升级入口（任务书 #78 卡 I）', async () => {
    const wrapper = mount(MerchantTaskForm, {
      props: {
        form: { ...baseForm }, open: true, editingDraft: null, revisingTask: null, stores: [],
        selectedStoreId: '', activeOrgId: 'org-1', canPublishBounty: false, loading: false,
      },
      global: { stubs: { Teleport: true } },
    })
    const bounty = wrapper.findAll('input[name="task-bounty"]')[0]
    expect(bounty.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('发布赏金/押金任务需先升级到资金交易权限')
    expect(wrapper.find('[data-testid="task-form-go-upgrade"]').exists()).toBe(true)

    // 套餐推广模式不涉赏金/押金：解释条不渲染
    const commerceForm = { ...baseForm, paymentMode: 'commerce' as const }
    const commerceWrapper = mount(MerchantTaskForm, {
      props: {
        form: commerceForm, open: true, editingDraft: null, revisingTask: null, stores: [],
        selectedStoreId: '', activeOrgId: 'org-1', canPublishBounty: false, loading: false,
      },
      global: { stubs: { Teleport: true } },
    })
    expect(commerceWrapper.text()).not.toContain('发布赏金/押金任务需先升级到资金交易权限')
  })
})

const freebieTask: Task = {
  id: 'task-1', ownerAccountId: 'owner-1', organizationId: 'org-1', title: '霸王餐探店',
  description: null, status: 'published', contentForm: null, platform: null, maxSlots: null,
  bountyCents: null, freebieDepositCents: 10000, minRecommenderLevel: 1, createdAt: null,
  version: 1, applicationDeadline: null, publishedAt: null, cancelledAt: null,
  requirements: { mustInclude: [], forbiddenContent: [], metricRequirements: [], evidenceRequirements: [] },
  autoAcceptMinLevel: null,
}

function mountHall(feedItems: Task[], walletBalanceCents: number | null) {
  return mount(RecommenderTaskHall, {
    props: {
      feedItems, feedHasMore: false, feedLoading: false, feedPage: 0, feedLimit: 10,
      feedFilters: {
        platform: '', contentForm: '', minBountyYuan: 0, maxDistanceKm: 0,
        latitude: null, longitude: null,
      },
      applyNote: '', selectedTaskId: '', loading: false, locating: false, walletBalanceCents,
    },
  })
}

describe('RecommenderTaskHall 霸王餐徽标与余额软提示（任务书 #22）', () => {
  test('押金任务显示「需预付 ¥100 · 达标全额返还」徽标，不显示赏金', () => {
    const wrapper = mountHall([freebieTask], null)
    expect(wrapper.text()).toContain('霸王餐 · 需预付 ¥100.00 · 达标全额返还')
    expect(wrapper.text()).not.toContain('class="gl-tag-money"')
  })

  test('钱包余额不足时详情显示软提示；余额充足时不显示（#77 卡 A 起详情块随弹窗收进 TaskDetailModal）', () => {
    // 软提示逻辑在 TaskDetailCard（详情块）——直接挂组件断言，弹窗（TaskDetailModal）只是宿主
    const insufficient = mount(TaskDetailCard, {
      props: { task: freebieTask, myApplication: null, loading: false, walletBalanceCents: 5000 },
    })   // ¥50 < ¥100 押金
    expect(insufficient.get('[data-testid="task-detail-card"]').text()).toContain('押金超过钱包余额 ¥50.00')

    const enough = mount(TaskDetailCard, {
      props: { task: freebieTask, myApplication: null, loading: false, walletBalanceCents: 20000 },
    })   // ¥200 ≥ ¥100
    expect(enough.get('[data-testid="task-detail-card"]').text()).not.toContain('押金超过钱包余额')
  })

  test('普通赏金任务保持既有赏金渲染，无霸王餐徽标', () => {
    const bountyTask: Task = { ...freebieTask, freebieDepositCents: null, bountyCents: 8800 }
    const wrapper = mountHall([bountyTask], null)
    expect(wrapper.text()).toContain('¥88.00')
    expect(wrapper.text()).not.toContain('霸王餐 ·')
    expect(wrapper.text()).not.toContain('需预付')
  })
})
