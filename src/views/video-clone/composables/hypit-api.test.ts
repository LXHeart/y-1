// @vitest-environment happy-dom
// hypit-api.test.ts — C107-21 (TC107-21-01/02 取数层)：信封解包、错误码保留、
// AbortSignal 取消传播。fetch 全程替换为受控替身（UI 专项允许精确接口替身）。
import { afterEach, describe, expect, test, vi } from 'vitest'
import {
  archiveOutput,
  closeStudioSession,
  createChangeset,
  hypitRequest,
  importPackage,
  importUrlAsset,
  listOutputs,
  listProjects,
  readMediaDownloadUrl,
  uploadAsset,
} from './hypit-api'
import { GrasslandHttpError } from '../../../composables/grassland-http'

function mockFetchOnce(status: number, body: unknown): void {
  vi.stubGlobal('fetch', vi.fn(async () =>
    new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })))
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('hypit-api 信封与错误', () => {
  test('success 信封解包 data', async () => {
    mockFetchOnce(200, { success: true, data: { items: [] } })
    const page = await listProjects()
    expect(page.items).toEqual([])
  })

  test('非 2xx 抛 GrasslandHttpError 并保留 code 与消息', async () => {
    mockFetchOnce(409, { success: false, error: '基线修订已过期', code: 'hypit_revision_conflict' })
    await expect(hypitRequest('/projects/x/file?path=main.svml')).rejects.toMatchObject({
      status: 409,
      code: 'hypit_revision_conflict',
    })
  })

  test('success=false 同样按错误处理（不假装成功）', async () => {
    mockFetchOnce(200, { success: false, error: '引擎未启用', code: 'hypit_disabled' })
    await expect(hypitRequest('/capabilities')).rejects.toBeInstanceOf(GrasslandHttpError)
  })

  test('损坏信封报 hypit_engine_error 不猜测', async () => {
    mockFetchOnce(200, { unexpected: true })
    await expect(hypitRequest('/capabilities')).rejects.toMatchObject({ code: 'hypit_engine_error' })
  })

  test('AbortSignal 取消传播到 fetch', async () => {
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      init?.signal?.throwIfAborted()
      return new Response(JSON.stringify({ success: true, data: {} }), { status: 200 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const controller = new AbortController()
    controller.abort()
    // C107F2-11：listProjects 增设 cursor 首参（§5.3 分页），signal 移为第二参。
    await expect(listProjects(undefined, controller.signal)).rejects.toThrow()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  test('changeset 创建/应用发送 JSON 体且路径正确', async () => {
    const fetchMock = vi.fn(async () =>
      new Response(JSON.stringify({ success: true, data: { changesetId: 'c1', baseRevision: 1, applyMode: 'save', checkStatus: 'not_checked', state: 'draft' } }), { status: 202 }))
    vi.stubGlobal('fetch', fetchMock)
    const changeset = await createChangeset('p1', {
      requestId: 'r1', baseRevision: 1, applyMode: 'save',
      changes: [{ path: 'main.svml', action: 'put', content: 'x', baseHash: null }],
    })
    expect(changeset.changesetId).toBe('c1')
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/hypit/projects/p1/changesets')
    expect(init.method).toBe('POST')
    expect((init.body as string)).toContain('"applyMode":"save"')
  })
})

// --- C107F2-40 V-15 变更行覆盖补环：端点形状与 XHR 错误分支 ---

class StubXhr {
  status = 0
  responseText = ''
  withCredentials = false
  upload: { onprogress: ((e: { lengthComputable: boolean; loaded: number; total: number }) => void) | null } = { onprogress: null }
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  onabort: (() => void) | null = null
  opened = ''
  open(_m: string, url: string): void { this.opened = url }
  send(): void { StubXhr.last = this }
  abort(): void { this.onabort?.() }
  complete(status: number, body: unknown): void {
    this.status = status
    this.responseText = JSON.stringify(body)
    this.onload?.()
  }
  static last: StubXhr | null = null
}

describe('hypit-api 端点形状（outputs/archive/media/studio/import-url）', () => {
  test('listOutputs 走 outputs 列表端点并透传 items', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ success: true, data: { items: [{ id: 'o1' }], outputs: [{ id: 'o1' }] } }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const page = await listOutputs('p1', 'b1')
    expect(page.items).toHaveLength(1)
    const [url] = fetchMock.mock.calls[0] as unknown as [string]
    expect(url).toBe('/api/hypit/builds/b1/outputs?limit=50')
  })

  test('archiveOutput POST {requestId, outputNames} 按名批量', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ success: true, data: { archiveId: 'ar1' } }), { status: 202 }))
    vi.stubGlobal('fetch', fetchMock)
    const archive = await archiveOutput('b1', ['final.video'])
    expect((archive as unknown as { archiveId: string }).archiveId).toBe('ar1')
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/hypit/builds/b1/archive')
    expect(init.method).toBe('POST')
    expect(init.body as string).toContain('"outputNames":["final.video"]')
  })

  test('readMediaDownloadUrl：ok → 透传 downloadUrl；null → null；非 2xx → media_unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response(JSON.stringify({ success: true, data: { downloadUrl: 'https://cdn/x.mp4?sig=1' } }), { status: 200 })))
    expect(await readMediaDownloadUrl('m1')).toBe('https://cdn/x.mp4?sig=1')
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response(JSON.stringify({ success: true, data: {} }), { status: 200 })))
    expect(await readMediaDownloadUrl('m1')).toBeNull()
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response(JSON.stringify({ success: false }), { status: 404 })))
    await expect(readMediaDownloadUrl('m1')).rejects.toMatchObject({ code: 'media_unavailable', status: 404 })
  })

  test('closeStudioSession DELETE 幂等返回 closed', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ success: true, data: { closed: true } }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    expect((await closeStudioSession('p1', 's/1')).closed).toBe(true)
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/hypit/projects/p1/studio-sessions/s%2F1')
    expect(init.method).toBe('DELETE')
  })

  test('importUrlAsset POST {requestId,url,role}', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ success: true, data: { id: 'a9' } }), { status: 202 }))
    vi.stubGlobal('fetch', fetchMock)
    const asset = await importUrlAsset('p1', 'https://example.com/v.mp4', 'r9')
    expect((asset as unknown as { id: string }).id).toBe('a9')
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(init.body as string).toContain('"role":"reference"')
  })
})

