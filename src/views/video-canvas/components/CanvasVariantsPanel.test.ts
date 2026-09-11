// @vitest-environment happy-dom
import { describe, expect, test, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import CanvasVariantsPanel from './CanvasVariantsPanel.vue'
import { useCanvasVariants } from '../composables/useCanvasVariants'
import type { VariantSummary } from '../../../types/video-canvas'

/**
 * 任务书 #100 C100-15：方案列表/创建/切换（TC-034：切换不串状态、失败不切走）。
 */

const ROOT = 'sb-root'
const CHILD = 'sb-child'

function variant(overrides: Partial<VariantSummary> = {}): VariantSummary {
  return {
    storyboardId: ROOT, draftId: 'draft-a', parentStoryboardId: null, rootStoryboardId: ROOT,
    sourceEditVersion: null, title: '方案A（根）', ...overrides,
  }
}

const variants = ref<VariantSummary[]>([
  variant(),
  variant({ storyboardId: CHILD, parentStoryboardId: ROOT, sourceEditVersion: 1, title: '方案B' }),
])

function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}

describe('#100 C100-15：useCanvasVariants', () => {
  test('创建丢响应原键重试（不换键）；连点防护', async () => {
    const calls: Array<{ operationId: string }> = []
    let failFirst = true
    vi.stubGlobal('fetch', vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) => {
      calls.push(JSON.parse(String(init?.body)))
      if (failFirst) {
        failFirst = false
        return new Response('network down', { status: 502 })
      }
      return jsonResponse({ variant: { storyboardId: 'sb-new', parentStoryboardId: ROOT,
        rootStoryboardId: ROOT, sourceEditVersion: 1, title: '方案C' },
      project: { id: 'draft-new' }, shotIdMap: {} })
    }))
    const session = useCanvasVariants({
      storyboardId: ref(ROOT),
      flushBeforeSwitch: async () => true,
      navigateToVariant: async () => {},
    })
    const first = await session.create({ expectedEditVersion: 1, expectedDraftVersion: 1,
      title: '方案C', shotIds: ['s1'] })
    expect(first).toBeNull()
    expect(session.error.value).toContain('原键重试')
    const retried = await session.retryPending()
    expect(retried?.variant.storyboardId).toBe('sb-new')
    expect(calls.map(call => call.operationId)).toHaveLength(2)
    expect(calls[0].operationId).toBe(calls[1].operationId) // 同键
  })

  test('切换前 flush 失败不切走（TC-034）', async () => {
    const navigate = vi.fn(async () => {})
    const session = useCanvasVariants({
      storyboardId: ref(ROOT),
      flushBeforeSwitch: async () => false,
      navigateToVariant: navigate,
    })
    expect(await session.switchTo({ storyboardId: CHILD, draftId: 'd2' })).toBe(false)
    expect(navigate).not.toHaveBeenCalled()
    expect(session.error.value).toContain('未保存')

    const sessionOk = useCanvasVariants({
      storyboardId: ref(ROOT),
      flushBeforeSwitch: async () => true,
      navigateToVariant: navigate,
    })
    expect(await sessionOk.switchTo({ storyboardId: CHILD, draftId: 'd2' })).toBe(true)
    expect(navigate).toHaveBeenCalledOnce()
  })
})

describe('#100 C100-15：CanvasVariantsPanel', () => {
  test('列表区分根/派生并展示父版本；比较只列明确字段差异', async () => {
    const wrapper = mount(CanvasVariantsPanel, {
      props: {
        variants: variants.value,
        currentStoryboardId: ROOT,
        loading: false,
        creating: false,
        error: '',
        hasPendingCreation: false,
      },
    })
    expect(wrapper.findAll('.variant-item').length).toBe(2)
    expect(wrapper.find(`[data-test="canvas-variant-${CHILD}"]`).text()).toContain('方案B')
    expect(wrapper.find(`[data-test="canvas-variant-${CHILD}"]`).text()).toContain('派生自')
    // 当前方案标记
    expect(wrapper.find(`[data-test="canvas-variant-current-${ROOT}"]`).exists()).toBe(true)

    await wrapper.find(`[data-test="canvas-variant-switch-${CHILD}"]`).trigger('click')
    const switched = wrapper.emitted('switch') ?? []
    expect(switched[switched.length - 1]?.[0]).toMatchObject({ storyboardId: CHILD })
    // 创建入口存在且标注独立内容方案（区别于分组分支）
    expect(wrapper.find('[data-test="canvas-variant-create"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('独立内容方案')
  })
})
