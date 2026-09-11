import { describe, expect, test, vi } from 'vitest'
import { ref } from 'vue'
import { useCanvasDocument } from './useCanvasDocument'
import type { CanvasDocument, CanvasDocumentBody } from '../../../types/video-canvas'

/**
 * 任务书 #100 C100-09：独立画布文档会话（TC-022 边界：并发迁移/未来 schema/失败保留）。
 */

const draftId = ref('draft-1')

function body(overrides: Partial<CanvasDocumentBody> = {}): CanvasDocumentBody {
  return {
    schemaVersion: 1,
    storyboardId: 'sb-1',
    viewport: { panX: 0, panY: 0, scale: 1 },
    nodes: [{
      id: 'shot:shot-1', kind: 'shot', refType: 'shot', refId: 'shot-1',
      label: null, text: null, x: 40, y: 40,
    }],
    edges: [],
    activeBranchId: null,
    ...overrides,
  }
}

function remoteDoc(revision: number, document: CanvasDocumentBody): CanvasDocument {
  return { id: 'canvas-1', draftId: 'draft-1', revision, updatedAt: '2026-09-12T00:00:00Z', document }
}

function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('#100 C100-09：独立画布文档会话', () => {
  test('GET null → 旧布局升级 revision=0 创建成功；重复升级幂等跳过', async () => {
    const puts: Array<{ expectedRevision: number }> = []
    vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      if (method === 'PUT') {
        const payload = JSON.parse(String(init?.body)) as { expectedRevision: number; document: CanvasDocumentBody }
        puts.push({ expectedRevision: payload.expectedRevision })
        // 服务端权威：回显落库后的同一 document
        return jsonResponse(remoteDoc(1, payload.document))
      }
      return jsonResponse(null)
    }))
    const session = useCanvasDocument(draftId, { fallbackShots: () => [{ id: 'shot-1' }] })
    expect(await session.load()).toBe(false)
    expect(await session.upgradeFromLegacy({
      schemaVersion: 1, storyboardId: 'sb-1', viewport: { panX: 5, panY: 6, scale: 1 },
      positions: { 'shot-1': { x: 100, y: 120 } }, activeBranchId: null,
    })).toBe(true)
    expect(puts).toEqual([{ expectedRevision: 0 }])
    expect(session.revision.value).toBe(1)
    expect(session.document.value?.nodes[0].x).toBe(100)
    // 已升级后再次调用：不再创建
    expect(await session.upgradeFromLegacy(null)).toBe(false)
    expect(puts).toHaveLength(1)
  })

  test('并发首建 409 → 读取胜出版本采纳，不覆盖对方（TC-022）', async () => {
    let created = false
    let winnerReturned = false
    vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      if (method === 'PUT') {
        if (!created) {
          created = true
          return new Response(JSON.stringify({ success: false, error: 'revision 冲突' }), { status: 409 })
        }
        throw new Error('不允许二次创建')
      }
      // 409 后的 GET：返回对方胜出版本（shot 节点坐标 999 = 对方保存的）
      winnerReturned = true
      return jsonResponse(remoteDoc(1, body({
        nodes: [{ id: 'shot:shot-1', kind: 'shot', refType: 'shot', refId: 'shot-1',
          label: null, text: null, x: 999, y: 999 }],
      })))
    }))
    const session = useCanvasDocument(draftId, { fallbackShots: () => [{ id: 'shot-1' }] })
    expect(await session.upgradeFromLegacy({
      schemaVersion: 1, storyboardId: 'sb-1', viewport: { panX: 0, panY: 0, scale: 1 },
      positions: {}, activeBranchId: null,
    })).toBe(true)
    expect(winnerReturned).toBe(true)
    expect(session.revision.value).toBe(1)
    expect(session.document.value?.nodes[0].x).toBe(999)
    expect(session.conflict.value).toBe(false)
  })

  test('未来 schema 只读：可展示但保存被拒（TC-022/E19）', async () => {
    vi.stubGlobal('fetch', vi.fn(async () =>
      jsonResponse(remoteDoc(3, body({ schemaVersion: 2 as unknown as 1 })))))
    const session = useCanvasDocument(draftId)
    expect(await session.load()).toBe(true)
    expect(session.readOnly.value).toBe(true)
    expect(await session.save(body())).toBe(false)
  })

  test('保存 409 保留本地修改并置 conflict；网络失败不回落覆盖（TC-022/E04）', async () => {
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      calls.push(method)
      if (method === 'PUT') {
        return new Response(JSON.stringify({ success: false, error: '冲突' }), { status: 409 })
      }
      if (calls.filter(call => call === 'GET').length > 1) {
        // 冲突后的 adoptLatest 载入远端最新
        return jsonResponse(remoteDoc(4, body()))
      }
      return jsonResponse(remoteDoc(3, body()))
    }))
    const session = useCanvasDocument(draftId)
    await session.load()
    const local = body({ viewport: { panX: 50, panY: 0, scale: 1 } })
    expect(await session.save(local)).toBe(false)
    expect(session.conflict.value).toBe(true)
    expect(session.document.value?.viewport.panX).toBe(50)
    // 显式采纳最新：远端 revision 4 覆盖本地（用户已确认放弃）
    expect(await session.adoptLatest()).toBe(true)
    expect(session.revision.value).toBe(4)
    expect(session.conflict.value).toBe(false)
  })

  test('GET 失败保留既有状态不覆盖（TC-022/E04）', async () => {
    let failNext = false
    vi.stubGlobal('fetch', vi.fn(async () => {
      if (failNext) {
        return new Response('gateway down', { status: 502 })
      }
      return jsonResponse(remoteDoc(2, body()))
    }))
    const session = useCanvasDocument(draftId)
    await session.load()
    const before = session.document.value
    failNext = true
    expect(await session.adoptLatest()).toBe(false)
    expect(session.error.value).toContain('画布读取失败')
    expect(session.document.value).toBe(before)
    expect(session.revision.value).toBe(2)
  })
})
