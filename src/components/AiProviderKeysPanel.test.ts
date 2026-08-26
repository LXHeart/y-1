// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import AiProviderKeysPanel from './AiProviderKeysPanel.vue'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

/** 任务书 #47 S5：无偏好行即 on（D14），后端 GET 会把四个能力都补成默认。 */
const ALL_ON = {
  success: true,
  data: {
    items: (['text', 'image', 'image_generation', 'video_generation'] as const).map((capability) => ({
      capability, useOwnKey: true, configured: false, version: 0, updatedAt: null,
    })),
  },
}

/**
 * 面板挂载时并发发两个请求（密钥 + 偏好），顺序不保证——故按 URL 分发而不用
 * mockResolvedValueOnce 链，避免加一个请求就把整条序列错位。
 */
function routedFetch(handlers: {
  keys?: () => Response
  preferences?: () => Response
  fallback?: (url: string, init?: RequestInit) => Response
}) {
  return vi.fn((url: string, init?: RequestInit) => {
    if (url === '/api/ai/preferences') {
      return Promise.resolve((handlers.preferences ?? (() => json(ALL_ON)))())
    }
    if (url === '/api/ai/keys' && (!init || !init.method || init.method === 'GET')) {
      return Promise.resolve((handlers.keys ?? (() => json([])))())
    }
    return Promise.resolve(handlers.fallback ? handlers.fallback(url, init) : json({}))
  })
}

