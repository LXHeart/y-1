import { describe, expect, it, vi, beforeEach } from 'vitest'

const { queryDbMock, withDbTransactionMock, txQueryMock } = vi.hoisted(() => ({
  queryDbMock: vi.fn(),
  withDbTransactionMock: vi.fn(),
  txQueryMock: vi.fn(),
}))

vi.mock('../lib/db.js', () => ({
  queryDb: queryDbMock,
  withDbTransaction: withDbTransactionMock,
}))

const { consumeCredit, refundCredit, refundOperationId } = await import('./credit.service.js')
const { AppError } = await import('../lib/errors.js')

/** 真事务语义：handler 抛错则整体失败（余额+流水都不生效）。 */
function runTransaction(): void {
  withDbTransactionMock.mockImplementation(async (handler: (tx: unknown) => Promise<unknown>) =>
    handler({ query: txQueryMock }),
  )
}

function uniqueViolation(): Error & { code: string } {
  return Object.assign(new Error('duplicate key'), { code: '23505' })
}

beforeEach(() => {
  vi.clearAllMocks()
  runTransaction()
})

describe('consumeCredit', () => {
  it('余额与流水写在同一事务内', async () => {
    txQueryMock
      .mockResolvedValueOnce({ rows: [{ balance: 9 }] })
      .mockResolvedValueOnce({ rows: [{ id: 'tx-1' }] })

    const result = await consumeCredit('user-1', 'video_analysis')

    expect(withDbTransactionMock).toHaveBeenCalledTimes(1)
    expect(result).toEqual({ balance: 9, transactionId: 'tx-1', deduplicated: false })
    // 两条语句都走同一个 tx.query，而不是各自独立的 queryDb
    expect(txQueryMock).toHaveBeenCalledTimes(2)
    expect(txQueryMock.mock.calls[0][0]).toContain('UPDATE user_credits')
    expect(txQueryMock.mock.calls[1][0]).toContain('INSERT INTO credit_transactions')
  })

  it('余额不足抛 402 且不写流水', async () => {
    txQueryMock
      .mockResolvedValueOnce({ rows: [] })
      .mockResolvedValueOnce({ rows: [{ balance: 0 }] })

    await expect(consumeCredit('user-1', 'video_analysis')).rejects.toMatchObject({ statusCode: 402 })

    const insertCalls = txQueryMock.mock.calls.filter((call) => String(call[0]).includes('INSERT'))
    expect(insertCalls).toHaveLength(0)
  })

  it('同一 operationId 重复投递只扣一次（幂等）', async () => {
    txQueryMock.mockResolvedValueOnce({ rows: [{ id: 'tx-1', balance_after: 9 }] })

    const result = await consumeCredit('user-1', 'video_analysis', 'op-1')

    expect(result).toEqual({ balance: 9, transactionId: 'tx-1', deduplicated: true })
    // 命中既有流水后短路：不再 UPDATE 余额
    const updateCalls = txQueryMock.mock.calls.filter((call) => String(call[0]).includes('UPDATE user_credits'))
    expect(updateCalls).toHaveLength(0)
  })

  it('并发重试撞唯一约束时读回既有流水，不双扣', async () => {
    txQueryMock
      .mockResolvedValueOnce({ rows: [] })
      .mockResolvedValueOnce({ rows: [{ balance: 9 }] })
      .mockRejectedValueOnce(uniqueViolation())
    queryDbMock.mockResolvedValueOnce({ rows: [{ id: 'tx-winner', balance_after: 9 }] })

    const result = await consumeCredit('user-1', 'video_analysis', 'op-1')

    expect(result).toEqual({ balance: 9, transactionId: 'tx-winner', deduplicated: true })
  })

  it('未带 operationId 时不做幂等预检（保持旧调用方语义）', async () => {
    txQueryMock
      .mockResolvedValueOnce({ rows: [{ balance: 4 }] })
      .mockResolvedValueOnce({ rows: [{ id: 'tx-2' }] })

    await consumeCredit('user-1', 'comedy_generation')

    const lookups = txQueryMock.mock.calls.filter((call) => String(call[0]).includes('WHERE operation_id'))
    expect(lookups).toHaveLength(0)
  })
})

describe('refundCredit', () => {
  it('退款余额与流水同事务，流水类型为 refund', async () => {
    txQueryMock
      .mockResolvedValueOnce({ rows: [{ balance: 10 }] })
      .mockResolvedValueOnce({ rows: [{ id: 'tx-r1' }] })

    const result = await refundCredit('user-1', 1, 'video_analysis', '失败退回')

    expect(result.balance).toBe(10)
    expect(txQueryMock.mock.calls[1][0]).toContain("'refund'")
  })

  it('同一退款 operationId 只退一次', async () => {
    txQueryMock.mockResolvedValueOnce({ rows: [{ id: 'tx-r1', balance_after: 10 }] })

    const result = await refundCredit('user-1', 1, 'video_analysis', '失败退回', 'refund:op-1')

    expect(result.deduplicated).toBe(true)
    const updateCalls = txQueryMock.mock.calls.filter((call) => String(call[0]).includes('UPDATE user_credits'))
    expect(updateCalls).toHaveLength(0)
  })

  it('账户缺失抛 500 且不写流水', async () => {
    txQueryMock.mockResolvedValueOnce({ rows: [] })

    await expect(refundCredit('ghost', 1, 'video_analysis', '失败退回')).rejects.toBeInstanceOf(AppError)
    const insertCalls = txQueryMock.mock.calls.filter((call) => String(call[0]).includes('INSERT'))
    expect(insertCalls).toHaveLength(0)
  })
})

describe('refundOperationId', () => {
  it('由扣减 key 派生，保证一次扣减最多一次退款', () => {
    expect(refundOperationId('op-1')).toBe('refund:op-1')
    expect(refundOperationId('op-1')).toBe(refundOperationId('op-1'))
  })
})
