import { describe, expect, it, beforeEach, vi } from 'vitest'

beforeEach(() => {
  process.env.BILIBILI_PROXY_TOKEN_SECRET = 'bilibili-proxy-test-secret-1234567890'
})

const {
  downloadBilibiliVideoHandler,
  extractBilibiliVideoHandler,
  proxyBilibiliVideoHandler,
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

const { extractBilibiliVideo } = await import('../services/bilibili-video.service.js')
const { parseBilibiliProxyToken } = await import('../services/bilibili-proxy.service.js')
const { downloadBilibiliMedia, proxyBilibiliMedia } = await import('../services/bilibili-stream.service.js')

function createResponseMock() {
  return {
    json: vi.fn(),
  }
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
