// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import DeliveryPanel from './DeliveryPanel.vue'

/**
 * DeliveryPanel（AI内容中心改造-02 §2.4/2.5）：平台编辑稿分字段 + readiness + 导出。
 * 修改字段只 emit patch，不触发任何生成；导出走 /exports 并渲染媒体链接。
 */

const fetchMock = vi.fn()
beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => vi.unstubAllGlobals())
enableAutoUnmount(afterEach)

describe('DeliveryPanel', () => {
  test('beforeExport waits for saving and supplies the confirmed version; false never downloads', async () => {
    let finish!: (version: number | false) => void
    const beforeExport = vi.fn(() => new Promise<number | false>(resolve => { finish = resolve }))
    const wrapper = mount(DeliveryPanel, { props: { modelValue: {}, platform: 'douyin', draftId: 'draft', draftVersion: 1, beforeExport } })
    await wrapper.get('[data-test="delivery-export"]').trigger('click'); await flushPromises()
    expect(fetchMock).not.toHaveBeenCalled()
    finish(false); await flushPromises(); expect(wrapper.get('[data-test="delivery-export-error"]').text()).toContain('尚未保存')
    await wrapper.get('[data-test="delivery-export"]').trigger('click')
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ success: true, data: { draftId: 'draft', version: 4, manifest: {}, downloads: [] } })))
    finish(4); await flushPromises()
    expect(JSON.parse(String(fetchMock.mock.calls[0][1].body)).version).toBe(4)
  })
  test('公众号显示摘要字段，小红书显示话题字段并解析 # 前缀', async () => {
    const wechat = mount(DeliveryPanel, { props: { modelValue: { titleOrOpening: 't', bodyOrDescription: 'b' }, platform: 'wechat-official' } })
    expect(wechat.find('[data-test="delivery-summary"]').exists()).toBe(true)
    expect(wechat.find('[data-test="delivery-topics"]').exists()).toBe(false)

    const xhs = mount(DeliveryPanel, { props: { modelValue: { titleOrOpening: 't', bodyOrDescription: 'b' }, platform: 'xiaohongshu' } })
    expect(xhs.find('[data-test="delivery-topics"]').exists()).toBe(true)
    await xhs.get('[data-test="delivery-topics"]').setValue('#探店 #开业')
    const updates = xhs.emitted('update:modelValue') ?? []
    const last = updates[updates.length - 1]
    expect(last?.[0]).toMatchObject({ topics: ['探店', '开业'] })
  })

  test('readiness 展示缺项；声明 pending 标记待补', () => {
    const wrapper = mount(DeliveryPanel, { props: {
      modelValue: { titleOrOpening: '标题', bodyOrDescription: '正文', platform: 'xiaohongshu' }, platform: 'xiaohongshu',
    } })
    const items = wrapper.findAll('[data-test="delivery-readiness"] li')
    expect(items.some(item => item.text().includes('话题') && item.text().includes('待补'))).toBe(true)
    expect(items.some(item => item.text().includes('声明状态') && item.text().includes('待补'))).toBe(true)
  })

  test('导出：POST /exports 下载 manifest 并列出媒体链接；失效项标注', async () => {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ success: true, data: {
      draftId: 'draft-1', version: 3, expiresAt: '2026-09-09T00:00:00Z',
      manifest: { title: '标题' },
      downloads: [
        { refType: 'media', id: 'm-1', role: 'card', position: 1, url: 'https://storage.test/m1?sig=1' },
        { refType: 'media', id: 'm-2', role: 'card', position: 2, unavailable: 'expired' },
      ],
    } }), { headers: { 'Content-Type': 'application/json' } }))
    const wrapper = mount(DeliveryPanel, { props: {
      modelValue: { titleOrOpening: '标题', bodyOrDescription: '正文' }, platform: 'xiaohongshu', draftId: 'draft-1',
    } })
    await wrapper.get('[data-test="delivery-export"]').trigger('click')
    await flushPromises()

    expect(fetchMock.mock.calls[0][0]).toBe('/api/creation-drafts/draft-1/exports')
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toMatchObject({ format: 'bundle-manifest' })
    const links = wrapper.findAll('[data-test="delivery-downloads"] a')
    expect(links).toHaveLength(1)
    expect(links[0].attributes('href')).toContain('https://storage.test/m1')
    expect(wrapper.text()).toContain('已失效，重新导出可按授权重取')
  })

  test('导出失败显示错误，不触发任何生成请求', async () => {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ success: false, error: '草稿不存在' }), { status: 404 }))
    const wrapper = mount(DeliveryPanel, { props: {
      modelValue: { titleOrOpening: '标题', bodyOrDescription: '正文' }, platform: 'xiaohongshu', draftId: 'draft-x',
    } })
    await wrapper.get('[data-test="delivery-export"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="delivery-export-error"]').text()).toContain('草稿不存在')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  // ---- 任务书 #101 C101-18：新格式真实文件导出 ----

  test('新格式导出：选 ZIP → POST 新端点 → 轮询 building → 真实文件下载', async () => {
    const calls: Array<{ url: string; body?: Record<string, unknown> }> = []
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      const body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : undefined
      calls.push({ url, body })
      if (String(url).endsWith('/exports') && init?.method === 'POST') {
        // 首次 building（202 语义在 data 层——mock 统一 200）；随后 GET ready
        return new Response(JSON.stringify({ success: true, data: { exportId: 'exp-1', state: 'building', error: null } }))
      }
      if (String(url).endsWith('/api/creation-studio/exports/exp-1')) {
        return new Response(JSON.stringify({ success: true, data: {
          draftId: 'draft-1', version: 4, format: 'bundle-zip',
          file: { exportId: 'exp-1', filename: '导出.zip', contentType: 'application/zip',
            sha256: 'h', url: 'https://signed.test.invalid/creation-exports/exp-1.zip?sig=1',
            sizeBytes: 99, expiresAt: '2999-01-01T00:00:00Z' },
          missingItems: [],
        } }))
      }
      return new Response(JSON.stringify({ success: true, data: {} }))
    })
    const downloads: string[] = []
    vi.stubGlobal('URL', URL)
    const clickSpy = vi.fn()
    const createElement = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tag) => {
      const element = createElement(tag)
      if (tag === 'a') {
        element.click = () => { downloads.push((element as HTMLAnchorElement).download) }
        void clickSpy
      }
      return element
    })
    const wrapper = mount(DeliveryPanel, { props: {
      modelValue: { titleOrOpening: '标题' }, platform: 'wechat-official', draftId: 'draft-1', draftVersion: 4,
    } })
    await wrapper.get('[data-test="studio-export"]').trigger('click')
    await vi.waitFor(() => {
      expect(wrapper.get('[data-test="studio-export-done"]').text()).toContain('导出.zip')
    }, { timeout: 10_000 })
    // 请求体：requestId+version+format（新端点契约）
    expect(calls[0].body).toMatchObject({ version: 4, format: 'bundle-zip' })
    expect(calls[0].body?.requestId).toBeTruthy()
    expect(downloads).toEqual(['导出.zip'])
  })

  test('新格式导出失败（缺媒体）：明确错误不伪装下载', async () => {
    fetchMock.mockImplementation(async () => new Response(JSON.stringify({ success: true, data: {
      exportId: 'exp-2', state: 'failed', error: { code: 'STUDIO_EXPORT_MISSING_MEDIA', message: '存在不可用媒体，导出未完成' },
    } })))
    const wrapper = mount(DeliveryPanel, { props: {
      modelValue: { titleOrOpening: '标题' }, platform: 'wechat-official', draftId: 'draft-x', draftVersion: 2,
    } })
    await wrapper.get('[data-test="studio-export"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="studio-export-error"]').text()).toContain('不可用媒体')
    expect(wrapper.find('[data-test="studio-export-done"]').exists()).toBe(false)
  })

  // ---- 任务书 #101 C101-12：采用媒体与 readiness（未采用不算完成） ----

  test('mediaExpected 未采用不算完成：媒体项待补且不显示采用区块', () => {
    const wrapper = mount(DeliveryPanel, { props: {
      modelValue: { titleOrOpening: '标题', bodyOrDescription: '正文', topics: ['探店'] }, platform: 'xiaohongshu',
      mediaExpected: true,
    } })
    const items = wrapper.findAll('[data-test="delivery-readiness"] li')
    expect(items.some(item => item.text().includes('选定媒体') && item.text().includes('待补'))).toBe(true)
    expect(wrapper.find('[data-test="delivery-adopted-media"]').exists()).toBe(false)
  })

  test('已采用媒体：readiness 就绪、封面/图卡计数明确（部分成功不冒充完整）', () => {
    const wrapper = mount(DeliveryPanel, { props: {
      modelValue: {
        titleOrOpening: '标题', bodyOrDescription: '正文', topics: ['探店'],
        coverRef: { id: 'm-1', refType: 'media', role: 'cover', cardId: 'c-1', position: 1 },
        mediaRefs: [
          { id: 'm-1', refType: 'media', role: 'card', cardId: 'c-1', position: 1 },
          { id: 'm-2', refType: 'media', role: 'card', cardId: 'c-2', position: 2 },
        ],
      }, platform: 'xiaohongshu', mediaExpected: true,
    } })
    const items = wrapper.findAll('[data-test="delivery-readiness"] li')
    expect(items.some(item => item.text().includes('选定媒体') && item.text().includes('待补'))).toBe(false)
    const adopted = wrapper.get('[data-test="delivery-adopted-media"]')
    expect(adopted.text()).toContain('封面 已就绪')
    expect(adopted.text()).toContain('图卡 1 张')
    // 封面外的采用卡逐张列出
    expect(wrapper.find('[data-test="delivery-adopted-card-1"]').exists()).toBe(true)
  })
})
