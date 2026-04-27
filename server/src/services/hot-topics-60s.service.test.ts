import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const { loggerInfoMock, loggerWarnMock, loggerErrorMock } = vi.hoisted(() => ({
  loggerInfoMock: vi.fn(),
  loggerWarnMock: vi.fn(),
  loggerErrorMock: vi.fn(),
}))

vi.mock('../lib/logger.js', () => ({
  logger: {
    info: loggerInfoMock,
    warn: loggerWarnMock,
    error: loggerErrorMock,
  },
}))

describe('loadMultiPlatformHotTopics', () => {
  const originalFetch = globalThis.fetch

  beforeEach(() => {
    globalThis.fetch = originalFetch
    vi.resetModules()
    vi.useRealTimers()
    loggerInfoMock.mockReset()
    loggerWarnMock.mockReset()
    loggerErrorMock.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  async function importService() {
    return import('./hot-topics-60s.service.js')
  }

  function mockFetchForPlatforms(
    platformData: Record<string, Array<{ title: string; hot_value?: string | number; link?: string; cover?: string }>>,
  ) {
    globalThis.fetch = vi.fn(async (input) => {
      const url = new URL(String(input))
      const path = url.pathname

      if (path === '/v2/douyin') return makeResponse(platformData.douyin ?? [])
      if (path === '/v2/weibo') return makeResponse(platformData.weibo ?? [])
      if (path === '/v2/zhihu') return makeResponse(platformData.zhihu ?? [])

      return makeResponse([])
    })
  }

  function makeResponse(data: unknown[], code = 200) {
    return new Response(JSON.stringify({ code, message: 'success', data }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }

  it('fetches all 3 platforms in parallel and returns groups', async () => {
    mockFetchForPlatforms({
      douyin: [{ title: '抖音热点', hot_value: '100' }],
      weibo: [{ title: '微博热点', hot_value: '200' }],
      zhihu: [{ title: '知乎热点', hot_value: '500' }],
    })

    const { loadMultiPlatformHotTopics } = await importService()
    const result = await loadMultiPlatformHotTopics()

    expect(result.groups).toHaveLength(3)
    expect(globalThis.fetch).toHaveBeenCalledTimes(3)

    const douyinGroup = result.groups.find((g) => g.platform === 'douyin')
    expect(douyinGroup).toBeDefined()
    expect(douyinGroup!.label).toBe('抖音')
    expect(douyinGroup!.items).toHaveLength(1)
    expect(douyinGroup!.items[0].title).toBe('抖音热点')
    expect(douyinGroup!.items[0].rank).toBe(1)
  })

  it('limits each platform to 20 items', async () => {
    const manyItems = Array.from({ length: 30 }, (_, i) => ({
      title: `热点 ${i + 1}`,
      hot_value: String(1000 - i),
    }))

    mockFetchForPlatforms({
      douyin: manyItems,
      weibo: [],
      zhihu: [],
    })

    const { loadMultiPlatformHotTopics } = await importService()
    const result = await loadMultiPlatformHotTopics()

    const douyinGroup = result.groups.find((g) => g.platform === 'douyin')
    expect(douyinGroup!.items).toHaveLength(20)
    expect(douyinGroup!.items[0].rank).toBe(1)
    expect(douyinGroup!.items[19].rank).toBe(20)
  })

  it('continues when some platforms fail', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = new URL(String(input))
      const path = url.pathname

      if (path === '/v2/douyin') {
        return makeResponse([{ title: '抖音热点' }])
      }

      if (path === '/v2/weibo') {
        return new Response(JSON.stringify({ code: 500, message: 'error' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }

      return makeResponse([])
    })

    const { loadMultiPlatformHotTopics } = await importService()
    const result = await loadMultiPlatformHotTopics()

    expect(result.groups.length).toBeGreaterThanOrEqual(1)
    const douyinGroup = result.groups.find((g) => g.platform === 'douyin')
    expect(douyinGroup).toBeDefined()
    expect(loggerWarnMock).toHaveBeenCalled()
    expect(loggerInfoMock).toHaveBeenCalledWith(
      expect.objectContaining({ successCount: expect.any(Number), failureCount: expect.any(Number) }),
      '60s hot topics partial failure, returning available platforms',
    )
  })

  it('throws when all platforms fail', async () => {
    globalThis.fetch = vi.fn(async () => {
      return new Response('server error', { status: 500 })
    })

    const { loadMultiPlatformHotTopics } = await importService()

    await expect(loadMultiPlatformHotTopics()).rejects.toMatchObject({
      statusCode: 502,
    })
  })

  it('handles timeout', async () => {
    globalThis.fetch = vi.fn(async () => {
      throw new DOMException('The operation was aborted.', 'AbortError')
    })

    const { loadMultiPlatformHotTopics } = await importService()

    try {
      await loadMultiPlatformHotTopics()
      expect.unreachable('should have thrown')
    } catch (error) {
      expect(error).toMatchObject({
        statusCode: 504,
        message: '获取热点超时，请稍后再试',
      })
    }
  })

  it('filters out items with empty titles', async () => {
    mockFetchForPlatforms({
      douyin: [
        { title: '有效标题' },
        { title: '' },
        { title: '   ' },
        { title: '另一个有效标题' },
      ],
      weibo: [],
      zhihu: [],
    })

    const { loadMultiPlatformHotTopics } = await importService()
    const result = await loadMultiPlatformHotTopics()

    const douyinGroup = result.groups.find((g) => g.platform === 'douyin')
    expect(douyinGroup!.items).toHaveLength(2)
    expect(douyinGroup!.items[0].title).toBe('有效标题')
    expect(douyinGroup!.items[1].title).toBe('另一个有效标题')
  })

  it('normalizes hot values from numbers and strings', async () => {
    mockFetchForPlatforms({
      douyin: [
        { title: '数字热度', hot_value: 12345 },
        { title: '字符串热度', hot_value: '99.8万' },
        { title: '空热度', hot_value: '' },
      ],
      weibo: [],
      zhihu: [],
    })

    const { loadMultiPlatformHotTopics } = await importService()
    const result = await loadMultiPlatformHotTopics()

    const douyinGroup = result.groups.find((g) => g.platform === 'douyin')
    expect(douyinGroup!.items[0].hotValue).toBe('12345')
    expect(douyinGroup!.items[1].hotValue).toBe('99.8万')
    expect(douyinGroup!.items[2].hotValue).toBeUndefined()
  })

  it('normalizes URLs to valid http/https only', async () => {
    mockFetchForPlatforms({
      douyin: [
        { title: '有效链接', link: 'https://www.douyin.com/video/123' },
        { title: '无效协议', link: 'ftp://example.com' },
        { title: '无效URL', link: 'not-a-url' },
      ],
      weibo: [],
      zhihu: [],
    })

    const { loadMultiPlatformHotTopics } = await importService()
    const result = await loadMultiPlatformHotTopics()

    const douyinGroup = result.groups.find((g) => g.platform === 'douyin')
    expect(douyinGroup!.items[0].url).toBe('https://www.douyin.com/video/123')
    expect(douyinGroup!.items[1].url).toBeUndefined()
    expect(douyinGroup!.items[2].url).toBeUndefined()
  })
})
