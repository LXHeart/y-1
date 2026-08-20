// @vitest-environment happy-dom
import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { normalizeHotItemsPayload, useDouyinHotItems } from './useDouyinHotItems'

function jsonResponse(data: unknown, ok = true, status = 200) {
  return {
    ok,
    status,
    headers: { get: () => 'application/json' },
    json: async () => data,
    text: async () => JSON.stringify(data),
  }
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn())
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('useDouyinHotItems', () => {
  test('加载并归一热点列表（可空字段省略、非法项丢弃）', async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse({
      success: true,
      data: {
        items: [
          { rank: 1, title: '夏日饮品测评', hotValue: '12345', url: 'https://www.douyin.com/video/1', cover: 'https://p3-sign.douyinpic.com/x.jpeg', source: '60sapi' },
          { rank: 2, title: '只有标题的热点' },
          { rank: 'three', title: 'rank 非数字被丢弃' },
        ],
      },
    }) as unknown as Response)

    const { items, loading, error, loadHotItems } = useDouyinHotItems()
    await loadHotItems()
    await flushPromises()

    expect(loading.value).toBe(false)
    expect(error.value).toBe('')
    expect(items.value).toHaveLength(2)
    expect(items.value[0]).toMatchObject({ rank: 1, title: '夏日饮品测评', hotValue: '12345' })
    expect(items.value[1]).toMatchObject({ rank: 2, title: '只有标题的热点' })
    expect(items.value[1].url).toBeUndefined()
    expect(vi.mocked(fetch).mock.calls[0]?.[0]).toBe('/api/douyin/hot-items')
  })

  test('信封失败展示后端错误文案', async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse({
      success: false,
      error: '获取抖音热点超时，请稍后再试',
    }) as unknown as Response)

    const { items, error, loadHotItems } = useDouyinHotItems()
    await loadHotItems()
    await flushPromises()

    expect(items.value).toEqual([])
    expect(error.value).toBe('获取抖音热点超时，请稍后再试')
  })

  test('data 为 null 时按加载失败处理', async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse({ success: true, data: null }) as unknown as Response)

    const { items, error, loadHotItems } = useDouyinHotItems()
    await loadHotItems()
    await flushPromises()

    expect(items.value).toEqual([])
    expect(error.value).toBe('加载抖音热点失败，请稍后重试')
  })
})

describe('normalizeHotItemsPayload', () => {
  test('非对象或缺失 items 返回 null', () => {
    expect(normalizeHotItemsPayload(null)).toBeNull()
    expect(normalizeHotItemsPayload('x')).toBeNull()
    expect(normalizeHotItemsPayload({})).toBeNull()
    expect(normalizeHotItemsPayload({ items: 'no' })).toBeNull()
  })
})
