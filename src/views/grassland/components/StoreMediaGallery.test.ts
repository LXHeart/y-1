// @vitest-environment happy-dom
/**
 * 任务书 #42 Stage 3：门店公开媒体画廊测试。
 *
 * 覆盖：四组分组渲染（空组不出现）、图片懒加载属性/视频 controls+preload=none、
 * 全空整卡不渲染、onerror → 重拉一次 public-media、再失败落占位。
 */
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import StoreMediaGallery from './StoreMediaGallery.vue'

enableAutoUnmount(afterEach)

afterEach(() => {
  vi.unstubAllGlobals()
})

function publicMediaEnvelope(groups: Record<string, unknown[]>, urlSuffix = ''): Response {
  return {
    ok: true, headers: { get: () => 'application/json' },
    json: async () => ({
      success: true,
      data: { storeId: 'store-1', groups },
    }),
  } as unknown as Response
}

function imageItem(mediaId: string, position: number, urlSuffix = '') {
  return {
    mediaId, mimeType: 'image/jpeg', sizeBytes: 100, position,
    downloadUrl: `https://cdn.test/${mediaId}${urlSuffix}`, urlExpiresAt: null,
  }
}

function fullGroups(urlSuffix = '') {
  return {
    storefront: [imageItem('media-s1', 1, urlSuffix)],
    environment: [],
    menu: [imageItem('media-m1', 1, urlSuffix), imageItem('media-m2', 2, urlSuffix)],
    video: [{
      mediaId: 'media-v1', mimeType: 'video/mp4', sizeBytes: 5000, position: 1,
      downloadUrl: `https://cdn.test/media-v1${urlSuffix}`, urlExpiresAt: null,
    }],
  }
}

function stubPublicMediaFetch(groups: Record<string, unknown[]>): ReturnType<typeof vi.fn> {
  const spy = vi.fn().mockImplementation(async (url: string) => {
    if (String(url).includes('/public-media')) return publicMediaEnvelope(groups)
    return publicMediaEnvelope({ storefront: [], environment: [], menu: [], video: [] })
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

describe('StoreMediaGallery 分组渲染', () => {
  test('非空组按序渲染；空组不出现；图片懒加载、视频不预载', async () => {
    const spy = stubPublicMediaFetch(fullGroups())
    const wrapper = mount(StoreMediaGallery, { props: { storeId: 'store-1' } })
    await flushPromises()

    expect(spy).toHaveBeenCalledWith(
      '/api/stores/store-1/public-media', expect.anything())
    expect(wrapper.text()).toContain('门店媒体')
    expect(wrapper.text()).toContain('门头照片')
    expect(wrapper.text()).toContain('菜单价目表')
    expect(wrapper.text()).toContain('宣传视频')
    // environment 为空组：不渲染标题。
    expect(wrapper.text()).not.toContain('环境照片')

    const firstImage = wrapper.find('img[src="https://cdn.test/media-s1"]')
    expect(firstImage.attributes('loading')).toBe('lazy')
    expect(firstImage.attributes('decoding')).toBe('async')
    // 固定宽高比占位容器防布局抖动。
    expect(firstImage.element.parentElement?.classList.contains('gl-media-frame')).toBe(true)

    const video = wrapper.find('video[src="https://cdn.test/media-v1"]')
    expect(video.attributes('controls')).toBeDefined()
    expect(video.attributes('preload')).toBe('none')
  })

  test('四组全空 → 整卡不渲染', async () => {
    stubPublicMediaFetch({ storefront: [], environment: [], menu: [], video: [] })
    const wrapper = mount(StoreMediaGallery, { props: { storeId: 'store-1' } })
    await flushPromises()

    expect(wrapper.find('#gl-store-media-gallery').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('门店媒体')
  })

  test('storeId 为空不渲染、不请求', async () => {
    const spy = stubPublicMediaFetch(fullGroups())
    const wrapper = mount(StoreMediaGallery, { props: { storeId: null } })
    await flushPromises()

    expect(wrapper.find('#gl-store-media-gallery').exists()).toBe(false)
    expect(spy).not.toHaveBeenCalled()
  })

  test('公开响应 404/失败 → 整卡不渲染（不打挂公开页）', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false, status: 404, headers: { get: () => 'application/json' },
      json: async () => ({ success: false, error: '门店不存在' }),
    }))
    const wrapper = mount(StoreMediaGallery, { props: { storeId: 'store-1' } })
    await flushPromises()

    expect(wrapper.find('#gl-store-media-gallery').exists()).toBe(false)
  })
})

