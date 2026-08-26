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

  test('fetch-models merges upstream list with the already-ticked set', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([QWEN_CREDENTIAL]))
      .mockResolvedValueOnce(json([{ id: 'qwen-plus' }, { id: 'qwen-max' }]))
      .mockResolvedValueOnce(json([{ id: 'qwen-plus' }, { id: 'qwen-retired' }]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiPlatformCredentialsPanel)
    await flushPromises()

    await wrapper.get('button[data-action="fetch-models"]').trigger('click')
    await flushPromises()

    expect(fetchMock.mock.calls[1][0]).toContain('/api/admin/ai/credentials/cred-1/models')
    expect(fetchMock.mock.calls[2][0]).toContain('/api/admin/ai/credentials/cred-1/selected-models')

    // 上游两个 + 已勾选但上游未返回的 qwen-retired（保留并标注）
    const ids = wrapper.findAll('.model-picker .model-id').map((node) => node.text())
    expect(ids).toEqual(['qwen-plus', 'qwen-max', 'qwen-retired'])
    expect(wrapper.get('.stale-tag').text()).toContain('上游本次未返回')

    // 勾选态来自 selected-models，不是「上游有就勾」
    const boxes = wrapper.findAll('.model-picker input[type="checkbox"]')
    expect((boxes[0].element as HTMLInputElement).checked).toBe(true)
    expect((boxes[1].element as HTMLInputElement).checked).toBe(false)
    expect((boxes[2].element as HTMLInputElement).checked).toBe(true)
  })

  test('saving ticked models PUTs the whole set and carries ownedBy', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([QWEN_CREDENTIAL]))
      .mockResolvedValueOnce(json([{ id: 'qwen-plus', ownedBy: 'aliyun' }, { id: 'qwen-max' }]))
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json([{ id: 'qwen-plus', ownedBy: 'aliyun' }]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiPlatformCredentialsPanel)
    await flushPromises()

    await wrapper.get('button[data-action="fetch-models"]').trigger('click')
    await flushPromises()

    await wrapper.findAll('.model-picker input[type="checkbox"]')[0].setValue(true)
    await wrapper.get('button[data-action="save-models"]').trigger('click')
    await flushPromises()

    const put = fetchMock.mock.calls.find((call) => call[1]?.method === 'PUT')
    if (!put) throw new Error('expected a PUT to /selected-models')
    expect(put[0]).toContain('/selected-models')
    expect(JSON.parse(put[1].body)).toEqual({ models: [{ id: 'qwen-plus', ownedBy: 'aliyun' }] })
    // 保存成功后收起面板
    expect(wrapper.find('.model-picker').exists()).toBe(false)
  })

  test('upstream failure keeps the existing ticked set instead of wiping it', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([QWEN_CREDENTIAL]))
      .mockResolvedValueOnce(json({ error: 'AI base-url 指向内网/私有/环回地址，已拒绝' }, 400))
      .mockResolvedValueOnce(json([{ id: 'qwen-plus' }]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiPlatformCredentialsPanel)
    await flushPromises()

    await wrapper.get('button[data-action="fetch-models"]').trigger('click')
    await flushPromises()

    // 一次网络故障不该擦掉运营已做的选择
    const ids = wrapper.findAll('.model-picker .model-id').map((node) => node.text())
    expect(ids).toEqual(['qwen-plus'])
    expect((wrapper.get('.model-picker input[type="checkbox"]').element as HTMLInputElement).checked)
      .toBe(true)
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
