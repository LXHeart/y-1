// @vitest-environment happy-dom
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { effectScope } from 'vue'
import VisualProductionPanel from './VisualProductionPanel.vue'
import VisualCandidateCard from './VisualCandidateCard.vue'
import { useVisualJob } from '../composables/useVisualJob'
import { useVisualPlan } from '../composables/useVisualPlan'
import type { VisualJob, VisualJobItem, VisualPlan, VisualPlanDocument, VisualQuote } from '../../../types/creation-studio'

/**
 * 任务书 #101 C101-11（TC101-052/054/055）：制作面——部分成功展示与单项重做、
 * 费用确认弹窗（预算/BYOK 分流）、unknown 重做确认闸、候选选择只 emit 引用。
 * fetch 零调用（composable 状态手工灌注，GlModal 用真实组件走 Teleport stub）。
 */

const planDocument: VisualPlanDocument = {
  recipe: { id: 'social-card-series', version: '1.0.0' },
  strategy: 'information',
  style: { styleId: 's', layoutId: 'l', paletteId: 'p' },
  items: [
    { itemId: 'item-1', cardId: 'c1', position: 1, role: 'cover', title: '封面', bullets: [], caption: '', purpose: '', illustration: '', sourceBlockIds: [], criticalText: [], layoutId: 'l', targetAspect: '3:4', placement: null, inputMediaRef: null },
    { itemId: 'item-2', cardId: 'c2', position: 2, role: 'content', title: '内页', bullets: [], caption: '', purpose: '', illustration: '', sourceBlockIds: [], criticalText: [], layoutId: 'l', targetAspect: '3:4', placement: null, inputMediaRef: null },
    { itemId: 'item-3', cardId: 'c3', position: 3, role: 'content', title: '失败页', bullets: [], caption: '', purpose: '', illustration: '', sourceBlockIds: [], criticalText: [], layoutId: 'l', targetAspect: '3:4', placement: null, inputMediaRef: null },
  ],
  explanation: '', uncoveredBlockIds: [],
}

function makePlan(): VisualPlan {
  return {
    id: 'plan-1', draftId: 'draft-1', status: 'ready', revision: 1, confirmedRevision: 1,
    source: { id: 'source-1', contentHash: 'a'.repeat(64) }, baseDraftVersion: 3,
    baseContentHash: 'b'.repeat(64), stale: false, document: structuredClone(planDocument),
    runId: null, error: null, createdAt: '2026-09-13T00:00:00Z',
  }
}

function artifactOf(itemId: string, attemptId: string): VisualJobItem['artifact'] {
  return {
    id: `art-${attemptId}`, itemId, attemptId, plan: { id: 'plan-1', revision: 1 },
    originalMediaRef: { id: `orig-${attemptId}`, refType: 'media' },
    deliveryMediaRef: { id: `del-${attemptId}`, refType: 'media' },
    runId: `run-${attemptId}`, width: 1080, height: 1440, contentHash: 'h',
    anchorArtifactId: null, createdAt: '2026-09-13T00:00:00Z',
  }
}

function makeJob(items: VisualJobItem[], overrides: Partial<VisualJob> = {}): VisualJob {
  return {
    id: 'job-1', requestId: 'req-1', draftId: 'draft-1', plan: { id: 'plan-1', revision: 1 },
    state: 'partial', version: 2, items, cancelRequested: false, quoteId: 'quote-1',
    createdAt: '2026-09-13T00:00:00Z', updatedAt: '2026-09-13T00:01:00Z',
    ...overrides,
  }
}

