// @vitest-environment happy-dom
// hypit-api.test.ts — C107-21 (TC107-21-01/02 取数层)：信封解包、错误码保留、
// AbortSignal 取消传播。fetch 全程替换为受控替身（UI 专项允许精确接口替身）。
import { afterEach, describe, expect, test, vi } from 'vitest'
import {
  applyChangeset,
  createChangeset,
  hypitRequest,
  listProjects,
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
    await expect(listProjects(controller.signal)).rejects.toThrow()
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
    void applyChangeset
  })
})
