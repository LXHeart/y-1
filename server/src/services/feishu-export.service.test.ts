import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const { loggerWarnMock } = vi.hoisted(() => ({
  loggerWarnMock: vi.fn(),
}))

vi.mock('../lib/logger.js', () => ({
  logger: {
    info: vi.fn(),
    warn: loggerWarnMock,
    error: vi.fn(),
  },
}))

vi.mock('../lib/env.js', () => ({
  env: {
    FEISHU_API_TIMEOUT_MS: 30000,
  },
}))

function mockJsonResponse(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

const baseInput = {
  feishu: { appId: 'test-app-id', appSecret: 'test-app-secret' },
  review: 'Great product!\nWould buy again',
  title: 'Test Review',
  tags: ['quality', 'value'],
  images: [],
  platform: 'taobao' as const,
  reviewLength: 100,
  feelings: 'very satisfied',
  runId: 'run-abc',
}

describe('exportToFeishu', () => {
  const originalFetch = globalThis.fetch

  beforeEach(() => {
    vi.resetModules()
    loggerWarnMock.mockReset()
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
  })

  it('throws when appId is missing', async () => {
    const { exportToFeishu } = await import('./feishu-export.service.js')
    await expect(exportToFeishu({
      ...baseInput,
      feishu: { appId: '', appSecret: 'secret' },
    })).rejects.toThrow('飞书应用凭证未配置')
  })

  it('throws when appSecret is missing', async () => {
    const { exportToFeishu } = await import('./feishu-export.service.js')
    await expect(exportToFeishu({
      ...baseInput,
      feishu: { appId: 'id', appSecret: undefined },
    })).rejects.toThrow('飞书应用凭证未配置')
  })

  it('propagates token acquisition failure', async () => {
    globalThis.fetch = vi.fn(async () => mockJsonResponse({
      code: -1,
      msg: 'invalid app_id',
    }))

    const { exportToFeishu } = await import('./feishu-export.service.js')
    await expect(exportToFeishu(baseInput)).rejects.toThrow('飞书获取访问凭证失败')
  })

  it('propagates document creation failure', async () => {
    let callCount = 0
    globalThis.fetch = vi.fn(async () => {
      callCount++
      if (callCount === 1) {
        return mockJsonResponse({ code: 0, tenant_access_token: 'tok', expire: 7200 })
      }
      return mockJsonResponse({ code: -1, msg: 'folder not found' })
    })

    const { exportToFeishu } = await import('./feishu-export.service.js')
    await expect(exportToFeishu(baseInput)).rejects.toThrow('飞书创建文档失败')
  })

  it('completes full export and returns document URL', async () => {
    let callCount = 0
    globalThis.fetch = vi.fn(async (_url, init) => {
      callCount++
      // Token request
      if (init?.method === 'POST' && !init?.headers?.['Authorization']) {
        return mockJsonResponse({ code: 0, tenant_access_token: 'tok', expire: 7200 })
      }
      // Document creation
      if (String(_url).includes('/docx/v1/documents') && !String(_url).includes('/blocks')) {
        return mockJsonResponse({
          code: 0,
          msg: '',
          data: { document: { document_id: 'doc123', revision_id: 1, title: 'Test Review' } },
        })
      }
      // Block append
      return mockJsonResponse({ code: 0, msg: '' })
    })

    const { exportToFeishu } = await import('./feishu-export.service.js')
    const result = await exportToFeishu(baseInput)

    expect(result.documentId).toBe('doc123')
    expect(result.documentUrl).toBe('https://bytedance.feishu.cn/docx/doc123')
  })

  it('uses folder token when provided', async () => {
    let createRequestBody: string | null = null
    globalThis.fetch = vi.fn(async (_url, init) => {
      if (init?.method === 'POST' && !init?.headers?.['Authorization']) {
        return mockJsonResponse({ code: 0, tenant_access_token: 'tok', expire: 7200 })
      }
      if (String(_url).includes('/docx/v1/documents') && !String(_url).includes('/blocks')) {
        createRequestBody = String(init?.body)
        return mockJsonResponse({
          code: 0,
          msg: '',
          data: { document: { document_id: 'doc456', revision_id: 1, title: 'T' } },
        })
      }
      return mockJsonResponse({ code: 0, msg: '' })
    })

    const { exportToFeishu } = await import('./feishu-export.service.js')
    await exportToFeishu({
      ...baseInput,
      feishu: { appId: 'id', appSecret: 'secret', folderToken: 'fld-abc' },
    })

    expect(createRequestBody).toContain('fld-abc')
  })

  it('throws an actionable error when Feishu app lacks image upload scope', async () => {
    let callCount = 0
    globalThis.fetch = vi.fn(async () => {
      callCount++
      if (callCount === 1) {
        return mockJsonResponse({ code: 0, tenant_access_token: 'tok', expire: 7200 })
      }
      if (callCount === 2) {
        return mockJsonResponse({
          code: 0,
          msg: '',
          data: { document: { document_id: 'doc789', revision_id: 1, title: 'T' } },
        })
      }
      if (callCount === 5) {
        return mockJsonResponse({
          code: 0,
          msg: '',
          data: { children: [{ block_id: 'blk-image-1', block_type: 27 }] },
        })
      }
      if (callCount === 6) {
        return mockJsonResponse({ code: 99991672, msg: 'Access denied' })
      }
      return mockJsonResponse({ code: 0, msg: '', data: {} })
    })

    const { exportToFeishu } = await import('./feishu-export.service.js')
    await expect(exportToFeishu({
      ...baseInput,
      images: [
        {
          buffer: Buffer.from('fake-image-data'),
          mimeType: 'image/png',
          originalName: 'å¾®ä¿¡å20260326181811_23_1000.jpg',
        },
      ],
    })).rejects.toThrow('飞书图片上传失败：当前飞书应用缺少图片上传权限，请在飞书开放平台为该应用开通 docs:document.media:upload 权限后重试')
  })

  it('falls back to placeholder text when image upload fails', async () => {
    let callCount = 0
    let blockRequestBodies: string[] = []
    globalThis.fetch = vi.fn(async (_url, init) => {
      callCount++
      if (callCount === 1) {
        return mockJsonResponse({ code: 0, tenant_access_token: 'tok', expire: 7200 })
      }
      if (callCount === 2) {
        return mockJsonResponse({
          code: 0,
          msg: '',
          data: { document: { document_id: 'doc789', revision_id: 1, title: 'T' } },
        })
      }
      if (callCount === 3 || callCount === 4 || callCount === 5 || callCount === 7) {
        blockRequestBodies = [...blockRequestBodies, String(init?.body)]
        if (callCount === 5) {
          return mockJsonResponse({
            code: 0,
            msg: '',
            data: { children: [{ block_id: 'blk-image-1', block_type: 27 }] },
          })
        }
        return mockJsonResponse({ code: 0, msg: '', data: {} })
      }
      if (callCount === 6) {
        return mockJsonResponse({ code: -1, msg: 'upload error' })
      }
      return mockJsonResponse({ code: 0, msg: '', data: {} })
    })

    const { exportToFeishu } = await import('./feishu-export.service.js')
    const result = await exportToFeishu({
      ...baseInput,
      images: [
        {
          buffer: Buffer.from('fake-image-data'),
          mimeType: 'image/png',
          originalName: 'å¾®ä¿¡å20260326181811_23_1000.jpg',
        },
      ],
    })

    expect(result.documentId).toBe('doc789')
    expect(loggerWarnMock).toHaveBeenCalledWith(
      expect.objectContaining({ originalName: expect.any(String) }),
      'Failed to upload image to Feishu, skipping',
    )
    expect(blockRequestBodies.some((body) => body.includes('[图片上传失败]'))).toBe(true)
  })

  it('defaults title to placeholder when not provided', async () => {
    let createRequestBody: string | null = null
    globalThis.fetch = vi.fn(async (_url, init) => {
      if (init?.method === 'POST' && !init?.headers?.['Authorization']) {
        return mockJsonResponse({ code: 0, tenant_access_token: 'tok', expire: 7200 })
      }
      if (String(_url).includes('/docx/v1/documents') && !String(_url).includes('/blocks')) {
        createRequestBody = String(init?.body)
        return mockJsonResponse({
          code: 0,
          msg: '',
          data: { document: { document_id: 'doc-default', revision_id: 1, title: '图片评价导出' } },
        })
      }
      return mockJsonResponse({ code: 0, msg: '' })
    })

    const { exportToFeishu } = await import('./feishu-export.service.js')
    await exportToFeishu({
      ...baseInput,
      title: undefined,
    })

    expect(createRequestBody).toContain('图片评价导出')
  })

  it('sends metadata blocks for platform, length, feelings, and runId', async () => {
    let blockRequestBodies: string[] = []
    let callCount = 0
    globalThis.fetch = vi.fn(async (_url, init) => {
      callCount++
      if (callCount === 1) {
        return mockJsonResponse({ code: 0, tenant_access_token: 'tok', expire: 7200 })
      }
      if (callCount === 2) {
        return mockJsonResponse({
          code: 0,
          msg: '',
          data: { document: { document_id: 'doc-meta', revision_id: 1, title: 'T' } },
        })
      }
      blockRequestBodies = [...blockRequestBodies, String(init?.body)]
      return mockJsonResponse({ code: 0, msg: '', data: {} })
    })

    const { exportToFeishu } = await import('./feishu-export.service.js')
    await exportToFeishu(baseInput)

    const combinedBodies = blockRequestBodies.join('\n')
    expect(combinedBodies).toContain('平台: 淘宝')
    expect(combinedBodies).toContain('字数: 100')
    expect(combinedBodies).toContain('补充感受: very satisfied')
    expect(combinedBodies).toContain('追踪 ID: run-abc')
    expect(combinedBodies).toContain('标签: quality、value')
  })

  it('uses Feishu heading payload keys that match the heading block type', async () => {
    let blockRequestBody: string | null = null
    let callCount = 0
    globalThis.fetch = vi.fn(async (_url, init) => {
      callCount++
      if (callCount === 1) {
        return mockJsonResponse({ code: 0, tenant_access_token: 'tok', expire: 7200 })
      }
      if (callCount === 2) {
        return mockJsonResponse({
          code: 0,
          msg: '',
          data: { document: { document_id: 'doc-heading', revision_id: 1, title: 'T' } },
        })
      }
      blockRequestBody = String(init?.body)
      return mockJsonResponse({ code: 0, msg: '' })
    })

    const { exportToFeishu } = await import('./feishu-export.service.js')
    await exportToFeishu(baseInput)

    expect(blockRequestBody).toContain('"block_type":5')
    expect(blockRequestBody).toContain('"heading3"')
    expect(blockRequestBody).toContain('"block_type":6')
    expect(blockRequestBody).toContain('"heading4"')
    expect(blockRequestBody).not.toContain('"heading":')
  })

  it('uses docx image upload params with block id and safe ascii filename', async () => {
    let uploadRequestBody: unknown = null
    let patchRequestBody: string | null = null
    let callCount = 0
    globalThis.fetch = vi.fn(async (_url, init) => {
      callCount++
      if (callCount === 1) {
        return mockJsonResponse({ code: 0, tenant_access_token: 'tok', expire: 7200 })
      }
      if (callCount === 2) {
        return mockJsonResponse({
          code: 0,
          msg: '',
          data: { document: { document_id: 'doc-upload', revision_id: 1, title: 'T' } },
        })
      }
      if (callCount === 3 || callCount === 4) {
        return mockJsonResponse({ code: 0, msg: '', data: {} })
      }
      if (callCount === 5) {
        return mockJsonResponse({
          code: 0,
          msg: '',
          data: { children: [{ block_id: 'blk-image-1', block_type: 27 }] },
        })
      }
      if (callCount === 6) {
        uploadRequestBody = init?.body ?? null
        return mockJsonResponse({ code: 0, msg: '', data: { file_token: 'file-token-1' } })
      }
      if (callCount === 7) {
        patchRequestBody = String(init?.body)
        return mockJsonResponse({ code: 0, msg: '' })
      }
      return mockJsonResponse({ code: 0, msg: '', data: {} })
    })

    const { exportToFeishu } = await import('./feishu-export.service.js')
    await exportToFeishu({
      ...baseInput,
      images: [
        {
          buffer: Buffer.from('fake-image-data'),
          mimeType: 'image/webp',
          originalName: 'å¾®ä¿¡å20260326181811_23_1000.jpg',
        },
      ],
    })

    expect(uploadRequestBody).toBeInstanceOf(FormData)
    if (!(uploadRequestBody instanceof FormData)) {
      throw new Error('Expected upload request body to be FormData')
    }
    const uploadFormData = uploadRequestBody
    expect(uploadFormData.get('parent_type')).toBe('docx_image')
    expect(uploadFormData.get('parent_node')).toBe('blk-image-1')
    expect(uploadFormData.get('size')).toBe(String(Buffer.from('fake-image-data').length))
    expect(String(uploadFormData.get('file_name'))).toMatch(/^image-export-\d+\.webp$/)
    expect(patchRequestBody).toContain('file-token-1')
  })

  it('omits metadata section when no metadata fields are provided', async () => {
    let blockRequestBody: string | null = null
    let callCount = 0
    globalThis.fetch = vi.fn(async (_url, init) => {
      callCount++
      if (callCount === 1) {
        return mockJsonResponse({ code: 0, tenant_access_token: 'tok', expire: 7200 })
      }
      if (callCount === 2) {
        return mockJsonResponse({
          code: 0,
          msg: '',
          data: { document: { document_id: 'doc-nometa', revision_id: 1, title: 'T' } },
        })
      }
      blockRequestBody = String(init?.body)
      return mockJsonResponse({ code: 0, msg: '' })
    })

    const { exportToFeishu } = await import('./feishu-export.service.js')
    await exportToFeishu({
      feishu: { appId: 'id', appSecret: 'secret' },
      review: 'simple review',
      images: [],
    })

    expect(blockRequestBody).not.toContain('生成参数')
    expect(blockRequestBody).toContain('评价内容')
  })
})
