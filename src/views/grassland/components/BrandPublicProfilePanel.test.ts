// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import BrandPublicProfilePanel from './BrandPublicProfilePanel.vue'

/**
 * 品牌公开资料卡片（缺口清偿之六）：按 organizationId 自拉
 * GET /api/organizations/{id}/public-brand-profile；全空字段不渲染；logo onerror 置空降级。
 */

function jsonResponse(data: unknown, ok = true, status = 200) {
  return {
    ok, status,
    headers: { get: () => 'application/json' },
    json: async () => data,
    text: async () => JSON.stringify(data),
  }
}

const PROFILE = {
  organizationId: 'org-1',
  brandName: '草原咖啡',
  description: '社区精品咖啡烘焙',
  industry: 'catering',
  logoUrl: 'https://cdn.example.com/logo.png',
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url.includes('/api/organizations/org-1/public-brand-profile')) {
      return jsonResponse({ success: true, data: PROFILE })
    }
    if (url.includes('/api/organizations/org-empty/public-brand-profile')) {
      return jsonResponse({
        success: true,
        data: { organizationId: 'org-empty', brandName: null, description: null, industry: null, logoUrl: null },
      })
    }
    return jsonResponse({ success: false, error: '组织不存在' }, false, 404)
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
})

enableAutoUnmount(afterEach)

describe('BrandPublicProfilePanel', () => {
  test('按组织拉取并渲染品牌名/经营分类/简介/Logo', async () => {
    const wrapper = mount(BrandPublicProfilePanel, { props: { organizationId: 'org-1' } })
    await flushPromises()

    expect(wrapper.find('#gl-brand-public-profile').exists()).toBe(true)
    expect(wrapper.text()).toContain('草原咖啡')
    expect(wrapper.text()).toContain('餐饮')
    expect(wrapper.text()).toContain('社区精品咖啡烘焙')
    expect(wrapper.find('.brand-logo').attributes('src')).toBe('https://cdn.example.com/logo.png')
    expect(fetch).toHaveBeenCalledWith(
      '/api/organizations/org-1/public-brand-profile',
      expect.objectContaining({ credentials: 'include' }),
    )
  })

  test('资料全空（未填写）时整卡不渲染', async () => {
    const wrapper = mount(BrandPublicProfilePanel, { props: { organizationId: 'org-empty' } })
    await flushPromises()

    expect(wrapper.find('#gl-brand-public-profile').exists()).toBe(false)
  })

  test('organizationId 为空不发请求；切换组织重拉', async () => {
    const wrapper = mount(BrandPublicProfilePanel, { props: { organizationId: null } })
    await flushPromises()
    expect(fetch).not.toHaveBeenCalled()

    await wrapper.setProps({ organizationId: 'org-1' })
    await flushPromises()
    expect(wrapper.text()).toContain('草原咖啡')
  })

  test('Logo 加载失败置空降级，其余字段仍渲染', async () => {
    const wrapper = mount(BrandPublicProfilePanel, { props: { organizationId: 'org-1' } })
    await flushPromises()

    await wrapper.find('.brand-logo').trigger('error')
    expect(wrapper.find('.brand-logo').exists()).toBe(false)
    expect(wrapper.text()).toContain('草原咖啡')
  })
})
