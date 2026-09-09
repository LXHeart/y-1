// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import TaskDetailModal from './TaskDetailModal.vue'
import type { Task } from '../../../types/grassland'

enableAutoUnmount(afterEach)

const baseTask: Task = {
  id: 'task-1', ownerAccountId: 'owner-1', organizationId: 'org-1', title: '大厅详情任务',
  description: '任务描述', status: 'published', contentForm: 'image', platform: 'xiaohongshu',
  maxSlots: 3, bountyCents: 5000, freebieDepositCents: 0, minRecommenderLevel: 1,
  requirements: { mustInclude: [], forbiddenContent: [], metricRequirements: [], evidenceRequirements: [] },
  version: 1, applicationDeadline: '2030-01-01T00:00:00Z', publishedAt: null, cancelledAt: null,
  createdAt: '2026-08-01T00:00:00Z', autoAcceptMinLevel: null,
} as Task

function stubTaskFetch(task: Task | null, options: { storeProfile?: unknown } = {}) {
  const spy = vi.fn(async (url: string) => {
    let data: unknown = {}
    if (url === '/api/tasks/task-1' && task) {
      data = task
    } else if (url.startsWith('/api/tasks/task-1/applications?')) {
      data = { items: [], nextCursor: null, hasMore: false }
    } else if (url.startsWith('/api/tasks/task-1/applications/app-')) {
      const id = url.split('/').slice(-1)[0]
      data = { id, taskId: 'task-1', recommenderAccountId: 'rec-1',
        status: ({ 'app-1': 'pending', 'app-2': 'reserving', 'app-3': 'accepted', 'app-4': 'withdrawn' } as Record<string, string>)[id],
        createdAt: null }
    } else if (url.startsWith('/api/applications/')) {
      data = { applicationId: url.split('/')[3], taskId: 'task-1', confirmedAt: null,
        settlementEligibleAt: null, settlementStatus: 'not_confirmed', holdReason: null, allowedActions: ['submit'] }
    } else if (url.startsWith('/api/stores/') && url.endsWith('/public-profile')) {
      data = options.storeProfile ?? null
    } else if (url.startsWith('/api/stores/')) {
      data = []
    }
    return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

/** GlModal Teleport 到 body：attachTo 挂载后统一查 document（VTU 的 emitted 仍从组件实例取）。 */
function mountModal(props: Record<string, unknown> = {}) {
  const wrapper = mount(TaskDetailModal, {
    attachTo: document.body,
    props: {
      task: baseTask,
      taskId: 'task-1',
      myApplication: null,
      loading: false,
      ...props,
    },
  })
  const buttons = () => Array.from(document.body.querySelectorAll('button'))
  const button = (text: string) => {
    const el = buttons().find((b) => b.textContent?.trim() === text)
    expect(el, `button「${text}」应存在`).toBeDefined()
    return el!
  }
  return { wrapper, buttons, button }
}

describe('TaskDetailModal（任务书 #77 卡 A：大厅与我的任务共用详情弹窗）', () => {
  beforeEach(() => {
    stubTaskFetch(baseTask)
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('渲染详情块 + 报名/举报 footer；未报名时报名可点并上抛 apply', async () => {
    const { wrapper, buttons, button } = mountModal()
    await flushPromises()

    expect(document.body.innerHTML).toContain('大厅详情任务')
    // 详情块内嵌（embedded 模式无收起按钮、无行内动作）
    expect(document.body.querySelector('[data-testid="task-detail-card"]')).toBeDefined()
    expect(buttons().some((b) => b.textContent?.trim() === '收起')).toBe(false)

    const apply = button('报名')
    expect(apply.hasAttribute('disabled')).toBe(false)
    apply.click()
    await flushPromises()
    expect(wrapper.emitted('apply')).toEqual([['task-1']])

    expect(buttons().some((b) => b.textContent?.trim() === '举报该任务')).toBe(true)
  })

  test('pending → 取消报名上抛 withdraw；reserving → 禁用「处理中」；accepted → 开始创作', async () => {
    const pending = mountModal({
      myApplication: { applicationId: 'app-1', taskId: 'task-1', applicationStatus: 'pending' },
    })
    await flushPromises()
    pending.button('取消报名').click()
    await flushPromises()
    expect(pending.wrapper.emitted('withdraw')![0][0]).toMatchObject({ applicationId: 'app-1' })

    const reserving = mountModal({
      myApplication: { applicationId: 'app-2', taskId: 'task-1', applicationStatus: 'reserving' },
    })
    await flushPromises()
    expect(reserving.button('处理中').hasAttribute('disabled')).toBe(true)

    const accepted = mountModal({
      myApplication: { applicationId: 'app-3', taskId: 'task-1', applicationStatus: 'accepted' },
    })
    await flushPromises()
    accepted.button('开始创作').click()
    await flushPromises()
    const events = accepted.wrapper.emitted('start-creation')
    expect(events![0][0]).toMatchObject({ task: { id: 'task-1' }, application: { applicationId: 'app-3' } })
  })

  test('终态（withdrawn）报名不可再报名——禁用「不可重新报名」防撞 UNIQUE（卡 C 口径）', async () => {
    const { wrapper, button } = mountModal({
      myApplication: { applicationId: 'app-4', taskId: 'task-1', applicationStatus: 'withdrawn' },
    })
    await flushPromises()
    expect(button('不可重新报名').hasAttribute('disabled')).toBe(true)
    expect(wrapper.emitted('apply')).toBeUndefined()
  })

  test('showApply=false（我的任务挂载）：不渲染报名入口', async () => {
    const { buttons } = mountModal({ showApply: false })
    await flushPromises()
    expect(buttons().some((b) => b.textContent?.trim() === '报名')).toBe(false)
  })

  test('task=null（我的任务投影行）时按 taskId 补拉详情', async () => {
    const spy = stubTaskFetch({ ...baseTask, title: '补拉的任务' })
    const { wrapper: pullWrapper } = mountModal({ task: null })
    await flushPromises()
    void pullWrapper

    expect(spy).toHaveBeenCalledWith('/api/tasks/task-1', expect.anything())
    expect(document.body.innerHTML).toContain('补拉的任务')
  })

  test('报名已截止：报名按钮禁用并显示「报名已截止」', async () => {
    stubTaskFetch(null)
    const { button } = mountModal({ task: { ...baseTask, applicationDeadline: '2020-01-01T00:00:00Z' } })
    await flushPromises()
    expect(button('报名已截止').hasAttribute('disabled')).toBe(true)
  })

  // ---------- 任务书 #98 C98-04：商家信用摘要 ----------

  test('详情展示商家信用标签（含口径与合作数）；样本不足显示中性文案不展示标签', async () => {
    stubTaskFetch({ ...baseTask, merchantCredit: { label: '良好', insufficientSamples: false, sampleCount: 23,
      policyVersion: 'merchant_credit_v1' } })
    const first = mountModal({ task: { ...baseTask, merchantCredit: { label: '良好', insufficientSamples: false, sampleCount: 23,
      policyVersion: 'merchant_credit_v1' } } })
    await flushPromises()
    expect(document.body.querySelector('[data-testid="merchant-credit"]')?.textContent)
      .toContain('商家信用：良好（近 23 次合作，口径 merchant_credit_v1）')
    // Teleport 到 body：先卸载再挂第二个，避免残留 DOM 干扰断言。
    first.wrapper.unmount()

    stubTaskFetch({ ...baseTask, merchantCredit: { label: null, insufficientSamples: true, sampleCount: 3,
      policyVersion: 'merchant_credit_v1' } })
    mountModal({ task: { ...baseTask, merchantCredit: { label: null, insufficientSamples: true, sampleCount: 3,
      policyVersion: 'merchant_credit_v1' } } })
    await flushPromises()
    expect(document.body.querySelector('[data-testid="merchant-credit"]')?.textContent)
      .toContain('合作样本不足（3 次），暂不展示信用标签')
  })
})
