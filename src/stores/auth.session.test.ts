import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'
import type { AuthUser } from '../types/auth'
import { useAccountSessionStore } from './account-session'

/**
 * TC79-01B（任务书 #79 C79-01）：迟到 /auth/me 的回归保护。
 * mock 在 fetch 边界（§12.2），用 deferred 控制释放顺序，不靠 sleep。
 */
const userA: AuthUser = { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', email: 'a@qa.invalid', displayName: '甲', role: 'user' }
const userB: AuthUser = { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', email: 'b@qa.invalid', displayName: '乙', role: 'user' }

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (reason?: unknown) => void } {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function makeAuth() {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  const session = useAccountSessionStore()
  return { auth, session }
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn())
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('迟到 /auth/me 不覆盖新认证（TC79-01B）', () => {
  it.each([200, 401, 403, 500, 'network'] as const)(
    '旧 me 以 %s 迟到：login(B) 成功后释放，B 的用户/错误/loading 均不被旧响应改动',
    async (releaseKind) => {
      const { auth } = makeAuth()
      const meDeferred = deferred<Response>()
      const log: string[] = []
      vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input)
        log.push(`${init?.method ?? 'GET'} ${url}`)
        if (url === '/api/auth/me') return meDeferred.promise
        if (url === '/api/auth/login') return json({ success: true, data: { user: userB } })
        throw new Error(`未登记请求: ${url}`)
      }))

      const oldMe = auth.loadCurrentUser()
      const loggedIn = auth.login({ email: 'b@qa.invalid', password: 'secret' })
      await loggedIn
      expect(auth.currentUser?.id).toBe(userB.id)

      if (releaseKind === 200) meDeferred.resolve(json({ success: true, data: { user: userA } }))
      else if (releaseKind === 'network') meDeferred.reject(new TypeError('Failed to fetch'))
      else meDeferred.resolve(json({ success: false, error: `旧会话错误（${releaseKind}）` }, releaseKind))

      await oldMe
      // B 不被旧 me 的数据/错误/finally 影响
      expect(auth.currentUser?.id).toBe(userB.id)
      expect(auth.loadError).toBe('')
      expect(auth.loading).toBe(false)
      // 只发生原请求：me 一次 + login POST 一次，无额外认证 POST
      expect(log.filter((entry) => entry.startsWith('POST'))).toEqual(['POST /api/auth/login'])
    },
  )

  it('E15：me 与 login 交错的受控逆序——旧 me 完成被丢弃后，新一轮 me 才发起并独占写权', async () => {
    const { auth } = makeAuth()
    const me1 = deferred<Response>()
    const meCalls = [me1]
    const meFetchLog: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/auth/me') {
        meFetchLog.push('me')
        const next = meCalls.shift()
        return next ? next.promise : json({ success: true, data: { user: userB } })
      }
      if (url === '/api/auth/login') return json({ success: true, data: { user: userB } })
      throw new Error('未登记请求')
    }))

    const first = auth.loadCurrentUser()
    await auth.login({ email: 'b@qa.invalid', password: 'secret' })
    expect(auth.currentUser?.id).toBe(userB.id)

    // 旧 me（stale）完成：数据丢弃，loading 释放，为下一轮 me 让路
    me1.resolve(json({ success: true, data: { user: userA } }))
    await first
    expect(auth.currentUser?.id).toBe(userB.id)
    expect(auth.loading).toBe(false)

    // 新一轮 me 正常发起：响应即使晚于其发起时刻到达，也只有它可写
    const me2 = deferred<Response>()
    meCalls.push(me2)
    const second = auth.loadCurrentUser(true)
    expect(auth.loading).toBe(true)
    me2.resolve(json({ success: true, data: { user: userB } }))
    await second
    expect(auth.currentUser?.id).toBe(userB.id)
    expect(auth.loading).toBe(false)
    expect(auth.loadError).toBe('')
    expect(meFetchLog).toHaveLength(2)
  })

  it('旧 me 完成后 loading 必然释放：后续 loadCurrentUser 不被早退闸永久卡死', async () => {
    const { auth } = makeAuth()
    const me1 = deferred<Response>()
    const meQueue = [me1]
    const meFetchLog: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/auth/me') {
        meFetchLog.push('me')
        const next = meQueue.shift()
        return next ? next.promise : json({ success: true, data: { user: userB } })
      }
      if (url === '/api/auth/login') return json({ success: true, data: { user: userB } })
      throw new Error('未登记请求')
    }))

    const first = auth.loadCurrentUser()
    await auth.login({ email: 'b@qa.invalid', password: 'secret' })

    // 旧 me 以网络异常结束（catch+finally 都要走 stale 分支）
    me1.reject(new TypeError('Failed to fetch'))
    await first
    expect(auth.loading).toBe(false)
    expect(auth.loadError).toBe('')

    // 此后新 me 可以正常发起并完成
    const second = auth.loadCurrentUser(true)
    await second
    expect(auth.currentUser?.id).toBe(userB.id)
    expect(meFetchLog).toHaveLength(2)
  })

  it('E08/E13：me 返回 user=null/缺 id/id 空白/id 非_string 时不标账号就绪', async () => {
    const cases: Array<{ label: string; user: unknown }> = [
      { label: 'user null', user: null },
      { label: '缺 id', user: { email: 'x@qa.invalid', role: 'user' } },
      { label: 'id 空串', user: { id: '', email: 'x@qa.invalid', role: 'user' } },
      { label: 'id 空白', user: { id: '   ', email: 'x@qa.invalid', role: 'user' } },
      { label: 'id 数值', user: { id: 123, email: 'x@qa.invalid', role: 'user' } },
    ]
    for (const testCase of cases) {
      const { auth } = makeAuth()
      vi.stubGlobal('fetch', vi.fn(async () => json({ success: true, data: { user: testCase.user } })))
      const ok = await auth.loadCurrentUser(true)
      expect(ok, testCase.label).toBe(false)
      expect(auth.isAuthenticated, testCase.label).toBe(false)
      expect(auth.currentUser, testCase.label).toBeNull()
      expect(auth.loaded, testCase.label).toBe(false)
      expect(auth.loadError, testCase.label).toContain('当前无法确认登录状态')
    }
  })

  it('当前 me 401 进入匿名（既有语义保留）；403 不当已登录并保留错误', async () => {
    const anonymous = makeAuth()
    vi.stubGlobal('fetch', vi.fn(async () => json({ success: false }, 401)))
    await expect(anonymous.auth.loadCurrentUser(true)).resolves.toBe(true)
    expect(anonymous.auth.currentUser).toBeNull()
    expect(anonymous.auth.loaded).toBe(true)

    const forbidden = makeAuth()
    vi.stubGlobal('fetch', vi.fn(async () => json({ success: false, error: '禁止访问' }, 403)))
    await expect(forbidden.auth.loadCurrentUser(true)).resolves.toBe(false)
    expect(forbidden.auth.isAuthenticated).toBe(false)
    expect(forbidden.auth.loadError).toBe('禁止访问')
  })

  it('A→B→A：每次登录推进 session epoch，旧票一律失效', async () => {
    const { auth, session } = makeAuth()
    const logins = [userA, userB, userA]
    for (const expected of logins) {
      vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
        if (String(input) === '/api/auth/login') return json({ success: true, data: { user: expected } })
        throw new Error('未登记请求')
      }))
      await auth.login({ email: expected.email, password: 'secret' })
    }
    expect(auth.currentUser?.id).toBe(userA.id)
    expect(session.epoch).toBe(3)
    const finalTicket = session.capture()
    expect(finalTicket.accountId).toBe(userA.id)
    expect(finalTicket.epoch).toBe(3)
    expect(session.isCurrent({ accountId: userA.id, epoch: 1, signal: AbortSignal.abort() })).toBe(false)
  })

  it('E16：logout 网络失败保留当前账号，不伪造服务端已登出', async () => {
    const { auth } = makeAuth()
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/auth/me') return json({ success: true, data: { user: userA } })
      if (url === '/api/auth/logout') return Promise.reject(new TypeError('Failed to fetch'))
      throw new Error('未登记请求')
    }))
    await auth.loadCurrentUser(true)
    await expect(auth.logout()).resolves.toBe(false)
    expect(auth.currentUser?.id).toBe(userA.id)
    expect(auth.logoutError).toBeTruthy()
  })

  it('旧 me 迟到也不复活已 logout 的匿名会话', async () => {
    const { auth } = makeAuth()
    const meDeferred = deferred<Response>()
    let meCount = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/auth/me') {
        meCount += 1
        return meCount === 1 ? meDeferred.promise : json({ success: false }, 401)
      }
      if (url === '/api/auth/logout') return json({ success: true, data: { loggedOut: true } })
      throw new Error('未登记请求')
    }))

    const oldMe = auth.loadCurrentUser()
    await auth.logout()
    expect(auth.currentUser).toBeNull()

    meDeferred.resolve(json({ success: true, data: { user: userA } }))
    await oldMe
    expect(auth.currentUser).toBeNull()
    expect(auth.loaded).toBe(true)
  })
})
