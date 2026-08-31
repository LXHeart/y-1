// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import AiPlatformModelsPanel from './AiPlatformModelsPanel.vue'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

/** 按 HTTP method 取那次写请求。下标定位会随「选凭据触发拉模型」这类新增 GET 而漂移。 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function mutatingCall(mock: { mock: { calls: any[][] } }, method: string): any[] {
  return mock.mock.calls.find((call) => call[1]?.method === method) as any[]
}

/** 挂载会并发发两个请求：模型列表 + 凭据列表。凭据行供表单下拉与 provider/baseUrl 带出。 */
const CREDENTIAL = {
  id: 'cred-1', name: 'qwen-dashscope', provider: 'qwen', baseUrl: 'https://dashscope.example/v1',
  hasKey: true, maskedHint: 'sk-****cdef', enabled: true, version: 1,
  createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:00:00Z',
}

/** 任务书 #58：挂载会并发发三个请求（模型/凭据/受信端点）。 */
const ORIGINS = [
  {
    id: 'origin-1', origin: 'https://dashscope.aliyuncs.com:443', label: '内置默认·Qwen/DashScope',
    enabled: true, version: 0, updatedAt: '2026-08-30T00:00:00Z', createdAt: '2026-08-30T00:00:00Z',
  },
]

/** 弹窗类组件的 mount 必带 Teleport stub，否则内容渲染到 body、findAll 全空（项目实测坑）。 */
function mountPanel() {
  return mount(AiPlatformModelsPanel, { global: { stubs: { Teleport: true } } })
}

