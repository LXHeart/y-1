// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { defineComponent } from 'vue'
import { GrasslandHttpError } from '../../../composables/grassland-http'
import {
  listWechatAccounts, useWechatAccounts, validateWechatDisplayName, validateWechatSecret,
  WECHAT_APP_ID_PATTERN, wechatActionError,
} from './useWechatAccounts'

/**
 * 任务书 #101 C101-20：useWechatAccounts——迟到响应丢弃（epoch）、账号切换隔离、
 * 输入契约前置校验、HTTP 错误文案映射。
 */

const fetchMock = vi.fn()
beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => vi.unstubAllGlobals())
enableAutoUnmount(afterEach)

function ok(data: unknown): Response {
  return new Response(JSON.stringify({ success: true, data }), { headers: { 'Content-Type': 'application/json' } })
}

function harness() {
  const state = useWechatAccounts()
  mount(defineComponent({ setup() { return () => null }, mounted() { void state.refresh() } }))
  return state
}

describe('useWechatAccounts', () => {
  test('输入契约：appId 形态 / displayName / secret 可见字符规则', () => {
    expect(WECHAT_APP_ID_PATTERN.test('wxaaaa000000000001')).toBe(true)
    expect(WECHAT_APP_ID_PATTERN.test('wx-aaaa0000000000')).toBe(false)
    expect(WECHAT_APP_ID_PATTERN.test('aaaa000000000001')).toBe(false)
    expect(validateWechatDisplayName('主号')).toBeNull()
    expect(validateWechatDisplayName('  ')).toContain('账号名称')
    expect(validateWechatDisplayName('x'.repeat(81))).toContain('80')
    expect(validateWechatSecret('short')).toContain('16')
    expect(validateWechatSecret('a'.repeat(513))).toContain('512')
    expect(validateWechatSecret('has space in it here!!')).toContain('空格')
    expect(validateWechatSecret('valid-secret-000001')).toBeNull()
  })

  test('迟到响应丢弃：慢的旧请求不覆盖新结果', async () => {
    let finishSlow!: (value: Response) => void
    let listCalls = 0
    fetchMock.mockImplementation(async (url: string) => {
      if (String(url).includes('limit=20')) {
        listCalls += 1
        if (listCalls === 1) return new Promise<Response>(resolve => { finishSlow = resolve }) // 旧（慢）
        return ok({ items: [{ id: 'acct-new', displayName: '新', appId: 'wxaaaa000000000002',   // 新（快）
          state: 'active', version: 1, verifiedAt: null, error: null }], nextCursor: null })
      }
      return ok({})
    })
    const state = harness() // refresh #1（挂起）
    await flushPromises()
    void state.refresh()    // refresh #2（快，立即返回）
    await flushPromises()
    expect(state.accounts.value.map((item) => item.id)).toEqual(['acct-new'])
    // 旧请求此时才返回——epoch 已推进，必须被丢弃
    finishSlow(ok({ items: [{ id: 'acct-stale', displayName: '旧', appId: 'wxaaaa000000000003',
      state: 'unverified', version: 1, verifiedAt: null, error: null }], nextCursor: null }))
    await flushPromises()
    expect(state.accounts.value.map((item) => item.id)).toEqual(['acct-new'])
    expect(state.loading.value).toBe(false)
  })

  test('账号切换（重新挂载新实例）：旧实例状态随组件销毁，互不串写', async () => {
    fetchMock.mockResolvedValue(ok({ items: [{ id: 'acct-a', displayName: 'A', appId: 'wxaaaa000000000004',
      state: 'active', version: 1, verifiedAt: null, error: null }], nextCursor: null }))
    const first = harness()
    await flushPromises()
    expect(first.accounts.value[0]?.id).toBe('acct-a')

    fetchMock.mockResolvedValue(ok({ items: [{ id: 'acct-b', displayName: 'B', appId: 'wxaaaa000000000005',
      state: 'unverified', version: 1, verifiedAt: null, error: null }], nextCursor: null }))
    const second = harness()
    await flushPromises()
    expect(second.accounts.value[0]?.id).toBe('acct-b')
    // 旧实例快照不被新实例改写（readonly 隔离）
    expect(first.accounts.value[0]?.id).toBe('acct-a')
  })

  test('错误映射：版本冲突→提示刷新；AppID 被占→不泄露他人信息', () => {
    const conflict = wechatActionError(
      new GrasslandHttpError(409, '连接版本已变化', 'STUDIO_VERSION_CONFLICT'), 'fallback')
    expect(conflict.versionConflict).toBe(true)
    expect(conflict.message).toContain('刷新')

    const bound = wechatActionError(
      new GrasslandHttpError(409, '该 AppID 已被其他账号绑定', 'STUDIO_CHANNEL_ACCOUNT_BOUND'), 'fallback')
    expect(bound.message).toContain('其他账号绑定')
    expect(bound.message).not.toContain('owner')

    expect(wechatActionError(new Error('网络断开'), '备用').message).toBe('网络断开')
    expect(wechatActionError('not-an-error' as unknown as Error, '备用').message).toBe('备用')
  })

  test('listWechatAccounts 走 owner 端点 GET', async () => {
    fetchMock.mockResolvedValueOnce(ok({ items: [], nextCursor: null }))
    await listWechatAccounts()
    expect(fetchMock.mock.calls[0][0]).toBe('/api/creation-channels/wechat/accounts?limit=20')
    expect(fetchMock.mock.calls[0][1]?.method).toBe('GET')
  })
})
