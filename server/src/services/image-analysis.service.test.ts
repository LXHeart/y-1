import { Buffer } from 'node:buffer'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppError } from '../lib/errors.js'

const {
  analyzeImageContentMock,
} = vi.hoisted(() => ({
  analyzeImageContentMock: vi.fn(),
}))

vi.mock('./image-analysis-dispatch.service.js', () => ({
  analyzeImageContent: analyzeImageContentMock,
}))

interface UploadedImageFixture {
  originalName: string
  mimeType: string
  size: number
  buffer: Buffer
}

function createPngBuffer(): Buffer {
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    Buffer.from('png-image-body'),
  ])
}

function createWebpBuffer(): Buffer {
  return Buffer.concat([
    Buffer.from('RIFF', 'ascii'),
    Buffer.from([0x24, 0x00, 0x00, 0x00]),
    Buffer.from('WEBP', 'ascii'),
    Buffer.from('webp-image-body'),
  ])
}

function createJpegBuffer(): Buffer {
  return Buffer.concat([
    Buffer.from([0xff, 0xd8, 0xff, 0xdb]),
    Buffer.from('jpeg-image-body'),
  ])
}

function createUploadedImage(input: Partial<UploadedImageFixture> = {}): UploadedImageFixture {
  const mimeType = input.mimeType ?? 'image/png'
  const buffer = input.buffer
    ?? (mimeType === 'image/webp'
      ? createWebpBuffer()
      : mimeType === 'image/jpeg'
        ? createJpegBuffer()
        : createPngBuffer())

  return {
    originalName: input.originalName ?? 'image.png',
    mimeType,
    size: input.size ?? buffer.byteLength,
    buffer,
  }
}

describe('analyzeUploadedImages', () => {
  beforeEach(() => {
    analyzeImageContentMock.mockReset()
    analyzeImageContentMock.mockResolvedValue({
      review: '整体很满意，质感不错，用起来也顺手。',
    })
  })

  it('converts uploaded images into data urls, preserves order, and forwards progress callback', async () => {
    const firstBuffer = createPngBuffer()
    const secondBuffer = createWebpBuffer()
    const progressSpy = vi.fn()
    const { analyzeUploadedImages } = await import('./image-analysis.service.js')

    const result = await analyzeUploadedImages([
      createUploadedImage({ originalName: 'first.png', mimeType: 'image/png', buffer: firstBuffer }),
      createUploadedImage({ originalName: 'second.webp', mimeType: 'image/webp', buffer: secondBuffer }),
    ], {
      reviewLength: 48,
      feelings: '包装看着挺用心的',
      onProgress: progressSpy,
    })

    expect(analyzeImageContentMock).toHaveBeenCalledWith([
      {
        mimeType: 'image/png',
        dataUrl: `data:image/png;base64,${firstBuffer.toString('base64')}`,
      },
      {
        mimeType: 'image/webp',
        dataUrl: `data:image/webp;base64,${secondBuffer.toString('base64')}`,
      },
    ], {
      reviewLength: 48,
      feelings: '包装看着挺用心的',
      platform: 'taobao',
    }, {
      signal: undefined,
      userId: undefined,
      onProgress: progressSpy,
    })
    expect(result).toEqual({
      review: '整体很满意，质感不错，用起来也顺手。',
      imageCount: 2,
    })
  })

  it('uses default review length and trims empty feelings', async () => {
    const { analyzeUploadedImages } = await import('./image-analysis.service.js')

    await analyzeUploadedImages([
      createUploadedImage(),
    ], {
      feelings: '   ',
    })

    expect(analyzeImageContentMock).toHaveBeenCalledWith(
      expect.any(Array),
      {
        reviewLength: 0,
        feelings: undefined,
        platform: 'taobao',
      },
      {
        signal: undefined,
        userId: undefined,
      },
    )
  })

  it('rejects requests without uploaded images', async () => {
    const { analyzeUploadedImages } = await import('./image-analysis.service.js')

    await expect(analyzeUploadedImages([], {
      reviewLength: 0,
    })).rejects.toMatchObject({
      statusCode: 400,
      message: '请至少上传 1 张图片',
    } satisfies Partial<AppError>)
    expect(analyzeImageContentMock).not.toHaveBeenCalled()
  })

  it('rejects requests with more than 6 images', async () => {
    const { analyzeUploadedImages } = await import('./image-analysis.service.js')

    await expect(analyzeUploadedImages(Array.from({ length: 7 }, () => createUploadedImage()), {
      reviewLength: 15,
    })).rejects.toMatchObject({
      statusCode: 400,
      message: '最多上传 6 张图片',
    } satisfies Partial<AppError>)
    expect(analyzeImageContentMock).not.toHaveBeenCalled()
  })

  it('rejects unsupported image mime types', async () => {
    const { analyzeUploadedImages } = await import('./image-analysis.service.js')

    await expect(analyzeUploadedImages([
      createUploadedImage({ mimeType: 'image/gif', originalName: 'animated.gif' }),
    ], {
      reviewLength: 15,
    })).rejects.toMatchObject({
      statusCode: 400,
      message: '仅支持 JPG、PNG、WebP 图片',
    } satisfies Partial<AppError>)
    expect(analyzeImageContentMock).not.toHaveBeenCalled()
  })

  it('rejects oversized images before provider dispatch', async () => {
    const { analyzeUploadedImages } = await import('./image-analysis.service.js')

    await expect(analyzeUploadedImages([
      createUploadedImage({ originalName: 'huge.png', size: 5 * 1024 * 1024 + 1 }),
    ], {
      reviewLength: 15,
    })).rejects.toMatchObject({
      statusCode: 400,
      message: '单张图片不能超过 5 MB',
    } satisfies Partial<AppError>)
    expect(analyzeImageContentMock).not.toHaveBeenCalled()
  })

  it('rejects buffers whose signature does not match the declared image type', async () => {
    const { analyzeUploadedImages } = await import('./image-analysis.service.js')

    await expect(analyzeUploadedImages([
      createUploadedImage({
        mimeType: 'image/png',
        originalName: 'fake.png',
        buffer: Buffer.from('not-a-real-png'),
      }),
    ], {
      reviewLength: 15,
    })).rejects.toMatchObject({
      statusCode: 400,
      message: '图片文件内容与类型不匹配',
    } satisfies Partial<AppError>)
    expect(analyzeImageContentMock).not.toHaveBeenCalled()
  })
})
