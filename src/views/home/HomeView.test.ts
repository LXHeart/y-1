// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import HomeView from '../../views/home/HomeView.vue'

/**
 * HomeView 特征测试（重构安全网）。
 *
 * 锁定：创作灵感定位 hero、功能入口卡（含 AI 中心主入口）及点击 emit、热点面板的
 * 请求地址/加载态/列表渲染/空态/错误态、「带入 AI 中心创作」的 emit 载荷。
 * 热点数据由 onMounted 拉取，测试中必须 mock fetch。
 */

afterEach(() => vi.unstubAllGlobals())

enableAutoUnmount(afterEach)

function stubHotItems(payload: { ok: boolean; status?: number; body?: unknown; error?: string }) {
  const urls: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    urls.push(url)
    if (!payload.ok) {
      return {
        ok: false,
        status: payload.status ?? 500,
        headers: { get: (): string => 'application/json' },
        json: async () => ({ success: false, error: payload.error ?? '热点服务暂不可用' }),
      }
    }
    return {
      ok: true,
      status: 200,
      headers: { get: (): string => 'application/json' },
      json: async () => payload.body,
      text: async () => JSON.stringify(payload.body),
    }
  }))
  return urls
}

const HOT_ITEMS_BODY = {
  success: true,
  data: {
    items: [
      { rank: 1, title: '测试热点话题', hotValue: '12345', sourceLabel: '抖音' },
      { rank: 2, title: '第二个热点', url: 'https://example.com/hot2' },
    ],
    provider: '60s',
    fetchedAt: '2026-08-06T03:00:00.000Z',
  },
}

describe('HomeView 渲染骨架', () => {
  test('锁定创作灵感定位 hero 与功能入口卡', () => {
    stubHotItems({ ok: true, body: HOT_ITEMS_BODY })
    const wrapper = mount(HomeView)

    expect(wrapper.find('.hero .eyebrow').text()).toBe('创作灵感')
    expect(wrapper.find('.hero-title').text()).toBe('从热点与灵感出发，进入 AI 内容创作中心。')
    expect(wrapper.find('.hero-note').text()).toContain('热点是创作的一种手段')
    const cards = wrapper.findAll('.feature-card')
    expect(cards).toHaveLength(6)
    expect(cards.map((card) => card.find('.eyebrow').text())).toEqual([
      'AI 内容创作中心',
      '视频提取分析',
      '图片评价文案',
      '爆款文章',
      '图片生成',
      '脱口秀创作',
    ])
    // AI 中心主入口卡突出展示
    expect(cards[0].classes()).toContain('feature-card-primary')
    expect(cards[0].find('.feature-badge').text()).toBe('主入口')
  })

  test('点击功能卡 emit open-view 并携带目标视图（AI 中心卡跳 ai-center）', async () => {
    stubHotItems({ ok: true, body: HOT_ITEMS_BODY })
    const wrapper = mount(HomeView)
    const cards = wrapper.findAll('.feature-card')

    await cards[0].trigger('click')
    await cards[1].trigger('click')
    await cards[5].trigger('click')

    expect(wrapper.emitted('open-view')).toEqual([['ai-center'], ['video'], ['comedy']])
  })
})