describe('AiProviderKeysPanel', () => {
  test('initial load failure shows only the error state', async () => {
    vi.stubGlobal('fetch', routedFetch({ keys: () => json({ error: '密钥服务不可用' }, 503) }))
    const wrapper = mount(AiProviderKeysPanel)
    await flushPromises()

    expect(wrapper.get('.error-state[role="alert"]').text()).toContain('密钥服务不可用')
    expect(wrapper.text()).not.toContain('暂无个人模型密钥')
  })

  test('creates a personal key, clears plaintext immediately, and only renders the mask', async () => {
    const key = {
      id: 'key-1', organizationId: null, capability: 'text', provider: 'openai-compatible',
      baseUrl: 'https://ai.example/v1', model: 'model-a', maskedHint: 'sk-***xyz', enabled: true,
      createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:00:00Z',
    }
    let created = false
    const fetchMock = routedFetch({
      keys: () => json(created ? [key] : []),
      fallback: (_url, init) => {
        if (init?.method === 'POST') { created = true; return json(key, 201) }
        return json({})
      },
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiProviderKeysPanel)
    await flushPromises()

    await wrapper.get('button[data-action="add-key"]').trigger('click')
    await wrapper.get('input[name="baseUrl"]').setValue('https://ai.example/v1')
    await wrapper.get('input[name="model"]').setValue('model-a')
    const apiKeyInput = wrapper.get('input[name="apiKey"]')
    await apiKeyInput.setValue('sk-plain-secret')
    await wrapper.get('form').trigger('submit')
    expect((apiKeyInput.element as HTMLInputElement).value).toBe('')
    await flushPromises()

    expect(wrapper.text()).toContain('sk-***xyz')
    expect(wrapper.text()).not.toContain('sk-plain-secret')
    const postCall = fetchMock.mock.calls.find((call) => call[1]?.method === 'POST')
    expect(JSON.parse(String(postCall![1]!.body)).apiKey).toBe('sk-plain-secret')
  })

  test('supports editing, rotation, and disabling with explicit confirmation', async () => {
    const key = {
      id: 'key-1', organizationId: null, capability: 'text', provider: 'openai-compatible',
      baseUrl: 'https://old.example/v1', model: 'old-model', maskedHint: 'sk-***old', enabled: true,
      createdAt: null, updatedAt: null,
    }
    const updated = { ...key, baseUrl: 'https://new.example/v1', model: 'new-model' }
    const rotated = { ...updated, maskedHint: 'sk-***new' }
    let current = key
    const fetchMock = routedFetch({
      keys: () => json([current]),
      fallback: (url, init) => {
        if (init?.method === 'PUT' && url.endsWith('/key')) { current = rotated; return json(rotated) }
        if (init?.method === 'PUT') { current = updated; return json(updated) }
        if (init?.method === 'DELETE') return new Response(null, { status: 204 })
        return json({})
      },
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('confirm', vi.fn(() => true))
    const wrapper = mount(AiProviderKeysPanel)
    await flushPromises()

    await wrapper.get('button[data-action="edit-key"]').trigger('click')
    await wrapper.get('input[name="baseUrl"]').setValue('https://new.example/v1')
    await wrapper.get('input[name="model"]').setValue('new-model')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(fetchMock.mock.calls.some((call) =>
      call[0] === '/api/ai/keys/key-1' && call[1]?.method === 'PUT')).toBe(true)

    await wrapper.get('button[data-action="rotate-key"]').trigger('click')
    const rotateInput = wrapper.get('input[name="apiKey"]')
    await rotateInput.setValue('sk-new-secret')
    await wrapper.get('form').trigger('submit')
    expect((rotateInput.element as HTMLInputElement).value).toBe('')
    await flushPromises()
    expect(fetchMock.mock.calls.some((call) =>
      call[0] === '/api/ai/keys/key-1/key' && call[1]?.method === 'PUT')).toBe(true)

    await wrapper.get('button[data-action="disable-key"]').trigger('click')
    await flushPromises()
    expect(fetchMock.mock.calls.some((call) => call[1]?.method === 'DELETE')).toBe(true)
    expect(confirm).toHaveBeenCalled()
  })

  // ---------- 任务书 #47 S6：四开关 + 计费主体 + 关闭二次确认 ----------

  test('shows billing subject per capability; own-key only when a key exists', async () => {
    const textKey = {
      id: 'key-1', organizationId: null, capability: 'text', provider: 'openai-compatible',
      baseUrl: 'https://ai.example/v1', model: 'model-a', maskedHint: 'sk-***xyz', enabled: true,
      createdAt: null, updatedAt: null,
    }
    vi.stubGlobal('fetch', routedFetch({ keys: () => json([textKey]) }))
    const wrapper = mount(AiProviderKeysPanel)
    await flushPromises()

    const rows = wrapper.findAll('.switch-row')
    expect(rows).toHaveLength(4)
    // text 配了密钥且开关 on → 我的模型
    expect(rows[0].text()).toContain('自定义模型 · 不扣积分')
    // image 开关 on 但没配密钥 → 仍是平台，否则用户会以为自己在付费
    expect(rows[1].text()).toContain('平台内置 · 按积分计费')
  })

  test('disabling a switch asks for confirmation and states the billing change', async () => {
    // 显式声明入参类型：vi.fn(() => true) 会推断成零参数，calls[0][0] 就索引不到
    const confirmMock = vi.fn((_message?: string) => true)
    vi.stubGlobal('confirm', confirmMock)
    const fetchMock = routedFetch({
      fallback: (url, init) => {
        if (url === '/api/ai/preferences/text' && init?.method === 'PUT') {
          return json({ success: true, data: {
            capability: 'text', useOwnKey: false, configured: true, version: 1, updatedAt: null } })
        }
        return json({})
      },
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiProviderKeysPanel)
    await flushPromises()

    await wrapper.get('input[data-action="toggle-text"]').setValue(false)
    await flushPromises()

    expect(confirmMock).toHaveBeenCalledTimes(1)
    expect(String(confirmMock.mock.calls[0][0])).toContain('按积分计费')
    const putCall = fetchMock.mock.calls.find((call) => call[0] === '/api/ai/preferences/text')
    expect(JSON.parse(String(putCall![1]!.body))).toEqual({ useOwnKey: false, expectedVersion: 0 })
    expect(wrapper.findAll('.switch-row')[0].text()).toContain('使用平台内置模型')
  })

  test('cancelling the confirmation leaves the switch untouched', async () => {
    vi.stubGlobal('confirm', vi.fn(() => false))
    const fetchMock = routedFetch({})
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiProviderKeysPanel)
    await flushPromises()

    await wrapper.get('input[data-action="toggle-text"]').setValue(false)
    await flushPromises()

    expect(fetchMock.mock.calls.some((call) => call[0] === '/api/ai/preferences/text')).toBe(false)
    expect(wrapper.findAll('.switch-row')[0].text()).toContain('使用自定义模型')
  })

  /**
   * D9 在前端的可见后果：商家身份下个人密钥不参与路由，故面板整体只读并给出指向。
   *
   * 直接改 activeSide 的 ref（它是模块级单例），并在用例末尾还原——比驱动
   * loadAccountIdentity 的完整装载路径更隔离，也避免单例状态泄漏到后续用例。
   */
  test('merchant identity renders read-only with a pointer to org settings', async () => {
    const { useActiveIdentity } = await import('../composables/useActiveIdentity')
    const identity = useActiveIdentity()
    identity.activeSide.value = 'merchant'
    identity.identitiesLoaded.value = true
    try {
      vi.stubGlobal('fetch', routedFetch({}))
      const wrapper = mount(AiProviderKeysPanel)
      await flushPromises()

      expect(wrapper.get('[data-testid="merchant-readonly-notice"]').text()).toContain('工作台 → 组织管理')
      expect(wrapper.find('button[data-action="add-key"]').exists()).toBe(false)
      expect(wrapper.findAll('.switch-row')).toHaveLength(0)
    } finally {
      identity.identitiesLoaded.value = false
      identity.activeSide.value = 'merchant'
    }
  })

  test('turning a switch back on needs no confirmation (saving money is not gated)', async () => {
    const confirmMock = vi.fn(() => true)
    vi.stubGlobal('confirm', confirmMock)
    vi.stubGlobal('fetch', routedFetch({
      preferences: () => json({ success: true, data: { items: [
        { capability: 'text', useOwnKey: false, configured: true, version: 3, updatedAt: null },
      ] } }),
      fallback: (url, init) => url === '/api/ai/preferences/text' && init?.method === 'PUT'
        ? json({ success: true, data: {
            capability: 'text', useOwnKey: true, configured: true, version: 4, updatedAt: null } })
        : json({}),
    }))
    const wrapper = mount(AiProviderKeysPanel)
    await flushPromises()

    await wrapper.get('input[data-action="toggle-text"]').setValue(true)
    await flushPromises()

    expect(confirmMock).not.toHaveBeenCalled()
    expect(wrapper.findAll('.switch-row')[0].text()).toContain('使用自定义模型')
  })
})
