// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import AdminUserResetPasswordDialog from './AdminUserResetPasswordDialog.vue'

enableAutoUnmount(afterEach)

function response(data: unknown): Response {
  return {
    ok: true,
    headers: { get: () => 'application/json' },
    json: async () => ({ success: true, data }),
  } as unknown as Response
}

const user = {
  id: 'u-1', email: 'target@example.com', displayName: null, role: 'user', status: 'active',
  createdAt: '2026-01-01T00:00:00Z', balance: 0, totalEarned: 0, totalSpent: 0, roles: [], identities: undefined,
}

function mountDialog() {
  return mount(AdminUserResetPasswordDialog, {
    props: { open: true, user: user as never },
    global: { stubs: { Teleport: true } },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('AdminUserResetPasswordDialog（任务书 #72 卡D）', () => {
  test('两段式：确认段说明会话失效与首登改密；提交后展示一次性明文 + 复制 + 警示', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url === '/api/admin/users/u-1/reset-password') {
        return response({ initialPassword: 'Abcd1234Efgh5678' })
      }
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const writeText = vi.fn(async () => {})
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })

    const wrapper = mountDialog()
    // 确认段：三条后果说明，无明文
    const text = wrapper.text()
    expect(text).toContain('全部登录会话立即失效')
    expect(text).toContain('强制修改密码')
    expect(wrapper.find('[data-testid="reset-initial-password"]').exists()).toBe(false)

    await wrapper.get('[data-testid="reset-dialog-confirm"]').trigger('click')
    await flushPromises()

    // 结果段：等宽明文 + 复制按钮 + 仅本次展示警示
    expect(wrapper.get('[data-testid="reset-initial-password"]').text()).toBe('Abcd1234Efgh5678')
    expect(wrapper.text()).toContain('仅本次展示')
    await wrapper.get('[data-testid="copy-reset-password"]').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith('Abcd1234Efgh5678')
    expect(wrapper.get('[data-testid="copy-reset-password"]').text()).toBe('已复制')

    // 完成按钮 emit done
    await wrapper.get('[data-testid="reset-done"]').trigger('click')
    expect(wrapper.emitted('done')).toBeTruthy()
  })

  test('提交失败停留在确认段并呈现错误', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: false, status: 403, headers: { get: () => 'application/json' },
      json: async () => ({ success: false, error: '权限不足' }),
    } as unknown as Response)))
    const wrapper = mountDialog()

    await wrapper.get('[data-testid="reset-dialog-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('权限不足')
    expect(wrapper.find('[data-testid="reset-initial-password"]').exists()).toBe(false)
    expect(wrapper.emitted('done')).toBeUndefined()
  })
})
