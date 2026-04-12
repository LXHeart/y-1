import { beforeEach, describe, expect, it } from 'vitest'
import { env } from '../lib/env.js'
import { buildBilibiliDownloadFilename } from '../lib/bilibili-filename.js'
import { AppError } from '../lib/errors.js'

beforeEach(() => {
  process.env.BILIBILI_PROXY_TOKEN_SECRET = 'bilibili-proxy-test-secret-1234567890'
})

const { createBilibiliProxyToken, parseBilibiliProxyToken, buildPublicBilibiliProxyUrl } = await import('./bilibili-proxy.service.js')

describe('bilibili proxy tokens', () => {
  it('builds a public bilibili proxy URL from the configured backend origin', () => {
    if (!env.PUBLIC_BACKEND_ORIGIN) {
      expect(() => buildPublicBilibiliProxyUrl('token')).toThrow('未配置 PUBLIC_BACKEND_ORIGIN')
      return
    }

    expect(buildPublicBilibiliProxyUrl('token value')).toBe(
      `${env.PUBLIC_BACKEND_ORIGIN}/api/bilibili/proxy/token%20value`,
    )
  })

  it('round-trips progressive playable url headers and filename', () => {
    const token = createBilibiliProxyToken({
      kind: 'progressive',
      playableVideoUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      requestHeaders: {
        Referer: 'https://www.bilibili.com/video/BV1test',
        'User-Agent': 'Mozilla/5.0 test',
        Origin: 'https://www.bilibili.com',
      },
      filename: 'test-video.mp4',
    })

    expect(parseBilibiliProxyToken(token)).toEqual({
      kind: 'progressive',
      playableVideoUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      requestHeaders: {
        Referer: 'https://www.bilibili.com/video/BV1test',
        'User-Agent': 'Mozilla/5.0 test',
        Origin: 'https://www.bilibili.com',
      },
      filename: 'test-video.mp4',
    })
  })

  it('round-trips dash track urls headers and filename', () => {
    const token = createBilibiliProxyToken({
      kind: 'dash',
      videoTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      audioTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
      requestHeaders: {
        Referer: 'https://www.bilibili.com/video/BV1test',
        'User-Agent': 'Mozilla/5.0 test',
      },
      filename: 'dash-video.mp4',
    })

    expect(parseBilibiliProxyToken(token)).toEqual({
      kind: 'dash',
      videoTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      audioTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
      requestHeaders: {
        Referer: 'https://www.bilibili.com/video/BV1test',
        'User-Agent': 'Mozilla/5.0 test',
      },
      filename: 'dash-video.mp4',
    })
  })

  it('drops unapproved proxy request headers from tokens', () => {
    const token = createBilibiliProxyToken({
      kind: 'progressive',
      playableVideoUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      requestHeaders: {
        Referer: 'https://www.bilibili.com/video/BV1test',
        Cookie: 'session=secret',
      },
    })

    expect(parseBilibiliProxyToken(token)).toEqual({
      kind: 'progressive',
      playableVideoUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      requestHeaders: {
        Referer: 'https://www.bilibili.com/video/BV1test',
      },
      filename: undefined,
    })
  })

  it('rejects tampered tokens', () => {
    const token = createBilibiliProxyToken({
      kind: 'progressive',
      playableVideoUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
    })

    expect(() => parseBilibiliProxyToken(`${token}x`)).toThrow(AppError)
  })
})

describe('buildBilibiliDownloadFilename', () => {
  it('builds a safe mp4 filename from title author and video id', () => {
    const filename = buildBilibiliDownloadFilename({
      title: '电子/玩具:合集?',
      author: 'Alice * Bob',
      videoId: 'BV123456',
    })

    expect(filename).toBe('电子-玩具-合集-Alice-Bob-BV123456.mp4')
  })

  it('falls back when metadata is missing', () => {
    expect(buildBilibiliDownloadFilename({})).toBe('bilibili-video.mp4')
  })
})
