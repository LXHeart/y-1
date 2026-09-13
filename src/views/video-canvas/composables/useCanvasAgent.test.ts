import { afterEach, describe, expect, test, vi } from 'vitest'
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
    expiresAt: new Date(Date.now() + 1800000).toISOString(), ...overrides,
  }
}

function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}

const sessions: Array<ReturnType<typeof useCanvasAgent>> = []
afterEach(() => { sessions.splice(0).forEach(session => session.deactivate()); vi.useRealTimers(); vi.unstubAllGlobals() })
const input = { selectedNodeIds: ['shot:1'], expectedEditVersion: 1, expectedCanvasRevision: 1, instruction: '原始指令' }

function setup(epochValue: number | string = 1) {
  const epoch = ref<number | string>(epochValue)
  const onApplied = vi.fn()
  const session = useCanvasAgent({
    draftId: ref('draft-1'),
    storyboardId: ref('sb-1'),
    epoch,
    onApplied,
  })
  sessions.push(session)
  return { session, epoch, onApplied }
}

describe('#100 C100-18：useCanvasAgent', () => {
  test('unknown results retain the full original request; changed input cannot reuse its operation key', async () => {
    const calls: Array<Record<string, unknown>> = []
    vi.stubGlobal('fetch', vi.fn(async (_url: unknown, init?: RequestInit) => {
      calls.push(JSON.parse(String(init?.body)))
      if (calls.length === 1) throw new TypeError('network lost')
      return jsonResponse(plan())
    }))
    const { session } = setup()
    expect(await session.submit(input)).toBe(false)
    expect(await session.submit({ ...input, instruction: '新的指令', expectedEditVersion: 2 })).toBe(false)
    expect(calls).toHaveLength(1); expect(session.canRetryPending.value).toBe(true)
    expect(await session.retryPending()).toBe(true)
    expect(calls[1]).toEqual(calls[0])
    await session.submit({ ...input, instruction: '新的指令', expectedEditVersion: 2 })
    expect(calls[2]?.operationId).not.toBe(calls[0]?.operationId)
    expect(calls[2]?.instruction).toBe('新的指令')
  })

  test('late POST cannot populate another generation, stop its busy state or restart polling', async () => {
    vi.useFakeTimers()
    const replies: Array<(value: Response) => void> = []
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => replies.push(resolve))))
    const { session, epoch } = setup()
    const first = session.submit(input); epoch.value = 'B'
    const second = session.submit({ ...input, instruction: 'B request' })
    replies[0]!(jsonResponse(plan({ status: 'preparing' }), 202)); await first
    expect(session.plan.value).toBeNull(); expect(session.submitting.value).toBe(true)
    replies[1]!(jsonResponse(plan({ id: 'B-plan' }))); await second
    expect(session.plan.value?.id).toBe('B-plan'); expect(vi.getTimerCount()).toBe(0)
  })

  test('deactivation preserves unknown request for explicit retry and ignores its late response', async () => {
    let finish!: (response: Response) => void
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn((_url: unknown, init?: RequestInit) => {
      calls.push(String(init?.body))
      return calls.length === 1 ? new Promise<Response>(resolve => { finish = resolve }) : Promise.resolve(jsonResponse(plan()))
    }))
    const { session } = setup(); const pending = session.submit(input)
    session.deactivate(); finish(jsonResponse(plan({ status: 'preparing' }), 202)); await pending
    expect(session.plan.value).toBeNull(); expect(session.canRetryPending.value).toBe(true)
    session.activate(); expect(calls).toHaveLength(1)
    await session.retryPending(); expect(calls[1]).toBe(calls[0]); expect(session.plan.value?.status).toBe('ready')
  })

  test('GET retries exactly twice for transient errors and stops when the page deactivates', async () => {
    vi.useFakeTimers(); const { session } = setup(); session.plan.value = plan()
    let count = 0
    vi.stubGlobal('fetch', vi.fn(async () => { count++; return new Response('{"error":"暂时不可用"}', { status: 503 }) }))
    const refresh = session.refreshPlan()
    await vi.advanceTimersByTimeAsync(3000); expect(await refresh).toBe(false); expect(count).toBe(3)
    const stopped = session.refreshPlan(); await vi.advanceTimersByTimeAsync(0)
    session.deactivate(); await stopped; await vi.advanceTimersByTimeAsync(10000)
    expect(count).toBe(4); expect(vi.getTimerCount()).toBe(0)
  })

  test.each([401, 404, 409])('GET %s does not retry; private state clears only for authorization loss', async status => {
    const request = vi.fn(async () => new Response('{"error":"拒绝","code":"CANVAS_RESOURCE_LOCKED"}', { status }))
    vi.stubGlobal('fetch', request)
    const { session } = setup(); session.plan.value = plan(); await session.refreshPlan()
    expect(request).toHaveBeenCalledTimes(1)
    expect(session.plan.value === null).toBe(status !== 409)
    expect(session.errorCode.value).toBe('CANVAS_RESOURCE_LOCKED')
  })

  test('preparing polling stops at 120 seconds and cannot create another model request', async () => {
    vi.useFakeTimers(); const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (_url: unknown, init?: RequestInit) => {
      calls.push(init?.method ?? 'GET'); return jsonResponse(plan({ status: 'preparing' }), 202)
    }))
    const { session } = setup(); await session.submit(input)
    await vi.advanceTimersByTimeAsync(120000)
    expect(session.errorCode.value).toBe('CANVAS_AGENT_TIMEOUT'); expect(vi.getTimerCount()).toBe(0)
    expect(calls.filter(method => method === 'POST')).toHaveLength(1)
    expect(calls.filter(method => method === 'GET')).toHaveLength(59)
  })

  test('late apply does not navigate; explicit recovery dispatches the saved result once', async () => {
    let finish!: (response: Response) => void; let applyCalls = 0
    const result = { planId: 'plan-1', draftId: 'draft-1', storyboardId: 'sb-1', editVersion: 2, affectedShotIds: [], variant: null, preparedGeneration: null }
    vi.stubGlobal('fetch', vi.fn(async (url: unknown) => {
      if (String(url).endsWith('/apply')) {
        applyCalls++
        return applyCalls === 1 ? new Promise<Response>(resolve => { finish = resolve }) : jsonResponse(result)
      }
      return jsonResponse(plan({ status: 'applied' }))
    }))
    const { session, onApplied } = setup(); session.plan.value = plan()
    const pending = session.apply(); session.deactivate(); finish(jsonResponse(result)); await pending
    expect(onApplied).not.toHaveBeenCalled(); expect(session.applyUnknown.value).toBe(true)
    session.activate(); await vi.waitFor(() => expect(session.plan.value?.status).toBe('applied'))
    expect(await session.recoverApply()).toBe(true); expect(onApplied).toHaveBeenCalledOnce()
    expect(await session.recoverApply()).toBe(false); expect(applyCalls).toBe(2)
  })
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
