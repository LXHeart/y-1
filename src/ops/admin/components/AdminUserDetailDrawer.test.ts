// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import AdminUserDetailDrawer from './AdminUserDetailDrawer.vue'

enableAutoUnmount(afterEach)

function response(data: unknown): Response {
  return {
    ok: true,
    headers: { get: () => 'application/json' },
    json: async () => ({ success: true, data }),
  } as unknown as Response
}

const baseUser = {
  id: 'u-1', email: 'owner@example.com', displayName: '张老板', role: 'user', status: 'active',
  createdAt: '2026-01-01T00:00:00Z', balance: 5, totalEarned: 10, totalSpent: 5,
  roles: ['content_reviewer'],
  identities: {
    recommender: true, merchant: true, member: false,
    ownedOrgNames: '牧场一号',
    ownedOrgs: [
      { id: 'org-1', name: '牧场一号', status: 'active' },
      { id: 'org-2', name: '牧场二号', status: 'suspended' },
    ],
  },
}

const auditEntries = [
  { id: 'a-1', action: 'switched_to_merchant', ipAddress: '1.1.1.1', userAgent: 'ua-1', occurredAt: '2026-01-02T00:00:00Z' },
  { id: 'a-2', action: 'switched_to_consumer', ipAddress: '2.2.2.2', userAgent: 'ua-2', occurredAt: '2026-01-03T00:00:00Z' },
]

