// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import AdminUserSuspendDialog from './AdminUserSuspendDialog.vue'

enableAutoUnmount(afterEach)

function response(data: unknown): Response {
  return {
    ok: true,
    headers: { get: () => 'application/json' },
    json: async () => ({ success: true, data }),
  } as unknown as Response
}

const ownerUser = {
  id: 'u-1', email: 'owner@example.com', displayName: '张老板', role: 'user', status: 'active',
  createdAt: '2026-01-01T00:00:00Z', balance: 0, totalEarned: 0, totalSpent: 0, roles: [],
  identities: {
    recommender: false, merchant: true, member: false,
    ownedOrgNames: '牧场一号, 牧场二号',
    ownedOrgs: [
      { id: 'org-1', name: '牧场一号', status: 'active' },
      { id: 'org-2', name: '牧场二号', status: 'active' },
    ],
  },
}

const plainUser = {
  ...ownerUser, id: 'u-2', email: 'member@example.com',
  identities: { recommender: true, merchant: false, member: true, ownedOrgNames: null },
}

function mountDialog(user: unknown, mode: 'suspend' | 'restore' = 'suspend') {
  return mount(AdminUserSuspendDialog, {
    props: { open: true, user: user as never, mode },
    global: { stubs: { Teleport: true } },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('AdminUserSuspendDialog（任务书 #72 卡D）', () => {
  test('停用模式：owner 账号确认文案含组织连带冻结警示与分别恢复说明', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response([])))
    const wrapper = mountDialog(ownerUser)

    const text = wrapper.text()
    expect(text).toContain('立即无法登录与调用')
    expect(text).toContain('一并冻结')
    expect(text).toContain('牧场一号, 牧场二号')
    expect(text).toContain('分别恢复')
    // 危险色确认按钮
    expect(wrapper.get('[data-testid="suspend-dialog-confirm"]').classes()).toContain('danger')
  })

  test('非 owner 账号不出组织连带条款', () => {
    vi.stubGlobal('fetch', vi.fn(async () => response([])))
    const wrapper = mountDialog(plainUser)
    expect(wrapper.text()).not.toContain('一并冻结')
    expect(wrapper.text()).toContain('立即无法登录与调用')
  })

  test('恢复模式：轻确认，说明组织不随本操作恢复', () => {
    vi.stubGlobal('fetch', vi.fn(async () => response([])))
    const wrapper = mountDialog(ownerUser, 'restore')
    expect(wrapper.text()).toContain('不随本操作恢复')
    expect(wrapper.get('[data-testid="suspend-dialog-confirm"]').classes()).not.toContain('danger')
  })

  test('确认后 POST 对应端点并 emit done；失败呈现错误', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url === '/api/admin/users/u-1/suspend') return response({ suspended: true })
      if (url === '/api/admin/users/u-2/restore') {
        return { ok: false, status: 409, headers: { get: () => 'application/json' },
          json: async () => ({ success: false, error: '已是该状态' }) } as unknown as Response
      }
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mountDialog(ownerUser)
    await wrapper.get('[data-testid="suspend-dialog-confirm"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('done')).toBeTruthy()

    const restoreWrapper = mountDialog(plainUser, 'restore')
    await restoreWrapper.get('[data-testid="suspend-dialog-confirm"]').trigger('click')
    await flushPromises()
    expect(restoreWrapper.emitted('done')).toBeUndefined()
    expect(restoreWrapper.text()).toContain('已是该状态')
  })
})
