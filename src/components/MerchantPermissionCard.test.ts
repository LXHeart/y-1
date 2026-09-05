// @vitest-environment happy-dom
/**
 * 任务书 #78 卡 G：权限材料 contact_info 只收 11 位手机号。
 *
 * 覆盖：非法手机号（字母/座机/邮箱/位数不对）→ 提交按钮禁用 + 格式提示可见；
 * 合法 11 位手机号 → 可提交；contact_info 输入框 placeholder/maxlength/inputmode 就位。
 * 其余端点走 fetch stub（quota/usage/申请列表/附件）。
 */
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import MerchantPermissionCard from './MerchantPermissionCard.vue'

enableAutoUnmount(afterEach)

function stubPermissionFetch(): ReturnType<typeof vi.fn> {
  const spy = vi.fn().mockImplementation(async (url: string) => {
    const data = url.endsWith('/quota')
      ? { tier: 'draft', quota: { maxActiveTasks: 5, maxMonthlyTasks: 20, maxTxAmountCents: 0 } }
      : url.includes('/tasks/usage')
        ? {
            organizationId: 'org-1', activeTasks: 0, monthlyTasks: 0,
            maxActiveTasks: 5, remainingActiveTasks: 5, maxMonthlyTasks: 20, remainingMonthlyTasks: 20,
            maxTxAmountCents: 0,
          }
        : url.endsWith('/permission-requests')
          ? []
          : url.endsWith('/merchant-attachments') ? [] : null
    return {
      ok: true, headers: { get: () => 'application/json' },
      json: async () => ({ success: true, data }),
    }
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

async function mountCard() {
  stubPermissionFetch()
  const wrapper = mount(MerchantPermissionCard, {
    props: { orgId: 'org-1', tier: 'draft', industry: 'other' },
  })
  await flushPromises()
  return wrapper
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('MerchantPermissionCard contact_info 手机号校验（任务书 #78 卡 G）', () => {
  test('contact_info 输入框：placeholder/maxlength=11/inputmode 就位', async () => {
    const wrapper = await mountCard()
    const input = wrapper.find('input[placeholder="填写联系方式（11 位手机号）"]')
    expect(input.exists()).toBe(true)
    expect(input.attributes('maxlength')).toBe('11')
    expect(input.attributes('inputmode')).toBe('numeric')
  })

  test('非法手机号（字母/座机/邮箱/位数不对）→ 格式提示 + 提交按钮禁用', async () => {
    const wrapper = await mountCard()
    for (const bad of ['abcdefghijk', '010-12345678', 'kyb@example.com', '12800138000', '1380013800']) {
      await wrapper.find('input[placeholder="填写联系方式（11 位手机号）"]').setValue(bad)
      await wrapper.find('input[placeholder="填写营业执照"]').setValue('BL-91110000123456789X')
      await flushPromises()
      expect(wrapper.text()).toContain('请输入有效的手机号（11 位）')
      const submit = wrapper.findAll('button').find((b) => b.text() === '提交申请')!
      expect(submit.attributes('disabled')).toBeDefined()
    }
  })

  test('合法 11 位手机号 + 必填材料齐 → 提交按钮可用', async () => {
    const wrapper = await mountCard()
    await wrapper.find('input[placeholder="填写联系方式（11 位手机号）"]').setValue('13800138000')
    await wrapper.find('input[placeholder="填写营业执照"]').setValue('BL-91110000123456789X')
    await flushPromises()
    expect(wrapper.text()).not.toContain('请输入有效的手机号（11 位）')
    const submit = wrapper.findAll('button').find((b) => b.text() === '提交申请')!
    expect(submit.attributes('disabled')).toBeUndefined()
  })

  test('手机号留空仍走「缺料」禁用路径（不重复报格式错误）', async () => {
    const wrapper = await mountCard()
    await wrapper.find('input[placeholder="填写营业执照"]').setValue('BL-91110000123456789X')
    await flushPromises()
    expect(wrapper.text()).toContain('还需填写：联系方式')
    expect(wrapper.text()).not.toContain('请输入有效的手机号（11 位）')
  })
})
