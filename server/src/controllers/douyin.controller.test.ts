import { EventEmitter } from 'node:events'
import { describe, expect, it, beforeEach, vi } from 'vitest'

const {
  createDouyinMediaReadStreamMock,
  getDouyinAnalysisMediaSessionMock,
  httpsRequestMock,
  mediaStreamMock,
} = vi.hoisted(() => {
  const mediaStreamMock = {
    on: vi.fn(() => mediaStreamMock),
    pipe: vi.fn(),
  }

  return {
    createDouyinMediaReadStreamMock: vi.fn(async () => mediaStreamMock),
    getDouyinAnalysisMediaSessionMock: vi.fn(async () => ({
      filePath: '/tmp/douyin-analysis.mp4',
      fileSize: 123,
      filename: 'analysis.mp4',
      mimeType: 'video/mp4',
    })),
    httpsRequestMock: vi.fn(),
    mediaStreamMock,
  }
})

beforeEach(() => {
  process.env.DOUYIN_PROXY_TOKEN_SECRET = 'douyin-proxy-test-secret-1234567890'
  createDouyinMediaReadStreamMock.mockClear()
  getDouyinAnalysisMediaSessionMock.mockClear()
  httpsRequestMock.mockReset()
  httpsRequestMock.mockImplementation((_url, _options, callback) => {
    const upstreamRes = new EventEmitter() as EventEmitter & {
      statusCode?: number
      headers: Record<string, string>
      resume: () => void
      pipe: (destination: unknown) => unknown
    }
    upstreamRes.statusCode = 200
    upstreamRes.headers = {
      'content-type': 'video/mp4',
      'content-length': '123',
    }
    upstreamRes.resume = vi.fn()
    upstreamRes.pipe = vi.fn((destination: unknown) => {
      const response = destination as { emit?: (event: string) => void }
      queueMicrotask(() => {
        response.emit?.('finish')
      })
      return destination
    })

    queueMicrotask(() => {
      callback(upstreamRes as never)
      upstreamRes.emit('end')
    })

    return {
      on: vi.fn().mockReturnThis(),
      destroy: vi.fn(),
      end: vi.fn(),
    }
  })
  mediaStreamMock.on.mockClear()
  mediaStreamMock.pipe.mockClear()
})

const {
  analyzeDouyinVideoHandler,
  assertAllowedVideoUrl,
  downloadDouyinVideoHandler,
  extractDouyinVideoHandler,
  getDouyinHotItemsHandler,
  isAllowedVideoHost,
  proxyDouyinVideoHandler,
  resolveUpstreamRedirectUrl,
  serveDouyinAnalysisMediaHandler,
} = await import('./douyin.controller.js')

vi.mock('node:https', () => ({
  request: httpsRequestMock,
}))

vi.mock('../services/douyin-video.service.js', () => ({
  extractDouyinVideo: vi.fn(async () => ({
    sourceUrl: 'https://www.douyin.com/video/123',
    platform: 'douyin',
    proxyVideoUrl: '/api/douyin/proxy/token',
    downloadVideoUrl: '/api/douyin/download/token',
    downloadAudioUrl: '/api/douyin/audio/token',
    durationSeconds: 125,
    usedSession: false,
    fetchStage: 'page_json',
  })),
}))

vi.mock('../services/douyin-proxy.service.js', async () => {
  const actual = await vi.importActual<typeof import('../services/douyin-proxy.service.js')>('../services/douyin-proxy.service.js')

  return {
    ...actual,
    parseDouyinProxyToken: vi.fn(() => ({
      playableVideoUrl: 'https://v3-dy-o.zjcdn.com/video.mp4',
      requestHeaders: {
        Referer: 'https://www.douyin.com/video/123',
      },
      filename: 'test.mp4',
      durationSeconds: 120,
    })),
  }
})

vi.mock('../services/douyin-video-analysis.service.js', () => ({
  analyzeDouyinVideoByProxyUrl: vi.fn(async () => ({
    videoCaptions: '字幕内容',
    videoScript: '脚本内容',
    sceneDescription: '场景内容',
    runId: 'douyin-analysis-run',
  })),
}))

vi.mock('../services/douyin-hot.service.js', () => ({
  loadDouyinHotItems: vi.fn(async () => ({
    items: [{
      rank: 1,
      title: '热点 1',
      hotValue: '9999',
      url: 'https://www.douyin.com/hot/1',
      cover: 'https://img.example.com/1.jpg',
      source: '60sapi',
    }],
  })),
}))

