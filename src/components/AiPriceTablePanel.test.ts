// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import AiPriceTablePanel from './AiPriceTablePanel.vue'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

const ACTIVE = {
  id: 'pt-1', label: 'v1', status: 'active', note: '初始价目', createdBy: 'system',
  createdAt: '2026-08-26T00:00:00Z', activatedAt: '2026-08-26T00:00:00Z',
}
const RETIRED = { ...ACTIVE, id: 'pt-0', label: 'v0', status: 'retired', note: '上一版' }
const DRAFT = { ...ACTIVE, id: 'pt-2', label: 'v2', status: 'draft', note: '调价', activatedAt: null }

const QWEN_ROW = {
  modelId: 'qwen-plus', capability: 'text', provider: 'qwen',
  centsPer1kInputTokens: 3, centsPer1kOutputTokens: 6, centsPerImage: 200, centsPerSecond: 30,
}

describe('AiPriceTablePanel', () => {
  test('initial load failure shows only the error state', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ error: '价目表不可用' }, 503)))
    const wrapper = mount(AiPriceTablePanel)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('价目表不可用')
    expect(wrapper.text()).not.toContain('暂无价目表版本')
  })

  test('lists versions with status labels and role-appropriate actions', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json([ACTIVE, DRAFT, RETIRED])))
    const wrapper = mount(AiPriceTablePanel)
    await flushPromises()

    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(3)
    expect(rows[0].text()).toContain('生效中')
    expect(rows[1].text()).toContain('草稿')
    expect(rows[2].text()).toContain('已退役')

    // 激活/删除只对 draft 出现——active 在用、retired 要留着复现存量 Run 的账
    expect(rows[0].find('[data-action="activate-version"]').exists()).toBe(false)
    expect(rows[0].find('[data-action="delete-draft"]').exists()).toBe(false)
    expect(rows[1].find('[data-action="activate-version"]').exists()).toBe(true)
    expect(rows[1].find('[data-action="delete-draft"]').exists()).toBe(true)
    expect(rows[2].find('[data-action="activate-version"]').exists()).toBe(false)
  })

  test('active version prices are read-only; draft prices are editable', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([ACTIVE, DRAFT]))
      .mockResolvedValueOnce(json({ ...ACTIVE, models: [QWEN_ROW] }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiPriceTablePanel)
    await flushPromises()

    await wrapper.findAll('tbody tr')[0].get('[data-action="view-models"]').trigger('click')
    await flushPromises()

    // 生效中的单价只读，且不给保存按钮
    expect(wrapper.get('input[name="modelId-0"]').attributes('readonly')).toBeDefined()
    expect(wrapper.find('[data-action="save-models"]').exists()).toBe(false)
    expect(wrapper.find('[data-action="add-row"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('已冻结')
  })

  test('editing a draft PUTs numeric prices, not the strings v-model produces', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([ACTIVE, DRAFT]))
      .mockResolvedValueOnce(json({ ...DRAFT, models: [QWEN_ROW] }))
      .mockResolvedValueOnce(json({ ...DRAFT, models: [{ ...QWEN_ROW, centsPer1kInputTokens: 7 }] }))
      .mockResolvedValueOnce(json({ ...DRAFT, models: [{ ...QWEN_ROW, centsPer1kInputTokens: 7 }] }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiPriceTablePanel)
    await flushPromises()

    await wrapper.findAll('tbody tr')[1].get('[data-action="view-models"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('input[name="modelId-0"]').attributes('readonly')).toBeUndefined()
    await wrapper.get('input[name="input-0"]').setValue('7')
    await wrapper.get('[data-action="save-models"]').trigger('click')
    await flushPromises()

    const put = fetchMock.mock.calls.find((call) => call[1]?.method === 'PUT')
    if (!put) throw new Error('expected a PUT to /models')
    const body = JSON.parse(put[1].body)
    // number 输入经 v-model 是字符串；必须转成数字，否则后端收到 "7"
    expect(body.models[0].centsPer1kInputTokens).toBe(7)
    expect(typeof body.models[0].centsPer1kInputTokens).toBe('number')
  })

  test('rows missing required identity fields are blocked before any request', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([ACTIVE, DRAFT]))
      .mockResolvedValueOnce(json({ ...DRAFT, models: [QWEN_ROW] }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiPriceTablePanel)
    await flushPromises()

    await wrapper.findAll('tbody tr')[1].get('[data-action="view-models"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-action="add-row"]').trigger('click')
    await wrapper.get('[data-action="save-models"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('.error-state.compact').text()).toContain('不能为空')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  test('copy-active creates a draft from the active version and opens it for editing', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([ACTIVE]))
      .mockResolvedValueOnce(json(DRAFT, 201))
      .mockResolvedValueOnce(json([ACTIVE, DRAFT]))
      .mockResolvedValueOnce(json({ ...DRAFT, models: [QWEN_ROW] }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiPriceTablePanel)
    await flushPromises()

    await wrapper.get('[data-action="copy-active"]').trigger('click')
    await wrapper.get('input[name="label"]').setValue('v2')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const post = fetchMock.mock.calls.find((call) => call[1]?.method === 'POST')
    if (!post) throw new Error('expected a POST creating the draft')
    expect(JSON.parse(post[1].body)).toMatchObject({ label: 'v2', copyFromVersionId: 'pt-1' })
    // 复制完直接打开新 draft 的单价面板
    expect(wrapper.text()).toContain('可改')
  })

  test('activation warns that the current version is retired, not deleted', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([ACTIVE, DRAFT]))
      .mockResolvedValueOnce(json({ ...DRAFT, status: 'active' }))
      .mockResolvedValueOnce(json([{ ...DRAFT, status: 'active' }, { ...ACTIVE, status: 'retired' }]))
    vi.stubGlobal('fetch', fetchMock)
    const confirmSpy = vi.fn((_message?: string) => true)
    vi.stubGlobal('confirm', confirmSpy)
    const wrapper = mount(AiPriceTablePanel)
    await flushPromises()

    await wrapper.findAll('tbody tr')[1].get('[data-action="activate-version"]').trigger('click')
    await flushPromises()

    expect(String(confirmSpy.mock.calls[0][0])).toContain('已退役')
    const post = fetchMock.mock.calls.find((call) => call[1]?.method === 'POST')
    if (!post) throw new Error('expected a POST to activate')
    expect(post[0]).toContain('/api/admin/ai/price-tables/pt-2/activate')
  })

  test('a 409 from the backend is surfaced verbatim', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([ACTIVE, DRAFT]))
      .mockResolvedValueOnce(json({ error: '只有 draft 版本可改单价；请复制成新 draft 后修改' }, 409))
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('confirm', vi.fn(() => true))
    const wrapper = mount(AiPriceTablePanel)
    await flushPromises()

    await wrapper.findAll('tbody tr')[1].get('[data-action="activate-version"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('只有 draft 版本可改单价')
  })
})
