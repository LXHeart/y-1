import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { consumeImageAnalysisStream, useImageAnalysis } from './useImageAnalysis'
import type { ImageAnalysisStreamEvent } from '../types/image-analysis'

function createSseResponse(events: Array<Record<string, unknown>>): Response {
  return new Response(new ReadableStream({
    start(controller) {
      const lines = events.flatMap((event) => [`data: ${JSON.stringify(event)}`, ''])
      lines.push('data: [DONE]', '')
      controller.enqueue(new TextEncoder().encode(lines.join('\n')))
      controller.close()
    },
  }), {
    headers: {
      'Content-Type': 'text/event-stream',
    },
  })
}

function createControlledSseResponse() {
  let streamController: ReadableStreamDefaultController<Uint8Array> | null = null
  const response = new Response(new ReadableStream({
    start(controller) {
      streamController = controller
    },
  }), {
    headers: {
      'Content-Type': 'text/event-stream',
    },
  })

  return {
    response,
    pushEvent(event: Record<string, unknown>) {
      streamController?.enqueue(new TextEncoder().encode(`data: ${JSON.stringify(event)}\n\n`))
    },
    finish() {
      streamController?.enqueue(new TextEncoder().encode('data: [DONE]\n\n'))
      streamController?.close()
    },
  }
}

async function flushStreamUpdates(): Promise<void> {
  await Promise.resolve()
  await Promise.resolve()
}

function createDeferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })

  return { promise, resolve, reject }
}

describe('consumeImageAnalysisStream', () => {
  it('returns completed when the stream reaches [DONE]', async () => {
    const events: ImageAnalysisStreamEvent[] = []
    const response = new Response(new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode([
          `data: ${JSON.stringify({ type: 'progress', stage: 'prepare', message: '准备中' })}`,
          '',
          `data: ${JSON.stringify({ type: 'result', data: { review: 'ok', imageCount: 1 } })}`,
          '',
          'data: [DONE]',
          '',
        ].join('\n')))
        controller.close()
      },
    }))

    const state = await consumeImageAnalysisStream(response, (event) => {
      events.push(event)
    })

    expect(state).toBe('completed')
    expect(events).toEqual([
      { type: 'progress', stage: 'prepare', message: '准备中' },
      { type: 'result', data: { review: 'ok', imageCount: 1 } },
    ])
  })

  it('returns aborted without throwing when cancelled mid-stream', async () => {
    const events: ImageAnalysisStreamEvent[] = []
    const abortController = new AbortController()
    const response = new Response(new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode([
          `data: ${JSON.stringify({ type: 'progress', stage: 'draft', message: '正在生成初稿', attempt: 1, totalAttempts: 8 })}`,
          '',
        ].join('\n')))
      },
      cancel() {
        return undefined
      },
    }))

    const state = await consumeImageAnalysisStream(response, (event) => {
      events.push(event)
      abortController.abort()
    }, abortController.signal)

    expect(state).toBe('aborted')
    expect(events).toEqual([
      { type: 'progress', stage: 'draft', message: '正在生成初稿', attempt: 1, totalAttempts: 8 },
    ])
  })
})

