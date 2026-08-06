// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import AdminView from './AdminView.vue'

enableAutoUnmount(afterEach)

afterEach(() => {
  vi.unstubAllGlobals()
})

function response(data: unknown, envelope = true): Response {
  return {
    ok: true,
    headers: { get: () => 'application/json' },
    json: async () => envelope ? { success: true, data } : data,
  } as unknown as Response
}

describe('AdminView KYB 审核', () => {
  test('第三个管理 tab 懒挂载 AI 模型面板且不显示 KYB 队列', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url === '/api/admin/users') return response({ users: [] }, true)
      if (url === '/api/admin/kyb-requests') return response([])
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AdminView, {
      global: {
        stubs: {
          Teleport: true,
          AiPlatformModelsPanel: { template: '<div data-testid="ai-models-panel">AI 模型配置</div>' },
        },
      },
    })
    await flushPromises()
    const tabs = wrapper.findAll('[role="tab"]')

    expect(tabs.map((tab) => tab.text().trim())).toEqual(['用户与积分', 'KYB 审核', '推荐官认证', '财务对账', 'AI 模型'])
    expect(wrapper.find('[data-testid="ai-models-panel"]').exists()).toBe(false)

    await tabs[4].trigger('click')

    expect(wrapper.find('[data-testid="ai-models-panel"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('待审核申请')
    expect(tabs[4].attributes('aria-selected')).toBe('true')
  })

  test('加载待审队列并携带拒绝备注提交，成功后移出队列', async () => {
    const request = {
      id: 'request-1', organizationId: 'org-1', requesterAccountId: 'account-1',
      verificationType: 'store_profile', targetId: 'store-1', materials: null,
      status: 'pending', reviewerAccountId: null, reviewNote: null,
      reviewDeadline: '2099-01-02T00:00:00Z', createdAt: '2099-01-01T00:00:00Z',
    }
    const detail = {
      request,
      subject: {
        type: 'store_profile', storeId: 'store-1', address: '{"address":"南京西路 8 号"}',
        phone: '13800000000', businessHours: null, description: '旗舰店', status: 'pending',
      },
      attachments: [],
    }
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url === '/api/admin/users') return response({ users: [] }, true)
      if (url === '/api/admin/kyb-requests') return response([request])
      if (url === '/api/admin/kyb-requests/request-1' && !init?.method) return response(detail)
      if (url === '/api/admin/kyb-requests/request-1/reject' && init?.method === 'POST') {
        return response({ ...request, status: 'rejected', reviewNote: '地址无法核验' })
      }
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AdminView, { global: { stubs: { Teleport: true } } })
    await flushPromises()
    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    expect(wrapper.text()).toContain('门店资料')
    expect(wrapper.text()).toContain('org-1')

    await wrapper.find('.reject-btn').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('南京西路 8 号')
    expect(wrapper.text()).toContain('13800000000')
    await wrapper.find('textarea').setValue('地址无法核验')
    await wrapper.find('.btn-confirm.danger').trigger('click')
    await flushPromises()

    const reviewCall = fetchMock.mock.calls.find(([url]) =>
      url === '/api/admin/kyb-requests/request-1/reject')
    expect(JSON.parse((reviewCall?.[1] as RequestInit).body as string)).toEqual({ note: '地址无法核验' })
    expect(wrapper.text()).toContain('暂无待审核申请')
  })

  test('拒绝时必须填写原因，校验失败不发送审核请求', async () => {
    const request = {
      id: 'request-2', organizationId: 'org-2', requesterAccountId: 'account-2',
      verificationType: 'merchant_profile', targetId: 'org-2', materials: null,
      status: 'pending', reviewerAccountId: null, reviewNote: null,
      reviewDeadline: null, createdAt: null,
    }
    const detail = {
      request,
      subject: {
        type: 'merchant_profile', organizationId: 'org-2', legalName: '草场商贸',
        unifiedSocialCreditCode: '91310000TEST000001', businessType: 'company',
        legalPersonName: '张三', legalPersonIdNumberMasked: '****1234',
        registeredCapitalCents: null, establishmentDate: null, businessAddress: null,
        contactPhone: null, contactEmail: null, status: 'pending',
      },
      attachments: [],
    }
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url === '/api/admin/users') return response({ users: [] }, true)
      if (url === '/api/admin/kyb-requests') return response([request])
      return response(detail)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AdminView, { global: { stubs: { Teleport: true } } })
    await flushPromises()
    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    await wrapper.find('.reject-btn').trigger('click')
    await flushPromises()
    await wrapper.find('.btn-confirm.danger').trigger('click')

    expect(wrapper.text()).toContain('请填写拒绝原因')
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  test('审核详情加载失败时禁止盲审', async () => {
    const request = {
      id: 'request-3', organizationId: 'org-3', requesterAccountId: 'account-3',
      verificationType: 'merchant_profile', targetId: 'org-3', materials: '["attachment-1"]',
      status: 'pending', reviewerAccountId: null, reviewNote: null,
      reviewDeadline: null, createdAt: null,
    }
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url === '/api/admin/users') return response({ users: [] }, true)
      if (url === '/api/admin/kyb-requests') return response([request])
      if (url === '/api/admin/kyb-requests/request-3') {
        return {
          ok: false, status: 503, headers: { get: () => 'application/json' },
          json: async () => ({ success: false, error: '审核材料暂不可用' }),
        } as unknown as Response
      }
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AdminView, { global: { stubs: { Teleport: true } } })
    await flushPromises()
    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    await wrapper.find('.approve-btn').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('审核材料暂不可用')
    expect(wrapper.find('.btn-confirm').attributes('disabled')).toBeDefined()
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })
})

