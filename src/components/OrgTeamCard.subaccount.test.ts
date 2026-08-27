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

/**
 * 多店门店列表。任务书 #51 起「建号入口」与「审核开关」只在多店模式呈现
 * （单店只有主体账号一个用户），故凡要操作这两者的用例都得先进多店。
 */
const MULTI_STORES = [
  { id: 'store-1', organizationId: ORG_ID, name: '旗舰店', status: 'active', createdAt: null },
  { id: 'store-2', organizationId: ORG_ID, name: '分店', status: 'active', createdAt: null },
]

/** baseHandler 的多店变体：只把门店列表换成两家，其余路由不变。 */
function multiStoreHandler(url: string): Response | undefined {
  if (url.includes('/stores') && !url.includes('/memberships') && !url.includes('/accounts')) {
    return envelopeResponse(MULTI_STORES)
  }
  return baseHandler(url)
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
  // 多店：建号入口只在多店呈现（#51 第 3 条），故本用例走 multiStoreHandler
  test('主体直建组织成员（2026-08-28 收敛：固定 member、无角色/门店下拉）+ 一次性密码明文展示一次', async () => {
    let createdBody: unknown = null
    const { wrapper } = await mountedWith((url, opts) => {
      const method = opts?.method ?? 'GET'
      if (method === 'POST' && url.endsWith(`/api/organizations/${ORG_ID}/accounts`)) {
        createdBody = JSON.parse((opts as unknown as { body?: string }).body ?? '{}')
        return envelopeResponse({
          account: { id: 'new-1', username: 'caoyuan-wang', displayName: '王成员', role: 'member', status: 'active' },
          initialPassword: 'zmX28J86LvHbevBn',
          mustChangePassword: true,
        })
      }
      return multiStoreHandler(url)
    })

    const orgSection = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '主体成员')!
    // 二轮收敛：主体区只建组织成员——角色/门店下拉退役，全卡无 <select>
    expect(wrapper.findAll('select').length).toBe(0)
    const loginInput = orgSection.find('input[placeholder="登录名（3-24 位字母数字）"]')
    const nameInput = orgSection.find('input[placeholder="显示名"]')
    expect(loginInput.exists()).toBe(true)
    await loginInput.setValue('wang')
    await nameInput.setValue('王成员')
    // 前缀预览随输入实时拼接
    expect(orgSection.text()).toContain('caoyuan-wang')
    const button = orgSection.findAll('button').find((b) => b.text() === '创建账号')
    expect(button, '创建按钮存在且可点').toBeTruthy()
    await button!.trigger('click')
    await flushPromises()

    expect(createdBody).toEqual({ role: 'member', loginName: 'wang', displayName: '王成员' })
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

  // 多店：审核开关只在多店呈现（#51 第 2 条——单店无店长代建场景）
  test('审核开关切换失败时回滚勾选状态并呈现后端错误', async () => {
    const { wrapper } = await mountedWith((url, opts) => {
      if ((opts?.method ?? '') === 'PATCH' && url.includes('member-review-required')) {
        return envelopeResponse('需要平台管理员权限', 403)
      }
      return multiStoreHandler(url)
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

  test('单店模式（#50 推导 + #51 收敛）：门店收敛为「我的门店」，无建号入口，开分店后切换多店', async () => {
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

    // 任务书 #51 第 3 条：单店不呈现任何建号入口（本主体只有主体账号一人），
    // 原 #50 的「角色下拉 + 店长高级选项」随之退役；改为一句「先开分店」引导
    expect(wrapper.findAll('select').length).toBe(0)
    expect(wrapper.findAll('details').some((d) => d.text().includes('主体直接创建账号'))).toBe(false)
    expect(wrapper.text()).toContain('需要为员工开账号请先在下方「门店」区开分店')

    // 开分店 → 门店数 2 → 推导切多店（列表展开）+ 切换提示
    await wrapper.find('input[placeholder="分店名称"]').setValue('分店')
    await wrapper.findAll('button').find((b) => b.text() === '创建分店')!.trigger('click')
    await flushPromises()
    expect(createCalls).toEqual([{ name: '分店' }])
    expect(wrapper.text()).toContain('已切换到多店管理')
    expect(wrapper.find('.team-list').exists()).toBe(true)
  })

  test('单店：本店员工含主体账号隐式店长行（无停用/删除入口），且不再有第二个建店员入口', async () => {
    const { wrapper } = await mountedWith((url) => {
      // 该门店没有任何 store_membership 行——正是「新注册单店商家」的真实起点
      if (url.includes('/stores/store-1/memberships')) return envelopeResponse([])
      return baseHandler(url)
    })

    const storeSection = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '本店员工')!
    // 主体账号（owner）以隐式店长呈现，取代原来的「该门店暂无成员」空态
    expect(storeSection.text()).not.toContain('该门店暂无成员')
    const ownerRow = storeSection.findAll('tbody tr').find((row) => row.text().includes('acc-owne'))
    expect(ownerRow, '主体账号行渲染').toBeTruthy()
    expect(ownerRow!.text()).toContain('主体账号')
    expect(ownerRow!.text()).toContain('店长')
    // 隐式行不给停用/恢复/删除——主体级动作只在「主体成员」表做
    expect(ownerRow!.findAll('button').length).toBe(0)
    expect(ownerRow!.text()).toContain('默认管理本店')

    // 任务书 #51 第 3 条：单店无任何建号入口（门店区的、主体区的都没有）
    expect(storeSection.find('input[placeholder="员工姓名"]').exists()).toBe(false)
    expect(storeSection.text()).toContain('你的主体账号默认就是本店店长，无需添加')
    expect(wrapper.findAll('details').some((d) => d.text().includes('主体直接创建账号'))).toBe(false)
    // 第 2 条：审核开关也不呈现
    expect(wrapper.find('.team-toggle').exists()).toBe(false)
  })

  test('多店：门店成员区只呈现真实成员，无店长时提示主体代管并提供内联任命（2026-08-28 二轮收敛）', async () => {
    const { wrapper } = await mountedWith((url) => {
      if (url.includes('/stores') && !url.includes('/memberships') && !url.includes('/accounts')) {
        return envelopeResponse([
          { id: 'store-1', organizationId: ORG_ID, name: '旗舰店', status: 'active', createdAt: null },
          { id: 'store-2', organizationId: ORG_ID, name: '分店', status: 'active', createdAt: null },
        ])
      }
      return baseHandler(url)
    })

    // 多店：门店需显式点选（单店的自动选中不适用）
    await wrapper.findAll('.team-link').find((b) => b.text() === '旗舰店')!.trigger('click')
    await flushPromises()

    const storeSection = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '门店成员')!
    // 不再画主体账号隐式店长行——orgSuperUserAsManager 是权限不是身份
    expect(storeSection.findAll('tbody tr').some((row) => row.text().includes('主体账号'))).toBe(false)
    // 真实 store_membership 行（acc-staff）操作入口不受影响
    const staffRow = storeSection.findAll('tbody tr').find((row) => row.text().includes('acc-staf'))!
    expect(staffRow.findAll('button').some((b) => b.text() === '停用账号')).toBe(true)
    expect(staffRow.findAll('button').some((b) => b.text() === '删除')).toBe(true)
    // baseHandler 的 store-1 只有店员没有店长 → 代管提示 + 内联任命入口 + 建店员入口
    expect(storeSection.text()).toContain('尚未任命店长')
    expect(storeSection.text()).toContain('由主体账号代管')
    expect(storeSection.findAll('details').some((d) => d.find('summary')?.text().includes('任命店长'))).toBe(true)
    expect(storeSection.find('input[placeholder="员工姓名"]').exists()).toBe(true)
  })

  test('任命店长：门店区内联任命 → POST role=manager 挂当前店，任命后代管提示消失（2026-08-28 二轮收敛）', async () => {
    let appointBody: unknown = null
    let appointed = false
    const { wrapper } = await mountedWith((url, opts) => {
      const method = opts?.method ?? 'GET'
      if (url.includes('/stores/store-1/memberships')) {
        return envelopeResponse(appointed
          ? [{ id: 'sm9', storeId: 'store-1', accountId: 'acc-manager', username: 'caoyuan-zhang', role: 'manager', createdAt: null, accountStatus: 'active' }]
          : [])
      }
      if (method === 'POST' && url.endsWith(`/api/organizations/${ORG_ID}/accounts`)) {
        appointed = true
        appointBody = JSON.parse((opts as unknown as { body?: string }).body ?? '{}')
        return envelopeResponse({
          account: { id: 'acc-manager', username: 'caoyuan-zhang', displayName: '张店长', role: 'manager', status: 'active' },
          initialPassword: 'zmX28J86LvHbevBn',
          mustChangePassword: true,
        })
      }
      return multiStoreHandler(url)
    })

    await wrapper.findAll('.team-link').find((b) => b.text() === '旗舰店')!.trigger('click')
    await flushPromises()

    const storeSection = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '门店成员')!
    expect(storeSection.text()).toContain('尚未任命店长')

    const appointDetails = storeSection.findAll('details').find((d) => d.find('summary')?.text().includes('任命店长'))!
    await appointDetails.find('input[placeholder="登录名（3-24 位字母数字）"]').setValue('zhang')
    await appointDetails.find('input[placeholder="店长姓名"]').setValue('张店长')
    await appointDetails.findAll('button').find((b) => b.text() === '任命并创建账号')!.trigger('click')
    await flushPromises()

    expect(appointBody).toEqual({ role: 'manager', loginName: 'zhang', displayName: '张店长', storeId: 'store-1' })
    const panel = wrapper.find('[data-testid="one-time-password"]')
    expect(panel.exists()).toBe(true)
    expect(panel.text()).toContain('caoyuan-zhang')
    // 任命后门店有了店长：代管提示消失、店长行呈现
    expect(storeSection.text()).not.toContain('尚未任命店长')
    const managerRow = storeSection.findAll('tbody tr').find((row) => row.text().includes('caoyuan-zhang'))!
    expect(managerRow.text()).toContain('店长')
  })

  test('单店且只有 owner：主体成员表不渲染操作列（任务书 #51 第 5 条）', async () => {
    const { wrapper } = await mountedWith((url) => {
      // 单店常态：成员只有 owner 一人
      if (url.includes('/memberships') && !url.includes('/stores/store-1')) {
        return envelopeResponse([
          { id: 'm1', organizationId: ORG_ID, accountId: 'acc-owner', role: 'owner', createdAt: null, accountStatus: 'active' },
        ])
      }
      return baseHandler(url)
    })

    const orgSection = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '主体成员')!
    // 表头无「操作」列；owner 行无任何按钮（停用/删除服务端一律 403，整列都是死按钮）
    expect(orgSection.findAll('thead th').map((th) => th.text())).toEqual(['账号', '角色', '状态'])
    const ownerRow = orgSection.findAll('tbody tr')[0]!
    expect(ownerRow.findAll('button').length).toBe(0)
  })

  test('单店即使存在非 owner 成员也不呈现操作列（2026-08-28 严格字面拍板；回落单店的成员由运营台处置）', async () => {
    const { wrapper } = await mountedWith((url) => baseHandler(url))

    // baseHandler 的成员表含 owner + member 两行，门店只有一家（单店）
    const orgSection = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '主体成员')!
    expect(orgSection.findAll('thead th').map((th) => th.text())).toEqual(['账号', '角色', '状态'])
    const rows = orgSection.findAll('tbody tr')
    expect(rows.length).toBe(2)
    // 非 owner 行同样无任何操作按钮——多店期建的成员回落单店后，停用/删除只能走治理台；
    // 「主体所有者」标记位于操作列单元格内，随整列一起不渲染
    expect(rows.every((row) => row.findAll('button').length === 0)).toBe(true)
    expect(rows.some((row) => row.text().includes('所有者'))).toBe(true)
  })

  test('删除成员须输入完整账号名强确认，不匹配时按钮禁用（任务书 #49 D9）', async () => {
    let deleted = false
    const { wrapper } = await mountedWith((url, opts) => {
      const method = opts?.method ?? 'GET'
      if (method === 'DELETE' && url.endsWith(`/api/organizations/${ORG_ID}/accounts/acc-member`)) {
        deleted = true
        return envelopeResponse({ success: true })
      }
      // 多店桩：#51 第 5 条严格字面后，操作列只在多店呈现
      return multiStoreHandler(url)
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
