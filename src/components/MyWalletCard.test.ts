// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import MyWalletCard from './MyWalletCard.vue'
import { useAuth } from '../composables/useAuth'
import type { AuthUser } from '../types/auth'

/**
 * 推荐官钱包卡片。钱的显示错了比不显示更糟，故重点锁：
 * - 流水金额**已带符号**（提现是负数），不能再自己补一次负号；
 * - 提现金额不得超过余额（前端先拦一道，后端另有 409）；
 * - 结算前空流水要说明「钱在托管中」，不能让人以为钱丢了。
 */

const { currentUser } = useAuth()

function asUser(id: string): AuthUser {
  return { id, email: `${id}@test.local`, displayName: id, role: 'user' }
}

const WALLET = {
  accountId: 'acct-1',
  balanceCents: 50000,
  updatedAt: '2026-07-27T10:00:00Z',
  entries: [
    { id: 'e1', entryType: 'task_payout', amountCents: 50000, feeCents: 0,
      engagementRef: 'app-1', memo: '任务结算入账', createdAt: '2026-07-27T10:00:00Z' },
  ],
}

function stubFetch(walletByCall: unknown[]): { calls: { url: string; body?: string }[] } {
  const calls: { url: string; body?: string }[] = []
  let index = 0
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, body: init?.body as string | undefined })
    const data = walletByCall[Math.min(index++, walletByCall.length - 1)]
    return {
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: true, data }),
    }
  }))
  return { calls }
}

enableAutoUnmount(afterEach)

beforeEach(() => {
  currentUser.value = null
})

afterEach(() => {
  vi.unstubAllGlobals()
  currentUser.value = null
})

describe('MyWalletCard 展示', () => {
  test('登录后拉钱包并展示余额与入账流水', async () => {
    const { calls } = stubFetch([WALLET])
    const wrapper = mount(MyWalletCard)
    await flushPromises()
    expect(calls).toEqual([])

    currentUser.value = asUser('acct-1')
    await flushPromises()

    expect(calls[0].url).toBe('/api/finance/wallets/me')
    expect(wrapper.text()).toContain('¥500.00')
    expect(wrapper.text()).toContain('任务结算入账')
    expect(wrapper.text()).toContain('+500.00')
  })

  /** 结算前没有流水是**正常**的（钱在托管中），必须讲清楚，否则会被当成钱丢了。 */
  test('空流水时说明钱还在托管中', async () => {
    stubFetch([{ ...WALLET, balanceCents: 0, entries: [] }])
    const wrapper = mount(MyWalletCard)
    currentUser.value = asUser('acct-1')
    await flushPromises()

    expect(wrapper.text()).toContain('在那之前钱在平台托管中')
    expect(wrapper.text()).toContain('¥0.00')
  })

  /** 后端给的 amountCents 已带符号；再补负号就会显示成 --200.00。 */
  test('提现流水按后端的负号原样展示，不重复加负号', async () => {
    stubFetch([{
      ...WALLET,
      balanceCents: 30000,
      entries: [{ id: 'e2', entryType: 'withdrawal', amountCents: -20000, feeCents: 0,
        engagementRef: null, memo: 'sandbox 提现', createdAt: '2026-07-27T11:00:00Z' }],
    }])
    const wrapper = mount(MyWalletCard)
    currentUser.value = asUser('acct-1')
    await flushPromises()

    expect(wrapper.text()).toContain('-200.00')
    expect(wrapper.text()).not.toContain('--200.00')
  })

  test('平台抽成 > 0 时如实标出服务费', async () => {
    stubFetch([{
      ...WALLET,
      entries: [{ id: 'e3', entryType: 'task_payout', amountCents: 47500, feeCents: 2500,
        engagementRef: 'app-9', memo: null, createdAt: '2026-07-27T10:00:00Z' }],
    }])
    const wrapper = mount(MyWalletCard)
    currentUser.value = asUser('acct-1')
    await flushPromises()

    expect(wrapper.text()).toContain('平台服务费 ¥25.00')
  })
})

describe('MyWalletCard 提现', () => {
  test('提现发送分为单位的金额并用返回的钱包刷新', async () => {
    const after = { ...WALLET, balanceCents: 20000, entries: [
      { id: 'e2', entryType: 'withdrawal', amountCents: -30000, feeCents: 0,
        engagementRef: null, memo: 'sandbox 提现', createdAt: '2026-07-27T11:00:00Z' },
      ...WALLET.entries,
    ] }
    const { calls } = stubFetch([WALLET, after])
    const wrapper = mount(MyWalletCard)
    currentUser.value = asUser('acct-1')
    await flushPromises()

    await wrapper.find('input[type=number]').setValue(300)
    await wrapper.findAll('button').find((b) => b.text() === '提现')!.trigger('click')
    await flushPromises()

    expect(calls[1].url).toBe('/api/finance/wallets/me/withdrawals')
    expect(JSON.parse(calls[1].body!)).toEqual({ amountCents: 30000 })   // 元 → 分
    expect(wrapper.text()).toContain('¥200.00')
    expect(wrapper.text()).toContain('已提现 ¥300.00')
  })

  test('超过余额时按钮禁用且不发请求', async () => {
    const { calls } = stubFetch([WALLET])
    const wrapper = mount(MyWalletCard)
    currentUser.value = asUser('acct-1')
    await flushPromises()

    await wrapper.find('input[type=number]').setValue(501)   // 余额 ¥500
    const button = wrapper.findAll('button').find((b) => b.text() === '提现')!

    expect(button.attributes('disabled')).toBeDefined()
    await button.trigger('click')
    await flushPromises()
    expect(calls).toHaveLength(1)   // 只有最初那次拉取
  })
})
