// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import AiCenterExternalRedirect from './AiCenterExternalRedirect.vue'
import { useAuth } from '../../composables/useAuth'

/** 任务书 #76 卡 D3：草场旧 /ai-center 深链兼容——外跳 AI 应用（已登录带 xat 免登）。 */
function response(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

const ORIGINAL_LOCATION = Object.getOwnPropertyDescriptor(window, 'location')
function stubLocation(): { href: string } {
  const stub = { href: 'http://localhost/', origin: 'http://localhost' }
  Object.defineProperty(window, 'location', { value: stub, configurable: true, writable: true })
  return stub
}

beforeEach(() => { useAuth().currentUser.value = null })
afterEach(() => {
  useAuth().currentUser.value = null
  vi.unstubAllGlobals()
  if (ORIGINAL_LOCATION) Object.defineProperty(window, 'location', ORIGINAL_LOCATION)
})
enableAutoUnmount(afterEach)

describe('旧 /ai-center 外跳兼容', () => {
  test('已登录：签发一次性 token，整页跳 AI 应用并拼 xat', async () => {
    useAuth().currentUser.value = { id: 'u-1', email: 'legacy@example.com', role: 'user', roles: [] }
    ;(window as unknown as { __GRASSLAND_APP_CONFIG__?: { aiAppOrigin: string } }).__GRASSLAND_APP_CONFIG__ =
      { aiAppOrigin: 'http://127.0.0.1:8084' }
    const location = stubLocation()
    const log: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      log.push(`${init?.method ?? 'GET'} ${String(input)}`)
      return response({ success: true, data: { token: 'issued-token-0123456789abcdef0123456789', expiresInSeconds: 300 } }) // secret-scan: allow（mock 签发 token 夹具）
    }))

    mount(AiCenterExternalRedirect)
    await flushPromises()

    expect(log).toContain('POST /api/auth/cross-app-tokens')
    expect(location.href).toContain('http://127.0.0.1:8084/?xat=issued-token')
    delete (window as unknown as { __GRASSLAND_APP_CONFIG__?: unknown }).__GRASSLAND_APP_CONFIG__
  })

  test('未登录：不签发直接跳（AI 应用落游客态）；签发失败也不阻断跳转', async () => {
    const location = stubLocation()
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    mount(AiCenterExternalRedirect)
    await flushPromises()

    expect(fetchMock).not.toHaveBeenCalled()
    expect(location.href).toContain('/ai.html')
  })

  test('渲染过渡态不白屏（标题与手动跳转按钮）', async () => {
    stubLocation()
    vi.stubGlobal('fetch', vi.fn(async () => response({ success: true, data: { token: 'x'.repeat(43) } })))
    const wrapper = mount(AiCenterExternalRedirect)
    await flushPromises()

    expect(wrapper.text()).toContain('正在前往 AI 创作中心')
  })
})