describe('useImageAnalysis', () => {
  const originalFetch = globalThis.fetch
  const originalCreateObjectURL = URL.createObjectURL
  const originalRevokeObjectURL = URL.revokeObjectURL

  beforeEach(() => {
    URL.createObjectURL = vi.fn(() => 'blob:preview')
    URL.revokeObjectURL = vi.fn()
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
    URL.createObjectURL = originalCreateObjectURL
    URL.revokeObjectURL = originalRevokeObjectURL
  })

  it('ignores stale updates from a superseded request', async () => {
    const firstDeferred = createDeferred<Response>()
    const secondDeferred = createDeferred<Response>()
    const fetchMock = vi.fn()
      .mockReturnValueOnce(firstDeferred.promise)
      .mockReturnValueOnce(secondDeferred.promise)
    globalThis.fetch = fetchMock as typeof fetch

    const analysis = useImageAnalysis()
    const file = new File(['image-data'], 'cover.png', { type: 'image/png' })
    analysis.addFiles([file])

    const firstRun = analysis.startGeneration()
    await Promise.resolve()

    const secondRun = analysis.startGeneration()
    expect(analysis.loading.value).toBe(true)

    firstDeferred.resolve(createSseResponse([
      { type: 'progress', stage: 'draft', message: '旧请求进度', attempt: 1, totalAttempts: 8 },
      { type: 'result', data: { review: 'old result', imageCount: 1 } },
    ]))

    await firstRun

    expect(analysis.loading.value).toBe(true)
    expect(analysis.result.value).toBeNull()
    expect(analysis.progressEvents.value).toEqual([])

    secondDeferred.resolve(createSseResponse([
      { type: 'progress', stage: 'draft', message: '新请求进度', attempt: 1, totalAttempts: 8, startedAt: '2026-04-24T00:00:20.000Z' },
      { type: 'result', data: { review: 'new result', imageCount: 1 } },
    ]))

    await secondRun

    expect(analysis.loading.value).toBe(false)
    expect(analysis.result.value).toEqual({ review: 'new result', imageCount: 1 })
    expect(analysis.progressEvents.value).toEqual([
      { stage: 'draft', message: '新请求进度', attempt: 1, totalAttempts: 8, startedAt: '2026-04-24T00:00:20.000Z', type: 'progress' },
    ])
  })

  it('merges progress updates for the same step and preserves duration fields', async () => {
    const fetchMock = vi.fn().mockResolvedValue(createSseResponse([
      { type: 'progress', stage: 'draft', message: '正在分析图片并生成初稿（第 1 / 8 步）', attempt: 1, totalAttempts: 8, startedAt: '2026-04-24T00:00:01.000Z' },
      { type: 'progress', stage: 'draft', message: '正在分析图片并生成初稿（第 1 / 8 步）', attempt: 1, totalAttempts: 8, startedAt: '2026-04-24T00:00:01.000Z', completedAt: '2026-04-24T00:00:03.500Z', durationMs: 2500 },
      { type: 'result', data: { review: 'done', imageCount: 1 } },
    ]))
    globalThis.fetch = fetchMock as typeof fetch

    const analysis = useImageAnalysis()
    const file = new File(['image-data'], 'cover.png', { type: 'image/png' })
    analysis.addFiles([file])

    await analysis.startGeneration()

    expect(analysis.result.value).toEqual({ review: 'done', imageCount: 1 })
    expect(analysis.progressEvents.value).toEqual([
      {
        type: 'progress',
        stage: 'draft',
        message: '正在分析图片并生成初稿（第 1 / 8 步）',
        attempt: 1,
        totalAttempts: 8,
        startedAt: '2026-04-24T00:00:01.000Z',
        completedAt: '2026-04-24T00:00:03.500Z',
        durationMs: 2500,
      },
    ])
  })

  it('ignores stale stream events after a newer request has started', async () => {
    const firstStream = createControlledSseResponse()
    const secondStream = createControlledSseResponse()
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(firstStream.response)
      .mockResolvedValueOnce(secondStream.response)
    globalThis.fetch = fetchMock as typeof fetch

    const analysis = useImageAnalysis()
    const file = new File(['image-data'], 'cover.png', { type: 'image/png' })
    analysis.addFiles([file])

    const firstRun = analysis.startGeneration()
    await flushStreamUpdates()

    firstStream.pushEvent({ type: 'progress', stage: 'draft', message: 'A-1', attempt: 1, totalAttempts: 8 })
    await flushStreamUpdates()

    expect(analysis.progressEvents.value).toEqual([
      { type: 'progress', stage: 'draft', message: 'A-1', attempt: 1, totalAttempts: 8 },
    ])

    const secondRun = analysis.startGeneration()
    await flushStreamUpdates()

    expect(analysis.loading.value).toBe(true)
    expect(analysis.progressEvents.value).toEqual([])
    expect(analysis.currentProgress.value).toBeNull()

    secondStream.pushEvent({ type: 'progress', stage: 'draft', message: 'B-1', attempt: 1, totalAttempts: 8, startedAt: '2026-04-24T00:00:10.000Z' })
    await flushStreamUpdates()

    firstStream.pushEvent({ type: 'progress', stage: 'optimize', message: 'A-2', attempt: 2, totalAttempts: 8 })
    firstStream.pushEvent({ type: 'result', data: { review: 'old result', imageCount: 1 } })
    firstStream.finish()
    await firstRun
    await flushStreamUpdates()

    expect(analysis.loading.value).toBe(true)
    expect(analysis.currentProgress.value).toEqual({ type: 'progress', stage: 'draft', message: 'B-1', attempt: 1, totalAttempts: 8, startedAt: '2026-04-24T00:00:10.000Z' })
    expect(analysis.progressEvents.value).toEqual([
      { type: 'progress', stage: 'draft', message: 'B-1', attempt: 1, totalAttempts: 8, startedAt: '2026-04-24T00:00:10.000Z' },
    ])
    expect(analysis.result.value).toBeNull()

    secondStream.pushEvent({ type: 'result', data: { review: 'new result', imageCount: 1 } })
    secondStream.finish()
    await secondRun

    expect(analysis.loading.value).toBe(false)
    expect(analysis.result.value).toEqual({ review: 'new result', imageCount: 1 })
    expect(analysis.progressEvents.value).toEqual([
      { type: 'progress', stage: 'draft', message: 'B-1', attempt: 1, totalAttempts: 8, startedAt: '2026-04-24T00:00:10.000Z' },
    ])
  })
})
