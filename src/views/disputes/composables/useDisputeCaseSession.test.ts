// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { KeepAlive, defineComponent, h, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { useAuthStore } from '../../../stores/auth'
import type { AuthUser } from '../../../types/auth'
import type { DisputeCase } from '../../../types/grassland/dispute'
import { useDisputeCaseSession } from './useDisputeCaseSession'

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
    reason: '履行争议',
    decision: null,
    decidedAt: null,
    round: 1,
    version: 1,
    appealState: null,
    finalDecision: null,
    csDueAt: null,
    evidenceDeadline: null,
    respondentAnswered: false,
    claimantDoneAt: null,
    respondentDoneAt: null,
    taskPlatform: null,
    createdAt: '2026-09-01T00:00:00Z',
    viewerRole: 'claimant',
    ...overrides,
  }
}

const ok = (data: unknown) => new Response(JSON.stringify({ success: true, data }), { status: 200 })
const fail = (status: number, error = 'failure') =>
  new Response(JSON.stringify({ success: false, error }), { status })

const fetchMock = vi.fn()

/**
 * KeepAlive 宿主：visible 切换驱动真实 activated/deactivated（组件在 KeepAlive 内，
 * 生命周期接线与 DisputeDetailView 一致——mock 网络但不 mock KeepAlive 机制）。
 */
function caseHost(initialCaseId: string | null = CASE_A) {
  let controller!: ReturnType<typeof useDisputeCaseSession>
  const caseIdRef = ref<string | null>(initialCaseId)
  const visible = ref(true)
  const Subject = defineComponent({
    setup() {
      controller = useDisputeCaseSession({ routeCaseId: () => caseIdRef.value })
      onMounted(() => controller.activate())
      onActivated(() => controller.activate())
      onDeactivated(() => controller.deactivate())
      onUnmounted(() => controller.deactivate())
      return () => h('div')
    },
  })
  const wrapper = mount(defineComponent({
    setup: () => () => h(KeepAlive, null, { default: () => visible.value ? h(Subject) : null }),
  }))
  return {
    controller,
    wrapper,
    caseIdRef,
    async show(value: boolean) {
      visible.value = value
      await nextTick()
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

/** 换 caseId 并等同步 watch 生效（load 已发出）。 */
async function retarget(caseIdRef: { value: string | null }, next: string): Promise<void> {
  caseIdRef.value = next
  await nextTick()
  await flushPromises()
}

beforeEach(() => {
  setActivePinia(createPinia())
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

test('TC103-11-01 身份恢复中等待；确认匿名进入匿名态且不发私有请求', async () => {
  const auth = useAuthStore()
  auth.loaded = false
  auth.loading = false
  const { controller } = caseHost()
  expect(controller.state.value).toBe('auth_pending')
  expect(fetchMock).not.toHaveBeenCalled()

  // 恢复完成 → 匿名：不发请求、按匿名态呈现（等待登录，不跳首页）
  loginAs(null)
  await flushPromises()
  expect(controller.state.value).toBe('anonymous')
  expect(fetchMock).not.toHaveBeenCalled()

  // 登录为 A 后同案重新读取
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A)))
  loginAs({ ...userA })
  await flushPromises()
  expect(controller.state.value).toBe('ready')
  expect(controller.dispute.value?.id).toBe(CASE_A)
  expect(fetchMock).toHaveBeenCalledTimes(1)
})

test('TC103-11-01 空/非法案件 ID 本地按不可用呈现，不发私有请求', async () => {
  loginAs(userA)
  const { controller, caseIdRef } = caseHost('not-a-uuid')
  await flushPromises()
  expect(controller.state.value).toBe('not_found')
  expect(fetchMock).not.toHaveBeenCalled()

  await retarget(caseIdRef, '   ')
  expect(controller.state.value).toBe('not_found')
  expect(fetchMock).not.toHaveBeenCalled()

  // 换成合法 ID 后正常读取
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A)))
  await retarget(caseIdRef, CASE_A)
  expect(controller.state.value).toBe('ready')
  expect(fetchMock).toHaveBeenCalledTimes(1)
})

