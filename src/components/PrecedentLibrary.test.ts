// @vitest-environment happy-dom
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrecedentLibrary from './PrecedentLibrary.vue'

// Mock fetch（契约对齐后端 PrecedentController：{success,data:{items,hasMore}} 信封 + 表行投影）
global.fetch = vi.fn()

/** 后端表行形状：voteSummary/rationaleDigest 是 JSON 字符串。 */
function backendRow(overrides: Record<string, unknown> = {}) {
  return {
    id: '1',
    disputeId: 'dispute-1',
    taskType: null,
    taskPlatform: 'douyin',
    disputeKind: 'standard',
    focus: '测试争议焦点',
    claimsSummary: '这是一个测试案例的描述',
    decision: 'for_merchant',
    finalVia: 'panel',
    voteSummary: JSON.stringify([
      { forMerchant: 2, forRecommender: 2, abstain: 0, matchedPlatformCount: 4 },
      { forMerchant: 5, forRecommender: 2, abstain: 0, matchedPlatformCount: 4 },
    ]),
    rationaleDigest: JSON.stringify(['理由一，超过二十个字的投票说明理由一', '理由二，同样超过二十个字的说明']),
    createdAt: '2026-09-01T10:00:00Z',
    ...overrides,
  }
}

function mockList(items: unknown[], hasMore = false) {
  ;(global.fetch as unknown as { mockResolvedValueOnce: (v: unknown) => void }).mockResolvedValueOnce({
    ok: true,
    json: async () => ({ success: true, data: { items, page: 1, pageSize: 20, total: items.length, hasMore } }),
  })
}

describe('PrecedentLibrary', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('renders the header', () => {
    const wrapper = mount(PrecedentLibrary)

    expect(wrapper.find('.library-header h1').text()).toBe('判例库')
    expect(wrapper.find('.subtitle').text()).toContain('往期争议裁决案例')
  })

  it('renders filters（平台 + 争议类型；task_type v1 无事实源不提供）', () => {
    const wrapper = mount(PrecedentLibrary)

    const filters = wrapper.findAll('.filter-group')
    expect(filters).toHaveLength(2)
    expect(filters[0].find('label').text()).toBe('平台')
    expect(filters[1].find('label').text()).toBe('争议类型')
  })

  it('shows empty state when no cases', async () => {
    mockList([])

    const wrapper = mount(PrecedentLibrary)
    await wrapper.vm.$nextTick()
    await new Promise(resolve => setTimeout(resolve, 100))

    expect(wrapper.find('.empty-state').exists()).toBe(true)
    expect(wrapper.find('.empty-state').text()).toContain('暂无符合条件的判例')
  })

  it('renders case cards when data is available', async () => {
    mockList([backendRow()])

    const wrapper = mount(PrecedentLibrary)
    await wrapper.vm.$nextTick()
    await new Promise(resolve => setTimeout(resolve, 100))

    expect(wrapper.find('.case-card').exists()).toBe(true)
    expect(wrapper.find('.focus').text()).toBe('测试争议焦点')
    // 终局轮投票分布解析进卡片计数
    expect(wrapper.find('.vote-counts').text()).toContain('商家 5')
    expect(wrapper.find('.vote-counts').text()).toContain('推荐官 2')
  })

  it('opens detail drawer when clicking a case and shows rationale list', async () => {
    mockList([backendRow()])

    const wrapper = mount(PrecedentLibrary)
    await wrapper.vm.$nextTick()
    await new Promise(resolve => setTimeout(resolve, 100))

    await wrapper.find('.case-card').trigger('click')
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.detail-overlay').exists()).toBe(true)
    expect(wrapper.find('.drawer-header h2').text()).toBe('判例详情')
    expect(wrapper.find('.final-via').text()).toBe('七官面板裁决')
    // 理由摘要为不分组的脱敏列表（D5：不见审判官身份与方向）
    const rationales = wrapper.findAll('.rationale-list li')
    expect(rationales).toHaveLength(2)
    expect(rationales[0].text()).toContain('理由一')
  })

  it('cs_direct cases render without vote bar counts and explain in drawer', async () => {
    mockList([backendRow({
      finalVia: 'cs',
      voteSummary: null,
      rationaleDigest: null,
      decision: 'for_recommender',
    })])

    const wrapper = mount(PrecedentLibrary)
    await wrapper.vm.$nextTick()
    await new Promise(resolve => setTimeout(resolve, 100))

    expect(wrapper.find('.case-card').exists()).toBe(true)
    expect(wrapper.find('.vote-counts').text()).toContain('商家 0')

    await wrapper.find('.case-card').trigger('click')
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.drawer-content').text()).toContain('客服终审')
    expect(wrapper.find('.drawer-content').text()).toContain('无投票理由摘要')
  })

  it('closes detail drawer when clicking close button', async () => {
    mockList([backendRow()])

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
