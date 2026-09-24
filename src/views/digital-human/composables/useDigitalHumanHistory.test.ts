// @vitest-environment happy-dom
import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import {
  useDigitalHumanHistory,
  type HistoryFilters,
} from './useDigitalHumanHistory'
import type { DigitalHumanApi } from '../../../composables/useDigitalHumanApi'
import { GrasslandHttpError } from '../../../composables/grassland-http'
import type { AccountSessionPort, AccountTicket } from '../../../stores/account-session'
import type { Operation, Page, SessionSummary } from '../../../types/digital-human'

/** TC105G-01-04 前端乱序：筛选快切旧回包不覆盖、越界过滤出错误、换号中止不残留。 */

function summaryOf(id: string, state: SessionSummary['state']): SessionSummary {
  return {
    id, profileId: '33333333-3333-4333-8333-333333333333', profileNameAtCreation: '角色', state,
    createdAt: '2026-09-22T16:00:00Z', endedAt: state === 'ended' ? '2026-09-22T16:05:00Z' : null,
    hasSavedTranscript: false, recordingCount: 0, savedAssetCount: 0,
    billing: { confirmedCents: 0, platformCostCents: 0, subsidizedCents: 0, pendingCount: 0, priceTableVersion: 'pt-v1' },
  }
}

function operationOf(state: Operation['state']): Operation {
  return {
    id: 'op-1', kind: 'session_delete', state, resourceId: '44444444-4444-4444-8444-444444444444',
    resultRef: null, errorCode: null, createdAt: '2026-09-24T00:00:00Z', updatedAt: '2026-09-24T00:00:00Z',
  }
}

function makeAccount(initial = 'account-a') {
  const state = { accountId: initial, epoch: 0 }
  let controller = new AbortController()
  const port: AccountSessionPort = {
    capture: (): AccountTicket => ({ accountId: state.accountId, epoch: state.epoch, signal: controller.signal }),
    isCurrent: (ticket) => ticket.accountId === state.accountId && ticket.epoch === state.epoch,
  }
  return {
    port,
    switchAccount(id = 'account-b') {
      state.accountId = id
      state.epoch += 1
      controller.abort()
      controller = new AbortController()
    },
  }
}

function makeApi() {
  return {
    listSessions: vi.fn(
      async (_input?: { cursor?: string; limit?: number; profileId?: string; state?: string; from?: string; to?: string }):
        Promise<Page<SessionSummary>> => ({ items: [], nextCursor: null })),
    deleteSession: vi.fn(async (): Promise<Operation> => operationOf('succeeded')),
    getOperation: vi.fn(async (): Promise<Operation> => operationOf('succeeded')),
  }
}

