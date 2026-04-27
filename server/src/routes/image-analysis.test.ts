import { beforeEach, describe, expect, it, vi } from 'vitest'

const analyzeImageContentHandlerMock = vi.fn()

vi.mock('../controllers/image-analysis.controller.js', () => ({
  analyzeImageContentHandler: analyzeImageContentHandlerMock,
  exportToFeishuHandler: vi.fn(),
  saveStyleMemoryHandler: vi.fn(),
  getStylePreferencesHandler: vi.fn(),
  draftStepHandler: vi.fn(),
  optimizeStepHandler: vi.fn(),
  styleRefineStepHandler: vi.fn(),
  updateStylePreferencesHandler: vi.fn(),
  optimizeStylePreferencesHandler: vi.fn(),
}))

describe('image analysis route helpers', () => {
  beforeEach(() => {
    analyzeImageContentHandlerMock.mockReset()
  })

  it('rejects unsupported mime types', async () => {
    const { filterUploadedImageFile } = await import('./image-analysis.js')
    const callback = vi.fn()

    filterUploadedImageFile(
      {} as never,
      { mimetype: 'image/gif' } as never,
      callback,
    )

    expect(callback).toHaveBeenCalledWith(expect.objectContaining({
      statusCode: 400,
      message: '仅支持 JPG、PNG、WebP 图片',
    }))
  })

  it('accepts supported mime types', async () => {
    const { filterUploadedImageFile } = await import('./image-analysis.js')
    const callback = vi.fn()

    filterUploadedImageFile(
      {} as never,
      { mimetype: 'image/png' } as never,
      callback,
    )

    expect(callback).toHaveBeenCalledWith(null, true)
  })

  it('rejects oversized requests by content-length before upload parsing', async () => {
    const { rejectOversizedImageUploadRequest } = await import('./image-analysis.js')
    const next = vi.fn()

    rejectOversizedImageUploadRequest({
      headers: {
        'content-length': String(6 * 5 * 1024 * 1024 + 1),
      },
    } as never, {} as never, next)

    expect(next).toHaveBeenCalledWith(expect.objectContaining({
      statusCode: 400,
      message: '图片上传总大小不能超过 30 MB',
    }))
  })

  it('allows requests under the content-length limit', async () => {
    const { rejectOversizedImageUploadRequest } = await import('./image-analysis.js')
    const next = vi.fn()

    rejectOversizedImageUploadRequest({
      headers: {
        'content-length': String(1024),
      },
    } as never, {} as never, next)

    expect(next).toHaveBeenCalledWith()
  })
})
