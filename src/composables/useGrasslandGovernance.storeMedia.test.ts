/**
 * 任务书 #42 Stage 3：门店媒体库 composable 测试。
 *
 * 覆盖：上传编排（票据体取真实 type/size → presigned → confirm 成功后才返回 mediaId、
 * 不自动绑定）、kind→accept/帽映射（STORE_MEDIA_KIND_META 单一权威）、开票前预检、
 * 管理端点 URL/body 契约。mock request/putToPresignedUrl，不打真实网络。
 */
import { beforeEach, describe, expect, test, vi } from 'vitest'
import type { RunFn } from './grassland-http'

vi.mock('./grassland-http', () => ({
  request: vi.fn(),
  putToPresignedUrl: vi.fn(),
}))

import { putToPresignedUrl, request } from './grassland-http'
import { useGrasslandGovernance } from './useGrasslandGovernance'
import { STORE_MEDIA_KIND_META, STORE_MEDIA_KINDS } from '../types/grassland'
import type { MediaMetadata, MediaUploadTicket } from '../types/grassland'

const requestMock = vi.mocked(request)
const putMock = vi.mocked(putToPresignedUrl)

/** 直通 run：失败原样抛出，便于断言预检与 confirm 失败路径。 */
const run: RunFn = (operation) => operation()

const TICKET: MediaUploadTicket = {
  id: 'ticket-1', objectKey: 'tmp/store-media/x', uploadUrl: 'https://minio.test/upload',
  method: 'PUT', headers: { 'Content-Type': 'image/jpeg' }, expiresAt: null,
}

const CONFIRMED = {
  id: 'media-9', ownerAccountId: 'account-1', organizationId: 'org-1', purpose: 'store_media',
  domainType: 'store', domainId: 'store-1', mimeType: 'image/jpeg', sizeBytes: 3,
  checksum: null, source: 'user_upload', status: 'active', createdAt: null,
  expiresAt: null, deletedAt: null,
} as MediaMetadata

function makeFile(type: string, size = 3): File {
  const file = new File(['abc'], `file-${type.split('/').join('.')}`, { type })
  Object.defineProperty(file, 'size', { value: size })
  return file
}

beforeEach(() => {
  vi.clearAllMocks()
  requestMock.mockReset()
  putMock.mockReset()
})

describe('STORE_MEDIA_KIND_META 单一权威映射（任务书 #42 D7）', () => {
  test('中文标签与展示顺序', () => {
    expect(STORE_MEDIA_KINDS).toEqual(['storefront', 'environment', 'menu', 'video'])
    expect(STORE_MEDIA_KIND_META.storefront.label).toBe('门头照片')
    expect(STORE_MEDIA_KIND_META.environment.label).toBe('环境照片')
    expect(STORE_MEDIA_KIND_META.menu.label).toBe('菜单价目表')
    expect(STORE_MEDIA_KIND_META.video.label).toBe('宣传视频')
  })

  test('kind→accept：图片三类同白名单、视频独立白名单', () => {
    const imageAccept = 'image/jpeg,image/png,image/webp'
    expect(STORE_MEDIA_KIND_META.storefront.accept).toBe(imageAccept)
    expect(STORE_MEDIA_KIND_META.environment.accept).toBe(imageAccept)
    expect(STORE_MEDIA_KIND_META.menu.accept).toBe(imageAccept)
    expect(STORE_MEDIA_KIND_META.video.accept).toBe('video/mp4,video/quicktime,video/webm')
  })

  test('帽：数量 6/12/12/3，图片 10MB、视频 20MB', () => {
    expect(STORE_MEDIA_KIND_META.storefront.maxCount).toBe(6)
    expect(STORE_MEDIA_KIND_META.environment.maxCount).toBe(12)
    expect(STORE_MEDIA_KIND_META.menu.maxCount).toBe(12)
    expect(STORE_MEDIA_KIND_META.video.maxCount).toBe(3)
    expect(STORE_MEDIA_KIND_META.storefront.maxBytes).toBe(10 * 1024 * 1024)
    expect(STORE_MEDIA_KIND_META.menu.maxBytes).toBe(10 * 1024 * 1024)
    expect(STORE_MEDIA_KIND_META.video.maxBytes).toBe(20 * 1024 * 1024)
  })
})

