// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import MerchantKybCard from './MerchantKybCard.vue'

function stubKybFetch(): ReturnType<typeof vi.fn> {
  const spy = vi.fn().mockImplementation(async (url: string) => {
    const data = url.endsWith('/merchant-profile')
      ? {
          organizationId: 'org-1', legalName: '草场商贸', unifiedSocialCreditCode: '91310000TEST',
          businessType: 'company', legalPersonName: '张三', legalPersonIdNumberMasked: '****5678',
          registeredCapitalCents: 10000, establishmentDate: '2020-01-01',
          businessAddress: '{"province":"上海市","city":"上海市","district":"静安区","address":"南京西路 1 号"}',
          contactPhone: '13800000000', contactEmail: null, status: 'rejected',
          submittedAt: null, reviewedAt: null, reviewNote: '证件照片模糊', createdAt: null,
        }
      : url.endsWith('/merchant-attachments')
        ? []
        : url.endsWith('/withdrawal-accounts')
          ? [{
              id: 'account-1', organizationId: 'org-1', accountType: 'bank_card', accountName: '草场商贸',
              accountNumberMasked: '****7890', bankName: '招商银行', branchName: null,
              isDefault: false, status: 'approved', submittedAt: null, reviewedAt: null,
              reviewNote: null, createdAt: null,
            }, {
              id: 'account-2', organizationId: 'org-1', accountType: 'alipay', accountName: '草场商贸',
              accountNumberMasked: '****4321', bankName: null, branchName: null,
              isDefault: false, status: 'pending', submittedAt: null, reviewedAt: null,
              reviewNote: null, createdAt: null,
            }]
          : url.endsWith('/stores')
            ? [{ id: 'store-1', name: '静安门店' }]
            : url.endsWith('/stores/store-1/profile')
              ? {
                  storeId: 'store-1', address: '{"address":"南京西路 1 号"}', phone: '13800000000',
                  businessHours: null, description: null, status: 'draft', reviewNote: null, createdAt: null,
                }
              : null
    return {
      ok: true, headers: { get: () => 'application/json' },
      json: async () => ({ success: true, data }),
    }
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

function stubPendingKybFetch(): ReturnType<typeof vi.fn> {
  const spy = stubKybFetch()
  const implementation = spy.getMockImplementation()!
  spy.mockImplementation(async (...args: Parameters<typeof fetch>) => {
    const response = await implementation(...args) as Response
    const body = await response.json() as { success: boolean; data: unknown }
    if (String(args[0]).endsWith('/merchant-profile') && body.data) {
      body.data = { ...(body.data as Record<string, unknown>), status: 'pending' }
    }
    if (String(args[0]).endsWith('/merchant-attachments')) {
      body.data = [{
        id: 'attachment-1', organizationId: 'org-1', attachmentType: 'business_license',
        mediaReferenceId: 'media-1', mimeType: 'image/jpeg', sizeBytes: 2048, uploadedAt: null,
      }]
    }
    return { ...response, json: async () => body } as Response
  })
  return spy
}

function successResponse(data: unknown): Response {
  return {
    ok: true,
    headers: { get: () => 'application/json' },
    json: async () => ({ success: true, data }),
  } as unknown as Response
}

enableAutoUnmount(afterEach)

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('MerchantKybCard 契约展示', () => {
  test('切换组织后忽略前一组织的迟到响应', async () => {
    let resolveFirstProfile!: (response: unknown) => void
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/org-1/merchant-profile')) {
        return new Promise((resolve) => { resolveFirstProfile = resolve })
      }
      const data = url.includes('/org-2/merchant-profile')
        ? { legalName: '组织二商家', status: 'draft', businessAddress: null }
        : []
      return {
        ok: true, headers: { get: () => 'application/json' },
        json: async () => ({ success: true, data }),
      }
    }))

    const wrapper = mount(MerchantKybCard, { props: { orgId: 'org-1' } })
    await flushPromises()
    await wrapper.setProps({ orgId: 'org-2' })
    await flushPromises()
    expect((wrapper.find('input[placeholder="请输入企业名称"]').element as HTMLInputElement).value)
      .toBe('组织二商家')

    resolveFirstProfile({
      ok: true, headers: { get: () => 'application/json' },
      json: async () => ({
        success: true,
        data: { legalName: '组织一商家', status: 'draft', businessAddress: null },
      }),
    })
    await flushPromises()

    expect((wrapper.find('input[placeholder="请输入企业名称"]').element as HTMLInputElement).value)
      .toBe('组织二商家')
  })

  test('切换组织后忽略前一组织的保存响应', async () => {
    let resolveFirstSave!: (response: unknown) => void
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.includes('/org-1/merchant-profile') && init?.method === 'PUT') {
        return new Promise((resolve) => { resolveFirstSave = resolve })
      }
      const data = url.includes('/merchant-profile')
        ? {
            legalName: url.includes('/org-1/') ? '组织一商家' : '组织二商家',
            status: 'draft', businessAddress: null,
          }
        : []
      return {
        ok: true, headers: { get: () => 'application/json' },
        json: async () => ({ success: true, data }),
      }
    }))

    const wrapper = mount(MerchantKybCard, { props: { orgId: 'org-1' } })
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '保存资料')!.trigger('click')
    await wrapper.setProps({ orgId: 'org-2' })
    await flushPromises()
    expect((wrapper.find('input[placeholder="请输入企业名称"]').element as HTMLInputElement).value)
      .toBe('组织二商家')

    resolveFirstSave({
      ok: true, headers: { get: () => 'application/json' },
      json: async () => ({
        success: true,
        data: { legalName: '组织一保存结果', status: 'approved', businessAddress: null },
      }),
    })
    await flushPromises()

    expect((wrapper.find('input[placeholder="请输入企业名称"]').element as HTMLInputElement).value)
      .toBe('组织二商家')
    expect(wrapper.findAll('button').find((button) => button.text() === '保存资料')?.attributes('disabled'))
      .toBeUndefined()
  })

  test('初始化读取失败时展示后端错误且不产生未处理拒绝', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => {
      if (url.endsWith('/merchant-profile')) {
        return {
          ok: false, status: 403, headers: { get: () => 'application/json' },
          json: async () => ({ success: false, error: '无权访问该组织' }),
        }
      }
      return {
        ok: true, headers: { get: () => 'application/json' },
        json: async () => ({ success: true, data: [] }),
      }
    }))

    const wrapper = mount(MerchantKybCard, { props: { orgId: 'org-1' } })
    await flushPromises()

    expect(wrapper.text()).toContain('无权访问该组织')
    expect(wrapper.findAll('button').find((button) => button.text() === '保存资料')?.attributes('disabled'))
      .not.toBeUndefined()
    expect(wrapper.findAll('.attachment-upload input').every((input) => input.attributes('disabled') !== undefined))
      .toBe(true)
  })

  test('只展示身份证掩码，驳回后允许重新保存和提交', async () => {
    stubKybFetch()
    const wrapper = mount(MerchantKybCard, { props: { orgId: 'org-1' } })
    await flushPromises()

    expect(wrapper.text()).toContain('已保存证件：****5678')
    expect(wrapper.text()).not.toContain('310101199002025678')
    const submit = wrapper.findAll('button').find((button) => button.text() === '提交审核')
    expect(submit?.attributes('disabled')).toBeUndefined()
  })

  test('收款账户展示服务端掩码字段', async () => {
    stubKybFetch()
    const wrapper = mount(MerchantKybCard, { props: { orgId: 'org-1' } })
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text() === '收款账户')!.trigger('click')

    expect(wrapper.text()).toContain('****7890')
    expect(wrapper.text()).not.toContain('undefined')
    expect(wrapper.findAll('button').some((button) => button.text() === '提交审核')).toBe(true)
  })

  test('商家资料进入审核后禁用附件增删', async () => {
    stubPendingKybFetch()
    const wrapper = mount(MerchantKybCard, { props: { orgId: 'org-1' } })
    await flushPromises()

    expect(wrapper.findAll('.attachment-item button')).toHaveLength(1)
    expect(wrapper.findAll('.attachment-item button').every((button) => button.attributes('disabled') !== undefined))
      .toBe(true)
    expect(wrapper.findAll('.attachment-upload input').every((input) => input.attributes('disabled') !== undefined))
      .toBe(true)
  })

  test('主体 KYB 通过后仍允许维护权限补充证照', async () => {
    const spy = stubKybFetch()
    const implementation = spy.getMockImplementation()!
    spy.mockImplementation(async (...args: Parameters<typeof fetch>) => {
      const response = await implementation(...args) as Response
      const body = await response.json() as { success: boolean; data: unknown }
      if (String(args[0]).endsWith('/merchant-profile') && body.data) {
        body.data = { ...(body.data as Record<string, unknown>), status: 'approved' }
      }
      if (String(args[0]).endsWith('/merchant-attachments')) {
        body.data = [{
          id: 'attachment-business', organizationId: 'org-1', attachmentType: 'business_license',
          mediaReferenceId: 'media-business', mimeType: 'image/jpeg', sizeBytes: 2048, uploadedAt: null,
        }, {
          id: 'attachment-industry', organizationId: 'org-1', attachmentType: 'industry_license',
          mediaReferenceId: 'media-industry', mimeType: 'image/jpeg', sizeBytes: 2048, uploadedAt: null,
        }]
      }
      return { ...response, json: async () => body } as Response
    })

    const wrapper = mount(MerchantKybCard, { props: { orgId: 'org-1' } })
    await flushPromises()

    const uploadByLabel = new Map(wrapper.findAll('.attachment-upload label').map((label) => [
      label.text().trim(), label.find('input'),
    ]))
    expect(uploadByLabel.get('营业执照')?.attributes('disabled')).not.toBeUndefined()
    expect(uploadByLabel.get('行业许可证')?.attributes('disabled')).toBeUndefined()
    expect(uploadByLabel.get('财务资质')?.attributes('disabled')).toBeUndefined()

    const deleteButtons = wrapper.findAll('.attachment-item button')
    expect(deleteButtons[0].attributes('disabled')).not.toBeUndefined()
    expect(deleteButtons[1].attributes('disabled')).toBeUndefined()
  })

  test('门店草稿可保存并提交审核', async () => {
    const spy = stubKybFetch()
    const wrapper = mount(MerchantKybCard, { props: { orgId: 'org-1' } })
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text() === '门店资料')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('保存资料')
    const submit = wrapper.findAll('button').find((button) => button.text() === '提交审核')
    expect(submit?.attributes('disabled')).toBeUndefined()
    await submit!.trigger('click')
    await flushPromises()

    const request = spy.mock.calls.find(([url, init]) =>
      String(url).endsWith('/stores/store-1/profile/submit') && (init as RequestInit)?.method === 'POST')
    expect(request).toBeDefined()
  })

  test('切换到无资料门店时清空上一门店表单并禁用保存', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => {
      const data = url.endsWith('/merchant-profile')
        ? null
        : url.endsWith('/merchant-attachments') || url.endsWith('/withdrawal-accounts')
          ? []
          : url.endsWith('/stores')
            ? [{ id: 'store-1', name: '一号店' }, { id: 'store-2', name: '二号店' }]
            : url.endsWith('/stores/store-1/profile')
              ? {
                  storeId: 'store-1', address: JSON.stringify({ address: '旧门店地址' }),
                  phone: '13800000000', businessHours: null, description: '旧门店描述',
                  status: 'active', createdAt: null,
                }
              : null
      return {
        ok: true, headers: { get: () => 'application/json' },
        json: async () => ({ success: true, data }),
      }
    }))

    const wrapper = mount(MerchantKybCard, { props: { orgId: 'org-1' } })
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '门店资料')!.trigger('click')
    await flushPromises()
    await wrapper.find('select').setValue('store-2')
    await flushPromises()

    expect((wrapper.find('input[placeholder="详细地址"]').element as HTMLInputElement).value).toBe('')
    expect(wrapper.findAll('button').find((button) => button.text() === '保存资料')?.attributes('disabled'))
      .not.toBeUndefined()
  })

  test('A→B→A 后忽略第一次 A 门店读取的迟到响应', async () => {
    let resolveFirstStoreA!: (response: Response) => void
    let storeAReads = 0
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.endsWith('/merchant-profile')) return successResponse(null)
      if (url.endsWith('/merchant-attachments') || url.endsWith('/withdrawal-accounts')) {
        return successResponse([])
      }
      if (url.endsWith('/stores')) {
        return successResponse([{ id: 'store-a', name: 'A 店' }, { id: 'store-b', name: 'B 店' }])
      }
      if (url.endsWith('/stores/store-a/profile') && !init?.method) {
        storeAReads += 1
        if (storeAReads === 1) {
          return new Promise<Response>((resolve) => { resolveFirstStoreA = resolve })
        }
        return successResponse({
          storeId: 'store-a', address: JSON.stringify({ address: 'A 店最新地址' }),
          phone: null, description: null, status: 'draft', createdAt: null,
        })
      }
      if (url.endsWith('/stores/store-b/profile')) {
        return successResponse({
          storeId: 'store-b', address: JSON.stringify({ address: 'B 店地址' }),
          phone: null, description: null, status: 'active', createdAt: null,
        })
      }
      return successResponse(null)
    }))

    const wrapper = mount(MerchantKybCard, { props: { orgId: 'org-1' } })
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '门店资料')!.trigger('click')
    await wrapper.find('select').setValue('store-b')
    await flushPromises()
    await wrapper.find('select').setValue('store-a')
    await flushPromises()
    expect((wrapper.find('input[placeholder="详细地址"]').element as HTMLInputElement).value)
      .toBe('A 店最新地址')

    resolveFirstStoreA(successResponse({
      storeId: 'store-a', address: JSON.stringify({ address: 'A 店过期地址' }),
      phone: null, description: null, status: 'active', createdAt: null,
    }))
    await flushPromises()

    expect((wrapper.find('input[placeholder="详细地址"]').element as HTMLInputElement).value)
      .toBe('A 店最新地址')
  })

  test('A→B→A 后忽略第一次 A 门店保存的迟到响应', async () => {
    let resolveFirstStoreSave!: (response: Response) => void
    let storeAReads = 0
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.endsWith('/merchant-profile')) return successResponse(null)
      if (url.endsWith('/merchant-attachments') || url.endsWith('/withdrawal-accounts')) {
        return successResponse([])
      }
      if (url.endsWith('/stores')) {
        return successResponse([{ id: 'store-a', name: 'A 店' }, { id: 'store-b', name: 'B 店' }])
      }
      if (url.endsWith('/stores/store-a/profile') && init?.method === 'POST') {
        return new Promise<Response>((resolve) => { resolveFirstStoreSave = resolve })
      }
      if (url.endsWith('/stores/store-a/profile')) {
        storeAReads += 1
        return successResponse({
          storeId: 'store-a',
          address: JSON.stringify({ address: storeAReads === 1 ? 'A 店初始地址' : 'A 店重载地址' }),
          phone: null, description: null, status: 'draft', createdAt: null,
        })
      }
      if (url.endsWith('/stores/store-b/profile')) {
        return successResponse({
          storeId: 'store-b', address: JSON.stringify({ address: 'B 店地址' }),
          phone: null, description: null, status: 'draft', createdAt: null,
        })
      }
      return successResponse(null)
    }))

    const wrapper = mount(MerchantKybCard, { props: { orgId: 'org-1' } })
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '门店资料')!.trigger('click')
    await wrapper.findAll('button').find((button) => button.text() === '保存资料')!.trigger('click')
    await wrapper.find('select').setValue('store-b')
    await flushPromises()
    await wrapper.find('select').setValue('store-a')
    await flushPromises()
    expect((wrapper.find('input[placeholder="详细地址"]').element as HTMLInputElement).value)
      .toBe('A 店重载地址')
    expect(wrapper.find('.store-status').text()).toContain('草稿')

    resolveFirstStoreSave(successResponse({
      storeId: 'store-a', address: JSON.stringify({ address: 'A 店过期保存结果' }),
      phone: null, description: null, status: 'inactive', createdAt: null,
    }))
    await flushPromises()

    expect((wrapper.find('input[placeholder="详细地址"]').element as HTMLInputElement).value)
      .toBe('A 店重载地址')
    expect(wrapper.find('.store-status').text()).toContain('草稿')
  })
})
