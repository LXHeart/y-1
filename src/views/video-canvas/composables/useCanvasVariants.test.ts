import { afterEach, describe, expect, test, vi } from 'vitest'
import { computed, effectScope, ref } from 'vue'
import type { Router } from 'vue-router'
import { useCanvasVariants } from './useCanvasVariants'
import { useCanvasVariantHost } from './useCanvasVariantHost'
import type { CanvasStoryboard } from '../useVideoCanvas'
import type { VariantSummary } from '../../../types/video-canvas'

/**
 * 任务书 #100 C100-15：useCanvasVariants 单元（列表读取/字段比较）。
 */

function variant(overrides: Partial<VariantSummary> = {}): VariantSummary {
  return {
    storyboardId: 'sb-a', draftId: 'draft-a', parentStoryboardId: null, rootStoryboardId: 'sb-a',
    sourceEditVersion: null, title: '方案A', createdAt: '2026-09-12T00:00:00Z', ...overrides,
  }
}

function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}
afterEach(() => vi.unstubAllGlobals())

describe('C102 variant creation and project isolation', () => {
  test('flush completes before reading source versions; lost-response retry uses original body and the success navigation', async () => {
    const scope = effectScope(); const storyboard = ref({ id: 'sb-a', editVersion: 1, shots: [{ id: 'shot-1' }] } as CanvasStoryboard)
    const version = ref(1); const writes: string[] = []; let fail = true
    vi.stubGlobal('fetch', vi.fn(async (_url, init?: RequestInit) => {
      if (init?.method !== 'POST') return jsonResponse({ items: [] })
      writes.push(String(init.body))
      if (fail) return new Response('lost response', { status: 502 })
      return jsonResponse({ variant: variant({ storyboardId: 'sb-child', draftId: 'draft-child' }), project: { id: 'draft-child' }, shotIdMap: {} })
    }))
    const replace = vi.fn(async () => undefined)
    const flush = vi.fn(async () => { storyboard.value.editVersion = 7; version.value = 4; return true })
    const host = scope.run(() => useCanvasVariantHost({ storyboard, storyboardKey: computed(() => storyboard.value.id),
      draftVersion: () => version.value, router: { replace } as unknown as Router, flushBeforeSwitch: flush }))!
    await host.createVariant({ title: '明确范围', shotIds: ['shot-1'] })
    expect(JSON.parse(writes[0]!)).toMatchObject({ expectedEditVersion: 7, expectedDraftVersion: 4, title: '明确范围', shotIds: ['shot-1'] })
    expect(host.host.hasPendingCreation.value).toBe(true); expect(replace).not.toHaveBeenCalled()
    fail = false; replace.mockRejectedValueOnce(new Error('导航暂不可用')); await host.retryPending()
    expect(writes[1]).toBe(writes[0]); expect(host.host.hasPendingCreation.value).toBe(true)
    await host.retryPending()
    expect(writes).toHaveLength(2); expect(host.host.hasPendingCreation.value).toBe(false)
    expect(replace).toHaveBeenCalledWith({ name: 'video-canvas', query: { storyboard: 'sb-child', draft: 'draft-child' } })
    scope.stop()
  })
  test('failed flush creates nothing and leaves the project; late list/POST/finally cannot overwrite the next project', async () => {
    const scope = effectScope(); const id = ref('sb-a'); const epoch = ref(1)
    const replies: Array<(response: Response) => void> = []
    const fetchMock = vi.fn(() => new Promise<Response>(resolve => replies.push(resolve)))
    vi.stubGlobal('fetch', fetchMock)
    const session = scope.run(() => useCanvasVariants({ storyboardId: id, epoch: () => epoch.value,
      flushBeforeSwitch: async () => false, navigateToVariant: vi.fn() }))!
    const oldList = session.load()
    id.value = 'sb-b'; const newList = session.load()
    replies[0]!(jsonResponse({ items: [variant()] })); await oldList
    expect(session.loading.value).toBe(true); expect(session.variants.value).toEqual([])
    replies[1]!(jsonResponse({ items: [variant({ storyboardId: 'sb-b' })] })); await newList
    expect(session.variants.value[0]?.storyboardId).toBe('sb-b')
    const oldPost = session.create({ expectedEditVersion: 1, expectedDraftVersion: 1, title: 'B方案', shotIds: ['b-shot'] })
    id.value = 'sb-c'
    const newPost = session.create({ expectedEditVersion: 2, expectedDraftVersion: 2, title: 'C方案', shotIds: ['c-shot'] })
    replies[2]!(jsonResponse({ variant: variant(), project: { id: 'draft-old' } })); expect(await oldPost).toBeNull()
    expect(session.creating.value).toBe(true)
    replies[3]!(jsonResponse({ variant: variant({ storyboardId: 'child-c' }), project: { id: 'draft-c' } })); await newPost
    expect(session.creating.value).toBe(false)
    expect(await session.switchTo({ storyboardId: 'sb-a', draftId: 'draft-a' })).toBe(false)
    epoch.value++
    expect(session.variants.value).toEqual([]); expect(session.hasPendingCreation.value).toBe(false)
    scope.stop()
  })
  test('host with a failed source/document/delivery flush never POSTs or navigates', async () => {
    const scope = effectScope(); const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (_url, init?: RequestInit) => { calls.push(init?.method ?? 'GET'); return jsonResponse({ items: [] }) }))
    const replace = vi.fn(); const storyboard = ref({ id: 'sb-a', editVersion: 1, shots: [{ id: 's1' }] } as CanvasStoryboard)
    const host = scope.run(() => useCanvasVariantHost({ storyboard, storyboardKey: computed(() => 'sb-a'), draftVersion: () => 1,
      flushBeforeSwitch: async () => false, router: { replace } as unknown as Router }))!
    await host.createVariant({ title: '不能创建', shotIds: ['s1'] })
    expect(calls).toEqual(['GET']); expect(replace).not.toHaveBeenCalled(); expect(host.host.error.value).toContain('未保存')
    scope.stop()
  })
})

describe('#100 C100-15：useCanvasVariants 列表与比较', () => {
  test('列表读取：根 + 派生；失败置 error 不清既有列表', async () => {
    let fail = false
    vi.stubGlobal('fetch', vi.fn(async () => fail
      ? new Response('down', { status: 502 })
      : jsonResponse({ items: [variant(), variant({ storyboardId: 'sb-b',
          parentStoryboardId: 'sb-a', sourceEditVersion: 1, title: '方案B' })] })))
    const session = useCanvasVariants({
      storyboardId: ref('sb-a'),
      flushBeforeSwitch: async () => true,
      navigateToVariant: async () => {},
    })
    await session.load()
    expect(session.variants.value).toHaveLength(2)
    fail = true
    await session.load()
    expect(session.variants.value).toHaveLength(2) // 保留上次读取
    expect(session.error.value).toContain('方案列表读取失败')
  })

  test('compareFields 只列明确字段（标题/来源版本/父方案），无评分类伪指标', () => {
    const session = useCanvasVariants({
      storyboardId: ref('sb-a'),
      flushBeforeSwitch: async () => true,
      navigateToVariant: async () => {},
    })
    const fields = session.compareFields(variant(), variant({
      storyboardId: 'sb-b', parentStoryboardId: 'sb-a', sourceEditVersion: 2, title: '方案B',
    }))
    expect(fields.map(field => field.field)).toEqual(['标题', '来源版本', '父方案'])
  })
})
