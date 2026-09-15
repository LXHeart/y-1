// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { KeepAlive, defineComponent, h, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { useAuthStore } from '../../../stores/auth'
import type { AuthUser } from '../../../types/auth'
import type { DisputeCase } from '../../../types/grassland/dispute'
import { useDisputeListSession } from './useDisputeListSession'

enableAutoUnmount(afterEach)

const userA: AuthUser = { id: 'account-a', email: 'a@example.com', role: 'user' }
const userB: AuthUser = { id: 'account-b', email: 'b@example.com', role: 'user' }

function itemOf(id: string): DisputeCase {
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
  }
}

const ok = (data: unknown) => new Response(JSON.stringify({ success: true, data }), { status: 200 })
const fail = (status: number, error = 'failure') =>
  new Response(JSON.stringify({ success: false, error }), { status })

const fetchMock = vi.fn()

/** KeepAlive 宿主：生命周期接线与 DisputeListView 一致（mock 网络、不 mock KeepAlive）。 */
function listHost() {
  let controller!: ReturnType<typeof useDisputeListSession>
  const visible = ref(true)
  const Subject = defineComponent({
    setup() {
      controller = useDisputeListSession()
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

beforeEach(() => {
  setActivePinia(createPinia())
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

test('TC103-11-01 身份恢复中等待不发请求；匿名进入匿名态；登录后取本账号列表', async () => {
  const auth = useAuthStore()
  auth.loaded = false
  auth.loading = false
  const { controller } = listHost()
  expect(controller.state.value).toBe('auth_pending')
  expect(fetchMock).not.toHaveBeenCalled()

  loginAs(null)
  await flushPromises()
  expect(controller.state.value).toBe('anonymous')
  expect(fetchMock).not.toHaveBeenCalled()

  fetchMock.mockResolvedValueOnce(ok({ items: [itemOf('d-1')] }))
  loginAs({ ...userA })
  await flushPromises()
  expect(controller.state.value).toBe('ready')
  expect(controller.items.value).toHaveLength(1)
  expect(fetchMock).toHaveBeenCalledTimes(1)
})

test('TC103-11-01/08 空列表为明确 ready 空态；缺失 items 字段按空处理', async () => {
  loginAs(userA)
  fetchMock.mockResolvedValueOnce(ok({}))
  const { controller, wrapper } = listHost()
  await flushPromises()
  expect(controller.state.value).toBe('ready')
  expect(controller.items.value).toEqual([])
  wrapper.unmount()
})

test('当前账号内加载去重：在途时重复 refresh/重激活不叠请求', async () => {
  loginAs(userA)
  let resolve!: (response: Response) => void
  fetchMock.mockImplementation(() => new Promise<Response>((done) => { resolve = done }))
  const { controller } = listHost()
  await flushPromises()
  expect(fetchMock).toHaveBeenCalledTimes(1)

  controller.refresh()
  controller.refresh()
  await flushPromises()
  // 同账号同代次：第二次在途期间不重复发（仍然只有 1 次）
  expect(fetchMock).toHaveBeenCalledTimes(1)

  resolve(ok({ items: [itemOf('d-1')] }))
  await flushPromises()
  expect(controller.state.value).toBe('ready')

  // 完成后再 refresh：允许新一次读取
  fetchMock.mockResolvedValueOnce(ok({ items: [itemOf('d-1'), itemOf('d-2')] }))
  controller.refresh()
  await flushPromises()
  expect(fetchMock).toHaveBeenCalledTimes(2)
  expect(controller.items.value).toHaveLength(2)
})

test('TC103-11-03 401→匿名态；503/网络错误本地呈现并可重试；不跳首页', async () => {
  loginAs(userA)
  fetchMock.mockResolvedValueOnce(fail(503, '服务暂不可用'))
  const { controller } = listHost()
  await flushPromises()
  expect(controller.state.value).toBe('error')
  expect(controller.error.value).toBe('服务暂不可用')

  fetchMock.mockResolvedValueOnce(fail(401, '未登录'))
  controller.refresh()
  await flushPromises()
  expect(controller.state.value).toBe('anonymous')
  expect(controller.items.value).toEqual([])

  // 重新登录同账号（currentUser 新对象、epoch 不变）后重读
  fetchMock.mockResolvedValueOnce(ok({ items: [itemOf('d-1')] }))
  loginAs({ ...userA })
  await flushPromises()
  expect(controller.state.value).toBe('ready')
  expect(controller.items.value).toHaveLength(1)
})

test('TC103-11-04 换账号 A→B：立即清 A 列表；A 迟回包不显示，B 数据呈现', async () => {
  loginAs(userA)
  let resolveA!: (response: Response) => void
  fetchMock.mockImplementationOnce(() => new Promise<Response>((done) => { resolveA = done }))
  const { controller } = listHost()
  await flushPromises()

  fetchMock.mockResolvedValueOnce(ok({ items: [itemOf('d-b1')] }))
  loginAs({ ...userB })
  await flushPromises()
  expect(controller.items.value).toHaveLength(1)
  expect(controller.items.value[0]?.id).toBe('d-b1')

  // A 的列表迟到：不得写回（账号 A→B→A 旧票也失效）
  resolveA(ok({ items: [itemOf('d-a1'), itemOf('d-a2')] }))
  await flushPromises()
  expect(controller.items.value).toHaveLength(1)
  expect(controller.items.value[0]?.id).toBe('d-b1')
})

test('TC103-11-02 KeepAlive 失活清列表；重激活重新读取', async () => {
  loginAs(userA)
  fetchMock.mockResolvedValueOnce(ok({ items: [itemOf('d-1')] }))
  const { controller, show } = listHost()
  await flushPromises()
  expect(controller.items.value).toHaveLength(1)

  await show(false)
  expect(controller.items.value).toEqual([])

  fetchMock.mockResolvedValueOnce(ok({ items: [itemOf('d-1')] }))
  await show(true)
  expect(fetchMock).toHaveBeenCalledTimes(2)
  expect(controller.items.value).toHaveLength(1)
  expect(controller.state.value).toBe('ready')
})
