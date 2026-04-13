import { describe, expect, it, beforeEach, vi } from 'vitest'

const {
  createBilibiliMediaReadStreamMock,
  getBilibiliAnalysisMediaSessionMock,
  mediaStreamMock,
} = vi.hoisted(() => {
  const mediaStreamMock = {
    on: vi.fn(() => mediaStreamMock),
    pipe: vi.fn(),
  }

  return {
    createBilibiliMediaReadStreamMock: vi.fn(async () => mediaStreamMock),
    getBilibiliAnalysisMediaSessionMock: vi.fn(async () => ({
      filePath: '/tmp/bilibili-analysis.mp4',
      fileSize: 123,
      filename: 'analysis.mp4',
      mimeType: 'video/mp4',
    })),
    mediaStreamMock,
  }
})

beforeEach(() => {
  process.env.BILIBILI_PROXY_TOKEN_SECRET = 'bilibili-proxy-test-secret-1234567890'
  createBilibiliMediaReadStreamMock.mockClear()
  getBilibiliAnalysisMediaSessionMock.mockClear()
  mediaStreamMock.on.mockClear()
  mediaStreamMock.pipe.mockClear()
})

const {
  analyzeBilibiliVideoHandler,
  downloadBilibiliVideoHandler,
  extractBilibiliVideoHandler,
  proxyBilibiliVideoHandler,
  serveBilibiliAnalysisMediaHandler,
} = await import('./bilibili.controller.js')

vi.mock('../services/bilibili-video.service.js', () => ({
  extractBilibiliVideo: vi.fn(async () => ({
    sourceUrl: 'https://www.bilibili.com/video/BV1test',
    platform: 'bilibili',
    proxyVideoUrl: '/api/bilibili/proxy/token',
    downloadVideoUrl: '/api/bilibili/download/token',
    playbackMode: 'dash',
  })),
}))

vi.mock('../services/bilibili-proxy.service.js', async () => {
  const actual = await vi.importActual<typeof import('../services/bilibili-proxy.service.js')>('../services/bilibili-proxy.service.js')

  return {
    ...actual,
    parseBilibiliProxyToken: vi.fn(() => ({
      kind: 'dash',
      videoTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      audioTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
      filename: 'test.mp4',
    })),
  }
})

vi.mock('../services/bilibili-stream.service.js', () => ({
  proxyBilibiliMedia: vi.fn(async () => {}),
  downloadBilibiliMedia: vi.fn(async () => {}),
}))

vi.mock('../services/bilibili-video-analysis.service.js', () => ({
  analyzeBilibiliVideoByProxyUrl: vi.fn(async () => ({
    runId: 'bilibili-analysis-run',
  })),
}))

vi.mock('../services/bilibili-analysis-media.service.js', () => ({
  getBilibiliAnalysisMediaSession: getBilibiliAnalysisMediaSessionMock,
}))

vi.mock('../services/bilibili-media.service.js', () => ({
  createBilibiliMediaReadStream: createBilibiliMediaReadStreamMock,
}))

const { extractBilibiliVideo } = await import('../services/bilibili-video.service.js')
const { parseBilibiliProxyToken } = await import('../services/bilibili-proxy.service.js')
const { downloadBilibiliMedia, proxyBilibiliMedia } = await import('../services/bilibili-stream.service.js')
const { analyzeBilibiliVideoByProxyUrl } = await import('../services/bilibili-video-analysis.service.js')

function createResponseMock() {
  const response = {
    destroy: vi.fn(),
    json: vi.fn(),
    setHeader: vi.fn(),
    status: vi.fn(() => response),
  }

  return response
}

describe('extractBilibiliVideoHandler', () => {
  it('returns extracted payload as json', async () => {
    const req = {
      body: {
        input: 'https://www.bilibili.com/video/BV1test',
      },
    }
    const res = createResponseMock()
    const next = vi.fn()

    await extractBilibiliVideoHandler(req as never, res as never, next)

    expect(extractBilibiliVideo).toHaveBeenCalledWith('https://www.bilibili.com/video/BV1test')
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: {
        sourceUrl: 'https://www.bilibili.com/video/BV1test',
        platform: 'bilibili',
        proxyVideoUrl: '/api/bilibili/proxy/token',
        downloadVideoUrl: '/api/bilibili/download/token',
        playbackMode: 'dash',
      },
    })
    expect(next).not.toHaveBeenCalled()
  })
})

