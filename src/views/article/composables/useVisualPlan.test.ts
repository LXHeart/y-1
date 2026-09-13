import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { effectScope } from 'vue'
import { useVisualPlan, defaultSelectedBlockIds } from './useVisualPlan'
import type { SourceBlock, VisualPlan, VisualPlanDocument } from '../../../types/creation-studio'

/**
 * 任务书 #101 C101-06（TC101-026~029）：计划保存队列、迟到响应丢弃、身份不变排序、
 * 封面规则与确认编排。fetch 全 mock，零网络。
 */

const fetchMock = vi.fn()

function json(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function makeDocument(overrides: Partial<VisualPlanDocument> = {}): VisualPlanDocument {
  return {
    recipe: { id: 'social-card-series', version: '1.0.0' },
    strategy: 'information',
    style: { styleId: 's1', layoutId: 'l1', paletteId: 'p1' },
    items: [
      { itemId: 'item-1', cardId: 'card-1', position: 1, role: 'cover', title: '封面', bullets: [], caption: '', purpose: '', illustration: '', sourceBlockIds: ['b1'], criticalText: ['68 元'], layoutId: 'l1', targetAspect: '3:4', placement: null, inputMediaRef: null },
      { itemId: 'item-2', cardId: 'card-2', position: 2, role: 'content', title: '内容', bullets: [], caption: '', purpose: '', illustration: '', sourceBlockIds: ['b2'], criticalText: [], layoutId: 'l2', targetAspect: '3:4', placement: null, inputMediaRef: null },
    ],
    explanation: '一套推荐',
    uncoveredBlockIds: [],
    ...overrides,
  }
}

function makePlan(overrides: Partial<VisualPlan> = {}): VisualPlan {
  return {
    id: 'plan-1', draftId: 'draft-1', status: 'ready', revision: 1, confirmedRevision: null,
    source: { id: 'source-1', contentHash: 'a'.repeat(64) }, baseDraftVersion: 3,
    baseContentHash: 'b'.repeat(64), stale: false, document: makeDocument(),
    runId: null, error: null, createdAt: '2026-09-13T00:00:00Z',
    ...overrides,
  }
}

function setup(overrides: Partial<Parameters<typeof useVisualPlan>[0]> = {}) {
  const state = { draftId: 'draft-1', draftVersion: 3, sourceDocumentId: 'source-1', sourceContentHash: 'a'.repeat(64) }
  const scope = effectScope()
  const onPlanCreated = vi.fn()
  const controller = scope.run(() => useVisualPlan({
    draftId: () => state.draftId,
    draftVersion: () => state.draftVersion,
    sourceDocumentId: () => state.sourceDocumentId,
    sourceContentHash: () => state.sourceContentHash,
    recipe: () => ({ id: 'social-card-series', version: '1.0.0' }),
    onPlanCreated,
    ...overrides,
  }))!
  return { controller, state, scope, onPlanCreated }
}

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('useVisualPlan：prepare（API101-08）', () => {
  test('ready 直落；onPlanCreated 收到计划；同意图重试沿用同一 requestId', async () => {
    const { controller, onPlanCreated } = setup()
    fetchMock.mockResolvedValueOnce(json({ success: true, data: makePlan() }))
    await controller.prepare()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const firstBody = JSON.parse(fetchMock.mock.calls[0][1].body as string)
    expect(firstBody.recipe).toEqual({ id: 'social-card-series', version: '1.0.0' })
    expect(firstBody.selectedBlockIds).toEqual([])
    expect(onPlanCreated).toHaveBeenCalledTimes(1)

    // 无响应后同意图重试：requestId 不变（安全重放）
    fetchMock.mockResolvedValueOnce(json({ success: true, data: makePlan() }))
    await controller.prepare()
    const retryBody = JSON.parse(fetchMock.mock.calls[1][1].body as string)
    expect(retryBody.requestId).toBe(firstBody.requestId)
  })

  test('202 preparing：2s 轮询 GET 直到终态；轮询不写草稿引用', async () => {
    const { controller, onPlanCreated } = setup()
    fetchMock.mockResolvedValueOnce(json({ success: true, data: makePlan({ status: 'preparing', document: null }) }, 202))
    fetchMock.mockResolvedValueOnce(json({ success: true, data: makePlan({ status: 'ready' }) }))
    await controller.prepare()
    expect(onPlanCreated).toHaveBeenCalledTimes(1) // 创建即落引用（引用只在新建时变化）
    await vi.advanceTimersByTimeAsync(2100)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls[1][0]).toBe('/api/creation-studio/visual-plans/plan-1')
    expect(controller.current.value?.status).toBe('ready')
  })
})

