import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppError } from '../lib/errors.js'

const { loggerWarnMock, loggerErrorMock } = vi.hoisted(() => ({
  loggerWarnMock: vi.fn(),
  loggerErrorMock: vi.fn(),
}))

vi.mock('../lib/logger.js', () => ({
  logger: {
    warn: loggerWarnMock,
    error: loggerErrorMock,
  },
}))

describe('loadDouyinHotItems', () => {
  const originalFetch = globalThis.fetch

  beforeEach(() => {
    globalThis.fetch = originalFetch
    loggerWarnMock.mockReset()
    loggerErrorMock.mockReset()
  })

  it('normalizes the upstream payload and keeps only the top 10 items', async () => {
    globalThis.fetch = vi.fn(async () => new Response(JSON.stringify({
      code: 200,
      message: 'ok',
      data: Array.from({ length: 12 }, (_, index) => ({
        title: `热点 ${index + 1}`,
        hot_value: 1000 + index,
        cover: `https://p3-dy.byteimg.com/img/${index + 1}.jpg`,
        link: `https://www.douyin.com/hot/${index + 1}`,
      })),
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))

    const { loadDouyinHotItems } = await import('./douyin-hot.service.js')
    const result = await loadDouyinHotItems()

    expect(result.items).toHaveLength(10)
    expect(result.items[0]).toEqual({
      rank: 1,
      title: '热点 1',
      hotValue: '1000',
      cover: 'https://p3-dy.byteimg.com/img/1.jpg',
      url: 'https://www.douyin.com/hot/1',
      source: '60sapi',
    })
    expect(result.items[9]).toEqual(expect.objectContaining({
      rank: 10,
      title: '热点 10',
    }))
  })

  it('drops untrusted upstream link and cover urls', async () => {
    globalThis.fetch = vi.fn(async () => new Response(JSON.stringify({
      code: 200,
      message: 'ok',
      data: [{
        title: '热点 1',
        hot_value: 1000,
        cover: 'https://evil.example.com/track.jpg',
        link: 'javascript:alert(1)',
      }],
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))

    const { loadDouyinHotItems } = await import('./douyin-hot.service.js')
    const result = await loadDouyinHotItems()

    expect(result.items[0]).toEqual({
      rank: 1,
      title: '热点 1',
      hotValue: '1000',
      source: '60sapi',
    })
  })

  it('throws when upstream payload data is not a list', async () => {
    globalThis.fetch = vi.fn(async () => new Response(JSON.stringify({
      code: 200,
      message: 'ok',
      data: null,
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))

    const { loadDouyinHotItems } = await import('./douyin-hot.service.js')

    await expect(loadDouyinHotItems()).rejects.toMatchObject({
      statusCode: 502,
      message: '抖音热点服务返回了无效数据',
    } satisfies Partial<AppError>)
  })

  it('keeps trusted douyin urls from the upstream payload', async () => {
    globalThis.fetch = vi.fn(async () => new Response(JSON.stringify({
      code: 200,
      message: 'ok',
      data: [{
        title: '热点 1',
        hot_value: 1000,
        cover: 'https://p3-dy.byteimg.com/img/example~tplv.jpg',
        link: 'https://www.douyin.com/hot/1',
      }],
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))

    const { loadDouyinHotItems } = await import('./douyin-hot.service.js')
    const result = await loadDouyinHotItems()

    expect(result.items[0]).toEqual({
      rank: 1,
      title: '热点 1',
      hotValue: '1000',
      cover: 'https://p3-dy.byteimg.com/img/example~tplv.jpg',
      url: 'https://www.douyin.com/hot/1',
      source: '60sapi',
    })
  })

  it('throws when upstream responds with a non-200 business code', async () => {
    globalThis.fetch = vi.fn(async () => new Response(JSON.stringify({
      code: 500,
      message: 'bad gateway',
      data: [],
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))

    const { loadDouyinHotItems } = await import('./douyin-hot.service.js')

    await expect(loadDouyinHotItems()).rejects.toMatchObject({
      statusCode: 502,
      message: '获取抖音热点失败，请稍后再试',
    } satisfies Partial<AppError>)
  })

  it('throws when fetch times out', async () => {
    globalThis.fetch = vi.fn(async (_input, init) => {
      const signal = init?.signal as AbortSignal | undefined
      await new Promise((_, reject) => {
        signal?.addEventListener('abort', () => {
          reject(new DOMException('The operation was aborted.', 'AbortError'))
        })
      })
      return new Response()
    })

    const { loadDouyinHotItems } = await import('./douyin-hot.service.js')

    await expect(loadDouyinHotItems({ timeoutMs: 1 })).rejects.toMatchObject({
      statusCode: 504,
      message: '获取抖音热点超时，请稍后再试',
    } satisfies Partial<AppError>)
  })
})
