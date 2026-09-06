// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { useAuthStore } from '../stores/auth'
import { useDouyinSession } from './useDouyinSession'
import type { DouyinSessionState } from '../types/douyin'

/**
 * 抖音绑定会话账号边界（任务书 #82 C82-01，E09/E06/E05）：
 * - 换号清绑定态/二维码/错误并停扫码轮询，owner 镜像可观察；
 * - 旧轮询回调与迟到响应（含 authenticated 态）不写入新账号；
 * - 2000ms 轮询间隔、同账号续链、卸载 bump 与当前账号错误语义等既有行为保持。
 */

const userA = { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', email: 'a@qa.invalid', displayName: '甲', role: 'user' }
const userB = { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', email: 'b@qa.invalid', displayName: '乙', role: 'user' }

function sessionBody(overrides: Partial<DouyinSessionState> = {}): DouyinSessionState {
  return {
    status: overrides.status ?? 'missing',
    hasPersistedSession: overrides.hasPersistedSession ?? false,
    ...overrides,
  }
}

type Resolver = (data: unknown, ok?: boolean) => void

/** 手动放行式 fetch stub：每个请求挂起，直到测试按序 resolve。 */
function stubDeferredFetch(): { calls: { url: string; method: string }[]; resolvers: Resolver[] } {
  const calls: { url: string; method: string }[] = []
  const resolvers: Resolver[] = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    calls.push({ url: String(url), method: init?.method || 'GET' })
    return new Promise((resolve) => {
      resolvers.push((data: unknown, ok = true) => resolve({
        ok,
        status: ok ? 200 : 500,
        headers: { get: () => 'application/json' },
        json: async () => (ok ? { success: true, data } : { error: String(data) }),
        text: async () => JSON.stringify(ok ? { success: true, data } : { error: String(data) }),
      }))
    })
  }))
  return { calls, resolvers }
}

let douyin: ReturnType<typeof useDouyinSession>
let wrapper: ReturnType<typeof mount>

// onBeforeUnmount 需要组件实例：包一层壳组件挂载，行为与 VideoAnalysisView 内使用一致。
function mountSession(): void {
  wrapper = mount(defineComponent({
    setup() {
      douyin = useDouyinSession()
      return () => h('div')
    },
  }))
}

beforeEach(() => {
  mountSession()
})