/** 手工灌注终态任务（部分成功：1/2 成功、3 失败）到 composable。 */
function setupPartial(overrides: { items?: VisualJobItem[]; job?: VisualJob } = {}) {
  const planScope = effectScope()
  const planState = planScope.run(() => useVisualPlan({
    draftId: () => 'draft-1', draftVersion: () => 3, sourceDocumentId: () => 'source-1',
    sourceContentHash: () => 'a'.repeat(64), recipe: () => ({ id: 'social-card-series', version: '1.0.0' }),
    onPlanCreated: () => {},
  }))!
  planState.current.value = makePlan()

  const jobScope = effectScope()
  const jobState = jobScope.run(() => useVisualJob({ plan: () => planState.current.value }))!
  jobState.current.value = overrides.job ?? makeJob(overrides.items ?? [
    { attemptId: 'a1', itemId: 'item-1', position: 1, state: 'succeeded', runId: 'r1', artifact: artifactOf('item-1', 'a1'), error: null },
    { attemptId: 'a2', itemId: 'item-2', position: 2, state: 'succeeded', runId: 'r2', artifact: artifactOf('item-2', 'a2'), error: null },
    { attemptId: 'a3', itemId: 'item-3', position: 3, state: 'failed', runId: 'r3', artifact: null, error: { code: 'PROVIDER_FAILED', message: '生成失败，详见错误码' } },
  ])
  return { planState, jobState, scopes: [planScope, jobScope] }
}

enableAutoUnmount(afterEach)

function mountPanel(planState: ReturnType<typeof useVisualPlan>, jobState: ReturnType<typeof useVisualJob>) {
  return mount(VisualProductionPanel, {
    props: { plan: planState, job: jobState },
    global: { stubs: { teleport: true } },
  })
}

const STUB_QUOTE = {
  id: 'quote-stub', plan: { id: 'plan-1', revision: 1 }, selectedItemIds: ['item-1'], imageCalls: 1,
  consistencyMode: 'prompt-only', anchorArtifactId: null, userCredits: 0, platformBudgetCents: 30,
  billingSource: 'platform', pricingVersion: 'v1', configurationFingerprint: 'f',
  expiresAt: '2026-09-13T00:02:00Z', warnings: [],
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const body = url.includes('/estimate')
      ? STUB_QUOTE
      : { url: 'https://signed.invalid/del.jpg' }
    return new Response(JSON.stringify({ success: true, data: body }), { status: 200 })
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('VisualProductionPanel 进度与部分成功（TC101-052）', () => {
  test('部分成功：真实状态标签 + 完成计数，失败项可重做，成功候选保留', () => {
    const { planState, jobState } = setupPartial()
    const wrapper = mountPanel(planState, jobState)
    expect(wrapper.find('[data-test="visual-job-state"]').text()).toContain('部分成功')
    expect(wrapper.find('[data-test="visual-job-state"]').text()).toContain('2/3 完成')
    // 第 3 张失败：错误可见 + 重做按钮；1/2 成功候选保留
    expect(wrapper.find('[data-test="visual-item-3"]').text()).toContain('失败')
    expect(wrapper.find('[data-test="visual-redo-3"]').exists()).toBe(true)
    expect(wrapper.findAllComponents(VisualCandidateCard)).toHaveLength(2)
    // 不用百分比假进度：进度区只出现「x/y 完成」
    expect(wrapper.find('[data-test="visual-job-state"]').text()).not.toMatch(/%\d|\d%/)
  })

  test('重做失败项：quote 后弹费用确认，确认才 create（显式范围仅含该项）', async () => {
    const { planState, jobState } = setupPartial()
    const estimate = vi.spyOn(jobState, 'estimate')
    const create = vi.spyOn(jobState, 'create')
    const wrapper = mountPanel(planState, jobState)
    await wrapper.find('[data-test="visual-redo-3"]').trigger('click')
    await vi.waitFor(() => { expect(estimate).toHaveBeenCalledWith(['item-3']) })
    expect(create).not.toHaveBeenCalled()
    // 费用确认弹窗 → 确认后携带 quoteId 创建
    await vi.waitFor(() => { expect(wrapper.find('[data-test="visual-cost-ok"]').exists()).toBe(true) })
    await wrapper.find('[data-test="visual-cost-ok"]').trigger('click')
    await vi.waitFor(() => {
      expect(create).toHaveBeenCalledWith({ selectedItemIds: ['item-3'], quoteId: 'quote-stub' })
    })
  })

  test('unknown 项重做：先弹确认闸（可能再次计费），estimate 失败不发起 create', async () => {
    const { planState, jobState } = setupPartial({
      items: [
        { attemptId: 'a1', itemId: 'item-1', position: 1, state: 'succeeded', runId: 'r1', artifact: artifactOf('item-1', 'a1'), error: null },
        { attemptId: 'a9', itemId: 'item-3', position: 3, state: 'unknown', runId: 'r9', artifact: null, error: null },
      ],
    })
    vi.spyOn(jobState, 'estimate').mockResolvedValue(null)
    const create = vi.spyOn(jobState, 'create')
    const wrapper = mountPanel(planState, jobState)
    await wrapper.find('[data-test="visual-redo-unknown-3"]').trigger('click')
    // 未确认前不发起
    expect(create).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="visual-unknown-ok"]').exists()).toBe(true)
    await wrapper.find('[data-test="visual-unknown-ok"]').trigger('click')
    await vi.waitFor(() => { expect(wrapper.find('[data-test="visual-unknown-ok"]').exists()).toBe(false) })
    // estimate 失败 → 不创建
    await vi.waitFor(() => { expect(jobState.estimate).toBeTruthy() })
    expect(create).not.toHaveBeenCalled()
  })

  test('unknown 确认后 estimate 成功则携带 ack 创建', async () => {
    const { planState, jobState } = setupPartial({
      items: [
        { attemptId: 'a1', itemId: 'item-1', position: 1, state: 'succeeded', runId: 'r1', artifact: artifactOf('item-1', 'a1'), error: null },
        { attemptId: 'a9', itemId: 'item-3', position: 3, state: 'unknown', runId: 'r9', artifact: null, error: null },
      ],
    })
    vi.spyOn(jobState, 'estimate').mockResolvedValue({
      id: 'quote-9', plan: { id: 'plan-1', revision: 1 }, selectedItemIds: ['item-3'], imageCalls: 1,
      consistencyMode: 'prompt-only', anchorArtifactId: null, userCredits: 0, platformBudgetCents: 30,
      billingSource: 'platform', pricingVersion: 'v1', configurationFingerprint: 'f',
      expiresAt: '2026-09-13T00:02:00Z', warnings: [],
    })
    const create = vi.spyOn(jobState, 'create')
    const wrapper = mountPanel(planState, jobState)
    await wrapper.find('[data-test="visual-redo-unknown-3"]').trigger('click')
    await wrapper.find('[data-test="visual-unknown-ok"]').trigger('click')
    await vi.waitFor(() => {
      expect(create).toHaveBeenCalledWith({
        selectedItemIds: ['item-3'], quoteId: 'quote-9', acknowledgedUnknownAttemptIds: ['a9'],
      })
    })
  })
})

