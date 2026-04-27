import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppError } from '../lib/errors.js'

const { loggerInfoMock, loggerWarnMock, loggerErrorMock } = vi.hoisted(() => ({
  loggerInfoMock: vi.fn(),
  loggerWarnMock: vi.fn(),
  loggerErrorMock: vi.fn(),
}))

const { loadHomepageSettingsMock } = vi.hoisted(() => ({
  loadHomepageSettingsMock: vi.fn(),
}))

const { isDatabaseConfiguredMock, queryDbMock } = vi.hoisted(() => ({
  isDatabaseConfiguredMock: vi.fn<() => boolean>().mockReturnValue(true),
  queryDbMock: vi.fn(),
}))

vi.mock('../lib/logger.js', () => ({
  logger: {
    info: loggerInfoMock,
    warn: loggerWarnMock,
    error: loggerErrorMock,
  },
}))

vi.mock('./analysis-settings.service.js', () => ({
  loadHomepageSettings: loadHomepageSettingsMock,
}))

vi.mock('../lib/db.js', () => ({
  isDatabaseConfigured: isDatabaseConfiguredMock,
  queryDb: queryDbMock,
}))

vi.mock('./douyin-hot.service.js', () => ({
  loadDouyinHotItems: vi.fn(),
}))

const { loadMultiPlatformHotTopicsMock } = vi.hoisted(() => ({
  loadMultiPlatformHotTopicsMock: vi.fn(),
}))

vi.mock('./hot-topics-60s.service.js', () => ({
  loadMultiPlatformHotTopics: loadMultiPlatformHotTopicsMock,
}))

