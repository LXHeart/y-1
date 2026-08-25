// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import AiPlatformCredentialsPanel from './AiPlatformCredentialsPanel.vue'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

const QWEN_CREDENTIAL = {
  id: 'cred-1', name: '主力-通义', provider: 'qwen', baseUrl: 'https://dashscope.example/v1',
  hasKey: true, maskedHint: 'sk-****cdef', enabled: true, version: 1,
  createdAt: '2026-08-25T00:00:00Z', updatedAt: '2026-08-25T00:00:00Z',
}

describe('AiPlatformCredentialsPanel', () => {
  test('initial load failure shows only the error state', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ error: '凭据控制面不可用' }, 503)))
    const wrapper = mount(AiPlatformCredentialsPanel)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('凭据控制面不可用')
    expect(wrapper.text()).not.toContain('暂无平台凭据')
  })

  test('shows masked hint and never renders a plaintext key', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json([QWEN_CREDENTIAL])))
    const wrapper = mount(AiPlatformCredentialsPanel)
    await flushPromises()

    expect(wrapper.text()).toContain('sk-****cdef')
    expect(wrapper.text()).not.toContain('sk-real')
  })

  test('creates a credential and clears the key binding after submit', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json(QWEN_CREDENTIAL, 201))
      .mockResolvedValueOnce(json([QWEN_CREDENTIAL]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiPlatformCredentialsPanel)
    await flushPromises()

    await wrapper.get('button[data-action="add-credential"]').trigger('click')
    await wrapper.get('input[name="name"]').setValue('主力-通义')
    await wrapper.get('input[name="baseUrl"]').setValue('https://dashscope.example/v1')
    await wrapper.get('input[name="apiKey"]').setValue('sk-real-secret-value')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const [, createCall] = fetchMock.mock.calls
    expect(createCall[0]).toBe('/api/admin/ai/credentials')
    expect(createCall[1]).toMatchObject({ method: 'POST' })
    expect(JSON.parse(createCall[1].body as string)).toMatchObject({
      name: '主力-通义', provider: 'qwen', apiKey: 'sk-real-secret-value',
    })
    // 表单关闭后明文不再留在 DOM 里
    expect(wrapper.find('input[name="apiKey"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('sk-real-secret-value')
  })

  test('sandbox credential submits without an apiKey field', async () => {
    const sandbox = {
      ...QWEN_CREDENTIAL, id: 'cred-2', name: '内置沙箱', provider: 'sandbox',
      baseUrl: 'https://sandbox.invalid', hasKey: false, maskedHint: null,
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json(sandbox, 201))
      .mockResolvedValueOnce(json([sandbox]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiPlatformCredentialsPanel)
    await flushPromises()

    await wrapper.get('button[data-action="add-credential"]').trigger('click')
    await wrapper.get('input[name="name"]').setValue('内置沙箱')
    await wrapper.get('select[name="provider"]').setValue('sandbox')
    await wrapper.get('input[name="baseUrl"]').setValue('https://sandbox.invalid')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const [, createCall] = fetchMock.mock.calls
    expect(JSON.parse(createCall[1].body as string).apiKey).toBeUndefined()
    expect(wrapper.text()).toContain('沙箱免密')
  })

  test('edit omits the key field; rotation posts to the dedicated key endpoint', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([QWEN_CREDENTIAL]))
      .mockResolvedValueOnce(json({ ...QWEN_CREDENTIAL, version: 2 }))
      .mockResolvedValueOnce(json([{ ...QWEN_CREDENTIAL, version: 2 }]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiPlatformCredentialsPanel)
    await flushPromises()

    await wrapper.get('button[data-action="edit-credential"]').trigger('click')
    expect(wrapper.find('input[name="apiKey"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('改密钥请用「轮换」')

    await wrapper.get('button[aria-label="关闭凭据表单"]').trigger('click')
    await wrapper.get('button[data-action="rotate-credential"]').trigger('click')
    await wrapper.get('input[name="apiKey"]').setValue('sk-rotated-value')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const [, rotateCall] = fetchMock.mock.calls
    expect(rotateCall[0]).toBe('/api/admin/ai/credentials/cred-1/key')
    expect(rotateCall[1]).toMatchObject({ method: 'PUT' })
  })

  test('surfaces the 409 reference count when disabling a credential in use', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([QWEN_CREDENTIAL]))
      .mockResolvedValueOnce(json({ error: '该凭据仍被 3 个模型配置引用，请先改指向后再停用' }, 409))
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true))
    const wrapper = mount(AiPlatformCredentialsPanel)
    await flushPromises()

    await wrapper.get('button[data-action="disable-credential"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('仍被 3 个模型配置引用')
  })
})
