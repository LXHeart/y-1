import { describe, expect, it, vi, beforeEach } from 'vitest'

// 积分存储与扣减逻辑已迁入 finance-service（GL-P3-AI-001 下属切片）；legacy credit.service 现为薄 HTTP 代理。
// 本文件验证代理行为：正确的 finance 端点 / 共享密钥头 / 请求体 / 响应映射 / 错误透传。
// 扣减·退款·幂等的真实 SQL 行为由 finance 侧 CreditsServiceTest / CreditsControllerIT 覆盖。

vi.mock('../lib/env.js', () => ({
  env: {
    FINANCE_CREDITS_BASE_URL: 'http://finance:8084',
    INTERNAL_API_KEY: 'shared-secret', // secret-scan: allow - test fixture
    LOG_LEVEL: 'info',
  },
}))

const fetchMock = vi.fn()
vi.stubGlobal('fetch', fetchMock)

const {
  consumeCredit,
  refundCredit,
  awardFreeCredits,
  getCreditBalance,
  getCreditHistory,
  ensureCreditAccount,
  refundOperationId,
} = await import('./credit.service.js')

function jsonResponse(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('consumeCredit (finance proxy)', () => {
  it('POST /internal/credits/consume：带共享密钥与 {accountId, feature, operationId}，映射 data', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(200, { success: true, data: { consumed: true, balance: 9, transactionId: 'tx-1', deduplicated: false } }),
    )

    const result = await consumeCredit('user-1', 'video_analysis', 'op-1')

    expect(result).toEqual({ balance: 9, transactionId: 'tx-1', deduplicated: false })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://finance:8084/internal/credits/consume')
    expect(init.method).toBe('POST')
    expect(init.headers).toMatchObject({ 'X-Internal-Key': 'shared-secret', 'Content-Type': 'application/json' })
    expect(JSON.parse(init.body as string)).toEqual({ accountId: 'user-1', feature: 'video_analysis', operationId: 'op-1' })
  })

  it('402（积分不足）透传为 AppError(402)', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(402, { success: false, error: '积分不足' }))
    await expect(consumeCredit('user-1', 'video_analysis')).rejects.toMatchObject({
      statusCode: 402,
      message: '积分不足',
    })
  })

  it('5xx / 非 success → AppError(502)', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(500, { success: false, error: 'down' }))
    await expect(consumeCredit('user-1', 'video_analysis')).rejects.toMatchObject({ statusCode: 502 })
  })
})

describe('refundCredit (finance proxy)', () => {
  it('POST /internal/credits/refund：带 amount + 派生的 refund:<consumeId>', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(200, { success: true, data: { refunded: true, balance: 10, transactionId: 'tx-r', deduplicated: false } }),
    )

    const result = await refundCredit('user-1', 1, 'comedy_generation', '失败退回', refundOperationId('op-1'))

    expect(result).toEqual({ balance: 10, transactionId: 'tx-r', deduplicated: false })
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(init.body as string)).toEqual({
      accountId: 'user-1',
      amount: 1,
      feature: 'comedy_generation',
      note: '失败退回',
      operationId: 'refund:op-1',
    })
  })
})

describe('awardFreeCredits (finance proxy)', () => {
  it('POST /internal/credits/award：{accountId, amount, note}', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { success: true, data: { awarded: true } }))
    await awardFreeCredits('user-1', 5, '注册赠送')
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(init.body as string)).toEqual({ accountId: 'user-1', amount: 5, note: '注册赠送' })
  })
})

describe('reads (finance proxy)', () => {
  it('getCreditBalance：GET /internal/credits/balance?accountId=', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(200, { success: true, data: { balance: 3, totalEarned: 5, totalSpent: 2 } }),
    )
    const balance = await getCreditBalance('user-1')
    expect(balance).toEqual({ balance: 3, totalEarned: 5, totalSpent: 2 })
    expect((fetchMock.mock.calls[0] as [string, RequestInit])[0]).toContain(
      '/internal/credits/balance?accountId=user-1',
    )
  })

  it('getCreditHistory：GET /internal/credits/history，返回 history 数组', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(200, { success: true, data: { history: [{ id: 'x', type: 'consume' }] } }),
    )
    const history = await getCreditHistory('user-1', 10)
    expect(history).toEqual([{ id: 'x', type: 'consume' }])
    expect((fetchMock.mock.calls[0] as [string, RequestInit])[0]).toContain('limit=10')
  })
})

describe('refundOperationId', () => {
  it('由扣减 key 派生 refund: 前缀，保证一次扣减最多一次退款', () => {
    expect(refundOperationId('op-1')).toBe('refund:op-1')
    expect(refundOperationId('op-1')).toBe(refundOperationId('op-1'))
  })
})

describe('ensureCreditAccount', () => {
  it('no-op：finance 在首次写入时自动建户，不远程调用', async () => {
    await ensureCreditAccount('user-1')
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
