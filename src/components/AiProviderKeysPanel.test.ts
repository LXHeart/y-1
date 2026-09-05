// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import AiProviderKeysPanel from './AiProviderKeysPanel.vue'

vi.mock('../composables/useActiveIdentity', () => ({
  useActiveIdentity: vi.fn(() => { throw new Error('AI 创作中心不应读取草场活动身份') }),
}))

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

/**
 * 面板挂载只发密钥请求（任务书 #78 卡 C：per-capability 偏好开关已退役，不再拉 preferences）。
 */
function routedFetch(handlers: {
  keys?: () => Response
  fallback?: (url: string, init?: RequestInit) => Response
}) {
  return vi.fn((url: string, init?: RequestInit) => {
    if (url === '/api/ai/keys' && (!init || !init.method || init.method === 'GET')) {
      return Promise.resolve((handlers.keys ?? (() => json([])))())
    }
    return Promise.resolve(handlers.fallback ? handlers.fallback(url, init) : json({}))
  })
}

function key(capability: string, id = 'key-1') {
  return {
    id, organizationId: null, capability, provider: 'openai-compatible',
    baseUrl: 'https://ai.example/v1', model: 'model-a', maskedHint: 'sk-***xyz', enabled: true,
    createdAt: null, updatedAt: null,
  }
}

describe('AiProviderKeysPanel', () => {
  test('个人密钥管理不依赖草场商家或推荐官身份', async () => {
    vi.stubGlobal('fetch', routedFetch({}))
    const wrapper = mount(AiProviderKeysPanel)
    await flushPromises()
    expect(wrapper.find('[data-testid="merchant-readonly-notice"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="key-availability"]').exists()).toBe(true)
    await wrapper.get('[data-action="add-key"]').trigger('click')
    expect(wrapper.find('input[name="apiKey"]').exists()).toBe(true)
  })
  test('initial load failure shows only the error state', async () => {
    vi.stubGlobal('fetch', routedFetch({ keys: () => json({ error: '密钥服务不可用' }, 503) }))
    const wrapper = mount(AiProviderKeysPanel)
    await flushPromises()

    expect(wrapper.get('.error-state[role="alert"]').text()).toContain('密钥服务不可用')
    expect(wrapper.text()).not.toContain('暂无个人模型密钥')
  })

  test('creates a personal key, clears plaintext immediately, and only renders the mask', async () => {
    const textKey = key('text')
    let created = false
    const fetchMock = routedFetch({
      keys: () => json(created ? [textKey] : []),
      fallback: (_url, init) => {
        if (init?.method === 'POST') { created = true; return json(textKey, 201) }
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
    const current = key('text')
    const updated = { ...current, baseUrl: 'https://new.example/v1', model: 'new-model' }
    const rotated = { ...updated, maskedHint: 'sk-***new' }
    let state = current
    const fetchMock = routedFetch({
      keys: () => json([state]),
      fallback: (url, init) => {
        if (init?.method === 'PUT' && url.endsWith('/key')) { state = rotated; return json(rotated) }
        if (init?.method === 'PUT') { state = updated; return json(updated) }
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

  // ---------- 任务书 #78 卡 C（own 态）：能力可用性一览取代 per-capability 开关 ----------

  test('availability band marks configured capabilities ready and missing ones unusable', async () => {
    vi.stubGlobal('fetch', routedFetch({ keys: () => json([key('text'), key('image', 'key-2')]) }))
    const wrapper = mount(AiProviderKeysPanel)
    await flushPromises()

    const rows = wrapper.findAll('.availability-row')
    expect(rows).toHaveLength(4)
    // text/image 配了密钥 → 可用；image_generation/video_generation 未配 → 不可用 + 引导
    expect(rows[0].text()).toContain('已配置 · 可用')
    expect(rows[1].text()).toContain('已配置 · 可用')
    expect(rows[2].text()).toContain('未配置 · 不可用')
    expect(rows[2].find('button').exists()).toBe(true)
    expect(rows[3].text()).toContain('未配置 · 不可用')
    // per-capability 开关不再渲染
    expect(wrapper.find('input[data-action="toggle-text"]').exists()).toBe(false)
  })

  test('availability quick-add preselects the capability in the create form', async () => {
    vi.stubGlobal('fetch', routedFetch({}))
    const wrapper = mount(AiProviderKeysPanel)
    await flushPromises()

    await wrapper.get('button[data-action="add-key-for-video_generation"]').trigger('click')
    const select = wrapper.get('select[name="capability"]')
    expect((select.element as HTMLSelectElement).value).toBe('video_generation')
  })

  test('商家自由创作仍可管理个人密钥，组织上下文不从草场活动身份注入', async () => {
    const { useActiveIdentity } = await import('../composables/useActiveIdentity')
    vi.mocked(useActiveIdentity).mockClear()
    vi.stubGlobal('fetch', routedFetch({ keys: () => json([key('text')]) }))
    const wrapper = mount(AiProviderKeysPanel)
    await flushPromises()

    expect(useActiveIdentity).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="merchant-readonly-notice"]').exists()).toBe(false)
    expect(wrapper.find('button[data-action="add-key"]').exists()).toBe(true)
    expect(wrapper.find('button[data-action="rotate-key"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="key-availability"]').exists()).toBe(true)
  })
})
