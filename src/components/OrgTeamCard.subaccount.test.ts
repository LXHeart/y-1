// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import OrgTeamCard from './OrgTeamCard.vue'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

/**
 * 任务书 #48：子账号直建 / 一次性密码展示 / 停用恢复 / 审核开关 的组件行为锁定。
 * 走全局 fetch 桩（useGrassland.request 解 {success,data} 信封），与既有面板测试同口径。
 */

const ORG_ID = 'org-1'

type Handler = (url: string, opts?: { method?: string }) => Response | undefined

function envelopeResponse(payload: unknown, status = 200): Response {
  const body = status >= 400 ? { success: false, error: payload } : { success: true, data: payload }
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** 先定义完整路由表再挂载；同一时刻只有一个全局桩，便于事后断言调用。 */
async function mountedWith(handler: Handler) {
  const calls: Array<{ url: string; method?: string }> = []
  const fn = vi.fn(async (input: RequestInfo | URL, opts?: { method?: string }) => {
    const url = String(input)
    calls.push({ url, method: opts?.method })
    const res = handler(url, opts)
    if (!res) throw new Error('unexpected request: ' + url)
    return res
  })
  vi.stubGlobal('fetch', fn as unknown as typeof fetch)
  const wrapper = mount(OrgTeamCard, { props: { orgId: ORG_ID } })
  await flushPromises()
  return { wrapper, calls }
}

function baseHandler(url: string): Response | undefined {
  if (url.includes('/memberships') && !url.includes('/stores/store-1')) {
    return envelopeResponse([
      { id: 'm1', organizationId: ORG_ID, accountId: 'acc-owner', role: 'owner', createdAt: null, accountStatus: 'active' },
      { id: 'm2', organizationId: ORG_ID, accountId: 'acc-member', role: 'member', createdAt: null, accountStatus: 'active' },
    ])
  }
  if (url.includes('/stores') && !url.includes('/memberships')) return envelopeResponse([
    { id: 'store-1', organizationId: ORG_ID, name: '旗舰店', status: 'active' },
  ])
  if (url.includes('/stores/store-1/memberships')) return envelopeResponse([
    { id: 'sm1', storeId: 'store-1', accountId: 'acc-staff', role: 'staff', createdAt: null, accountStatus: 'active' },
  ])
  if (url.includes('member-review-required')) return envelopeResponse({ required: false })
  if (url.includes('account-prefix')) return envelopeResponse({ prefix: 'caoyuan' })
  return undefined
}

describe('OrgTeamCard · 子账号管控（任务书 #48/#49）', () => {
  test('主体直建成员账号：loginName 表单 + 一次性密码明文展示一次，点「我已保存」后销毁', async () => {
    let created = false
    const { wrapper } = await mountedWith((url, opts) => {
      const method = opts?.method ?? 'GET'
      if (method === 'POST' && url.endsWith(`/api/organizations/${ORG_ID}/accounts`)) {
        created = true
        return envelopeResponse({
          account: { id: 'new-1', username: 'caoyuan-wang', displayName: '王成员', role: 'member', status: 'active' },
          initialPassword: 'zmX28J86LvHbevBn',
          mustChangePassword: true,
        })
      }
      return baseHandler(url)
    })

    const loginInput = wrapper.find('input[placeholder="登录名（3-24 位字母数字）"]')
    const nameInput = wrapper.find('input[placeholder="显示名"]')
    expect(loginInput.exists()).toBe(true)
    await loginInput.setValue('wang')
    await nameInput.setValue('王成员')
    // 前缀预览随输入实时拼接
    expect(wrapper.text()).toContain('caoyuan-wang')
    const button = wrapper.findAll('button').find((b) => b.text() === '创建账号')
    expect(button, '创建按钮存在且可点').toBeTruthy()
    await button!.trigger('click')
    await flushPromises()

    expect(created).toBe(true)
    const panel = wrapper.find('[data-testid="one-time-password"]')
    expect(panel.exists()).toBe(true)
    expect(panel.text()).toContain('caoyuan-wang')
    expect(panel.text()).toContain('zmX28J86LvHbevBn')
    expect(panel.text()).toContain('关闭后无法再次查看')

    await panel.findAll('button')[0].trigger('click')
    await flushPromises()
    // 明文确实只出现在一次性区块，随「我已保存」一起消失
    expect(wrapper.find('[data-testid="one-time-password"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('zmX28J86LvHbevBn')
  })

  test('审核开关切换失败时回滚勾选状态并呈现后端错误', async () => {
    const { wrapper } = await mountedWith((url, opts) => {
      if ((opts?.method ?? '') === 'PATCH' && url.includes('member-review-required')) {
        return envelopeResponse('需要平台管理员权限', 403)
      }
      return baseHandler(url)
    })

    const box = wrapper.find('.team-toggle input')
    expect((box.element as HTMLInputElement).checked).toBe(false)

    await box.setValue(true)
    await flushPromises()

    // 失败后 UI 回滚为关闭；错误条呈现后端文案
    expect((box.element as HTMLInputElement).checked).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
  })

  test('门店成员行提供停用/恢复即时操作，走 /suspend、/restore 端点', async () => {
    const suspendCalls: string[] = []
    const restoreCalls: string[] = []
    const { wrapper } = await mountedWith((url, opts) => {
      const method = opts?.method ?? 'GET'
      if (method === 'POST' && url.endsWith(`/api/organizations/${ORG_ID}/accounts/acc-staff/suspend`)) {
        suspendCalls.push(url)
        return envelopeResponse({ success: true })
      }
      if (method === 'POST' && url.endsWith(`/api/organizations/${ORG_ID}/accounts/acc-staff/restore`)) {
        restoreCalls.push(url)
        return envelopeResponse({ success: true })
      }
      return baseHandler(url)
    })

    // 选中门店以加载其成员表
    const storeButton = wrapper.findAll('.team-link').find((b) => b.text() === '旗舰店')
    expect(storeButton, '门店列表渲染').toBeTruthy()
    await storeButton!.trigger('click')
    await flushPromises()

    // 只在「门店成员」分区内操作，避免误触主体成员表的同名按钮
    const storeSection = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '门店成员')
    expect(storeSection, '门店成员分区渲染').toBeTruthy()

    const suspendButton = storeSection!.findAll('button').find((b) => b.text() === '停用账号')
    expect(suspendButton).toBeTruthy()
    await suspendButton!.trigger('click')
    await flushPromises()
    expect(suspendCalls.length).toBe(1)
    expect(wrapper.text()).toContain('账号已停用')

    const restoreButton = storeSection!.findAll('button').find((b) => b.text() === '恢复')
    expect(restoreButton).toBeTruthy()
    await restoreButton!.trigger('click')
    await flushPromises()
    expect(restoreCalls.length).toBe(1)
    expect(wrapper.text()).toContain('账号已恢复可用')
  })

  test('待审核员工行展示审核入口：通过走 /review(approve)，行内不再出现停用按钮', async () => {
    let reviewBody: unknown = null
    const { wrapper, calls } = await mountedWith((url, opts) => {
      const method = opts?.method ?? 'GET'
      if (url.includes('/stores/store-1/memberships')) {
        return envelopeResponse([
          { id: 'sm1', storeId: 'store-1', accountId: 'acc-pending', role: 'staff', createdAt: null, accountStatus: 'pending_review' },
        ])
      }
      if (method === 'POST' && url.endsWith(`/api/organizations/${ORG_ID}/accounts/acc-pending/review`)) {
        reviewBody = (opts as unknown as { body?: string })?.body ?? null
        return envelopeResponse({ success: true })
      }
      return baseHandler(url)
    })

    await wrapper.findAll('.team-link').find((b) => b.text() === '旗舰店')!.trigger('click')
    await flushPromises()

    const storeSection = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '门店成员')!
    expect(storeSection.text()).toContain('待审核')

    const approve = storeSection.findAll('button').find((b) => b.text() === '通过')
    expect(approve).toBeTruthy()
    // pending 行的操作区是审核而非停用/恢复
    expect(storeSection.findAll('button').some((b) => b.text() === '停用账号')).toBe(false)

    await approve!.trigger('click')
    await flushPromises()
    expect(reviewBody).toBe('{"decision":"approve"}')
    expect(wrapper.text()).toContain('已通过审核')
    void calls
  })
})