describe('uploadStoreMediaFile 三步上传编排', () => {
  test('票据体取真实文件 type/size；presigned → confirm 成功后才返回 mediaId；不自动绑定', async () => {
    const calls: string[] = []
    requestMock.mockImplementation(async (url: string, init?: RequestInit) => {
      calls.push(`${init?.method ?? 'GET'} ${url}`)
      if (url.endsWith('/media/upload-tickets')) return TICKET
      if (url.endsWith('/confirm')) return CONFIRMED
      throw new Error(`unexpected request: ${url}`)
    })
    putMock.mockImplementation(async () => { calls.push('PUT presigned') })

    const governance = useGrasslandGovernance(run)
    const file = makeFile('image/jpeg', 2048)
    const mediaId = await governance.uploadStoreMediaFile('org-1', 'store-1', 'menu', file)

    expect(mediaId).toBe('media-9')
    // 票据体 = 真实文件的 type/size（confirm 按对象 HEAD 逐字节校验，两处必须同源）。
    const ticketCall = requestMock.mock.calls.find(([url]) => url.endsWith('/media/upload-tickets'))
    expect(ticketCall?.[0]).toBe('/api/organizations/org-1/stores/store-1/media/upload-tickets')
    expect(JSON.parse(String((ticketCall?.[1] as RequestInit).body))).toEqual({
      kind: 'menu', contentType: 'image/jpeg', sizeBytes: 2048,
    })
    // 顺序：开票 → presigned 直传 → confirm。
    expect(calls).toEqual([
      'POST /api/organizations/org-1/stores/store-1/media/upload-tickets',
      'PUT presigned',
      'POST /api/media/ticket-1/confirm',
    ])
    expect(putMock).toHaveBeenCalledWith(TICKET, file)
    // 不自动绑定：没有任何 POST /media 绑定调用（绑定由管理端显式发起）。
    expect(requestMock.mock.calls.every(([url, init]) =>
      !((init?.method === 'POST') && url.endsWith('/stores/store-1/media')))).toBe(true)
  })

  test('confirm 失败则不返回 mediaId（错误透传）', async () => {
    requestMock.mockImplementation(async (url: string) => {
      if (url.endsWith('/media/upload-tickets')) return TICKET
      throw new Error('对象校验失败')
    })
    putMock.mockResolvedValue(undefined)

    const governance = useGrasslandGovernance(run)
    await expect(governance.uploadStoreMediaFile('org-1', 'store-1', 'storefront', makeFile('image/png')))
      .rejects.toThrow('对象校验失败')
  })

  test('帽预检：不支持的 MIME 直接拒绝，不开票', async () => {
    const governance = useGrasslandGovernance(run)
    await expect(governance.uploadStoreMediaFile('org-1', 'store-1', 'storefront', makeFile('image/gif')))
      .rejects.toThrow('不支持此文件类型')
    await expect(governance.uploadStoreMediaFile('org-1', 'store-1', 'video', makeFile('image/jpeg')))
      .rejects.toThrow('不支持此文件类型')
    expect(requestMock).not.toHaveBeenCalled()
    expect(putMock).not.toHaveBeenCalled()
  })

  test('帽预检：超出大小帽直接拒绝，不开票', async () => {
    const governance = useGrasslandGovernance(run)
    const bigImage = makeFile('image/jpeg', 10 * 1024 * 1024 + 1)
    await expect(governance.uploadStoreMediaFile('org-1', 'store-1', 'menu', bigImage))
      .rejects.toThrow('不得超过 10MB')
    const bigVideo = makeFile('video/mp4', 20 * 1024 * 1024 + 1)
    await expect(governance.uploadStoreMediaFile('org-1', 'store-1', 'video', bigVideo))
      .rejects.toThrow('不得超过 20MB')
    expect(requestMock).not.toHaveBeenCalled()
  })
})

describe('管理端点契约', () => {
  test('getStoreMedia / bindStoreMedia / unbindStoreMedia / reorderStoreMedia 的 URL 与 body', async () => {
    const list = { storeId: 'store-1', items: [] }
    requestMock.mockResolvedValue(list)

    const governance = useGrasslandGovernance(run)
    await governance.getStoreMedia('org-1', 'store-1')
    await governance.bindStoreMedia('org-1', 'store-1', 'menu', ['media-1', 'media-2'])
    requestMock.mockResolvedValue({ deleted: true })
    await governance.unbindStoreMedia('org-1', 'store-1', 'media-1')
    requestMock.mockResolvedValue(list)
    await governance.reorderStoreMedia('org-1', 'store-1', 'video', ['media-2', 'media-1'])

    expect(requestMock.mock.calls.map(([url, init]) => [init?.method ?? 'GET', url])).toEqual([
      ['GET', '/api/organizations/org-1/stores/store-1/media'],
      ['POST', '/api/organizations/org-1/stores/store-1/media'],
      ['DELETE', '/api/organizations/org-1/stores/store-1/media/media-1'],
      ['PUT', '/api/organizations/org-1/stores/store-1/media/order'],
    ])
    expect(JSON.parse(String((requestMock.mock.calls[1][1] as RequestInit).body))).toEqual({
      kind: 'menu', mediaIds: ['media-1', 'media-2'],
    })
    expect(JSON.parse(String((requestMock.mock.calls[3][1] as RequestInit).body))).toEqual({
      kind: 'video', orderedMediaIds: ['media-2', 'media-1'],
    })
  })
})
