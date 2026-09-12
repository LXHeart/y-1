// @vitest-environment happy-dom
import { afterEach, describe, expect, test, vi } from 'vitest'
import { mount, enableAutoUnmount, flushPromises } from '@vue/test-utils'
import CanvasAssetRail from './CanvasAssetRail.vue'

/**
 * 任务书 #100 C100-10（C100-20 端点补缺）：素材轨——媒体空/错/未生效状态（TC-023 前置面）。
 * 数据源是既有 /api/content-assets?libraryType=personal；mediaId（media_reference 句柄）
 * 是画布引用节点与 own-media 制作来源的键，不是 content_asset 行 id。
 */
enableAutoUnmount(afterEach)

interface AssetRow {
  mediaId?: string
  id?: string
  title?: string
  status?: string
  mimeType?: string
}

function contentAssetResponse(rows: AssetRow[], ok = true) {
  return vi.fn(async () => ({
    ok,
    status: ok ? 200 : 500,
    json: async () => ok
      ? { success: true, data: { items: rows.map((row, index) => ({
          id: row.id ?? `asset-${index + 1}`,
          mediaId: row.mediaId ?? `m-${index + 1}`,
          title: row.title ?? `素材 ${index + 1}`,
          status: row.status ?? 'active',
          mimeType: row.mimeType ?? 'video/mp4',
          validUntil: null,
        })) } }
      : { success: false, error: '素材读取失败' },
  }))
}

function requestedUrl(fetchMock: ReturnType<typeof vi.fn>): string {
  return fetchMock.mock.calls[0][0]
}

describe('#100 C100-10：画布素材轨（CanvasAssetRail）', () => {
  test('走既有个人内容资产端点；可用素材给「加为参考」，未生效素材原位占位', async () => {
    const fetchMock = contentAssetResponse([
      { mediaId: 'm-1', title: '门店实拍.mp4' },
      { mediaId: 'm-2', status: 'expired' },
      { mediaId: 'm-3', status: 'pending_review' },
    ])
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(CanvasAssetRail, { props: { authenticated: true } })
    await flushPromises()

    expect(requestedUrl(fetchMock)).toContain('/api/content-assets?libraryType=personal')
    expect(wrapper.find('[data-test="canvas-asset-add-m-1"]').exists()).toBe(true)
    // 未生效（过期/审核中）不提供入口，保留位置说明（TC-023）
    expect(wrapper.find('[data-test="canvas-asset-add-m-2"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="canvas-asset-state-m-2"]').text()).toContain('暂不可用')
    expect(wrapper.find('[data-test="canvas-asset-state-m-3"]').text()).toContain('暂不可用')
    // 「作为参考 ≠ 用于制作」边界常驻提示
    expect(wrapper.text()).toContain('作为参考不等于用于成片制作')
    // 拉取成功回传可用性投影（父级喂 useCanvasGraph.mediaAssets——addUserNode 校验源）
    const loaded = wrapper.emitted('loaded') ?? []
    const projection = loaded[loaded.length - 1]?.[0] as unknown[]
    expect(projection).toHaveLength(3)
    expect(projection[0]).toMatchObject({ id: 'm-1', name: '门店实拍.mp4', status: 'active' })
    expect(projection[1]).toMatchObject({ id: 'm-2', status: 'inactive' })
  })

  test('点击加为参考上抛 mediaId 键的素材对象（引用/制作来源共用键）', async () => {
    vi.stubGlobal('fetch', contentAssetResponse([{ mediaId: 'm-1', title: '门店实拍.mp4' }]))
    const wrapper = mount(CanvasAssetRail, { props: { authenticated: true } })
    await flushPromises()
    await wrapper.find('[data-test="canvas-asset-add-m-1"]').trigger('click')
    const emitted = wrapper.emitted('add-media') ?? []
    expect(emitted[emitted.length - 1]?.[0]).toMatchObject({ id: 'm-1', name: '门店实拍.mp4', authorized: true })
  })

  test('空态与错误态可重试（E08/E04）', async () => {
    vi.stubGlobal('fetch', contentAssetResponse([]))
    const wrapper = mount(CanvasAssetRail, { props: { authenticated: true } })
    await flushPromises()
    expect(wrapper.find('[data-test="canvas-asset-empty"]').exists()).toBe(true)

    vi.stubGlobal('fetch', contentAssetResponse([], false))
    const failing = mount(CanvasAssetRail, { props: { authenticated: true } })
    await flushPromises()
    expect(failing.find('[data-test="canvas-asset-error"]').exists()).toBe(true)
    // 网络恢复后重试成功 → 错误态消失
    vi.stubGlobal('fetch', contentAssetResponse([]))
    await failing.find('[data-test="canvas-asset-error"] button').trigger('click')
    await flushPromises()
    expect(failing.find('[data-test="canvas-asset-error"]').exists()).toBe(false)
  })
})
