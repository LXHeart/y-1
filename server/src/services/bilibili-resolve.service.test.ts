import { beforeEach, describe, expect, it } from 'vitest'
import { AppError } from '../lib/errors.js'

beforeEach(() => {
  process.env.BILIBILI_PROXY_TOKEN_SECRET = 'bilibili-proxy-test-secret-1234567890'
})

const { extractBilibiliEntryUrl, resolveBilibiliSource } = await import('./bilibili-resolve.service.js')

describe('extractBilibiliEntryUrl', () => {
  it('extracts bilibili video urls from share text', () => {
    expect(extractBilibiliEntryUrl('【测试】https://www.bilibili.com/video/BV1ri9FBTES5?x=1')).toBe(
      'https://www.bilibili.com/video/BV1ri9FBTES5?x=1',
    )
  })

  it('rejects text without allowed bilibili urls', () => {
    expect(() => extractBilibiliEntryUrl('https://example.com/video')).toThrow(AppError)
  })
})

describe('resolveBilibiliSource', () => {
  it('returns progressive source material when durl is present', async () => {
    const originalFetch = globalThis.fetch
    const html = `
      <script>window.__INITIAL_STATE__={"videoData":{"title":"标题","owner":{"name":"作者"},"pic":"https://i0.hdslb.com/test.jpg","bvid":"BV123","duration":118}};</script>
      <script>window.__playinfo__={"data":{"durl":[{"url":"https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.mp4"}]}};</script>
    `

    globalThis.fetch = (async () => new Response(html, {
      status: 200,
      headers: { 'content-type': 'text/html' },
    })) as typeof fetch

    try {
      await expect(resolveBilibiliSource('https://www.bilibili.com/video/BV123')).resolves.toEqual({
        sourceUrl: 'https://www.bilibili.com/video/BV123',
        resolvedUrl: 'https://www.bilibili.com/video/BV123',
        videoId: 'BV123',
        author: '作者',
        title: '标题',
        coverUrl: 'https://i0.hdslb.com/test.jpg',
        durationSeconds: 118,
        playableVideoUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.mp4',
        requestHeaders: {
          'User-Agent': expect.any(String),
          Referer: 'https://www.bilibili.com/video/BV123',
          Origin: 'https://www.bilibili.com',
        },
        playbackMode: 'progressive',
      })
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  it('returns dash source material when video and audio tracks are present', async () => {
    const originalFetch = globalThis.fetch
    const html = `
      <script>window.__INITIAL_STATE__={"videoData":{"title":"标题","owner":{"name":"作者"},"pic":"https://i0.hdslb.com/test.jpg","bvid":"BV123","duration":125}};</script>
      <script>window.__playinfo__={"data":{"dash":{"video":[{"baseUrl":"https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s"}],"audio":[{"baseUrl":"https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s"}]}}};</script>
    `

    globalThis.fetch = (async () => new Response(html, {
      status: 200,
      headers: { 'content-type': 'text/html' },
    })) as typeof fetch

    try {
      await expect(resolveBilibiliSource('https://www.bilibili.com/video/BV123')).resolves.toEqual({
        sourceUrl: 'https://www.bilibili.com/video/BV123',
        resolvedUrl: 'https://www.bilibili.com/video/BV123',
        videoId: 'BV123',
        author: '作者',
        title: '标题',
        coverUrl: 'https://i0.hdslb.com/test.jpg',
        durationSeconds: 125,
        videoTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
        audioTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
        requestHeaders: {
          'User-Agent': expect.any(String),
          Referer: 'https://www.bilibili.com/video/BV123',
          Origin: 'https://www.bilibili.com',
        },
        playbackMode: 'dash',
      })
    } finally {
      globalThis.fetch = originalFetch
    }
  })


  it('falls back to playinfo timelength when initial state duration is missing', async () => {
    const originalFetch = globalThis.fetch
    const html = `
      <script>window.__INITIAL_STATE__={"videoData":{"title":"标题","owner":{"name":"作者"},"pic":"https://i0.hdslb.com/test.jpg","bvid":"BV123"}};</script>
      <script>window.__playinfo__={"data":{"timelength":301001,"durl":[{"url":"https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.mp4"}]}};</script>
    `

    globalThis.fetch = (async () => new Response(html, {
      status: 200,
      headers: { 'content-type': 'text/html' },
    })) as typeof fetch

    try {
      await expect(resolveBilibiliSource('https://www.bilibili.com/video/BV123')).resolves.toMatchObject({
        durationSeconds: 302,
        playbackMode: 'progressive',
      })
    } finally {
      globalThis.fetch = originalFetch
    }
  })
})

