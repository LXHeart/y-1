// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import MerchantKybCard from './MerchantKybCard.vue'

function stubKybFetch(): ReturnType<typeof vi.fn> {
  const spy = vi.fn().mockImplementation(async (url: string) => {
    const data = url.endsWith('/merchant-profile')
      ? {
          organizationId: 'org-1', legalName: '草场商贸', unifiedSocialCreditCode: '91310000TEST',
          industry: 'retail', businessType: 'company', legalPersonName: '张三',
          legalPersonIdNumberMasked: '****5678',
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
  test('独立门店经理模式：只渲染门店 tab、不触碰组织级端点', async () => {
    const spy = stubKybFetch()
    const wrapper = mount(MerchantKybCard, {
      props: {
        orgId: 'org-1',
        storeOnly: true,
        stores: [{ id: 'store-1', name: '静安门店' }],
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('门店 KYB 资料')
    expect(wrapper.text()).not.toContain('商家资料')
    expect(wrapper.text()).not.toContain('收款账户')
    // 组织级端点（merchant-profile/attachments/withdrawal/listStores）不应被调用。
    const calledUrls = spy.mock.calls.map(([url]) => String(url))
    expect(calledUrls.some((url) => url.includes('/merchant-profile'))).toBe(false)
    expect(calledUrls.some((url) => url.includes('/merchant-attachments'))).toBe(false)
    expect(calledUrls.some((url) => url.includes('/withdrawal-accounts'))).toBe(false)
    expect(calledUrls.some((url) => url.endsWith('/stores') || url.includes('/stores?'))).toBe(false)
    // 注入的门店列表可用，门店资料照常加载。
    expect(wrapper.text()).toContain('静安门店')
    expect(wrapper.text()).toContain('草稿')
  })
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

  test('企业类型与行业类型分别使用下拉并按各自契约保存', async () => {
    const spy = stubKybFetch()
    const wrapper = mount(MerchantKybCard, { props: { orgId: 'org-1' } })
    await flushPromises()

    const businessType = wrapper.get('#merchant-business-type')
    expect(businessType.element.tagName).toBe('SELECT')
    expect((businessType.element as HTMLSelectElement).value).toBe('company')
    expect(businessType.findAll('option').map((option) => option.text())).toEqual([
      '请选择企业类型', '个体工商户', '个人独资企业', '合伙企业', '有限责任公司',
      '股份有限公司', '公司',
    ])

    const industry = wrapper.get('#merchant-industry')
    expect(industry.element.tagName).toBe('SELECT')
    expect((industry.element as HTMLSelectElement).value).toBe('retail')
    expect(industry.findAll('option').map((option) => option.text())).toEqual([
      '请选择行业类型', '餐饮', '零售', '美业', '教育培训', '电商',
      '医疗健康', '金融服务', '房地产', '旅游', '母婴儿童', '其他',
    ])

    await industry.setValue('catering')
    await wrapper.findAll('button').find((button) => button.text() === '保存资料')!.trigger('click')
    await flushPromises()

    const save = spy.mock.calls.find(([url, init]) =>
      String(url).endsWith('/merchant-profile') && (init as RequestInit)?.method === 'PUT')
    expect(save).toBeDefined()
    const body = JSON.parse(String((save![1] as RequestInit).body)) as Record<string, unknown>
    expect(body.businessType).toBe('company')
    expect(body.industry).toBe('catering')
  })

  test('电话、身份证号和邮箱格式错误时就地提示并阻止保存请求', async () => {
    const spy = stubKybFetch()
    const wrapper = mount(MerchantKybCard, { props: { orgId: 'org-1' } })
    await flushPromises()

    await wrapper.get('#merchant-id-number').setValue('310101199001011235')
    await wrapper.get('#merchant-contact-phone').setValue('12345')
    await wrapper.get('#merchant-contact-email').setValue('wrong@email')
    await wrapper.get('#merchant-id-number').trigger('blur')
    await wrapper.get('#merchant-contact-phone').trigger('blur')
    await wrapper.get('#merchant-contact-email').trigger('blur')

    expect(wrapper.get('#merchant-id-number-error').text()).toContain('身份证号')
    expect(wrapper.get('#merchant-contact-phone-error').text()).toContain('联系电话')
    expect(wrapper.get('#merchant-contact-email-error').text()).toContain('邮箱')
    expect(wrapper.get('#merchant-id-number').attributes('aria-invalid')).toBe('true')

    await wrapper.findAll('button').find((button) => button.text() === '保存资料')!.trigger('click')
    await flushPromises()
    expect(spy.mock.calls.some(([url, init]) =>
      String(url).endsWith('/merchant-profile') && (init as RequestInit)?.method === 'PUT')).toBe(false)

    await wrapper.get('#merchant-id-number').setValue('11010519491231002X')
    await wrapper.get('#merchant-contact-phone').setValue('13800138000')
    await wrapper.get('#merchant-contact-email').setValue('kyb@example.com')
    await wrapper.findAll('button').find((button) => button.text() === '保存资料')!.trigger('click')
    await flushPromises()
    expect(spy.mock.calls.some(([url, init]) =>
      String(url).endsWith('/merchant-profile') && (init as RequestInit)?.method === 'PUT')).toBe(true)
  })

  test('商家省市区下拉逐级联动，切换上级会清空下级并提交所选地址', async () => {
    const spy = stubKybFetch()
    const wrapper = mount(MerchantKybCard, { props: { orgId: 'org-1' } })
    await flushPromises()

    const province = wrapper.get('select[name="businessAddressProvince"]')
    const city = wrapper.get('select[name="businessAddressCity"]')
    const district = wrapper.get('select[name="businessAddressDistrict"]')
    expect((province.element as HTMLSelectElement).value).toBe('上海市')
    expect((city.element as HTMLSelectElement).value).toBe('上海市')
    expect((district.element as HTMLSelectElement).value).toBe('静安区')

    await province.setValue('广东省')
    expect((city.element as HTMLSelectElement).value).toBe('')
    expect((district.element as HTMLSelectElement).value).toBe('')
    expect(city.findAll('option').map((option) => option.text())).toContain('广州市')
    expect(district.attributes('disabled')).not.toBeUndefined()

    await city.setValue('广州市')
    expect(district.findAll('option').map((option) => option.text())).toContain('天河区')
    await district.setValue('天河区')
    await wrapper.findAll('button').find((button) => button.text() === '保存资料')!.trigger('click')
    await flushPromises()

    const save = spy.mock.calls.find(([url, init]) =>
      String(url).endsWith('/merchant-profile') && (init as RequestInit)?.method === 'PUT')
    const body = JSON.parse(String((save![1] as RequestInit).body)) as {
      businessAddress: Record<string, string>
    }
    expect(body.businessAddress).toMatchObject({ province: '广东省', city: '广州市', district: '天河区' })
  })

  test('门店地址兼容对象回填并三级联动，电话格式错误时阻止保存', async () => {
    const spy = stubKybFetch()
    const implementation = spy.getMockImplementation()!
    spy.mockImplementation(async (...args: Parameters<typeof fetch>) => {
      const response = await implementation(...args) as Response
      const body = await response.json() as { success: boolean; data: unknown }
      if (String(args[0]).endsWith('/stores/store-1/profile') && !(args[1] as RequestInit | undefined)?.method) {
        body.data = {
          ...(body.data as Record<string, unknown>),
          address: {
            province: '浙江省', city: '杭州市', district: '西湖区', address: '文三路 1 号',
          },
        }
      }
      return { ...response, json: async () => body } as Response
    })

    const wrapper = mount(MerchantKybCard, { props: { orgId: 'org-1' } })
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '门店资料')!.trigger('click')
    await flushPromises()

    const province = wrapper.get('select[name="storeAddressProvince"]')
    const city = wrapper.get('select[name="storeAddressCity"]')
    const district = wrapper.get('select[name="storeAddressDistrict"]')
    expect((province.element as HTMLSelectElement).value).toBe('浙江省')
    expect((city.element as HTMLSelectElement).value).toBe('杭州市')
    expect((district.element as HTMLSelectElement).value).toBe('西湖区')

    await province.setValue('广东省')
    expect((city.element as HTMLSelectElement).value).toBe('')
    expect((district.element as HTMLSelectElement).value).toBe('')
    await city.setValue('深圳市')
    await district.setValue('南山区')

    await wrapper.get('#store-contact-phone').setValue('12345')
    await wrapper.findAll('button').find((button) => button.text() === '保存资料')!.trigger('click')
    await flushPromises()
    expect(wrapper.get('#store-contact-phone-error').text()).toContain('联系电话')
    expect(spy.mock.calls.some(([url, init]) =>
      String(url).endsWith('/stores/store-1/profile') && (init as RequestInit)?.method === 'POST')).toBe(false)

    await wrapper.get('#store-contact-phone').setValue('0755-12345678')
    await wrapper.findAll('button').find((button) => button.text() === '保存资料')!.trigger('click')
    await flushPromises()

    const save = spy.mock.calls.find(([url, init]) =>
      String(url).endsWith('/stores/store-1/profile') && (init as RequestInit)?.method === 'POST')
    expect(save).toBeDefined()
    const savedBody = JSON.parse(String((save![1] as RequestInit).body)) as {
      address: string
      phone: string
    }
    expect(JSON.parse(savedBody.address)).toMatchObject({
      province: '广东省', city: '深圳市', district: '南山区', address: '文三路 1 号',
    })
    expect(savedBody.phone).toBe('0755-12345678')
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

  test('门店营销字段编辑→保存往返：换行拆行去重、元↔cents（任务书 #24）', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.endsWith('/merchant-profile')) return successResponse(null)
      if (url.endsWith('/merchant-attachments') || url.endsWith('/withdrawal-accounts')) {
        return successResponse([])
      }
      if (url.endsWith('/stores')) {
        return successResponse([{ id: 'store-1', name: '静安门店' }])
      }
      if (url.endsWith('/stores/store-1/profile') && init?.method === 'POST') {
        // 回显保存结果（同后端 draft 语义）。
        const body = JSON.parse(String(init.body)) as Record<string, unknown>
        return successResponse({
          storeId: 'store-1', address: body.address, phone: null, businessHours: null,
          description: null, categories: body.categories, signatureItems: body.signatureItems,
          sellingPoints: body.sellingPoints, mustEmphasize: body.mustEmphasize,
          forbiddenPhrases: body.forbiddenPhrases, allowedTags: body.allowedTags,
          brandTone: body.brandTone, priceRange: body.priceRange,
          averageSpendCents: body.averageSpendCents, visitNotes: body.visitNotes,
          status: 'draft', submittedAt: null, reviewedAt: null, reviewerAccountId: null,
          reviewNote: null, createdAt: null,
        })
      }
      if (url.endsWith('/stores/store-1/profile')) {
        return successResponse({
          storeId: 'store-1', address: '{"address":"南京西路 1 号"}', phone: '13800000000',
          businessHours: null, description: null,
          categories: ['火锅', '川菜'], signatureItems: ['招牌毛肚'], sellingPoints: [],
          mustEmphasize: ['锅底现熬'], forbiddenPhrases: ['最好吃'], allowedTags: ['#探店'],
          brandTone: '温暖亲切', priceRange: '¥30–¥80', averageSpendCents: 6500,
          visitNotes: '地铁直达', status: 'draft', submittedAt: null, reviewedAt: null,
          reviewerAccountId: null, reviewNote: null, createdAt: null,
        })
      }
      return successResponse(null)
    }))

    const wrapper = mount(MerchantKybCard, { props: { orgId: 'org-1' } })
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '门店资料')!.trigger('click')
    await flushPromises()

    // 读取映射：列表 → 换行文本；cents → 元。
    const categories = wrapper.find('textarea[placeholder="每行一项，如：火锅（选填）"]')
    expect((categories.element as HTMLTextAreaElement).value).toBe('火锅\n川菜')
    expect((wrapper.find('textarea[placeholder="每行一项（选填）"]').element as HTMLTextAreaElement).value)
      .toBe('招牌毛肚')
    expect((wrapper.find('input[placeholder="如：65（选填）"]').element as HTMLInputElement).value)
      .toBe('65')
    expect((wrapper.find('input[placeholder="如：¥30–¥80（选填）"]').element as HTMLInputElement).value)
      .toBe('¥30–¥80')

    // 编辑：换行/空白/重复需归一；人均改 88.88 元。
    await categories.setValue('火锅\n\n 川菜 \n火锅\n烧烤')
    await wrapper.find('input[placeholder="如：65（选填）"]').setValue('88.88')

    const spy = vi.mocked(fetch)
    await wrapper.findAll('button').find((button) => button.text() === '保存资料')!.trigger('click')
    await flushPromises()

    const save = spy.mock.calls.find(([url, init]) =>
      String(url).endsWith('/stores/store-1/profile') && (init as RequestInit)?.method === 'POST')
    expect(save).toBeDefined()
    const body = JSON.parse(String((save![1] as RequestInit).body)) as Record<string, unknown>
    expect(body.categories).toEqual(['火锅', '川菜', '烧烤'])
    expect(body.signatureItems).toEqual(['招牌毛肚'])
    expect(body.sellingPoints).toEqual([])
    expect(body.mustEmphasize).toEqual(['锅底现熬'])
    expect(body.forbiddenPhrases).toEqual(['最好吃'])
    expect(body.allowedTags).toEqual(['#探店'])
    expect(body.brandTone).toBe('温暖亲切')
    expect(body.averageSpendCents).toBe(8888)
    expect(body.visitNotes).toBe('地铁直达')
  })
})
