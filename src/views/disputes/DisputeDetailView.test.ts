// @vitest-environment happy-dom
/**
 * C103-12 视图级回归：真实 Router（memory history）+ 真实 KeepAlive 挂载 DisputeDetailView，
 * 走「打开表单冻结上下文 → 提交 → 结果呈现」全链；只 mock 网络层。
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

enableAutoUnmount(afterEach)

const CASE_A = '11111111-1111-4111-8111-111111111111'
const CASE_B = '22222222-2222-4222-8222-222222222222'
const userA: AuthUser = { id: 'account-a', email: 'a@example.com', role: 'user' }

function disputeOf(id: string, overrides: Partial<DisputeCase> = {}): DisputeCase {
  return {
    id, engagementRef: 'eng-1', organizationId: 'org-1', openedByRole: 'recommender',
    status: 'evidence', kind: 'standard', channel: 'court', reason: `案件-${id.slice(0, 4)}`,
    decision: null, decidedAt: null, round: 1, version: 1, appealState: null,
    finalDecision: null, csDueAt: null, evidenceDeadline: null, respondentAnswered: false,
    claimantDoneAt: null, respondentDoneAt: null, taskPlatform: null,
    createdAt: '2026-09-01T00:00:00Z', viewerRole: 'respondent',
    ...overrides,
  }
}

const ok = (data: unknown) => new Response(JSON.stringify({ success: true, data }), { status: 200 })
const httpFail = (status: number, error: string) =>
  new Response(JSON.stringify({ success: false, error }), { status })
const networkError = () => Promise.reject(new TypeError('Failed to fetch'))

const fetchMock = vi.fn()

async function mountView(startPath: string): Promise<{ router: Router; wrapper: ReturnType<typeof mount> }> {
  const Shell = defineComponent({
    setup: () => () => h(RouterView, null, {
      default: ({ Component }: { Component: unknown }) => h(KeepAlive, () => h(Component as never)),
    }),
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{
      path: '/',
      component: Shell,
      children: [
        { path: '', name: 'home', component: { render: () => h('div', '首页') } },
        { path: 'me/disputes/:id', name: 'dispute-detail', component: DisputeDetailView },
      ],
    }],
  })
  router.push(startPath)
  await router.isReady()
  const wrapper = mount({ render: () => h(RouterView) }, {
    global: { plugins: [router], stubs: { teleport: true } },
  })
  await flushPromises()
  return { router, wrapper }
}

function loginAs(user: AuthUser | null): void {
  const auth = useAuthStore()
  auth.currentUser = user
  auth.loaded = true
  auth.loading = false
}

function evidenceWrites(): Array<{ url: string; body?: Record<string, unknown> }> {
  return fetchMock.mock.calls
    .filter((call) => String(call[0]).includes('/evidence'))
    .map((call) => ({ url: String(call[0]), body: JSON.parse(String(call[1]?.body ?? '{}')) }))
}

beforeEach(() => {
  setActivePinia(createPinia())
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  loginAs(userA)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

test('TC103-12-01 被诉方质证期可开答辩表单，提交固定原案', async () => {
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A)))
  const { wrapper } = await mountView(`/me/disputes/${CASE_A}`)
  await flushPromises()
  expect(wrapper.text()).toContain('提交答辩')

  await wrapper.find('button.gl-btn-primary').trigger('click')
  await flushPromises()
  expect(wrapper.find('[data-testid="evidence-form-case"]').text()).toContain(CASE_A.slice(0, 8))

  const textarea = wrapper.find('[data-testid="evidence-text"]').element as HTMLTextAreaElement
  textarea.value = '已按约定交付'
  textarea.dispatchEvent(new Event('input'))
  await flushPromises()

  fetchMock.mockResolvedValueOnce(ok({ submitted: 1 })) // 写
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A, { respondentAnswered: true }))) // 刷新
  await wrapper.find('[data-testid="evidence-submit"]').trigger('click')
  await flushPromises()

  const writes = evidenceWrites()
  expect(writes).toHaveLength(1)
  expect(writes[0]!.url).toBe(`/api/trust/disputes/${CASE_A}/evidence`)
  expect(writes[0]!.body).toMatchObject({ phase: 'answer' })
  // 成功后表单关闭、案面更新（答辩徽标出现）
  expect(wrapper.find('[data-testid="evidence-text"]').exists()).toBe(false)
  expect(wrapper.text()).toContain('被诉方已提交答辩')
})

test('TC103-12-06 表单在 A 打开后路由转 B：表单即清，未发写、B 不显示 A 任何痕迹', async () => {
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A)))
  const { router, wrapper } = await mountView(`/me/disputes/${CASE_A}`)
  await flushPromises()
  await wrapper.find('button.gl-btn-primary').trigger('click')
  await flushPromises()
  const textarea = wrapper.find('[data-testid="evidence-text"]').element as HTMLTextAreaElement
  textarea.value = 'A 案正文'
  textarea.dispatchEvent(new Event('input'))
  await flushPromises()
  expect(wrapper.find('[data-testid="evidence-form-case"]').text()).toContain(CASE_A.slice(0, 8))

  // 切案 B：表单立即关闭（切案清表单），无任何写请求发出
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_B)))
  await router.push(`/me/disputes/${CASE_B}`)
  await flushPromises()
  expect(wrapper.find('[data-testid="evidence-text"]').exists()).toBe(false)
  expect(evidenceWrites()).toHaveLength(0)
  expect(wrapper.text()).not.toContain('A 案正文')
  expect(wrapper.text()).toContain(CASE_B.slice(0, 8))
})

test('TC103-12-02 409 业务冲突：写失败提示呈现且表单保留同案可恢复输入', async () => {
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A)))
  const { wrapper } = await mountView(`/me/disputes/${CASE_A}`)
  await flushPromises()
  await wrapper.find('button.gl-btn-primary').trigger('click')
  await flushPromises()
  const textarea = wrapper.find('[data-testid="evidence-text"]').element as HTMLTextAreaElement
  textarea.value = '答辩正文'
  textarea.dispatchEvent(new Event('input'))
  await flushPromises()

  fetchMock.mockResolvedValueOnce(httpFail(409, '答辩已提交，每案至多一次'))
  await wrapper.find('[data-testid="evidence-submit"]').trigger('click')
  await flushPromises()

  expect(wrapper.find('[data-testid="dispute-write-notice"]').text()).toContain('答辩已提交，每案至多一次')
  // 表单保留（同案可恢复输入），正文未清
  const kept = wrapper.find('[data-testid="evidence-text"]').element as HTMLTextAreaElement
  expect(kept.value).toBe('答辩正文')
})

test('TC103-12-03 写后丢响应：显示待核实且不自动重发；原案回读已接受则收口', async () => {
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A)))
  const { wrapper } = await mountView(`/me/disputes/${CASE_A}`)
  await flushPromises()
  await wrapper.find('button.gl-btn-primary').trigger('click')
  await flushPromises()
  const textarea = wrapper.find('[data-testid="evidence-text"]').element as HTMLTextAreaElement
  textarea.value = '答辩正文'
  textarea.dispatchEvent(new Event('input'))
  await flushPromises()

  // 写请求网络层失败；核实回读显示服务端已接受 → 收口（案面更新）
  fetchMock.mockImplementationOnce(networkError)
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A, { respondentAnswered: true })))
  await wrapper.find('[data-testid="evidence-submit"]').trigger('click')
  await flushPromises()

  const writes = evidenceWrites()
  expect(writes).toHaveLength(1) // 不自动重发
  expect(wrapper.text()).toContain('被诉方已提交答辩')

  // 反向：回读未能证明 → 待核实提示 + 重新核实入口
  fetchMock.mockReset()
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_B, { viewerRole: 'respondent' })))
  const second = await mountView(`/me/disputes/${CASE_B}`)
  await flushPromises()
  await second.wrapper.find('button.gl-btn-primary').trigger('click')
  await flushPromises()
  const ta2 = second.wrapper.find('[data-testid="evidence-text"]').element as HTMLTextAreaElement
  ta2.value = 'B 案答辩'
  ta2.dispatchEvent(new Event('input'))
  await flushPromises()
  fetchMock.mockImplementationOnce(networkError)
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_B)))
  await second.wrapper.find('[data-testid="evidence-submit"]').trigger('click')
  await flushPromises()
  expect(second.wrapper.find('[data-testid="dispute-write-notice"]').text()).toContain('待核实')
})
