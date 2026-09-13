// @vitest-environment happy-dom
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import { effectScope } from 'vue'
import ArticleVisualPanel from './ArticleVisualPanel.vue'
import VisualPlanEditor from './VisualPlanEditor.vue'
import VisualProductionPanel from './VisualProductionPanel.vue'
import { useVisualJob } from '../composables/useVisualJob'
import { useVisualPlan } from '../composables/useVisualPlan'
import type { CreationResultRef } from '../../../types/creation'
import type { SourceBlock, VisualPlan, VisualPlanDocument } from '../../../types/creation-studio'

/**
 * 任务书 #101 C101-15（TC101-069~071）：文章配图联合视图——封面/插图分列、
 * 插图用途与段落绑定展示、stale 重核提示、已采用回显、正文改动不隐式重生成、
 * 错误态可见。fetch 零调用（状态手工灌注）。
 */

const articleDocument: VisualPlanDocument = {
  recipe: { id: 'article-visuals', version: '1.0.0' },
  strategy: 'information',
  style: { styleId: 'minimal-note', layoutId: 'list', paletteId: 'macaron' },
  items: [
    { itemId: 'item-cover', cardId: 'card-cover', position: 1, role: 'cover', title: '三年排队生意', bullets: [], caption: '', purpose: '封面主题', illustration: '门头', sourceBlockIds: ['b1'], criticalText: [], layoutId: 'list', targetAspect: '16:9', placement: null, inputMediaRef: null },
    { itemId: 'item-2', cardId: 'card-2', position: 2, role: 'illustration', title: '定价图', bullets: [], caption: '', purpose: '放大数据对比', illustration: '明档', sourceBlockIds: ['b2'], criticalText: [], layoutId: 'list', targetAspect: '16:9', placement: { afterBlockId: 'b2' }, inputMediaRef: null },
    { itemId: 'item-3', cardId: 'card-3', position: 3, role: 'illustration', title: '流程图', bullets: [], caption: '', purpose: '解释出餐步骤', illustration: '灶火', sourceBlockIds: ['b3'], criticalText: [], layoutId: 'list', targetAspect: '16:9', placement: { afterBlockId: 'b3' }, inputMediaRef: null },
  ],
  explanation: '', uncoveredBlockIds: [],
}

const blocks: Record<string, SourceBlock> = {
  b1: { id: 'b1', kind: 'paragraph', position: 1, startCodePoint: 0, endCodePoint: 20, text: '门店三年，人均 68 元。', textHash: '' },
  b2: { id: 'b2', kind: 'heading', position: 2, startCodePoint: 21, endCodePoint: 40, text: '招牌与定价', textHash: '' },
  b3: { id: 'b3', kind: 'paragraph', position: 3, startCodePoint: 41, endCodePoint: 70, text: '招牌面 32 元一碗，日销两百碗。', textHash: '' },
}

function makePlan(overrides: Partial<VisualPlan> = {}): VisualPlan {
  return {
    id: 'plan-1', draftId: 'draft-1', status: 'ready', revision: 1, confirmedRevision: 1,
    source: { id: 'source-1', contentHash: 'a'.repeat(64) }, baseDraftVersion: 3,
    baseContentHash: 'b'.repeat(64), stale: false, document: structuredClone(articleDocument),
    runId: null, error: null, createdAt: '2026-09-13T00:00:00Z',
    ...overrides,
  }
}

const adoptedRefs: CreationResultRef[] = [
  { id: 'media-cover', refType: 'media', role: 'cover', cardId: 'card-cover', position: 1 },
  { id: 'media-2', refType: 'media', role: 'card', cardId: 'card-2', position: 2 },
]

enableAutoUnmount(afterEach)

function setup(plan: VisualPlan) {
  const planScope = effectScope()
  const planState = planScope.run(() => useVisualPlan({
    draftId: () => 'draft-1', draftVersion: () => 3, sourceDocumentId: () => 'source-1',
    sourceContentHash: () => 'a'.repeat(64), recipe: () => ({ id: 'article-visuals', version: '1.0.0' }),
    onPlanCreated: () => {},
  }))!
  planState.current.value = plan
  planState.document.value = JSON.parse(JSON.stringify(plan.document)) as VisualPlanDocument
  planState.bindSource({
    id: 'source-1', draftId: 'draft-1', schemaVersion: 1, kind: 'markdown', title: '', rawText: '',
    normalizedMarkdown: '', contentHash: 'a'.repeat(64),
    blocks: Object.values(blocks), sourceRefs: [], warnings: [], createdAt: '',
  })
  const jobScope = effectScope()
  const jobState = jobScope.run(() => useVisualJob({ plan: () => planState.current.value }))!
  return { planState, jobState }
}

