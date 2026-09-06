// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import LegalDocumentView from './LegalDocumentView.vue'

/**
 * 法律文档视图（任务书 #85 C-01）：公开静态页——匿名可读、净化渲染、非法 kind 防御。
 * 「返回首页」用 RouterLink stub（本测试不挂真实 router/pinia，纯组件层）。
 */
const RouterLinkStub = {
  name: 'RouterLink',
  props: { to: { type: [String, Object], required: true } },
  template: '<a :href="typeof to === \'string\' ? to : \'#\'"><slot /></a>',
}

function mountView(kind: string) {
  return mount(LegalDocumentView, {
    props: { kind: kind as 'user-agreement' },
    global: { stubs: { RouterLink: RouterLinkStub } },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('法律文档视图（任务书 #85 TC-85-012）', () => {
  test('user-agreement：标题/版本行/占位横幅/正文章节/返回首页齐全，零网络请求', async () => {
    vi.stubGlobal('fetch', vi.fn())
    const wrapper = mountView('user-agreement')

    expect(wrapper.get('h1').text()).toBe('用户协议')
    expect(wrapper.get('.legal-doc-version').text()).toContain('v0.1-placeholder')
    expect(wrapper.get('.legal-doc-version').text()).toContain('未生效（占位预览）')
    expect(wrapper.get('.legal-doc-notice').text()).toContain('占位预览版本')
    expect(wrapper.get('.legal-doc-notice').attributes('role')).toBe('note')

    const body = wrapper.get('.legal-doc-body')
    expect(body.text()).toContain('一、协议的接受')
    expect(body.text()).toContain('十、与我们联系')
    expect(body.findAll('h2').length).toBeGreaterThan(0)
    expect(body.findAll('li').length).toBeGreaterThan(0)

    const links = wrapper.findAll('a')
    expect(links.filter((a) => a.text() === '返回首页')).toHaveLength(1)
    expect(fetch).not.toHaveBeenCalled()
  })

  test('privacy-policy：同构渲染《隐私政策》', () => {
    vi.stubGlobal('fetch', vi.fn())
    const wrapper = mountView('privacy-policy')

    expect(wrapper.get('h1').text()).toBe('隐私政策')
    expect(wrapper.get('.legal-doc-version').text()).toContain('v0.1-placeholder')
    expect(wrapper.get('.legal-doc-body').text()).toContain('一、适用范围')
    expect(wrapper.get('.legal-doc-body').text()).toContain('十、与我们联系')
    expect(wrapper.findAll('a').filter((a) => a.text() === '返回首页')).toHaveLength(1)
    expect(fetch).not.toHaveBeenCalled()
  })
})

describe('净化渲染路径（任务书 #85 TC-85-013）', () => {
  test('正文 innerHTML 不含脚本面，站内互链保留', () => {
    const wrapper = mountView('user-agreement')
    const html = wrapper.get('.legal-doc-body').html()

    expect(html).not.toContain('<script')
    expect(html.toLowerCase()).not.toContain('onerror')
    expect(html.toLowerCase()).not.toContain('javascript:')
    expect(html).toContain('href="/docs/privacy-policy"')
  })

  test('隐私政策互链指向用户协议', () => {
    const wrapper = mountView('privacy-policy')
    expect(wrapper.get('.legal-doc-body').html()).toContain('href="/docs/user-agreement"')
  })
})

describe('非法 kind 防御（任务书 #85 TC-85-016）', () => {
  test('未知 kind 渲染「文档不存在」兜底，不渲染正文不发请求', () => {
    vi.stubGlobal('fetch', vi.fn())
    const wrapper = mountView('nope')

    expect(wrapper.text()).toContain('文档不存在')
    expect(wrapper.find('.legal-doc-body').exists()).toBe(false)
    expect(wrapper.findAll('a').filter((a) => a.text() === '返回首页')).toHaveLength(1)
    expect(fetch).not.toHaveBeenCalled()
  })
})
