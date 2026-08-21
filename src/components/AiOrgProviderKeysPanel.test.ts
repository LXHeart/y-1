// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import AiOrgProviderKeysPanel from './AiOrgProviderKeysPanel.vue'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

const ORG = 'org-17'

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

function policyData(overrides: Record<string, unknown> = {}) {
  return { configured: true, allowPlatformFallback: false, version: 3, updatedAt: null, ...overrides }
}

/** 按默认顺序应答：GET keys → GET policy。 */
function stubHappyPath(keys: unknown[], policy = policyData()) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === `/api/ai/organizations/${ORG}/keys`) return json(keys)
    if (url === `/api/ai/organizations/${ORG}/byok-policy`) return json({ success: true, data: policy })
    return json([], 404)
  })
}

describe('AiOrgProviderKeysPanel', () => {
  test('渲染组织密钥与回退开关初始态；掩码可见、密文不出现', async () => {
    const key = {
      id: 'org-key-1', organizationId: ORG, capability: 'text', provider: 'openai-compatible',
      baseUrl: 'https://ai.example/v1', model: 'org-model', maskedHint: 'sk-***org', enabled: true,
      createdAt: null, updatedAt: null,
    }
    vi.stubGlobal('fetch', stubHappyPath([key]))
    const wrapper = mount(AiOrgProviderKeysPanel, { props: { organizationId: ORG } })
    await flushPromises()

    expect(wrapper.text()).toContain('组织模型密钥')
    expect(wrapper.text()).toContain('sk-***org')
    expect(wrapper.text()).not.toContain('ciphertext')
    const toggle = wrapper.get('input[data-action="toggle-org-fallback"]')
    expect((toggle.element as HTMLInputElement).checked).toBe(false)
    expect(wrapper.text()).toContain('未允许')
  })

  test('创建组织密钥打到组织端点，明文即用即清', async () => {
    const created = {
      id: 'org-key-2', organizationId: ORG, capability: 'text', provider: 'openai-compatible',
      baseUrl: 'https://ai.example/v1', model: 'm', maskedHint: 'sk-***new', enabled: true,
      createdAt: null, updatedAt: null,
    }
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === `/api/ai/organizations/${ORG}/keys` && init?.method === 'POST') return json(created, 201)
      if (url === `/api/ai/organizations/${ORG}/keys`) return json(init?.method ? [] : [created])
      if (url === `/api/ai/organizations/${ORG}/byok-policy`) return json({ success: true, data: policyData() })
      return json([], 404)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiOrgProviderKeysPanel, { props: { organizationId: ORG } })
    await flushPromises()

    await wrapper.get('button[data-action="add-org-key"]').trigger('click')
    await wrapper.get('input[name="baseUrl"]').setValue('https://ai.example/v1')
    const apiKeyInput = wrapper.get('input[name="apiKey"]')
    await apiKeyInput.setValue('sk-org-plain')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const createCall = fetchMock.mock.calls.find(([url, init]) =>
      String(url) === `/api/ai/organizations/${ORG}/keys` && init?.method === 'POST')
    expect(createCall).toBeTruthy()
    expect(JSON.parse(String(createCall![1]?.body)).apiKey).toBe('sk-org-plain')
    expect((apiKeyInput.element as HTMLInputElement).value).toBe('')
  })

  test('打开回退开关带 expectedVersion；409 冲突时重载并提示', async () => {
    let policyGetCount = 0
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === `/api/ai/organizations/${ORG}/keys`) return json([])
      if (url === `/api/ai/organizations/${ORG}/byok-policy` && init?.method === 'PUT') {
        return json({ success: false, error: '策略已被他人修改，请刷新后重试' }, 409)
      }
      if (url === `/api/ai/organizations/${ORG}/byok-policy`) {
        policyGetCount += 1
        // 首次载入 version=3；409 冲突重载时模拟他已改为 version=4
        const version = policyGetCount === 1 ? 3 : 4
        return json({ success: true, data: policyData({ allowPlatformFallback: false, version }) })
      }
      return json([], 404)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiOrgProviderKeysPanel, { props: { organizationId: ORG } })
    await flushPromises()

    const toggle = wrapper.get('input[data-action="toggle-org-fallback"]')
    await toggle.setValue(true)
    await flushPromises()

    const putCall = fetchMock.mock.calls.find(([url, init]) =>
      String(url) === `/api/ai/organizations/${ORG}/byok-policy` && init?.method === 'PUT')
    expect(JSON.parse(String(putCall![1]?.body))).toEqual({ expectedVersion: 3, allowPlatformFallback: true })
    expect(wrapper.text()).toContain('策略已被其他管理员修改，已重新载入，请重试')
    // 冲突后按服务端 version=4 重载，开关回退为未勾选
    expect((wrapper.get('input[data-action="toggle-org-fallback"]').element as HTMLInputElement).checked).toBe(false)
  })

  test('组织不存在（404）显示专用提示', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ success: false, error: '组织不存在' }, 404)))
    const wrapper = mount(AiOrgProviderKeysPanel, { props: { organizationId: ORG } })
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('组织不存在或当前环境未启用密钥托管')
  })
})
