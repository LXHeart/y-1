// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import PersonalAiBudgetCard from './PersonalAiBudgetCard.vue'
import { useAuthStore } from '../stores/auth'
import { GrasslandHttpError } from '../composables/grassland-http'
import type { AiOrgBudget } from '../types/grassland'

enableAutoUnmount(afterEach)
afterEach(() => {
  useAuthStore().currentUser = null
  vi.unstubAllGlobals()
})

/**
 * 个人 AI 预算卡（GL-P3-AI-001 登记项，Slice 31 前端）。此前零测试。
 * 锁定：挂载即拉取、已配置预算回填表单与用量四格、超限警示、
 * 保存走 expectedVersion 乐观锁、层级校验（单次≤每日≤每月）与 409 冲突文案。
 */
const unlimited: AiOrgBudget = {
  configured: false,
  version: 0,
  maxTokensPerRun: null, maxTokensDaily: null, maxTokensMonthly: null,
  maxCentsPerRun: null, maxCentsDaily: null, maxCentsMonthly: null,
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

function budgetBody(overrides: Partial<AiOrgBudget> = {}): AiOrgBudget {
  return {
    ...unlimited,
    configured: true,
    version: 3,
    maxTokensDaily: 10000,
    maxCentsMonthly: 500,
    usage: { measured: true, dailyTokens: 1200, dailyCents: 30, monthlyTokens: 9000, monthlyCents: 210 },
    ...overrides,
  }
}

describe('PersonalAiBudgetCard', () => {
  test('挂载即拉取 /api/ai/me/budget；未配置时空白 + 暂无计量', async () => {
    const fetchMock = vi.fn(async () => response(unlimited))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(PersonalAiBudgetCard)
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('/api/ai/me/budget', expect.anything())
    expect(wrapper.text()).toContain('暂无计量')
    expect(wrapper.find('.form-error').exists()).toBe(false)
    expect(wrapper.classes()).toContain('gl-budget-card')
    expect(wrapper.find('.gl-budget-limits').findAll('input')).toHaveLength(6)
    expect(wrapper.find('.gl-budget-usage').exists()).toBe(true)
  })

  test('已配置预算回填表单、展示四格用量与超限警示', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response(budgetBody({ overCurrentUsage: true }))))

    const wrapper = mount(PersonalAiBudgetCard)
    await flushPromises()

    expect((wrapper.find('input[placeholder="不限"]').element as HTMLInputElement)).toBeTruthy()
    const inputs = wrapper.findAll('input')
    const dailyTokens = inputs.find((i) => (i.element as HTMLInputElement).value === '10000')
    expect(dailyTokens).toBeTruthy()
    expect(wrapper.text()).toContain('1,200')   // 今日调用量
    expect(wrapper.text()).toContain('30 分')   // 今日消费（分为单位展示）
    expect(wrapper.text()).toContain('已超设定上限')
  })

  test('保存校验层级（单次≤每日≤每月）不合法不打网络', async () => {
    const fetchMock = vi.fn(async () => response(unlimited))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(PersonalAiBudgetCard)
    await flushPromises()
    fetchMock.mockClear()

    const tokenInputs = wrapper.findAll('input')
    // 前三个输入是 Token 三档（单次/每日/每月）：单次 500 > 每日 100 违序
    await tokenInputs[0].setValue('500')
    await tokenInputs[1].setValue('100')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('Token上限必须满足单次 ≤ 每日 ≤ 每月')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  test('保存携带 expectedVersion；409 冲突展示刷新提示', async () => {
    let version = 3
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/ai/me/budget' && init?.method === 'PUT') {
        const body = JSON.parse(String(init.body)) as { expectedVersion: number }
        expect(body.expectedVersion).toBe(version)
        if (body.expectedVersion === 3) {
          return response('预算已被修改', 409)
        }
        return response(budgetBody({ version: 4, maxTokensDaily: 20000 }))
      }
      return response(budgetBody())
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(PersonalAiBudgetCard)
    await flushPromises()

    const inputs = wrapper.findAll('input')
    await inputs[1].setValue('20000')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('预算已被修改（可能在其他设备），请刷新后重试')

    // 模拟他端已改：刷新（GET 返回 version 4）后再保存成功
    version = 4
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (url === '/api/ai/me/budget' && init?.method === 'PUT') {
        return response(budgetBody({ version: 5 }))
      }
      return response(budgetBody({ version: 4 }))
    })
    await wrapper.find('header button').trigger('click')
    await flushPromises()
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('个人 AI 预算已保存')
  })

  test('非 409 保存失败展示原始错误信息', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === 'PUT') return response('积分服务不可用', 503)
      return response(budgetBody())
    }))
    const wrapper = mount(PersonalAiBudgetCard)
    await flushPromises()

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('积分服务不可用')
  })
})

describe('PersonalAiBudgetCard 账号边界（任务书 #82 C82-04）', () => {
  const userA = { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', email: 'a@qa.invalid', displayName: '甲', role: 'user' }
  const userB = { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', email: 'b@qa.invalid', displayName: '乙', role: 'user' }

  test('加载中换号：A 的预算迟到不回填 B 的表单（E03）', async () => {
    const auth = useAuthStore()
    let resolveLoad!: (data: Response) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => { resolveLoad = resolve })))
    auth.currentUser = userA
    const wrapper = mount(PersonalAiBudgetCard)

    auth.currentUser = userB // A 的 GET 挂起中换号
    resolveLoad(response(budgetBody({ maxTokensDaily: 10000 })))
    await flushPromises()

    const inputs = wrapper.findAll('input')
    expect(inputs.every((i) => (i.element as HTMLInputElement).value === '')).toBe(true) // A 的 10000 不回填
    expect(wrapper.find('.form-error').exists()).toBe(false) // 无 A 的错误
  })

  test('保存中换号：A 的保存成功迟到不 applyBudget、不写 notice、expectedVersion 不带 A 版本（E03）', async () => {
    const auth = useAuthStore()
    let resolveSave!: (data: Response) => void
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === 'PUT') return new Promise<Response>((resolve) => { resolveSave = resolve })
      return response(budgetBody({ version: 3 }))
    })
    vi.stubGlobal('fetch', fetchMock)
    auth.currentUser = userA
    const wrapper = mount(PersonalAiBudgetCard)
    await flushPromises()

    await wrapper.find('form').trigger('submit') // A 的 PUT 挂起（body 已按 A 的 version 3 发出）
    auth.currentUser = userB
    resolveSave(response(budgetBody({ version: 4, maxTokensDaily: 20000 })))
    await flushPromises()

    expect(wrapper.text()).not.toContain('个人 AI 预算已保存') // A 的成功 notice 不落 B
    const inputs = wrapper.findAll('input')
    expect(inputs.every((i) => (i.element as HTMLInputElement).value === '')).toBe(true) // A 的预算/表单不 apply

    // B 不带 A 的 version 提交：换号后 budget 已清空 → version 0（而非 A 的 3/4）
    const putBodies: number[] = []
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (init?.method === 'PUT') {
        putBodies.push((JSON.parse(String(init.body)) as { expectedVersion: number }).expectedVersion)
        return response(budgetBody({ version: 9 }))
      }
      return response(budgetBody({ version: 4 }))
    })
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(putBodies).toEqual([0])
  })
})

// GrasslandHttpError 仅作类型引用（409 分支由 composable 的错误信封抛出）
void GrasslandHttpError
