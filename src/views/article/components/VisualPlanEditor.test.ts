// @vitest-environment happy-dom
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import { effectScope } from 'vue'
import VisualPlanEditor from './VisualPlanEditor.vue'
import { useVisualPlan } from '../composables/useVisualPlan'
import type { SourceBlock, VisualPlan, VisualPlanDocument } from '../../../types/creation-studio'

/**
 * 任务书 #101 C101-06（TC101-026~029）：计划编辑界面——键盘排序、未保存/stale 生成闸、
 * 来源定位展开、关键文字核对提示与确认流。fetch 零调用（composable 状态手工灌注）。
 */

const planDocument: VisualPlanDocument = {
  recipe: { id: 'social-card-series', version: '1.0.0' },
  strategy: 'information',
  style: { styleId: 'minimal-note', layoutId: 'list', paletteId: 'macaron' },
  items: [
    { itemId: 'item-1', cardId: 'card-1', position: 1, role: 'cover', title: '封面页', bullets: [], caption: '', purpose: '吸引点开', illustration: '门头', sourceBlockIds: ['b1'], criticalText: ['68 元一份'], layoutId: 'list', targetAspect: '3:4', placement: null, inputMediaRef: null },
    { itemId: 'item-2', cardId: 'card-2', position: 2, role: 'content', title: '菜品页', bullets: [], caption: '', purpose: '招牌推荐', illustration: '烤鱼特写', sourceBlockIds: ['b2'], criticalText: ['不存在的关键句'], layoutId: 'comparison', targetAspect: '3:4', placement: null, inputMediaRef: null },
    { itemId: 'item-3', cardId: 'card-3', position: 3, role: 'summary', title: '总结页', bullets: [], caption: '', purpose: '行动号召', illustration: '地址卡片', sourceBlockIds: ['b2'], criticalText: [], layoutId: 'flow', targetAspect: '3:4', placement: null, inputMediaRef: null },
  ],
  explanation: '按信息密度拆三页',
  uncoveredBlockIds: [],
}

const blocks: Record<string, SourceBlock> = {
  b1: { id: 'b1', kind: 'paragraph', position: 1, startCodePoint: 0, endCodePoint: 40, text: '招牌烤鱼 68 元一份，手工酸辣粉 22 元。', textHash: '' },
  b2: { id: 'b2', kind: 'heading', position: 2, startCodePoint: 41, endCodePoint: 60, text: '开业福利', textHash: '' },
}

function makePlan(overrides: Partial<VisualPlan> = {}): VisualPlan {
  return {
    id: 'plan-1', draftId: 'draft-1', status: 'ready', revision: 1, confirmedRevision: null,
    source: { id: 'source-1', contentHash: 'a'.repeat(64) }, baseDraftVersion: 3,
    baseContentHash: 'b'.repeat(64), stale: false, document: structuredClone(planDocument),
    runId: null, error: null, createdAt: '2026-09-13T00:00:00Z',
    ...overrides,
  }
}

enableAutoUnmount(afterEach)

function mountEditor(planState: ReturnType<typeof useVisualPlan>, props: Record<string, unknown> = {}) {
  return mount(VisualPlanEditor, {
    props: { plan: planState, ...props },
    global: { stubs: { teleport: true } },
  })
}

function setupPlan(plan: VisualPlan) {
  const scope = effectScope()
  const controller = scope.run(() => useVisualPlan({
    draftId: () => 'draft-1',
    draftVersion: () => 3,
    sourceDocumentId: () => 'source-1',
    sourceContentHash: () => 'a'.repeat(64),
    recipe: () => ({ id: 'social-card-series', version: '1.0.0' }),
    onPlanCreated: () => {},
  }))!
  controller.current.value = plan
  controller.document.value = structuredClone(plan.document!)
  return { controller, scope }
}

