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

  test('org member 只读组织级素材，管理入口仅 org owner/admin 可见', async () => {
    const identities = [{ id: 'id-1', identityType: 'merchant', organizationId: 'org-9', status: 'active' }]
    const scopes = (role: string) => [{
      organizationId: 'org-9', organizationName: '示例组织', organizationStatus: 'active',
      permissionTier: 'basic_publish', role,
    }]
    const asset = {
      id: 'm1', mediaId: 'md1', libraryType: 'merchant', category: 'store',
      title: '组织素材', tags: [], status: 'active', version: 1,
      createdAt: '2026-08-16T00:00:00Z', updatedAt: '2026-08-16T00:00:00Z',
    }

    // member：能看到组织级列表，但没有上传/管理入口。
    vi.stubGlobal('fetch', mockFetch({
      '/api/me/identities': identities,
      '/api/me/store-scopes': [],
      '/api/me/organization-scopes': scopes('member'),
      'libraryType=merchant': { items: [asset] },
      'libraryType=personal': { items: [] },
    }))
    const memberWrapper = mount(MediaLibraryPanel, { props: { authenticated: true } })
    await flushPromises()
    await memberWrapper.findAll('button[role="tab"]')
      .find((button) => button.text().includes('商家素材'))!.trigger('click')
    await flushPromises()
    expect(memberWrapper.text()).toContain('组织素材')
    expect(memberWrapper.text()).not.toContain('添加素材')
    expect(memberWrapper.text()).not.toContain('授权推荐官')

    // admin：管理入口出现。
    vi.stubGlobal('fetch', mockFetch({
      '/api/me/identities': identities,
      '/api/me/store-scopes': [],
      '/api/me/organization-scopes': scopes('admin'),
      'libraryType=merchant': { items: [asset] },
      'libraryType=personal': { items: [] },
    }))
    const adminWrapper = mount(MediaLibraryPanel, { props: { authenticated: true } })
    await flushPromises()
    await adminWrapper.findAll('button[role="tab"]')
      .find((button) => button.text().includes('商家素材'))!.trigger('click')
    await flushPromises()
    expect(adminWrapper.text()).toContain('添加素材')
    expect(adminWrapper.text()).toContain('授权推荐官')
  })

  test('智能推荐 tab 展示带分数与理由的推荐并可勾选', async () => {
    const fetchMock = mockFetch({
      '/api/content-assets/recommendations': {
        items: [
          { id: 'r1', mediaId: 'm1', libraryType: 'personal', category: 'store',
            title: '开业招牌海报', tags: ['开业'], status: 'active', version: 1,
            createdAt: '2026-08-16T00:00:00Z', updatedAt: '2026-08-16T00:00:00Z',
            score: 68, reasons: ['标题命中「开业」', '个人素材'] },
        ],
        query: { platform: 'xiaohongshu', contentForm: 'graphic', category: 'store', terms: ['开业'] },
        sourceTitle: '新店开业探店图文',
      },
      '/api/me/identities': [],
      '/api/me/store-scopes': [],
      '/api/me/organization-scopes': [],
      'libraryType=personal': { items: [] },
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(MediaLibraryPanel, {
      props: {
        authenticated: true,
        selectable: true,
        selectedAssetIds: [],
        recommendationContext: {
          applicationId: 'app-1', taskId: 'task-1', platform: 'xiaohongshu', contentForm: 'graphic',
        },
      },
    })
    await flushPromises()
    await wrapper.findAll('button[role="tab"]').find((button) => button.text().includes('智能推荐'))!
      .trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('按任务「新店开业探店图文」')
    expect(wrapper.text()).toContain('开业招牌海报')
    expect(wrapper.text()).toContain('匹配度 68')
    expect(wrapper.text()).toContain('标题命中「开业」')
    const recommendRequest = fetchMock.mock.calls
      .map(([url]) => String(url))
      .find((url) => url.includes('/api/content-assets/recommendations'))
    expect(recommendRequest).toContain('applicationId=app-1')
    expect(recommendRequest).toContain('taskId=task-1')
    expect(recommendRequest).toContain('platform=xiaohongshu')
    await wrapper.get('input[aria-label="选择素材：开业招牌海报"]').setValue(true)
    expect(wrapper.emitted('selection-change')?.[0]?.[0]).toEqual(['r1'])
  })

  test('org admin 可把组织级素材批量迁移到门店，member 无迁移入口', async () => {
    const identities = [{ id: 'id-1', identityType: 'merchant', organizationId: 'org-m', status: 'active' }]
    const orgScopes = (role: string) => [{
      organizationId: 'org-m', organizationName: '示例组织', organizationStatus: 'active',
      permissionTier: 'basic_publish', role,
    }]
    const stores = [
      { id: 'store-1', organizationId: 'org-m', name: '一号门店', status: 'active', createdAt: null },
    ]
    const orgAssets = { items: [
      { id: 'a1', mediaId: 'md1', libraryType: 'merchant', category: 'store',
        title: '门头照', tags: [], status: 'active', version: 1,
        createdAt: '2026-08-16T00:00:00Z', updatedAt: '2026-08-16T00:00:00Z' },
    ] }
    const routes = (role: string) => ({
      '/api/me/identities': identities,
      '/api/me/store-scopes': [],
      '/api/me/organization-scopes': orgScopes(role),
      '/api/organizations/org-m/stores': stores,
      'libraryType=merchant': orgAssets,
      'libraryType=personal': { items: [] },
    })
    const migrationRoute = '/api/content-assets/store-migration'

    // member：无迁移入口。
    vi.stubGlobal('fetch', mockFetch(routes('member')))
    const memberWrapper = mount(MediaLibraryPanel, { props: { authenticated: true } })
    await flushPromises()
    await memberWrapper.findAll('button[role="tab"]')
      .find((button) => button.text().includes('商家素材'))!.trigger('click')
    await flushPromises()
    expect(memberWrapper.text()).not.toContain('迁移组织素材到门店')

    // admin：进入迁移模式 → 勾选 → 选目标门店 → 提交体锁死契约。
    const fetchMock = vi.fn(async (url: string | URL, init?: RequestInit) => {
      const path = typeof url === 'string' ? url : url.pathname + url.search
      if (path.includes(migrationRoute)) {
        return envelope({ moved: 1, items: [{ id: 'a1', moved: true }] })
      }
      for (const [key, data] of Object.entries(routes('admin'))) {
        if (path.includes(key)) return envelope(data)
      }
      return envelope(null)
    })
    vi.stubGlobal('fetch', fetchMock)
    const adminWrapper = mount(MediaLibraryPanel, { props: { authenticated: true } })
    await flushPromises()
    await adminWrapper.findAll('button[role="tab"]')
      .find((button) => button.text().includes('商家素材'))!.trigger('click')
    await flushPromises()

    await adminWrapper.get('.lib-migrate > button').trigger('click')
    await adminWrapper.get('input[aria-label="迁移素材到门店：门头照"]').setValue(true)
    const migrateButtons = adminWrapper.findAll('.lib-migrate-row button')
    const submit = migrateButtons.find((button) => button.text().includes('执行迁移'))!
    expect(submit.attributes('disabled')).toBeDefined()
    await adminWrapper.get('.lib-migrate-row select').setValue('store-1')
    expect(submit.attributes('disabled')).toBeUndefined()
    await submit.trigger('click')
    await flushPromises()

    const migrationCall = fetchMock.mock.calls.find(([url]) => String(url).includes(migrationRoute))
    expect(migrationCall).toBeDefined()
    expect((migrationCall?.[1] as RequestInit).method).toBe('POST')
    expect(JSON.parse((migrationCall?.[1] as RequestInit).body as string)).toEqual({
      storeId: 'store-1', assetIds: ['a1'],
    })
    expect(adminWrapper.text()).toContain('已迁移 1 项素材到门店')
  })
})
