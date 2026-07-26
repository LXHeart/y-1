import { afterEach, describe, expect, test, vi } from 'vitest'
import { useGrassland } from './useGrassland'
import { parsePermissionMaterials } from '../types/grassland'

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

describe('materials 解析（响应是 JSON 字符串，不是对象）', () => {
  // 浏览器实测缺陷：审核卡片直接 Object.entries(req.materials)，而后端按 materials::text
  // 返回的是 JSON **字符串** → 被逐字符展开，界面显示成一列单字（0:'{', 1:'"', …）。
  // 请求侧收对象、响应侧回字符串，是 P0-1 同类的请求/响应不对称。
  test('把 JSON 字符串解析成材料表', () => {
    const raw = '{"business_license":"91310000TEST","contact_info":"13800000000"}'

    expect(parsePermissionMaterials(raw)).toEqual({
      business_license: '91310000TEST',
      contact_info: '13800000000',
    })
  })

  test('逐字符展开的老写法与正确解析结果不同（锁住回归）', () => {
    const raw = '{"business_license":"x"}'

    // 老写法：Object.entries 对字符串 → 索引到字符
    expect(Object.entries(raw)[0]).toEqual(['0', '{'])
    // 正确写法
    expect(Object.keys(parsePermissionMaterials(raw))).toEqual(['business_license'])
  })

  test('null 与坏 JSON 返回空对象，不抛（审核界面不因脏数据整块炸掉）', () => {
    expect(parsePermissionMaterials(null)).toEqual({})
    expect(parsePermissionMaterials('not json')).toEqual({})
    expect(parsePermissionMaterials('[1,2]')).toEqual({})
  })
})

describe('权限升级审核流 + 额度请求契约', () => {
  function mockFetchData(data: unknown): ReturnType<typeof vi.fn> {
    const spy = vi.fn().mockResolvedValue({
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: true, data }),
    })
    vi.stubGlobal('fetch', spy)
    return spy
  }

  test('createPermissionRequest 发送 requestedTier/materials/industry', async () => {
    const spy = mockFetchData({})
    const { createPermissionRequest } = useGrassland()

    await createPermissionRequest('org-1', {
      requestedTier: 'finance_transaction',
      materials: { business_license: '照号 123', contact_info: '13800000000' },
      industry: 'catering',
    })

    expect(spy.mock.calls[0][0]).toBe('/api/organizations/org-1/permission-requests')
    const body = bodyOf(spy)
    expect(body.requestedTier).toBe('finance_transaction')
    expect(body.industry).toBe('catering')
    expect(body.materials).toEqual({ business_license: '照号 123', contact_info: '13800000000' })
    // 后端 CreatePermissionRequest 的字段名就是这三个，写错任何一个都是 400
    expect(body).not.toHaveProperty('tier')
  })

  test('reviewPermissionRequest 发送 decision（approve/reject）', async () => {
    const spy = mockFetchData({})
    const { reviewPermissionRequest } = useGrassland()

    await reviewPermissionRequest('req-1', 'approve', '材料齐全')

    expect(spy.mock.calls[0][0]).toBe('/api/admin/permission-requests/req-1/review')
    expect(bodyOf(spy)).toEqual({ decision: 'approve', note: '材料齐全' })
  })

  test('reviewPermissionRequest 不带 note 时不发该字段', async () => {
    const spy = mockFetchData({})
    const { reviewPermissionRequest } = useGrassland()

    await reviewPermissionRequest('req-1', 'reject')

    expect(bodyOf(spy)).toEqual({ decision: 'reject' })
  })

  test('getQuota 把嵌套的 {tier,quota:{...}} 拍平给 UI', async () => {
    // 线上格式是嵌套的；拍平点只在 composable 里，UI 直接拿扁平结构
    mockFetchData({ tier: 'basic_publish', quota: { maxActiveTasks: 5, maxMonthlyTasks: 20, maxTxAmountCents: 0 } })
    const { getQuota } = useGrassland()

    const quota = await getQuota('org-1')

    expect(quota).toEqual({
      tier: 'basic_publish', maxActiveTasks: 5, maxMonthlyTasks: 20, maxTxAmountCents: 0,
    })
  })

  test('getUsage 命中 marketplace 的 /api/tasks/usage 并带 organizationId', async () => {
    const spy = mockFetchData({ organizationId: 'org-1', activeTasks: 2, monthlyTasks: 3 })
    const { getUsage } = useGrassland()

    const usage = await getUsage('org-1')

    expect(spy.mock.calls[0][0]).toBe('/api/tasks/usage?organizationId=org-1')
    expect(usage).toEqual({ organizationId: 'org-1', activeTasks: 2, monthlyTasks: 3 })
  })

  test('appealPermissionRequest 打到原申请的 appeal 子路径', async () => {
    const spy = mockFetchData({})
    const { appealPermissionRequest } = useGrassland()

    await appealPermissionRequest('org-1', 'req-9', { business_license: '补正后的照号' }, '已补材料')

    expect(spy.mock.calls[0][0]).toBe('/api/organizations/org-1/permission-requests/req-9/appeal')
    expect(bodyOf(spy)).toEqual({ materials: { business_license: '补正后的照号' }, note: '已补材料' })
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