describe('useVisualPlan：编辑队列与 PATCH（API101-10）', () => {
  test('touch 后 800ms 触发 PATCH，expectedRevision 取当前修订；成功推进 revision', async () => {
    const { controller } = setup()
    fetchMock.mockResolvedValueOnce(json({ success: true, data: makePlan() }))
    await controller.prepare()
    fetchMock.mockClear()

    controller.document.value!.items[1].title = '改过的标题'
    controller.touch()
    expect(controller.dirty.value).toBe(true)
    fetchMock.mockResolvedValueOnce(json({ success: true, data: makePlan({ revision: 2 }) }))
    await vi.advanceTimersByTimeAsync(900)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/creation-studio/visual-plans/plan-1')
    expect(init.method).toBe('PATCH')
    const body = JSON.parse(init.body as string)
    expect(body.expectedRevision).toBe(1)
    expect(body.document.items[1].title).toBe('改过的标题')
  })

  test('迟到 PATCH 响应不覆盖保存期间的新编辑（TC101-027）', async () => {
    const { controller } = setup()
    fetchMock.mockResolvedValueOnce(json({ success: true, data: makePlan() }))
    await controller.prepare()
    fetchMock.mockClear()

    controller.document.value!.items[1].title = '第一次编辑'
    controller.touch()
    // PATCH 挂起（响应未回）：用户在保存期间继续编辑
    let resolvePatch: (value: Response) => void = () => {}
    fetchMock.mockImplementationOnce(() => new Promise<Response>((resolve) => { resolvePatch = resolve }))
    const flushPromise = controller.flush()
    controller.document.value!.items[1].title = '第二次编辑'
    resolvePatch(json({ success: true, data: makePlan({ revision: 2 }) }))
    await flushPromise
    // 新编辑保留，不回退到服务器旧文档
    expect(controller.document.value?.items[1].title).toBe('第二次编辑')
    expect(controller.dirty.value).toBe(true)
  })

  test('409 版本冲突：保留本地编辑并报错，不追加修订', async () => {
    const { controller } = setup()
    fetchMock.mockResolvedValueOnce(json({ success: true, data: makePlan() }))
    await controller.prepare()
    fetchMock.mockClear()

    controller.document.value!.items[0].title = '本地改'
    fetchMock.mockResolvedValueOnce(json({ success: false, error: '计划版本冲突，请刷新后重试' }, 409))
    const ok = await controller.flush()
    expect(ok).toBe(false)
    expect(controller.error.value).toContain('计划版本冲突')
    expect(controller.document.value?.items[0].title).toBe('本地改')
  })
})

describe('useVisualPlan：confirm（API101-11）', () => {
  test('先 flush 再确认；sourceContentHash 用计划自身来源 hash', async () => {
    const { controller } = setup()
    fetchMock.mockResolvedValueOnce(json({ success: true, data: makePlan() }))
    await controller.prepare()
    fetchMock.mockClear()

    controller.document.value!.items[0].title = '待保存编辑'
    fetchMock.mockResolvedValueOnce(json({ success: true, data: makePlan({ revision: 2, document: makeDocument() }) }))
    fetchMock.mockResolvedValueOnce(json({ success: true, data: makePlan({ revision: 2, confirmedRevision: 2 }) }))
    const ok = await controller.confirm()

    expect(ok).toBe(true)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    const confirmCall = fetchMock.mock.calls[1]
    expect(confirmCall[0]).toBe('/api/creation-studio/visual-plans/plan-1/confirm')
    const body = JSON.parse(confirmCall[1].body as string)
    expect(body.sourceContentHash).toBe('a'.repeat(64))
    expect(body.expectedRevision).toBe(2)
    expect(controller.current.value?.confirmedRevision).toBe(2)
  })
})