function mountDrawer(user: unknown = baseUser, admin = true) {
  return mount(AdminUserDetailDrawer, {
    props: { user: user as never, admin },
    global: { stubs: { Teleport: true } },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('AdminUserDetailDrawer（任务书 #72 卡D）', () => {
  test('六分区渲染：基本/身份档案/所属组织/后台角色/积分摘要/审计时间线（倒序）', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url === '/api/admin/users/u-1/audit') return response(auditEntries)
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountDrawer()
    await flushPromises()

    expect(wrapper.find('[data-testid="detail-section-basic"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="detail-section-identities"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="detail-section-organizations"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="detail-section-roles"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="detail-section-credits"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="detail-section-audit"]').exists()).toBe(true)

    // 基本：邮箱/ID/状态徽章
    const basic = wrapper.find('[data-testid="detail-section-basic"]').text()
    expect(basic).toContain('owner@example.com')
    expect(basic).toContain('u-1')
    expect(basic).toContain('正常')
    // 身份档案：推荐官/商家 + 组织名 + 冻结徽章（org-2 suspended）
    const identities = wrapper.find('[data-testid="detail-section-identities"]').text()
    expect(identities).toContain('推荐官')
    expect(identities).toContain('牧场一号')
    expect(identities).toContain('组织已冻结')
    // 所属组织：两主体 + 状态徽章 + active 侧给冻结钮/suspended 侧给恢复钮
    const orgSection = wrapper.find('[data-testid="detail-section-organizations"]')
    expect(orgSection.text()).toContain('牧场二号')
    expect(orgSection.findAll('.org-freeze-btn')).toHaveLength(1)
    expect(orgSection.findAll('.org-restore-btn')).toHaveLength(1)
    // 后台角色 chips
    expect(wrapper.find('[data-testid="detail-section-roles"]').text()).toContain('content_reviewer')
    // 积分摘要三项 + 调整积分入口
    const credits = wrapper.find('[data-testid="detail-section-credits"]').text()
    expect(credits).toContain('积分余额')
    expect(wrapper.find('.adjust-entry-btn').text()).toBe('调整积分')
    // 审计时间线：倒序（a-2 在前）
    const actions = wrapper.findAll('.audit-action').map((node) => node.text())
    expect(actions).toEqual(['switched_to_consumer', 'switched_to_merchant'])
  })

  test('审计为空时显示「暂无记录」；身份档案纯展示无任何变更控件（D5）', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url === '/api/admin/users/u-1/audit') return response([])
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountDrawer()
    await flushPromises()

    expect(wrapper.find('[data-testid="audit-empty"]').text()).toBe('暂无记录')
    // D5 红线：身份档案分区没有任何可交互控件（无 button/input/select）
    const section = wrapper.find('[data-testid="detail-section-identities"]')
    expect(section.find('button').exists()).toBe(false)
    expect(section.find('input').exists()).toBe(false)
    expect(section.find('select').exists()).toBe(false)
    expect(section.text()).toContain('只读')
  })

  test('角色授予/回收发正确 PUT；platform_admin 授予需二次确认', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url === '/api/admin/users/u-1/audit') return response([])
      if (url === '/api/admin/users/u-1/roles') return response({ granted: true })
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountDrawer()
    await flushPromises()

    // 普通角色：点击授予直接发 PUT {action:'grant', role:'customer_service'}
    await wrapper.get('[data-testid="role-select"]').setValue('customer_service')
    await wrapper.get('.grant-btn').trigger('click')
    await flushPromises()
    const grantCall = fetchMock.mock.calls.find(([url]) => url === '/api/admin/users/u-1/roles')
    expect(JSON.parse((grantCall?.[1] as RequestInit).body as string))
      .toEqual({ action: 'grant', role: 'customer_service' })

    // platform_admin：首次点击只出强确认，不发请求
    fetchMock.mockClear()
    await wrapper.get('[data-testid="role-select"]').setValue('platform_admin')
    await wrapper.get('.grant-btn').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="grant-admin-confirm"]').exists()).toBe(true)
    expect(fetchMock.mock.calls.some(([url]) => url === '/api/admin/users/u-1/roles')).toBe(false)

    // 确认后才发请求
    await wrapper.get('[data-testid="grant-admin-confirm"] .btn-confirm').trigger('click')
    await flushPromises()
    const adminGrant = fetchMock.mock.calls.find(([url]) => url === '/api/admin/users/u-1/roles')
    expect(JSON.parse((adminGrant?.[1] as RequestInit).body as string))
      .toEqual({ action: 'grant', role: 'platform_admin' })

    // 回收
    await wrapper.get('[data-testid="role-select"]').setValue('risk')
    await wrapper.get('.revoke-btn').trigger('click')
    await flushPromises()
    const revokeCall = fetchMock.mock.calls.filter(([url]) => url === '/api/admin/users/u-1/roles').pop()
    expect(JSON.parse((revokeCall?.[1] as RequestInit).body as string)).toEqual({ action: 'revoke', role: 'risk' })
  })

  test('组织冻结/恢复二次确认后 POST 组织端点并 emit refresh；非 admin 不出按钮', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url === '/api/admin/users/u-1/audit') return response([])
      if (url === '/api/admin/organizations/org-1/suspend') return response({ suspended: true })
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountDrawer()
    await flushPromises()

    // 冻结 active 组织：点击只出确认行，不发请求
    await wrapper.get('.org-freeze-btn').trigger('click')
    expect(wrapper.find('[data-testid="org-action-confirm"]').exists()).toBe(true)
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/api/admin/organizations/'))).toBe(false)

    await wrapper.get('[data-testid="org-action-confirm"] .btn-confirm').trigger('click')
    await flushPromises()
    expect(fetchMock.mock.calls.some(([url]) => url === '/api/admin/organizations/org-1/suspend')).toBe(true)
    expect(wrapper.emitted('refresh')).toBeTruthy()

    // 非 admin（客服/风控视角）：组织管控与角色编辑分区整体隐藏
    const viewerWrapper = mountDrawer(baseUser, false)
    await flushPromises()
    expect(viewerWrapper.find('[data-testid="detail-section-roles"]').exists()).toBe(false)
    expect(viewerWrapper.find('.org-freeze-btn').exists()).toBe(false)
    expect(viewerWrapper.find('.org-restore-btn').exists()).toBe(false)
    expect(viewerWrapper.find('.adjust-entry-btn').exists()).toBe(false)
    expect(viewerWrapper.find('[data-testid="detail-section-organizations"]').exists()).toBe(true)
  })
})
