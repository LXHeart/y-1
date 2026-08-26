// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import HomepageHotConfigPanel from './HomepageHotConfigPanel.vue'

/**
 * 治理台「首页热点数据源」面板（任务书 #47 S7b / D18①）：
 * 平台级配置读写 + token 三态（不传=保持 / 空格=清空 / 新值=替换）+ 乐观锁版本推进。
 */

const calls: Array<{ url: string; init?: RequestInit }> = []

function stubFetch(config: Record<string, unknown>, putResponse?: Record<string, unknown> | { __status: number; __body: unknown }) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, init })
    if (init?.method === 'PUT') {
      if (putResponse && '__status' in putResponse) {
        return {
          ok: false, status: putResponse.__status,
          headers: { get: () => 'application/json' },
          json: async () => putResponse.__body,
        }
      }
      return {
        ok: true, status: 200, headers: { get: () => 'application/json' },
        json: async () => ({ success: true, data: putResponse ?? config }),
      }
    }
    return {
      ok: true, status: 200, headers: { get: () => 'application/json' },
      json: async () => ({ success: true, data: config }),
    }
  }))
}

beforeEach(() => { calls.length = 0 })
afterEach(() => { vi.unstubAllGlobals() })

describe('HomepageHotConfigPanel 治理台平台配置', () => {
  test('加载默认视图：60s、未配置版本为 0、不出现 token 区', async () => {
    stubFetch({ provider: '60s', alapiTokenMasked: null, hasAlapiToken: false, version: 0, updatedBy: null, updatedAt: null })
    const wrapper = mount(HomepageHotConfigPanel)
    await flushPromises()

    expect(calls.map((c) => c.url)).toEqual(['/api/admin/homepage/hot-config'])
    expect(wrapper.text()).toContain('尚未配置')
    expect(wrapper.find('#alapi-token-input').exists()).toBe(false)
  })

  test('切 ALAPI 显示 token 区；保存带 expectedVersion 与新 token；成功后版本推进', async () => {
    stubFetch(
      { provider: 'alapi', alapiTokenMasked: 'sk-***cdef', hasAlapiToken: true, version: 3, updatedBy: 'aabbccdd-1234', updatedAt: '2026-08-27T10:00:00Z' },
      { provider: 'alapi', alapiTokenMasked: 'sk-***zzzz', hasAlapiToken: true, version: 4, updatedBy: 'aabbccdd-1234', updatedAt: '2026-08-27T11:00:00Z' },
    )
    const wrapper = mount(HomepageHotConfigPanel)
    await flushPromises()

    expect(wrapper.text()).toContain('sk-***cdef')
    await wrapper.get('[data-action="use-alapi"]').trigger('click')
    await wrapper.get('#alapi-token-input').setValue('sk-new-token-value')
    await wrapper.get('[data-action="save-config"]').trigger('click')
    await flushPromises()

    const put = calls.find((c) => c.init?.method === 'PUT')!
    expect(put.init?.method).toBe('PUT')
    const body = JSON.parse(String(put.init?.body))
    expect(body).toEqual({ provider: 'alapi', alapiToken: 'sk-new-token-value', expectedVersion: 3 })
    expect(wrapper.text()).toContain('v4')
    expect(wrapper.text()).toContain('sk-***zzzz')
  })

  test('token 留空保存 = 不传字段（保持不变）；输入空格 = 传空格（清空）', async () => {
    stubFetch({ provider: 'alapi', alapiTokenMasked: 'sk-***cdef', hasAlapiToken: true, version: 1, updatedBy: null, updatedAt: null })
    const wrapper = mount(HomepageHotConfigPanel)
    await flushPromises()

    // 留空 → 不传字段
    await wrapper.get('[data-action="save-config"]').trigger('click')
    await flushPromises()
    let body = JSON.parse(String(calls.find((c) => c.init?.method === 'PUT')!.init?.body))
    expect(body).toEqual({ provider: 'alapi', expectedVersion: 1 })

    // 空格 → 显式传空格（后端清空）
    calls.length = 0
    await wrapper.get('#alapi-token-input').setValue(' ')
    await wrapper.get('[data-action="save-config"]').trigger('click')
    await flushPromises()
    body = JSON.parse(String(calls.find((c) => c.init?.method === 'PUT')!.init?.body))
    expect(body).toEqual({ provider: 'alapi', alapiToken: ' ', expectedVersion: 1 })
  })

  test('保存失败（409 等）显示后端错误文案，不吞', async () => {
    stubFetch(
      { provider: '60s', alapiTokenMasked: null, hasAlapiToken: false, version: 2, updatedBy: null, updatedAt: null },
      { __status: 409, __body: { success: false, error: '配置已被其他管理员修改，请刷新后重试' } },
    )
    const wrapper = mount(HomepageHotConfigPanel)
    await flushPromises()

    await wrapper.get('[data-action="save-config"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('配置已被其他管理员修改')
  })
})