describe('proxyBilibiliVideoHandler', () => {
  it('delegates token target streaming to the stream service', async () => {
    const req = {
      params: {
        token: 'token',
      },
    }
    const res = createResponseMock()
    const next = vi.fn()

    await proxyBilibiliVideoHandler(req as never, res as never, next)

    expect(parseBilibiliProxyToken).toHaveBeenCalledWith('token')
    expect(proxyBilibiliMedia).toHaveBeenCalledWith(req, res, {
      kind: 'dash',
      videoTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      audioTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
      filename: 'test.mp4',
    })
    expect(next).not.toHaveBeenCalled()
  })
})

describe('analyzeBilibiliVideoHandler', () => {
  it('delegates proxy url analysis and returns json', async () => {
    const req = {
      body: {
        proxyVideoUrl: '/api/bilibili/proxy/token',
      },
    }
    const res = createResponseMock()
    const next = vi.fn()

    await analyzeBilibiliVideoHandler(req as never, res as never, next)

    expect(analyzeBilibiliVideoByProxyUrl).toHaveBeenCalledWith('/api/bilibili/proxy/token')
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: {
        runId: 'bilibili-analysis-run',
      },
    })
    expect(next).not.toHaveBeenCalled()
  })

  it('forwards analysis errors to next', async () => {
    const req = {
      body: {
        proxyVideoUrl: '/api/bilibili/proxy/token',
      },
    }
    const res = createResponseMock()
    const next = vi.fn()
    const error = new Error('视频内容提取失败')

    vi.mocked(analyzeBilibiliVideoByProxyUrl).mockRejectedValueOnce(error)

    await analyzeBilibiliVideoHandler(req as never, res as never, next)

    expect(res.json).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledWith(error)
  })
})

describe('serveBilibiliAnalysisMediaHandler', () => {
  it('streams the prepared analysis media file', async () => {
    const req = {
      params: {
        id: 'analysis-media-id',
      },
    }
    const res = createResponseMock()
    const next = vi.fn()

    await serveBilibiliAnalysisMediaHandler(req as never, res as never, next)

    expect(getBilibiliAnalysisMediaSessionMock).toHaveBeenCalledWith('analysis-media-id')
    expect(createBilibiliMediaReadStreamMock).toHaveBeenCalledWith('/tmp/bilibili-analysis.mp4')
    expect(res.setHeader).toHaveBeenCalledWith('Cache-Control', 'no-store, private')
    expect(res.setHeader).toHaveBeenCalledWith('Content-Type', 'video/mp4')
    expect(res.setHeader).toHaveBeenCalledWith('Content-Length', '123')
    expect(res.status).toHaveBeenCalledWith(200)
    expect(mediaStreamMock.pipe).toHaveBeenCalledWith(res)
    expect(next).not.toHaveBeenCalled()
  })

  it('forwards missing media errors to next', async () => {
    const req = {
      params: {
        id: 'missing-id',
      },
    }
    const res = createResponseMock()
    const next = vi.fn()
    const error = new Error('分析视频文件不存在或已过期')

    getBilibiliAnalysisMediaSessionMock.mockRejectedValueOnce(error)

    await serveBilibiliAnalysisMediaHandler(req as never, res as never, next)

    expect(next).toHaveBeenCalledWith(error)
  })
})

describe('downloadBilibiliVideoHandler', () => {
  it('builds content disposition and delegates downloading to the stream service', async () => {
    const req = {
      params: {
        token: 'token',
      },
    }
    const res = createResponseMock()
    const next = vi.fn()

    await downloadBilibiliVideoHandler(req as never, res as never, next)

    expect(parseBilibiliProxyToken).toHaveBeenCalledWith('token')
    expect(downloadBilibiliMedia).toHaveBeenCalledWith(req, res, {
      kind: 'dash',
      videoTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      audioTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
      filename: 'test.mp4',
    }, "attachment; filename*=UTF-8''test.mp4")
    expect(next).not.toHaveBeenCalled()
  })
})