describe('AdminView 用户管理（identity 信封）', () => {
  test('解析 {success,data:{users}} 信封并渲染用户列表 + 余额', async () => {
    const users = [
      { id: 'u-1', email: 'a@example.com', displayName: '用户A', role: 'user', status: 'active',
        createdAt: '2026-01-01T00:00:00Z', balance: 5, totalEarned: 10, totalSpent: 5 },
      { id: 'u-2', email: 'b@example.com', displayName: null, role: 'user', status: 'active',
        createdAt: '2026-01-02T00:00:00Z', balance: 0, totalEarned: 0, totalSpent: 0 },
    ]
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url === '/api/admin/users') return response({ users })
      if (url === '/api/admin/kyb-requests') return response([])
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AdminView, { global: { stubs: { Teleport: true } } })
    await flushPromises()

    expect(wrapper.text()).toContain('a@example.com')
    expect(wrapper.text()).toContain('用户A')
    expect(wrapper.text()).toContain('b@example.com')
    // 第一行的「调整积分」按钮存在
    expect(wrapper.findAll('.adjust-btn').length).toBeGreaterThanOrEqual(1)
  })

  test('调整积分发送 {userId,amount,note} 且成功后重载列表', async () => {
    const users = [
      { id: 'u-1', email: 'a@example.com', displayName: null, role: 'user', status: 'active',
        createdAt: '2026-01-01T00:00:00Z', balance: 3, totalEarned: 3, totalSpent: 0 },
    ]
    let usersCallCount = 0
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url === '/api/admin/users') {
        usersCallCount++
        return response({ users })
      }
      if (url === '/api/admin/adjust-credits') {
        const body = JSON.parse(init?.body as string)
        expect(body.userId).toBe('u-1')
        expect(body.amount).toBe(-2)
        expect(body.note).toBe('扣减测试')
        return response({ adjusted: true })
      }
      if (url === '/api/admin/kyb-requests') return response([])
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AdminView, { global: { stubs: { Teleport: true } } })
    await flushPromises()

    await wrapper.find('.adjust-btn').trigger('click')
    await flushPromises()
    await (wrapper.find('input[type="number"]')).setValue(-2)
    await wrapper.find('input[placeholder*="手动充值"]').setValue('扣减测试')
    await wrapper.find('.btn-confirm').trigger('click')
    await flushPromises()

    // 成功后重载用户列表（第二次 GET /api/admin/users）
    expect(usersCallCount).toBe(2)
    // 模态关闭
    expect(wrapper.find('.modal-overlay').exists()).toBe(false)
  })
})
