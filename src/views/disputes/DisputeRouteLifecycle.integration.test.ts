// @vitest-environment happy-dom
/**
 * C103-11 路由生命周期集成回归：真实 Vue Router（memory history）+ 真实 KeepAlive，
 * 只 mock 网络层（可延迟的 fetch），不 mock 路由复用/缓存/激活机制。
 * 覆盖 TC103-11-01/02/03/04/06 的路由侧行为（组件级细化见两个 composable 单测）。
 */
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { KeepAlive, defineComponent, h } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import type { Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { useAuthStore } from '../../stores/auth'
import type { AuthUser } from '../../types/auth'
import type { DisputeCase } from '../../types/grassland/dispute'
import DisputeDetailView from './DisputeDetailView.vue'
import DisputeListView from './DisputeListView.vue'

enableAutoUnmount(afterEach)

const CASE_A = '11111111-1111-4111-8111-111111111111'
const CASE_B = '22222222-2222-4222-8222-222222222222'

const userA: AuthUser = { id: 'account-a', email: 'a@example.com', role: 'user' }
const userB: AuthUser = { id: 'account-b', email: 'b@example.com', role: 'user' }

function disputeOf(id: string, overrides: Partial<DisputeCase> = {}): DisputeCase {
  return {
    id,
    engagementRef: 'eng-1',
    organizationId: 'org-1',
    openedByRole: 'recommender',
    status: 'evidence',
    kind: 'standard',
    channel: 'court',
    reason: `案件-${id.slice(0, 4)}`,
    decision: null, decidedAt: null, round: 1, version: 1,
    appealState: null, finalDecision: null, csDueAt: null, evidenceDeadline: null,
    respondentAnswered: false, claimantDoneAt: null, respondentDoneAt: null,
    taskPlatform: null, createdAt: '2026-09-01T00:00:00Z', viewerRole: 'claimant',
    ...overrides,
  }
}

const ok = (data: unknown) => new Response(JSON.stringify({ success: true, data }), { status: 200 })
const fail = (status: number, error = 'failure') =>
  new Response(JSON.stringify({ success: false, error }), { status })

const fetchMock = vi.fn()
const requestLoginSpy = vi.fn()

/** 真实路由 + KeepAlive 挂载（不 mock RouterView 复用与 activated/deactivated 机制）。 */
async function mountApp(startPath: string): Promise<{ router: Router; wrapper: ReturnType<typeof mount> }> {
  // 与 DefaultLayout 同构的路由壳：router-view 外包 KeepAlive（key 固定——换账号不销毁
  // 缓存实例，正确性由 composable 的 epoch/代次闸保证，这正是本测试要验证的场景）。
  const Shell = defineComponent({
    setup() {
      return () => h(RouterView, null, {
        default: ({ Component }: { Component: unknown }) => h(KeepAlive, () => h(Component as never, {
          onRequestLogin: () => requestLoginSpy(),
        })),
      })
    },
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/',
        component: Shell,
        children: [
          { path: '', name: 'home', component: { render: () => h('div', '首页') } },
          { path: 'me/disputes', name: 'disputes', component: DisputeListView },
          { path: 'me/disputes/:id', name: 'dispute-detail', component: DisputeDetailView },
          { path: 'other', name: 'other', component: { render: () => h('div', '其他页面') } },
        ],
      },
    ],
  })
  router.push(startPath)
  await router.isReady()

  const wrapper = mount({ render: () => h(RouterView) }, { global: { plugins: [router] } })
  await flushPromises()
  return { router, wrapper }
}

function loginAs(user: AuthUser | null): void {
  const auth = useAuthStore()
  auth.currentUser = user
  auth.loaded = true
  auth.loading = false
}

function requestedCaseIds(): string[] {
  // 只统计案件主体读取（GET /disputes/{id}）；内嵌审判看板自取的 /adjudication、
  // /votes、/judges 等同前缀请求不计入。
  return fetchMock.mock.calls
    .map((call) => String(call[0]))
    .filter((url) => /^\/api\/trust\/disputes\/[0-9a-f-]+$/.test(url))
}