describe('hypit-api XHR 错误分支（import/upload 信封三形态与坏 JSON）', () => {
  test('importPackage：对象 error 透 message；字符串 error 直用；坏 JSON 兜底', async () => {
    vi.stubGlobal('XMLHttpRequest', StubXhr as unknown as typeof XMLHttpRequest)
    const file = new File(['PK'], 'a.zip')
    const first = importPackage(file, 'r1', undefined)
    StubXhr.last!.complete(409, { success: false, error: { message: '包校验失败', code: 'hypit_package_invalid' } })
    await expect(first).rejects.toMatchObject({ message: '包校验失败' })

    const second = importPackage(file, 'r2', undefined)
    StubXhr.last!.complete(500, { success: false, error: '内部错误' })
    await expect(second).rejects.toMatchObject({ message: '内部错误' })

    const third = importPackage(file, 'r3', undefined)
    StubXhr.last!.status = 502
    StubXhr.last!.responseText = '<html>bad</html>'
    StubXhr.last!.onload?.()
    await expect(third).rejects.toMatchObject({ message: '导入失败（502）' })
  })

  test('uploadAsset：对象/字符串/坏 JSON 三分支 + 上传地址形状', async () => {
    vi.stubGlobal('XMLHttpRequest', StubXhr as unknown as typeof XMLHttpRequest)
    const file = new File(['AA'], 'v.mp4')
    const first = uploadAsset('p 1', file, 'reference', 'r1')
    StubXhr.last!.complete(413, { success: false, error: { message: '太大', code: 'hypit_too_large' } })
    await expect(first).rejects.toMatchObject({ message: '太大' })
    expect(StubXhr.last!.opened).toBe('/api/hypit/projects/p%201/assets/upload')

    const second = uploadAsset('p1', file, 'reference', 'r2')
    StubXhr.last!.complete(500, { success: false, error: '磁盘满' })
    await expect(second).rejects.toMatchObject({ message: '磁盘满' })

    const third = uploadAsset('p1', file, 'reference', 'r3')
    StubXhr.last!.status = 502
    StubXhr.last!.responseText = 'not-json'
    StubXhr.last!.onload?.()
    await expect(third).rejects.toMatchObject({ message: '上传失败（502）' })
  })
})