describe('VisualProductionPanel 费用确认（§5.5）', () => {
  test('平台预算计费文案带金额；确认前不创建', async () => {
    const { planState, jobState } = setupPartial({ items: [], job: undefined })
    jobState.current.value = null
    const create = vi.spyOn(jobState, 'create')
    const wrapper = mountPanel(planState, jobState)
    // 初始面板：范围选择 + 发起估算
    expect(wrapper.find('[data-test="visual-quote-start"]').exists()).toBe(true)
    await wrapper.find('[data-test="visual-quote-start"]').trigger('click')
    expect(create).not.toHaveBeenCalled()
    // 估算桩返回平台预算 quote（¥0.30 / 1 次）
    await vi.waitFor(() => { expect(wrapper.find('[data-test="visual-cost-text"]').exists()).toBe(true) })
    expect(wrapper.find('[data-test="visual-cost-text"]').text()).toContain('¥0.30')
    expect(wrapper.find('[data-test="visual-cost-text"]').text()).toContain('1 次图片生成')
    // 取消不创建
    await wrapper.find('[data-test="visual-cost-cancel"]').trigger('click')
    expect(create).not.toHaveBeenCalled()
  })

  test('BYOK 计费文案不写平台金额（不伪造 0 元）', async () => {
    const { planState, jobState } = setupPartial()
    jobState.current.value = null
    const byokQuote: VisualQuote = {
      id: 'quote-byok', plan: { id: 'plan-1', revision: 1 }, selectedItemIds: ['item-1'], imageCalls: 1,
      consistencyMode: 'prompt-only', anchorArtifactId: null, userCredits: 0, platformBudgetCents: 0,
      billingSource: 'personal-byok', pricingVersion: 'v1', configurationFingerprint: 'f',
      expiresAt: '2026-09-13T00:02:00Z', warnings: [],
    }
    vi.spyOn(jobState, 'estimate').mockImplementation(async () => {
      jobState.quote.value = byokQuote
      return byokQuote
    })
    const wrapper = mountPanel(planState, jobState)
    await wrapper.find('[data-test="visual-quote-start"]').trigger('click')
    await vi.waitFor(() => {
      expect(wrapper.find('[data-test="visual-cost-text"]').text()).not.toBe('')
    })
    const text = wrapper.find('[data-test="visual-cost-text"]').text()
    expect(text).toContain('自有模型密钥')
    expect(text).not.toContain('¥')
  })
})

