// @vitest-environment happy-dom
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrecedentLibrary from './PrecedentLibrary.vue'

// Mock fetch
global.fetch = vi.fn()

describe('PrecedentLibrary', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('renders the header', () => {
    const wrapper = mount(PrecedentLibrary, {
      global: {
        stubs: {
          // Stub out the router if needed
        }
      }
    })

    expect(wrapper.find('.library-header h1').text()).toBe('判例库')
    expect(wrapper.find('.subtitle').text()).toContain('往期争议裁决案例')
  })

  it('renders filters', () => {
    const wrapper = mount(PrecedentLibrary)

    const filters = wrapper.findAll('.filter-group')
    expect(filters).toHaveLength(3)
    expect(filters[0].find('label').text()).toBe('平台')
    expect(filters[1].find('label').text()).toBe('任务类型')
    expect(filters[2].find('label').text()).toBe('争议类型')
  })

  it('shows empty state when no cases', async () => {
    ;(global.fetch as any).mockResolvedValueOnce({
      ok: true,
      json: async () => ({ cases: [], hasMore: false, nextCursor: null })
    })

    const wrapper = mount(PrecedentLibrary)
    await wrapper.vm.$nextTick()
    await new Promise(resolve => setTimeout(resolve, 100))

    expect(wrapper.find('.empty-state').exists()).toBe(true)
    expect(wrapper.find('.empty-state').text()).toContain('暂无符合条件的判例')
  })

  it('renders case cards when data is available', async () => {
    const mockCases = [
      {
        id: '1',
        disputeId: 'dispute-1',
        taskPlatform: 'douyin',
        disputeKind: 'quality',
        focus: '测试争议焦点',
        claimsSummary: '这是一个测试案例的描述',
        decision: 'for_merchant',
        finalVia: 'voting',
        voteDistribution: {
          for_merchant: 5,
          for_recommender: 2,
          abstain: 0
        },
        rationaleDigest: {
          for_merchant: ['理由1', '理由2'],
          for_recommender: ['理由3'],
          abstain: []
        },
        createdAt: '2026-09-01T10:00:00Z'
      }
    ]

    ;(global.fetch as any).mockResolvedValueOnce({
      ok: true,
      json: async () => ({ cases: mockCases, hasMore: false, nextCursor: null })
    })

    const wrapper = mount(PrecedentLibrary)
    await wrapper.vm.$nextTick()
    await new Promise(resolve => setTimeout(resolve, 100))

    expect(wrapper.find('.case-card').exists()).toBe(true)
    expect(wrapper.find('.focus').text()).toBe('测试争议焦点')
  })

  it('opens detail drawer when clicking a case', async () => {
    const mockCases = [
      {
        id: '1',
        disputeId: 'dispute-1',
        taskPlatform: 'douyin',
        disputeKind: 'quality',
        focus: '测试争议焦点',
        claimsSummary: '这是一个测试案例的描述',
        decision: 'for_merchant',
        finalVia: 'voting',
        voteDistribution: {
          for_merchant: 5,
          for_recommender: 2,
          abstain: 0
        },
        rationaleDigest: {
          for_merchant: ['理由1'],
          for_recommender: [],
          abstain: []
        },
        createdAt: '2026-09-01T10:00:00Z'
      }
    ]

    ;(global.fetch as any).mockResolvedValueOnce({
      ok: true,
      json: async () => ({ cases: mockCases, hasMore: false, nextCursor: null })
    })

    const wrapper = mount(PrecedentLibrary)
    await wrapper.vm.$nextTick()
    await new Promise(resolve => setTimeout(resolve, 100))

    await wrapper.find('.case-card').trigger('click')
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.detail-overlay').exists()).toBe(true)
    expect(wrapper.find('.drawer-header h2').text()).toBe('判例详情')
  })

  it('closes detail drawer when clicking close button', async () => {
    const mockCases = [
      {
        id: '1',
        disputeId: 'dispute-1',
        taskPlatform: 'douyin',
        disputeKind: 'quality',
        focus: '测试争议焦点',
        claimsSummary: '这是一个测试案例的描述',
        decision: 'for_merchant',
        finalVia: 'voting',
        voteDistribution: {
          for_merchant: 5,
          for_recommender: 2,
          abstain: 0
        },
        rationaleDigest: {
          for_merchant: [],
          for_recommender: [],
          abstain: []
        },
        createdAt: '2026-09-01T10:00:00Z'
      }
    ]

    ;(global.fetch as any).mockResolvedValueOnce({
      ok: true,
      json: async () => ({ cases: mockCases, hasMore: false, nextCursor: null })
    })

    const wrapper = mount(PrecedentLibrary)
    await wrapper.vm.$nextTick()
    await new Promise(resolve => setTimeout(resolve, 100))

    await wrapper.find('.case-card').trigger('click')
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.detail-overlay').exists()).toBe(true)

    await wrapper.find('.close-btn').trigger('click')
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.detail-overlay').exists()).toBe(false)
  })
})
