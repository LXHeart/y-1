// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import OrganizationBrandCard from './OrganizationBrandCard.vue'

/**
 * 组织品牌资料卡片（#32 规格测试清单 9）组件契约测试：
 * 表单回填 / 保存 payload（含 expectedVersion）/ Logo 三步上传后 mediaId 随保存提交 /
 * 移除 Logo 后 null 清空提交 + 预览清空（含下拉 14 项断言）/ 409 提示 + 自动重拉 /
 * member 只读 / 独立渲染不依赖 MerchantKybCard。
 *
 * 照 MerchantKybCard.test.ts 的 stubFetch 模式：字段名 typecheck 抓不到，靠断言实际请求体锁死。
 */

const FILLED_PROFILE = {
  organizationId: 'org-1',
  brandName: '草场咖啡',
  brandLogoMediaReferenceId: 'media-1',
  logoUrl: 'http://storage.test/signed/logo',
  description: '一杯好咖啡',
  industry: 'catering',
  version: 3,
}

function success(data: unknown): Response {
  return {
    ok: true, status: 200,
    headers: { get: () => 'application/json' },
    json: async () => ({ success: true, data }),
  } as unknown as Response
}

function failure(status: number, error: string): Response {
  return {
    ok: false, status,
    headers: { get: () => 'application/json' },
    json: async () => ({ success: false, error }),
  } as unknown as Response
}

function emptyProfile(version = 0): Record<string, unknown> {
  return {
    organizationId: 'org-1', brandName: null, brandLogoMediaReferenceId: null,
    logoUrl: null, description: null, industry: null, version,
  }
}

enableAutoUnmount(afterEach)

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

function putBodyOf(fetchMock: ReturnType<typeof vi.fn>): Record<string, unknown> {
  const puts = fetchMock.mock.calls.filter(([url, init]) =>
    String(url).endsWith('/brand-profile') && (init as RequestInit | undefined)?.method === 'PUT')
  expect(puts.length).toBeGreaterThan(0)
  // 取最后一次 PUT——多次保存的用例断言的是最新一次的 payload。
  const call = puts[puts.length - 1]
  return JSON.parse(String((call![1] as RequestInit).body)) as Record<string, unknown>
}

