// @vitest-environment happy-dom
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { effectScope } from 'vue'
import ArticleFormatPanel from './ArticleFormatPanel.vue'
import { useArticleRender } from '../composables/useArticleRender'
import type { RenderPreview } from '../../../types/creation-studio'

/**
 * 任务书 #101 C101-17（SC101-07）：排版双栏——no-AI（预览按钮走受控端点）、主题只改样式、
 * 净化（script/onerror 剥离）、缺图与未绑定说明、移动端栏切换、摘要建议独立动作。
 * fetch 零调用（composable 状态手工灌注）。
 */

const PREVIEW: RenderPreview = {
  draftId: 'draft-1', version: 3, renderVersion: 'creation-render-1.0.0',
  contentHash: 'h',
  html: '<style>.creation-render{font-size:16px}</style><div class="creation-render"><p>人均 68 元</p>'
    + '<figure data-render="media"><img src="/api/media/m-1" alt="配图"></figure>'
    + '<script>alert(1)</script><img src=x onerror="alert(2)"></div>',
  text: '人均 68 元',
  warnings: ['媒体 m-1 未绑定段落，已按原顺序附于文后'],
  unresolvedMediaIds: ['m-1'],
}

enableAutoUnmount(afterEach)

function setup(preview: RenderPreview | null = PREVIEW) {
  const scope = effectScope()
  const renderState = scope.run(() => useArticleRender({
    draftId: () => 'draft-1', draftVersion: () => 3,
  }))!
  if (preview) {
    renderState.preview.value = preview
  }
  return { renderState }
}

function mountPanel(renderState: ReturnType<typeof useArticleRender>, props: Record<string, unknown> = {}) {
  return mount(ArticleFormatPanel, {
    props: { render: renderState, content: '门店三年，人均 68 元。', ...props },
  })
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn())
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('ArticleFormatPanel', () => {
  test('预览经净化插入：script 块整体剥离，正文文字保留', () => {
    const { renderState } = setup()
    const wrapper = mountPanel(renderState)
    const body = wrapper.find('[data-test="format-preview-body"]')
    expect(body.element.textContent).toContain('人均 68 元')
    // script 块整体预剥离（happy-dom 的 DOMPurify 有环境限制，真实浏览器双保险）
    expect(body.element.querySelectorAll('script')).toHaveLength(0)
    expect(body.html()).not.toContain('onerror')
    expect(body.html()).not.toContain('alert(')
    // 服务端受控样式（style 白名单）保留
    expect(body.html()).toContain('creation-render')
  })

  test('缺图与未绑定段落明确标注（不伪装完整）', () => {
    const { renderState } = setup()
    const wrapper = mountPanel(renderState)
    expect(wrapper.find('[data-test="format-unresolved"]').text()).toContain('1 张配图未绑定段落')
    expect(wrapper.find('[data-test="format-warnings"]').text()).toContain('m-1')
  })

  test('生成预览按钮 emit 参数（主题/开关只影响本次输出）；不直接发请求', async () => {
    const { renderState } = setup(null)
    const wrapper = mountPanel(renderState)
    await wrapper.find('[data-test="format-theme"]').setValue('compact')
    await wrapper.find('[data-test="format-include-title"]').setValue(true)
    await wrapper.find('[data-test="format-render"]').trigger('click')
    expect(wrapper.emitted('render-requested')?.[0]).toEqual([{
      theme: 'compact', includeTitle: true, citeExternalLinks: false,
    }])
    expect(fetch).not.toHaveBeenCalled()
  })

  test('摘要建议是独立动作（emit，不触发渲染/请求）', async () => {
    const { renderState } = setup()
    const wrapper = mountPanel(renderState)
    await wrapper.find('[data-test="format-suggest-summary"]').trigger('click')
    expect(wrapper.emitted('suggest-summary')).toHaveLength(1)
    expect(fetch).not.toHaveBeenCalled()
  })

  test('错误可见；无预览时占位文案', () => {
    const { renderState } = setup(null)
    renderState.error.value = '排版预览失败'
    const wrapper = mountPanel(renderState)
    expect(wrapper.find('[data-test="format-error"]').text()).toContain('排版预览失败')
    expect(wrapper.find('[data-test="format-preview-body"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('点击「生成排版预览」')
  })

  test('移动端（768px 断点）原稿/预览页内切换', async () => {
    const { renderState } = setup()
    const wrapper = mountPanel(renderState)
    const columns = wrapper.find('.columns')
    expect(columns.attributes('data-mobile-view')).toBe('source')
    const toggles = columns.element.parentElement?.querySelectorAll('.mobile-toggle button')
    expect(toggles?.length).toBe(2)
    const buttons = wrapper.findAll('.mobile-toggle button')
    await buttons[1].trigger('click')
    expect(wrapper.find('.columns').attributes('data-mobile-view')).toBe('preview')
  })

  test('双栏：原稿只读展示用户 markdown', () => {
    const { renderState } = setup()
    const wrapper = mountPanel(renderState)
    expect(wrapper.find('[data-test="format-source"]').text()).toContain('门店三年，人均 68 元。')
  })
})
