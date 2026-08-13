// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import MediaLibraryPanel from './MediaLibraryPanel.vue'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

/**
 * useGrassland.request 解 {success,data} 信封。公共列表端点 GET /api/content-assets?libraryType=public
 * 的 fetch mock 要返回该格式。身份端点 GET /api/me/identities 返回空数组（无商家/推荐官身份）。
 */
function envelope(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}

function mockFetch(routes: Record<string, unknown>): ReturnType<typeof vi.fn> {
  return vi.fn(async (url: string | URL) => {
    const path = typeof url === 'string' ? url : url.pathname + url.search
    for (const [key, data] of Object.entries(routes)) {
      if (path.includes(key)) return envelope(data)
    }
    return envelope(null)
  })
}

describe('MediaLibraryPanel', () => {
  test('公共 tab 渲染公共素材列表（未登录也可）', async () => {
    const fetchMock = mockFetch({
      '/api/content-assets?libraryType=public': { items: [
        { id: 'a1', mediaId: 'm1', libraryType: 'public', category: 'scene',
          title: '行业背景图', tags: ['背景'], status: 'active', version: 1,
          source: '平台素材', createdAt: '2026-08-07T00:00:00Z', updatedAt: '2026-08-07T00:00:00Z' },
      ] },
      '/api/me/identities': [],
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(MediaLibraryPanel, { props: { authenticated: false } })
    await flushPromises()

    // 默认 personal tab 但未登录，切到 public
    const publicTab = wrapper.findAll('button[role="tab"]').find((b) => b.text().includes('公共'))!
    await publicTab.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('行业背景图')
    expect(wrapper.text()).toContain('来源：平台素材')
  })

  test('公共列表为空时显示提示', async () => {
    const fetchMock = mockFetch({
      '/api/content-assets': { items: [] },
      '/api/me/identities': [],
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(MediaLibraryPanel, { props: { authenticated: false } })
    await flushPromises()
    await wrapper.findAll('button[role="tab"]').find((b) => b.text().includes('公共'))!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('暂无公共素材')
  })

  test('个人 tab 渲染自己的素材', async () => {
    const fetchMock = mockFetch({
      '/api/content-assets?libraryType=personal': { items: [
        { id: 'p1', mediaId: 'm1', libraryType: 'personal', category: 'copy',
          title: '我的文案模板', tags: [], status: 'active', version: 1,
          mimeType: 'text/plain', sizeBytes: 512,
          createdAt: '2026-08-07T00:00:00Z', updatedAt: '2026-08-07T00:00:00Z' },
      ] },
      '/api/me/identities': [],
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(MediaLibraryPanel, { props: { authenticated: true } })
    await flushPromises()

    expect(wrapper.text()).toContain('我的文案模板')
    expect(wrapper.text()).toContain('文案')
  })

  test('可选模式勾选素材并回传稳定 ID 列表', async () => {
    const fetchMock = mockFetch({
      '/api/content-assets?libraryType=personal': { items: [
        { id: 'p1', mediaId: 'm1', libraryType: 'personal', category: 'copy',
          title: '任务文案', tags: [], status: 'active', version: 1,
          createdAt: '2026-08-07T00:00:00Z', updatedAt: '2026-08-07T00:00:00Z' },
      ] },
      '/api/me/identities': [],
      '/api/me/store-scopes': [],
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(MediaLibraryPanel, {
      props: { authenticated: true, selectable: true, selectedAssetIds: [] },
    })
    await flushPromises()
    await wrapper.get('input[aria-label="选择素材：任务文案"]').setValue(true)

    expect(wrapper.emitted('selection-change')?.[0]?.[0]).toEqual(['p1'])
    expect(wrapper.text()).toContain('已选择 1 / 50 项创作素材')
  })

  test('纯门店 MANAGER 可进入商家素材管理并限定到获授权门店', async () => {
    const fetchMock = mockFetch({
      '/api/me/identities': [],
      '/api/me/store-scopes': [{
        storeId: 'store-1', storeName: '一号门店', storeStatus: 'active',
        organizationId: 'org-1', organizationName: '示例组织', organizationStatus: 'active',
        permissionTier: 'basic_publish', role: 'manager',
      }],
      'libraryType=merchant': { items: [] },
      'libraryType=personal': { items: [] },
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(MediaLibraryPanel, { props: { authenticated: true } })
    await flushPromises()
    const merchantTab = wrapper.findAll('button[role="tab"]').find((button) => button.text().includes('商家素材'))!
    await merchantTab.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('一号门店')
    expect(wrapper.text()).toContain('添加素材')
    const merchantRequest = fetchMock.mock.calls
      .map(([url]) => String(url))
      .find((url) => url.includes('libraryType=merchant'))
    expect(merchantRequest).toContain('organizationId=org-1')
    expect(merchantRequest).toContain('storeId=store-1')
  })
})
