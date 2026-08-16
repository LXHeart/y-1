// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import MerchantTaskForm from './MerchantTaskForm.vue'
import RecommenderTaskHall from './RecommenderTaskHall.vue'
import type { Task } from '../../../types/grassland'

/**
 * 任务书 #22 Stage B4：霸王餐押金前端——表单 XOR 交互、押金↔cents 映射、大厅徽标与余额软提示。
 */

const baseForm = {
  title: '霸王餐任务', description: '', platform: '', contentForm: '', maxSlots: 1,
  interactionTargetUrl: '', interactionActionType: 'like',
  bountyYuan: 0, freebieDepositYuan: 0, applicationDeadline: '', minRecommenderLevel: 1,
  autoAcceptMinLevel: null as number | null, productServiceInfo: '', mustInclude: '',
  forbiddenContent: '', publishStartAt: '', publishEndAt: '', metricRequirements: '',
  evidenceRequirements: '',
}

function mountForm(form: typeof baseForm) {
  return mount(MerchantTaskForm, {
    props: {
      form, editingDraft: null, revisingTask: null, stores: [], selectedStoreId: '',
      activeOrgId: 'org-1', hasOrganizationAccess: true, canPublishBounty: true, loading: false,
    },
  })
}

function bountyInput(wrapper: ReturnType<typeof mountForm>) {
  return wrapper.findAll('input').find((i) => i.element.placeholder === undefined
    && i.attributes('type') === 'number' && i.element.closest('label')?.textContent?.includes('赏金'))!
}

describe('MerchantTaskForm 霸王餐押金 XOR 交互（任务书 #22）', () => {
  test('押金 >0 时赏金输入禁用并显示押金模式提示', () => {
    const wrapper = mountForm({ ...baseForm, freebieDepositYuan: 100 })
    const labels = wrapper.findAll('label')
    const bountyLabel = labels.find((l) => l.text().includes('赏金'))!
    const depositLabel = labels.find((l) => l.text().includes('霸王餐押金'))!

    expect(bountyLabel.find('input').attributes('disabled')).toBeDefined()
    expect(depositLabel.find('input').attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).toContain('霸王餐押金模式')
    expect(wrapper.text()).toContain('达标全额返还')
  })

  test('赏金 >0 时押金输入禁用并显示赏金模式提示', () => {
    const wrapper = mountForm({ ...baseForm, bountyYuan: 50 })
    const labels = wrapper.findAll('label')
    const depositLabel = labels.find((l) => l.text().includes('霸王餐押金'))!

    expect(depositLabel.find('input').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('赏金模式')
  })

  test('两者都为 0 时互不禁用，无资金模式提示', () => {
    const wrapper = mountForm({ ...baseForm })
    const labels = wrapper.findAll('label')
    const depositLabel = labels.find((l) => l.text().includes('霸王餐押金'))!
    expect(depositLabel.find('input').attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).not.toContain('霸王餐押金模式')
    void bountyInput
  })

  test('押金输入变更发出 update:field 事件（元值，父组件负责换算 cents）', async () => {
    const wrapper = mountForm({ ...baseForm })
    const labels = wrapper.findAll('label')
    const depositLabel = labels.find((l) => l.text().includes('霸王餐押金'))!
    await depositLabel.find('input').setValue('66')
    const events = wrapper.emitted('update:field') ?? []
    expect(events.some((args) => args[0] === 'freebieDepositYuan' && args[1] === 66)).toBe(true)
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
      feedItems, feedHasMore: false, feedLoading: false,
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

  test('钱包余额不足时显示行内软提示；余额充足时不显示', () => {
    const insufficient = mountHall([freebieTask], 5000)   // ¥50 < ¥100 押金
    expect(insufficient.text()).toContain('押金超过钱包余额 ¥50.00')

    const enough = mountHall([freebieTask], 20000)        // ¥200 ≥ ¥100
    expect(enough.text()).not.toContain('押金超过钱包余额')
  })

  test('普通赏金任务保持既有赏金渲染，无霸王餐徽标', () => {
    const bountyTask: Task = { ...freebieTask, freebieDepositCents: null, bountyCents: 8800 }
    const wrapper = mountHall([bountyTask], null)
    expect(wrapper.text()).toContain('¥88.00')
    expect(wrapper.text()).not.toContain('霸王餐 ·')
    expect(wrapper.text()).not.toContain('需预付')
  })
})
