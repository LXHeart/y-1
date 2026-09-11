// @vitest-environment happy-dom
import { afterEach, describe, expect, test, vi } from 'vitest'
import { mount, enableAutoUnmount, flushPromises } from '@vue/test-utils'
import CanvasAssetRail from './CanvasAssetRail.vue'

/**
 * 任务书 #100 C100-10：素材轨——媒体空/错/过期状态（TC-023 前置面）。
 */
enableAutoUnmount(afterEach)

function mediaResponse(items: Array<{ id: string; fileName?: string; status?: string }>, ok = true) {
  return vi.fn(async () => ({
    ok,
    status: ok ? 200 : 500,
    json: async () => ok
      ? { success: true, data: { items: items.map(item => ({
          id: item.id, fileName: item.fileName ?? `${item.id}.mp4`,
          status: item.status ?? 'active', contentType: 'video/mp4',
        })) } }
      : { success: false, error: '素材读取失败' },
  }))
}

describe('#100 C100-10：画布素材轨（CanvasAssetRail）', () => {
  test('可用素材给「加为参考」；撤销/删除素材原位占位不给入口', async () => {
    vi.stubGlobal('fetch', mediaResponse([
      { id: 'm-1' },
      { id: 'm-2', status: 'revoked' },
      { id: 'm-3', status: 'deleted' },
    ]))
    const wrapper = mount(CanvasAssetRail, { props: { authenticated: true } })
    await flushPromises()

    expect(wrapper.find('[data-test="canvas-asset-add-m-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="canvas-asset-add-m-2"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="canvas-asset-state-m-2"]').text()).toContain('授权已撤销')
    expect(wrapper.find('[data-test="canvas-asset-state-m-3"]').text()).toContain('已删除')
    // 「作为参考 ≠ 用于制作」边界常驻提示（C100-11 前不提供可执行来源切换）
    expect(wrapper.text()).toContain('作为参考不等于用于成片制作')
  })

  test('点击加为参考上抛完整素材对象（mediaId 引用由父级写入画布文档）', async () => {
    vi.stubGlobal('fetch', mediaResponse([{ id: 'm-1', fileName: '门店实拍.mp4' }]))
    const wrapper = mount(CanvasAssetRail, { props: { authenticated: true } })
    await flushPromises()
    await wrapper.find('[data-test="canvas-asset-add-m-1"]').trigger('click')
    const emitted = wrapper.emitted('add-media') ?? []
    expect(emitted[emitted.length - 1]?.[0]).toMatchObject({ id: 'm-1', name: '门店实拍.mp4' })
  })

  test('空态与错误态可重试（E08/E04）', async () => {
    vi.stubGlobal('fetch', mediaResponse([]))
    const wrapper = mount(CanvasAssetRail, { props: { authenticated: true } })
    await flushPromises()
    expect(wrapper.find('[data-test="canvas-asset-empty"]').exists()).toBe(true)

    vi.stubGlobal('fetch', mediaResponse([], false))
    const failing = mount(CanvasAssetRail, { props: { authenticated: true } })
    await flushPromises()
    expect(failing.find('[data-test="canvas-asset-error"]').exists()).toBe(true)
    // 网络恢复后重试成功 → 错误态消失
    vi.stubGlobal('fetch', mediaResponse([]))
    await failing.find('[data-test="canvas-asset-error"] button').trigger('click')
    await flushPromises()
    expect(failing.find('[data-test="canvas-asset-error"]').exists()).toBe(false)
  })
})
