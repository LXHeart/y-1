// @vitest-environment happy-dom
// RuntimePanel.test.ts — C107-21 (TC107-21-01 disabled 态 / 密钥写后清)：
// capabilities disabled 显示明确未启用；凭据写入成功后输入框立即清空。
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import RuntimePanel from './RuntimePanel.vue'
import type { HypitCapabilities } from '../../../types/hypit'

const disabledCaps: HypitCapabilities = {
  enabled: false,
  version: null,
  features: [{ id: 'engine', installed: false, configured: false, prepared: false, ready: false, reason: 'HYPIT_ENABLED=false', action: '联系部署者启用 Hypit' }],
  templates: [],
}

const readyCaps: HypitCapabilities = {
  enabled: true,
  version: '0.2.13',
  features: [{ id: 'engine', installed: true, configured: true, prepared: true, ready: true, reason: null, action: null }],
  templates: [{ id: 'ranking-tier', title: '排行榜层叠', available: true }],
}

function respond(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('RuntimePanel（TC107-21-01/TC107-22-03 disabled 口径）', () => {
  test('disabled：显示未启用说明，不循环请求', async () => {
    const fetchMock = vi.fn(async () => respond(200, { success: true, data: disabledCaps }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RuntimePanel, { props: { projectReady: false } })
    await vi.waitFor(() => expect(wrapper.find('[data-testid="clone-disabled"]').exists()).toBe(true))
    expect(fetchMock).toHaveBeenCalledTimes(1)
    // 不自动重试：仍只有一次请求。
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  test('ready：特性就绪状态如实展示', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => respond(200, { success: true, data: readyCaps })))
    const wrapper = mount(RuntimePanel, { props: { projectReady: true } })
    await vi.waitFor(() => expect(wrapper.find('[data-ready="true"]').exists()).toBe(true))
  })

  test('凭据写入成功后输入框立即清空', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/capabilities')) return respond(200, { success: true, data: readyCaps })
      if (url.includes('/runtime/credentials/')) {
        const body = JSON.parse(String(init?.body)) as { secret: string }
        expect(body.secret).toBe('sk-test-secret')
        return respond(200, { success: true, data: { configured: true } })
      }
      return respond(404, { success: false, error: 'nf', code: 'hypit_not_found' })
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RuntimePanel, { props: { projectReady: true } })
    await vi.waitFor(() => expect(wrapper.find('[data-ready="true"]').exists()).toBe(true))
    const inputs = wrapper.findAll('input')
    const slot = inputs.find((node) => node.attributes('aria-label') === '凭据槽')
    const secret = inputs.find((node) => node.attributes('aria-label') === '密钥')
    expect(slot).toBeDefined()
    expect(secret).toBeDefined()
    await slot!.setValue('x/apiKey')
    await secret!.setValue('sk-test-secret')
    await wrapper.find('form.clone-runtime-secret').trigger('submit')
    await vi.waitFor(() => expect((secret!.element as HTMLInputElement).value).toBe(''))
  })
})
