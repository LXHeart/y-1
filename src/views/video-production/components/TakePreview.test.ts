// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, test, vi } from 'vitest'
import TakePreview from './TakePreview.vue'

/**
 * 任务书 #100 C100-06：两模式共用候选媒体预览回归。
 * happy-dom 无 IntersectionObserver——回落立即可见路径；浏览器内的懒加载/离屏释放
 * 由 V-UI harness 在真实 Chromium 覆盖。
 */

function mountPreview(props: { url?: string | null; placeholder?: string; note?: string | null }) {
  return mount(TakePreview, {
    props: {
      url: props.url ?? null,
      placeholder: props.placeholder ?? '',
      note: props.note ?? null,
    },
  })
}

describe('TakePreview', () => {
  test('有 url：挂 video（metadata 预载/静音/可控/隔断 pointerdown），不整文件预载', async () => {
    const wrapper = mountPreview({ url: 'https://media.example.test/take-1.mp4' })
    const video = wrapper.find('[data-test="take-preview-video"]')
    expect(video.exists()).toBe(true)
    expect(video.attributes('preload')).toBe('metadata')
    expect(video.attributes('muted')).toBeDefined()
    expect(video.attributes('controls')).toBeDefined()
    expect(video.attributes('src')).toBe('https://media.example.test/take-1.mp4')

    // 容器隔断 pointerdown（画布节点不因控件交互被拖拽/选中）
    const stop = vi.fn()
    wrapper.find('[data-test="take-preview"]').trigger('pointerdown', { stopPropagation: stop })
    expect(stop).toHaveBeenCalled()
  })

  test('无 url：占位态显示状态与失败原因', () => {
    const wrapper = mountPreview({ placeholder: '生成中', note: '上游限流，自动重试中' })
    expect(wrapper.find('[data-test="take-preview-placeholder"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('生成中')
    expect(wrapper.text()).toContain('上游限流，自动重试中')
    expect(wrapper.find('[data-test="take-preview-video"]').exists()).toBe(false)
  })

  test('加载失败：显示失效态与重试；重试重建 video 并上抛 media-error 供宿主重取 URL', async () => {
    const wrapper = mountPreview({ url: 'https://media.example.test/expired.mp4' })
    expect(wrapper.find('[data-test="take-preview-video"]').exists()).toBe(true)

    await wrapper.find('[data-test="take-preview-video"]').trigger('error')
    expect(wrapper.find('[data-test="take-preview-failed"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('媒体已失效')

    await wrapper.find('[data-test="take-preview-retry"]').trigger('click')
    expect(wrapper.emitted('media-error')).toHaveLength(1)
    expect(wrapper.find('[data-test="take-preview-video"]').exists()).toBe(true)
  })

  test('url 变化（宿主重取到合法 URL）：自动重挂新地址', async () => {
    const wrapper = mountPreview({ url: 'https://media.example.test/a.mp4' })
    await wrapper.setProps({ url: 'https://media.example.test/b.mp4' })
    const video = wrapper.find('[data-test="take-preview-video"]')
    expect(video.attributes('src')).toBe('https://media.example.test/b.mp4')
  })
})