vi.mock('../services/douyin-analysis-media.service.js', () => ({
  getDouyinAnalysisMediaSession: getDouyinAnalysisMediaSessionMock,
}))

vi.mock('../services/douyin-media.service.js', () => ({
  createDouyinMediaReadStream: createDouyinMediaReadStreamMock,
}))

vi.mock('../services/douyin-session.service.js', () => ({
  getDouyinSessionSnapshot: vi.fn(),
  startDouyinSession: vi.fn(),
  pollDouyinSession: vi.fn(),
  logoutDouyinSession: vi.fn(),
}))

vi.mock('../services/douyin-audio.service.js', () => ({
  cleanupDouyinAudioFile: vi.fn(),
  extractDouyinAudio: vi.fn(),
  buildAudioDownloadFilename: vi.fn((filename?: string) => filename ? filename.replace(/\.[^.]+$/u, '.mp3') : 'douyin-video.mp3'),
}))

const { extractDouyinVideo } = await import('../services/douyin-video.service.js')
const { parseDouyinProxyToken } = await import('../services/douyin-proxy.service.js')
const { analyzeDouyinVideoByProxyUrl } = await import('../services/douyin-video-analysis.service.js')
const { loadDouyinHotItems } = await import('../services/douyin-hot.service.js')

function createResponseMock() {
  const responseEmitter = new EventEmitter()
  const response = {
    destroy: vi.fn(),
    json: vi.fn(),
    headersSent: false,
    off: responseEmitter.off.bind(responseEmitter),
    on: responseEmitter.on.bind(responseEmitter),
    once: responseEmitter.once.bind(responseEmitter),
    emit: responseEmitter.emit.bind(responseEmitter),
    setHeader: vi.fn(),
    status: vi.fn(() => response),
  }

  return response
}

describe('isAllowedVideoHost', () => {
  it('allows exact trusted cdn hosts', () => {
    expect(isAllowedVideoHost('zjcdn.com')).toBe(true)
  })

  it('allows trusted cdn subdomains', () => {
    expect(isAllowedVideoHost('v1.zjcdn.com')).toBe(true)
  })

  it('rejects lookalike hosts', () => {
    expect(isAllowedVideoHost('evilzjcdn.com')).toBe(false)
  })

  it('rejects suffix-trick hosts', () => {
    expect(isAllowedVideoHost('zjcdn.com.evil.com')).toBe(false)
  })
})

describe('assertAllowedVideoUrl', () => {
  it('rejects untrusted video urls', () => {
    expect(() => assertAllowedVideoUrl('https://evilzjcdn.com/video.mp4')).toThrow()
  })
})

describe('resolveUpstreamRedirectUrl', () => {
  it('accepts trusted redirected video urls', () => {
    expect(resolveUpstreamRedirectUrl(
      'https://aweme.snssdk.com/aweme/v1/playwm/?video_id=123',
      'https://v5-dy-o-abtest.zjcdn.com/video.mp4',
    )).toBe('https://v5-dy-o-abtest.zjcdn.com/video.mp4')
  })

  it('rejects untrusted redirected video urls', () => {
    expect(() => resolveUpstreamRedirectUrl(
      'https://aweme.snssdk.com/aweme/v1/playwm/?video_id=123',
      'https://evil.example.com/video.mp4',
    )).toThrow()
  })
})

describe('extractDouyinVideoHandler', () => {
  it('returns extracted payload as json', async () => {
    const req = {
      body: {
        input: 'https://www.douyin.com/video/123',
      },
    }
    const res = createResponseMock()
    const next = vi.fn()

    await extractDouyinVideoHandler(req as never, res as never, next)

    expect(extractDouyinVideo).toHaveBeenCalledWith('https://www.douyin.com/video/123')
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: {
        sourceUrl: 'https://www.douyin.com/video/123',
        platform: 'douyin',
        proxyVideoUrl: '/api/douyin/proxy/token',
        downloadVideoUrl: '/api/douyin/download/token',
        downloadAudioUrl: '/api/douyin/audio/token',
        durationSeconds: 125,
        usedSession: false,
        fetchStage: 'page_json',
      },
    })
    expect(next).not.toHaveBeenCalled()
  })
})

