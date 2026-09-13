// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { effectScope } from 'vue'
import { useVisualJob } from './useVisualJob'
import type { VisualJob, VisualPlan, VisualQuote } from '../../../types/creation-studio'

/**
 * 任务书 #101 C101-11（TC101-052/053/055）：视觉任务客户端——轮询节奏（2s→5s、暂停恢复、
 * 30min 停止）、迟到响应 epoch 丢弃（账号/页面切换）、unknown 重做需 ack、错误不假报。
 */

function makePlan(): VisualPlan {
  return {
    id: 'plan-1', draftId: 'draft-1', status: 'ready', revision: 1, confirmedRevision: 1,
    source: { id: 'source-1', contentHash: 'a'.repeat(64) }, baseDraftVersion: 3,
    baseContentHash: 'b'.repeat(64), stale: false, document: {
      recipe: { id: 'social-card-series', version: '1.0.0' }, strategy: 'information',
      style: { styleId: 's', layoutId: 'l', paletteId: 'p' },
      items: [
        { itemId: 'item-1', cardId: 'c1', position: 1, role: 'cover', title: '封面', bullets: [], caption: '', purpose: '', illustration: '', sourceBlockIds: [], criticalText: [], layoutId: 'l', targetAspect: '3:4', placement: null, inputMediaRef: null },
        { itemId: 'item-2', cardId: 'c2', position: 2, role: 'content', title: '内页', bullets: [], caption: '', purpose: '', illustration: '', sourceBlockIds: [], criticalText: [], layoutId: 'l', targetAspect: '3:4', placement: null, inputMediaRef: null },
      ],
      explanation: '', uncoveredBlockIds: [],
    },
    runId: null, error: null, createdAt: '2026-09-13T00:00:00Z',
  }
}

function makeJob(overrides: Partial<VisualJob> = {}): VisualJob {
  return {
    id: 'job-1', requestId: 'req-1', draftId: 'draft-1', plan: { id: 'plan-1', revision: 1 },
    state: 'running', version: 1,
    items: [
      { attemptId: 'a1', itemId: 'item-1', position: 1, state: 'succeeded', runId: 'r1',
        artifact: { id: 'art-1', itemId: 'item-1', attemptId: 'a1', plan: { id: 'plan-1', revision: 1 }, originalMediaRef: { id: 'm-orig', refType: 'media' }, deliveryMediaRef: { id: 'm-del', refType: 'media' }, runId: 'r1', width: 1080, height: 1440, contentHash: 'h1', anchorArtifactId: null, createdAt: '2026-09-13T00:00:00Z' }, error: null },
      { attemptId: 'a2', itemId: 'item-2', position: 2, state: 'dispatching', runId: null, artifact: null, error: null },
    ],
    cancelRequested: false, quoteId: 'quote-1', createdAt: '2026-09-13T00:00:00Z', updatedAt: '2026-09-13T00:00:00Z',
    ...overrides,
  }
}

function makeQuote(): VisualQuote {
  return {
    id: 'quote-1', plan: { id: 'plan-1', revision: 1 }, selectedItemIds: ['item-1'], imageCalls: 1,
    consistencyMode: 'prompt-only', anchorArtifactId: null, userCredits: 0, platformBudgetCents: 30,
    billingSource: 'platform', pricingVersion: 'v1', configurationFingerprint: 'f',
    expiresAt: '2026-09-13T00:02:00Z', warnings: [],
  }
}

type FetchMock = ReturnType<typeof vi.fn>

function ok(data: unknown): Response {
  return new Response(JSON.stringify({ success: true, data }), { status: 200 })
}

function fail(status: number, error: string): Response {
  return new Response(JSON.stringify({ success: false, error }), { status })
}

/** 按路径路由的 fetch 桩：handler 可同步返回 Response 或挂起 Promise（迟到响应用）。 */
function stubFetch(handler: (url: string, init?: RequestInit) => Response | Promise<Response>): FetchMock {
  const mock = vi.fn(async (url: string, init?: RequestInit) => handler(url, init))
  vi.stubGlobal('fetch', mock)
  return mock
}