describe('loadHomepageHotItems', () => {
  const originalFetch = globalThis.fetch

  beforeEach(() => {
    globalThis.fetch = originalFetch
    vi.resetModules()
    vi.useRealTimers()
    loggerInfoMock.mockReset()
    loggerWarnMock.mockReset()
    loggerErrorMock.mockReset()
    loadHomepageSettingsMock.mockReset()
    loadMultiPlatformHotTopicsMock.mockReset()
    isDatabaseConfiguredMock.mockReturnValue(true)
    queryDbMock.mockReset()
    queryDbMock.mockResolvedValue({ rows: [] })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  async function importService() {
    return import('./homepage-hot.service.js')
  }

  it('uses 60s provider by default and returns multi-platform groups', async () => {
    loadHomepageSettingsMock.mockReturnValue({
      hotItems: { provider: '60s' },
    })

    loadMultiPlatformHotTopicsMock.mockResolvedValue({
      groups: [
        {
          platform: 'douyin',
          label: '抖音',
          items: Array.from({ length: 20 }, (_, i) => ({
            rank: i + 1,
            title: `热点 ${i + 1}`,
            hotValue: String(1000 - i),
            url: `https://www.douyin.com/hot/${i + 1}`,
          })),
        },
        {
          platform: 'weibo',
          label: '微博',
          items: [{ rank: 1, title: '微博热点', hotValue: '500' }],
        },
      ],
    })

    const { loadHomepageHotItems } = await importService()
    const result = await loadHomepageHotItems()

    expect(result.provider).toBe('60s')
    expect(result.groups).toHaveLength(2)
    expect(result.groups![0].platform).toBe('douyin')
    expect(result.groups![0].items).toHaveLength(20)
    expect(result.groups![0].items[0]).toEqual({
      rank: 1,
      title: '热点 1',
      hotValue: '1000',
      url: 'https://www.douyin.com/hot/1',
    })
    expect(result.groups![1].platform).toBe('weibo')
  })

  it('uses ALAPI provider when configured', async () => {
    loadHomepageSettingsMock.mockReturnValue({
      hotItems: { provider: 'alapi', alapiToken: 'test-token-123' },
    })

    globalThis.fetch = vi.fn(async (input, init) => {
      const url = String(input)

      expect(init?.headers).toMatchObject({ token: 'test-token-123', Accept: 'application/json' })

      if (url.endsWith('/api/tophub/site')) {
        return new Response(JSON.stringify({
          code: 200,
          message: 'success',
          data: [
            { id: 'weibo', site: '微博', title: '微博热搜', category: '热搜' },
            { id: 'douyin', site: '抖音', title: '抖音热榜', category: '热视频' },
          ],
        }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }

      const body = JSON.parse(String(init?.body ?? '{}')) as { id?: string }
      const list = body.id === 'weibo'
        ? [
            { title: '热搜 1', link: 'https://weibo.com/1', image: null, other: '5000' },
            { title: '热搜 2', link: 'https://weibo.com/2', image: null, other: '4800' },
          ]
        : [
            { title: '抖音 1', link: 'https://www.douyin.com/1', image: 'https://example.com/1.jpg', other: '4600' },
          ]

      return new Response(JSON.stringify({
        code: 200,
        message: 'success',
        data: {
          name: body.id === 'weibo' ? '微博热搜' : '抖音热榜',
          last_update: '2026-04-19 08:00:00',
          list,
        },
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })

    const { loadHomepageHotItems } = await importService()
    const result = await loadHomepageHotItems()

    expect(result.provider).toBe('alapi')
    expect(result.items).toHaveLength(3)
    expect(result.items[0]).toEqual({
      rank: 1,
      title: '热搜 1',
      hotValue: '5000',
      url: 'https://weibo.com/1',
      sourceLabel: '微博',
    })
    expect(result.items[2]).toEqual({
      rank: 3,
      title: '抖音 1',
      hotValue: '4600',
      url: 'https://www.douyin.com/1',
      cover: 'https://example.com/1.jpg',
      sourceLabel: '抖音',
    })
  })

  it('throws when ALAPI selected but token is missing', async () => {
    loadHomepageSettingsMock.mockReturnValue({
      hotItems: { provider: 'alapi' },
    })

    const { loadHomepageHotItems } = await importService()

    await expect(loadHomepageHotItems()).rejects.toMatchObject({
      statusCode: 400,
      message: '请先在设置中配置 ALAPI Token',
    } satisfies Partial<AppError>)
  })

  it('clamps ALAPI results to 100 items', async () => {
    loadHomepageSettingsMock.mockReturnValue({
      hotItems: { provider: 'alapi', alapiToken: 'tok' },
    })

    globalThis.fetch = vi.fn(async (input, init) => {
      const url = String(input)

      if (url.endsWith('/api/tophub/site')) {
        return new Response(JSON.stringify({
          code: 200,
          message: 'success',
          data: [
            { id: 'weibo', site: '微博', title: '微博热搜', category: '热搜' },
            { id: 'weixin', site: '微信', title: '微信热榜', category: '热榜' },
          ],
        }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }

      const body = JSON.parse(String(init?.body ?? '{}')) as { id?: string }
      const count = body.id === 'weibo' ? 70 : 70

      return new Response(JSON.stringify({
        code: 200,
        message: 'success',
        data: {
          name: body.id,
          list: Array.from({ length: count }, (_, i) => ({
            title: `${body.id} item ${i + 1}`,
            other: `${1000 - i}`,
          })),
        },
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })

    const { loadHomepageHotItems } = await importService()
    const result = await loadHomepageHotItems()

    expect(result.items).toHaveLength(100)
    expect(result.items[0].rank).toBe(1)
    expect(result.items[99].rank).toBe(100)
  })

  it('handles ALAPI upstream error gracefully', async () => {
    loadHomepageSettingsMock.mockReturnValue({
      hotItems: { provider: 'alapi', alapiToken: 'tok' },
    })

    globalThis.fetch = vi.fn(async (input) => {
      const url = String(input)

      if (url.endsWith('/api/tophub/site')) {
        return new Response(JSON.stringify({
          code: 200,
          message: 'success',
          data: [{ id: 'weibo', site: '微博', title: '微博热搜', category: '热搜' }],
        }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }

      return new Response(JSON.stringify({
        code: 500,
        message: 'error',
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })

    const { loadHomepageHotItems } = await importService()

    await expect(loadHomepageHotItems()).rejects.toMatchObject({
      statusCode: 502,
      message: 'error',
    } satisfies Partial<AppError>)
  })

  it('surfaces ALAPI upstream message when upstream returns a business error', async () => {
    loadHomepageSettingsMock.mockReturnValue({
      hotItems: { provider: 'alapi', alapiToken: 'tok' },
    })

    globalThis.fetch = vi.fn(async (input) => {
      const url = String(input)

      if (url.endsWith('/api/tophub/site')) {
        return new Response(JSON.stringify({
          code: 10005,
          message: '接口剩余可用次数不足，请充值',
        }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }

      expect.unreachable('should not request tophub details when site request already fails')
    })

    const { loadHomepageHotItems } = await importService()

    await expect(loadHomepageHotItems()).rejects.toMatchObject({
      statusCode: 502,
      message: '接口剩余可用次数不足，请充值',
    } satisfies Partial<AppError>)

    expect(loggerWarnMock).toHaveBeenCalledWith(
      { upstreamCode: 10005, upstreamMessage: '接口剩余可用次数不足，请充值' },
      'ALAPI tophub upstream returned non-success code',
    )
  })

  it('handles ALAPI timeout', async () => {
    loadHomepageSettingsMock.mockReturnValue({
      hotItems: { provider: 'alapi', alapiToken: 'tok' },
    })

    let rejectFetch!: (reason: unknown) => void
    globalThis.fetch = vi.fn(async (_input, init): Promise<Response> => {
      return new Promise<Response>((_resolve, reject) => {
        rejectFetch = reject
        const signal = init?.signal as AbortSignal | undefined
        if (signal) {
          signal.addEventListener('abort', () => {
            reject(new DOMException('The operation was aborted.', 'AbortError'))
          })
        }
      })
    })

    const { loadHomepageHotItems } = await importService()
    const promise = loadHomepageHotItems()

    rejectFetch(new DOMException('The operation was aborted.', 'AbortError'))

    try {
      await promise
      expect.unreachable('should have thrown')
    } catch (error) {
      expect(error).toMatchObject({
        statusCode: 504,
        message: '获取全网热点超时，请稍后再试',
      } satisfies Partial<AppError>)
    }
  })

  it('only requests ALAPI tophub details for douyin weibo weixin and xiaohongshu', async () => {
    loadHomepageSettingsMock.mockReturnValue({
      hotItems: { provider: 'alapi', alapiToken: 'tok' },
    })

    const requestedIds: string[] = []

    globalThis.fetch = vi.fn(async (input, init) => {
      const url = String(input)

      if (url.endsWith('/api/tophub/site')) {
        return new Response(JSON.stringify({
          code: 200,
          message: 'success',
          data: [
            { id: 'douyin', site: '抖音', title: '抖音热榜', category: '热视频' },
            { id: 'weibo', site: '微博', title: '微博热搜', category: '热搜' },
            { id: 'weixin', site: '微信', title: '微信热榜', category: '热榜' },
            { id: 'xiaohongshu', site: '小红书', title: '小红书热榜', category: '种草' },
            { id: 'zhihu', site: '知乎', title: '知乎热榜', category: '热榜' },
            { id: 'baidu', site: '百度', title: '百度热搜', category: '热搜' },
          ],
        }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }

      const body = JSON.parse(String(init?.body ?? '{}')) as { id?: string }
      requestedIds.push(String(body.id))

      return new Response(JSON.stringify({
        code: 200,
        message: 'success',
        data: {
          name: body.id,
          list: [{ title: `${body.id} item`, other: '1234' }],
        },
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })

    const { loadHomepageHotItems } = await importService()
    const result = await loadHomepageHotItems()

    expect(globalThis.fetch).toHaveBeenCalledTimes(5)
    expect(requestedIds).toEqual(['douyin', 'weibo', 'weixin', 'xiaohongshu'])
    expect(result.items).toHaveLength(4)
    expect(result.items.map((item) => item.sourceLabel)).toEqual(['抖音', '微博', '微信', '小红书'])
  })

  it('reuses cached ALAPI homepage hot items within ttl', async () => {
    loadHomepageSettingsMock.mockReturnValue({
      hotItems: { provider: 'alapi', alapiToken: 'tok' },
    })

    globalThis.fetch = vi.fn(async (input, init) => {
      const url = String(input)

      if (url.endsWith('/api/tophub/site')) {
        return new Response(JSON.stringify({
          code: 200,
          message: 'success',
          data: [
            { id: 'douyin', site: '抖音', title: '抖音热榜', category: '热视频' },
            { id: 'weibo', site: '微博', title: '微博热搜', category: '热搜' },
            { id: 'weixin', site: '微信', title: '微信热榜', category: '热榜' },
            { id: 'xiaohongshu', site: '小红书', title: '小红书热榜', category: '种草' },
          ],
        }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }

      const body = JSON.parse(String(init?.body ?? '{}')) as { id?: string }
      return new Response(JSON.stringify({
        code: 200,
        message: 'success',
        data: {
          name: body.id,
          list: [{ title: `${body.id} item`, other: '1234' }],
        },
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })

    const { loadHomepageHotItems } = await importService()
    const firstResult = await loadHomepageHotItems()
    const secondResult = await loadHomepageHotItems()

    expect(firstResult).toEqual(secondResult)
    expect(globalThis.fetch).toHaveBeenCalledTimes(5)
    expect(loggerInfoMock).toHaveBeenCalledWith({ ttlMs: 5 * 60 * 1000 }, 'ALAPI homepage hot items cache miss')
    expect(loggerInfoMock).toHaveBeenCalledWith(
      { siteIds: ['douyin', 'weibo', 'weixin', 'xiaohongshu'] },
      'ALAPI homepage hot items selected sites',
    )
    expect(loggerInfoMock).toHaveBeenCalledWith({ itemCount: 4 }, 'ALAPI homepage hot items cache hit')
  })

  describe('60s hot topics DB caching', () => {
    const mockGroups = [
      {
        platform: 'douyin',
        label: '抖音',
        items: Array.from({ length: 3 }, (_, i) => ({
          rank: i + 1,
          title: `缓存热点 ${i + 1}`,
          hotValue: String(1000 - i),
          url: `https://www.douyin.com/hot/${i + 1}`,
        })),
      },
      {
        platform: 'weibo',
        label: '微博',
        items: [{ rank: 1, title: '微博缓存热点', hotValue: '500' }],
      },
    ]

    it('returns cached groups when cache is fresh', async () => {
      loadHomepageSettingsMock.mockReturnValue({
        hotItems: { provider: '60s' },
      })

      queryDbMock.mockResolvedValue({
        rows: [{
          items: mockGroups,
          fetched_at: new Date(),
        }],
      })

      const { loadHomepageHotItems } = await importService()
      const result = await loadHomepageHotItems()

      expect(result.provider).toBe('60s')
      expect(result.groups).toEqual(mockGroups)
      expect(result.groups).toHaveLength(2)
    })

    it('fetches fresh data when cache is expired', async () => {
      loadHomepageSettingsMock.mockReturnValue({
        hotItems: { provider: '60s' },
      })

      const staleDate = new Date(Date.now() - 3 * 60 * 60 * 1000)
      queryDbMock.mockResolvedValueOnce({
        rows: [{
          items: [{ platform: 'douyin', label: '抖音', items: [{ rank: 1, title: '旧数据' }] }],
          fetched_at: staleDate,
        }],
      }).mockResolvedValueOnce({ rows: [] })
      queryDbMock.mockResolvedValue({ rows: [] })

      loadMultiPlatformHotTopicsMock.mockResolvedValue({
        groups: [
          { platform: 'douyin', label: '抖音', items: [{ rank: 1, title: '新数据', hotValue: '9999' }] },
        ],
      })

      const { loadHomepageHotItems } = await importService()
      const result = await loadHomepageHotItems()

      expect(result.groups![0].items[0].title).toBe('新数据')
      expect(queryDbMock).toHaveBeenCalledWith(
        'DELETE FROM cached_hot_topics WHERE provider = $1',
        ['60s'],
      )
      expect(queryDbMock).toHaveBeenCalledWith(
        'INSERT INTO cached_hot_topics (provider, items) VALUES ($1, $2)',
        ['60s', expect.any(String)],
      )
    })

    it('falls back to stale cache when 60s API fails', async () => {
      loadHomepageSettingsMock.mockReturnValue({
        hotItems: { provider: '60s' },
      })

      const staleGroups = [{ platform: 'douyin', label: '抖音', items: [{ rank: 1, title: '旧缓存' }] }]
      const staleDate = new Date(Date.now() - 3 * 60 * 60 * 1000)
      queryDbMock.mockResolvedValueOnce({
        rows: [{ items: staleGroups, fetched_at: staleDate }],
      }).mockResolvedValue({ rows: [] })

      loadMultiPlatformHotTopicsMock.mockRejectedValue(new AppError('获取热点失败', 502))

      const { loadHomepageHotItems } = await importService()
      const result = await loadHomepageHotItems()

      expect(result.groups![0].items[0].title).toBe('旧缓存')
      expect(loggerWarnMock).toHaveBeenCalledWith(
        { err: expect.any(AppError) },
        '60s hot topics API failed, returning stale cache',
      )
    })

    it('throws when 60s API fails and no cache exists', async () => {
      loadHomepageSettingsMock.mockReturnValue({
        hotItems: { provider: '60s' },
      })

      queryDbMock.mockResolvedValue({ rows: [] })

      loadMultiPlatformHotTopicsMock.mockRejectedValue(new AppError('获取热点失败', 502))

      const { loadHomepageHotItems } = await importService()

      await expect(loadHomepageHotItems()).rejects.toMatchObject({
        statusCode: 502,
        message: '获取热点失败',
      })
    })

    it('skips cache when database is not configured', async () => {
      loadHomepageSettingsMock.mockReturnValue({
        hotItems: { provider: '60s' },
      })
      isDatabaseConfiguredMock.mockReturnValue(false)

      loadMultiPlatformHotTopicsMock.mockResolvedValue({
        groups: [
          { platform: 'douyin', label: '抖音', items: [{ rank: 1, title: '直接请求' }] },
        ],
      })

      const { loadHomepageHotItems } = await importService()
      const result = await loadHomepageHotItems()

      expect(result.groups![0].items[0].title).toBe('直接请求')
      expect(queryDbMock).not.toHaveBeenCalled()
    })

    it('degrades gracefully when DB query fails', async () => {
      loadHomepageSettingsMock.mockReturnValue({
        hotItems: { provider: '60s' },
      })

      queryDbMock.mockRejectedValue(new Error('DB connection lost'))

      loadMultiPlatformHotTopicsMock.mockResolvedValue({
        groups: [
          { platform: 'douyin', label: '抖音', items: [{ rank: 1, title: '降级请求' }] },
        ],
      })

      const { loadHomepageHotItems } = await importService()
      const result = await loadHomepageHotItems()

      expect(result.groups![0].items[0].title).toBe('降级请求')
    })

    it('ignores old-format flat items cache and fetches fresh groups', async () => {
      loadHomepageSettingsMock.mockReturnValue({
        hotItems: { provider: '60s' },
      })

      const oldFlatItems = [
        { rank: 1, title: '旧格式热点', hotValue: '100' },
        { rank: 2, title: '另一个旧热点', hotValue: '200' },
      ]
      queryDbMock.mockResolvedValueOnce({
        rows: [{ items: oldFlatItems, fetched_at: new Date() }],
      }).mockResolvedValue({ rows: [] })

      loadMultiPlatformHotTopicsMock.mockResolvedValue({
        groups: [
          { platform: 'douyin', label: '抖音', items: [{ rank: 1, title: '新格式数据' }] },
        ],
      })

      const { loadHomepageHotItems } = await importService()
      const result = await loadHomepageHotItems()

      expect(result.groups![0].items[0].title).toBe('新格式数据')
      expect(loadMultiPlatformHotTopicsMock).toHaveBeenCalled()
    })
  })
})
