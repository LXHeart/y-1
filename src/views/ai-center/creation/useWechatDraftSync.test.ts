// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { defineComponent } from 'vue'
import {
  cancelDraftSync, createDraftSync, fetchDraftSyncCandidates, isSyncActive, listDraftSyncs,
  readDraftSync, reconcileDraftSync, syncStateLabel, useWechatDraftSync,
} from './useWechatDraftSync'

/**
 * 任务书 #101 C101-22：useWechatDraftSync——版本/请求 ID、轮询、unknown 只读核实、
 * 断线/迟到响应/连接轮换（reset 隔离）。
 */

const fetchMock = vi.fn()
beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => vi.unstubAllGlobals())
enableAutoUnmount(afterEach)

function ok(data: unknown): Response {
  return new Response(JSON.stringify({ success: true, data }), { headers: { 'Content-Type': 'application/json' } })
}

function sync(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 'sync-1', requestId: 'req-1', accountId: 'acct-1', draftId: 'draft-1', draftVersion: 3,
    state: 'succeeded', externalDraftMediaId: 'MID-1', payloadHash: 'h', version: 5,
    createdAt: '2026-09-14T00:00:00Z', verifiedAt: '2026-09-14T00:01:00Z', error: null, ...overrides,
  }
}

function harness(draftId: () => string | undefined = () => 'draft-1') {
  const state = useWechatDraftSync(draftId, { pollIntervalMs: 20 })
  mount(defineComponent({ setup: () => () => null }))
  return state
}

describe('useWechatDraftSync', () => {
  test('track 保留 syncId 并轮询到终态；终态停止轮询', async () => {
    let pollCount = 0
    fetchMock.mockImplementation(async (url: string) => {
      if (String(url).endsWith('/draft-syncs/sync-1')) {
        pollCount += 1
        return ok(sync({ state: pollCount >= 2 ? 'succeeded' : 'uploading', version: pollCount }))
      }
      return ok({ items: [sync()], nextCursor: null })
    })
    const state = harness()
    state.track(sync({ state: 'uploading' }) as never)
    expect(state.current.value?.id).toBe('sync-1')
    await vi.waitFor(() => {
      expect(state.current.value?.state).toBe('succeeded')
    }, { timeout: 3_000 })
    expect(pollCount).toBeGreaterThanOrEqual(2)
    // 终态后不再轮询
    await new Promise((resolve) => { setTimeout(resolve, 200) })
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/draft-syncs/sync-1')).length)
      .toBe(pollCount)
    expect(state.isPolling()).toBe(false)
  })

  test('refresh 读回同任务最近同步（刷新恢复语义，TC101-106）', async () => {
    fetchMock.mockResolvedValue(ok({ items: [sync({ state: 'unknown', version: 7 }),
      sync({ state: 'succeeded', version: 5 })], nextCursor: null }))
    const state = harness()
    await state.refresh()
    expect(state.current.value?.state).toBe('unknown')
    expect(state.current.value?.version).toBe(7)
    expect(state.history.value).toHaveLength(2)
  })

  test('reset 停止轮询并清空（账号切换/连接轮换隔离，TC101-108）', async () => {
    fetchMock.mockImplementation(async (url: string) => {
      if (String(url).endsWith('/draft-syncs/sync-1')) return ok(sync({ state: 'verifying' }))
      return ok({ items: [], nextCursor: null })
    })
    const state = harness()
    state.track(sync({ state: 'verifying' }) as never)
    expect(state.isPolling()).toBe(true)
    state.reset()
    expect(state.isPolling()).toBe(false)
    expect(state.current.value).toBeNull()
    await new Promise((resolve) => { setTimeout(resolve, 200) })
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/draft-syncs/sync-1')).length).toBe(0)
  })

  test('迟到响应丢弃：旧轮询返回不覆盖新状态', async () => {
    let finishSlow!: (value: Response) => void
    let reads = 0
    fetchMock.mockImplementation(async (url: string) => {
      if (String(url).endsWith('/draft-syncs/sync-1')) {
        reads += 1
        // 第 1 次（track 排程的轮询）挂起模拟迟到在途；第 2 次直接读返回终态
        if (reads === 1) return new Promise<Response>(resolve => { finishSlow = resolve })
        return ok(sync({ state: 'succeeded', version: 9 }))
      }
      return ok({ items: [sync({ state: 'succeeded', version: 9 })], nextCursor: null })
    })
    const state = harness()
    state.track(sync({ state: 'uploading' }) as never)
    // 等第一轮排程轮询发出（挂起=迟到在途），随后直接读回终态并接管（epoch 推进）
    await new Promise((resolve) => { setTimeout(resolve, 40) })
    const next = await readDraftSync('sync-1')
    state.track(next)
    finishSlow(ok(sync({ state: 'uploading', version: 2 })))
    await flushPromises()
    expect(state.current.value?.state).toBe('succeeded')
    expect(state.current.value?.version).toBe(9)
  })

  test('API 客户端：创建载荷 0/1 评论位、取消/核实/候选端点', async () => {
    fetchMock.mockImplementation(async () => ok(sync()))
    await createDraftSync({
      requestId: 'req-9', accountId: 'acct-1', expectedAccountVersion: 2, draftId: 'd', draftVersion: 3,
      exportId: 'e', needOpenComment: 1, onlyFansCanComment: 0,
    })
    expect(String(fetchMock.mock.calls[0][0])).toBe('/api/creation-channels/wechat/draft-syncs')
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toMatchObject(
      { needOpenComment: 1, onlyFansCanComment: 0, draftVersion: 3 })

    await cancelDraftSync('sync-1', 5, 'req-c')
    expect(String(fetchMock.mock.calls[1][0])).toContain('/cancel')
    expect(JSON.parse(String(fetchMock.mock.calls[1][1]?.body))).toMatchObject({ expectedVersion: 5 })

    await reconcileDraftSync('sync-1', 6, 'MID-9', 'req-r')
    expect(String(fetchMock.mock.calls[2][0])).toContain('/reconcile')
    expect(JSON.parse(String(fetchMock.mock.calls[2][1]?.body))).toMatchObject(
      { externalDraftMediaId: 'MID-9' })

    await fetchDraftSyncCandidates('sync-1')
    expect(String(fetchMock.mock.calls[3][0])).toContain('/candidates')

    await listDraftSyncs('draft-1')
    expect(String(fetchMock.mock.calls[4][0])).toContain('draftId=draft-1')
  })

  test('状态文案：成功只说草稿箱，不出现「已发布」；活动态判定', () => {
    expect(syncStateLabel('succeeded')).toBe('已存入草稿箱')
    for (const state of ['preparing', 'uploading', 'submitting', 'verifying'] as const) {
      expect(isSyncActive(state)).toBe(true)
    }
    for (const state of ['succeeded', 'failed', 'unknown', 'cancelled'] as const) {
      expect(isSyncActive(state)).toBe(false)
    }
    // 全部状态文案不含公开发布暗示
    for (const state of ['preparing', 'uploading', 'submitting', 'verifying', 'succeeded', 'failed',
      'unknown', 'cancelled'] as const) {
      expect(syncStateLabel(state)).not.toContain('发布')
    }
  })
})
