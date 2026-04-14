import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppError } from '../lib/errors.js'

const loggerWarnMock = vi.fn()
const loggerErrorMock = vi.fn()

vi.mock('../lib/logger.js', () => ({
  logger: {
    warn: loggerWarnMock,
    error: loggerErrorMock,
  },
}))

describe('analyzeVideoContent', () => {
  const originalFetch = globalThis.fetch

  beforeEach(() => {
    process.env.VIDEO_ANALYSIS_API_BASE_URL = 'https://analysis.example.com/run'
    process.env.VIDEO_ANALYSIS_API_TIMEOUT_MS = '180000'
    process.env.VIDEO_ANALYSIS_API_TOKEN = ''
    loggerWarnMock.mockReset()
    loggerErrorMock.mockReset()
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
  })

  it('returns normalized analysis data from the upstream response', async () => {
    globalThis.fetch = vi.fn(async () => new Response(JSON.stringify({
      video_captions: 'captions',
      run_id: 'run_123',
    }), {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
      },
    }))

    const { analyzeVideoContent } = await import('./video-analysis.service.js')
    const result = await analyzeVideoContent('https://backend.example.com/api/bilibili/proxy/token')

    expect(result).toEqual({
      videoCaptions: 'captions',
      videoScript: undefined,
      charactersDescription: undefined,
      voiceDescription: undefined,
      propsDescription: undefined,
      sceneDescription: undefined,
      runId: 'run_123',
    })
  })

  it('returns 499 when the caller aborts the analysis request', async () => {
    globalThis.fetch = vi.fn((_, init?: RequestInit) => new Promise((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => {
        reject(new DOMException('The operation was aborted.', 'AbortError'))
      }, { once: true })
    })) as typeof fetch

    const { analyzeVideoContent } = await import('./video-analysis.service.js')
    const controller = new AbortController()
    const analysisPromise = analyzeVideoContent('https://backend.example.com/api/bilibili/analysis-media/id', {
      signal: controller.signal,
    })

    controller.abort()

    await expect(analysisPromise).rejects.toMatchObject({
      statusCode: 499,
      message: '分析请求已取消',
    } satisfies Partial<AppError>)
    expect(loggerWarnMock).toHaveBeenCalled()
  })

  it('returns 499 immediately when the caller signal is already aborted', async () => {
    globalThis.fetch = vi.fn(async () => new Response('{}', {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
      },
    }))

    const { analyzeVideoContent } = await import('./video-analysis.service.js')
    const controller = new AbortController()
    controller.abort()

    await expect(analyzeVideoContent('https://backend.example.com/api/bilibili/proxy/token', {
      signal: controller.signal,
    })).rejects.toMatchObject({
      statusCode: 499,
      message: '分析请求已取消',
    } satisfies Partial<AppError>)
    expect(globalThis.fetch).not.toHaveBeenCalled()
  })

  it('uses request-scoped analysis config with env fallback', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      video_captions: 'captions',
    }), {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
      },
    }))
    globalThis.fetch = fetchMock

    const { analyzeVideoContent } = await import('./video-analysis.service.js')

    await analyzeVideoContent('https://backend.example.com/api/douyin/proxy/token', {
      analysisConfig: {
        baseUrl: 'https://custom.example.com/analyze',
      },
    })

    expect(fetchMock).toHaveBeenCalledWith(
      'https://custom.example.com/analyze',
      expect.objectContaining({
        headers: expect.objectContaining({
          'Content-Type': 'application/json',
        }),
      }),
    )
  })

  it('prefers request-scoped token over env token', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      run_id: 'run_custom',
    }), {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
      },
    }))
    globalThis.fetch = fetchMock
    process.env.VIDEO_ANALYSIS_API_TOKEN = 'env-token'

    const { analyzeVideoContent } = await import('./video-analysis.service.js')

    await analyzeVideoContent('https://backend.example.com/api/douyin/proxy/token', {
      analysisConfig: {
        apiToken: 'request-token',
      },
    })

    expect(fetchMock).toHaveBeenCalledWith(
      'https://analysis.example.com/run',
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer request-token',
        }),
      }),
    )
  })

  it('rejects non-https request-scoped analysis url', async () => {
    globalThis.fetch = vi.fn(async () => new Response('{}', {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
      },
    }))

    const { analyzeVideoContent } = await import('./video-analysis.service.js')

    await expect(analyzeVideoContent('https://backend.example.com/api/douyin/proxy/token', {
      analysisConfig: {
        baseUrl: 'http://custom.example.com/analyze',
      },
    })).rejects.toMatchObject({
      statusCode: 400,
      message: '视频分析服务地址必须使用 HTTPS',
    } satisfies Partial<AppError>)
  })
})
