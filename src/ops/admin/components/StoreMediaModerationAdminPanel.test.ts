// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import StoreMediaModerationAdminPanel from './StoreMediaModerationAdminPanel.vue'

const api = vi.hoisted(() => ({
  error: { value: '' },
  listStoreMediaModerationQueue: vi.fn(),
  reviewStoreMediaModeration: vi.fn(),
}))
vi.mock('../../../composables/useGrassland', () => ({ useGrassland: () => api }))

function queueItem(overrides: Record<string, unknown> = {}) {
  return {
    mediaId: 'media-1',
    status: 'review',
    findings: [{ category: 'unparseable', severity: 'medium', advice: '审核模型输出不可解析' }],
    model: null,
    runId: 'run-1',
    moderatedAt: '2026-08-21T02:00:00Z',
    reviewedBy: null,
    reviewedAt: null,
    reviewNote: null,
    mimeType: 'image/png',
    sizeBytes: 2048,
    organizationId: 'org-1',
    storeId: 'store-1',
    createdAt: '2026-08-20T10:00:00Z',
    downloadUrl: 'http://localhost:9002/media/media-1',
    ...overrides,
  }
}

enableAutoUnmount(afterEach)
beforeEach(() => {
  vi.clearAllMocks(); api.error.value = ''
  api.listStoreMediaModerationQueue.mockResolvedValue({ status: 'review', items: [], total: 0 })
})

describe('StoreMediaModerationAdminPanel', () => {
  test('renders review queue with findings and preview, empty state otherwise', async () => {
    api.listStoreMediaModerationQueue.mockResolvedValue({ status: 'review', items: [queueItem()], total: 1 })
    const wrapper = mount(StoreMediaModerationAdminPanel)
    await flushPromises()

    expect(api.listStoreMediaModerationQueue).toHaveBeenCalledWith('review', { limit: 50, offset: 0 })
    expect(wrapper.get('img').attributes('src')).toBe('http://localhost:9002/media/media-1')
    expect(wrapper.text()).toContain('unparseable')
    expect(wrapper.text()).toContain('store-1')

    api.listStoreMediaModerationQueue.mockResolvedValue({ status: 'review', items: [], total: 0 })
    await wrapper.get('button.refresh-btn').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('暂无待复核的门店媒体')
  })

  test('rejecting requires a note; approving posts expectedModeratedAt and reloads queue', async () => {
    api.listStoreMediaModerationQueue.mockResolvedValue({ status: 'review', items: [queueItem()], total: 1 })
    api.reviewStoreMediaModeration.mockResolvedValue({ mediaId: 'media-1', status: 'pass' })
    const wrapper = mount(StoreMediaModerationAdminPanel)
    await flushPromises()

    const actions = wrapper.findAll('.review-actions button')
    await actions[1].trigger('click')
    expect(wrapper.text()).toContain('驳回门店媒体必须填写原因')
    expect(api.reviewStoreMediaModeration).not.toHaveBeenCalled()

    await actions[0].trigger('click'); await flushPromises()
    expect(api.reviewStoreMediaModeration).toHaveBeenCalledWith('media-1', 'approve', '2026-08-21T02:00:00Z', undefined)
    expect(api.listStoreMediaModerationQueue).toHaveBeenCalledTimes(2)
  })

  test('video items render a video preview and decided items show the review trail', async () => {
    api.listStoreMediaModerationQueue.mockResolvedValue({
      status: 'blocked', total: 1,
      items: [
        queueItem({ mediaId: 'media-2', mimeType: 'video/mp4', status: 'blocked',
          reviewedBy: 'reviewer-9', reviewedAt: '2026-08-21T03:00:00Z', reviewNote: '画面含违禁品' }),
      ],
    })
    const wrapper = mount(StoreMediaModerationAdminPanel)
    await flushPromises()

    expect(wrapper.find('video').exists()).toBe(true)
    expect(wrapper.text()).toContain('画面含违禁品')
    // 已裁决项不再出现复核按钮
    expect(wrapper.findAll('.review-actions button')).toHaveLength(0)
  })
})
