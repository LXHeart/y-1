import { describe, expect, it } from 'vitest'
import { env } from '../lib/env.js'
import { AppError } from '../lib/errors.js'
import {
  buildDownloadFilename,
  buildDouyinAudioPath,
  buildDouyinDownloadPath,
  buildDouyinProxyPath,
  buildPublicDouyinProxyUrl,
  createDouyinProxyToken,
  parseDouyinProxyToken,
} from './douyin-proxy.service.js'

describe('douyin proxy tokens', () => {
  it('round-trips playable url headers and filename', () => {
    const token = createDouyinProxyToken({
      playableVideoUrl: 'https://v3-dy-example.zjcdn.com/video.mp4',
      requestHeaders: {
        Referer: 'https://www.douyin.com/video/123',
        'User-Agent': 'Mozilla/5.0 test',
      },
      filename: 'test-video.mp4',
      durationSeconds: 118,
    })

    expect(parseDouyinProxyToken(token)).toEqual({
      playableVideoUrl: 'https://v3-dy-example.zjcdn.com/video.mp4',
      requestHeaders: {
        Referer: 'https://www.douyin.com/video/123',
        'User-Agent': 'Mozilla/5.0 test',
      },
      filename: 'test-video.mp4',
      durationSeconds: 118,
    })
  })

  it('drops unapproved proxy request headers from tokens', () => {
    const token = createDouyinProxyToken({
      playableVideoUrl: 'https://v3-dy-example.zjcdn.com/video.mp4',
      requestHeaders: {
        Referer: 'https://www.douyin.com/video/123',
        Cookie: 'session=secret',
      },
    })

    expect(parseDouyinProxyToken(token)).toEqual({
      playableVideoUrl: 'https://v3-dy-example.zjcdn.com/video.mp4',
      requestHeaders: {
        Referer: 'https://www.douyin.com/video/123',
      },
      filename: undefined,
    })
  })

  it('rejects tampered tokens', () => {
    const token = createDouyinProxyToken({
      playableVideoUrl: 'https://v3-dy-example.zjcdn.com/video.mp4',
    })

    const tampered = `${token}x`

    expect(() => parseDouyinProxyToken(tampered)).toThrow(AppError)
  })
})

describe('proxy url builders', () => {
  it('builds relative proxy, download, and audio paths from a token', () => {
    const token = createDouyinProxyToken({
      playableVideoUrl: 'https://v3-dy-example.zjcdn.com/video.mp4',
    })

    expect(buildDouyinProxyPath(token)).toBe(`/api/douyin/proxy/${encodeURIComponent(token)}`)
    expect(buildDouyinDownloadPath(token)).toBe(`/api/douyin/download/${encodeURIComponent(token)}`)
    expect(buildDouyinAudioPath(token)).toBe(`/api/douyin/audio/${encodeURIComponent(token)}`)
  })

  it('builds a public proxy url from the configured backend origin when available', () => {
    const token = createDouyinProxyToken({
      playableVideoUrl: 'https://v3-dy-example.zjcdn.com/video.mp4',
    })

    if (!env.PUBLIC_BACKEND_ORIGIN) {
      expect(() => buildPublicDouyinProxyUrl(token)).toThrow('未配置 PUBLIC_BACKEND_ORIGIN')
      return
    }

    expect(buildPublicDouyinProxyUrl(token)).toBe(
      `${env.PUBLIC_BACKEND_ORIGIN}/api/douyin/proxy/${encodeURIComponent(token)}`,
    )
  })
})

describe('buildDownloadFilename', () => {
  it('builds a safe mp4 filename from title author and video id', () => {
    const filename = buildDownloadFilename({
      title: '夏日/海边:穿搭?',
      author: 'Alice * Bob',
      videoId: '123456',
    })

    expect(filename).toBe('夏日-海边-穿搭-Alice-Bob-123456.mp4')
  })

  it('falls back when metadata is missing', () => {
    expect(buildDownloadFilename({})).toBe('douyin-video.mp4')
  })
})