test('TC103-11-02 KeepAlive 失活清私有案情并作废在途，重激活同案重新读取且不叠请求', async () => {
  loginAs(userA)
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A)))
  const { controller, show } = caseHost()
  await flushPromises()
  expect(controller.state.value).toBe('ready')
  expect(fetchMock).toHaveBeenCalledTimes(1)

  await show(false)
  expect(controller.dispute.value).toBeNull()
  expect(controller.adjudication.value).toBeNull()

  // 重激活：同案重新读取验证，且只有一次新请求（不重复叠）
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A)))
  await show(true)
  expect(fetchMock).toHaveBeenCalledTimes(2)
  expect(fetchMock.mock.calls.filter((call) => String(call[0]).includes(CASE_A))).toHaveLength(2)
  expect(controller.dispute.value?.id).toBe(CASE_A)
  expect(controller.state.value).toBe('ready')
})

test('TC103-11-03 读取错误分类本地呈现：401/403/404 不再跳转掩盖、503/网络可重试', async () => {
  loginAs(userA)
  fetchMock.mockResolvedValueOnce(fail(403, '非当事方'))
  const { controller, caseIdRef } = caseHost(CASE_A)
  await flushPromises()
  expect(controller.state.value).toBe('forbidden')
  expect(controller.dispute.value).toBeNull()

  fetchMock.mockResolvedValueOnce(fail(404, '不存在'))
  await retarget(caseIdRef, CASE_B)
  expect(controller.state.value).toBe('not_found')

  fetchMock.mockResolvedValueOnce(fail(401, '未登录'))
  await retarget(caseIdRef, CASE_A)
  expect(controller.state.value).toBe('anonymous')
  expect(controller.dispute.value).toBeNull()

  fetchMock.mockResolvedValueOnce(fail(503, '服务暂不可用'))
  await retarget(caseIdRef, CASE_B)
  expect(controller.state.value).toBe('error')
  expect(controller.error.value).toBe('服务暂不可用')

  // 本案重试成功 → ready
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_B)))
  controller.refresh()
  await flushPromises()
  expect(controller.state.value).toBe('ready')
  expect(controller.dispute.value?.id).toBe(CASE_B)
})

test('TC103-11-03 401 后同账号重新登录（epoch 不变、currentUser 换新对象）也能重新读取', async () => {
  loginAs(userA)
  fetchMock.mockResolvedValueOnce(fail(401, '会话过期'))
  const { controller } = caseHost(CASE_A)
  await flushPromises()
  expect(controller.state.value).toBe('anonymous')

  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A)))
  loginAs({ ...userA })
  await flushPromises()
  expect(controller.state.value).toBe('ready')
  expect(controller.dispute.value?.id).toBe(CASE_A)
})

test('TC103-11-03 旧请求的失败也不得显示：换案后迟到的旧案错误被代次闸拦截', async () => {
  loginAs(userA)
  let resolveA!: (response: Response) => void
  fetchMock.mockImplementationOnce(() => new Promise<Response>((done) => { resolveA = done }))
  const { controller, caseIdRef } = caseHost(CASE_A)
  await flushPromises()
  expect(controller.state.value).toBe('loading_case')

  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_B)))
  await retarget(caseIdRef, CASE_B)
  expect(controller.state.value).toBe('ready')
  expect(controller.dispute.value?.id).toBe(CASE_B)

  // A 的 503 迟到：不得覆盖 B 的就绪态
  resolveA(fail(503, '旧案错误'))
  await flushPromises()
  expect(controller.state.value).toBe('ready')
  expect(controller.dispute.value?.id).toBe(CASE_B)
  expect(controller.error.value).toBe('')
})

test('TC103-11-04 账号 A→B：旧账号迟回包不显示，viewerRole 始终来自当前服务端回包', async () => {
  loginAs(userA)
  let resolveA!: (response: Response) => void
  fetchMock.mockImplementationOnce(() => new Promise<Response>((done) => { resolveA = done }))
  const { controller } = caseHost(CASE_A)
  await flushPromises()

  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A, { viewerRole: 'respondent' })))
  loginAs({ ...userB })
  await flushPromises()
  expect(controller.state.value).toBe('ready')
  expect(controller.dispute.value?.viewerRole).toBe('respondent')

  // A 的回包（claimant 视角）迟到：不得显示
  resolveA(ok(disputeOf(CASE_A, { viewerRole: 'claimant' })))
  await flushPromises()
  expect(controller.dispute.value?.viewerRole).toBe('respondent')
})

