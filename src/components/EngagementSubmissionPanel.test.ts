// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import EngagementSubmissionPanel from './EngagementSubmissionPanel.vue'

/**
 * 履约交付物面板。两个视角共用一份数据，重点锁：
 * - 列表端点返回 `{submissions: [...]}` 而非裸数组（与其它列表端点不一致，最容易接错）；
 * - 已有待核验的一份时不能再提交（后端 409，前端先禁用）；
 * - 商家侧才有「退回补交」，推荐官侧才有提交表单。
 */

const SUBMITTED = {
  id: 's1', applicationId: 'app-1', recommenderAccountId: 'rec-1',
  contentUrl: 'https://example.com/post/1', note: '已按要求发布',
  status: 'submitted', reviewNote: null, reviewedAt: null, createdAt: '2026-07-27T10:00:00Z',
}

const REJECTED = {
  ...SUBMITTED, id: 's0', status: 'rejected', reviewNote: '缺少门店实拍',
  reviewedAt: '2026-07-27T09:00:00Z', createdAt: '2026-07-27T08:00:00Z',
}

function stubFetch(listByCall: unknown[][]): { calls: { url: string; method: string; body?: string }[] } {
  const calls: { url: string; method: string; body?: string }[] = []
  let listIndex = 0
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method || 'GET'
    calls.push({ url, method, body: init?.body as string | undefined })
    const isList = method === 'GET'
    const data = isList
      ? { submissions: listByCall[Math.min(listIndex++, listByCall.length - 1)] }
      : SUBMITTED
    return {
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: true, data }),
    }
  }))
  return { calls }
}

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

function mountPanel(role: 'merchant' | 'recommender') {
  return mount(EngagementSubmissionPanel, {
    props: { taskId: 'task-1', applicationId: 'app-1', role },
  })
}

describe('EngagementSubmissionPanel 列表', () => {
  /** 响应是 {submissions:[...]}；直接当数组用会渲染成空列表。 */
  test('从 submissions 字段取数组并展示链接与状态', async () => {
    const { calls } = stubFetch([[SUBMITTED]])
    const wrapper = mountPanel('recommender')
    await flushPromises()

    expect(calls[0]).toMatchObject({ url: '/api/tasks/task-1/applications/app-1/submissions', method: 'GET' })
    expect(wrapper.text()).toContain('https://example.com/post/1')
    expect(wrapper.text()).toContain('待商家核验')
    expect(wrapper.text()).not.toContain('尚未提交履约凭证')
  })

  test('退回的交付物展示退回原因', async () => {
    stubFetch([[REJECTED]])
    const wrapper = mountPanel('recommender')
    await flushPromises()

    expect(wrapper.text()).toContain('已退回')
    expect(wrapper.text()).toContain('退回原因：缺少门店实拍')
  })

  test('商家侧空列表明确说明「在此之前无法确认履约」', async () => {
    stubFetch([[]])
    const wrapper = mountPanel('merchant')
    await flushPromises()

    expect(wrapper.text()).toContain('无法确认履约')
  })
})

describe('EngagementSubmissionPanel 提交', () => {
  test('推荐官提交发送 contentUrl + note 并重拉列表', async () => {
    const { calls } = stubFetch([[], [SUBMITTED]])
    const wrapper = mountPanel('recommender')
    await flushPromises()

    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('https://example.com/post/1')
    await inputs[1].setValue('已按要求发布')
    await wrapper.findAll('button').find((b) => b.text() === '提交履约')!.trigger('click')
    await flushPromises()

    const post = calls.find((c) => c.method === 'POST')!
    expect(post.url).toBe('/api/tasks/task-1/applications/app-1/submissions')
    expect(JSON.parse(post.body!)).toEqual({ contentUrl: 'https://example.com/post/1', note: '已按要求发布' })
    expect(wrapper.text()).toContain('已提交，等待商家核验')
  })

  /** 已有待核验的一份 → 后端 409；前端先把按钮禁掉并说明原因。 */
  test('已有待核验时提交按钮禁用', async () => {
    stubFetch([[SUBMITTED]])
    const wrapper = mountPanel('recommender')
    await flushPromises()

    await wrapper.findAll('input')[0].setValue('https://example.com/another')
    const button = wrapper.findAll('button').find((b) => b.text() === '提交履约')!

    expect(button.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('已有一份待商家核验')
  })

  test('推荐官侧没有「退回补交」', async () => {
    stubFetch([[SUBMITTED]])
    const wrapper = mountPanel('recommender')
    await flushPromises()

    expect(wrapper.findAll('button').some((b) => b.text() === '退回补交')).toBe(false)
  })
})

describe('EngagementSubmissionPanel 退回', () => {
  test('商家退回发送原因并重拉列表', async () => {
    const { calls } = stubFetch([[SUBMITTED], [REJECTED]])
    const wrapper = mountPanel('merchant')
    await flushPromises()

    await wrapper.findAll('input')[0].setValue('缺少门店实拍')
    await wrapper.findAll('button').find((b) => b.text() === '退回补交')!.trigger('click')
    await flushPromises()

    const post = calls.find((c) => c.method === 'POST')!
    expect(post.url).toBe('/api/tasks/task-1/applications/app-1/submissions/s1/reject')
    expect(JSON.parse(post.body!)).toEqual({ note: '缺少门店实拍' })
    expect(wrapper.text()).toContain('已退回，推荐官可修改后重新提交')
  })

  test('商家侧没有提交表单', async () => {
    stubFetch([[SUBMITTED]])
    const wrapper = mountPanel('merchant')
    await flushPromises()

    expect(wrapper.findAll('button').some((b) => b.text() === '提交履约')).toBe(false)
  })
})
