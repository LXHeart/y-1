// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import AiProviderKeysPanel from './AiProviderKeysPanel.vue'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('AiProviderKeysPanel', () => {
  test('initial load failure shows only the error state', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ error: '密钥服务不可用' }, 503)))
    const wrapper = mount(AiProviderKeysPanel)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('密钥服务不可用')
    expect(wrapper.text()).not.toContain('暂无个人模型密钥')
  })

  test('creates a personal key, clears plaintext immediately, and only renders the mask', async () => {
    const key = {
      id: 'key-1', organizationId: null, capability: 'text', provider: 'openai-compatible',
      baseUrl: 'https://ai.example/v1', model: 'model-a', maskedHint: 'sk-***xyz', enabled: true,
      createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:00:00Z',
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json(key, 201))
      .mockResolvedValueOnce(json([key]))
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
    expect(JSON.parse(fetchMock.mock.calls[1][1].body).apiKey).toBe('sk-plain-secret')
  })

  test('supports editing, rotation, and disabling with explicit confirmation', async () => {
    const key = {
      id: 'key-1', organizationId: null, capability: 'text', provider: 'openai-compatible',
      baseUrl: 'https://old.example/v1', model: 'old-model', maskedHint: 'sk-***old', enabled: true,
      createdAt: null, updatedAt: null,
    }
    const updated = { ...key, baseUrl: 'https://new.example/v1', model: 'new-model' }
    const rotated = { ...updated, maskedHint: 'sk-***new' }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([key]))
      .mockResolvedValueOnce(json(updated))
      .mockResolvedValueOnce(json([updated]))
      .mockResolvedValueOnce(json(rotated))
      .mockResolvedValueOnce(json([rotated]))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(json([{ ...rotated, enabled: false }]))
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('confirm', vi.fn(() => true))
    const wrapper = mount(AiProviderKeysPanel)
    await flushPromises()

    await wrapper.get('button[data-action="edit-key"]').trigger('click')
    await wrapper.get('input[name="baseUrl"]').setValue('https://new.example/v1')
    await wrapper.get('input[name="model"]').setValue('new-model')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(fetchMock.mock.calls[1][1].method).toBe('PUT')

    await wrapper.get('button[data-action="rotate-key"]').trigger('click')
    const rotateInput = wrapper.get('input[name="apiKey"]')
    await rotateInput.setValue('sk-new-secret')
    await wrapper.get('form').trigger('submit')
    expect((rotateInput.element as HTMLInputElement).value).toBe('')
    await flushPromises()

    await wrapper.get('button[data-action="disable-key"]').trigger('click')
    await flushPromises()
    expect(fetchMock.mock.calls[5][1].method).toBe('DELETE')
    expect(confirm).toHaveBeenCalled()
  })
})
