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

    // 任务书 #50 单店模式：唯一门店自动选中（门店成员区直接呈现，无点击动作）
    const storeSection = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '本店员工')
    expect(storeSection, '本店员工分区渲染（单店自动选中）').toBeTruthy()

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

    const storeSection = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '本店员工')!
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

  test('多店行提供停用/恢复/删除（守卫冲突由后端 409 呈现）；单店行只有停用/恢复', async () => {
    const suspended: string[] = []
    const restored: string[] = []
    const deleted: string[] = []
    const MULTI = [
      { id: 'store-1', organizationId: ORG_ID, name: '旗舰店', status: 'active', createdAt: null },
      { id: 'store-2', organizationId: ORG_ID, name: '分店', status: 'active', createdAt: null },
    ]
    let current = [...MULTI]
    const { wrapper } = await mountedWith((url, opts) => {
      const method = opts?.method ?? 'GET'
      if (url.includes('/stores/store-1/suspend') && method === 'POST') {
        suspended.push('store-1'); current = current.map((st) => st.id === 'store-1' ? { ...st, status: 'suspended' } : st)
        return envelopeResponse({ success: true })
      }
      if (url.includes('/stores/store-1/restore') && method === 'POST') {
        restored.push('store-1'); current = current.map((st) => st.id === 'store-1' ? { ...st, status: 'active' } : st)
        return envelopeResponse({ success: true })
      }
      if (url.endsWith('/stores/store-2') && method === 'DELETE') {
        deleted.push('store-2'); current = current.filter((st) => st.id !== 'store-2')
        return envelopeResponse({ success: true })
      }
      if (url.includes('/stores') && !url.includes('/memberships') && !url.includes('/accounts') && method === 'GET') {
        return envelopeResponse(current)
      }
      return baseHandler(url)
    })

    // 多店：每行 停用+删除；停用后按钮翻转为恢复
    const rows = wrapper.findAll('.team-store-row')
    expect(rows.length).toBe(2)
    await rows[1]!.findAll('button').find((b) => b.text() === '停用')!.trigger('click')
    await flushPromises()
    expect(suspended).toEqual([])
    // rows[0] 是旗舰店：停用它
    await rows[0]!.findAll('button').find((b) => b.text() === '停用')!.trigger('click')
    await flushPromises()
    expect(suspended).toEqual(['store-1'])
    expect(wrapper.text()).toContain('已停用（对外隐藏，可随时恢复）')

    // 删除：确认弹窗 → 确认后调端点并从列表消失
    await wrapper.findAll('.team-store-row')[1]!.findAll('button').find((b) => b.text() === '删除')!.trigger('click')
    await flushPromises()
    const dialog = wrapper.find('[data-testid="store-delete-confirm"]')
    expect(dialog.exists()).toBe(true)
    await dialog.findAll('button').find((b) => b.text() === '确认删除')!.trigger('click')
    await flushPromises()
    expect(deleted).toEqual(['store-2'])
    expect(wrapper.text()).toContain('不可恢复')
    // 删到一家 → 单店模式（推导）：门店行只剩停用/恢复，无删除
    expect(wrapper.find('.team-store-row').exists()).toBe(false)
    expect(wrapper.text()).toContain('唯一门店不可删除')
  })

  test('单店模式（任务书 #50）：门店列表收敛为「我的门店」，建号免选门店，开分店后切换多店', async () => {
    const createdStores = [{ id: 'store-1', organizationId: ORG_ID, name: '旗舰店', status: 'active' }]
    let createCalls: Array<{ name?: string }> = []
    const { wrapper } = await mountedWith((url, opts) => {
      const method = opts?.method ?? 'GET'
      if (url.includes('/stores') && !url.includes('/memberships') && !url.includes('/accounts')) {
        if (method === 'POST') {
          createCalls.push(JSON.parse((opts as unknown as { body?: string }).body ?? '{}'))
          createdStores.push({ id: 'store-2', organizationId: ORG_ID, name: '分店', status: 'active' })
          return envelopeResponse(createdStores[createdStores.length - 1], 201)
        }
        return envelopeResponse(createdStores)
      }
      return baseHandler(url)
    })

    // 单店：无门店列表/新建门店表单；有「我的门店」与开分店折叠
    expect(wrapper.find('.team-list').exists()).toBe(false)
    expect(wrapper.find('input[placeholder="新门店名称"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('我的门店')
    expect(wrapper.text()).toContain('旗舰店')

    // 建号：无门店下拉（单店隐式）；店长在高级选项内（默认不出现三角色下拉中的 manager）
    const mainSelect = wrapper.findAll('select').find((sel) => sel.text().includes('店员') && sel.text().includes('组织成员'))
    expect(mainSelect, '单店主角色下拉（店员/组织成员）').toBeTruthy()
    expect(mainSelect!.text()).not.toContain('店长')
    const advanced = wrapper.findAll('details').find((d) => d.text().includes('高级选项：任命店长'))
    expect(advanced, '店长藏进高级选项').toBeTruthy()

    // 开分店 → 门店数 2 → 推导切多店（列表展开）+ 切换提示
    await wrapper.find('input[placeholder="分店名称"]').setValue('分店')
    await wrapper.findAll('button').find((b) => b.text() === '创建分店')!.trigger('click')
    await flushPromises()
    expect(createCalls).toEqual([{ name: '分店' }])
    expect(wrapper.text()).toContain('已切换到多店管理')
    expect(wrapper.find('.team-list').exists()).toBe(true)
  })

  test('删除成员须输入完整账号名强确认，不匹配时按钮禁用（任务书 #49 D9）', async () => {
    let deleted = false
    const { wrapper } = await mountedWith((url, opts) => {
      const method = opts?.method ?? 'GET'
      if (method === 'DELETE' && url.endsWith(`/api/organizations/${ORG_ID}/accounts/acc-member`)) {
        deleted = true
        return envelopeResponse({ success: true })
      }
      return baseHandler(url)
    })

    // 组织成员表：非 owner 行有「删除」入口（owner 行不渲染）
    const orgSection = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '主体成员')!
    const delButton = orgSection.findAll('button').find((b) => b.text() === '删除')
    expect(delButton, '成员行有删除入口').toBeTruthy()
    const ownerRowHasDelete = orgSection.findAll('tbody tr')
      .filter((row) => row.text().includes('所有者'))
      .flatMap((row) => row.findAll('button'))
      .some((b) => b.text() === '删除')
    expect(ownerRowHasDelete).toBe(false)

    await delButton!.trigger('click')
    await flushPromises()

    // 强确认弹窗：确认物 = 列表行的 username（无则 accountId）；不匹配不可确认
    const dialog = wrapper.find('[data-testid="delete-confirm"]')
    expect(dialog.exists()).toBe(true)
    const confirmBtn = dialog.findAll('button').find((b) => b.text() === '永久删除')!
    expect((confirmBtn.element as HTMLButtonElement).disabled).toBe(true)

    const input = dialog.find('input')
    await input.setValue('caoyuan-wrong')
    expect((confirmBtn.element as HTMLButtonElement).disabled).toBe(true)

    await input.setValue('acc-member')
    expect((confirmBtn.element as HTMLButtonElement).disabled).toBe(false)
    await confirmBtn.trigger('click')
    await flushPromises()

    expect(deleted).toBe(true)
    expect(wrapper.find('[data-testid="delete-confirm"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('不可恢复')
  })
})