describe('AiPlatformModelsPanel', () => {
  test('initial load failure shows only the error state', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ error: '模型控制面不可用' }, 503)))
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('模型控制面不可用')
    expect(wrapper.text()).not.toContain('暂无平台模型配置')
  })

  test('capability dropdown lists only control-plane capabilities', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json([CREDENTIAL]))
      .mockResolvedValueOnce(json(ORIGINS)))
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.get('button[data-action="add-model"]').trigger('click')

    const values = wrapper.get('select[name="capability"]').findAll('option').map((o) => o.element.value)
    expect(values).toEqual(['text', 'voice', 'retrieval', 'image_edit', 'content_safety', 'image_generation'])
    // video 仍走 MiniMax 专用异步链、控制面不解析，不得出现
    expect(values).not.toContain('video_generation')
  })

  test('provider and baseUrl are not form fields, only a summary of the credential', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json([CREDENTIAL]))
      .mockResolvedValueOnce(json(ORIGINS))
      .mockResolvedValueOnce(json([])))
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.get('button[data-action="add-model"]').trigger('click')

    // 它们由凭据唯一决定，不该作为字段（连只读框也不留）
    expect(wrapper.find('input[name="provider"]').exists()).toBe(false)
    expect(wrapper.find('input[name="baseUrl"]').exists()).toBe(false)
    expect(wrapper.find('.credential-summary').exists()).toBe(false)

    await wrapper.get('select[name="credentialId"]').setValue('cred-1')
    await flushPromises()

    // 选中后以一行摘要复述目标地址，供确认
    const summary = wrapper.get('.credential-summary').text()
    expect(summary).toContain('qwen')
    expect(summary).toContain('https://dashscope.example/v1')
  })

  test('model dropdown is fed by the credential ticked set, not a live upstream call', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json([CREDENTIAL]))
      .mockResolvedValueOnce(json(ORIGINS))
      .mockResolvedValueOnce(json([{ id: 'qwen-max' }, { id: 'qwen-plus' }]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('button[data-action="add-model"]').trigger('click')
    // 选凭据前无从得知可用模型，只能手填
    expect(wrapper.find('input[name="model"]').exists()).toBe(true)

    await wrapper.get('select[name="credentialId"]').setValue('cred-1')
    await flushPromises()

    // 读的是勾选集端点，不是实时 /models——本表单不该依赖上游可达性
    expect(fetchMock.mock.calls[3][0]).toContain('/api/admin/ai/credentials/cred-1/selected-models')
    expect(fetchMock.mock.calls[3][0]).not.toMatch(/\/credentials\/cred-1\/models$/)
    const options = wrapper.get('select[name="modelChoice"]').findAll('option')
      .map((o) => o.element.value).filter((v) => v !== '')
    expect(options).toEqual(['qwen-max', 'qwen-plus', '__manual__'])
    // 手动出口未选中时不渲染手填框
    expect(wrapper.find('input[name="model"]').exists()).toBe(false)
  })

  test('a credential with no ticked models points the admin at the credentials panel', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json([CREDENTIAL]))
      .mockResolvedValueOnce(json(ORIGINS))
      .mockResolvedValueOnce(json([])))
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('button[data-action="add-model"]').trigger('click')
    await wrapper.get('select[name="credentialId"]').setValue('cred-1')
    await flushPromises()

    const input = wrapper.get('input[name="model"]')
    expect(input.attributes('placeholder')).toContain('获取模型')
  })

  test('upstream model listing failure degrades to manual entry', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json([CREDENTIAL]))
      .mockResolvedValueOnce(json(ORIGINS))
      .mockResolvedValueOnce(json({ error: '加密基建未配置（CRYPTO_KEK_BASE64）' }, 503))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('button[data-action="add-model"]').trigger('click')
    await wrapper.get('select[name="credentialId"]').setValue('cred-1')
    await flushPromises()

    // 上游不可达不得阻断表单：仍可手填模型名，且不冒充提交态错误
    const input = wrapper.get('input[name="model"]')
    expect(input.attributes('placeholder')).toContain('CRYPTO_KEK_BASE64')
    expect(wrapper.find('.error-state.compact').exists()).toBe(false)
  })

  test('switching credential clears a model name from the previous upstream', async () => {
    const second = { ...CREDENTIAL, id: 'cred-2', name: 'openai', provider: 'openai-compatible' }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json([CREDENTIAL, second]))
      .mockResolvedValueOnce(json(ORIGINS))
      .mockResolvedValueOnce(json([{ id: 'qwen-max' }]))
      .mockResolvedValueOnce(json([{ id: 'gpt-4' }]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('button[data-action="add-model"]').trigger('click')
    await wrapper.get('select[name="credentialId"]').setValue('cred-1')
    await flushPromises()
    await wrapper.get('select[name="modelChoice"]').setValue('qwen-max')

    await wrapper.get('select[name="credentialId"]').setValue('cred-2')
    await flushPromises()

    // qwen-max 在新上游不存在，留着会提交一个对方不认的名字
    expect((wrapper.get('select[name="modelChoice"]').element as HTMLSelectElement).value).toBe('')
  })

  test('submitting without a credential is blocked before any request', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json([CREDENTIAL]))
      .mockResolvedValueOnce(json(ORIGINS))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('button[data-action="add-model"]').trigger('click')
    await wrapper.get('input[name="model"]').setValue('qwen-plus')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('请选择凭据')
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  test('creates primary/backup model configuration with health and concurrency', async () => {
    const created = {
      id: 'model-1', capability: 'text', modelRole: 'backup', provider: 'qwen', model: 'qwen-plus',
      baseUrl: 'https://dashscope.example/v1', maxConcurrency: 8, healthStatus: 'degraded',
      enabled: true, version: 1, createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:00:00Z',
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json([CREDENTIAL]))
      .mockResolvedValueOnce(json(ORIGINS))
      // 选凭据触发拉上游模型；这里回空 → 模型字段降级为手填 input（本用例正是驱动 input）
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json(created, 201))
      .mockResolvedValueOnce(json([created]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('button[data-action="add-model"]').trigger('click')
    await wrapper.get('select[name="modelRole"]').setValue('backup')
    await wrapper.get('select[name="credentialId"]').setValue('cred-1')
    await wrapper.get('input[name="model"]').setValue('qwen-plus')
    await wrapper.get('input[name="maxConcurrency"]').setValue('8')
    await wrapper.get('select[name="healthStatus"]').setValue('degraded')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    // 按 method 定位而非硬编码下标：挂载与选凭据都会发 GET，下标会随出站请求增减而漂移
    const post = mutatingCall(fetchMock, 'POST')
    expect(post).toBeDefined()
    const body = JSON.parse(post[1].body)
    expect(body).toMatchObject({
      capability: 'text', modelRole: 'backup', credentialId: 'cred-1',
      maxConcurrency: 8, healthStatus: 'degraded',
    })
    // provider/baseUrl 不再由前端下发——后端从凭据带出，避免手抄地址与隐式建空壳凭据
    expect(body).not.toHaveProperty('provider')
    expect(body).not.toHaveProperty('baseUrl')
    expect(wrapper.text()).toContain('qwen-plus')
    expect(wrapper.text()).toContain('备用')
  })

  test('editing a row with no matching credential leaves the picker empty', async () => {
    // 旧表单隐式建的空壳凭据、或凭据已停用 → 反查不到。此时必须留空逼用户显式选，
    // 而不是静默沿用一个不存在的凭据（那会让保存悄悄落到别的地址上）。
    const orphan = {
      id: 'model-2', capability: 'text', modelRole: 'primary', provider: 'qwen', model: 'qwen-plus',
      baseUrl: 'https://qwen.invalid/v1', maxConcurrency: null, healthStatus: 'healthy',
      enabled: true, version: 1, createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:00:00Z',
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([orphan]))
      .mockResolvedValueOnce(json([CREDENTIAL]))
      .mockResolvedValueOnce(json(ORIGINS))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('button[data-action="edit-model"]').trigger('click')
    expect((wrapper.get('select[name="credentialId"]').element as HTMLSelectElement).value).toBe('')

    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('请选择凭据')
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  test('the show-disabled toggle refetches with includeDisabled and marks those rows', async () => {
    const live = {
      id: 'model-live', capability: 'text', modelRole: 'primary', provider: 'qwen', model: 'qwen-max',
      baseUrl: 'https://dashscope.example/v1', maxConcurrency: null, healthStatus: 'healthy',
      enabled: true, version: 2, createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:00:00Z',
    }
    const stale = { ...live, id: 'model-stale', model: 'qwen-plus', enabled: false, version: 1 }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([live]))
      .mockResolvedValueOnce(json([CREDENTIAL]))
      .mockResolvedValueOnce(json(ORIGINS))
      .mockResolvedValueOnce(json([live, stale]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountPanel()
    await flushPromises()

    // 默认不带 includeDisabled
    expect(fetchMock.mock.calls[0][0]).not.toContain('includeDisabled')
    expect(wrapper.findAll('.models-list tbody tr')).toHaveLength(1)

    await wrapper.get('input[name="includeDisabled"]').setValue(true)
    await flushPromises()

    expect(fetchMock.mock.calls[3][0]).toContain('includeDisabled=true')
    const rows = wrapper.findAll('.models-list tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[1].classes()).toContain('row-disabled')
    expect(rows[1].text()).toContain('已停用')

    // 停用行只给恢复/删除；修订会打两段路径、只命中生效行，故不提供
    expect(rows[1].find('[data-action="restore-model"]').exists()).toBe(true)
    expect(rows[1].find('[data-action="delete-model"]').exists()).toBe(true)
    expect(rows[1].find('[data-action="edit-model"]').exists()).toBe(false)
    expect(rows[0].find('[data-action="restore-model"]').exists()).toBe(false)
  })

  test('restore POSTs to the id route and surfaces a 409 conflict verbatim', async () => {
    const stale = {
      id: 'model-stale', capability: 'text', modelRole: 'primary', provider: 'qwen', model: 'qwen-plus',
      baseUrl: 'https://dashscope.example/v1', maxConcurrency: null, healthStatus: 'healthy',
      enabled: false, version: 1, createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:00:00Z',
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([stale]))
      .mockResolvedValueOnce(json([CREDENTIAL]))
      .mockResolvedValueOnce(json(ORIGINS))
      .mockResolvedValueOnce(json({ error: '该能力+角色已有生效配置，请先停用它再恢复此版本' }, 409))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('[data-action="restore-model"]').trigger('click')
    await flushPromises()

    expect(fetchMock.mock.calls[3][0]).toContain('/api/admin/ai/models/model-stale/restore')
    expect(fetchMock.mock.calls[3][1].method).toBe('POST')
    expect(wrapper.get('[role="alert"]').text()).toContain('请先停用它再恢复此版本')
  })

  test('delete asks for confirmation and DELETEs the id route', async () => {
    const stale = {
      id: 'model-stale', capability: 'text', modelRole: 'primary', provider: 'qwen', model: 'qwen-plus',
      baseUrl: 'https://dashscope.example/v1', maxConcurrency: null, healthStatus: 'healthy',
      enabled: false, version: 1, createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:00:00Z',
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([stale]))
      .mockResolvedValueOnce(json([CREDENTIAL]))
      .mockResolvedValueOnce(json(ORIGINS))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(json([]))
    vi.stubGlobal('fetch', fetchMock)
    const confirmSpy = vi.fn((_message?: string) => true)
    vi.stubGlobal('confirm', confirmSpy)
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('[data-action="delete-model"]').trigger('click')
    await flushPromises()

    // 不可逆操作要说清边界：审计仍在 history
    expect(confirmSpy).toHaveBeenCalled()
    expect(String(confirmSpy.mock.calls[0][0])).toContain('history')
    expect(fetchMock.mock.calls[3][0]).toContain('/api/admin/ai/models/model-stale')
    expect(fetchMock.mock.calls[3][1].method).toBe('DELETE')
  })

  test('cancelling the delete confirmation sends no request', async () => {
    const stale = {
      id: 'model-stale', capability: 'text', modelRole: 'primary', provider: 'qwen', model: 'qwen-plus',
      baseUrl: 'https://dashscope.example/v1', maxConcurrency: null, healthStatus: 'healthy',
      enabled: false, version: 1, createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:00:00Z',
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([stale]))
      .mockResolvedValueOnce(json([CREDENTIAL]))
      .mockResolvedValueOnce(json(ORIGINS))
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('confirm', vi.fn(() => false))
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('[data-action="delete-model"]').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  test('revises existing models and disables them after confirmation', async () => {
    // provider/baseUrl 与 CREDENTIAL 同源，编辑态才能反查到 cred-1（不同源的回填留空见下一个用例）
    const model = {
      id: 'model-1', capability: 'text', modelRole: 'primary', provider: 'qwen', model: 'qwen-plus',
      baseUrl: 'https://dashscope.example/v1', maxConcurrency: null, healthStatus: 'healthy',
      enabled: true, version: 2, createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:00:00Z',
    }
    const revised = { ...model, model: 'qwen-max', version: 3 }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([model]))
      .mockResolvedValueOnce(json([CREDENTIAL]))
      .mockResolvedValueOnce(json(ORIGINS))
      // 编辑态回填 credentialId 同样触发拉上游模型；回空 → 保持手填 input
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json(revised))
      .mockResolvedValueOnce(json([revised]))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(json([]))
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('confirm', vi.fn(() => true))
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('button[data-action="edit-model"]').trigger('click')
    expect(wrapper.get('select[name="capability"]').attributes('disabled')).toBeDefined()
    // 编辑态按 (provider, baseUrl) 反查凭据回填——fixture 的 model 行与 CREDENTIAL 同源
    expect((wrapper.get('select[name="credentialId"]').element as HTMLSelectElement).value).toBe('cred-1')
    await wrapper.get('input[name="model"]').setValue('qwen-max')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const put = mutatingCall(fetchMock, 'PUT')
    expect(put).toBeDefined()
    expect(JSON.parse(put[1].body)).toMatchObject({ credentialId: 'cred-1', model: 'qwen-max' })

    await wrapper.get('button[data-action="disable-model"]').trigger('click')
    await flushPromises()
    expect(mutatingCall(fetchMock, 'DELETE')).toBeDefined()
    expect(wrapper.text()).toContain('暂无平台模型配置')
  })

  // ---------- 任务书 #58：受信端点区块 + 冷启动空态引导 ----------

  test('empty model list shows the cold-start guide with configuration order', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json([CREDENTIAL]))
      .mockResolvedValueOnce(json(ORIGINS)))
    const wrapper = mountPanel()
    await flushPromises()

    const guide = wrapper.get('[data-testid="platform-models-empty-guide"]')
    expect(guide.text()).toContain('尚无平台模型配置')
    expect(guide.text()).toContain('先加受信端点')

    await wrapper.get('[data-action="dismiss-empty-guide"]').trigger('click')
    expect(wrapper.find('[data-testid="platform-models-empty-guide"]').exists()).toBe(false)
  })

  test('trusted origins are listed with status and can be created', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json([CREDENTIAL]))
      .mockResolvedValueOnce(json(ORIGINS))
      .mockResolvedValueOnce(json([{ ...ORIGINS[0], id: 'origin-2', origin: 'https://api.minimaxi.com:443', label: 'MiniMax 图像' }], 201))
      .mockResolvedValueOnce(json([
        ORIGINS[0],
        { id: 'origin-2', origin: 'https://api.minimaxi.com:443', label: 'MiniMax 图像', enabled: true, version: 0, updatedAt: '2026-08-31T00:00:00Z', createdAt: '2026-08-31T00:00:00Z' },
      ]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.text()).toContain('https://dashscope.aliyuncs.com:443')

    await wrapper.get('button[data-action="add-origin"]').trigger('click')
    await wrapper.get('input[name="origin"]').setValue('https://api.minimaxi.com')
    await wrapper.get('input[name="label"]').setValue('MiniMax 图像')
    await wrapper.get('.trusted-origins form').trigger('submit')
    await flushPromises()

    const post = mutatingCall(fetchMock, 'POST')
    expect(post).toBeDefined()
    expect(post[0]).toContain('/api/admin/ai/trusted-origins')
    expect(JSON.parse(post[1].body)).toMatchObject({ origin: 'https://api.minimaxi.com', label: 'MiniMax 图像' })
    expect(wrapper.text()).toContain('https://api.minimaxi.com:443')
  })

  test('origin save surfaces a 409 version conflict verbatim', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json([CREDENTIAL]))
      .mockResolvedValueOnce(json(ORIGINS))
      .mockResolvedValueOnce(json({ error: '该端点已被他人修改（版本冲突），请刷新后重试' }, 409))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('button[data-action="edit-origin"]').trigger('click')
    await wrapper.get('.trusted-origins form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('请刷新后重试')
  })

  test('toggling an origin PUTs the inverted enabled state with expectedVersion', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json([CREDENTIAL]))
      .mockResolvedValueOnce(json(ORIGINS))
      .mockResolvedValueOnce(json([{ ...ORIGINS[0], enabled: false, version: 1 }]))
      .mockResolvedValueOnce(json([{ ...ORIGINS[0], enabled: false, version: 1 }]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('button[data-action="toggle-origin"]').trigger('click')
    await flushPromises()

    const put = mutatingCall(fetchMock, 'PUT')
    expect(put).toBeDefined()
    expect(put[0]).toContain('/api/admin/ai/trusted-origins/origin-1')
    expect(JSON.parse(put[1].body)).toMatchObject({ enabled: false, expectedVersion: 0 })
    expect(wrapper.text()).toContain('已停用')
  })
})