describe('VisualPlanEditor（C101-06）', () => {
  test('要点与交付画幅可编辑，修改保持项目身份', async () => {
    const { controller, scope } = setupPlan(makePlan())
    const wrapper = mountEditor(controller)
    await wrapper.get('[data-test="plan-bullets-0"]').setValue('人均 68 元\n保留事实')
    await wrapper.get('[data-test="plan-aspect-0"]').setValue('16:9')
    expect(controller.document.value?.items[0]).toMatchObject({ itemId: 'item-1', cardId: 'card-1',
      bullets: ['人均 68 元', '保留事实'], targetAspect: '16:9' })
    expect(controller.dirty.value).toBe(true)
    scope.stop()
  })

  test('渲染逐页条目与修订徽标；策略与整组风格可见', () => {
    const { controller } = setupPlan(makePlan())
    const wrapper = mountEditor(controller)
    expect(wrapper.findAll('article.plan-item')).toHaveLength(3)
    expect(wrapper.find('[data-test="plan-revision-badge"]').text()).toContain('修订 1')
    expect(wrapper.find('[data-test="plan-strategy"]').exists()).toBe(true)
    expect((wrapper.find('[data-test="plan-style"]').element as HTMLSelectElement).value).toBe('minimal-note')
  })

  test('键盘排序：上移/下移按钮身份不变、位置连续（TC101-026）', async () => {
    const { controller } = setupPlan(makePlan())
    const wrapper = mountEditor(controller)
    await wrapper.find('[data-test="plan-item-up-2"]').trigger('click')
    const items = controller.document.value!.items
    expect(items.map((item) => item.itemId)).toEqual(['item-1', 'item-3', 'item-2'])
    expect(items.map((item) => item.position)).toEqual([1, 2, 3])
    // 首项上移禁用；末项下移禁用（键盘不会越界）
    expect((wrapper.find('[data-test="plan-item-up-0"]').element as HTMLButtonElement).disabled).toBe(true)
    expect((wrapper.find('[data-test="plan-item-down-2"]').element as HTMLButtonElement).disabled).toBe(true)
  })

  test('删除封面被拦截并提示；设为封面重排（封面规则有效）', async () => {
    const { controller } = setupPlan(makePlan())
    const wrapper = mountEditor(controller)
    await wrapper.find('[data-test="plan-item-remove-0"]').trigger('click')
    expect(controller.document.value!.items).toHaveLength(3)

    await wrapper.find('[data-test="plan-item-cover-2"]').trigger('click')
    const items = controller.document.value!.items
    expect(items[0].itemId).toBe('item-3')
    expect(items[0].role).toBe('cover')
    expect(items[1].role).toBe('summary')
  })

  test('未保存（dirty）时确认不可用，生成不可用；保存后确认可用（TC101-028 闸门）', async () => {
    const { controller } = setupPlan(makePlan())
    const wrapper = mountEditor(controller)
    await wrapper.find('[data-test="plan-title-1"]').setValue('改过的标题')
    expect(wrapper.find('[data-test="plan-dirty-badge"]').exists()).toBe(true)
    expect((wrapper.find('[data-test="plan-confirm"]').element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.find('[data-test="plan-generate"]').exists()).toBe(false)

    const flushSpy = vi.spyOn(controller, 'flush').mockResolvedValue(true)
    await wrapper.find('[data-test="plan-save"]').trigger('click')
    expect(flushSpy).toHaveBeenCalled()
  })

  test('stale 计划：显示过期说明，确认与生成均禁用', () => {
    const { controller } = setupPlan(makePlan({ stale: true, confirmedRevision: 1 }))
    const wrapper = mountEditor(controller)
    expect(wrapper.find('[data-test="plan-stale"]').exists()).toBe(true)
    expect((wrapper.find('[data-test="plan-confirm"]').element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.find('[data-test="plan-generate"]').exists()).toBe(false)
  })

  test('来源定位：点击展开原文依据；关键文字不逐字出现给出提示', async () => {
    const { controller } = setupPlan(makePlan())
    // 手工灌注来源块（生产由 launchVisualPlan bindSource）
    controller.bindSource({
      id: 'source-1', draftId: 'draft-1', schemaVersion: 1, kind: 'markdown', title: '',
      rawText: '', normalizedMarkdown: '', contentHash: '', blocks: Object.values(blocks),
      sourceRefs: [], warnings: [], createdAt: '',
    })
    const wrapper = mountEditor(controller)
    await wrapper.find('[data-test="plan-locate-b1"]').trigger('click')
    expect(wrapper.find('[data-test="plan-source-quote"]').text()).toContain('68 元一份')

    // item-2 的关键文字不在 b2 文本中 → 逐字核对失败提示
    const mismatch = wrapper.findAll('[data-test="plan-critical-mismatch"]')
    expect(mismatch).toHaveLength(1)
    expect(mismatch[0].text()).toContain('不存在的关键句')
  })

  test('未覆盖来源块说明可见；图片制作由制作区承接', () => {
    const { controller } = setupPlan(makePlan({ confirmedRevision: 1 }))
    const wrapper = mountEditor(controller)
    expect(wrapper.find('[data-test="plan-generate"]').exists()).toBe(false)

    const uncovered = setupPlan(makePlan({ confirmedRevision: 1, document: { ...structuredClone(planDocument), uncoveredBlockIds: ['b9'] } }))
    const wrapper2 = mountEditor(uncovered.controller)
    expect(wrapper2.find('[data-test="plan-uncovered"]').text()).toContain('1')
  })

  test('换策略弹窗：取消保留编辑；确认走 prepare 新建', async () => {
    const { controller } = setupPlan(makePlan())
    const prepareSpy = vi.spyOn(controller, 'prepare').mockResolvedValue()
    const wrapper = mountEditor(controller)
    await wrapper.find('[data-test="plan-switch-story"]').trigger('click')
    expect(wrapper.find('[data-test="plan-switch-cancel"]').exists()).toBe(true)
    await wrapper.find('[data-test="plan-switch-cancel"]').trigger('click')
    expect(prepareSpy).not.toHaveBeenCalled()

    await wrapper.find('[data-test="plan-switch-story"]').trigger('click')
    await wrapper.find('[data-test="plan-switch-ok"]').trigger('click')
    expect(prepareSpy).not.toHaveBeenCalled()
    expect(wrapper.emitted('prepare-requested')).toEqual([[{ strategy: 'story' }]])
  })
})
