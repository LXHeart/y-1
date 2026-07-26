// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import MyInvitationsCard from './MyInvitationsCard.vue'
import { useAuth } from '../composables/useAuth'
import type { AuthUser } from '../types/auth'

/**
 * 「我的邀请」卡片的**登录态**回归测试。
 *
 * 背景：浏览器实测发现原实现只在 `onMounted` 拉一次，而工作台在未登录时就已挂载
 * （App.vue 用 `<component :is>` 复用组件，切标签页不重挂载）——同一页面内登录后
 * 列表仍停在「暂无邀请」，必须手点刷新才出现。这类「组件内部状态没跟着外部状态变」
 * 的缺陷，纯 composable 测试覆盖不到，必须挂真组件才测得出，故本仓库自此引入
 * `@vue/test-utils` + happy-dom（本文件是第一个组件测试）。
 */

const { currentUser } = useAuth()

function asUser(id: string, email: string): AuthUser {
  return { id, email, displayName: email, role: 'user' }
}

const INVITATION = {
  id: 'inv-1',
  organizationId: 'org-1',
  organizationName: '示例商家',
  role: 'member',
  expiresAt: '2026-08-03T10:00:00Z',
  createdAt: '2026-07-27T10:00:00Z',
}

/**
 * 按 URL 分派的 fetch 桩，返回调用过的 URL 列表以便断言「有没有真的去拉」。
 *
 * handler 返回 `undefined` 时，响应体是 `{success:true}` **不带 data 键**——这正是后端
 * decline/revoke 的真实形状。别图省事写成 `data: null`：`useGrassland` 的 `run()` 用
 * **null 表示失败**，桩里回 null 会把成功伪装成失败，测出来的行为与线上相反。
 */
function stubFetch(handler: (url: string) => unknown): { urls: string[] } {
  const urls: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    urls.push(url)
    const data = handler(url)
    return {
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => (data === undefined ? { success: true } : { success: true, data }),
    }
  }))
  return { urls }
}

// 组件挂载共享 useAuth 的模块级 currentUser：不卸载的话，上一个用例的组件仍会响应
// 后续用例的登录事件，请求数被算重（本文件第一版就栽在这上面）。
enableAutoUnmount(afterEach)

beforeEach(() => {
  currentUser.value = null
})

afterEach(() => {
  vi.unstubAllGlobals()
  currentUser.value = null
})

describe('MyInvitationsCard 登录态', () => {
  test('未登录时不请求邀请列表', async () => {
    const { urls } = stubFetch(() => [])

    const wrapper = mount(MyInvitationsCard)
    await flushPromises()

    expect(urls).toEqual([])
    expect(wrapper.text()).toContain('暂无待接受的邀请')
  })

  test('同一页面内登录后自动拉取，无需手点刷新（原缺陷）', async () => {
    const { urls } = stubFetch(() => [INVITATION])

    const wrapper = mount(MyInvitationsCard)
    await flushPromises()
    expect(urls).toEqual([])

    // 模拟登录：currentUser 是 useAuth 的模块级共享 ref
    currentUser.value = asUser('acct-1', 'invitee@test.local')
    await flushPromises()

    expect(urls).toEqual(['/api/me/invitations'])
    expect(wrapper.text()).toContain('示例商家')
    expect(wrapper.text()).not.toContain('暂无待接受的邀请')
  })

  test('换账号重新拉取（不沿用上一个账号的结果）', async () => {
    const { urls } = stubFetch(() => [INVITATION])
    const wrapper = mount(MyInvitationsCard)

    currentUser.value = asUser('acct-1', 'a@test.local')
    await flushPromises()
    currentUser.value = asUser('acct-2', 'b@test.local')
    await flushPromises()

    expect(urls).toEqual(['/api/me/invitations', '/api/me/invitations'])
    expect(wrapper.text()).toContain('示例商家')
  })

  test('登出清空列表（不把上个账号的邀请留在界面上）', async () => {
    stubFetch(() => [INVITATION])
    const wrapper = mount(MyInvitationsCard)

    currentUser.value = asUser('acct-1', 'invitee@test.local')
    await flushPromises()
    expect(wrapper.text()).toContain('示例商家')

    currentUser.value = null
    await flushPromises()

    expect(wrapper.text()).not.toContain('示例商家')
    expect(wrapper.text()).toContain('暂无待接受的邀请')
  })
})

describe('MyInvitationsCard 接受 / 谢绝', () => {
  test('接受后向 accept 端点发 POST 并向父组件抛 joined', async () => {
    const { urls } = stubFetch((url) => url.endsWith('/accept')
      ? { organizationId: 'org-1', role: 'member', alreadyMember: false }
      : [INVITATION])
    const wrapper = mount(MyInvitationsCard)
    currentUser.value = asUser('acct-1', 'invitee@test.local')
    await flushPromises()

    await wrapper.findAll('button').find((b) => b.text() === '接受')!.trigger('click')
    await flushPromises()

    expect(urls).toContain('/api/me/invitations/inv-1/accept')
    expect(wrapper.emitted('joined')).toEqual([['org-1']])
    expect(wrapper.text()).toContain('已加入「示例商家」')
  })

  /** 后端对「本来就是成员」不报错而是回 alreadyMember，UI 必须如实说，不能假装刚加入。 */
  test('alreadyMember 时提示「本来就是成员」而非「已加入」', async () => {
    stubFetch((url) => url.endsWith('/accept')
      ? { organizationId: 'org-1', role: 'member', alreadyMember: true }
      : [INVITATION])
    const wrapper = mount(MyInvitationsCard)
    currentUser.value = asUser('acct-1', 'invitee@test.local')
    await flushPromises()

    await wrapper.findAll('button').find((b) => b.text() === '接受')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('你本来就是「示例商家」的成员')
    expect(wrapper.text()).not.toContain('已加入')
  })

  test('谢绝走 decline 端点且不抛 joined', async () => {
    // decline 成功时后端回 {success:true}，没有 data 键 → 桩返回 undefined
    const { urls } = stubFetch((url) => url.endsWith('/decline') ? undefined : [INVITATION])
    const wrapper = mount(MyInvitationsCard)
    currentUser.value = asUser('acct-1', 'invitee@test.local')
    await flushPromises()

    await wrapper.findAll('button').find((b) => b.text() === '谢绝')!.trigger('click')
    await flushPromises()

    expect(urls).toContain('/api/me/invitations/inv-1/decline')
    expect(wrapper.emitted('joined')).toBeUndefined()
    expect(wrapper.text()).toContain('已谢绝「示例商家」的邀请')
  })
})
