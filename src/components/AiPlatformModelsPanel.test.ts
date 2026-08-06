// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import AiPlatformModelsPanel from './AiPlatformModelsPanel.vue'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('AiPlatformModelsPanel', () => {
  test('initial load failure shows only the error state', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ error: '模型控制面不可用' }, 503)))
    const wrapper = mount(AiPlatformModelsPanel)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('模型控制面不可用')
    expect(wrapper.text()).not.toContain('暂无平台模型配置')
  })

  test('creates primary/backup model configuration with health and concurrency', async () => {
    const created = {
      id: 'model-1', capability: 'text', modelRole: 'backup', provider: 'qwen', model: 'qwen-plus',
      baseUrl: 'https://dashscope.example/v1', maxConcurrency: 8, healthStatus: 'degraded',
      enabled: true, version: 1, createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:00:00Z',
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json(created, 201))
      .mockResolvedValueOnce(json([created]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiPlatformModelsPanel)
    await flushPromises()

    await wrapper.get('button[data-action="add-model"]').trigger('click')
    await wrapper.get('select[name="modelRole"]').setValue('backup')
    await wrapper.get('input[name="provider"]').setValue('qwen')
    await wrapper.get('input[name="model"]').setValue('qwen-plus')
    await wrapper.get('input[name="baseUrl"]').setValue('https://dashscope.example/v1')
    await wrapper.get('input[name="maxConcurrency"]').setValue('8')
    await wrapper.get('select[name="healthStatus"]').setValue('degraded')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(fetchMock.mock.calls[1][1].method).toBe('POST')
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toMatchObject({
      capability: 'text', modelRole: 'backup', maxConcurrency: 8, healthStatus: 'degraded',
    })
    expect(wrapper.text()).toContain('qwen-plus')
    expect(wrapper.text()).toContain('备用')
  })

  test('revises existing models and disables them after confirmation', async () => {
    const model = {
      id: 'model-1', capability: 'text', modelRole: 'primary', provider: 'qwen', model: 'qwen-plus',
      baseUrl: 'https://old.example/v1', maxConcurrency: null, healthStatus: 'healthy',
      enabled: true, version: 2, createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:00:00Z',
    }
    const revised = { ...model, model: 'qwen-max', version: 3 }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([model]))
      .mockResolvedValueOnce(json(revised))
      .mockResolvedValueOnce(json([revised]))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(json([]))
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('confirm', vi.fn(() => true))
    const wrapper = mount(AiPlatformModelsPanel)
    await flushPromises()

    await wrapper.get('button[data-action="edit-model"]').trigger('click')
    expect(wrapper.get('input[name="capability"]').attributes('disabled')).toBeDefined()
    await wrapper.get('input[name="model"]').setValue('qwen-max')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(fetchMock.mock.calls[1][1].method).toBe('PUT')

    await wrapper.get('button[data-action="disable-model"]').trigger('click')
    await flushPromises()
    expect(fetchMock.mock.calls[3][1].method).toBe('DELETE')
    expect(wrapper.text()).toContain('暂无平台模型配置')
  })
})
