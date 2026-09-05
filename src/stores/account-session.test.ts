import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'
import type { AuthUser } from '../types/auth'
import { normalizeAccountId, useAccountSessionStore } from './account-session'

/**
 * TC79-01A（任务书 #79 C79-01）：账号票据 store 的归属/代次语义。
 * fixture 取 §12.2 固定合成账号；两个独立 Pinia 验证互不共享。
 */
const userA: AuthUser = { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', email: 'a@qa.invalid', displayName: '甲', role: 'user' }
const userB: AuthUser = { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', email: 'b@qa.invalid', displayName: '乙', role: 'user' }

function makeSession() {
  const pinia = createPinia()
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
})
