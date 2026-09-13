// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import WechatDraftSyncPanel from './WechatDraftSyncPanel.vue'
import type { WechatDraftSync } from '../creation/useWechatDraftSync'

/**
 * 任务书 #101 C101-22（TC101-105~108 / SC101-10）：同步状态面板。
 * 真实状态文案（无「已发布」与伪公开链接）、unknown 只提供核实、重复点击防抖、
 * 候选核实分支与版本冲突提示。弹窗经 Teleport 的部分由 WechatDraftPreview 集成在
 * DeliveryPanel 测试覆盖；本文件聚焦状态/核实分支。
 */

const fetchMock = vi.fn()
beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  fetchMock.mockImplementation(async () => new Response(JSON.stringify({ success: true, data: {} }),
    { headers: { 'Content-Type': 'application/json' } }))
})
afterEach(() => vi.unstubAllGlobals())
enableAutoUnmount(afterEach)

function ok(data: unknown): Response {
  return new Response(JSON.stringify({ success: true, data }), { headers: { 'Content-Type': 'application/json' } })
}

function sync(overrides: Partial<WechatDraftSync> = {}): WechatDraftSync {
  return {
    id: 'sync-1', requestId: 'req-1', accountId: 'acct-1', draftId: 'draft-1', draftVersion: 3,
    state: 'succeeded', externalDraftMediaId: 'MID-1', payloadHash: 'h', version: 5,
    createdAt: '2026-09-14T00:00:00Z', verifiedAt: '2026-09-14T00:01:00Z', error: null, ...overrides,
  }
}

function mountPanel(props: { sync?: WechatDraftSync | null; history?: WechatDraftSync[] }) {
  return mount(WechatDraftSyncPanel, {
    attachTo: document.body,
    props: { sync: props.sync ?? null, history: props.history ?? [], ...{} },
    global: { stubs: { teleport: true } },
  })
}

describe('WechatDraftSyncPanel', () => {
  test('TC101-107：成功文案只说已存入草稿箱，绝无「已发布」或公开链接', () => {
    const wrapper = mountPanel({ sync: sync({ state: 'succeeded' }) })
    expect(wrapper.get('[data-test="wechat-sync-state"]').text()).toBe('已存入草稿箱')
    expect(wrapper.get('[data-test="wechat-sync-done"]').text()).toContain('草稿箱')
    expect(wrapper.text()).not.toContain('已发布')
    expect(wrapper.html()).not.toMatch(/weixin\.qq\.com\/s|mp\.weixin\.qq\.com/)
    // 不编造外链（无任何指向外部的 <a>）
    expect(wrapper.findAll('a')).toHaveLength(0)
  })

  test('失败态展示服务端错误文案，不伪装成功', () => {
    const wrapper = mountPanel({
      sync: sync({ state: 'failed', error: { code: 'STUDIO_CHANNEL_CONTENT_MISMATCH', message: '外部草稿与快照不一致' } }),
    })
    expect(wrapper.get('[data-test="wechat-sync-state"]').text()).toBe('失败')
    expect(wrapper.get('[data-test="wechat-sync-error"]').text()).toContain('不一致')
  })

  test('TC101-106：unknown 提供核实（不自动重发）；候选由服务端匹配标记', async () => {
    fetchMock.mockImplementation(async (url: string) => {
      if (String(url).endsWith('/candidates')) {
        return ok({ items: [
          { externalDraftMediaId: 'MID-A', title: '草稿 A', updatedAt: '1726262400', contentMatches: true },
          { externalDraftMediaId: 'MID-B', title: '草稿 B', updatedAt: '1726262500', contentMatches: false },
        ], searchedCount: 2, hasMore: false })
      }
      return ok(sync({ state: 'succeeded', externalDraftMediaId: 'MID-A', version: 6 }))
    })
    const wrapper = mountPanel({ sync: sync({ state: 'unknown' }) })
    await wrapper.get('[data-test="wechat-sync-candidates"]').trigger('click')
    await flushPromises()
    const items = wrapper.findAll('[data-test^="wechat-sync-verify-"]')
    expect(items).toHaveLength(2)
    expect(wrapper.text()).toContain('内容一致')
    expect(wrapper.text()).toContain('内容不一致')
    // 无默认选中：核实是逐项显式动作
    expect(wrapper.find('input[type=radio]').exists()).toBe(false)

    await wrapper.get('[data-test="wechat-sync-verify-MID-A"]').trigger('click')
    await flushPromises()
    expect(String(fetchMock.mock.calls[1][0])).toContain('/reconcile')
    expect(JSON.parse(String(fetchMock.mock.calls[1][1]?.body))).toMatchObject({ externalDraftMediaId: 'MID-A' })
    // 核实成功后 updated 事件交给父层刷新
    expect(wrapper.emitted('updated')).toBeTruthy()
  })

  test('TC101-108：重复点击只发一次核实请求；取消进行中同步走 cancel', async () => {
    fetchMock.mockImplementation(async (url: string) => {
      if (String(url).endsWith('/candidates')) {
        return ok({ items: [{ externalDraftMediaId: 'MID-A', title: 'A', updatedAt: '1', contentMatches: true }],
          searchedCount: 1, hasMore: false })
      }
      // reconcile 慢返回：双击窗口内只允许一个在途请求
      await new Promise(resolve => { setTimeout(resolve, 80) })
      return ok(sync({ state: 'succeeded', externalDraftMediaId: 'MID-A', version: 6 }))
    })
    const wrapper = mountPanel({ sync: sync({ state: 'unknown' }) })
    await wrapper.get('[data-test="wechat-sync-candidates"]').trigger('click')
    await flushPromises()
    const button = wrapper.get('[data-test="wechat-sync-verify-MID-A"]')
    await button.trigger('click')
    await button.trigger('click') // 双击
    await flushPromises()
    const reconciles = fetchMock.mock.calls.filter(([url]) => String(url).includes('/reconcile'))
    expect(reconciles.length).toBe(1)

    const cancelling = mountPanel({ sync: sync({ state: 'uploading', version: 2 }) })
    await cancelling.get('[data-test="wechat-sync-cancel"]').trigger('click')
    await flushPromises()
    const calls = fetchMock.mock.calls; expect(String(calls[calls.length - 1]?.[0])).toContain('/cancel')
  })

  test('快照版本提示绑定冻结版本（TC101-105 前端口径）', () => {
    const wrapper = mountPanel({ sync: sync({ state: 'verifying', draftVersion: 3 }) })
    expect(wrapper.get('[data-test="wechat-sync-version"]').text()).toContain('v3')
    expect(wrapper.get('[data-test="wechat-sync-version"]').text()).toContain('不影响本次同步')
  })

  test('历史列表展示多版本记录（重开恢复状态，TC101-108）', () => {
    const wrapper = mountPanel({
      sync: sync({ state: 'unknown', version: 7 }),
      history: [sync({ state: 'unknown', version: 7 }), sync({ state: 'succeeded', version: 5, draftVersion: 2 })],
    })
    expect(wrapper.get('[data-test="wechat-sync-history"]').text()).toContain('历史同步（2）')
    expect(wrapper.get('[data-test="wechat-sync-history"]').text()).toContain('v2')
  })
})
