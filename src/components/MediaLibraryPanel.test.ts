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
  test('图片素材提供「送入图片编辑」，视频素材不提供；点击 emit edit-image', async () => {
    const fetchMock = mockFetch({
      '/api/content-assets?libraryType=public': { items: [
        { id: 'img-1', mediaId: 'm1', libraryType: 'public', category: 'scene',
          title: '门店招牌图', tags: [], status: 'active', version: 1, mimeType: 'image/png', sizeBytes: 1024,
          source: '平台素材', createdAt: '2026-08-07T00:00:00Z', updatedAt: '2026-08-07T00:00:00Z' },
        { id: 'vid-1', mediaId: 'm2', libraryType: 'public', category: 'scene',
          title: '探店视频', tags: [], status: 'active', version: 1, mimeType: 'video/mp4', sizeBytes: 2048,
          source: '平台素材', createdAt: '2026-08-07T00:00:00Z', updatedAt: '2026-08-07T00:00:00Z' },
      ] },
      '/api/me/identities': [],
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(MediaLibraryPanel, { props: { authenticated: true } })
    await flushPromises()
    const publicTab = wrapper.findAll('button[role="tab"]').find((b) => b.text().includes('公共'))!
    await publicTab.trigger('click')
    await flushPromises()

    const editButtons = wrapper.findAll('button').filter((b) => b.text().includes('送入图片编辑'))
    expect(editButtons).toHaveLength(1)   // 仅图片素材

    const emitted: Array<{ id: string; title: string; mimeType: string }> = []
    wrapper.vm.$emit // ensure instance
    editButtons[0].trigger('click')
    // 直接断言组件事件
    const events = wrapper.emitted<{ id: string; title: string; mimeType: string }[]>('edit-image')
    expect(events).toBeTruthy()
    expect(events![0][0]).toEqual({ id: 'img-1', title: '门店招牌图', mimeType: 'image/png' })
  })

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

  test('关键词搜索会把 q 参数透传到素材列表接口', async () => {
    const fetchMock = mockFetch({
      '/api/content-assets': { items: [] },
      '/api/me/identities': [],
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(MediaLibraryPanel, { props: { authenticated: false } })
    await flushPromises()
    await wrapper.findAll('button[role="tab"]').find((button) => button.text().includes('公共'))!.trigger('click')
    await flushPromises()
    await wrapper.get('.lib-semantic-search input').setValue('门店 海报')
    await wrapper.get('.lib-semantic-search').trigger('submit')
    await flushPromises()

    expect(fetchMock.mock.calls.map(([url]) => String(url)))
      .toContain('/api/content-assets?libraryType=public&q=%E9%97%A8%E5%BA%97+%E6%B5%B7%E6%8A%A5')
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
    const fetchMock = vi.fn(async (url: string | URL, _init?: RequestInit) => {
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

// ---------- 任务书 #33：智能推荐 tab 的语义搜索与可解释得分 ----------

describe('MediaLibraryPanel 语义搜索（#33）', () => {
  const baseAsset = {
    id: 's1', mediaId: 'm1', libraryType: 'personal', category: 'campaign',
    title: '开业 海报', tags: [], status: 'active', version: 1,
    mimeType: 'image/png', sizeBytes: 1024,
    createdAt: '2026-08-18T00:00:00Z', updatedAt: '2026-08-18T00:00:00Z',
  }

  function appliedResult() {
    return {
      items: [{
        ...baseAsset, score: 82, ruleScore: 70, semanticScore: 90,
        reasons: ['标题命中「开业」', '语义匹配 90'],
      }],
      query: {
        platform: '', contentForm: '', category: '', terms: ['开业'],
        semantic: { status: 'applied' as const, provider: 'sandbox', model: 'sandbox-embedding-v1', sandbox: true },
      },
    }
  }

  function stubRecommend(result: unknown) {
    return vi.fn(async (url: string | URL) => {
      const path = typeof url === 'string' ? url : url.pathname + url.search
      if (path.includes('/api/content-assets/recommendations')) return envelope(result)
      if (path.includes('/api/me/identities')) return envelope([])
      return envelope({ items: [] })
    })
  }

  async function openRecommend(wrapper: Awaited<ReturnType<typeof mount>>) {
    await wrapper.findAll('button[role="tab"]').find((b) => b.text().includes('智能推荐'))!.trigger('click')
    await flushPromises()
  }

  test('搜索表单只在智能推荐 tab 显示，个人素材 tab 不显示', async () => {
    vi.stubGlobal('fetch', stubRecommend(appliedResult()))
    const wrapper = mount(MediaLibraryPanel, { props: { authenticated: true } })
    await flushPromises()

    expect(wrapper.find('input[data-testid="semantic-query"]').exists()).toBe(false)

    await openRecommend(wrapper)
    expect(wrapper.find('input[data-testid="semantic-query"]').exists()).toBe(true)
  })

  test('提交发送 trim 后的 query；Enter 也能提交', async () => {
    const spy = stubRecommend(appliedResult())
    vi.stubGlobal('fetch', spy)
    const wrapper = mount(MediaLibraryPanel, { props: { authenticated: true } })
    await openRecommend(wrapper)

    const input = wrapper.find('input[data-testid="semantic-query"]')
    await input.setValue('  开业 海报  ')
    await wrapper.findAll('button').find((b) => b.text().includes('搜索'))!.trigger('click')
    await flushPromises()

    expect(spy.mock.calls.some((c) => String(c[0]).includes('query=')
      && String(c[0]).includes('%E5%BC%80%E4%B8%9A'))).toBe(true)

    // Enter 提交不整页刷新（form submit 拦截）
    await input.setValue('新店 开业')
    await wrapper.find('form[data-testid="semantic-search"]').trigger('submit')
    await flushPromises()
    expect(spy.mock.calls.filter((c) => String(c[0]).includes('query=')).length).toBeGreaterThanOrEqual(2)
  })

  test('任务模式空 query 省略参数（用权威任务文本派生）', async () => {
    const spy = stubRecommend(appliedResult())
    vi.stubGlobal('fetch', spy)
    const wrapper = mount(MediaLibraryPanel, {
      props: {
        authenticated: true,
        recommendationContext: { applicationId: 'app-1', taskId: 'task-1' },
      },
    })
    await openRecommend(wrapper)

    await wrapper.findAll('button').find((b) => b.text().includes('搜索'))!.trigger('click')
    await flushPromises()

    const recommendationCalls = spy.mock.calls
      .filter((c) => String(c[0]).includes('/api/content-assets/recommendations'))
    expect(recommendationCalls.length).toBeGreaterThanOrEqual(1)
    expect(recommendationCalls.some((c) => String(c[0]).includes('query='))).toBe(false)
    expect(recommendationCalls.some((c) => String(c[0]).includes('applicationId=app-1'))).toBe(true)
  })

  test('applied 结果展示总分/规则/语义得分与理由', async () => {
    vi.stubGlobal('fetch', stubRecommend(appliedResult()))
    const wrapper = mount(MediaLibraryPanel, { props: { authenticated: true } })
    await openRecommend(wrapper)

    expect(wrapper.text()).toContain('匹配度 82')
    expect(wrapper.text()).toContain('规则 70')
    expect(wrapper.text()).toContain('语义 90')
    expect(wrapper.text()).toContain('语义匹配 90')
  })

  test('fallback 提示非阻断且规则结果仍可选择', async () => {
    const fallbackResult = {
      items: [{
        ...baseAsset, score: 55, ruleScore: 55,
        reasons: ['标题命中「开业」'],
      }],
      query: {
        platform: '', contentForm: '', category: '', terms: [],
        semantic: { status: 'fallback' as const, message: '语义检索暂不可用，已按规则排序' },
      },
    }
    vi.stubGlobal('fetch', stubRecommend(fallbackResult))
    const wrapper = mount(MediaLibraryPanel, {
      props: { authenticated: true, selectable: true, selectedAssetIds: [] },
    })
    await openRecommend(wrapper)

    expect(wrapper.text()).toContain('语义检索暂不可用，已按规则排序')
    expect(wrapper.text()).toContain('匹配度 55')
    expect(wrapper.text()).not.toContain('语义 90')
    const checkbox = wrapper.find('input[type="checkbox"]')
    expect((checkbox.element as HTMLInputElement).disabled).toBe(false)
    await checkbox.setValue()
    expect(wrapper.emitted('selection-change')?.[0]?.[0]).toEqual(['s1'])
  })

  test('无结果与请求失败文案不同；二次搜索替换陈旧得分', async () => {
    let empty = true
    const spy = vi.fn(async (url: string | URL) => {
      const path = typeof url === 'string' ? url : url.pathname + url.search
      if (path.includes('/api/content-assets/recommendations')) {
        if (empty) {
          return envelope({ items: [], query: { platform: '', contentForm: '', category: '', terms: [], semantic: { status: 'not_requested' } } })
        }
        return envelope(appliedResult())
      }
      if (path.includes('/api/me/identities')) return envelope([])
      return envelope({ items: [] })
    })
    vi.stubGlobal('fetch', spy)
    const wrapper = mount(MediaLibraryPanel, { props: { authenticated: true } })
    await openRecommend(wrapper)
    expect(wrapper.text()).toContain('暂无可推荐的素材')

    empty = false
    await wrapper.find('input[data-testid="semantic-query"]').setValue('开业')
    await wrapper.findAll('button').find((b) => b.text().includes('搜索'))!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('匹配度 82')

    // 请求失败：错误提示与空态文案是两条不同文案
    const failing = vi.fn(async () => new Response(JSON.stringify({ success: false, error: '请求失败（502）' }), {
      status: 502, headers: { 'Content-Type': 'application/json' },
    }))
    vi.stubGlobal('fetch', failing)
    await wrapper.findAll('button').find((b) => b.text().includes('搜索'))!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('请求失败（502）')
  })
})

describe('MediaLibraryPanel 素材版本比较（PRD §4.8 历史快照）', () => {
  function stubPersonalWithVersions() {
    vi.stubGlobal('fetch', mockFetch({
      '/api/content-assets?libraryType=personal': { items: [
        { id: 'p1', mediaId: 'm1', libraryType: 'personal', category: 'copy',
          title: '改后文案', tags: [], status: 'active', version: 2,
          createdAt: '2026-08-07T00:00:00Z', updatedAt: '2026-08-08T00:00:00Z' },
      ] },
      '/api/content-assets/p1/versions': { items: [
        { version: 1, title: '原始文案', category: 'copy', tags: ['初稿'],
          snapshottedAt: '2026-08-07T10:00:00Z', snapshottedBy: null,
          mimeType: 'text/plain', sizeBytes: 256 },
        { version: 2, title: '改后文案', category: 'campaign', tags: [],
          snapshottedAt: '2026-08-08T10:00:00Z', snapshottedBy: 'u1',
          mimeType: 'text/plain', sizeBytes: 512 },
      ] },
      '/api/me/identities': [],
    }))
  }

  test('个人 tab 点「版本」展开快照对比，变更字段高亮', async () => {
    stubPersonalWithVersions()

    const wrapper = mount(MediaLibraryPanel, { props: { authenticated: true } })
    await flushPromises()

    expect(wrapper.find('.cavh').exists()).toBe(false)

    const versionButton = wrapper.findAll('button').find((b) => b.text() === '版本')
    expect(versionButton).toBeDefined()
    await versionButton!.trigger('click')
    await flushPromises()

    expect(wrapper.find('.cavh').exists()).toBe(true)
    const compare = wrapper.get('[data-testid="asset-version-compare"]')
    expect(compare.text()).toContain('原始文案')
    expect(compare.text()).toContain('改后文案')
    // 标题行 v1≠v2 应高亮，媒体类型行相同不高亮
    const changed = wrapper.findAll('.cavh-changed').map((row) => row.text())
    expect(changed.some((text) => text.includes('标题'))).toBe(true)
    expect(changed.some((text) => text.includes('媒体类型'))).toBe(false)
    expect(wrapper.text()).toContain('快照操作人')

    await wrapper.findAll('button').find((b) => b.text() === '收起版本')!.trigger('click')
    expect(wrapper.find('.cavh').exists()).toBe(false)
  })

  test('公共 tab（只读）不出现版本入口', async () => {
    vi.stubGlobal('fetch', mockFetch({
      '/api/content-assets': { items: [
        { id: 'a1', mediaId: 'm1', libraryType: 'public', category: 'scene',
          title: '行业背景图', tags: [], status: 'active', version: 1,
          createdAt: '2026-08-07T00:00:00Z', updatedAt: '2026-08-07T00:00:00Z' },
      ] },
      '/api/me/identities': [],
    }))

    const wrapper = mount(MediaLibraryPanel, { props: { authenticated: false } })
    await flushPromises()
    await wrapper.findAll('button[role="tab"]').find((b) => b.text().includes('公共'))!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('行业背景图')
    expect(wrapper.findAll('button').some((b) => b.text() === '版本')).toBe(false)
  })
})