test('TC103-11-05 数据边界：open 存量案不取审判快照、null 截止原样呈现；voting 案同上下文取快照', async () => {
  loginAs(userA)
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A, {
    status: 'open',
    evidenceDeadline: null,
    reason: null,
  })))
  const { controller, caseIdRef } = caseHost(CASE_A)
  await flushPromises()
  expect(controller.state.value).toBe('ready')
  expect(controller.dispute.value?.evidenceDeadline).toBeNull()
  expect(controller.adjudication.value).toBeNull()
  // open 态无需快照：只应有案件一次请求
  expect(fetchMock).toHaveBeenCalledTimes(1)

  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_B, { status: 'voting' })))
  fetchMock.mockResolvedValueOnce(ok({
    id: CASE_B, status: 'voting', round: 1, decision: null, appealState: null,
    finalDecision: null, decidedAt: null,
    panel: { size: 7, voted: 3 },
    tallies: { forMerchant: 1, forRecommender: 1, abstain: 1, panelSize: 7, majority: null },
    window: { phase: 'vote', durationSeconds: 3600, startedAt: null, deadline: null, remainingSeconds: null },
  }))
  await retarget(caseIdRef, CASE_B)
  expect(controller.dispute.value?.id).toBe(CASE_B)
  expect(controller.adjudication.value?.panel.size).toBe(7)
  // 案件与快照两次请求都指向同一 caseId
  const urls = fetchMock.mock.calls.map((call) => String(call[0]))
  expect(urls.filter((url) => url.includes(CASE_B))).toHaveLength(2)
})

test('TC103-11-06 乱序与卸载：A慢B快再A，迟到 success/error/finally 均不得覆盖新目标', async () => {
  loginAs(userA)
  let resolveA1!: (response: Response) => void
  fetchMock.mockImplementationOnce(() => new Promise<Response>((done) => { resolveA1 = done }))
  const { controller, caseIdRef } = caseHost(CASE_A)
  await flushPromises()

  // A（慢）→ B（快，成功）
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_B)))
  await retarget(caseIdRef, CASE_B)
  expect(controller.dispute.value?.id).toBe(CASE_B)

  // 再回 A：进入 loading_case 且旧数据已清
  let resolveA2!: (response: Response) => void
  fetchMock.mockImplementationOnce(() => new Promise<Response>((done) => { resolveA2 = done }))
  await retarget(caseIdRef, CASE_A)
  expect(controller.state.value).toBe('loading_case')
  expect(controller.dispute.value).toBeNull()

  // 最早的 A1 迟到成功：代次已过，不得写回
  resolveA1(ok(disputeOf(CASE_A)))
  await flushPromises()
  expect(controller.state.value).toBe('loading_case')
  expect(controller.dispute.value).toBeNull()

  // 最新 A2 失败：属于当前代次，可显示
  resolveA2(fail(503, '当前案失败'))
  await flushPromises()
  expect(controller.state.value).toBe('error')
  expect(controller.error.value).toBe('当前案失败')
})

test('captureAction 仅在 ready 且目标一致时产出有效上下文；目标漂移即失效', async () => {
  loginAs(userA)
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A)))
  const { controller, caseIdRef, show } = caseHost(CASE_A)
  await flushPromises()

  const context = controller.captureAction()
  expect(context).not.toBeNull()
  expect(context).toMatchObject({
    accountId: 'account-a',
    disputeId: CASE_A,
    activationGeneration: expect.any(Number),
  })
  expect(context!.epoch).toBeGreaterThan(0)
  expect(context!.isCurrent()).toBe(true)

  // 切案：旧上下文失效（loadedDispute 已清），未就绪时不再产出
  fetchMock.mockImplementationOnce(() => new Promise<Response>(() => {}))
  await retarget(caseIdRef, CASE_B)
  expect(context!.isCurrent()).toBe(false)
  expect(controller.captureAction()).toBeNull()

  // 失活（KeepAlive 离开）：上下文失效
  fetchMock.mockResolvedValueOnce(ok(disputeOf(CASE_A)))
  await retarget(caseIdRef, CASE_A)
  const context2 = controller.captureAction()
  expect(context2!.isCurrent()).toBe(true)
  await show(false)
  expect(context2!.isCurrent()).toBe(false)
  expect(controller.captureAction()).toBeNull()
})