beforeEach(() => {
  setActivePinia(createPinia())
  fetchMock.mockReset()
  requestLoginSpy.mockClear()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

test('TC103-11-01 冷启动匿名：列表等待登录引导、不发私有请求、不跳首页', async () => {
  const auth = useAuthStore()
  auth.loaded = true
  auth.loading = false
  const { router, wrapper } = await mountApp('/me/disputes')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/me/disputes')
  expect(fetchMock).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('登录后即可查看您的争议案件')

  // 匿名态点「去登录」走既有登录引导（request-login 事件，不自行跳转）
  await wrapper.find('.retry-btn').trigger('click')
  expect(requestLoginSpy).toHaveBeenCalledTimes(1)
})

test('TC103-11-01 冷启动身份恢复中：详情等待；恢复后取本账号案', async () => {
  const auth = useAuthStore()
  auth.loaded = false
  auth.loading = false
  const { wrapper } = await mountApp(`/me/disputes/${CASE_A}`)
  await flushPromises()
  expect(wrapper.text()).toContain('正在确认登录状态')
  expect(fetchMock).not.toHaveBeenCalled()

  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A)))
  loginAs({ ...userA })
  await flushPromises()
  expect(wrapper.text()).toContain(CASE_A.slice(0, 8))
  expect(requestedCaseIds()).toEqual([`/api/trust/disputes/${CASE_A}`])
})

test('TC103-11-02 KeepAlive 重入：A→B 组件复用清 A；离开再回 B 重新验证', async () => {
  loginAs(userA)
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A)))
  const { router, wrapper } = await mountApp(`/me/disputes/${CASE_A}`)
  await flushPromises()
  expect(wrapper.text()).toContain(CASE_A.slice(0, 8))

  // A → B：同一组件实例被复用（不重新 onMounted），watch 换目标清 A 读 B
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_B)))
  await router.push(`/me/disputes/${CASE_B}`)
  await flushPromises()
  expect(wrapper.text()).toContain(CASE_B.slice(0, 8))
  expect(wrapper.text()).not.toContain(CASE_A.slice(0, 8))
  expect(requestedCaseIds().filter((url) => url.includes(CASE_A))).toHaveLength(1)

  // 离开（缓存失活）再回 B：重激活重新读取验证
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_B)))
  await router.push('/other')
  await flushPromises()
  await router.push(`/me/disputes/${CASE_B}`)
  await flushPromises()
  expect(wrapper.text()).toContain(CASE_B.slice(0, 8))
  expect(requestedCaseIds().filter((url) => url.includes(CASE_B))).toHaveLength(2)
})

test('TC103-11-03 读取 403/404：本地分类呈现，不再重定向到列表/首页掩盖', async () => {
  loginAs(userA)
  fetchMock.mockResolvedValueOnce(fail(403, '非当事方'))
  const { router, wrapper } = await mountApp(`/me/disputes/${CASE_A}`)
  await flushPromises()
  expect(wrapper.text()).toContain('您没有权限查看该案件')
  expect(router.currentRoute.value.path).toBe(`/me/disputes/${CASE_A}`)

  fetchMock.mockResolvedValueOnce(fail(404, '不存在'))
  await router.push(`/me/disputes/${CASE_B}`)
  await flushPromises()
  expect(wrapper.text()).toContain('案件不存在或不可用')
  expect(router.currentRoute.value.path).toBe(`/me/disputes/${CASE_B}`)
})

test('TC103-11-04 账号 A→B：旧账号慢回包不显示；列表/详情均呈现当前账号数据', async () => {
  loginAs(userA)
  let resolveA!: (response: Response) => void
  fetchMock.mockImplementationOnce(() => new Promise<Response>((done) => { resolveA = done }))
  const { router, wrapper } = await mountApp(`/me/disputes/${CASE_A}`)
  await flushPromises()

  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A, { reason: 'B-账号视角', viewerRole: 'respondent' })))
  loginAs({ ...userB })
  await flushPromises()
  expect(wrapper.text()).toContain('B-账号视角')

  // A 的回包（claimant 视角文案）迟到：不得显示
  resolveA(ok(disputeOf(CASE_A, { reason: 'A-账号视角', viewerRole: 'claimant' })))
  await flushPromises()
  expect(wrapper.text()).toContain('B-账号视角')
  expect(wrapper.text()).not.toContain('A-账号视角')
  expect(router.currentRoute.value.path).toBe(`/me/disputes/${CASE_A}`)
})

test('TC103-11-02/06 列表→详情→返回列表：重激活重新读取，旧详情数据不串页', async () => {
  loginAs(userA)
  fetchMock.mockResolvedValueOnce(ok({ items: [disputeOf(CASE_A)] }))
  const { router, wrapper } = await mountApp('/me/disputes')
  await flushPromises()
  expect(wrapper.text()).toContain('进行中（1）')

  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A)))
  await router.push(`/me/disputes/${CASE_A}`)
  await flushPromises()
  expect(wrapper.text()).toContain(CASE_A.slice(0, 8))

  // 返回列表：缓存列表重激活 → 重新读取（不显示旧缓存私有数据）
  fetchMock.mockResolvedValueOnce(ok({ items: [disputeOf(CASE_A), disputeOf(CASE_B)] }))
  await router.push('/me/disputes')
  await flushPromises()
  expect(wrapper.text()).toContain('进行中（2）')
})