function mountPanel(planState: ReturnType<typeof useVisualPlan>, jobState: ReturnType<typeof useVisualJob>,
  props: Record<string, unknown> = {}) {
  return mount(ArticleVisualPanel, {
    props: { plan: planState, job: jobState, ...props },
    global: { stubs: { teleport: true } },
  })
}

afterEach(() => { vi.unstubAllGlobals() })

describe('ArticleVisualPanel 计划展示', () => {
  test('封面恰好一张与插图分列；插图显示用途与绑定段落预览', () => {
    const { planState, jobState } = setup(makePlan())
    const wrapper = mountPanel(planState, jobState)
    expect(wrapper.find('[data-test="article-visual-cover"]').text()).toContain('三年排队生意')
    const rows = wrapper.findAll('[data-test^="article-visual-item-"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].find('[data-test="article-visual-purpose"]').text()).toBe('放大数据对比')
    expect(rows[0].find('[data-test="article-visual-placement"]').text()).toContain('招牌与定价')
    expect(rows[1].find('[data-test="article-visual-placement"]').text()).toContain('招牌面 32 元一碗')
  })

  test('未发起计划：显示发起入口与说明；错误可见', async () => {
    const { planState, jobState } = setup(makePlan())
    planState.current.value = null
    planState.error.value = '计划生成失败'
    const wrapper = mountPanel(planState, jobState)
    expect(wrapper.find('[data-test="article-visual-launch"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="article-visual-error"]').text()).toContain('计划生成失败')
    await wrapper.find('[data-test="article-visual-launch"]').trigger('click')
    expect(wrapper.emitted('prepare-plan')).toHaveLength(1)
  })

  test('stale：显示重核提示；不出现隐式重生成按钮', () => {
    const { planState, jobState } = setup(makePlan({ stale: true }))
    const wrapper = mountPanel(planState, jobState)
    expect(wrapper.find('[data-test="article-visual-stale"]').text()).toContain('正文已变化')
    // stale 时编辑器自身禁确认；面板不提供任何「自动重新生成」入口
    expect(wrapper.text()).not.toContain('自动重新生成')
  })

  test('已采用回显：封面与已采用插图显示已采用标记', () => {
    const { planState, jobState } = setup(makePlan())
    const wrapper = mountPanel(planState, jobState, { adoptedRefs })
    expect(wrapper.find('[data-test="article-visual-cover-adopted"]').exists()).toBe(true)
    const rows = wrapper.findAll('[data-test^="article-visual-item-"]')
    expect(rows[0].text()).toContain('已采用')
    expect(rows[1].text()).not.toContain('已采用')
  })

  test('段落被删除：位置提示需重新核对', () => {
    const mutated = JSON.parse(JSON.stringify(articleDocument)) as VisualPlanDocument
    ;(mutated.items[1] as { placement: { afterBlockId: string } }).placement.afterBlockId = 'b-gone'
    const { planState, jobState } = setup(makePlan({ document: mutated }))
    const wrapper = mountPanel(planState, jobState)
    expect(wrapper.find('[data-test="article-visual-item-2"]').find('[data-test="article-visual-placement"]').text())
      .toContain('重新核对')
  })

  test('计划就绪时装配编辑器与制作面板（同一 VisualProductionPanel）', () => {
    const { planState, jobState } = setup(makePlan())
    const wrapper = mountPanel(planState, jobState)
    expect(wrapper.findComponent(VisualPlanEditor).exists()).toBe(true)
    expect(wrapper.findComponent(VisualProductionPanel).exists()).toBe(true)
    // 采用中/错误透传
    const wrapper2 = mountPanel(planState, jobState, { adopting: true, adoptError: '采用失败' })
    expect(wrapper2.findComponent(VisualProductionPanel).props('adopting')).toBe(true)
    expect(wrapper2.findComponent(VisualProductionPanel).props('adoptError')).toBe('采用失败')
  })
})