describe('proxyDouyinVideoHandler', () => {
  it('parses the token before streaming the target media', async () => {
    const req = {
      params: {
        token: 'token',
      },
      headers: {},
    }
    const res = createResponseMock()
    const next = vi.fn()

    await proxyDouyinVideoHandler(req as never, res as never, next)

    expect(parseDouyinProxyToken).toHaveBeenCalledWith('token')
    expect(next).not.toHaveBeenCalled()
  })
})

describe('analyzeDouyinVideoHandler', () => {
  it('delegates proxy url analysis and returns json with abort signal', async () => {
    const reqEmitter = new EventEmitter()
    const req = {
      body: {
        proxyVideoUrl: '/api/douyin/proxy/token',
      },
      off: reqEmitter.off.bind(reqEmitter),
      on: reqEmitter.on.bind(reqEmitter),
      once: reqEmitter.once.bind(reqEmitter),
      emit: reqEmitter.emit.bind(reqEmitter),
    }
    const res = createResponseMock()
    const next = vi.fn()

    await analyzeDouyinVideoHandler(req as never, res as never, next)

    expect(analyzeDouyinVideoByProxyUrl).toHaveBeenCalledWith('/api/douyin/proxy/token', {
      signal: expect.any(AbortSignal),
    })
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: {
        videoCaptions: '字幕内容',
        videoScript: '脚本内容',
        sceneDescription: '场景内容',
        runId: 'douyin-analysis-run',
      },
    })
    expect(next).not.toHaveBeenCalled()
  })

  it('ignores request-scoped analysis config from public requests', async () => {
    const reqEmitter = new EventEmitter()
    const req = {
      body: {
        proxyVideoUrl: '/api/douyin/proxy/token',
        analysisConfig: {
          provider: 'qwen',
          baseUrl: 'https://custom.example.com/run',
          apiToken: 'request-token',
          apiKey: 'request-key',
          model: 'qwen-max',
        },
      },
      off: reqEmitter.off.bind(reqEmitter),
      on: reqEmitter.on.bind(reqEmitter),
      once: reqEmitter.once.bind(reqEmitter),
      emit: reqEmitter.emit.bind(reqEmitter),
    }
    const res = createResponseMock()
    const next = vi.fn()

    await analyzeDouyinVideoHandler(req as never, res as never, next)

    expect(analyzeDouyinVideoByProxyUrl).toHaveBeenCalledWith('/api/douyin/proxy/token', {
      signal: expect.any(AbortSignal),
    })
    expect(analyzeDouyinVideoByProxyUrl).not.toHaveBeenCalledWith(
      '/api/douyin/proxy/token',
      expect.objectContaining({ analysisConfig: expect.anything() }),
    )
  })

  it('rejects analyze requests with proxy urls from a different origin', async () => {
    const reqEmitter = new EventEmitter()
    const req = {
      body: {
        proxyVideoUrl: 'https://evil.example/api/douyin/proxy/token',
      },
      off: reqEmitter.off.bind(reqEmitter),
      on: reqEmitter.on.bind(reqEmitter),
      once: reqEmitter.once.bind(reqEmitter),
      emit: reqEmitter.emit.bind(reqEmitter),
    }
    const res = createResponseMock()
    const next = vi.fn()

    vi.mocked(analyzeDouyinVideoByProxyUrl).mockClear()

    await analyzeDouyinVideoHandler(req as never, res as never, next)

    expect(analyzeDouyinVideoByProxyUrl).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledWith(expect.objectContaining({
      name: 'ZodError',
      issues: expect.arrayContaining([
        expect.objectContaining({
          message: '视频代理地址无效',
          path: ['proxyVideoUrl'],
        }),
      ]),
    }))
  })

  it('forwards analysis errors to next', async () => {
    const reqEmitter = new EventEmitter()
    const req = {
      body: {
        proxyVideoUrl: '/api/douyin/proxy/token',
      },
      off: reqEmitter.off.bind(reqEmitter),
      on: reqEmitter.on.bind(reqEmitter),
      once: reqEmitter.once.bind(reqEmitter),
      emit: reqEmitter.emit.bind(reqEmitter),
    }
    const res = createResponseMock()
    const next = vi.fn()
    const error = new Error('视频内容提取失败')

    vi.mocked(analyzeDouyinVideoByProxyUrl).mockRejectedValueOnce(error)

    await analyzeDouyinVideoHandler(req as never, res as never, next)

    expect(res.json).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledWith(error)
  })
})

