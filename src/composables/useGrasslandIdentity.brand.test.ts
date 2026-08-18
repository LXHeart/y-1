import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { useGrasslandIdentity } from './useGrasslandIdentity'
import { compressImageToFile } from './compress-image'
import { GrasslandHttpError } from './grassland-http'
import type { RunFn } from './grassland-http'

/**
 * 组织品牌资料（#32）composable 请求契约测试：URL、方法、请求体（含 expectedVersion）、
 * 乐观锁 409 的两条错误通道、Logo 三步上传（票据体取**压缩后**的 contentType/sizeBytes）。
 *
 * 镜像 useGrassland.test.ts / useGrasslandMarketplace.batch.test.ts 的 stubFetch 契约动机：
 * 字段名不匹配 typecheck 抓不到（两个名字都是合法 TS），只能靠断言实际请求体锁死。
 */

vi.mock('./compress-image', () => ({
  // 默认原样返回；个别用例用 mockResolvedValueOnce 换成「压缩后」的假文件，
  // 用来断言开票体取的是压缩结果的 type/size 而非原始文件。
  compressImageToFile: vi.fn(async (file: File) => file),
}))

const compressMock = vi.mocked(compressImageToFile)

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

beforeEach(() => {
  compressMock.mockClear()
})

/** 镜像 useGrassland().run：吞错落 error 通道（spec 测试清单 10 的「null + error」分支）。 */
function createCapturingRun(): { run: RunFn; error: { value: string } } {
  const error = { value: '' }
  const run: RunFn = async (operation) => {
    error.value = ''
    try {
      return await operation()
    } catch (caught: unknown) {
      error.value = caught instanceof Error ? caught.message : '未知错误'
      return null
    }
  }
  return { run, error }
}

/** 透传 run：不吞错——供断言 request 抛出的原始错误类型与 status（409 供组件分支）。 */
const passthroughRun: RunFn = async (operation) => operation()

function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function errorResponse(status: number, error: string): Response {
  return new Response(JSON.stringify({ success: false, error }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function bodyOf(fetchMock: ReturnType<typeof vi.fn>, callIndex = 0): Record<string, unknown> {
  const init = fetchMock.mock.calls[callIndex][1] as RequestInit
  return JSON.parse(init.body as string) as Record<string, unknown>
}

const FILLED_PROFILE = {
  organizationId: 'org-1',
  brandName: '草场咖啡',
  brandLogoMediaReferenceId: 'media-1',
  logoUrl: 'http://storage.test/signed/logo',
  description: '一杯好咖啡',
  industry: 'catering',
  version: 3,
}

describe('品牌资料读取（GET /brand-profile）', () => {
  test('回显完整资料并携带 cookie', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(FILLED_PROFILE))
    vi.stubGlobal('fetch', fetchMock)
    const { getBrandProfile } = useGrasslandIdentity(passthroughRun)

    const profile = await getBrandProfile('org-1')

    expect(profile).toEqual(FILLED_PROFILE)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/organizations/org-1/brand-profile')
    expect((fetchMock.mock.calls[0][1] as RequestInit | undefined)?.credentials).toBe('include')
    expect((fetchMock.mock.calls[0][1] as RequestInit | undefined)?.body).toBeUndefined()
  })

  test('无行时回 version=0 的空资料（不是 404，可直接绑表单）', async () => {
    const emptyProfile = {
      organizationId: 'org-1', brandName: null, brandLogoMediaReferenceId: null,
      logoUrl: null, description: null, industry: null, version: 0,
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(emptyProfile)))
    const { run, error } = createCapturingRun()
    const { getBrandProfile } = useGrasslandIdentity(run)

    const profile = await getBrandProfile('org-1')

    expect(profile).toEqual(emptyProfile)
    expect(error.value).toBe('')
  })

  test('读取失败返回 null 且错误落入 error 通道', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(500, '服务暂不可用')))
    const { run, error } = createCapturingRun()
    const { getBrandProfile } = useGrasslandIdentity(run)

    const profile = await getBrandProfile('org-1')

    expect(profile).toBeNull()
    expect(error.value).toBe('服务暂不可用')
  })
})

