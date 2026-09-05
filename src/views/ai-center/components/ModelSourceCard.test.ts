// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import ModelSourceCard from './ModelSourceCard.vue'
import { useModelSource } from '../../../composables/useModelSource'

/**
 * 模型来源开关卡（任务书 #78 卡 C，接卡 B 契约）：
 * GET 回显；platform→own 二次确认（定死警示文案）；own→platform 不拦；
 * 409 冲突重载后提示重试；非法值 400 原样透出。
 */
enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

/** 共享单例态在用例间会残留：每例先复位再挂载。 */
async function resetSharedState(): Promise<void> {
  useModelSource().reset()
  await Promise.resolve()
}

describe('ModelSourceCard（任务书 #78 卡 C）', () => {
  test('挂载拉取总开关并回显；platform 为默认态', async () => {
    await resetSharedState()
    const fetchMock = vi.fn(async (url: string) => url === '/api/ai/preferences'
      ? json({ success: true, data: { items: [], modelSource: 'platform', masterVersion: 0 } })
      : json({}))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ModelSourceCard)
    await flushPromises()

    expect(fetchMock.mock.calls.some(([url]) => url === '/api/ai/preferences')).toBe(true)
    const radios = wrapper.findAll('input[name="model-source"]')
    expect((radios[0].element as HTMLInputElement).checked).toBe(true)
    expect((radios[1].element as HTMLInputElement).checked).toBe(false)
  })

  test('platform→own 二次确认含定死警示文案；确认后 PUT model-source 且乐观锁版本回传', async () => {
    await resetSharedState()
    const confirmMock = vi.fn((_message?: string) => true)
    vi.stubGlobal('confirm', confirmMock)
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/ai/preferences') {
        return json({ success: true, data: { items: [], modelSource: 'platform', masterVersion: 2 } })
      }
      if (url === '/api/ai/preferences/model-source' && init?.method === 'PUT') {
        return json({ success: true, data: { modelSource: 'own', masterVersion: 3 } })
      }
      return json({})
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ModelSourceCard)
    await flushPromises()

    await wrapper.findAll('input[name="model-source"]')[1].setValue(true)
    await flushPromises()

    expect(confirmMock).toHaveBeenCalledTimes(1)
    const message = String(confirmMock.mock.calls[0][0])
    expect(message).toContain('平台内容安全深检、内容修复等免费能力将不再提供')
    expect(message).toContain('未配置密钥的能力将不可用')
    const putCall = fetchMock.mock.calls.find(([url]) => url === '/api/ai/preferences/model-source')
    expect(JSON.parse(String(putCall![1]!.body))).toEqual({ modelSource: 'own', expectedVersion: 2 })

    // 共享态随动：own 生效（AiGovernanceSection 据此切 own 分支）
    const { modelSource } = useModelSource()
    expect(modelSource.value).toBe('own')
  })

  test('取消二次确认不发请求', async () => {
    await resetSharedState()
    vi.stubGlobal('confirm', vi.fn(() => false))
    const fetchMock = vi.fn(async (url: string) => url === '/api/ai/preferences'
      ? json({ success: true, data: { items: [], modelSource: 'platform', masterVersion: 0 } })
      : json({}))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ModelSourceCard)
    await flushPromises()
    fetchMock.mockClear()

    await wrapper.findAll('input[name="model-source"]')[1].setValue(true)
    await flushPromises()

    expect(fetchMock).not.toHaveBeenCalled()
    expect((wrapper.get('input[value="platform"]').element as HTMLInputElement).checked).toBe(true)
    expect((wrapper.get('input[value="own"]').element as HTMLInputElement).checked).toBe(false)
  })

  test('加载失败就地报错并禁用切换，刷新成功后恢复', async () => {
    await resetSharedState()
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ error: '模型来源服务暂不可用' }, 503))
      .mockResolvedValueOnce(json({ data: { modelSource: 'own', masterVersion: 4 } }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ModelSourceCard)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('模型来源服务暂不可用')
    expect(wrapper.get('input[value="own"]').attributes('disabled')).toBeDefined()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect((wrapper.get('input[value="own"]').element as HTMLInputElement).checked).toBe(true)
    expect(wrapper.get('input[value="own"]').attributes('disabled')).toBeUndefined()
  })

  test('保存失败恢复单选框，不伪装为已切到自有模型', async () => {
    await resetSharedState()
    vi.stubGlobal('confirm', vi.fn(() => true))
    vi.stubGlobal('fetch', vi.fn(async (url: string) => url === '/api/ai/preferences'
      ? json({ data: { modelSource: 'platform', masterVersion: 0 } })
      : json({ error: '保存失败' }, 503)))
    const wrapper = mount(ModelSourceCard)
    await flushPromises()
    await wrapper.get('input[value="own"]').setValue(true)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('保存失败')
    expect((wrapper.get('input[value="platform"]').element as HTMLInputElement).checked).toBe(true)
    expect((wrapper.get('input[value="own"]').element as HTMLInputElement).checked).toBe(false)
  })

  test('own→platform 不拦（省钱方向不设确认）', async () => {
    await resetSharedState()
    const { modelSource, masterVersion, loaded } = useModelSource()
    modelSource.value = 'own'
    masterVersion.value = 1
    loaded.value = true
    const confirmMock = vi.fn(() => false)
    vi.stubGlobal('confirm', confirmMock)
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/ai/preferences/model-source' && init?.method === 'PUT') {
        return json({ success: true, data: { modelSource: 'platform', masterVersion: 2 } })
      }
      return json({ success: true, data: { items: [], modelSource: 'own', masterVersion: 1 } })
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ModelSourceCard)
    await flushPromises()
    confirmMock.mockClear()

    await wrapper.findAll('input[name="model-source"]')[0].setValue(true)
    await flushPromises()

    expect(confirmMock).not.toHaveBeenCalled()
    expect(modelSource.value).toBe('platform')
  })

  test('409 冲突：重载真实状态并提示重试', async () => {
    await resetSharedState()
    vi.stubGlobal('confirm', vi.fn(() => true))
    let version = 1
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/ai/preferences') {
        return json({ success: true, data: { items: [], modelSource: 'platform', masterVersion: version } })
      }
      if (url === '/api/ai/preferences/model-source' && init?.method === 'PUT') {
        version += 1
        return json({ error: '模型来源开关已被其他会话修改，请重新加载后再试' }, 409)
      }
      return json({})
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ModelSourceCard)
    await flushPromises()

    await wrapper.findAll('input[name="model-source"]')[1].setValue(true)
    await flushPromises()

    expect(wrapper.get('.msc-error').text()).toContain('已被其他会话修改')
  })
})
