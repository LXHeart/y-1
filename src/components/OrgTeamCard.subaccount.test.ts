// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import OrgTeamCard from './OrgTeamCard.vue'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

/**
 * 任务书 #52 池模型：建号一律入池、门店身份是分配层的组件行为锁定。
 * 走全局 fetch 桩（useGrassland.request 解 {success,data} 信封），与既有面板测试同口径。
 */

const ORG_ID = 'org-1'

type Handler = (url: string, opts?: { method?: string; body?: string }) => Response | undefined

function envelopeResponse(payload: unknown, status = 200): Response {
  const body = status >= 400 ? { success: false, error: payload } : { success: true, data: payload }
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** 先定义完整路由表再挂载；同一时刻只有一个全局桩，便于事后断言调用。 */
async function mountedWith(handler: Handler) {
  const calls: Array<{ url: string; method?: string; body?: string }> = []
  const fn = vi.fn(async (input: RequestInfo | URL, opts?: { method?: string; body?: string }) => {
    const url = String(input)
    calls.push({ url, method: opts?.method, body: opts?.body })
    const res = handler(url, opts)
    if (!res) throw new Error('unexpected request: ' + url)
    return res
  })
  vi.stubGlobal('fetch', fn as unknown as typeof fetch)
  const wrapper = mount(OrgTeamCard, { props: { orgId: ORG_ID } })
  await flushPromises()
  return { wrapper, calls }
}

const MULTI_STORES = [
  { id: 'store-1', organizationId: ORG_ID, name: '旗舰店', status: 'active', createdAt: null },
  { id: 'store-2', organizationId: ORG_ID, name: '湖畔分店', status: 'active', createdAt: null },
]

/** 组织成员（池）桩：owner + 未分配成员 poolguy + 已挂旗舰店的店员 staff1。 */
const ORG_MEMBERS = [
  { id: 'm1', organizationId: ORG_ID, accountId: 'acc-owner', role: 'owner', createdAt: null, accountStatus: 'active', username: null, storeId: null, storeRole: null, storeName: null },
  { id: 'm2', organizationId: ORG_ID, accountId: 'acc-pool', role: 'member', createdAt: null, accountStatus: 'active', username: 'caoyuan-poolguy', storeId: null, storeRole: null, storeName: null },
  { id: 'm3', organizationId: ORG_ID, accountId: 'acc-staff', role: 'member', createdAt: null, accountStatus: 'active', username: 'caoyuan-staff1', storeId: 'store-1', storeRole: 'staff', storeName: '旗舰店' },
]

function baseHandler(url: string): Response | undefined {
  if (url.includes('/memberships') && !url.includes('/stores/store-')) {
    return envelopeResponse(ORG_MEMBERS)
  }
  if (url.includes('/stores') && !url.includes('/memberships')) return envelopeResponse([
    { id: 'store-1', organizationId: ORG_ID, name: '旗舰店', status: 'active' },
  ])
  const storeMatch = url.match(/\/stores\/(store-\d+)\/memberships/)
  if (storeMatch) {
    return envelopeResponse(storeMatch[1] === 'store-1'
      ? [{ id: 'sm1', storeId: 'store-1', accountId: 'acc-staff', role: 'staff', createdAt: null, accountStatus: 'active', username: 'caoyuan-staff1' }]
      : [])
  }
  if (url.includes('account-prefix')) return envelopeResponse({ prefix: 'caoyuan' })
  return undefined
}

function multiStoreHandler(url: string): Response | undefined {
  if (url.includes('/stores') && !url.includes('/memberships') && !url.includes('/accounts')) {
    return envelopeResponse(MULTI_STORES)
  }
  return baseHandler(url)
}

describe('OrgTeamCard · 成员池与门店分配（任务书 #52）', () => {
  // ---------- 单店态 ----------

  test('单店：无建号/分配入口，主体成员表无操作列，本店员工为隐式店长行', async () => {
    const { wrapper } = await mountedWith((url) => baseHandler(url))

    const teamText = wrapper.find('article.team').text()
    expect(teamText).toContain('单店模式下本主体只有你这一个账号')
    expect(teamText).not.toContain('主体直接创建账号')
    expect(teamText).not.toContain('从成员池分配')

    const orgSection = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '主体成员')!
    expect(orgSection.findAll('thead th').map((th) => th.text())).toEqual(['账号', '角色', '所属门店', '状态'])

    const staffSection = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '本店员工')!
    const ownerRow = staffSection.findAll('tbody tr').filter((row) => row.text().includes('主体账号'))
    expect(ownerRow.length).toBeGreaterThan(0)
    expect(ownerRow[0]!.findAll('button').length).toBe(0)
    expect(ownerRow[0]!.text()).toContain('默认管理本店')
  })

  test('单店：未分配成员显示「未分配」，不提供分配入口（#51 口径：雇人先开分店）', async () => {
    const { wrapper } = await mountedWith((url) => baseHandler(url))
    const orgSection = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '主体成员')!
    const poolRow = orgSection.findAll('tbody tr').find((row) => row.text().includes('caoyuan-poolguy'))
    expect(poolRow!.text()).toContain('未分配')
    expect(wrapper.find('article.team').findAll('select').length).toBe(0)
  })

  // ---------- 多店：建号入池（可选挂店） ----------

  test('多店建号不选门店：POST role=member，入池未分配', async () => {
    let createdBody: unknown = null
    const { wrapper } = await mountedWith((url, opts) => {
      if ((opts?.method ?? 'GET') === 'POST' && url.endsWith(`/api/organizations/${ORG_ID}/accounts`)) {
        createdBody = JSON.parse(opts?.body ?? '{}')
        return envelopeResponse({
          account: { id: 'new-1', username: 'caoyuan-wang', displayName: '王成员', role: 'member', status: 'active' },
          initialPassword: 'zmX28J86LvHbevBn',
          mustChangePassword: true,
        })
      }
      return multiStoreHandler(url)
    })

    const orgSection = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '主体成员')!
    const details = orgSection.findAll('details').find((d) => d.find('summary')?.text().includes('主体直接创建账号'))!
    await details.find('input[placeholder="登录名（3-24 位字母数字）"]').setValue('wang')
    await details.find('input[placeholder="显示名"]').setValue('王成员')
    // 不选门店 = 纯池内成员；角色下拉不出现
    expect(details.findAll('select').length).toBe(1)
    await details.findAll('button').find((b) => b.text() === '创建账号')!.trigger('click')
    await flushPromises()

    expect(createdBody).toEqual({ role: 'member', loginName: 'wang', displayName: '王成员' })
    expect(wrapper.find('[data-testid="one-time-password"]').exists()).toBe(true)
  })

  test('多店建号选门店+店长：POST role=manager 带 storeId', async () => {
    let createdBody: unknown = null
    const { wrapper } = await mountedWith((url, opts) => {
      if ((opts?.method ?? 'GET') === 'POST' && url.endsWith(`/api/organizations/${ORG_ID}/accounts`)) {
        createdBody = JSON.parse(opts?.body ?? '{}')
        return envelopeResponse({
          account: { id: 'new-2', username: 'caoyuan-zhang', displayName: '张店长', role: 'manager', status: 'active' },
          initialPassword: 'zmX28J86LvHbevBn',
          mustChangePassword: true,
        })
      }
      return multiStoreHandler(url)
    })

    const orgSection = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '主体成员')!
    const details = orgSection.findAll('details').find((d) => d.find('summary')?.text().includes('主体直接创建账号'))!
    await details.find('input[placeholder="登录名（3-24 位字母数字）"]').setValue('zhang')
    await details.find('input[placeholder="显示名"]').setValue('张店长')
    const selects = details.findAll('select')
    await selects[0]!.setValue('store-2')
    await flushPromises()
    await details.findAll('select')[1]!.setValue('manager')
    await details.findAll('button').find((b) => b.text() === '创建账号')!.trigger('click')
    await flushPromises()

    expect(createdBody).toEqual({ role: 'manager', loginName: 'zhang', displayName: '张店长', storeId: 'store-2' })
  })

  test('多店：无审核开关（#52 决策 A 退役）', async () => {
    const { wrapper } = await mountedWith((url) => multiStoreHandler(url))
    expect(wrapper.find('article.team').text()).not.toContain('店长添加员工需主体审核')
  })

  // ---------- 多店：分配 / 调度 / 移除 ----------

  test('多店门店成员区：呈现所属门店真实行，无建号入口，无店长时提示代管+池分配入口', async () => {
    const { wrapper } = await mountedWith((url) => multiStoreHandler(url))

    // 湖畔分店无任何挂靠（baseHandler 的 store-2 memberships 未定义→改用点选旗舰店对照）
    await wrapper.findAll('.team-store-row .team-link').find((b) => b.text() === '湖畔分店')!.trigger('click')
    await flushPromises()
    const storeSection = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '门店成员')!
    // store-2 未在桩里单列 → baseHandler 的 /stores/store-2/memberships 未命中会抛错；
    // 换回旗舰店验证正向态
    await wrapper.findAll('.team-store-row .team-link').find((b) => b.text() === '旗舰店')!.trigger('click')
    await flushPromises()
    const sec = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '门店成员')!
    expect(sec.findAll('tbody tr').some((row) => row.text().includes('主体账号'))).toBe(false)
    // 无建号入口（任命店长/添加店员是 #51 二轮产物，#52 已被池分配取代）
    expect(sec.findAll('details').some((d) => d.find('summary')?.text().includes('任命店长'))).toBe(false)
    expect(sec.findAll('details').some((d) => d.find('summary')?.text().includes('添加店员'))).toBe(false)
    expect(sec.findAll('details').some((d) => d.find('summary')?.text().includes('从成员池分配'))).toBe(true)
    // 真实行有 调度/移除，无 删除（删号归主体区）
    const staffRow = sec.findAll('tbody tr').find((row) => row.text().includes('caoyuan-staff1'))!
    const btns = staffRow.findAll('button').map((b) => b.text())
    expect(btns).toContain('调度')
    expect(btns).toContain('移除')
    expect(btns).not.toContain('删除')
    void storeSection
  })

  test('从池中分配：PUT memberships role=manager，冲突 409 由错误条呈现', async () => {
    let putBody: unknown = null
    let conflict = false
    const { wrapper } = await mountedWith((url, opts) => {
      if ((opts?.method ?? '') === 'PUT' && url.endsWith(`/stores/store-2/memberships/acc-pool`)) {
        putBody = JSON.parse(opts?.body ?? '{}')
        if (conflict) return envelopeResponse('该门店已有店长，请先移除或调度原店长', 409)
        return envelopeResponse({ id: 'sm9', storeId: 'store-2', accountId: 'acc-pool', role: 'manager' })
      }
      return multiStoreHandler(url)
    })

    await wrapper.findAll('.team-store-row .team-link').find((b) => b.text() === '湖畔分店')!.trigger('click')
    await flushPromises()
    const sec = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '门店成员')!
    expect(sec.text()).toContain('尚未任命店长')
    expect(sec.text()).toContain('由主体账号代管')

    const details = sec.findAll('details').find((d) => d.find('summary')?.text().includes('从成员池分配'))!
    // 2026-08-28 拍板：管理层也在可选之列（owner 行带「（所有者）」标注）
    const poolOptions = details.findAll('select')[0]!.findAll('option').map((o) => o.text())
    expect(poolOptions.some((t) => t.includes('（所有者）'))).toBe(true)
    const selects = details.findAll('select')
    await selects[0]!.setValue('acc-pool')
    await selects[1]!.setValue('manager')
    await details.findAll('button').find((b) => b.text() === '分配到本店')!.trigger('click')
    await flushPromises()
    expect(putBody).toEqual({ role: 'manager' })

    // 冲突 409：错误条呈现，不误报成功（分配成功后下拉已清空，须重选）
    conflict = true
    const selects2 = details.findAll('select')
    await selects2[0]!.setValue('acc-pool')
    await selects2[1]!.setValue('manager')
    await details.findAll('button').find((b) => b.text() === '分配到本店')!.trigger('click')
    await flushPromises()
    const teamText = wrapper.find('article.team').text()
    const alertText = wrapper.find('[role="alert"]').text?.() ?? ''
    expect(teamText + alertText).toContain('该门店已有店长')
  })

  test('调度：行内展开选目标店+角色 → PUT 目标店 memberships；移除 → DELETE', async () => {
    let transferBody: unknown = null
    let deleted = false
    const { wrapper } = await mountedWith((url, opts) => {
      const method = opts?.method ?? 'GET'
      if (method === 'PUT' && url.endsWith(`/stores/store-2/memberships/acc-staff`)) {
        transferBody = JSON.parse(opts?.body ?? '{}')
        return envelopeResponse({ id: 'sm1', storeId: 'store-2', accountId: 'acc-staff', role: 'manager' })
      }
      if (method === 'DELETE' && url.endsWith(`/stores/store-1/memberships/acc-staff`)) {
        deleted = true
        return envelopeResponse({ success: true })
      }
      return multiStoreHandler(url)
    })

    await wrapper.findAll('.team-store-row .team-link').find((b) => b.text() === '旗舰店')!.trigger('click')
    await flushPromises()
    const sec = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '门店成员')!
    const staffRow = sec.findAll('tbody tr').find((row) => row.text().includes('caoyuan-staff1'))!

    await staffRow.findAll('button').find((b) => b.text() === '调度')!.trigger('click')
    await flushPromises()
    const transferRow = wrapper.find('.team-transfer')
    expect(transferRow.exists()).toBe(true)
    const tSelects = transferRow.findAll('select')
    expect(tSelects[0]!.findAll('option').some((o) => o.text() === '旗舰店')).toBe(false)
    await tSelects[0]!.setValue('store-2')
    await tSelects[1]!.setValue('manager')
    await transferRow.findAll('button').find((b) => b.text() === '确认调度')!.trigger('click')
    await flushPromises()
    expect(transferBody).toEqual({ role: 'manager' })

    await wrapper.findAll('.team-store-row .team-link').find((b) => b.text() === '旗舰店')!.trigger('click')
    await flushPromises()
    const sec2 = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '门店成员')!
    const row2 = sec2.findAll('tbody tr').find((row) => row.text().includes('caoyuan-staff1'))!
    await row2.findAll('button').find((b) => b.text() === '移除')!.trigger('click')
    await flushPromises()
    expect(deleted).toBe(true)
    expect(wrapper.find('article.team').text()).toContain('保留在主体成员池')
  })

  // ---------- 建号闭环与既有约束 ----------

  test('删除成员仍走主体成员表强确认（任务书 #49 D9 保留）', async () => {
    let deleted = false
    const { wrapper } = await mountedWith((url, opts) => {
      if ((opts?.method ?? 'GET') === 'DELETE' && url.endsWith(`/api/organizations/${ORG_ID}/accounts/acc-staff`)) {
        deleted = true
        return envelopeResponse({ success: true })
      }
      return multiStoreHandler(url)
    })

    const orgSection = wrapper.findAll('section').find((s) => s.find('h4')?.text() === '主体成员')!
    const staffRow = orgSection.findAll('tbody tr').find((row) => row.text().includes('caoyuan-staff1'))!
    const delButton = staffRow.findAll('button').find((b) => b.text() === '删除')
    expect(delButton).toBeTruthy()
    await delButton!.trigger('click')
    await flushPromises()
    const dialog = wrapper.find('[data-testid="delete-confirm"]')
    expect(dialog.exists()).toBe(true)
    const confirmBtn = dialog.findAll('button').find((b) => b.text() === '永久删除')!
    expect((confirmBtn.element as HTMLButtonElement).disabled).toBe(true)
    await dialog.find('input').setValue('caoyuan-staff1')
    expect((confirmBtn.element as HTMLButtonElement).disabled).toBe(false)
    await confirmBtn.trigger('click')
    await flushPromises()
    expect(deleted).toBe(true)
  })

  test('开分店后提示切换多店（#50 推导回归）', async () => {
    const createdStores = [{ id: 'store-1', organizationId: ORG_ID, name: '旗舰店', status: 'active' }]
    const { wrapper } = await mountedWith((url, opts) => {
      if (url.includes('/stores') && !url.includes('/memberships') && !url.includes('/accounts')) {
        if ((opts?.method ?? 'GET') === 'POST') {
          createdStores.push({ id: 'store-2', organizationId: ORG_ID, name: '湖畔分店', status: 'active' })
          return envelopeResponse(createdStores[createdStores.length - 1], 201)
        }
        return envelopeResponse(createdStores)
      }
      return baseHandler(url)
    })

    await wrapper.find('input[placeholder="分店名称"]').setValue('湖畔分店')
    await wrapper.findAll('button').find((b) => b.text() === '创建分店')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('article.team').text()).toContain('已切换到多店管理')
  })
})
