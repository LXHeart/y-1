// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { KeepAlive, defineComponent, h, onActivated, onDeactivated, onMounted, onUnmounted, ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { useAuthStore } from '../../../stores/auth'
import type { AuthUser } from '../../../types/auth'
import type { DisputeCase } from '../../../types/grassland/dispute'
import { useDisputeCaseSession } from './useDisputeCaseSession'
import { useDisputeEvidenceActions } from './useDisputeEvidenceActions'

enableAutoUnmount(afterEach)

const CASE_A = '11111111-1111-4111-8111-111111111111'
const CASE_B = '22222222-2222-4222-8222-222222222222'
const userA: AuthUser = { id: 'account-a', email: 'a@example.com', role: 'user' }

function disputeOf(id: string, overrides: Partial<DisputeCase> = {}): DisputeCase {
  return {
    id, engagementRef: 'eng-1', organizationId: 'org-1', openedByRole: 'recommender',
    status: 'evidence', kind: 'standard', channel: 'court', reason: '履行争议',
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

function host(initialCaseId: string | null = CASE_A) {
  let session!: ReturnType<typeof useDisputeCaseSession>
  let actions!: ReturnType<typeof useDisputeEvidenceActions>
  const caseIdRef = ref<string | null>(initialCaseId)
  const visible = ref(true)
  const Subject = defineComponent({
    setup() {
      session = useDisputeCaseSession({ routeCaseId: () => caseIdRef.value })
      actions = useDisputeEvidenceActions(session)
      onMounted(() => session.activate())
      onActivated(() => session.activate())
      onDeactivated(() => session.deactivate())
      onUnmounted(() => session.deactivate())
      return () => h('div')
    },
  })
  mount(defineComponent({
    setup: () => () => h(KeepAlive, null, { default: () => visible.value ? h(Subject) : null }),
  }))
  return {
    session, actions, caseIdRef,
    async show(value: boolean) {
      visible.value = value
      await flushPromises()
    },
  }
}

function loginAs(user: AuthUser | null): void {
  const auth = useAuthStore()
  auth.currentUser = user
  auth.loaded = true
  auth.loading = false
}

const items = [{ kind: 'text' as const, contentRef: '答辩内容' }]

beforeEach(() => {
  setActivePinia(createPinia())
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

test('TC103-12-02 提交前上下文校验：失效上下文本地拒绝且不发请求', async () => {
  loginAs(userA)
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A)))
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_B)))
  const { session, actions, caseIdRef } = host()
  await flushPromises()
  const context = session.captureAction()
  expect(context).not.toBeNull()

  // 切案 → 上下文失效：本地拒绝、零写请求
  caseIdRef.value = CASE_B
  await flushPromises()
  expect(context!.isCurrent()).toBe(false)

  const outcome = await actions.submitEvidence(context!, 'answer', items)
  expect(outcome).toBe('rejected_local')
  expect(fetchMock).toHaveBeenCalledTimes(2) // 只有两次读取（A 初始 + B 换案），无写请求
  expect(actions.error.value).toContain('未发送')
})

test('TC103-12-01/02 成功路径：URL 冻结自上下文、连点防重、成功后刷新原案', async () => {
  loginAs(userA)
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A)))
  const { session, actions } = host()
  await flushPromises()
  const context = session.captureAction()!

  fetchMock.mockImplementationOnce(() => {
    // 写请求挂起期间连点第二次：submitting 闸拦住（动作层由视图层防重，这里验证单发）
    return new Promise<Response>((done) => setTimeout(() => done(ok({ submitted: 1 })), 0))
  })
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A, { respondentAnswered: true }))) // 刷新回读
  const outcome = await actions.submitEvidence(context, 'answer', items)
  expect(outcome).toBe('accepted')
  const writeCall = fetchMock.mock.calls.find((call) => String(call[0]).includes('/evidence'))
  expect(writeCall).toBeDefined()
  expect(String(writeCall![0])).toBe(`/api/trust/disputes/${CASE_A}/evidence`)
  const body = JSON.parse(String(writeCall![1]?.body))
  expect(body).toMatchObject({ phase: 'answer', items: [{ kind: 'text', contentRef: '答辩内容' }] })
  expect(session.dispute.value?.respondentAnswered).toBe(true)
})

