// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { consumeCrossAppTokenFromUrl, stripUrlParams, useCrossAppJump } from './useCrossAppToken'
import { aiAppHref, grasslandAppHref } from '../lib/app-config'
import { useAuth } from './useAuth'

function response(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

const TOKEN = 'xat-token-0123456789abcdef0123456789abcdef0123456789'

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

describe('consumeCrossAppTokenFromUrl', () => {
  test('无 xat 时是 no-op', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    await expect(consumeCrossAppTokenFromUrl()).resolves.toBe('none')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  test('核销成功清参；失败也清参（防刷新重放与分享泄漏）', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/exchange')) return response({ success: true, data: { token: 'ok' } })
      throw new Error('unexpected')
    }))
    window.history.replaceState(null, '', `/?xat=${TOKEN}`)
    await expect(consumeCrossAppTokenFromUrl()).resolves.toBe('exchanged')
    expect(window.location.search).toBe('')

    vi.stubGlobal('fetch', vi.fn(async () => response({ success: false }, 401)))
    window.history.replaceState(null, '', `/?xat=${TOKEN}`)
    await expect(consumeCrossAppTokenFromUrl()).resolves.toBe('failed')
    expect(window.location.search).toBe('')
  })
})

describe('useCrossAppJump', () => {
  test('已登录：签发 token 拼入 xat 后跳目标应用', async () => {
    useAuth().currentUser.value = { id: 'u-9', email: 'jumper@example.com', role: 'user', roles: [] }
    ;(window as unknown as { __GRASSLAND_APP_CONFIG__?: { aiAppOrigin: string } }).__GRASSLAND_APP_CONFIG__ =
      { aiAppOrigin: 'http://127.0.0.1:8084' }
    const location = stubLocation()
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      expect(String(input)).toBe('/api/auth/cross-app-tokens')
      expect(init?.method).toBe('POST')
      return response({ success: true, data: { token: TOKEN, expiresInSeconds: 300 } })
    }))

    const { jumpToAiApp } = useCrossAppJump()
    await jumpToAiApp('/', { entry: 'store', org: 'o1', store: 's1' })

    expect(location.href).toBe(`http://127.0.0.1:8084/?entry=store&org=o1&store=s1&xat=${TOKEN}`)
    delete (window as unknown as { __GRASSLAND_APP_CONFIG__?: unknown }).__GRASSLAND_APP_CONFIG__
  })

  test('未登录：不发签发请求，直接跳（目标方游客态接住）', async () => {
    const location = stubLocation()
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    const { jumpToGrassland } = useCrossAppJump()
    await jumpToGrassland('/')

    expect(location.href).toBe('http://localhost/')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  test('签发失败（后端不可用）：不带 token 跳，不阻断跳转', async () => {
    useAuth().currentUser.value = { id: 'u-9', email: 'jumper@example.com', role: 'user', roles: [] }
    const location = stubLocation()
    vi.stubGlobal('fetch', vi.fn(async () => response({ success: false }, 503)))

    const { jumpToAiApp } = useCrossAppJump()
    await jumpToAiApp('/')

    expect(location.href).toBe('http://localhost/ai.html')
  })
})
