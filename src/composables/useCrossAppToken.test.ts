// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { consumeCrossAppTokenFromUrl, stripUrlParams, useCrossAppJump } from './useCrossAppToken'
import { aiAppHref, grasslandAppHref } from '../lib/app-config'
import { useAuth } from './useAuth'

function response(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

const TOKEN = 'xat-token-0123456789abcdef0123456789abcdef0123456789' // secret-scan: allow（一次性跨壳 token 测试夹具）

const ORIGINAL_LOCATION = Object.getOwnPropertyDescriptor(window, 'location')

/** happy-dom 允许重定义 window.location——换成可断言的普通对象（须带 origin 供 URL 拼装）。 */
function stubLocation(): { href: string } {
  const stub = { href: 'http://localhost/', origin: 'http://localhost' }
  Object.defineProperty(window, 'location', { value: stub, configurable: true, writable: true })
  return stub
}

function restoreLocation(): void {
  if (ORIGINAL_LOCATION) Object.defineProperty(window, 'location', ORIGINAL_LOCATION)
}

beforeEach(() => {
  useAuth().currentUser.value = null
  window.history.replaceState(null, '', '/')
})

afterEach(() => {
  useAuth().currentUser.value = null
  vi.unstubAllGlobals()
  restoreLocation()
})

describe('跨应用 origin 解析（app-config）', () => {
  test('配置注入后按部署 origin 拼 AI 应用链接', () => {
    ;(window as unknown as { __GRASSLAND_APP_CONFIG__?: { aiAppOrigin: string } }).__GRASSLAND_APP_CONFIG__ =
      { aiAppOrigin: 'http://127.0.0.1:8084' }
    expect(aiAppHref('/')).toBe('http://127.0.0.1:8084/')
    expect(aiAppHref('/', { xat: 'abc' })).toBe('http://127.0.0.1:8084/?xat=abc')
    delete (window as unknown as { __GRASSLAND_APP_CONFIG__?: unknown }).__GRASSLAND_APP_CONFIG__
  })

  test('未配置（dev 同源）回落到 /ai.html 入口', () => {
    expect(aiAppHref('/', { entry: 'hot', title: 'T' })).toContain('/ai.html?entry=hot&title=T')
    expect(grasslandAppHref('/grassland')).toBe(`${window.location.origin}/grassland`)
  })
})

describe('stripUrlParams', () => {
  test('只移除指定参数且不留历史（replaceState）', () => {
    window.history.replaceState(null, '', '/?xat=secret&entry=store&org=o1')
    stripUrlParams(['xat', 'org', 'store', 'entry', 'title', 'platform'])
    expect(window.location.search).toBe('')
  })
})

describe('consumeCrossAppTokenFromUrl（任务书 #86：先清参再请求 + body 带受众）', () => {
  test('无 xat 时是 no-op（TC-C02-001）', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    await expect(consumeCrossAppTokenFromUrl('ai')).resolves.toBe('none')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  test('成功/失败都清参；请求体为 {token, audience}；恰一次 fetch（TC-C02-002）', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) =>
      response({ success: true, data: { token: 'ok' } }))
    vi.stubGlobal('fetch', fetchMock)
    window.history.replaceState(null, '', `/?xat=${TOKEN}`)
    await expect(consumeCrossAppTokenFromUrl('ai')).resolves.toBe('exchanged')
    expect(window.location.search).toBe('')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const init = fetchMock.mock.calls[0]![1] as RequestInit
    expect(JSON.parse(String(init.body))).toEqual({ token: TOKEN, audience: 'ai' })

    vi.stubGlobal('fetch', vi.fn(async () => response({ success: false }, 401)))
    window.history.replaceState(null, '', `/?xat=${TOKEN}`)
    await expect(consumeCrossAppTokenFromUrl('ai')).resolves.toBe('failed')
    expect(window.location.search).toBe('')
  })

  test('请求发起时 URL 已无 xat（TC-C02-003，referrer 机制等价断言）', async () => {
    let searchAtRequest = 'unset'
    vi.stubGlobal('fetch', vi.fn(async () => {
      // Referer 由请求时刻的页面 URL 构造——此处即机制等价断言点
      searchAtRequest = window.location.search
      return response({ success: true, data: { token: 'ok' } })
    }))
    window.history.replaceState(null, '', `/?xat=${TOKEN}`)
    await expect(consumeCrossAppTokenFromUrl('ai')).resolves.toBe('exchanged')
    expect(searchAtRequest).toBe('')
  })

  test('失败路径 console 无 token 明文（TC-C02-004，错误日志哨兵）', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    vi.stubGlobal('fetch', vi.fn(async () => response({ success: false }, 401)))
    window.history.replaceState(null, '', `/?xat=${TOKEN}`)
    await expect(consumeCrossAppTokenFromUrl('ai')).resolves.toBe('failed')
    for (const spy of [errorSpy, warnSpy]) {
      for (const call of spy.mock.calls) {
        expect(String(call)).not.toContain(TOKEN)
      }
    }
    errorSpy.mockRestore()
    warnSpy.mockRestore()
  })
})