describe('useVisualPlan：条目操作（TC101-026）', () => {
  test('上移/下移：顺序变化、身份不变、position 连续重排', async () => {
    const { controller } = setup()
    fetchMock.mockResolvedValueOnce(json({ success: true, data: makePlan() }))
    await controller.prepare()

    controller.moveItem(1, -1)
    const items = controller.document.value!.items
    expect(items[0].itemId).toBe('item-2')
    expect(items[1].itemId).toBe('item-1')
    expect(items.map((item) => item.position)).toEqual([1, 2])
  })

  test('删除封面被拒并提示；删除非封面成功', async () => {
    const { controller } = setup()
    fetchMock.mockResolvedValueOnce(json({ success: true, data: makePlan() }))
    await controller.prepare()

    expect(controller.removeItem(0)).toContain('设为封面')
    expect(controller.document.value!.items).toHaveLength(2)

    expect(controller.removeItem(1)).toBeNull()
    expect(controller.document.value!.items).toHaveLength(1)
  })

  test('设为封面：目标升 cover 移到首位，原封面降级，身份不变', async () => {
    const { controller } = setup()
    fetchMock.mockResolvedValueOnce(json({ success: true, data: makePlan() }))
    await controller.prepare()

    controller.promoteToCover(1)
    const items = controller.document.value!.items
    expect(items[0].itemId).toBe('item-2')
    expect(items[0].role).toBe('cover')
    expect(items[1].role).toBe('content')
    expect(items[1].itemId).toBe('item-1')
  })
})

describe('useVisualPlan：迟到响应与生命周期（TC101-029）', () => {
  test('切换/卸载后（epoch 变化）迟到响应不落地', async () => {
    const { controller, scope } = setup()
    let resolvePrepare: (value: Response) => void = () => {}
    fetchMock.mockImplementationOnce(() => new Promise<Response>((resolve) => { resolvePrepare = resolve }))
    void controller.prepare()
    scope.stop() // 卸载：epoch 递增
    resolvePrepare(json({ success: true, data: makePlan() }))
    await vi.advanceTimersByTimeAsync(0)
    expect(controller.current.value).toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  test('卸载清理防抖与轮询计时器', async () => {
    const { controller, scope } = setup()
    fetchMock.mockResolvedValueOnce(json({ success: true, data: makePlan() }))
    await controller.prepare()
    fetchMock.mockClear()

    controller.document.value!.items[0].title = '待保存'
    controller.touch()
    scope.stop()
    await vi.advanceTimersByTimeAsync(2000)
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('defaultSelectedBlockIds（§5.1 AI 输入选择缺省）', () => {
  const block = (id: string, length: number): SourceBlock => ({
    id, kind: 'paragraph', position: 1, startCodePoint: 0, endCodePoint: length,
    text: 'x'.repeat(length), textHash: '',
  })

  test('按顺序取块，合计 8,000 code point 截止', () => {
    const blocks = [block('b1', 4000), block('b2', 3000), block('b3', 2000), block('b4', 100)]
    expect(defaultSelectedBlockIds(blocks)).toEqual(['b1', 'b2'])
  })

  test('首块即超限仍保留至少一块；至多 200 块', () => {
    expect(defaultSelectedBlockIds([block('b1', 9000)])).toEqual(['b1'])
    const many = Array.from({ length: 250 }, (_, index) => block(`b${index}`, 10))
    expect(defaultSelectedBlockIds(many)).toHaveLength(200)
  })
})
