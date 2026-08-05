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
      if (url === '/api/admin/users') return response({ users: [] }, false)
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
      if (url === '/api/admin/users') return response({ users: [] }, false)
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
      if (url === '/api/admin/users') return response({ users: [] }, false)
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
