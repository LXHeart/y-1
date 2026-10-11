// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { buildHomepageHotItemsUrl, useHomepageHotItems } from './useHomepageHotItems'

afterEach(() => vi.unstubAllGlobals())

function response(title: string): Response {
  return new Response(JSON.stringify({
    success: true,
    data: {
      provider: '60s',
      items: [{
        rank: 1,
        title,
        tags: { industries: ['catering'], city: '上海', contentType: 'tech', taxonomyVersion: 'hot-taxonomy-v1' },
        validUntil: '2099-08-18T00:00:00Z',
        expired: false,
      }],
      taxonomy: {
        version: 'hot-taxonomy-v1',
        industries: [{ value: 'catering', label: '餐饮' }],
        cities: ['上海'],
        contentTypes: [{ value: 'tech', label: '科技' }],
      },
    },
  }), { headers: { 'Content-Type': 'application/json' } })
}

describe('useHomepageHotItems', () => {
  it('首页刷新失败时保留上次热点，重试成功后更新', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response('上次的热点')).mockRejectedValueOnce(new Error('暂时无法连接')).mockResolvedValueOnce(response('新的热点')))
    const hot = useHomepageHotItems({ keepPreviousData: true })
    await hot.loadHotItems()
    await hot.loadHotItems()
    expect(hot.items.value[0].title).toBe('上次的热点')
    expect(hot.error.value).toBe('暂时无法连接')
    expect(hot.loading.value).toBe(false)
    await hot.loadHotItems()
    expect(hot.items.value[0].title).toBe('新的热点')
    expect(hot.error.value).toBe('')
  })
  it('按固定顺序生成筛选 URL，空筛选保持旧请求地址', () => {
    expect(buildHomepageHotItemsUrl()).toBe('/api/homepage/hot-items')
    expect(buildHomepageHotItemsUrl({
      industry: 'catering', city: '上海', contentType: 'tech', includeExpired: true,
    })).toBe('/api/homepage/hot-items?industry=catering&city=%E4%B8%8A%E6%B5%B7&contentType=tech&includeExpired=true')
  })

  it('解析服务端 taxonomy、标签和有效期字段', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response('上海AI火锅发布会')))
    const hot = useHomepageHotItems()

    await hot.loadHotItems({ industry: 'catering' })

    expect(hot.items.value[0]).toMatchObject({
      title: '上海AI火锅发布会',
      tags: { industries: ['catering'], city: '上海', contentType: 'tech' },
      expired: false,
    })
    expect(hot.taxonomy.value?.industries).toEqual([{ value: 'catering', label: '餐饮' }])
    expect(hot.filters.value).toMatchObject({ industry: 'catering', includeExpired: false })
  })

  it('快速切换筛选时忽略迟到的旧响应', async () => {
    let resolveFirst!: (value: Response) => void
    const first = new Promise<Response>((resolve) => { resolveFirst = resolve })
    vi.stubGlobal('fetch', vi.fn()
      .mockImplementationOnce(() => first)
      .mockResolvedValueOnce(response('美容新趋势')))
    const hot = useHomepageHotItems()

    const oldRequest = hot.loadHotItems({ industry: 'catering' })
    await hot.loadHotItems({ industry: 'beauty' })
    resolveFirst(response('迟到的餐饮热点'))
    await oldRequest

    expect(hot.items.value[0].title).toBe('美容新趋势')
    expect(hot.filters.value.industry).toBe('beauty')
    expect(hot.loading.value).toBe(false)
  })
})
