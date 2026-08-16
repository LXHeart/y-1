// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import MerchantMonthlyBillCard from './MerchantMonthlyBillCard.vue'

/**
 * 商家月度账单（任务书 #29+#30 #30）。重点锁：
 * - flows 科目 label 直接渲染后端中文（映射只放一处）；
 * - 金额带符号格式化（充值正、预留负）；
 * - 平台费与托管净变动汇总如实展示（Σ flows == netEscrowDelta 由后端保证）。
 */

const ORG = 'org-1'

function stubFetch(bill: unknown) {
  const calls: { url: string }[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    calls.push({ url })
    // 按子串匹配，避免依赖测试机时区下的具体月份字符串。
    const data = url.includes('/monthly-bill') ? bill : undefined
    return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
  }))
  return { calls }
}

enableAutoUnmount(afterEach)
afterEach(() => { vi.unstubAllGlobals() })

async function mountWith(bill: unknown) {
  const { calls } = stubFetch(bill)
  const wrapper = mount(MerchantMonthlyBillCard, { props: { organizationId: ORG } })
  await flushPromises()
  return { wrapper, calls }
}

describe('MerchantMonthlyBillCard 渲染', () => {
  test('flows 科目与金额如实渲染，label 用后端中文', async () => {
    const { wrapper } = await mountWith({
      month: '2026-08',
      flows: [
        { type: 'DEPOSIT', label: '充值', amountCents: 10000 },
        { type: 'RESERVE', label: '预留', amountCents: -6000 },
        { type: 'RELEASE', label: '释放', amountCents: 1000 },
      ],
      platformFeeCents: 600,
      netEscrowDeltaCents: 5000,
    })

    expect(wrapper.text()).toContain('充值')
    expect(wrapper.text()).toContain('预留')
    expect(wrapper.text()).toContain('释放')
    // 带符号：充值 +100.00，预留 -60.00
    expect(wrapper.text()).toContain('+100.00')
    expect(wrapper.text()).toContain('-60.00')
    // 汇总
    expect(wrapper.text()).toContain('+50.00')   // netEscrowDelta
    expect(wrapper.text()).toContain('¥6.00')   // platformFee
  })

  test('空月显示提示，无流水表', async () => {
    const { wrapper } = await mountWith({
      month: '2026-08', flows: [], platformFeeCents: 0, netEscrowDeltaCents: 0,
    })
    expect(wrapper.text()).toContain('该月无资金流水')
    expect(wrapper.find('table').exists()).toBe(false)
  })

  test('orgId 缺失时不发请求', async () => {
    const { calls } = stubFetch(null)
    mount(MerchantMonthlyBillCard, { props: { organizationId: '' } })
    await flushPromises()
    expect(calls.length).toBe(0)
  })
})
