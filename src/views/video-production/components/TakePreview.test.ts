// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import TakePreview from './TakePreview.vue'
enableAutoUnmount(afterEach)
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

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
  test('TC102-052/054：Space starts and pauses the focused video once without selecting its canvas parent', async () => {
    const wrapper = mountPreview({ url: '/own.mp4' })
    const node = wrapper.get('video'); const video = node.element as HTMLVideoElement
    let paused = true
    Object.defineProperty(video, 'paused', { get: () => paused, configurable: true })
    const play = vi.spyOn(video, 'play').mockImplementation(async () => { paused = false })
    const pause = vi.spyOn(video, 'pause').mockImplementation(() => { paused = true })
    const parentKey = vi.fn(); wrapper.element.addEventListener('keydown', parentKey)
    const key = new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true })
    video.dispatchEvent(key); await flushPromises()
    expect(play).toHaveBeenCalledOnce(); expect(paused).toBe(false)
    expect(key.defaultPrevented).toBe(true); expect(parentKey).not.toHaveBeenCalled()
    await node.trigger('keydown', { key: ' ', repeat: true })
    expect(pause).not.toHaveBeenCalled()
    await node.trigger('keydown', { key: ' ' })
    expect(pause).toHaveBeenCalledOnce(); expect(paused).toBe(true)
    expect(node.attributes('tabindex')).toBe('0')
  })
  test('TC102-052：late playback rejection cannot fail a new source, while a current failure offers retry', async () => {
    const wrapper = mountPreview({ url: '/old.mp4' })
    let rejectOld!: (error: Error) => void
    vi.spyOn(wrapper.get('video').element as HTMLVideoElement, 'play').mockImplementation(() => new Promise((_resolve, reject) => { rejectOld = reject }))
    await wrapper.get('video').trigger('keydown', { key: ' ' })
    await wrapper.setProps({ url: '/current.mp4' })
    expect(rejectOld).toBeTypeOf('function')
    rejectOld(new Error('old playback interrupted')); await flushPromises()
    expect(wrapper.get('video').attributes('src')).toBe('/current.mp4')
    vi.spyOn(wrapper.get('video').element as HTMLVideoElement, 'play').mockRejectedValue(new Error('media unavailable'))
    await wrapper.get('video').trigger('keydown', { key: ' ' }); await flushPromises()
    expect(wrapper.find('[data-test="take-preview-failed"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="take-preview-retry"]').exists()).toBe(true)
  })
  test('TC102-052：Space at the clip end restarts from the trim start even while the final seek is settling', async () => {
    const wrapper = mount(TakePreview, { props: { url: '/own.mp4', trimStartMs: 500, trimEndMs: 5500 } })
    const video = wrapper.get('video').element as HTMLVideoElement
    video.currentTime = 5.5
    Object.defineProperty(video, 'seeking', { value: true, configurable: true })
    const play = vi.spyOn(video, 'play').mockResolvedValue()
    await wrapper.get('video').trigger('keydown', { key: ' ' })
    expect(video.currentTime).toBe(0.5)
    expect(play).toHaveBeenCalledOnce()
  })
  test('TC102-052：毫秒区间从起点播放、到尾暂停，越界seek受限，原音可预览', async () => {
    vi.useFakeTimers()
    const wrapper = mount(TakePreview, { props: { url: '/own.mp4', trimStartMs: 1250, trimEndMs: 6250, muted: false } })
    const node = wrapper.get('video'); const video = node.element as HTMLVideoElement
    const pause = vi.spyOn(video, 'pause').mockImplementation(() => {})
    Object.defineProperty(video, 'paused', { value: false, configurable: true })
    await node.trigger('loadedmetadata'); expect(video.currentTime).toBe(1.25)
    expect(node.attributes('muted')).toBeUndefined()
    await node.trigger('play'); await vi.advanceTimersByTimeAsync(5000)
    expect(video.currentTime).toBe(6.25); expect(pause).toHaveBeenCalled()
    video.currentTime = 0.1; await node.trigger('seeked'); expect(video.currentTime).toBe(1.25)
    video.currentTime = 7; await node.trigger('timeupdate'); expect(video.currentTime).toBe(6.25)
    await wrapper.setProps({ url: '/other.mp4', trimStartMs: 500, trimEndMs: 5500 })
    expect(pause).toHaveBeenCalled(); expect(wrapper.get('video').element).not.toBe(video)
  })
  test('TC102-052：离屏与卸载暂停，非法区间不挂媒体', async () => {
    let observe!: IntersectionObserverCallback
    const disconnect = vi.fn()
    vi.stubGlobal('IntersectionObserver', class { constructor(callback: IntersectionObserverCallback) { observe = callback } observe() {} disconnect = disconnect })
    const wrapper = mountPreview({ url: '/own.mp4' })
    const pause = vi.spyOn(wrapper.get('video').element as HTMLVideoElement, 'pause').mockImplementation(() => {})
    observe([{ isIntersecting: false } as IntersectionObserverEntry], {} as IntersectionObserver)
    await wrapper.vm.$nextTick()
    expect(pause).toHaveBeenCalled(); expect(wrapper.find('video').exists()).toBe(false)
    wrapper.unmount(); expect(disconnect).toHaveBeenCalled()
    const invalid = mount(TakePreview, { props: { url: '/invalid.mp4', trimStartMs: 3.5, trimEndMs: 5000 } })
    expect(invalid.find('video').exists()).toBe(false)
    expect(invalid.text()).toContain('裁剪区间无效')
  })
  test('TC102-052：浏览器仍在seek时不重复重置目标，避免首帧加载死循环', async () => {
    const wrapper = mount(TakePreview, { props: { url: '/own.mp4', trimStartMs: 1250, trimEndMs: 6250 } })
    const node = wrapper.get('video'); const video = node.element as HTMLVideoElement
    let position = 0; const seek = vi.fn((value: number) => { position = value })
    Object.defineProperty(video, 'currentTime', { get: () => position, set: seek, configurable: true })
    Object.defineProperty(video, 'seeking', { value: true, configurable: true })
    await node.trigger('seeking'); await node.trigger('timeupdate')
    expect(seek).not.toHaveBeenCalled()
    Object.defineProperty(video, 'seeking', { value: false, configurable: true })
    await node.trigger('seeked'); expect(seek).toHaveBeenCalledOnce(); expect(position).toBe(1.25)
  })
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