describe('品牌资料保存（PUT /brand-profile）', () => {
  test('PUT 整份覆盖，payload 含 expectedVersion 与显式 null 清空字段', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ ...FILLED_PROFILE, version: 4 }))
    vi.stubGlobal('fetch', fetchMock)
    const { updateBrandProfile } = useGrasslandIdentity(passthroughRun)

    const saved = await updateBrandProfile('org-1', {
      brandName: '草场咖啡二店',
      brandLogoMediaReferenceId: null,
      description: null,
      industry: 'retail',
      expectedVersion: 3,
    })

    expect(fetchMock.mock.calls[0][0]).toBe('/api/organizations/org-1/brand-profile')
    expect((fetchMock.mock.calls[0][1] as RequestInit).method).toBe('PUT')
    expect((fetchMock.mock.calls[0][1] as RequestInit).credentials).toBe('include')
    // null 字段照发（清空语义）；expectedVersion 原样回传——写成别的名字后端 400
    expect(bodyOf(fetchMock)).toEqual({
      brandName: '草场咖啡二店',
      brandLogoMediaReferenceId: null,
      description: null,
      industry: 'retail',
      expectedVersion: 3,
    })
    expect(saved?.version).toBe(4)
  })

  test('409 乐观锁冲突：run 吞错返回 null，error 通道是后端冲突文案', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(409, '品牌资料已变更，请刷新后重试')))
    const { run, error } = createCapturingRun()
    const { updateBrandProfile } = useGrasslandIdentity(run)

    const saved = await updateBrandProfile('org-1', { expectedVersion: 0 })

    expect(saved).toBeNull()
    expect(error.value).toBe('品牌资料已变更，请刷新后重试')
  })

  test('409 抛的是 GrasslandHttpError(409)（status 保留，供组件按状态分支）', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(409, '品牌资料已变更，请刷新后重试')))
    const { updateBrandProfile } = useGrasslandIdentity(passthroughRun)

    const promise = updateBrandProfile('org-1', { expectedVersion: 7 })

    await expect(promise).rejects.toBeInstanceOf(GrasslandHttpError)
    await promise.catch((caught: unknown) => {
      expect((caught as GrasslandHttpError).status).toBe(409)
      expect((caught as GrasslandHttpError).message).toBe('品牌资料已变更，请刷新后重试')
    })
  })
})

