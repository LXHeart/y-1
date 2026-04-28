import { beforeEach, describe, expect, it, vi } from 'vitest'
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

  it('fetches signed playurl when html does not embed __playinfo__', async () => {
    const originalFetch = globalThis.fetch
    const html = `
      <script>window.__INITIAL_STATE__={"aid":115990390112737,"bvid":"BV16W6nBZEZh","cid":35736847775,"defaultWbiKey":{"wbiImgKey":"2590160e9f5142d4a501feda0490f3bd","wbiSubKey":"34ba9c5c4a824b368e9c053be34016bd"},"videoData":{"title":"真实标题","owner":{"name":"真实作者"},"pic":"https://i0.hdslb.com/test.jpg","bvid":"BV16W6nBZEZh"}};</script>
    `

    const fetchMock = vi.fn<typeof fetch>(async (input) => {
      const url = String(input)

      if (url === 'https://www.bilibili.com/video/BV16W6nBZEZh') {
        return new Response(html, {
          status: 200,
          headers: { 'content-type': 'text/html' },
        })
      }

      if (url.startsWith('https://api.bilibili.com/x/player/wbi/playurl?')) {
        const parsed = new URL(url)
        expect(parsed.searchParams.get('avid')).toBe('115990390112737')
        expect(parsed.searchParams.get('bvid')).toBe('BV16W6nBZEZh')
        expect(parsed.searchParams.get('cid')).toBe('35736847775')
        expect(parsed.searchParams.get('fnval')).toBe('16')
        expect(parsed.searchParams.get('fourk')).toBe('1')
        expect(parsed.searchParams.get('wts')).toBeTruthy()
        expect(parsed.searchParams.get('w_rid')).toBeTruthy()

        return new Response(JSON.stringify({
          code: 0,
          data: {
            timelength: 1051000,
            dash: {
              video: [{ baseUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s' }],
              audio: [{ baseUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s' }],
            },
          },
        }), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        })
      }

      throw new Error(`Unexpected fetch URL: ${url}`)
    })

    globalThis.fetch = fetchMock

    try {
      await expect(resolveBilibiliSource('https://www.bilibili.com/video/BV16W6nBZEZh')).resolves.toEqual({
        sourceUrl: 'https://www.bilibili.com/video/BV16W6nBZEZh',
        resolvedUrl: 'https://www.bilibili.com/video/BV16W6nBZEZh',
        videoId: 'BV16W6nBZEZh',
        author: '真实作者',
        title: '真实标题',
        coverUrl: 'https://i0.hdslb.com/test.jpg',
        durationSeconds: 1051,
        videoTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
        audioTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
        requestHeaders: {
          'User-Agent': expect.any(String),
          Referer: 'https://www.bilibili.com/video/BV16W6nBZEZh',
          Origin: 'https://www.bilibili.com',
        },
        playbackMode: 'dash',
      })
      expect(fetchMock).toHaveBeenCalledTimes(2)
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  it('uses the requested page cid for multi-part videos', async () => {
    const originalFetch = globalThis.fetch
    const html = `
      <script>window.__INITIAL_STATE__={"aid":115990390112737,"bvid":"BV16W6nBZEZh","p":2,"defaultWbiKey":{"wbiImgKey":"2590160e9f5142d4a501feda0490f3bd","wbiSubKey":"34ba9c5c4a824b368e9c053be34016bd"},"videoData":{"title":"多P标题","owner":{"name":"作者"},"pic":"https://i0.hdslb.com/test.jpg","bvid":"BV16W6nBZEZh","pages":[{"page":1,"cid":11111111111},{"page":2,"cid":22222222222}]}};</script>
    `

    const fetchMock = vi.fn<typeof fetch>(async (input) => {
      const url = String(input)

      if (url === 'https://www.bilibili.com/video/BV16W6nBZEZh?p=2') {
        return new Response(html, {
          status: 200,
          headers: { 'content-type': 'text/html' },
        })
      }

      if (url.startsWith('https://api.bilibili.com/x/player/wbi/playurl?')) {
        const parsed = new URL(url)
        expect(parsed.searchParams.get('cid')).toBe('22222222222')

        return new Response(JSON.stringify({
          code: 0,
          data: {
            durl: [{ url: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video-p2.mp4' }],
          },
        }), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        })
      }

      throw new Error(`Unexpected fetch URL: ${url}`)
    })

    globalThis.fetch = fetchMock

    try {
      await expect(resolveBilibiliSource('https://www.bilibili.com/video/BV16W6nBZEZh?p=2')).resolves.toMatchObject({
        resolvedUrl: 'https://www.bilibili.com/video/BV16W6nBZEZh?p=2',
        playbackMode: 'progressive',
        playableVideoUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video-p2.mp4',
      })
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  it('surfaces playurl business failures as upstream errors instead of unsupported media', async () => {
    const originalFetch = globalThis.fetch
    const html = `
      <script>window.__INITIAL_STATE__={"aid":115990390112737,"bvid":"BV16W6nBZEZh","cid":35736847775,"defaultWbiKey":{"wbiImgKey":"2590160e9f5142d4a501feda0490f3bd","wbiSubKey":"34ba9c5c4a824b368e9c053be34016bd"},"videoData":{"title":"真实标题","owner":{"name":"真实作者"},"pic":"https://i0.hdslb.com/test.jpg","bvid":"BV16W6nBZEZh"}};</script>
    `

    const fetchMock = vi.fn<typeof fetch>(async (input) => {
      const url = String(input)

      if (url === 'https://www.bilibili.com/video/BV16W6nBZEZh') {
        return new Response(html, {
          status: 200,
          headers: { 'content-type': 'text/html' },
        })
      }

      if (url.startsWith('https://api.bilibili.com/x/player/wbi/playurl?')) {
        return new Response(JSON.stringify({
          code: -352,
          message: 'risk control',
          data: null,
        }), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        })
      }

      throw new Error(`Unexpected fetch URL: ${url}`)
    })

    globalThis.fetch = fetchMock

    try {
      await expect(resolveBilibiliSource('https://www.bilibili.com/video/BV16W6nBZEZh')).rejects.toMatchObject({
        message: '请求 B 站播放信息失败',
        statusCode: 502,
      })
    } finally {
      globalThis.fetch = originalFetch
    }
  })
})

