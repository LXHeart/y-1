// @vitest-environment happy-dom
import { afterEach, describe, expect, test, vi } from 'vitest'
import { GrasslandHttpError, request, requestRaw } from './grassland-http'

/**
 * requestRaw 协议测试（任务书 #87 C-01）：真实 Response 对象锁定传输与解析语义——
 * 2xx JSON 原样返回不判 success；非 2xx 抛 GrasslandHttpError 保留状态码；
 * 204/坏 JSON 抛格式错误；网络拒绝/取消原异常透传。对照组锁定 request 对裸对象仍抛格式错误
 * （严格信封语义未回退）。
 */
const URL_UNDER_TEST = '/api/guest-trial/quota'

function jsonResponse(body: string, status = 200, withContentType = true): Response {
  const headers: Record<string, string> = withContentType ? { 'Content-Type': 'application/json' } : {}
  return new Response(body, { status, headers })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TC-C01-001：requestRaw 对 2xx JSON 原样返回且不判 success', () => {
  test.each([
    ['裸对象（负数/null 字段保持）', '{"balance":-3,"note":null}', { balance: -3, note: null }],
    ['信封形态对象原样返回不抛', '{"success":false,"error":"x"}', { success: false, error: 'x' }],
    ['JSON null 是合法返回', 'null', null],
    ['数组原样返回', '[1,2]', [1, 2]],
  ] as const)('%s', async (_name, rawBody, expected) => {
    const fetchMock = vi.fn(async () => jsonResponse(rawBody))
    vi.stubGlobal('fetch', fetchMock)

    await expect(requestRaw<unknown>(URL_UNDER_TEST)).resolves.toEqual(expected)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  test('不配 Content-Type 的两组变体同构', async () => {
    for (const rawBody of ['{"balance":7}', '[1,2]']) {
      vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(rawBody, 200, false)))
      await expect(requestRaw<unknown>(URL_UNDER_TEST)).resolves.toEqual(JSON.parse(rawBody))
    }
  })

  test('对照组：request 对裸对象仍抛格式错误（严格信封未回退）', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse('{"balance":-3,"note":null}')))
    const error = await request<unknown>(URL_UNDER_TEST).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(GrasslandHttpError)
    expect((error as GrasslandHttpError).message).toContain('响应格式错误')
  })

  test('并发两次调用：两个独立 fetch', async () => {
    const fetchMock = vi.fn(async () => jsonResponse('{"n":1}'))
    vi.stubGlobal('fetch', fetchMock)
    await Promise.all([requestRaw(URL_UNDER_TEST), requestRaw(URL_UNDER_TEST)])
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})

describe('TC-C01-002：requestRaw 非 2xx/坏 JSON/204/网络拒绝/取消语义', () => {
  test.each([401, 403, 404, 500])('%s 抛 GrasslandHttpError 且 message 取 body.error', async (status) => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(`{"error":"e-${status}"}`, status)))
    const error = await requestRaw(URL_UNDER_TEST).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(GrasslandHttpError)
    expect((error as GrasslandHttpError).status).toBe(status)
    expect((error as GrasslandHttpError).message).toBe(`e-${status}`)
  })

  test('500 无 body 用 fallback 文案「请求失败（500）」', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 500 })))
    const error = await requestRaw(URL_UNDER_TEST).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(GrasslandHttpError)
    expect((error as GrasslandHttpError).status).toBe(500)
    expect((error as GrasslandHttpError).message).toBe('请求失败（500）')
  })

  test.each([
    ['200 非 JSON 体', () => new Response('<html>gateway error</html>', { status: 200 })],
    ['204 空体', () => new Response(null, { status: 204 })],
  ])('%s 抛格式错误', async (_name, makeResponse) => {
    vi.stubGlobal('fetch', vi.fn(async () => makeResponse()))
    const error = await requestRaw(URL_UNDER_TEST).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(GrasslandHttpError)
    expect((error as GrasslandHttpError).message).toBe('响应格式错误')
  })

  test('fallbackError 覆盖格式错误文案', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 204 })))
    const error = await requestRaw(URL_UNDER_TEST, {}, { fallbackError: '配额读取失败' })
      .catch((e: unknown) => e)
    expect((error as GrasslandHttpError).message).toBe('配额读取失败')
  })

  test('网络拒绝：原 TypeError 透传，不包装', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))
    const error = await requestRaw(URL_UNDER_TEST).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(TypeError)
    expect(error).not.toBeInstanceOf(GrasslandHttpError)
  })

  test('AbortSignal 中途取消：AbortError 透传且无第二次 fetch', async () => {
    const controller = new AbortController()
    const fetchMock = vi.fn((_url: unknown, init?: RequestInit) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => {
          const abortError = new Error('The operation was aborted')
          abortError.name = 'AbortError'
          reject(abortError)
        })
        controller.abort()
      }))
    vi.stubGlobal('fetch', fetchMock)
    const error = await requestRaw(URL_UNDER_TEST, { signal: controller.signal }).catch((e: unknown) => e)
    expect((error as Error).name).toBe('AbortError')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
