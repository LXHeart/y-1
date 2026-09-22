// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import EngagementExitActions from './EngagementExitActions.vue'
import { useAuthStore } from '../../../stores/auth'
import type { ApplicationSettlement, EngagementExitRequest } from '../../../types/grassland'

/**
 * 任务书 #97 C97-03（TC97-014 前端侧）：协商退出动作区——服务端动作契约驱动三态
 * （发起入口 / 待对方回应+撤回 / 待退出确认+预演金额弹窗），资金数字只读服务端 settlementPreview。
 */
vi.mock('../../../composables/useGrassland', () => ({ useGrassland: () => ({}) }))

function basePending(initiatedRole: 'recommender' | 'merchant'): EngagementExitRequest {
  return {
    id: 'exit-1', applicationId: 'app-1', taskId: 'task-1', initiatedRole,
    reason: '档期冲突无法继续履约', status: 'pending',
    respondDeadlineAt: '2026-09-11T00:00:00Z', createdAt: '2026-09-08T00:00:00Z',
    settlementPreview: { scriptCents: 0, deliverableCents: 30000, publishedCents: 0, totalCents: 30000, milestoneIds: ['m1'] },
  }
}

function settlement(group: string): ApplicationSettlement {
  return {
    applicationId: 'app-1', taskId: 'task-1', confirmedAt: null, settlementEligibleAt: null,
    settlementStatus: 'not_confirmed', holdReason: null, allowedActions: [], nextActionGroup: group,
    nextActionLabel: '待退出确认', blockedReason: null, nextActionDueAt: '2026-09-11T00:00:00Z',
  }
}

function makeClient() {
  return {
    requestEngagementExit: vi.fn(async () => ({ exitRequestId: 'exit-2', status: 'pending', respondDeadlineAt: 'x' })),
    listEngagementExitRequests: vi.fn(async () => [basePending('recommender')]),
    respondEngagementExitRequest: vi.fn(async () => basePending('recommender')),
    cancelEngagementExitRequest: vi.fn(async () => basePending('recommender')),
    // 任务书 #103 C103-04：退出终态资金态查询。
    fetchEngagementExitFunds: vi.fn(async (): Promise<import('../../../types/grassland/task').EngagementExitFunds | null> => ({
      operationId: 'op-1', kind: 'no_fault', state: 'retry_wait',
      amounts: { deposit_refundCents: 10000, bounty_captureCents: 0, bounty_releaseCents: 50000 },
      blockedReason: 'funds_pending', updatedAt: '2026-09-15T00:00:00Z',
    })),
    error: { value: '' },
  }
}

beforeEach(() => {
  // #106 D05：资金轮询发起门闸要求有效非匿名账号票据——组件单测统一登录态。
  setActivePinia(createPinia())
  useAuthStore().currentUser = { id: 'user-exit-funds', email: 'e@qa.invalid', displayName: '商家', role: 'user' }
})

function mountActions(client: ReturnType<typeof makeClient>, group: string, status = 'accepted') {
  return mount(EngagementExitActions, {
    props: {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      client: client as any, taskId: 'task-1', applicationId: 'app-1',
      settlement: settlement(group), applicationStatus: status,
    },
    global: { stubs: { teleport: true } },
  })
}

afterEach(() => vi.clearAllMocks())

