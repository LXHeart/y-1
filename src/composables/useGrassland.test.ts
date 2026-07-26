import { afterEach, describe, expect, test, vi } from 'vitest'
import { useGrassland } from './useGrassland'

/**
 * 请求契约回归测试。
 *
 * 背景：e2e 联调发现 `openIdentity`/`activateIdentity` 曾把字段写成 `identityType`，
 * 而 identity-service 的 `OpenIdentityRequest`/`ActivateIdentityRequest` 要求 **`type`** → 400。
 * 这类字段名不匹配 **typecheck 抓不到**（两个名字都是合法 TS），只能靠断言实际请求体锁死。
 *
 * 注意请求/响应字段不对称：请求用 `type`，响应返回 `identityType`。
 */

function mockFetchOk(): ReturnType<typeof vi.fn> {
  const spy = vi.fn().mockResolvedValue({
    ok: true,
    headers: { get: () => 'application/json' },
    json: async () => ({ success: true, data: {} }),
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

function bodyOf(spy: ReturnType<typeof vi.fn>, callIndex = 0): Record<string, unknown> {
  const init = spy.mock.calls[callIndex][1] as RequestInit
  return JSON.parse(init.body as string) as Record<string, unknown>
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('身份端点请求契约', () => {
  test('openIdentity 发送 type 而非 identityType', async () => {
    const spy = mockFetchOk()
    const { openIdentity } = useGrassland()

    await openIdentity('merchant', 'org-1')

    const body = bodyOf(spy)
    expect(body.type).toBe('merchant')
    expect(body.organizationId).toBe('org-1')
    expect(body).not.toHaveProperty('identityType')  // 写成 identityType 后端会 400
  })

  test('openIdentity 无 org 时不带 organizationId（推荐官）', async () => {
    const spy = mockFetchOk()
    const { openIdentity } = useGrassland()

    await openIdentity('recommender')

    const body = bodyOf(spy)
    expect(body.type).toBe('recommender')
    expect(body).not.toHaveProperty('organizationId')
  })

  test('activateIdentity 发送 type', async () => {
    const spy = mockFetchOk()
    const { activateIdentity } = useGrassland()

    await activateIdentity('merchant')

    const body = bodyOf(spy)
    expect(body.type).toBe('merchant')
    expect(body).not.toHaveProperty('identityType')
  })

  test('请求携带 cookie（BFF 靠 session 换发内部断言）', async () => {
    const spy = mockFetchOk()
    const { activateIdentity } = useGrassland()

    await activateIdentity('merchant')

    const init = spy.mock.calls[0][1] as RequestInit
    expect(init.credentials).toBe('include')
  })
})

describe('202 异步轮询终态判据', () => {
  /** 按序返回若干轮询响应；用尽后固定返回最后一个。 */
  function mockFetchSequence(statuses: Array<Record<string, unknown>>): ReturnType<typeof vi.fn> {
    let call = 0
    const spy = vi.fn().mockImplementation(async () => {
      const data = statuses[Math.min(call, statuses.length - 1)]
      call += 1
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
    })
    vi.stubGlobal('fetch', spy)
    return spy
  }

  test('pollReservation 不把 pending 当终态（accept 202 后的竞态窗口）', async () => {
    // 浏览器实测缺陷：Saga 的 beginAcceptance(pending→reserving) 执行前，后端回 pending。
    // 旧判据「≠reserving 即终态」只轮询一次就收工，UI 永久停在「处理中…」。
    const spy = mockFetchSequence([
      { status: 'pending' },
      { status: 'reserving' },
      { status: 'accepted' },
    ])
    const { pollReservation } = useGrassland()

    const outcome = await pollReservation('task-1', 'app-1')

    expect(outcome).toEqual({ status: 'accepted' })
    expect(spy).toHaveBeenCalledTimes(3)  // pending 与 reserving 都必须继续轮询
  })

  test('pollReservation 在 compensated 终态停止并带出 reason', async () => {
    const spy = mockFetchSequence([
      { status: 'pending' },
      { status: 'compensated', reason: 'insufficient_funds' },
    ])
    const { pollReservation } = useGrassland()

    const outcome = await pollReservation('task-1', 'app-1')

    expect(outcome).toEqual({ status: 'compensated', reason: 'insufficient_funds' })
    expect(spy).toHaveBeenCalledTimes(2)
  })

  test('pollSettlement 不把 not_confirmed 当终态（confirm 202 后的同款竞态）', async () => {
    const spy = mockFetchSequence([
      { status: 'not_confirmed' },
      { status: 'settling' },
      { status: 'settled' },
    ])
    const { pollSettlement } = useGrassland()

    const outcome = await pollSettlement('task-1', 'app-1')

    expect(outcome).toEqual({ status: 'settled' })
    expect(spy).toHaveBeenCalledTimes(3)
  })

  test('pollSettlement 在 held 终态停止（存在未终局争议）', async () => {
    const spy = mockFetchSequence([
      { status: 'settling' },
      { status: 'held', reason: 'open_dispute' },
    ])
    const { pollSettlement } = useGrassland()

    const outcome = await pollSettlement('task-1', 'app-1')

    expect(outcome).toEqual({ status: 'held', reason: 'open_dispute' })
    expect(spy).toHaveBeenCalledTimes(2)
  })
})

describe('错误处理', () => {
  test('后端 {success:false,error} 被提取为 error 消息', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: false, error: '当前等级不可发布任务' }),
    }))
    const { createTask, error } = useGrassland()

    const result = await createTask({ organizationId: 'o', title: 't' })

    expect(result).toBeNull()
    expect(error.value).toBe('当前等级不可发布任务')
  })
})
