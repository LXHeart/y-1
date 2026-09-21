// @vitest-environment happy-dom
import { afterEach, describe, expect, test, vi } from 'vitest'
import { GrasslandHttpError, readError, request, requestRaw, requestText } from './grassland-http'

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
  test('canvas error code survives the envelope while the original error constructor remains compatible', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse('{"success":false,"error":"项目已归档","code":"CANVAS_RESOURCE_LOCKED"}', 409)))
    const failure = await request('/api/creation-assistant/canvas/plans').catch((error: unknown) => error)
    expect(failure).toMatchObject({ status: 409, code: 'CANVAS_RESOURCE_LOCKED', message: '项目已归档' })
    expect(new GrasslandHttpError(500, 'legacy').message).toBe('legacy')
  })
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

  test('任务书 #103 C103-16：错误信封 blockedReason 统一解析（如 analytics_facts_incomplete）', async () => {
    const fetchMock = vi.fn(() => Promise.resolve(new Response(
      JSON.stringify({ success: false, error: '结算数据待核对（缺失分账事实 2 条）', blockedReason: 'analytics_facts_incomplete' }),
      { status: 503, headers: { 'Content-Type': 'application/json' } },
    )))
    vi.stubGlobal('fetch', fetchMock)
    const error = await request(URL_UNDER_TEST).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(GrasslandHttpError)
    const httpError = error as GrasslandHttpError
    expect(httpError.status).toBe(503)
    expect(httpError.blockedReason).toBe('analytics_facts_incomplete')
    expect(httpError.message).toContain('结算数据待核对')
  })

  test('无 blockedReason 的既有错误不受影响', async () => {
    const fetchMock = vi.fn(() => Promise.resolve(new Response(
      JSON.stringify({ success: false, error: '余额不足' }),
      { status: 409, headers: { 'Content-Type': 'application/json' } },
    )))
    vi.stubGlobal('fetch', fetchMock)
    const error = await request(URL_UNDER_TEST).catch((e: unknown) => e)
    expect((error as GrasslandHttpError).blockedReason).toBeUndefined()
    expect((error as GrasslandHttpError).message).toBe('余额不足')
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

// ---------- 任务书 #104 C104-08（D07 / TC104-08-01～06）：错误体单次消费 ----------

/** 真实 Response + 计数（锁定「只读一次」，不靠 mock 代替）。 */
class CountingResponse extends Response {
  public readonly textCalls: { count: number } = { count: 0 }
  public readonly jsonCalls: { count: number } = { count: 0 }
  constructor(body?: BodyInit | null, init?: ResponseInit) {
    super(body, init)
  }
  override text(): Promise<string> {
    this.textCalls.count += 1
    return super.text()
  }
  override json(): Promise<unknown> {
    this.jsonCalls.count += 1
    return super.json()
  }
}

describe('TC104-08-01：纯文本真实体单次消费', () => {
  test('503 纯文本：readError 与三类 request 失败入口保留文本/status；text 只调一次且 bodyUsed', async () => {
    const response = new CountingResponse('upstream unavailable', { status: 503 })
    await expect(readError(response, '请求失败（503）')).resolves.toBe('upstream unavailable')
    expect(response.textCalls.count).toBe(1)
    expect(response.bodyUsed).toBe(true)
    expect(response.jsonCalls.count).toBe(0)

    vi.stubGlobal('fetch', vi.fn(async () => new Response('upstream unavailable', { status: 503 })))
    const failure = await request('/api/x').catch((error: unknown) => error)
    expect(failure).toBeInstanceOf(GrasslandHttpError)
    expect((failure as GrasslandHttpError).status).toBe(503)
    expect((failure as GrasslandHttpError).message).toBe('upstream unavailable')

    const textFailure = await requestText('/api/x').catch((error: unknown) => error)
    expect((textFailure as GrasslandHttpError).message).toBe('upstream unavailable')

    const rawFailure = await requestRaw('/api/x').catch((error: unknown) => error)
    expect((rawFailure as GrasslandHttpError).status).toBe(503)
    expect((rawFailure as GrasslandHttpError).message).toBe('upstream unavailable')
  })
})

describe('TC104-08-02：错误信封与机器字段', () => {
  test('JSON error/code/blockedReason 全保留；error 空/非 string → message fallback 但机器字段不丢', async () => {
    const full = await requestRawCatch('{"error":"余额不足","code":"INSUFFICIENT","blockedReason":"budget_exceeded"}', 402)
    expect(full).toMatchObject({ status: 402, code: 'INSUFFICIENT', blockedReason: 'budget_exceeded', message: '余额不足' })

    const emptyError = await requestRawCatch('{"error":"","code":"KEEP_ME"}', 400)
    expect((emptyError as GrasslandHttpError).message).toContain('请求失败（400）')
    expect((emptyError as GrasslandHttpError).code).toBe('KEEP_ME')

    const nonStringError = await requestRawCatch('{"error":{"nested":1},"code":123,"blockedReason":null}', 409)
    expect((nonStringError as GrasslandHttpError).message).toContain('请求失败（409）')
    expect((nonStringError as GrasslandHttpError).code).toBeUndefined()
    expect((nonStringError as GrasslandHttpError).blockedReason).toBeUndefined()
  })

  async function requestRawCatch(body: string, status: number): Promise<unknown> {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(body, { status, headers: { 'Content-Type': 'application/json' } })))
    return requestRaw('/api/x').catch((error: unknown) => error)
  }
})