describe('EngagementExitActions（任务书 #97 C97-03）', () => {
  it('#106 复核：资金刷新失败必须展示错误并保留已有快照', async () => {
    const client = makeClient()
    const wrapper = mountActions(client, 'none', 'withdrawn')
    await flushPromises()
    client.error.value = 'upstream unavailable'
    client.fetchEngagementExitFunds.mockResolvedValue(null)
    await wrapper.get('[data-action="refresh-exit-funds"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="exit-funds-result"]').text()).toContain('资金处理中')
    expect(wrapper.get('[data-testid="exit-funds-error"]').text()).toContain('upstream unavailable')
    wrapper.unmount()
  })
  it('对方视角（exit_pending_confirm）：展示待确认动作，确认弹窗只读服务端预演金额', async () => {
    const client = makeClient()
    client.listEngagementExitRequests.mockResolvedValue([basePending('merchant')])
    const wrapper = mountActions(client, 'exit_pending_confirm')
    await flushPromises()

    expect(wrapper.find('[data-action="confirm-exit-request"]').exists()).toBe(true)
    expect(wrapper.find('[data-action="cancel-exit-request"]').exists()).toBe(false)

    await wrapper.find('[data-action="confirm-exit-request"]').trigger('click')
    // teleport stub 渲染原地：弹窗内容在 wrapper 内
    const dialogText = wrapper.text()
    expect(dialogText).toContain('商家')
    expect(dialogText).toContain('档期冲突无法继续履约')
    // 预演金额来自服务端 settlementPreview（300 元 = 30000 分）
    expect(wrapper.find('[data-testid="exit-settlement-preview"]').text()).toContain('300')

    await wrapper.find('[data-testid="exit-confirm-submit"]').trigger('click')
    await flushPromises()
    expect(client.respondEngagementExitRequest).toHaveBeenCalledWith('task-1', 'app-1', 'exit-1', true)
    expect(wrapper.emitted('refresh')).toBeTruthy()
  })

  it('发起方视角（exit_await_response）：撤回申请发 cancel 请求', async () => {
    const client = makeClient()
    const wrapper = mountActions(client, 'exit_await_response')
    await flushPromises()

    expect(wrapper.find('[data-action="cancel-exit-request"]').exists()).toBe(true)
    await wrapper.find('[data-action="cancel-exit-request"]').trigger('click')
    await flushPromises()
    expect(client.cancelEngagementExitRequest).toHaveBeenCalledWith('task-1', 'app-1', 'exit-1')
  })

  it('进行中合作：协商退出发起入口，原因必填且进入请求体（kind=negotiated）', async () => {
    const client = makeClient()
    const wrapper = mountActions(client, 'delivery')
    await flushPromises()

    await wrapper.find('[data-action="open-exit-request"]').trigger('click')
    await wrapper.find('[data-testid="exit-reason-input"]').setValue('内容方向调整希望协商')
    await wrapper.find('[data-testid="exit-request-submit"]').trigger('click')
    await flushPromises()
    expect(client.requestEngagementExit).toHaveBeenCalledWith('task-1', 'app-1', '内容方向调整希望协商')
    expect(wrapper.emitted('refresh')).toBeTruthy()
  })

  it('非进行中（终态/待筛选）不渲染发起入口', async () => {
    const client = makeClient()
    const wrapper = mountActions(client, 'ended', 'refunded')
    await flushPromises()
    expect(wrapper.find('[data-action="open-exit-request"]').exists()).toBe(false)
    expect(wrapper.text()).toBe('')
  })

  /** 任务书 #103 C103-04：退出终态（withdrawn）展示资金态结果与查询入口，不显示发起按钮。 */
  it('withdrawn 展示退出资金态（处理中 + 三腿金额 + 查询按钮）', async () => {
    const client = makeClient()
    const wrapper = mountActions(client, 'ended', 'withdrawn')
    await flushPromises()
    const result = wrapper.find('[data-testid="exit-funds-result"]')
    expect(result.exists()).toBe(true)
    expect(result.text()).toContain('资金处理中')
    expect(result.text()).toContain('押金退')
    expect(result.text()).toContain('赏金退')
    expect(wrapper.find('[data-action="refresh-exit-funds"]').exists()).toBe(true)
    expect(wrapper.find('[data-action="open-exit-request"]').exists()).toBe(false)
  })

  /** 旧响应/查询失败缺资金态 → 「资金状态待查询」，不得推断成功（§6.1 兼容原则）。 */
  it('无资金态数据时显示待查询而非伪成功', async () => {
    const client = makeClient()
    client.fetchEngagementExitFunds.mockResolvedValueOnce({ operationId: null, state: null })
    const wrapper = mountActions(client, 'ended', 'withdrawn')
    await flushPromises()
    expect(wrapper.find('[data-testid="exit-funds-result"]').text()).toContain('资金状态待查询')
  })
})
