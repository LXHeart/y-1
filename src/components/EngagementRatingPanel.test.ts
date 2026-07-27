// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import EngagementRatingPanel from './EngagementRatingPanel.vue'

/**
 * 履约评分面板。重点锁：
 * - 未评价时后端回 `data:null`（不是 404），商家侧（canRate）才出评分表单；
 * - 评分必须先确认履约——canRate=false 时不出表单（后端另有 409 守卫）；
 * - 已评过两个视角都只读展示，不再出表单；
 * - 提交发送 {score, comment}，1-5 星。
 */

const RATING = {
  id: 'r1', applicationId: 'app-1', taskId: 'task-1',
  recommenderAccountId: 'rec-1', ratedByAccountId: 'mer-1',
  score: 5, comment: '合作顺畅', createdAt: '2026-07-27T10:00:00Z',
}

function stubFetch(getData: unknown, postData: unknown): {
  calls: { url: string; method: string; body?: string }[]
} {
  const calls: { url: string; method: string; body?: string }[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method || 'GET'
    calls.push({ url, method, body: init?.body as string | undefined })
    const data = method === 'POST' ? postData : getData
    return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
  }))
  return { calls }
}

function mountPanel(role: 'merchant' | 'recommender', canRate = false) {
  return mount(EngagementRatingPanel, {
    props: { taskId: 'task-1', applicationId: 'app-1', role, canRate },
  })
}

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

describe('EngagementRatingPanel 未评价', () => {
  /** data:null 不是 404；canRate 才出表单，否则提示先确认履约。 */
  test('商家未确认履约时不出表单，提示先确认', async () => {
    stubFetch(null, RATING)
    const wrapper = mountPanel('merchant', false)
    await flushPromises()

    expect(wrapper.text()).toContain('确认履约后可对本次合作评分')
    expect(wrapper.findAll('button').some((b) => b.text() === '提交评分')).toBe(false)
  })

  test('商家已确认履约（canRate）出评分表单', async () => {
    stubFetch(null, RATING)
    const wrapper = mountPanel('merchant', true)
    await flushPromises()

    expect(wrapper.findAll('button').some((b) => b.text() === '提交评分')).toBe(true)
    expect(wrapper.findAll('.rate-stars button')).toHaveLength(5)
  })

  test('推荐官侧未收到评分提示「商家尚未评分」', async () => {
    stubFetch(null, RATING)
    const wrapper = mountPanel('recommender')
    await flushPromises()

    expect(wrapper.text()).toContain('商家尚未评分')
    expect(wrapper.findAll('button').some((b) => b.text() === '提交评分')).toBe(false)
  })
})

describe('EngagementRatingPanel 评分', () => {
  test('商家提交发送 score 与 comment 并展示结果', async () => {
    const created = { ...RATING, score: 4, comment: '不错' }
    const { calls } = stubFetch(null, created)
    const wrapper = mountPanel('merchant', true)
    await flushPromises()

    // 点第 3 颗星（1-5 里选 3）
    await wrapper.findAll('.rate-stars button')[2].trigger('click')
    await wrapper.find('input[placeholder*="评价"]').setValue('不错')
    await wrapper.findAll('button').find((b) => b.text() === '提交评分')!.trigger('click')
    await flushPromises()

    const post = calls.find((c) => c.method === 'POST')!
    expect(post.url).toBe('/api/tasks/task-1/applications/app-1/rating')
    expect(JSON.parse(post.body!)).toEqual({ score: 3, comment: '不错' })
    expect(wrapper.text()).toContain('评分已提交')
    expect(wrapper.text()).toContain('4 分')
  })

  /** 未选星时提交按钮禁用（score 边界 1-5）。 */
  test('未选星时提交按钮禁用', async () => {
    stubFetch(null, RATING)
    const wrapper = mountPanel('merchant', true)
    await flushPromises()

    const submit = wrapper.findAll('button').find((b) => b.text() === '提交评分')!
    expect(submit.attributes('disabled')).toBeDefined()
  })
})

describe('EngagementRatingPanel 已评价', () => {
  /** 已评过：两个视角都只读展示，不再出表单。 */
  test('已评过分时只读展示评分与评论，无表单', async () => {
    stubFetch(RATING, RATING)
    const merchant = mountPanel('merchant', true)
    await flushPromises()

    expect(merchant.text()).toContain('5 分')
    expect(merchant.text()).toContain('合作顺畅')
    expect(merchant.findAll('button').some((b) => b.text() === '提交评分')).toBe(false)

    const recommender = mountPanel('recommender')
    await flushPromises()
    expect(recommender.text()).toContain('5 分')
  })
})
