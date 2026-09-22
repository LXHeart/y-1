import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'
import type { AuthUser } from '../types/auth'
import { normalizeAccountId, useAccountSessionStore } from './account-session'
import { registerAccountKey, readAccountKey } from '../lib/account-private-cache'

/**
 * TC79-01A（任务书 #79 C79-01）：账号票据 store 的归属/代次语义。
 * fixture 取 §12.2 固定合成账号；两个独立 Pinia 验证互不共享。
 */
const userA: AuthUser = { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', email: 'a@qa.invalid', displayName: '甲', role: 'user' }
const userB: AuthUser = { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', email: 'b@qa.invalid', displayName: '乙', role: 'user' }

const pinias: Array<ReturnType<typeof createPinia>> = []

function makeSession() {
  const pinia = createPinia()
  pinias.push(pinia)
  setActivePinia(pinia)
  const auth = useAuthStore()
  const session = useAccountSessionStore()
  return { pinia, auth, session }
}

const fetchMock = vi.fn(() => Promise.reject(new Error('account-session 不应发起网络请求')))

beforeEach(() => {
  fetchMock.mockClear()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  // 统一销毁：释放账号缓存激活（listener 引用计数归零、模块级 channel 复位），
  // 避免跨用例泄漏模块状态（E12 用例自行 dispose 幂等无害）。
  while (pinias.length > 0) disposePinia(pinias.pop()!)
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('account-session store（TC79-01A）', () => {
  it('匿名起步 epoch=0；首次 A 只产生一次 owner 变化并使旧票失效', () => {
    const { auth, session } = makeSession()

    expect(session.ownerAccountId).toBeNull()
    expect(session.epoch).toBe(0)
    const anonymousTicket = session.capture()
    expect(session.isCurrent(anonymousTicket)).toBe(true)

    auth.currentUser = userA
    expect(session.ownerAccountId).toBe(userA.id)
    expect(session.epoch).toBe(1)
    expect(anonymousTicket.signal.aborted).toBe(true)
    expect(session.isCurrent(anonymousTicket)).toBe(false)

    const ticketA = session.capture()
    expect(ticketA.accountId).toBe(userA.id)
    expect(ticketA.epoch).toBe(1)
    expect(session.isCurrent(ticketA)).toBe(true)
  })

  it('E03：同 id 的资料更新（换昵称/邮箱）不增 epoch，票仍有效', () => {
    const { auth, session } = makeSession()
    auth.currentUser = userA
    const ticketA = session.capture()

    auth.currentUser = { ...userA, email: 'a2@qa.invalid', displayName: '甲二' }
    expect(session.epoch).toBe(1)
    expect(session.ownerAccountId).toBe(userA.id)
    expect(session.isCurrent(ticketA)).toBe(true)
  })

  it('E01/E11：A→null→B→A 每次变更都失效旧票并递增 epoch；A→B→A 不复活第一个 A 的票', () => {
    const { auth, session } = makeSession()
    auth.currentUser = userA
    const firstTicketA = session.capture()
    expect(session.epoch).toBe(1)

    auth.currentUser = null
    expect(session.epoch).toBe(2)
    expect(session.isCurrent(firstTicketA)).toBe(false)
    expect(firstTicketA.signal.aborted).toBe(true)

    auth.currentUser = userB
    expect(session.epoch).toBe(3)
    const ticketB = session.capture()

    auth.currentUser = userA
    expect(session.epoch).toBe(4)
    // E09：epoch 旧但 id 相同仍拒绝
    expect(session.isCurrent(firstTicketA)).toBe(false)
    expect(session.isCurrent(ticketB)).toBe(false)
    expect(ticketB.signal.aborted).toBe(true)
    const secondTicketA = session.capture()
    expect(secondTicketA.epoch).toBe(4)
    expect(secondTicketA.accountId).toBe(userA.id)
    expect(session.isCurrent(secondTicketA)).toBe(true)
  })

  it('E13：null/缺 id/空白 id/非 string 不作为有效 owner（一律匿名）', () => {
    const { auth, session } = makeSession()
    auth.currentUser = userA
    expect(session.epoch).toBe(1)

    auth.currentUser = null
    expect(session.ownerAccountId).toBeNull()
    auth.currentUser = { email: 'x@qa.invalid', role: 'user' } as unknown as AuthUser
    expect(session.ownerAccountId).toBeNull()
    auth.currentUser = { ...userA, id: '' }
    expect(session.ownerAccountId).toBeNull()
    auth.currentUser = { ...userA, id: '   ' }
    expect(session.ownerAccountId).toBeNull()
    auth.currentUser = { ...userA, id: 123 } as unknown as AuthUser
    expect(session.ownerAccountId).toBeNull()
    // 非法值与匿名同侧：epoch 只在 A→匿名侧切换时递增一次，此后不因非法值再变
    expect(session.epoch).toBe(2)

    expect(normalizeAccountId(undefined)).toBeNull()
    expect(normalizeAccountId('ok-id')).toBe('ok-id')
  })

  it('E14/E21：epoch 是单调计数器，固定时钟下照样递增（不以时间做相等判定）', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-09-05T16:00:00.000Z'))
    const { auth, session } = makeSession()
    auth.currentUser = userA
    const e1 = session.epoch
    vi.advanceTimersByTime(0)
    auth.currentUser = null
    const e2 = session.epoch
    expect(e2).toBeGreaterThan(e1)
    expect(Date.now()).toBe(new Date('2026-09-05T16:00:00.000Z').getTime())
    auth.currentUser = userB
    expect(session.epoch).toBeGreaterThan(e2)
  })

  it('E10：第二个 Pinia 保持匿名不受第一个影响；销毁重建后从匿名 epoch=0 重新装载', () => {
    const first = makeSession()
    first.auth.currentUser = userA
    expect(first.session.epoch).toBe(1)

    const second = makeSession()
    expect(second.session.ownerAccountId).toBeNull()
    expect(second.session.epoch).toBe(0)

    disposePinia(second.pinia)
    const third = makeSession()
    expect(third.session.ownerAccountId).toBeNull()
    expect(third.session.epoch).toBe(0)
    third.auth.currentUser = userB
    expect(third.session.ownerAccountId).toBe(userB.id)
    expect(first.session.ownerAccountId).toBe(userA.id)
    disposePinia(first.pinia)
    disposePinia(third.pinia)
  })

  it('E12：store 销毁（disposePinia）释放 watch 与当前 controller', () => {
    const { pinia, auth, session } = makeSession()
    auth.currentUser = userA
    const signal = session.capture().signal
    expect(signal.aborted).toBe(false)

    disposePinia(pinia)
    expect(signal.aborted).toBe(true)

    // watch 已释放：再变更 auth 不递增 epoch
    auth.currentUser = userB
    expect(session.epoch).toBe(1)
  })

  it('零网络/存储副作用：全程不发任何请求', () => {
    const { auth, session } = makeSession()
    auth.currentUser = userA
    auth.currentUser = null
    auth.currentUser = userB
    session.capture()
    expect(session.isCurrent(session.capture())).toBe(true)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('C103-10 → C104-04：换号先失效旧票、释放旧激活并清旧账号私有缓存；新账号恢复缓存资格', () => {
    // node 测试环境无 localStorage：以最小桩提供 v2 登记簿所需的完整 Storage 形面
    // （length/key 支持元数据扫描）。
    const store = new Map<string, string>()
    const stub = {
      get length() { return store.size },
      key: (index: number) => Array.from(store.keys())[index] ?? null,
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => { store.set(k, v) },
      removeItem: (k: string) => { store.delete(k) },
      clear: () => store.clear(),
    }
    vi.stubGlobal('localStorage', stub)
    vi.stubGlobal('sessionStorage', stub)
    store.set('subtitle-cues-aaaa:1', '[]')
    store.set('video-canvas-bind:aaaa:1:sb:dd', '{}')
    registerAccountKey('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'local', 'subtitle-cues-aaaa:1')
    registerAccountKey('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'session', 'video-canvas-bind:aaaa:1:sb:dd')
    store.set('theme', 'dark')

    const { pinia, auth, session } = makeSession()
    auth.currentUser = userA
    expect(session.ownerAccountId).toBe(userA.id)
    const ticketA = session.capture()
    auth.currentUser = userB

    expect(session.epoch).toBe(2)
    expect(session.isCurrent(ticketA)).toBe(false)
    expect(store.get('subtitle-cues-aaaa:1')).toBeUndefined()
    expect(store.get('video-canvas-bind:aaaa:1:sb:dd')).toBeUndefined()
    expect(store.get('theme')).toBe('dark')
    // 新账号激活后恢复缓存写资格；旧账号墓碑已就位（迟到写不复活）。
    store.set('subtitle-cues-bbbb:1', '[]')
    registerAccountKey('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'local', 'subtitle-cues-bbbb:1')
    expect(store.get('subtitle-cues-bbbb:1')).toBe('[]')
    expect(store.get('grassland:apc:gen:' + JSON.stringify(['aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa']))).toBeTruthy()
    // 释放激活（listener 引用计数归零），避免模块级 channel/会话状态泄漏到后续用例。
    disposePinia(pinia)
  })

  it('C104-04：远端清理通知只失效当前账号旧票据（不冒充登出、不变更 owner）', async () => {
    const store = new Map<string, string>()
    const stub = {
      get length() { return store.size },
      key: (index: number) => Array.from(store.keys())[index] ?? null,
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => { store.set(k, v) },
      removeItem: (k: string) => { store.delete(k) },
      clear: () => store.clear(),
    }
    vi.stubGlobal('localStorage', stub)
    vi.stubGlobal('sessionStorage', stub)
    // 不桩 BroadcastChannel：node 环境自带真实现，同进程同名信道互通——
    // 另一「页」（peer 模块实例）的主动清理经真实消息送达本页模块接收端。

    const { pinia, auth, session } = makeSession()
    auth.currentUser = userA
    const ticketA = session.capture()
    store.set('subtitle-cues-aaaa:2', '[]')
    registerAccountKey('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'local', 'subtitle-cues-aaaa:2')

    // 另一标签页发起清理（独立模块实例写新墓碑 + 真实 BroadcastChannel 广播）。
    // 查询串后缀制造独立模块实例（同 realm 第二「标签页」）；TS 不识别 query 导入，按运行时断言。
    // @ts-expect-error vitest/vite 支持带查询串的模块导入
    const peer = (await import('../lib/account-private-cache?tab=peer')) as typeof import('../lib/account-private-cache')
    peer.clearAccountCache('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')
    const operationId = store.get('grassland:apc:gen:' + JSON.stringify(['aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa']))
    expect(operationId).toBeTruthy()
    // 真实 BC 异步派发（实测晚于 microtask/首个 immediate）：等带余量的宏任务。
    await new Promise((resolve) => { setTimeout(resolve, 25) })

    // 票据失效（epoch 递增）、值清理、ownerAccountId 不变（不冒充登出）。
    expect(session.isCurrent(ticketA)).toBe(false)
    expect(ticketA.signal.aborted).toBe(true)
    expect(session.ownerAccountId).toBe(userA.id)
    expect(store.get('subtitle-cues-aaaa:2')).toBeUndefined()
    // 释放激活，避免模块级 channel/引用计数泄漏到后续用例。
    disposePinia(pinia)
  })

  it('TC106-02-05：A→B→A 换号旧字幕/绑定不复活；重登后新写可恢复；同 id 资料更新不双增', () => {
    const store = new Map<string, string>()
    const stub = {
      get length() { return store.size },
      key: (index: number) => Array.from(store.keys())[index] ?? null,
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => { store.set(k, v) },
      removeItem: (k: string) => { store.delete(k) },
      clear: () => store.clear(),
    }
    vi.stubGlobal('localStorage', stub)
    vi.stubGlobal('sessionStorage', stub)

    const { pinia, auth, session } = makeSession()
    auth.currentUser = userA
    // A 登记字幕（local）与画布绑定（session）暂存。
    store.set('subtitle-cues-aaaa:1', '[]')
    registerAccountKey(userA.id, 'local', 'subtitle-cues-aaaa:1')
    store.set('video-canvas-bind:aaaa:1:sb:dd', '{}')
    registerAccountKey(userA.id, 'session', 'video-canvas-bind:aaaa:1:sb:dd')

    // A→B：旧账号缓存被清（值消失、墓碑就位），B 侧读取 A 旧键不得复活。
    auth.currentUser = userB
    expect(store.get('subtitle-cues-aaaa:1')).toBeUndefined()
    expect(store.get('video-canvas-bind:aaaa:1:sb:dd')).toBeUndefined()
    expect(readAccountKey(userA.id, 'local', 'subtitle-cues-aaaa:1')).toBeNull()
    store.set('subtitle-cues-bbbb:1', '[]')
    registerAccountKey(userB.id, 'local', 'subtitle-cues-bbbb:1')
    expect(readAccountKey(userB.id, 'local', 'subtitle-cues-bbbb:1')).toBe('[]')

    // B→A 重登：旧键不复活；同代次新写可正常恢复。
    const epochAfterSwitch = session.epoch
    auth.currentUser = userA
    expect(readAccountKey(userA.id, 'local', 'subtitle-cues-aaaa:1')).toBeNull()
    store.set('subtitle-cues-aaaa:2', '[]')
    registerAccountKey(userA.id, 'local', 'subtitle-cues-aaaa:2')
    expect(readAccountKey(userA.id, 'local', 'subtitle-cues-aaaa:2')).toBe('[]')

    // 同 id 资料更新（换昵称）：epoch 不双增、缓存会话不换（恢复不受影响）。
    const epochBeforeProfile = session.epoch
    expect(epochBeforeProfile).toBeGreaterThan(epochAfterSwitch)
    auth.currentUser = { ...userA, displayName: '甲三' }
    expect(session.epoch).toBe(epochBeforeProfile)
    expect(readAccountKey(userA.id, 'local', 'subtitle-cues-aaaa:2')).toBe('[]')
    disposePinia(pinia)
  })
})