describe('VisualProductionPanel 候选选择（TC101-054）', () => {
  test('选择候选只 emit 引用 + 记录预选，不显示「已采用」', async () => {
    const { planState, jobState } = setupPartial()
    const selectCandidate = vi.spyOn(jobState, 'selectCandidate')
    const wrapper = mountPanel(planState, jobState)
    const card = wrapper.findAllComponents(VisualCandidateCard)[0]
    await card.vm.$emit('select', { itemId: 'item-1', artifactId: 'art-a1' })
    expect(selectCandidate).toHaveBeenCalledWith('item-1', 'art-a1')
    expect(wrapper.emitted('candidate-selected')?.[0]).toEqual([{ itemId: 'item-1', artifactId: 'art-a1' }])
    // 预选标记是「待确认采用」，不是「已采用」
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('已选择（待确认采用）')
      expect(wrapper.text()).not.toContain('已采用')
    })
  })

  test('候选放大走 zoom 事件（父层 lightbox 承载）', async () => {
    const { planState, jobState } = setupPartial()
    const wrapper = mountPanel(planState, jobState)
    const card = wrapper.findAllComponents(VisualCandidateCard)[0]
    await card.vm.$emit('zoom', 'https://signed.invalid/del.jpg')
    expect(wrapper.emitted('zoom')?.[0]).toEqual(['https://signed.invalid/del.jpg'])
  })
})

describe('VisualProductionPanel 进行中与取消（TC101-055）', () => {
  test('进行中任务：可取消、可刷新超时提示；错误不假报', async () => {
    const { planState, jobState } = setupPartial({
      job: makeJob([
        { attemptId: 'a1', itemId: 'item-1', position: 1, state: 'dispatching', runId: null, artifact: null, error: null },
        { attemptId: 'a2', itemId: 'item-2', position: 2, state: 'queued', runId: null, artifact: null, error: null },
      ], { state: 'running', version: 1 }),
    })
    jobState.polling.value = true
    const cancel = vi.spyOn(jobState, 'cancel').mockResolvedValue(true)
    const wrapper = mountPanel(planState, jobState)
    expect(wrapper.find('[data-test="visual-item-1"]').text()).toContain('生成中')
    expect(wrapper.find('[data-test="visual-item-2"]').text()).toContain('排队中')
    await wrapper.find('[data-test="visual-cancel"]').trigger('click')
    expect(cancel).toHaveBeenCalled()
  })

  test('失败任务错误可见且可行动（重做入口存在）', () => {
    const { planState, jobState } = setupPartial({
      job: makeJob([
        { attemptId: 'a3', itemId: 'item-3', position: 3, state: 'failed', runId: 'r3', artifact: null, error: { code: 'NO_BUDGET', message: '预算不足' } },
      ], { state: 'failed' }),
    })
    const wrapper = mountPanel(planState, jobState)
    expect(wrapper.find('[data-test="visual-item-3"]').text()).toContain('预算不足')
    expect(wrapper.find('[data-test="visual-redo-3"]').exists()).toBe(true)
  })
})
