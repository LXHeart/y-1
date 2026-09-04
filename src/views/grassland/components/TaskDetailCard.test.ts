// @vitest-environment happy-dom
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test } from 'vitest'
import TaskDetailCard from './TaskDetailCard.vue'
import type { MyApplication, Task } from '../../../types/grassland'

enableAutoUnmount(afterEach)

function makeTask(overrides: Partial<Task> = {}): Task {
  return {
    id: 'task-1',
    title: '招牌奶茶种草',
    description: '突出门店招牌与新品',
    status: 'published',
    contentForm: 'image',
    platform: 'xiaohongshu',
    maxSlots: 3,
    bountyCents: 5000,
    minRecommenderLevel: 1,
    createdAt: null,
    version: 1,
    applicationDeadline: null,
    requirements: {
      mustInclude: ['门店定位'],
      forbiddenContent: ['竞品对比'],
      metricRequirements: [],
      evidenceRequirements: ['发布截图'],
    },
    ...overrides,
  } as Task
}

function makeApplication(status: MyApplication['applicationStatus']): MyApplication {
  return { applicationId: 'app-1', taskId: 'task-1', applicationStatus: status, taskTitle: null, taskStatus: null, bountyCents: 0, appliedAt: null, settledAt: null }
}

function mountCard(props: { task?: Task; myApplication?: MyApplication | null; loading?: boolean; walletBalanceCents?: number | null } = {}) {
  return mount(TaskDetailCard, {
    props: {
      task: props.task ?? makeTask(),
      myApplication: props.myApplication ?? null,
      loading: props.loading ?? false,
      walletBalanceCents: props.walletBalanceCents ?? null,
    },
  })
}

describe('TaskDetailCard（2026-09-04 反馈 1/2/4）', () => {
  test('任务本体信息齐备：标题/描述/元信息/要求块', () => {
    const wrapper = mountCard()
    const text = wrapper.get('[data-testid="task-detail-card"]').text()
    expect(text).toContain('招牌奶茶种草')
    expect(text).toContain('突出门店招牌与新品')
    expect(text).toContain('内容须包含')
    expect(text).toContain('门店定位')
    expect(text).toContain('禁止出现')
    expect(text).toContain('凭证要求')
    expect(text).toContain('未报名')
  })

  test('报名状态标识：pending → 已报名·待处理且报名禁用', async () => {
    const wrapper = mountCard({ myApplication: makeApplication('pending') })
    expect(wrapper.text()).toContain('已报名 · 待商家处理')
    const applyButton = wrapper.findAll('button').find((b) => b.text() === '已报名')!
    expect(applyButton.attributes('disabled')).toBeDefined()
  })

  test('报名状态标识：rejected → 曾报名·未通过，可再报名', async () => {
    const wrapper = mountCard({ myApplication: makeApplication('rejected') })
    expect(wrapper.text()).toContain('曾报名 · 未通过')
    const applyButton = wrapper.findAll('button').find((b) => b.text() === '报名')!
    expect(applyButton.attributes('disabled')).toBeUndefined()
  })

  test('报名截止后禁用并显示「报名已截止」', () => {
    const wrapper = mountCard({ task: makeTask({ applicationDeadline: '2000-01-01T00:00:00Z' }) })
    const applyButton = wrapper.findAll('button').find((b) => b.text() === '报名已截止')!
    expect(applyButton.attributes('disabled')).toBeDefined()
  })

  test('举报/报名/收起三事件上抛（举报自大厅操作栏迁入，反馈 2）', async () => {
    const wrapper = mountCard()
    const buttons = wrapper.findAll('button')
    await buttons.find((b) => b.text() === '举报该任务')!.trigger('click')
    expect(wrapper.emitted('report')).toHaveLength(1)
    expect(wrapper.emitted('report')![0][0]).toMatchObject({ id: 'task-1' })

    await wrapper.findAll('button').find((b) => b.text() === '报名')!.trigger('click')
    expect(wrapper.emitted('apply')).toEqual([['task-1']])

    await wrapper.findAll('button').find((b) => b.text() === '收起')!.trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  test('霸王餐押金超过余额时给出软提示（任务书 #22 行为随迁）', () => {
    const wrapper = mountCard({
      task: makeTask({ bountyCents: null, freebieDepositCents: 8000 }),
      walletBalanceCents: 3000,
    })
    expect(wrapper.text()).toContain('押金超过钱包余额')
  })
})