describe('HomeView 热点面板', () => {
  test('挂载后请求 /api/homepage/hot-items 并渲染列表', async () => {
    const urls = stubHotItems({ ok: true, body: HOT_ITEMS_BODY })
    const wrapper = mount(HomeView)
    await flushPromises()

    expect(urls).toEqual(['/api/homepage/hot-items'])
    expect(wrapper.get('.card-title').text()).toBe('热门话题')
    const items = wrapper.findAll('.hot-item')
    expect(items).toHaveLength(2)
    expect(items[0].text()).toContain('测试热点话题')
    expect(items[0].text()).toContain('热度 12345')
    expect(items[0].text()).toContain('抖音')
    // 每条热点都有「带入 AI 中心创作」入口
    expect(wrapper.findAll('.hot-action-btn').map((b) => b.text())).toEqual(['带入 AI 中心创作', '带入 AI 中心创作'])
    // 抓取时间提示
    expect(wrapper.find('.hot-fetched-note').exists()).toBe(true)
  })

  test('「带入 AI 中心创作」 emit open-creation 并携带热点标题', async () => {
    stubHotItems({ ok: true, body: HOT_ITEMS_BODY })
    const wrapper = mount(HomeView)
    await flushPromises()

    await wrapper.findAll('.hot-action-btn')[0].trigger('click')

    const emitted = wrapper.emitted('open-creation')
    expect(emitted).toHaveLength(1)
    const entry = emitted![0][0] as { source: { type: string; title: string }; prefill: { topic: string } }
    expect(entry.source).toEqual({ type: 'hot-topic', title: '测试热点话题' })
    expect(entry.prefill).toEqual({ topic: '测试热点话题' })
  })

  test('请求失败时展示「热点暂时不可用」空态', async () => {
    stubHotItems({ ok: false, error: '热点服务暂不可用' })
    const wrapper = mount(HomeView)
    await flushPromises()

    expect(wrapper.find('.empty-title').text()).toBe('热点暂时不可用')
    expect(wrapper.find('.empty-copy').text()).toBe('热点服务暂不可用')
    expect(wrapper.findAll('.hot-item')).toHaveLength(0)
  })

  test('返回空列表时展示「暂无热点数据」', async () => {
    stubHotItems({ ok: true, body: { success: true, data: { items: [], provider: '60s' } } })
    const wrapper = mount(HomeView)
    await flushPromises()

    expect(wrapper.find('.empty-title').text()).toBe('暂无热点数据')
  })
})

describe('HomeView 热点时间范围（缺口清偿之八：今天/本周）', () => {
  test('切「今天」请求 history 端点并渲染聚合条目（出现次数）；切回「实时」回实时端点', async () => {
    const urls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      urls.push(url)
      if (url.includes('/api/homepage/hot-items/history')) {
        return {
          ok: true, status: 200,
          headers: { get: (): string => 'application/json' },
          json: async () => ({
            success: true,
            data: {
              range: 'today', since: '2026-08-20T00:00:00Z', snapshotCount: 4,
              groups: [{
                platform: 'douyin', label: '抖音',
                items: [{ rank: 1, title: '今日聚合热点', hotValue: '999', occurrences: 3 }],
              }],
            },
          }),
          text: async (): Promise<string> => '',
        }
      }
      return {
        ok: true, status: 200,
        headers: { get: (): string => 'application/json' },
        json: async () => HOT_ITEMS_BODY,
        text: async (): Promise<string> => '',
      }
    }))

    const wrapper = mount(HomeView)
    await flushPromises()
    expect(urls.some((url) => url === '/api/homepage/hot-items')).toBe(true)

    const todayTab = wrapper.findAll('.hot-range-tab').find((btn) => btn.text() === '今天')
    expect(todayTab).toBeDefined()
    await todayTab!.trigger('click')
    await flushPromises()

    expect(urls.some((url) => url === '/api/homepage/hot-items/history?range=today')).toBe(true)
    expect(wrapper.text()).toContain('今日聚合热点')
    expect(wrapper.text()).toContain('出现 3 次')
    expect(wrapper.text()).toContain('4 份')

    const liveTab = wrapper.findAll('.hot-range-tab').find((btn) => btn.text() === '实时')
    await liveTab!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('测试热点话题')
  })

  test('历史窗口无归档时展示引导提示', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/api/homepage/hot-items/history')) {
        return {
          ok: true, status: 200,
          headers: { get: (): string => 'application/json' },
          json: async () => ({
            success: true,
            data: { range: 'today', since: '2026-08-20T00:00:00Z', snapshotCount: 0, groups: [] },
          }),
          text: async (): Promise<string> => '',
        }
      }
      return {
        ok: true, status: 200,
        headers: { get: (): string => 'application/json' },
        json: async () => HOT_ITEMS_BODY,
        text: async (): Promise<string> => '',
      }
    }))

    const wrapper = mount(HomeView)
    await flushPromises()
    await wrapper.findAll('.hot-range-tab').find((btn) => btn.text() === '今天')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('暂无历史归档')
    expect(wrapper.text()).toContain('切回「实时」')
  })
})