describe('品牌 Logo 三步上传（uploadBrandLogo）', () => {
  const TICKET = {
    id: 'media-7',
    objectKey: 'media/brand_logo/media-7',
    uploadUrl: 'http://localhost:9002/grassland/tmp/media-7',
    method: 'PUT',
    headers: { 'Content-Type': 'image/jpeg' },
    expiresAt: null,
  }

  function ticketFlowMock(): ReturnType<typeof vi.fn> {
    return vi.fn().mockImplementation(async (url: string) => {
      if (url === TICKET.uploadUrl) return new Response(null, { status: 200 })
      if (url === '/api/organizations/org-1/brand-profile/logo/upload-ticket') {
        return jsonResponse(TICKET)
      }
      if (url === '/api/media/media-7/confirm') {
        return jsonResponse({ id: 'media-7', status: 'active' })
      }
      throw new Error(`unexpected url: ${url}`)
    })
  }

  test('超过 1MB 的图先压缩；开票体取压缩结果的 contentType/sizeBytes，三步后返回 mediaId', async () => {
    const fetchMock = ticketFlowMock()
    vi.stubGlobal('fetch', fetchMock)
    const original = new File([new Uint8Array(2 * 1024 * 1024)], 'logo.png', { type: 'image/png' })
    const compressed = new File([new Uint8Array(500_000)], 'logo.jpg', { type: 'image/jpeg' })
    compressMock.mockResolvedValueOnce(compressed)
    const { uploadBrandLogo } = useGrasslandIdentity(passthroughRun)

    const mediaId = await uploadBrandLogo('org-1', original)

    expect(mediaId).toBe('media-7')
    // 第一步：identity 代开票据（无 purpose 字段——归属由服务断言，D6）
    expect(compressMock).toHaveBeenCalledWith(original, 1024 * 1024)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/organizations/org-1/brand-profile/logo/upload-ticket')
    expect((fetchMock.mock.calls[0][1] as RequestInit).method).toBe('POST')
    expect(bodyOf(fetchMock)).toEqual({ contentType: 'image/jpeg', sizeBytes: 500_000 })
    // 第二步：直传 presigned（ticket 的 url/method/headers，body 是压缩后文件）
    const put = fetchMock.mock.calls[1][1] as RequestInit
    expect(fetchMock.mock.calls[1][0]).toBe(TICKET.uploadUrl)
    expect(put.method).toBe('PUT')
    expect(put.headers).toEqual({ 'Content-Type': 'image/jpeg' })
    expect(put.body).toBe(compressed)
    expect(put.credentials).toBeUndefined()  // 带 cookie 会被 nginx CORS 拦（SigV4 不认 cookie）
    // 第三步：confirm 不带请求体
    expect(fetchMock.mock.calls[2][0]).toBe('/api/media/media-7/confirm')
    expect((fetchMock.mock.calls[2][1] as RequestInit).method).toBe('POST')
    expect((fetchMock.mock.calls[2][1] as RequestInit).body).toBeUndefined()
  })

  test('不超过 1MB 的图原样直传，不重复编码', async () => {
    const fetchMock = ticketFlowMock()
    vi.stubGlobal('fetch', fetchMock)
    const file = new File([new Uint8Array(3)], 'logo.png', { type: 'image/png' })
    const { uploadBrandLogo } = useGrasslandIdentity(passthroughRun)

    const mediaId = await uploadBrandLogo('org-1', file)

    expect(mediaId).toBe('media-7')
    expect(compressMock).not.toHaveBeenCalled()
    expect(bodyOf(fetchMock)).toEqual({ contentType: 'image/png', sizeBytes: 3 })
    expect((fetchMock.mock.calls[1][1] as RequestInit).body).toBe(file)
  })

  test('开票被拒（如非法 MIME 400）返回 null，后续步骤不发生', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(400, '不支持的图片类型')))
    const { run, error } = createCapturingRun()
    const { uploadBrandLogo } = useGrasslandIdentity(run)
    const file = new File([new Uint8Array(4)], 'logo.gif', { type: 'image/gif' })

    const mediaId = await uploadBrandLogo('org-1', file)

    expect(mediaId).toBeNull()
    expect(error.value).toBe('不支持的图片类型')
    expect(vi.mocked(globalThis.fetch)).toHaveBeenCalledTimes(1)
  })

  test('直传 presigned 失败不 confirm（不留半成品 pending 资产）', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url === TICKET.uploadUrl) return new Response(null, { status: 403 })
      return jsonResponse(TICKET)
    })
    vi.stubGlobal('fetch', fetchMock)
    const { run, error } = createCapturingRun()
    const { uploadBrandLogo } = useGrasslandIdentity(run)
    const file = new File([new Uint8Array(3)], 'logo.png', { type: 'image/png' })

    const mediaId = await uploadBrandLogo('org-1', file)

    expect(mediaId).toBeNull()
    expect(error.value).toContain('403')
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls.some((c) => String(c[0]).includes('/confirm'))).toBe(false)
  })

  test('confirm 失败返回 null 且错误落入 error 通道', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url === TICKET.uploadUrl) return new Response(null, { status: 200 })
      if (url === '/api/media/media-7/confirm') return errorResponse(500, '确认上传失败')
      return jsonResponse(TICKET)
    })
    vi.stubGlobal('fetch', fetchMock)
    const { run, error } = createCapturingRun()
    const { uploadBrandLogo } = useGrasslandIdentity(run)
    const file = new File([new Uint8Array(3)], 'logo.png', { type: 'image/png' })

    const mediaId = await uploadBrandLogo('org-1', file)

    expect(mediaId).toBeNull()
    expect(error.value).toBe('确认上传失败')
  })
})
