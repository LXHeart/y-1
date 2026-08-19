// @vitest-environment happy-dom
/**
 * 任务书 #42 Stage 3：门店媒体管理组件测试。
 *
 * 覆盖：四类分组渲染、kind→accept 映射、上传编排（票据体取真实 type/size、confirm 后显式绑定）、
 * 选错类型前端即拒、帽预检（帽满隐藏上传按钮/超名额拒收）、多文件并发 ≤2、删除二次确认、整类调序。
 */
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import StoreMediaManager from './StoreMediaManager.vue'
import type { StoreMediaManageItem } from '../types/grassland'

enableAutoUnmount(afterEach)

afterEach(() => {
  vi.unstubAllGlobals()
})

function manageItem(mediaId: string, kind: StoreMediaManageItem['kind'], position: number): StoreMediaManageItem {
  return {
    mediaId, kind, mimeType: kind === 'video' ? 'video/mp4' : 'image/jpeg',
    sizeBytes: 100, position, uploadedByAccountId: 'account-1',
    createdAt: '2026-08-19T00:00:00Z', downloadUrl: `https://cdn.test/${mediaId}`,
  }
}

function envelope(data: unknown): Response {
  return {
    ok: true, headers: { get: () => 'application/json' },
    json: async () => ({ success: true, data }),
  } as unknown as Response
}

const MEDIA_BASE = '/api/organizations/org-1/stores/store-1/media'

