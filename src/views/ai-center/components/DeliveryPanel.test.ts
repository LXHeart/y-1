// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import DeliveryPanel from './DeliveryPanel.vue'

/**
 * DeliveryPanel（AI内容中心改造-02 §2.4/2.5）：平台编辑稿分字段 + readiness + 导出。
 * 修改字段只 emit patch，不触发任何生成；导出走 /exports 并渲染媒体链接。
 */

const fetchMock = vi.fn()
beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => vi.unstubAllGlobals())
enableAutoUnmount(afterEach)

describe('DeliveryPanel', () => {
  test('公众号显示摘要字段，小红书显示话题字段并解析 # 前缀', async () => {
    const wechat = mount(DeliveryPanel, { props: { modelValue: { titleOrOpening: 't', bodyOrDescription: 'b' }, platform: 'wechat-official' } })
    expect(wechat.find('[data-test="delivery-summary"]').exists()).toBe(true)
    expect(wechat.find('[data-test="delivery-topics"]').exists()).toBe(false)

    const xhs = mount(DeliveryPanel, { props: { modelValue: { titleOrOpening: 't', bodyOrDescription: 'b' }, platform: 'xiaohongshu' } })
    expect(xhs.find('[data-test="delivery-topics"]').exists()).toBe(true)
    await xhs.get('[data-test="delivery-topics"]').setValue('#探店 #开业')
    const updates = xhs.emitted('update:modelValue') ?? []
    const last = updates[updates.length - 1]
    expect(last?.[0]).toMatchObject({ topics: ['探店', '开业'] })
  })

  test('readiness 展示缺项；声明 pending 标记待补', () => {
    const wrapper = mount(DeliveryPanel, { props: {
      modelValue: { titleOrOpening: '标题', bodyOrDescription: '正文', platform: 'xiaohongshu' }, platform: 'xiaohongshu',
    } })
    const items = wrapper.findAll('[data-test="delivery-readiness"] li')
    expect(items.some(item => item.text().includes('话题') && item.text().includes('待补'))).toBe(true)
    expect(items.some(item => item.text().includes('声明状态') && item.text().includes('待补'))).toBe(true)
  })

  test('导出：POST /exports 下载 manifest 并列出媒体链接；失效项标注', async () => {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ success: true, data: {
      draftId: 'draft-1', version: 3, expiresAt: '2026-09-09T00:00:00Z',
      manifest: { title: '标题' },
      downloads: [
        { refType: 'media', id: 'm-1', role: 'card', position: 1, url: 'https://storage.test/m1?sig=1' },
        { refType: 'media', id: 'm-2', role: 'card', position: 2, unavailable: 'expired' },
      ],
    } }), { headers: { 'Content-Type': 'application/json' } }))
    const wrapper = mount(DeliveryPanel, { props: {
      modelValue: { titleOrOpening: '标题', bodyOrDescription: '正文' }, platform: 'xiaohongshu', draftId: 'draft-1',
    } })
    await wrapper.get('[data-test="delivery-export"]').trigger('click')
    await flushPromises()

    expect(fetchMock.mock.calls[0][0]).toBe('/api/creation-drafts/draft-1/exports')
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toMatchObject({ format: 'bundle-manifest' })
    const links = wrapper.findAll('[data-test="delivery-downloads"] a')
    expect(links).toHaveLength(1)
    expect(links[0].attributes('href')).toContain('https://storage.test/m1')
    expect(wrapper.text()).toContain('已失效，重新导出可按授权重取')
  })

  test('导出失败显示错误，不触发任何生成请求', async () => {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ success: false, error: '草稿不存在' }), { status: 404 }))
    const wrapper = mount(DeliveryPanel, { props: {
      modelValue: { titleOrOpening: '标题', bodyOrDescription: '正文' }, platform: 'xiaohongshu', draftId: 'draft-x',
    } })
    await wrapper.get('[data-test="delivery-export"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="delivery-export-error"]').text()).toContain('草稿不存在')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
