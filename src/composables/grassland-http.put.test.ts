/**
 * 任务书 #78 卡 E：putToPresignedUrl 按状态码分文案。
 *
 * 413（文件过大，nginx/MinIO 拒收与凭据无关）/ 403（预签名过期，保留原口径）/
 * 其余（通用失败）。mock fetch 不打真实网络；带凭证头的直传语义见 grassland-http.ts 注释。
 */
import { afterEach, describe, expect, test, vi } from 'vitest'
import { putToPresignedUrl } from './grassland-http'
import type { MediaUploadTicket } from '../types/grassland'

const TICKET: MediaUploadTicket = {
  id: 'ticket-1', objectKey: 'tmp/x', uploadUrl: 'https://minio.test/upload',
  method: 'PUT', headers: { 'Content-Type': 'image/jpeg' }, expiresAt: null,
}

const FILE = new File(['abc'], 'a.jpg', { type: 'image/jpeg' })

function mockFetchWith(status: number) {
  return vi.fn().mockResolvedValue(new Response(null, { status }))
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('putToPresignedUrl 状态码分文案（任务书 #78 卡 E）', () => {
  test('413 → 文件过大文案（不再误报凭据过期）', async () => {
    vi.stubGlobal('fetch', mockFetchWith(413))
    await expect(putToPresignedUrl(TICKET, FILE))
      .rejects.toThrow('文件过大，超出大小上限（图片 ≤10MB / 视频 ≤20MB）')
  })

  test('403 → 保留「凭据可能已过期」口径', async () => {
    vi.stubGlobal('fetch', mockFetchWith(403))
    await expect(putToPresignedUrl(TICKET, FILE))
      .rejects.toThrow('凭据可能已过期，请重试')
  })

  test('500 → 通用失败文案', async () => {
    vi.stubGlobal('fetch', mockFetchWith(500))
    await expect(putToPresignedUrl(TICKET, FILE))
      .rejects.toThrow('附件上传失败（500），请重试')
  })

  test('2xx 正常通过', async () => {
    vi.stubGlobal('fetch', mockFetchWith(200))
    await expect(putToPresignedUrl(TICKET, FILE)).resolves.toBeUndefined()
  })
})
