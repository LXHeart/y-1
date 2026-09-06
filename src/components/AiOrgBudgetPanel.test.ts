// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import AiOrgBudgetPanel from './AiOrgBudgetPanel.vue'
import { useAuthStore } from '../stores/auth'
import type { AiOrgBudget } from '../types/grassland'

enableAutoUnmount(afterEach)
afterEach(() => {
  useAuthStore().currentUser = null
  vi.unstubAllGlobals()
})

const unlimited: AiOrgBudget = {
  configured: false,
  version: 0,
  maxTokensPerRun: null,
  maxTokensDaily: null,
  maxTokensMonthly: null,
  maxCentsPerRun: null,
  maxCentsDaily: null,
  maxCentsMonthly: null,
  usage: { measured: false, dailyTokens: null, dailyCents: null, monthlyTokens: null, monthlyCents: null },
  overCurrentUsage: false,
  updatedAt: null,
}

function response(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(status >= 400
    ? { success: false, error: data }
    : { success: true, data }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('AiOrgBudgetPanel', () => {
  test('未配置时六项保持空白并如实显示暂无计量', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response(unlimited)))
    const wrapper = mount(AiOrgBudgetPanel, { props: { organizationId: 'org-1' } })
    await flushPromises()

    expect(wrapper.text()).toContain('未设置，当前不限')
    expect(wrapper.text()).toContain('暂无计量')
    for (const input of wrapper.findAll('input')) expect((input.element as HTMLInputElement).value).toBe('')
  })

  test('渲染真实日/月用量，保存时空输入映射为 null 并携带 version', async () => {
    const configured: AiOrgBudget = {
      ...unlimited,
      configured: true,
      version: 3,
      maxTokensPerRun: 100,
      maxTokensDaily: 1000,
      maxTokensMonthly: 10000,
      maxCentsDaily: 250,
      usage: { measured: true, dailyTokens: 123, dailyCents: 45, monthlyTokens: 4567, monthlyCents: 890 },
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(configured))
      .mockResolvedValueOnce(response({ ...configured, version: 4, maxTokensPerRun: null }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiOrgBudgetPanel, { props: { organizationId: 'org-1' } })
    await flushPromises()

    expect(wrapper.text()).toContain('123')
    expect(wrapper.text()).toContain('4,567')
    expect(wrapper.text()).toContain('45 分')
    await wrapper.get('input[name="maxTokensPerRun"]').setValue('')
    await wrapper.get('button.primary').trigger('click')
    await flushPromises()

    const request = fetchMock.mock.calls[1][1] as RequestInit
    expect(request.method).toBe('PUT')
    expect(JSON.parse(request.body as string)).toMatchObject({
      expectedVersion: 3,
      maxTokensPerRun: null,
      maxTokensDaily: 1000,
      maxTokensMonthly: 10000,
      maxCentsPerRun: null,
      maxCentsDaily: 250,
      maxCentsMonthly: null,
    })
    expect(wrapper.text()).toContain('AI 预算已保存')
  })

  test('409 后进入冲突态，重新载入取得其他管理员的新版本', async () => {
    const v1: AiOrgBudget = { ...unlimited, configured: true, version: 1, maxTokensDaily: 100 }
    const v2: AiOrgBudget = { ...v1, version: 2, maxTokensDaily: 200 }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(v1))
      .mockResolvedValueOnce(response('AI 预算已被其他管理员修改，请重新载入', 409))
      .mockResolvedValueOnce(response(v2))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiOrgBudgetPanel, { props: { organizationId: 'org-1' } })
    await flushPromises()

    await wrapper.get('button.primary').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('重新载入后重试')

    await wrapper.get('.conflict-actions button').trigger('click')
    await flushPromises()
    expect((wrapper.get('input[name="maxTokensDaily"]').element as HTMLInputElement).value).toBe('200')
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })
})

describe('AiOrgBudgetPanel 账号/组织双边界（任务书 #82 C82-04）', () => {
  const userA = { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', email: 'a@qa.invalid', displayName: '甲', role: 'user' }
  const userB = { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', email: 'b@qa.invalid', displayName: '乙', role: 'user' }

  test('O1→O2：换组织先清空，O1 的迟到响应不回填（E03）', async () => {
    const v1: AiOrgBudget = { ...unlimited, configured: true, version: 1, maxTokensDaily: 111 }
    const v2: AiOrgBudget = { ...unlimited, configured: true, version: 7, maxTokensDaily: 222 }
    let resolveO1!: (data: Response) => void
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => new Promise<Response>((resolve) => { resolveO1 = resolve }))
      .mockImplementationOnce(async () => response(v2))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiOrgBudgetPanel, { props: { organizationId: 'org-1' } })

    await wrapper.setProps({ organizationId: 'org-2' }) // O1 挂起中切 O2：先清空再装载 O2
    await flushPromises()
    expect((wrapper.get('input[name="maxTokensDaily"]').element as HTMLInputElement).value).toBe('222')

    resolveO1(response(v1)) // O1 迟到
    await flushPromises()
    expect((wrapper.get('input[name="maxTokensDaily"]').element as HTMLInputElement).value).toBe('222') // 不回填 O1
  })

  test('账号切换：预算/表单/冲突态清空；A 的保存成功迟到不 apply、不写 notice（E03）', async () => {
    const auth = useAuthStore()
    auth.currentUser = userA
    const v1: AiOrgBudget = { ...unlimited, configured: true, version: 3, maxTokensDaily: 100 }
    let resolveSave!: (data: Response) => void
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === 'PUT') return new Promise<Response>((resolve) => { resolveSave = resolve })
      return response(v1)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiOrgBudgetPanel, { props: { organizationId: 'org-1' } })
    await flushPromises()
    expect((wrapper.get('input[name="maxTokensDaily"]').element as HTMLInputElement).value).toBe('100')

    await wrapper.get('button.primary').trigger('click') // A 的 PUT 挂起
    auth.currentUser = userB // 换号：同步清空
    resolveSave(response({ ...v1, version: 4, maxTokensDaily: 999 }))
    await flushPromises()

    expect(wrapper.text()).not.toContain('AI 预算已保存') // A 的 notice 不落 B
    const inputs = wrapper.findAll('input')
    expect(inputs.every((i) => (i.element as HTMLInputElement).value === '')).toBe(true) // A 的预算不 apply
  })
})