function setup(plan: VisualPlan) {
  const scope = effectScope()
  const controller = scope.run(() => useVisualJob({ plan: () => plan }))!
  return { scope, controller }
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('useVisualJob 创建与同键幂等', () => {
  test('create 成功后开始轮询，终态自动停止', async () => {
    const plan = makePlan()
    let pollCount = 0
    const fetchMock = stubFetch((url) => {
      if (url === '/api/creation-studio/visual-jobs' ) return ok(makeJob())
      if (url === '/api/creation-studio/visual-jobs/job-1') {
        pollCount += 1
        return ok(pollCount >= 2 ? makeJob({ state: 'succeeded' }) : makeJob())
      }
      return fail(404, 'not found')
    })
    const { controller } = setup(plan)
    const job = await controller.create({ selectedItemIds: ['item-1'], quoteId: 'quote-1' })
    expect(job?.id).toBe('job-1')
    expect(controller.polling.value).toBe(true)
    await vi.advanceTimersByTimeAsync(2000)
    await vi.advanceTimersByTimeAsync(2000)
    expect(pollCount).toBe(2)
    expect(controller.polling.value).toBe(false)
    expect(controller.current.value?.state).toBe('succeeded')
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  test('无响应重试沿用同 requestId（同键幂等），换参数换新键', async () => {
    const plan = makePlan()
    const requestIds: string[] = []
    stubFetch((url, init) => {
      if (url === '/api/creation-studio/visual-jobs') {
        requestIds.push(JSON.parse(String(init?.body)).requestId)
        return fail(500, '网络错误')
      }
      return fail(404, 'not found')
    })
    const { controller } = setup(plan)
    await controller.create({ selectedItemIds: ['item-1'], quoteId: 'quote-1' })
    await controller.create({ selectedItemIds: ['item-1'], quoteId: 'quote-1' })
    expect(requestIds).toHaveLength(2)
    expect(requestIds[0]).toBe(requestIds[1])
    await controller.create({ selectedItemIds: ['item-1', 'item-2'], quoteId: 'quote-2' })
    expect(requestIds[2]).not.toBe(requestIds[0])
  })

  test('create 请求体携带 acknowledgedUnknownAttemptIds（unknown 重做确认闸）', async () => {
    const plan = makePlan()
    const captured: { body: Record<string, unknown> | null } = { body: null }
    stubFetch((url, init) => {
      if (url === '/api/creation-studio/visual-jobs') {
        captured.body = JSON.parse(String(init?.body))
        return ok(makeJob())
      }
      return fail(404, 'not found')
    })
    const { controller } = setup(plan)
    await controller.create({
      selectedItemIds: ['item-1'], quoteId: 'quote-1', acknowledgedUnknownAttemptIds: ['old-attempt'],
    })
    expect(captured.body?.acknowledgedUnknownAttemptIds).toEqual(['old-attempt'])
  })
})

describe('useVisualJob 轮询策略（TC101-053）', () => {
  test('60s 后降频到 5s；隐藏暂停、resume 立即读取', async () => {
    const plan = makePlan()
    let polls: number[] = []
    stubFetch((url) => {
      if (url.endsWith('/visual-jobs/job-1')) {
        polls.push(Date.now())
        return ok(makeJob())
      }
      if (url === '/api/creation-studio/visual-jobs') return ok(makeJob())
      return fail(404, 'not found')
    })
    const { controller } = setup(plan)
    await controller.create({ selectedItemIds: ['item-1'], quoteId: 'quote-1' })
    // 快档：前 60s 每 2s
    await vi.advanceTimersByTimeAsync(60_000)
    const fastCount = polls.length
    expect(fastCount).toBeGreaterThanOrEqual(28)
    // 慢档：60s 后每 5s
    polls = []
    await vi.advanceTimersByTimeAsync(20_000)
    expect(polls.length).toBe(4)
    // 隐藏暂停：不再发请求
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(true)
    polls = []
    await vi.advanceTimersByTimeAsync(10_000)
    expect(polls.length).toBe(0)
    // 恢复可见 + resume：立即读取
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
    controller.resume()
    await vi.advanceTimersByTimeAsync(0)
    expect(polls.length).toBe(1)
  })

  test('pause 停止轮询且不再读任务（离开页面）', async () => {
    const plan = makePlan()
    let pollCount = 0
    stubFetch((url) => {
      if (url.endsWith('/visual-jobs/job-1')) {
        pollCount += 1
        return ok(makeJob())
      }
      if (url === '/api/creation-studio/visual-jobs') return ok(makeJob())
      return fail(404, 'not found')
    })
    const { controller } = setup(plan)
    await controller.create({ selectedItemIds: ['item-1'], quoteId: 'quote-1' })
    controller.pause()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(pollCount).toBe(0)
    expect(controller.polling.value).toBe(false)
  })
})

describe('useVisualJob 账号/页面切换与迟到响应', () => {
  test('dismiss 后迟到响应不落地（epoch 丢弃）', async () => {
    const plan = makePlan()
    const deferred: { resolve: ((response: Response) => void) | null } = { resolve: null }
    stubFetch((url) => {
      if (url === '/api/creation-studio/visual-jobs') {
        return new Promise<Response>((resolve) => { deferred.resolve = resolve })
      }
      return fail(404, 'not found')
    })
    const { controller } = setup(plan)
    const pending = controller.create({ selectedItemIds: ['item-1'], quoteId: 'quote-1' })
    await vi.advanceTimersByTimeAsync(1)
    // 用户切换账号：dismiss 清空当前会话
    controller.dismiss()
    deferred.resolve?.(ok(makeJob()))
    const job = await pending
    expect(job).toBeNull()
    expect(controller.current.value).toBeNull()
  })

  test('restore 按 studio.activeVisualJobId 读回并继续轮询', async () => {
    const plan = makePlan()
    let pollCount = 0
    stubFetch((url) => {
      if (url.endsWith('/visual-jobs/job-9')) {
        pollCount += 1
        return ok(makeJob({ id: 'job-9', state: pollCount >= 2 ? 'partial' : 'running' }))
      }
      return fail(404, 'not found')
    })
    const { controller } = setup(plan)
    await controller.restore('job-9')
    expect(controller.current.value?.id).toBe('job-9')
    expect(controller.polling.value).toBe(true)
    await vi.advanceTimersByTimeAsync(5000)
    expect(controller.current.value?.state).toBe('partial')
  })
})

describe('useVisualJob 错误与选择（TC101-055 / TC101-054）', () => {
  test('请求失败落 error，不假报任务成功', async () => {
    const plan = makePlan()
    stubFetch(() => fail(409, 'STUDIO_PLAN_STALE: 计划未确认或已变更，请重新确认'))
    const { controller } = setup(plan)
    const job = await controller.create({ selectedItemIds: ['item-1'], quoteId: 'quote-1' })
    expect(job).toBeNull()
    expect(controller.current.value).toBeNull()
    expect(controller.error.value).toContain('计划未确认')
    expect(controller.polling.value).toBe(false)
  })

  test('selectCandidate 只记录预选引用，不标记已保存', async () => {
    const plan = makePlan()
    const { controller } = setup(plan)
    controller.selectCandidate('item-1', 'art-1')
    expect(controller.selectedCandidate.value).toEqual({ itemId: 'item-1', artifactId: 'art-1' })
  })

  test('redo 用新 quote 新键（显式范围重做）', async () => {
    const plan = makePlan()
    const createBodies: Record<string, unknown>[] = []
    stubFetch((url, init) => {
      if (url === '/api/creation-studio/visual-plans/plan-1/estimate') return ok(makeQuote())
      if (url === '/api/creation-studio/visual-jobs') {
        createBodies.push(JSON.parse(String(init?.body)))
        return ok(makeJob())
      }
      return fail(404, 'not found')
    })
    const { controller } = setup(plan)
    await controller.create({ selectedItemIds: ['item-1'], quoteId: 'quote-1' })
    const previousRequestId = createBodies[0].requestId
    await controller.redo(['item-1'], ['old-unknown-attempt'])
    expect(createBodies).toHaveLength(2)
    expect(createBodies[1].requestId).not.toBe(previousRequestId)
    expect(createBodies[1].quoteId).toBe('quote-1')
    expect(createBodies[1].acknowledgedUnknownAttemptIds).toEqual(['old-unknown-attempt'])
  })

  test('estimate 无 plan 时拒绝（计划先行）', async () => {
    const fetchMock = stubFetch(() => fail(404, 'not found'))
    const scope = effectScope()
    const controller = scope.run(() => useVisualJob({ plan: () => null }))!
    const quote = await controller.estimate(['item-1'])
    expect(quote).toBeNull()
    expect(fetchMock).not.toHaveBeenCalled()
    scope.stop()
  })
})