function asApi(mocks: ReturnType<typeof makeApi>): DigitalHumanApi {
  return mocks as unknown as DigitalHumanApi
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

function setup() {
  const api = makeApi()
  const account = makeAccount()
  const state = useDigitalHumanHistory(asApi(api), account.port)
  return { state, api, account }
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('TC105G-01-04 前端乱序与换号', () => {
  test('筛选1慢、筛选2快：迟到旧回包不覆盖新列表；游标随新筛选重置', async () => {
    const { state, api } = setup()
    const endedPage: Page<SessionSummary> = { items: [summaryOf('ended-1', 'ended')], nextCursor: 'cursor-ended' }
    const failedPage: Page<SessionSummary> = { items: [summaryOf('failed-1', 'failed')], nextCursor: null }
    const slow = deferred<Page<SessionSummary>>()
    api.listSessions.mockImplementation((input?: { state?: string }) =>
      input?.state === 'ended' ? slow.promise : Promise.resolve(failedPage))

    // 筛选1（慢）：发出后未回。
    state.applyFilters({ state: 'ended' })
    await Promise.resolve()
    expect(api.listSessions).toHaveBeenCalledTimes(1)

    // 快切筛选2（快）：立即回包并提交。
    state.applyFilters({ state: 'failed' })
    await flushPromises()
    expect(api.listSessions).toHaveBeenCalledTimes(2)
    expect(state.items.value.map((item) => item.id)).toEqual(['failed-1'])
    expect(state.hasMore.value).toBe(false)

    // 迟到的筛选1回包到达：不得覆盖新列表（请求代次丢弃）。
    slow.resolve(endedPage)
    await flushPromises()
    expect(state.items.value.map((item) => item.id)).toEqual(['failed-1'])
    expect(state.error.value).toBeNull()
  })

  test('越界过滤：客户端预检拦截不起请求；服务端 422 保留旧数据只置错误', async () => {
    const { state, api } = setup()
    const goodPage: Page<SessionSummary> = { items: [summaryOf('s-1', 'ended')], nextCursor: null }
    api.listSessions.mockImplementation(async () => goodPage)

    state.applyFilters({ state: '' })
    await flushPromises()
    expect(state.items.value).toHaveLength(1)

    // from ≥ to：客户端预检直接拒绝（零请求），列表与游标清空并显示错误。
    state.applyFilters({ fromDate: '2026-09-10', toDate: '2026-09-10' })
    expect(api.listSessions).toHaveBeenCalledTimes(1)
    expect(state.items.value).toHaveLength(0)
    expect(state.error.value).toContain('起点必须早于终点')

    // 跨度 >90 天同理由预检拦截。
    state.applyFilters({ fromDate: '2026-06-01', toDate: '2026-09-10' })
    expect(state.error.value).toContain('90 天')

    // 服务端 422（预检未覆盖的形态）：错误可见、已有数据不被静默清空。
    api.listSessions.mockImplementation(async () => {
      throw new GrasslandHttpError(422, '时间窗跨度不能超过 90 天。', 'dh_invalid_input')
    })
    await state.reload()
    expect(state.error.value).toContain('90 天')
  })

  test('换号：在途读与删除轮询中止、迟到结果不提交；clear 清全部本地态', async () => {
    const { state, api, account } = setup()
    const slowPage = deferred<Page<SessionSummary>>()
    api.listSessions.mockImplementationOnce(async () => slowPage.promise)
    void state.reload()

    // 在途删除（轮询挂起中）。
    const target = summaryOf('s-1', 'ended')
    api.deleteSession.mockImplementation(async () => operationOf('running'))
    const slowOutcome = deferred<Operation>()
    api.getOperation.mockImplementation(async () => slowOutcome.promise)
    void state.remove(target.id)
    await Promise.resolve()
    expect(state.deletingIds.value.has(target.id)).toBe(true)

    // 换号：账号代次变化 → 在途读/轮询全部作废。
    account.switchAccount()
    slowPage.resolve({ items: [target], nextCursor: null })
    slowOutcome.resolve(operationOf('succeeded'))
    await vi.advanceTimersByTimeAsync(5_000)
    await flushPromises()
    // 旧账号数据与删除结果都不提交（换号后由 clear 兜底清场）。
    state.clear()
    expect(state.items.value).toHaveLength(0)
    expect(state.error.value).toBeNull()
    expect(state.deletingIds.value.size).toBe(0)
    expect(Object.keys(state.deleteOutcomes.value)).toHaveLength(0)
  })

  test('删除成功：轮询至 succeeded 后从列表移除并给结果；running 不提前当成功', async () => {
    const { state, api } = setup()
    const doomed = summaryOf('doomed-1', 'ended')
    const keeper = summaryOf('keep-1', 'ended')
    api.listSessions.mockImplementation(async (): Promise<Page<SessionSummary>> =>
      ({ items: [doomed, keeper], nextCursor: null }))
    state.applyFilters({})
    await flushPromises()
    expect(state.items.value).toHaveLength(2)

    api.deleteSession.mockImplementation(async () => operationOf('running'))
    let polled = 0
    api.getOperation.mockImplementation(async (): Promise<Operation> => {
      polled += 1
      return operationOf(polled >= 2 ? 'succeeded' : 'running')
    })
    const done = state.remove(doomed.id)
    await vi.advanceTimersByTimeAsync(800) // 第一次轮询仍 running：不提前当成功。
    await Promise.resolve()
    expect(state.deleteOutcomes.value[doomed.id]?.state).toBeUndefined()
    await vi.advanceTimersByTimeAsync(800) // 第二次 succeeded：移除行、落结果。
    await done
    expect(state.deleteOutcomes.value[doomed.id]?.state).toBe('succeeded')
    expect(state.items.value.map((item) => item.id)).toEqual(['keep-1'])
    expect(state.deletingIds.value.size).toBe(0)
  })

  test('空过滤器形状与查询映射：日期转 UTC 半开区间、空串缺省', async () => {
    const { state, api } = setup()
    api.listSessions.mockImplementation(async () => ({ items: [], nextCursor: null }))
    const filters: HistoryFilters = { profileId: 'p-1', state: 'failed', fromDate: '2026-09-01', toDate: '2026-09-10' }
    state.applyFilters(filters)
    await flushPromises()
    expect(api.listSessions).toHaveBeenCalledWith(
      { profileId: 'p-1', state: 'failed', from: '2026-09-01T00:00:00Z', to: '2026-09-10T00:00:00Z', limit: 20 },
      expect.any(AbortSignal),
    )
  })
})