describe('onerror 自愈：重拉一次 public-media，再失败落占位', () => {
  test('第一次 onerror 触发重拉（URL 换新）；重拉后再失败 → 该项落占位', async () => {
    let fetchCount = 0
    const spy = vi.fn().mockImplementation(async (url: string) => {
      if (String(url).includes('/public-media')) {
        fetchCount += 1
        // 重拉后签发新 URL（模拟换发短时 GET URL）。
        return publicMediaEnvelope(fullGroups(fetchCount > 1 ? '?fresh=1' : ''))
      }
      return publicMediaEnvelope({ storefront: [], environment: [], menu: [], video: [] })
    })
    vi.stubGlobal('fetch', spy)

    const wrapper = mount(StoreMediaGallery, { props: { storeId: 'store-1' } })
    await flushPromises()
    expect(fetchCount).toBe(1)

    // 图片加载失败（URL 过期）→ 重拉一次 public-media。
    await wrapper.find('img[src="https://cdn.test/media-s1"]').trigger('error')
    await flushPromises()
    expect(fetchCount).toBe(2)
    // 换新后的 URL 已渲染。
    expect(wrapper.find('img[src="https://cdn.test/media-s1?fresh=1"]').exists()).toBe(true)

    // 新 URL 仍失败 → 不再重拉，该项落占位，其余媒体不受影响。
    await wrapper.find('img[src="https://cdn.test/media-s1?fresh=1"]').trigger('error')
    await flushPromises()
    expect(fetchCount).toBe(2)
    expect(wrapper.text()).toContain('预览不可用')
    expect(wrapper.find('img[src="https://cdn.test/media-s1?fresh=1"]').exists()).toBe(false)
    expect(wrapper.find('img[src="https://cdn.test/media-m1?fresh=1"]').exists()).toBe(true)
  })

  test('重拉本身失败（上游 503）→ 保留旧 groups 不整卡蒸发，旧项逐个 @error 落占位', async () => {
    let fetchCount = 0
    const spy = vi.fn().mockImplementation(async (url: string) => {
      if (String(url).includes('/public-media')) {
        fetchCount += 1
        if (fetchCount > 1) {
          // 重拉时上游整体故障（D5 → 503）。
          return {
            ok: false, status: 503, headers: { get: () => 'application/json' },
            json: async () => ({ success: false, error: '门店媒体服务暂不可用' }),
          } as unknown as Response
        }
        return publicMediaEnvelope(fullGroups())
      }
      return publicMediaEnvelope({ storefront: [], environment: [], menu: [], video: [] })
    })
    vi.stubGlobal('fetch', spy)

    const wrapper = mount(StoreMediaGallery, { props: { storeId: 'store-1' } })
    await flushPromises()
    expect(fetchCount).toBe(1)

    // 首次 onerror 触发重拉；重拉 503 → 旧 groups 保留，整卡仍在（不蒸发）。
    await wrapper.find('img[src="https://cdn.test/media-s1"]').trigger('error')
    await flushPromises()
    expect(fetchCount).toBe(2)
    expect(wrapper.find('#gl-store-media-gallery').exists()).toBe(true)
    expect(wrapper.find('img[src="https://cdn.test/media-s1"]').exists()).toBe(true)

    // retryUsed 已耗：后续 onerror 不再重拉，逐个落占位，其余媒体不受影响。
    await wrapper.find('img[src="https://cdn.test/media-s1"]').trigger('error')
    await flushPromises()
    expect(fetchCount).toBe(2)
    expect(wrapper.text()).toContain('预览不可用')
    expect(wrapper.find('img[src="https://cdn.test/media-s1"]').exists()).toBe(false)
    expect(wrapper.find('img[src="https://cdn.test/media-m1"]').exists()).toBe(true)
    expect(wrapper.find('#gl-store-media-gallery').exists()).toBe(true)
  })
})