afterEach(() => {
  useAuthStore().currentUser = null
  douyin.resetForAccount(null)
  wrapper.unmount()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('useDouyinSession 账号边界（任务书 #82 C82-01）', () => {
  it('A 已绑定 → 换 B：绑定态/二维码/错误清空、轮询停止、owner=B（E09）', async () => {
    const auth = useAuthStore()
    const { resolvers } = stubDeferredFetch()
    auth.currentUser = userA
    const refresh = douyin.refresh()
    resolvers[0](sessionBody({ status: 'authenticated', hasPersistedSession: true, lastAuthenticatedAt: '2026-09-06T00:00:00Z' }))
    await refresh
    expect(douyin.hasActiveSession.value).toBe(true)
    expect(douyin.ownerAccountId.value).toBe(userA.id)

    auth.currentUser = userB
    expect(douyin.ownerAccountId.value).toBe(userB.id)
    expect(douyin.state.value).toBeNull()
    expect(douyin.loading.value).toBe(false)
    expect(douyin.error.value).toBe('')
    expect(douyin.polling.value).toBe(false)
    expect(douyin.hasActiveSession.value).toBe(false)
  })

  it('A 的 refresh 迟到响应不写入 B——authenticated 态也不行（E09）', async () => {
    const auth = useAuthStore()
    const { resolvers } = stubDeferredFetch()
    auth.currentUser = userA
    const aRefresh = douyin.refresh()
    auth.currentUser = userB
    resolvers[0](sessionBody({ status: 'authenticated', hasPersistedSession: true }))
    await aRefresh
    expect(douyin.state.value).toBeNull()
    expect(douyin.ownerAccountId.value).toBe(userB.id)
  })

  it('扫码轮询中换号：轮询停止、迟到的 poll 响应不写入也不续链', async () => {
    vi.useFakeTimers()
    const auth = useAuthStore()
    const { calls, resolvers } = stubDeferredFetch()
    auth.currentUser = userA
    const starting = douyin.start()
    resolvers[0](sessionBody({ status: 'qr_ready', qrImageUrl: 'data:image/png;base64,qa-qr' }))
    await starting
    expect(douyin.polling.value).toBe(true)
    expect(douyin.state.value?.qrImageUrl).toBe('data:image/png;base64,qa-qr')

    await vi.advanceTimersByTimeAsync(2000) // 第一轮 poll 已发出、挂起
    expect(calls.filter((c) => c.url.includes('/api/douyin/session/poll'))).toHaveLength(1)

    auth.currentUser = userB
    expect(douyin.polling.value).toBe(false)
    expect(douyin.state.value).toBeNull()

    resolvers[1](sessionBody({ status: 'authenticated', hasPersistedSession: true })) // 迟到 poll 响应
    await vi.advanceTimersByTimeAsync(6000)
    expect(douyin.state.value).toBeNull() // 不写入 B
    expect(calls.filter((c) => c.url.includes('/api/douyin/session/poll'))).toHaveLength(1) // 不再续链
  })

  it('同账号轮询链正常续转：qr_ready → waiting_for_confirm → authenticated 停链（2000ms 间隔保持）', async () => {
    vi.useFakeTimers()
    const auth = useAuthStore()
    const { calls, resolvers } = stubDeferredFetch()
    auth.currentUser = userA
    const starting = douyin.start()
    resolvers[0](sessionBody({ status: 'qr_ready' }))
    await starting

    await vi.advanceTimersByTimeAsync(2000)
    resolvers[1](sessionBody({ status: 'waiting_for_confirm' }))
    await vi.advanceTimersByTimeAsync(0)
    expect(douyin.state.value?.status).toBe('waiting_for_confirm')
    expect(douyin.polling.value).toBe(true)

    await vi.advanceTimersByTimeAsync(2000)
    resolvers[2](sessionBody({ status: 'authenticated', hasPersistedSession: true }))
    await vi.advanceTimersByTimeAsync(0)
    expect(douyin.state.value?.status).toBe('authenticated')
    expect(douyin.polling.value).toBe(false)
    expect(douyin.hasActiveSession.value).toBe(true)
    expect(calls.map((c) => c.url)).toEqual([
      '/api/douyin/session/start',
      '/api/douyin/session/poll',
      '/api/douyin/session/poll',
    ])
  })

  it('logout 中换号：迟到响应不写 B、loading 不残留', async () => {
    const auth = useAuthStore()
    const { resolvers } = stubDeferredFetch()
    auth.currentUser = userA
    const loggingOut = douyin.logout()
    auth.currentUser = userB
    resolvers[0](sessionBody({ status: 'missing' }))
    await loggingOut
    expect(douyin.state.value).toBeNull()
    expect(douyin.loading.value).toBe(false)
    expect(douyin.ownerAccountId.value).toBe(userB.id)
  })

  it('当前账号失败语义保持：错误写入、不伪造成功（E05）', async () => {
    const auth = useAuthStore()
    const { resolvers } = stubDeferredFetch()
    auth.currentUser = userA
    const refreshing = douyin.refresh()
    resolvers[0](null, false) // 500
    await expect(refreshing).resolves.toBeNull()
    expect(douyin.error.value).not.toBe('')
    expect(douyin.loading.value).toBe(false)
  })

  it('卸载 bump：停轮询且在途请求不回写（E06，既有行为保持）', async () => {
    vi.useFakeTimers()
    const auth = useAuthStore()
    const { calls, resolvers } = stubDeferredFetch()
    auth.currentUser = userA
    const starting = douyin.start()
    resolvers[0](sessionBody({ status: 'qr_ready' }))
    await starting
    wrapper.unmount()
    expect(douyin.polling.value).toBe(false)
    await vi.advanceTimersByTimeAsync(4000)
    expect(calls.filter((c) => c.url.includes('/api/douyin/session/poll'))).toHaveLength(0)
  })
})