describe('TC104-08-03：空/坏/HTML/已读回退', () => {
  test.each([
    ['空体', () => new Response('', { status: 502 })],
    ['HTML Content-Type', () => new Response('<html>ok</html>', { status: 502, headers: { 'Content-Type': 'text/html' } })],
    ['HTML 起始标签无 Content-Type', () => new Response('<!DOCTYPE html><html>502</html>', { status: 502 })],
    ['以 { 开头的损坏 JSON', () => new Response('{"error": "broken', { status: 500 })],
    ['以 [ 开头的损坏 JSON', () => new Response('[1,2', { status: 500 })],
    ['合法非错误 JSON（信封 false 无 error）', () => new Response('{"success":false}', { status: 422 })],
    ['合法 JSON 标量', () => new Response('42', { status: 500 })],
  ])('%s → fallback 且不抛二次消费异常', async (_name, make) => {
    const message = await readError(make(), '请求失败')
    expect(message).toBe('请求失败')
  })

  test('读前已消费（bodyUsed）：fallback，不抛二次消费异常', async () => {
    const response = new Response('plaintext', { status: 500 })
    await response.text()
    await expect(readError(response, '请求失败（500）')).resolves.toBe('请求失败（500）')
  })
})

describe('TC104-08-04：长度与字符边界', () => {
  test.each([499, 500, 501])('%d code point 纯文本有界', async (length) => {
    const body = 'a'.repeat(length)
    const message = await readError(new Response(body, { status: 503 }), 'fallback')
    expect(Array.from(message)).toHaveLength(Math.min(length, 500))
    if (length > 500) expect(message.endsWith('…')).toBe(true)
  })

  test('emoji 不被拆坏（代理对整体计数）；截断标记稳定；控制字符去除、换行/tab 保留', async () => {
    const body = '😀'.repeat(400) + 'x'.repeat(150
    )
    const message = await readError(new Response(body, { status: 503 }), 'fallback')
    expect(Array.from(message)).toHaveLength(500)
    expect(message.endsWith('…')).toBe(true)
    expect(Array.from(message)[0]).toBe('😀')

    const dirty = 'line1\nline2\ttab\u0000\u0007ctrl'
    const cleaned = await readError(new Response(dirty, { status: 503 }), 'fallback')
    expect(cleaned).toBe('line1\nline2\ttabctrl')
  })
})

describe('TC104-08-05：成功协议不退化', () => {
  test('成功信封/raw 数组/null/数字/text 成功语义保持', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{"success":true,"data":{"n":1}}', { headers: { 'Content-Type': 'application/json' } })))
    await expect(request<{ n: number }>('/api/x')).resolves.toEqual({ n: 1 })

    vi.stubGlobal('fetch', vi.fn(async () => new Response('[1,2]')))
    await expect(requestRaw<unknown[]>('/api/x')).resolves.toEqual([1, 2])

    vi.stubGlobal('fetch', vi.fn(async () => new Response('null')))
    await expect(requestRaw<unknown>('/api/x')).resolves.toBeNull()

    vi.stubGlobal('fetch', vi.fn(async () => new Response('123')))
    await expect(requestRaw<unknown>('/api/x')).resolves.toBe(123)

    vi.stubGlobal('fetch', vi.fn(async () => new Response('<svg>ok</svg>', { headers: { 'Content-Type': 'image/svg+xml' } })))
    await expect(requestText('/api/x')).resolves.toBe('<svg>ok</svg>')
  })
})

describe('TC104-08-06：兼容与读取失败', () => {
  test('仅 json 的旧 stub：单次读取同样解析（不先 json 后 text）', async () => {
    const calls = { json: 0 }
    const legacyStub = {
      ok: false,
      status: 418,
      headers: { get: () => 'application/json' },
      json: async () => {
        calls.json += 1
        return { error: 'legacy stub message' }
      },
    } as unknown as Response
    await expect(readError(legacyStub, 'fallback')).resolves.toBe('legacy stub message')
    expect(calls.json).toBe(1)
  })

  test('text() 拒绝与错误 body 为 JSON 标量：fallback 稳定、无 body 日志', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const rejecting = {
      ok: false, status: 500, headers: { get: () => null },
      text: () => Promise.reject(new Error('stream broken')),
    } as unknown as Response
    await expect(readError(rejecting, '请求失败（500）')).resolves.toBe('请求失败（500）')
    await expect(readError(new Response('true', { status: 500 }), '请求失败（500）')).resolves.toBe('请求失败（500）')
    expect(errorSpy).not.toHaveBeenCalled()
    expect(warnSpy).not.toHaveBeenCalled()
    errorSpy.mockRestore()
    warnSpy.mockRestore()
  })
})
