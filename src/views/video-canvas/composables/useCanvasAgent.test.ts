import { describe, expect, test, vi } from 'vitest'
import { ref } from 'vue'
import { useCanvasAgent } from './useCanvasAgent'
import type { CanvasPlanResult } from '../../../types/video-canvas'

/**
 * 任务书 #100 C100-18：计划会话（超时/重放/账号切换 epoch）。
 */

function plan(overrides: Partial<CanvasPlanResult> = {}): CanvasPlanResult {
  return {
    id: 'plan-1', status: 'ready', draftId: 'draft-1', storyboardId: 'sb-1',
    baseDraftVersion: 1, baseEditVersion: 1, baseCanvasRevision: 1,
    summary: '', clarification: null, action: null, runId: 'run-1', errorCode: null,
    expiresAt: '2026-09-12T01:00:00Z', ...overrides,
  }
}

function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}

function setup(epochValue: number | string = 1) {
  const epoch = ref<number | string>(epochValue)
  const onApplied = vi.fn()
  const session = useCanvasAgent({
    draftId: ref('draft-1'),
    storyboardId: ref('sb-1'),
    epoch,
    onApplied,
  })
  return { session, epoch, onApplied }
}

describe('#100 C100-18：useCanvasAgent', () => {
  test('提交通道：同键重试（丢响应不换键）；202 preparing 后轮询到 ready', async () => {
    const calls: Array<Record<string, unknown>> = []
    let respondPreparing = false
    vi.stubGlobal('fetch', vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
      const text = String(url)
      if (text.endsWith('/canvas/plans') && (init?.method ?? 'GET') === 'POST') {
        calls.push(JSON.parse(String(init?.body)))
        if (respondPreparing) {
          return jsonResponse(plan({ status: 'preparing' }), 202)
        }
        return jsonResponse(plan())
      }
      return jsonResponse(plan())
    }))
    const { session } = setup()
    await session.submit({ selectedNodeIds: ['shot:1'], expectedEditVersion: 1,
      expectedCanvasRevision: 1, instruction: '改一下' })
    expect(session.plan.value?.status).toBe('ready')
    expect(calls).toHaveLength(1)

    // 模拟 202 preparing：重试路径（pending 键）
    respondPreparing = true
    await session.retryPending({ selectedNodeIds: ['shot:1'], expectedEditVersion: 1,
      expectedCanvasRevision: 1, instruction: '改一下' })
    expect(session.plan.value?.status).toBe('preparing')
    session.stopPolling()
  })

  test('apply 409 保留计划提示重新提问；成功标记已应用并刷新（TC-038 界面语义）', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: RequestInfo | URL) => {
      const text = String(url)
      if (text.endsWith('/apply')) {
        return new Response(JSON.stringify({ success: false, error: '画布已变化' }), { status: 409 })
      }
      return jsonResponse(plan())
    }))
    const { session, onApplied } = setup()
    await session.submit({ selectedNodeIds: ['shot:1'], expectedEditVersion: 1,
      expectedCanvasRevision: 1, instruction: 'x' })
    expect(await session.apply()).toBe(false)
    expect(session.plan.value?.status).toBe('ready') // 保留
    expect(session.error.value).toContain('重新提问')
    expect(onApplied).not.toHaveBeenCalled()

    // 成功路径
    vi.stubGlobal('fetch', vi.fn(async (url: RequestInfo | URL) =>
      String(url).endsWith('/apply')
        ? jsonResponse({ planId: 'plan-1', storyboardId: 'sb-1', draftId: 'draft-1', editVersion: 2,
          affectedShotIds: ['shot-1'], variant: null, preparedGeneration: null })
        : jsonResponse(plan())))
    await session.submit({ selectedNodeIds: ['shot:1'], expectedEditVersion: 1,
      expectedCanvasRevision: 1, instruction: 'x' })
    expect(await session.apply()).toBe(true)
    expect(session.plan.value?.status).toBe('applied')
    expect(onApplied).toHaveBeenCalledOnce()
  })

  test('epoch（账号/项目）变化丢弃旧计划并停止轮询', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(plan({ status: 'preparing' }), 202)))
    const { session, epoch } = setup()
    await session.submit({ selectedNodeIds: ['shot:1'], expectedEditVersion: 1,
      expectedCanvasRevision: 1, instruction: 'x' })
    expect(session.plan.value).not.toBeNull()
    epoch.value = 'another-account'
    expect(session.plan.value).toBeNull()
    expect(session.error.value).toBe('')
  })
})
