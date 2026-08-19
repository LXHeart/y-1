// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import PublicAssetsAdminPanel from './PublicAssetsAdminPanel.vue'

const api = vi.hoisted(() => ({
  error: { value: '' },
  listPendingPublicAssetReviews: vi.fn(), getContentAssetDownloadUrl: vi.fn(),
  batchGeneratePublicAssets: vi.fn(), approvePublicAsset: vi.fn(), rejectPublicAsset: vi.fn(),
}))
vi.mock('../../../composables/useGrassland', () => ({ useGrassland: () => api }))

enableAutoUnmount(afterEach)
beforeEach(() => {
  vi.clearAllMocks(); api.error.value = ''
  api.listPendingPublicAssetReviews.mockResolvedValue({ items: [] })
})

describe('PublicAssetsAdminPanel', () => {
  test('invalid batch input is blocked before calling the API', async () => {
    const wrapper = mount(PublicAssetsAdminPanel)
    await flushPromises()
    await wrapper.get('input[placeholder="例如：夏日饮品"]').setValue('   ')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('主题长度需为 1-100 字符')
    expect(api.batchGeneratePublicAssets).not.toHaveBeenCalled()
  })

  test('rejecting an asset requires a note and successful approval removes it', async () => {
    api.listPendingPublicAssetReviews.mockResolvedValue({ items: [{
      id: 'asset-1', version: 3, title: '夏日图标', category: 'other', tags: ['夏日'], validUntil: null,
    }] })
    api.getContentAssetDownloadUrl.mockResolvedValue({ downloadUrl: '/api/media/asset-1' })
    api.approvePublicAsset.mockResolvedValue({ id: 'asset-1' })
    const wrapper = mount(PublicAssetsAdminPanel)
    await flushPromises()

    const actions = wrapper.findAll('.review-actions button')
    await actions[1].trigger('click')
    expect(wrapper.text()).toContain('驳回公共素材必须填写原因')
    expect(api.rejectPublicAsset).not.toHaveBeenCalled()

    await actions[0].trigger('click'); await flushPromises()
    expect(api.approvePublicAsset).toHaveBeenCalledWith('asset-1', 3, undefined)
    expect(wrapper.text()).toContain('暂无待审核公共素材')
  })
})