describe('OrganizationBrandCard 契约', () => {
  test('随组织加载回填表单与 Logo 预览（独立渲染，不触碰 KYB 端点）', async () => {
    const spy = vi.fn().mockImplementation(async (url: string) => {
      expect(url).toBe('/api/organizations/org-1/brand-profile')
      return success(FILLED_PROFILE)
    })
    vi.stubGlobal('fetch', spy)

    const wrapper = mount(OrganizationBrandCard, { props: { orgId: 'org-1', role: 'owner' } })
    await flushPromises()

    expect(wrapper.text()).toContain('品牌资料')
    expect((wrapper.find('input[placeholder="请输入品牌名称"]').element as HTMLInputElement).value)
      .toBe('草场咖啡')
    expect((wrapper.find('select').element as HTMLSelectElement).value).toBe('catering')
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('一杯好咖啡')
    expect((wrapper.find('img[alt="品牌 Logo 预览"]').element as HTMLImageElement).src)
      .toBe('http://storage.test/signed/logo')
    // 独立卡片：不依赖也不调用 MerchantKybCard 的任何组织级端点。
    expect(spy).toHaveBeenCalledTimes(1)
  })

  test('编辑后保存：PUT 整份 payload 含 expectedVersion，成功后 version 刷新', async () => {
    let stored = { ...FILLED_PROFILE }
    const spy = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if ((init as RequestInit | undefined)?.method === 'PUT') {
        const body = JSON.parse(String(init!.body)) as Record<string, unknown>
        stored = { ...stored, ...body, version: stored.version + 1 } as typeof stored
        return success(stored)
      }
      return success({ ...FILLED_PROFILE, version: stored.version })
    })
    vi.stubGlobal('fetch', spy)

    const wrapper = mount(OrganizationBrandCard, { props: { orgId: 'org-1', role: 'owner' } })
    await flushPromises()

    await wrapper.find('input[placeholder="请输入品牌名称"]').setValue('草场咖啡二店')
    await wrapper.find('select').setValue('retail')
    await wrapper.find('textarea').setValue('新简介')
    await wrapper.findAll('button').find((button) => button.text() === '保存资料')!.trigger('click')
    await flushPromises()

    const url = spy.mock.calls.find(([u, init]) =>
      String(u).endsWith('/brand-profile') && (init as RequestInit | undefined)?.method === 'PUT')![0]
    expect(String(url)).toBe('/api/organizations/org-1/brand-profile')
    expect(putBodyOf(spy)).toEqual({
      brandName: '草场咖啡二店',
      brandLogoMediaReferenceId: 'media-1',
      description: '新简介',
      industry: 'retail',
      expectedVersion: 3,
    })
    expect(wrapper.text()).toContain('品牌资料已保存')

    // 成功保存后 version 已刷新：再次保存回传的是新版本号（乐观锁不误伤下一次编辑）。
    await wrapper.findAll('button').find((button) => button.text() === '保存资料')!.trigger('click')
    await flushPromises()
    expect(putBodyOf(spy).expectedVersion).toBe(4)
  })

  test('Logo 三步上传后 mediaId 进入表单并随保存提交', async () => {
    const createObjectUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:logo-preview')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    const spy = vi.fn().mockImplementation(async (url: string) => {
      if (url === '/api/organizations/org-1/brand-profile') return success(FILLED_PROFILE)
      if (url === '/api/organizations/org-1/brand-profile/logo/upload-ticket') {
        return success({
          id: 'media-7', objectKey: 'media/brand_logo/media-7',
          uploadUrl: 'http://127.0.0.1:9002/grassland/tmp/media-7',
          method: 'PUT', headers: { 'Content-Type': 'image/png' }, expiresAt: null,
        })
      }
      if (url === 'http://127.0.0.1:9002/grassland/tmp/media-7') {
        return { ok: true, status: 200 } as unknown as Response
      }
      if (url === '/api/media/media-7/confirm') {
        return success({ id: 'media-7', status: 'active' })
      }
      throw new Error(`unexpected url: ${url}`)
    })
    vi.stubGlobal('fetch', spy)

    const wrapper = mount(OrganizationBrandCard, { props: { orgId: 'org-1', role: 'owner' } })
    await flushPromises()

    const fileInput = wrapper.find('input[type="file"]')
    const file = new File([new Uint8Array([1, 2, 3])], 'logo.png', { type: 'image/png' })
    Object.defineProperty(fileInput.element, 'files', { value: [file] })
    await fileInput.trigger('change')
    await flushPromises()

    // 三步链路：代开票据 → 直传 presigned → confirm。
    expect(spy.mock.calls.map(([url]) => String(url))).toEqual([
      '/api/organizations/org-1/brand-profile',
      '/api/organizations/org-1/brand-profile/logo/upload-ticket',
      'http://127.0.0.1:9002/grassland/tmp/media-7',
      '/api/media/media-7/confirm',
    ])
    // 本地预览切到新上传的图。
    expect((wrapper.find('img[alt="品牌 Logo 预览"]').element as HTMLImageElement).src)
      .toBe('blob:logo-preview')
    expect(createObjectUrl).toHaveBeenCalled()

    await wrapper.findAll('button').find((button) => button.text() === '保存资料')!.trigger('click')
    await flushPromises()
    expect(putBodyOf(spy).brandLogoMediaReferenceId).toBe('media-7')
  })

  test('移除 Logo 后保存：PUT 提交 null 清空引用且预览清空；经营分类下拉共 14 项', async () => {
    let stored = { ...FILLED_PROFILE }
    const spy = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if ((init as RequestInit | undefined)?.method === 'PUT') {
        const body = JSON.parse(String(init!.body)) as Record<string, unknown>
        // 服务端落库后 logo 清空：回包 logoUrl=null。
        stored = { ...stored, ...body, logoUrl: null, version: stored.version + 1 } as unknown as typeof stored
        return success(stored)
      }
      return success({ ...stored })
    })
    vi.stubGlobal('fetch', spy)

    const wrapper = mount(OrganizationBrandCard, { props: { orgId: 'org-1', role: 'owner' } })
    await flushPromises()

    // 13 值经营分类（镜像 identity Industry 枚举）+「未设置」空选项 = 14。
    expect(wrapper.find('select').findAll('option')).toHaveLength(14)

    expect(wrapper.find('img[alt="品牌 Logo 预览"]').exists()).toBe(true)
    await wrapper.findAll('button').find((button) => button.text() === '移除 Logo')!.trigger('click')
    // 移除即时清空预览（D8 清空语义在表单层生效，不等保存）。
    expect(wrapper.find('img[alt="品牌 Logo 预览"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('暂无 Logo')

    await wrapper.findAll('button').find((button) => button.text() === '保存资料')!.trigger('click')
    await flushPromises()

    expect(putBodyOf(spy).brandLogoMediaReferenceId).toBeNull()
    // 保存回包（服务端 logoUrl=null）后预览仍为空。
    expect(wrapper.find('img[alt="品牌 Logo 预览"]').exists()).toBe(false)
  })

  test('409 乐观锁冲突：展示后端冲突文案并自动重拉最新资料回填', async () => {
    let serverProfile = { ...FILLED_PROFILE }
    let conflict = false
    const spy = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if ((init as RequestInit | undefined)?.method === 'PUT') {
        conflict = true
        return failure(409, '品牌资料已变更，请刷新后重试')
      }
      // 冲突后服务器上是「他人改过」的最新资料（version 已前进）。
      if (conflict) return success({ ...serverProfile, brandName: '他人改过的名字', version: 4 })
      return success({ ...serverProfile })
    })
    vi.stubGlobal('fetch', spy)

    const wrapper = mount(OrganizationBrandCard, { props: { orgId: 'org-1', role: 'owner' } })
    await flushPromises()

    await wrapper.find('input[placeholder="请输入品牌名称"]').setValue('我的并发编辑')
    await wrapper.findAll('button').find((button) => button.text() === '保存资料')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('品牌资料已变更，请刷新后重试')
    expect(wrapper.text()).not.toContain('品牌资料已保存')
    // 自动重拉：GET 至少两次（初始 + 冲突后）。
    const gets = spy.mock.calls.filter(([url, init]) =>
      String(url).endsWith('/brand-profile') && !(init as RequestInit | undefined)?.method)
    expect(gets.length).toBeGreaterThanOrEqual(2)
    // 表单被服务器最新资料覆盖（version 也已刷新，用户从最新态重试）。
    expect((wrapper.find('input[placeholder="请输入品牌名称"]').element as HTMLInputElement).value)
      .toBe('他人改过的名字')
  })

  test('member 只读：无编辑控件与保存钮，展示资料文本', async () => {
    const spy = vi.fn().mockImplementation(async () => success(FILLED_PROFILE))
    vi.stubGlobal('fetch', spy)

    const wrapper = mount(OrganizationBrandCard, { props: { orgId: 'org-1', role: 'member' } })
    await flushPromises()

    expect(wrapper.findAll('input')).toHaveLength(0)
    expect(wrapper.findAll('select')).toHaveLength(0)
    expect(wrapper.findAll('textarea')).toHaveLength(0)
    expect(wrapper.findAll('button').some((button) => button.text() === '保存资料')).toBe(false)
    expect(wrapper.text()).toContain('草场咖啡')
    expect(wrapper.text()).toContain('餐饮')
    expect(wrapper.text()).toContain('一杯好咖啡')
    expect((wrapper.find('img[alt="品牌 Logo"]').element as HTMLImageElement).src)
      .toBe('http://storage.test/signed/logo')
    expect(spy).toHaveBeenCalledTimes(1)
  })

  test('无资料组织回填空表单，首存 expectedVersion=0', async () => {
    let saved = false
    const spy = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if ((init as RequestInit | undefined)?.method === 'PUT') {
        saved = true
        return success({ ...emptyProfile(1), brandName: '新品牌' })
      }
      return success(saved ? { ...emptyProfile(1), brandName: '新品牌' } : emptyProfile())
    })
    vi.stubGlobal('fetch', spy)

    const wrapper = mount(OrganizationBrandCard, { props: { orgId: 'org-1', role: 'owner' } })
    await flushPromises()

    expect((wrapper.find('input[placeholder="请输入品牌名称"]').element as HTMLInputElement).value).toBe('')
    await wrapper.find('input[placeholder="请输入品牌名称"]').setValue('新品牌')
    await wrapper.findAll('button').find((button) => button.text() === '保存资料')!.trigger('click')
    await flushPromises()
    expect(putBodyOf(spy).expectedVersion).toBe(0)
  })

  test('切换组织后忽略前一组织的迟到响应（防串扰）', async () => {
    let resolveFirstProfile!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/org-1/brand-profile')) {
        return new Promise((resolve) => { resolveFirstProfile = resolve })
      }
      return success({ ...FILLED_PROFILE, brandName: '组织二品牌', organizationId: 'org-2', version: 1 })
    }))

    const wrapper = mount(OrganizationBrandCard, { props: { orgId: 'org-1', role: 'owner' } })
    await flushPromises()
    await wrapper.setProps({ orgId: 'org-2' })
    await flushPromises()
    expect((wrapper.find('input[placeholder="请输入品牌名称"]').element as HTMLInputElement).value)
      .toBe('组织二品牌')

    resolveFirstProfile(success(FILLED_PROFILE))
    await flushPromises()
    expect((wrapper.find('input[placeholder="请输入品牌名称"]').element as HTMLInputElement).value)
      .toBe('组织二品牌')
  })
})
