// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import NotFoundView from './NotFoundView.vue'

/**
 * 404 内容页（任务书 #85 C-02）：固定文案、不回显路径、返回首页链接。
 * RouterLink 用 stub（纯组件层，不挂真实 router）。
 */
const RouterLinkStub = {
  name: 'RouterLink',
  props: { to: { type: [String, Object], required: true } },
  template: '<a :href="typeof to === \'string\' ? to : \'#\'"><slot /></a>',
}

function mountView() {
  return mount(NotFoundView, { global: { stubs: { RouterLink: RouterLinkStub } } })
}

describe('404 页渲染（任务书 #85 TC-85-021 组件侧）', () => {
  test('404 大字/标题/说明/返回首页四段文案齐全，链接指向 /', () => {
    const wrapper = mountView()

    expect(wrapper.get('.not-found-code').text()).toBe('404')
    expect(wrapper.get('h1').text()).toBe('页面不存在')
    expect(wrapper.get('.not-found-copy').text()).toBe('你访问的页面不存在或已被移动。')

    const links = wrapper.findAll('a')
    expect(links).toHaveLength(1)
    expect(links[0].text()).toBe('返回首页')
    expect(links[0].attributes('href')).toBe('/')
    expect(links[0].classes()).toContain('gl-btn-primary')
  })
})

describe('404 页不回显路径（任务书 #85 TC-85-023）', () => {
  test('页面文本仅由四个固定文案组成，不含路径字符', () => {
    const wrapper = mountView()
    const text = wrapper.text()

    expect(text).toContain('404')
    expect(text).toContain('页面不存在')
    expect(text).not.toContain('/')
    expect(text).not.toContain('?')
    expect(text).not.toContain('#')
  })
})
