import { describe, expect, test, vi } from 'vitest'
import { ref } from 'vue'
import { useCanvasVariants } from './useCanvasVariants'
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