test('TC103-12-03 写后丢响应（网络层失败）：回读原案可证则收口，不自动重发', async () => {
  loginAs(userA)
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A)))
  const { session, actions } = host()
  await flushPromises()
  const context = session.captureAction()!

  // 写请求网络层失败（结果未知）
  fetchMock.mockImplementationOnce(networkError)
  // 核实回读：服务端其实已接受 → respondentAnswered=true
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A, { respondentAnswered: true })))
  const outcome = await actions.submitEvidence(context, 'answer', items)
  expect(outcome).toBe('accepted')
  // 只有：初始读 1 + 写 1 + 核实读 1 —— 无自动重发
  expect(fetchMock).toHaveBeenCalledTimes(3)
})

test('TC103-12-03 写后丢响应且无法证明：待核实，绝不自动重发', async () => {
  loginAs(userA)
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A)))
  const { session, actions } = host()
  await flushPromises()
  const context = session.captureAction()!

  fetchMock.mockImplementationOnce(networkError)
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A))) // 回读：仍未接受
  const outcome = await actions.submitEvidence(context, 'answer', items)
  expect(outcome).toBe('unverified')
  expect(fetchMock).toHaveBeenCalledTimes(3)

  // rebuttal 无案面字段可证：即便回读成功也只能待核实
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A, { respondentAnswered: true })))
  await session.refresh()
  const context2 = session.captureAction()!
  fetchMock.mockImplementationOnce(networkError)
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A, { respondentAnswered: true })))
  const outcome2 = await actions.submitEvidence(context2, 'rebuttal', items)
  expect(outcome2).toBe('unverified')
})

test('TC103-12-04/05 HTTP 确定失败：展示服务端文案（409 业务冲突），不进核实模式', async () => {
  loginAs(userA)
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A)))
  const { session, actions } = host()
  await flushPromises()
  const context = session.captureAction()!

  fetchMock.mockResolvedValueOnce(httpFail(409, '答辩已提交，每案至多一次'))
  const outcome = await actions.submitEvidence(context, 'answer', items)
  expect(outcome).toBe('failed')
  expect(actions.error.value).toBe('答辩已提交，每案至多一次')
  expect(fetchMock).toHaveBeenCalledTimes(2) // 无核实回读
})

test('TC103-12-06 已发出的写不因切案改目标；迟到回包不写进新案', async () => {
  loginAs(userA)
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A)))
  const { session, actions, caseIdRef } = host()
  await flushPromises()
  const context = session.captureAction()!

  let resolveWrite!: (response: Response) => void
  fetchMock.mockImplementationOnce(() => new Promise<Response>((done) => { resolveWrite = done }))
  const pending = actions.submitEvidence(context, 'answer', items)

  // 请求已发出后切案 B：回包仍属 A（URL 已冻结）；成功后 isCurrent false → 不触发 A 的刷新副作用
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_B)))
  caseIdRef.value = CASE_B
  await flushPromises()
  resolveWrite(ok({ submitted: 1 }))
  const outcome = await pending
  expect(outcome).toBe('accepted')
  // 全部写/读 URL：A 读 ×1、A 写 ×1、B 读 ×1 —— 没有对 B 发任何写
  const urls = fetchMock.mock.calls.map((call) => String(call[0]))
  expect(urls.filter((url) => url.includes('/evidence'))).toHaveLength(1)
  expect(urls.find((url) => url.includes('/evidence'))).toContain(CASE_A)
})

test('markEvidenceDone/startAdjudication：URL 冻结与同一票据协议', async () => {
  loginAs(userA)
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A)))
  const { session, actions } = host()
  await flushPromises()
  const context = session.captureAction()!

  // done：网络失败 → 回读可证（respondent 视角 respondentDoneAt）→ 收口
  fetchMock.mockImplementationOnce(networkError)
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A, { respondentDoneAt: '2026-09-15T00:00:00Z' })))
  const doneOutcome = await actions.markEvidenceDone(context)
  expect(doneOutcome).toBe('accepted')
  const doneCall = fetchMock.mock.calls.find((call) => String(call[0]).endsWith('/evidence-done'))
  expect(doneCall).toBeDefined()

  // start：成功路径刷新；URL 冻结
  fetchMock.mockResolvedValueOnce(ok({ status: 'voting' }))
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A, { status: 'voting' })))
  const startOutcome = await actions.startAdjudication(context)
  expect(startOutcome).toBe('accepted')
  const startCall = fetchMock.mock.calls.find((call) => String(call[0]).endsWith('/adjudicate'))
  expect(String(startCall![0])).toBe(`/api/trust/disputes/${CASE_A}/adjudicate`)
})
