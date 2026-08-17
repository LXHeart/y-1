// @vitest-environment happy-dom
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import HotTopicPicker from '../views/ai-center/components/HotTopicPicker.vue'
import type { HomepageHotTaxonomy } from '../types/homepage-hot'

enableAutoUnmount(afterEach)

const taxonomy: HomepageHotTaxonomy = {
  version: 'hot-taxonomy-v1',
  industries: [
    { value: 'catering', label: '餐饮' },
    { value: 'beauty', label: '美业' },
  ],
  cities: ['上海', '北京'],
  contentTypes: [{ value: 'tech', label: '科技' }],
}

function mountPicker() {
  return mount(HotTopicPicker, {
    props: {
      items: [
        {
          rank: 1,
          title: '上海AI火锅发布会',
          tags: { industries: ['catering'], city: '上海', contentType: 'tech' },
          validUntil: '2099-08-18T00:00:00Z',
          expired: false,
        },
        {
          rank: 2,
          title: '无标签热点',
          validUntil: '2020-01-01T00:00:00Z',
          expired: true,
        },
      ],
      groups: [],
      provider: '60s',
      fetchedAt: '2026-08-17T00:00:00Z',
      taxonomy,
      filters: { includeExpired: true },
      loading: false,
      error: '',
      selectedTitle: '',
      pickedTitle: '',
      resolvingTopic: false,
      topicError: '',
      structuredTopic: null,
    },
  })
}

describe('HotTopicPicker 热点筛选', () => {
  it('由响应 taxonomy 渲染筛选项并发送完整筛选状态', async () => {
    const wrapper = mountPicker()
    const selects = wrapper.findAll('.hot-filters select')

    expect(selects[0].findAll('option').map((option) => option.text())).toEqual(['全部行业', '餐饮', '美业'])
    await selects[0].setValue('catering')

    expect(wrapper.emitted('filter')?.[0]?.[0]).toEqual({ industry: 'catering', includeExpired: true })
    await wrapper.get('.hot-expired-toggle input').setValue(false)
    expect(wrapper.emitted('filter')?.[1]?.[0]).toEqual({ includeExpired: false })
  })

  it('展示行业城市内容类型标签，并灰显已过期项', () => {
    const wrapper = mountPicker()
    const items = wrapper.findAll('.hot-item')

    expect(items[0].find('.hot-tag-row').text()).toContain('餐饮')
    expect(items[0].find('.hot-tag-row').text()).toContain('上海')
    expect(items[0].find('.hot-tag-row').text()).toContain('科技')
    expect(items[0].find('.hot-validity').text()).toContain('有效期至')
    expect(items[0].find('.hot-validity').text()).toContain('剩余')
    expect(items[1].classes()).toContain('hot-item-expired')
    expect(items[1].find('.hot-validity').text()).toBe('已过期')
    expect(items[1].find('.hot-tag-row').exists()).toBe(false)
  })
})
