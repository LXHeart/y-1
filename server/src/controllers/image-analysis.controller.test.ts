import { EventEmitter } from 'node:events'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const {
  analyzeUploadedImagesMock,
} = vi.hoisted(() => ({
  analyzeUploadedImagesMock: vi.fn(),
}))

vi.mock('../services/image-analysis.service.js', () => ({
  analyzeUploadedImages: analyzeUploadedImagesMock,
}))

const {
  analyzeImageContentHandler,
} = await import('./image-analysis.controller.js')

function createResponseMock() {
  const responseEmitter = new EventEmitter()
  const response = {
    json: vi.fn(),
    write: vi.fn(),
    end: vi.fn(),
    setHeader: vi.fn(),
    headersSent: false,
    off: responseEmitter.off.bind(responseEmitter),
    on: responseEmitter.on.bind(responseEmitter),
    once: responseEmitter.once.bind(responseEmitter),
    emit: responseEmitter.emit.bind(responseEmitter),
  }

  return response
}

describe('analyzeImageContentHandler', () => {
  beforeEach(() => {
    analyzeUploadedImagesMock.mockReset()
    analyzeUploadedImagesMock.mockImplementation(async (_images, options) => {
      options?.onProgress?.({
        stage: 'prepare',
        message: '已接收 1 张图片，准备开始生成',
        totalAttempts: 8,
        startedAt: '2026-04-24T00:00:00.000Z',
      })
      options?.onProgress?.({
        stage: 'draft',
        message: '正在分析图片并生成初稿（第 1 / 8 步）',
        attempt: 1,
        totalAttempts: 8,
        startedAt: '2026-04-24T00:00:01.000Z',
      })
      options?.onProgress?.({
        stage: 'draft',
        message: '正在分析图片并生成初稿（第 1 / 8 步）',
        attempt: 1,
        totalAttempts: 8,
        startedAt: '2026-04-24T00:00:01.000Z',
        completedAt: '2026-04-24T00:00:03.500Z',
        durationMs: 2500,
      })
      return {
        review: '看起来很不错，整体体验挺好的。',
        imageCount: 1,
      }
    })
  })

  it('streams progress and final result events with abort signal', async () => {
    const reqEmitter = new EventEmitter()
    const req = {
      files: [{
        originalname: 'cover.png',
        mimetype: 'image/png',
        size: 12,
        buffer: Buffer.from('cover-image'),
      }],
      body: {
        reviewLength: '36',
        feelings: '看着就很有食欲',
      },
      off: reqEmitter.off.bind(reqEmitter),
      on: reqEmitter.on.bind(reqEmitter),
      once: reqEmitter.once.bind(reqEmitter),
      emit: reqEmitter.emit.bind(reqEmitter),
    }
    const res = createResponseMock()
    const next = vi.fn()

    await analyzeImageContentHandler(req as never, res as never, next)

    expect(analyzeUploadedImagesMock).toHaveBeenCalledWith([
      {
        originalName: 'cover.png',
        mimeType: 'image/png',
        size: 12,
        buffer: Buffer.from('cover-image'),
      },
    ], expect.objectContaining({
      reviewLength: 36,
      feelings: '看着就很有食欲',
      platform: 'taobao',
      signal: expect.any(AbortSignal),
      userId: undefined,
      onProgress: expect.any(Function),
    }))
    expect(res.setHeader).toHaveBeenNthCalledWith(1, 'Content-Type', 'text/event-stream')
    expect(res.setHeader).toHaveBeenNthCalledWith(2, 'Cache-Control', 'no-cache')
    expect(res.write).toHaveBeenCalledWith(`data: ${JSON.stringify({
      type: 'progress',
      stage: 'prepare',
      message: '已接收 1 张图片，准备开始生成',
      totalAttempts: 8,
      startedAt: '2026-04-24T00:00:00.000Z',
    })}\n\n`)
    expect(res.write).toHaveBeenCalledWith(`data: ${JSON.stringify({
      type: 'progress',
      stage: 'draft',
      message: '正在分析图片并生成初稿（第 1 / 8 步）',
      attempt: 1,
      totalAttempts: 8,
      startedAt: '2026-04-24T00:00:01.000Z',
    })}\n\n`)
    expect(res.write).toHaveBeenCalledWith(`data: ${JSON.stringify({
      type: 'progress',
      stage: 'draft',
      message: '正在分析图片并生成初稿（第 1 / 8 步）',
      attempt: 1,
      totalAttempts: 8,
      startedAt: '2026-04-24T00:00:01.000Z',
      completedAt: '2026-04-24T00:00:03.500Z',
      durationMs: 2500,
    })}\n\n`)
    expect(res.write).toHaveBeenCalledWith(`data: ${JSON.stringify({
      type: 'result',
      data: {
        review: '看起来很不错，整体体验挺好的。',
        imageCount: 1,
      },
    })}\n\n`)
    expect(res.write).toHaveBeenCalledWith('data: [DONE]\n\n')
    expect(res.end).toHaveBeenCalled()
    expect(next).not.toHaveBeenCalled()
  })

  it('writes an error event when service fails after stream setup', async () => {
    const reqEmitter = new EventEmitter()
    const req = {
      files: [],
      off: reqEmitter.off.bind(reqEmitter),
      on: reqEmitter.on.bind(reqEmitter),
      once: reqEmitter.once.bind(reqEmitter),
      emit: reqEmitter.emit.bind(reqEmitter),
    }
    const res = createResponseMock()
    const next = vi.fn()
    const error = new Error('图片分析失败')
    analyzeUploadedImagesMock.mockRejectedValueOnce(error)

    await analyzeImageContentHandler(req as never, res as never, next)

    expect(res.write).toHaveBeenCalledWith(`data: ${JSON.stringify({ type: 'error', error: '评价生成失败，请稍后重试' })}\n\n`)
    expect(res.end).toHaveBeenCalled()
    expect(next).not.toHaveBeenCalled()
  })
})
