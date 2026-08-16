// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import RecommenderIncomeStatsCard from './RecommenderIncomeStatsCard.vue'
import { useAuth } from '../composables/useAuth'
import type { AuthUser } from '../types/auth'

/**
 * 推荐官收入统计（任务书 #29+#30 #29）。重点锁：
 * - 月度汇总表四类金额 + 毛/抽成/净按带符号 cents 格式化；
 * - 按任务明细的任务标题 = 前端 join my-applications（engagementRef==applicationId，D3），
 *   join 不上的（如 commerce 订单）回退为截断 ref。
 */

const { currentUser } = useAuth()

function asUser(id: string): AuthUser {
  return { id, email: `${id}@test.local`, displayName: id, role: 'user' }
}

const STATISTICS = {
  from: '2026-08',
  to: '2026-08',
  months: [{
    month: '2026-08',
    taskPayoutCents: 8000,
    commerceCommissionCents: 1200,
    withdrawalCents: -2000,
    clawbackCents: -1000,
    grossCents: 9800,
    feeCents: 600,
    netCents: 6200,
  }],
  byEngagement: [
    { engagementRef: 'app-task-1', payoutCents: 6200, feeCents: 600, count: 2, lastAt: '2026-08-20T10:00:00Z' },
    { engagementRef: 'order-commerce-9', payoutCents: 1200, feeCents: 0, count: 1, lastAt: '2026-08-21T10:00:00Z' },
  ],
}

/** my-applications：app-task-1 有任务标题；order-commerce-9 不在其中（commerce 订单）。 */
const MY_APPLICATIONS = {
  items: [
    { applicationId: 'app-task-1', taskId: 'task-1', taskTitle: '探店视频任务', taskStatus: 'published', applicationStatus: 'accepted', bountyCents: 6200, appliedAt: '2026-08-01T10:00:00Z', settledAt: null },
  ],
  nextCursor: null,
  hasMore: false,
}

function stubFetch() {
  const calls: { url: string }[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    calls.push({ url })
    let data: unknown
    if (url.includes('/wallets/me/statistics')) data = STATISTICS
    else if (url.includes('/my-applications')) data = MY_APPLICATIONS
    else data = undefined
    return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
  }))
  return { calls }
}

enableAutoUnmount(afterEach)
beforeEach(() => { currentUser.value = null })
afterEach(() => { vi.unstubAllGlobals(); currentUser.value = null })

async function mountLoggedIn() {
  const { calls } = stubFetch()
  const wrapper = mount(RecommenderIncomeStatsCard)
  currentUser.value = asUser('acct-1')
  await flushPromises()
  return { wrapper, calls }
}

describe('RecommenderIncomeStatsCard', () => {
  test('月度汇总表渲染四类金额与毛/抽成/净', async () => {
    const { wrapper } = await mountLoggedIn()
    const text = wrapper.text()
    expect(text).toContain('2026-08')
    expect(text).toContain('80.00')    // 任务佣金
    expect(text).toContain('12.00')    // 到店佣金
    expect(text).toContain('-20.00')   // 提现
    expect(text).toContain('-10.00')   // 冲正
    expect(text).toContain('98.00')    // 毛额
    expect(text).toContain('6.00')     // 平台抽成
    expect(text).toContain('+62.00')   // 净额
  })

  test('按任务明细用 my-applications 的标题 join，join 不上回退截断 ref', async () => {
    const { wrapper } = await mountLoggedIn()
    const text = wrapper.text()
    // engagementRef==applicationId → 命中任务标题
    expect(text).toContain('探店视频任务')
    // commerce 订单 ref 不在 my-applications 里 → 回退为「订单/任务 + 截断 ref」
    expect(text).toContain('订单/任务 order-co…')
  })

  test('发起 statistics 与 my-applications 请求', async () => {
    const { calls } = await mountLoggedIn()
    const urls = calls.map((c) => c.url)
    expect(urls.some((u) => u.includes('/wallets/me/statistics'))).toBe(true)
    expect(urls.some((u) => u.includes('/my-applications'))).toBe(true)
  })
})