/** 有状态 fetch 桩：模拟后端 bind/reorder/unbind 对整店 items 的权威维护。 */
function stubStoreMediaFetch(initial: StoreMediaManageItem[]): ReturnType<typeof vi.fn> {
  let items = [...initial]
  let ticketSeq = 0
  let confirmSeq = 0
  const spy = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    if (url === 'https://minio.test/upload') return { ok: true } as unknown as Response
    if (method === 'POST' && url.endsWith('/media/upload-tickets')) {
      return envelope({
        id: `ticket-${++ticketSeq}`, objectKey: 'tmp/x', uploadUrl: 'https://minio.test/upload',
        method: 'PUT', headers: {}, expiresAt: null,
      })
    }
    if (method === 'POST' && url.includes('/confirm')) {
      return envelope({
        id: `media-new-${++confirmSeq}`, ownerAccountId: 'account-1', organizationId: 'org-1',
        purpose: 'store_media', mimeType: 'image/jpeg', sizeBytes: 100, status: 'active',
        domainType: 'store', domainId: 'store-1', checksum: null, source: 'user_upload',
        createdAt: null, expiresAt: null, deletedAt: null,
      })
    }
    if (method === 'PUT' && url.endsWith('/media/order')) {
      const body = JSON.parse(String(init?.body)) as { kind: string; orderedMediaIds: string[] }
      const group = items.filter((item) => item.kind === body.kind)
      const rest = items.filter((item) => item.kind !== body.kind)
      const reordered = body.orderedMediaIds
        .map((id, index) => ({ ...group.find((item) => item.mediaId === id)!, position: index + 1 }))
      items = [...rest, ...reordered]
      return envelope({ storeId: 'store-1', items: [...items] })
    }
    if (method === 'POST' && url.endsWith('/stores/store-1/media')) {
      const body = JSON.parse(String(init?.body)) as { kind: StoreMediaManageItem['kind']; mediaIds: string[] }
      let maxPosition = items.filter((item) => item.kind === body.kind)
        .reduce((max, item) => Math.max(max, item.position), 0)
      for (const mediaId of body.mediaIds) {
        items = [...items, manageItem(mediaId, body.kind, ++maxPosition)]
      }
      return envelope({ storeId: 'store-1', items: [...items] })
    }
    if (method === 'DELETE' && url.startsWith(`${MEDIA_BASE}/`)) {
      const mediaId = url.slice(MEDIA_BASE.length + 1)
      items = items.filter((item) => item.mediaId !== mediaId)
      return envelope({ deleted: true })
    }
    if (method === 'GET' && url.endsWith('/stores/store-1/media')) {
      return envelope({ storeId: 'store-1', items: [...items] })
    }
    return envelope(null)
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

function makeFile(name: string, type: string, size = 100): File {
  const file = new File(['abc'], name, { type })
  Object.defineProperty(file, 'size', { value: size })
  return file
}

async function selectFiles(wrapper: ReturnType<typeof mount>, kind: string, files: File[]): Promise<void> {
  const input = wrapper.find(`input[type="file"][data-kind="${kind}"]`)
  Object.defineProperty(input.element, 'files', { value: files, configurable: true })
  await input.trigger('change')
  await flushPromises()
}

describe('StoreMediaManager 分组渲染与 kind→accept 映射', () => {
  test('四类分组、计数与缩略图；上传控件 accept 按 kind 取单一映射', async () => {
    stubStoreMediaFetch([manageItem('media-s1', 'storefront', 1), manageItem('media-v1', 'video', 1)])
    const wrapper = mount(StoreMediaManager, { props: { orgId: 'org-1', storeId: 'store-1' } })
    await flushPromises()

    expect(wrapper.text()).toContain('门头照片')
    expect(wrapper.text()).toContain('环境照片')
    expect(wrapper.text()).toContain('菜单价目表')
    expect(wrapper.text()).toContain('宣传视频')
    expect(wrapper.text()).toContain('1/6')
    expect(wrapper.text()).toContain('1/3')
    expect(wrapper.text()).toContain('0/12')

    const imageAccept = 'image/jpeg,image/png,image/webp'
    expect(wrapper.find('input[data-kind="storefront"]').attributes('accept')).toBe(imageAccept)
    expect(wrapper.find('input[data-kind="environment"]').attributes('accept')).toBe(imageAccept)
    expect(wrapper.find('input[data-kind="menu"]').attributes('accept')).toBe(imageAccept)
    expect(wrapper.find('input[data-kind="video"]').attributes('accept'))
      .toBe('video/mp4,video/quicktime,video/webm')

    // 图片用管理读 URL 缩略图，视频用 video 标签。
    expect(wrapper.find('img[src="https://cdn.test/media-s1"]').exists()).toBe(true)
    expect(wrapper.find('video[src="https://cdn.test/media-v1"]').exists()).toBe(true)
  })
})

describe('上传流程', () => {
  test('票据体取真实文件 type/size；confirm 后显式绑定并刷新列表', async () => {
    const spy = stubStoreMediaFetch([])
    const wrapper = mount(StoreMediaManager, { props: { orgId: 'org-1', storeId: 'store-1' } })
    await flushPromises()

    await selectFiles(wrapper, 'menu', [makeFile('menu.jpg', 'image/jpeg', 500)])

    const ticket = spy.mock.calls.find(([url]) => String(url).endsWith('/media/upload-tickets'))
    expect(ticket).toBeDefined()
    expect(JSON.parse(String((ticket![1] as RequestInit).body))).toEqual({
      kind: 'menu', contentType: 'image/jpeg', sizeBytes: 500,
    })
    // presigned 直传走原始 fetch（不经本站信封）。
    expect(spy.mock.calls.some(([url]) => String(url) === 'https://minio.test/upload')).toBe(true)
    // confirm 成功后才显式绑定（上传与绑定两步分离）。
    const bind = spy.mock.calls.find(([url, init]) =>
      String(url).endsWith('/stores/store-1/media') && (init as RequestInit)?.method === 'POST')
    expect(bind).toBeDefined()
    expect(JSON.parse(String((bind![1] as RequestInit).body)).mediaIds).toEqual(['media-new-1'])
    expect(wrapper.find('img[src="https://cdn.test/media-new-1"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('1/12')
    expect(wrapper.text()).not.toContain('上传中')
  })

  test('选错类型前端即拒：不开票、不发绑定', async () => {
    const spy = stubStoreMediaFetch([])
    const wrapper = mount(StoreMediaManager, { props: { orgId: 'org-1', storeId: 'store-1' } })
    await flushPromises()

    await selectFiles(wrapper, 'storefront', [makeFile('wrong.gif', 'image/gif')])

    expect(wrapper.text()).toContain('不支持的文件类型')
    expect(spy.mock.calls.some(([url]) => String(url).endsWith('/media/upload-tickets'))).toBe(false)
  })

  test('帽预检：超出剩余名额的文件当场拒收', async () => {
    const spy = stubStoreMediaFetch([
      manageItem('media-v1', 'video', 1), manageItem('media-v2', 'video', 2),
    ])
    const wrapper = mount(StoreMediaManager, { props: { orgId: 'org-1', storeId: 'store-1' } })
    await flushPromises()

    // 视频帽 3、已绑 2：选 2 个文件 → 第一个上传，第二个被帽拒收。
    await selectFiles(wrapper, 'video', [
      makeFile('ok.mp4', 'video/mp4'), makeFile('over.mov', 'video/quicktime'),
    ])

    expect(wrapper.text()).toContain('已达上限')
    const ticketCalls = spy.mock.calls.filter(([url]) => String(url).endsWith('/media/upload-tickets'))
    expect(ticketCalls).toHaveLength(1)
  })

  test('帽满隐藏上传按钮', async () => {
    stubStoreMediaFetch([
      manageItem('media-v1', 'video', 1), manageItem('media-v2', 'video', 2), manageItem('media-v3', 'video', 3),
    ])
    const wrapper = mount(StoreMediaManager, { props: { orgId: 'org-1', storeId: 'store-1' } })
    await flushPromises()

    expect(wrapper.find('input[data-kind="video"]').exists()).toBe(false)
    expect(wrapper.find('input[data-kind="storefront"]').exists()).toBe(true)
  })

  test('多文件上传限并发 ≤2', async () => {
    let items: StoreMediaManageItem[] = []
    const pendingTickets: Array<(response: Response) => void> = []
    let confirmSeq = 0
    const spy = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      if (url === 'https://minio.test/upload') return { ok: true } as unknown as Response
      if (method === 'POST' && url.endsWith('/media/upload-tickets')) {
        // 挂起票据请求，手动放行以观察并发窗口。
        return new Promise<Response>((resolve) => { pendingTickets.push(resolve) })
      }
      if (method === 'POST' && url.includes('/confirm')) {
        return envelope({
          id: `media-new-${++confirmSeq}`, status: 'active', mimeType: 'image/jpeg', sizeBytes: 100,
        })
      }
      if (method === 'POST' && url.endsWith('/stores/store-1/media')) {
        const body = JSON.parse(String(init?.body)) as { kind: StoreMediaManageItem['kind']; mediaIds: string[] }
        let position = items.filter((item) => item.kind === body.kind).length
        for (const mediaId of body.mediaIds) items = [...items, manageItem(mediaId, body.kind, ++position)]
        return envelope({ storeId: 'store-1', items: [...items] })
      }
      if (method === 'GET' && url.endsWith('/stores/store-1/media')) {
        return envelope({ storeId: 'store-1', items: [...items] })
      }
      return envelope(null)
    })
    vi.stubGlobal('fetch', spy)

    const wrapper = mount(StoreMediaManager, { props: { orgId: 'org-1', storeId: 'store-1' } })
    await flushPromises()

    await selectFiles(wrapper, 'storefront', [
      makeFile('a.jpg', 'image/jpeg'), makeFile('b.jpg', 'image/jpeg'), makeFile('c.jpg', 'image/jpeg'),
    ])
    // 三个文件入场，但在途票据只有 2 个（并发帽）。
    expect(pendingTickets).toHaveLength(2)

    // 放行第一个 → 其后续（直传/confirm/绑定）完成后腾出槽位，第三个才开票。
    pendingTickets.shift()!(envelope({
      id: 'ticket-1', objectKey: 'k', uploadUrl: 'https://minio.test/upload',
      method: 'PUT', headers: {}, expiresAt: null,
    }))
    await flushPromises()
    expect(pendingTickets).toHaveLength(2) // 第三个已补位

    for (const release of pendingTickets.splice(0)) {
      release(envelope({
        id: `ticket-${Math.random()}`, objectKey: 'k', uploadUrl: 'https://minio.test/upload',
        method: 'PUT', headers: {}, expiresAt: null,
      }))
      await flushPromises()
    }
    expect(wrapper.text()).toContain('3/6')
  })
})

describe('解绑与调序', () => {
  test('删除需二次确认；确认后 DELETE 并移除条目', async () => {
    const spy = stubStoreMediaFetch([manageItem('media-s1', 'storefront', 1)])
    const wrapper = mount(StoreMediaManager, { props: { orgId: 'org-1', storeId: 'store-1' } })
    await flushPromises()

    const buttons = wrapper.findAll('.sm-item button')
    const remove = buttons.find((button) => button.text() === '删除')!
    await remove.trigger('click')
    await flushPromises()
    // 未确认前不发 DELETE。
    expect(spy.mock.calls.some(([, init]) => (init as RequestInit)?.method === 'DELETE')).toBe(false)

    const confirm = wrapper.findAll('.sm-item button').find((button) => button.text() === '确认删除')!
    await confirm.trigger('click')
    await flushPromises()

    expect(spy.mock.calls.some(([url, init]) =>
      String(url).endsWith('/media/media-s1') && (init as RequestInit)?.method === 'DELETE')).toBe(true)
    expect(wrapper.text()).toContain('暂无门头照片')
  })

  test('↑↓ 调序：PUT 整类精确序列，服务端返回为准', async () => {
    const spy = stubStoreMediaFetch([
      manageItem('media-s1', 'storefront', 1), manageItem('media-s2', 'storefront', 2),
    ])
    const wrapper = mount(StoreMediaManager, { props: { orgId: 'org-1', storeId: 'store-1' } })
    await flushPromises()

    await wrapper.find('button[aria-label="下移 门头照片第 1 项"]').trigger('click')
    await flushPromises()

    const reorder = spy.mock.calls.find(([url, init]) =>
      String(url).endsWith('/media/order') && (init as RequestInit)?.method === 'PUT')
    expect(reorder).toBeDefined()
    expect(JSON.parse(String((reorder![1] as RequestInit).body))).toEqual({
      kind: 'storefront', orderedMediaIds: ['media-s2', 'media-s1'],
    })
    const thumbs = wrapper.findAll('.sm-thumb img')
    expect(thumbs[0].attributes('src')).toBe('https://cdn.test/media-s2')
    expect(thumbs[1].attributes('src')).toBe('https://cdn.test/media-s1')
    // 首位项的上移按钮禁用（已是第一位）。
    expect(wrapper.find('button[aria-label="上移 门头照片第 1 项"]').attributes('disabled')).toBeDefined()
  })
})