describe('getDouyinHotItemsHandler', () => {
  it('returns normalized hot items as json', async () => {
    const req = {}
    const res = createResponseMock()
    const next = vi.fn()

    await getDouyinHotItemsHandler(req as never, res as never, next)

    expect(loadDouyinHotItems).toHaveBeenCalledWith()
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: {
        items: [{
          rank: 1,
          title: '热点 1',
          hotValue: '9999',
          url: 'https://www.douyin.com/hot/1',
          cover: 'https://img.example.com/1.jpg',
          source: '60sapi',
        }],
      },
    })
    expect(next).not.toHaveBeenCalled()
  })

  it('forwards hot item loading errors to next', async () => {
    const req = {}
    const res = createResponseMock()
    const next = vi.fn()
    const error = new Error('热点接口失败')

    vi.mocked(loadDouyinHotItems).mockRejectedValueOnce(error)

    await getDouyinHotItemsHandler(req as never, res as never, next)

    expect(res.json).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledWith(error)
  })
})

describe('serveDouyinAnalysisMediaHandler', () => {
  it('streams the prepared analysis media file', async () => {
    const req = {
      headers: {},
      params: {
        id: 'analysis-media-id',
      },
    }
    const res = createResponseMock()
    const next = vi.fn()

    await serveDouyinAnalysisMediaHandler(req as never, res as never, next)

    expect(getDouyinAnalysisMediaSessionMock).toHaveBeenCalledWith('analysis-media-id')
    expect(createDouyinMediaReadStreamMock).toHaveBeenCalledWith('/tmp/douyin-analysis.mp4', undefined)
    expect(res.setHeader).toHaveBeenCalledWith('Cache-Control', 'no-store, private')
    expect(res.setHeader).toHaveBeenCalledWith('Accept-Ranges', 'bytes')
    expect(res.setHeader).toHaveBeenCalledWith('Content-Type', 'video/mp4')
    expect(res.setHeader).toHaveBeenCalledWith('Content-Length', '123')
    expect(res.status).toHaveBeenCalledWith(200)
    expect(mediaStreamMock.pipe).toHaveBeenCalledWith(res)
    expect(next).not.toHaveBeenCalled()
  })

  it('streams a requested analysis media byte range', async () => {
    const req = {
      headers: {
        range: 'bytes=10-19',
      },
      params: {
        id: 'analysis-media-id',
      },
    }
    const res = createResponseMock()
    const next = vi.fn()

    await serveDouyinAnalysisMediaHandler(req as never, res as never, next)

    expect(createDouyinMediaReadStreamMock).toHaveBeenCalledWith('/tmp/douyin-analysis.mp4', {
      start: 10,
      end: 19,
    })
    expect(res.setHeader).toHaveBeenCalledWith('Accept-Ranges', 'bytes')
    expect(res.setHeader).toHaveBeenCalledWith('Content-Range', 'bytes 10-19/123')
    expect(res.setHeader).toHaveBeenCalledWith('Content-Length', '10')
    expect(res.status).toHaveBeenCalledWith(206)
    expect(mediaStreamMock.pipe).toHaveBeenCalledWith(res)
    expect(next).not.toHaveBeenCalled()
  })

  it('forwards missing media errors to next', async () => {
    const req = {
      headers: {},
      params: {
        id: 'missing-id',
      },
    }
    const res = createResponseMock()
    const next = vi.fn()
    const error = new Error('分析视频文件不存在或已过期')

    getDouyinAnalysisMediaSessionMock.mockRejectedValueOnce(error)

    await serveDouyinAnalysisMediaHandler(req as never, res as never, next)

    expect(next).toHaveBeenCalledWith(error)
  })
})

describe('downloadDouyinVideoHandler', () => {
  it('parses the token before handling download streaming', async () => {
    const req = {
      params: {
        token: 'token',
      },
      headers: {},
    }
    const res = createResponseMock()
    const next = vi.fn()

    await downloadDouyinVideoHandler(req as never, res as never, next)

    expect(parseDouyinProxyToken).toHaveBeenCalledWith('token')
    expect(next).not.toHaveBeenCalled()
  })
})
