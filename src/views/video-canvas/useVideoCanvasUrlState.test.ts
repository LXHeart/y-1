// @vitest-environment happy-dom
import { describe, expect, test, vi } from 'vitest'
import { reactive } from 'vue'
import { normalizeCanvasRoute, useVideoCanvasUrlState } from './useVideoCanvasUrlState'

/** TC-010/011（任务书 #100 C100-04）：URL 是定位器不是权限；draft+storyboard 标准、旧深链兼容。 */
describe('#100 C100-04：画布 URL 状态', () => {
  test('旧 storyboard-only 深链：draft 为 null（服务端唯一补关联）', () => {
    expect(normalizeCanvasRoute({ query: { storyboard: 'sb-1' } })).toEqual({ storyboard: 'sb-1', draft: null })
  })

  test('draft+storyboard 双参；空白与空串归一', () => {
    expect(normalizeCanvasRoute({ query: { storyboard: ' sb-1 ', draft: ' d-1 ' } }))
      .toEqual({ storyboard: 'sb-1', draft: 'd-1' })
    expect(normalizeCanvasRoute({ query: { storyboard: 'sb-1', draft: '' } })!.draft).toBeNull()
    expect(normalizeCanvasRoute({ query: { storyboard: 'sb-1', draft: 7 } })!.draft).toBeNull()
  })

  test('缺 storyboard（或非字符串）返回 null——视图显示空态', () => {
    expect(normalizeCanvasRoute({ query: {} })).toBeNull()
    expect(normalizeCanvasRoute({ query: { storyboard: '   ' } })).toBeNull()
    expect(normalizeCanvasRoute({ query: { storyboard: 42 } })).toBeNull()
  })

  test('syncDraft：绑定后回写 draft（replace 不污染历史），保留其他 query 键', () => {
    const route = reactive({ query: { storyboard: 'sb-1', other: 'keep' } })
    const router = { replace: vi.fn() }
    const { syncDraft } = useVideoCanvasUrlState(route as never, router as never)
    syncDraft('d-9')
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledWith({ query: { other: 'keep', storyboard: 'sb-1', draft: 'd-9' } })
  })

  test('syncDraft：draft 未变化不触发导航（恢复/激活循环不抖动）', () => {
    const route = reactive({ query: { storyboard: 'sb-1', draft: 'd-1' } })
    const router = { replace: vi.fn() }
    const { syncDraft } = useVideoCanvasUrlState(route as never, router as never)
    syncDraft('d-1')
    expect(router.replace).not.toHaveBeenCalled()
  })

  test('syncDraft：无 storyboard / 无 draft 不写路由', () => {
    const route = reactive({ query: {} })
    const router = { replace: vi.fn() }
    const { syncDraft } = useVideoCanvasUrlState(route as never, router as never)
    syncDraft('d-1')
    expect(router.replace).not.toHaveBeenCalled()

    const route2 = reactive({ query: { storyboard: 'sb-1' } })
    const { syncDraft: sync2 } = useVideoCanvasUrlState(route2 as never, router as never)
    sync2(undefined)
    expect(router.replace).not.toHaveBeenCalled()
  })

  test('key 随路由变化（KeepAlive 内路由 key 改变可被观察）', () => {
    const route = reactive({ query: { storyboard: 'sb-1' } as Record<string, unknown> })
    const { key } = useVideoCanvasUrlState(route as never, { replace: vi.fn() } as never)
    expect(key.value?.storyboard).toBe('sb-1')
    route.query = { storyboard: 'sb-2', draft: 'd-2' }
    expect(key.value).toEqual({ storyboard: 'sb-2', draft: 'd-2' })
  })
})