describe('useCrossAppJump（任务书 #86：签发 body 带受众）', () => {
  test('已登录：签发 body 为 {audience:"ai"}，token 拼入 xat 后跳 AI 应用（TC-C02-005）', async () => {
    useAuth().currentUser.value = { id: 'u-9', email: 'jumper@example.com', role: 'user', roles: [] }
    ;(window as unknown as { __GRASSLAND_APP_CONFIG__?: { aiAppOrigin: string } }).__GRASSLAND_APP_CONFIG__ =
      { aiAppOrigin: 'http://127.0.0.1:8084' }
    const location = stubLocation()
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      expect(String(input)).toBe('/api/auth/cross-app-tokens')
      expect(init?.method).toBe('POST')
      expect(JSON.parse(String(init?.body))).toEqual({ audience: 'ai' })
      return response({ success: true, data: { token: TOKEN, expiresInSeconds: 300 } })
    })
    vi.stubGlobal('fetch', fetchMock)

    const { jumpToAiApp } = useCrossAppJump()
    await jumpToAiApp('/', { entry: 'store', org: 'o1', store: 's1' })

    expect(location.href).toBe(`http://127.0.0.1:8084/?entry=store&org=o1&store=s1&xat=${TOKEN}`)
    delete (window as unknown as { __GRASSLAND_APP_CONFIG__?: unknown }).__GRASSLAND_APP_CONFIG__
  })

  test('已登录：jumpToGrassland 签发 body 为 {audience:"grassland"}（TC-C02-006）', async () => {
    useAuth().currentUser.value = { id: 'u-9', email: 'jumper@example.com', role: 'user', roles: [] }
    const location = stubLocation()
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      expect(JSON.parse(String(init?.body))).toEqual({ audience: 'grassland' })
      return response({ success: true, data: { token: TOKEN, expiresInSeconds: 300 } })
    })
    vi.stubGlobal('fetch', fetchMock)

    const { jumpToGrassland } = useCrossAppJump()
    await jumpToGrassland('/grassland')

    expect(location.href).toBe(`http://localhost/grassland?xat=${TOKEN}`)
  })

  test('未登录：不发签发请求，直接跳（目标方游客态接住）（TC-C02-007）', async () => {
    const location = stubLocation()
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    const { jumpToGrassland } = useCrossAppJump()
    await jumpToGrassland('/')

    expect(location.href).toBe('http://localhost/')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  test('签发失败（后端不可用）：不带 token 跳，不阻断跳转（TC-C02-008）', async () => {
    useAuth().currentUser.value = { id: 'u-9', email: 'jumper@example.com', role: 'user', roles: [] }
    const location = stubLocation()
    vi.stubGlobal('fetch', vi.fn(async () => response({ success: false }, 503)))

    const { jumpToAiApp } = useCrossAppJump()
    await jumpToAiApp('/')

    expect(location.href).toBe('http://localhost/ai.html')
  })
})
