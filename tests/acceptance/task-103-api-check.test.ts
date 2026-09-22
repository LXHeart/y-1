import { beforeEach, describe, expect, it, vi } from 'vitest'

const database = vi.hoisted(() => ({ connect: vi.fn(), query: vi.fn(), end: vi.fn() }))
vi.mock('pg', () => ({ Client: class { connect = database.connect; query = database.query; end = database.end } }))

import { checkDatabaseFacts, validateManifest } from '../../scripts/acceptance/task-103-api-check'

const manifest = {
  runId: 't103-20260916000000-abcdef',
  environment: 'isolated-stack',
  accounts: [{ email: 't103-20260916000000-abcdef-consumer-1@example.invalid' }],
}

function orderRows(amount = '10000', refunded = '3000', factNet = '7000') {
  database.query
    .mockResolvedValueOnce({ rowCount: 1, rows: [{ amount_cents: amount, refunded_amount_cents: refunded, status: 'succeeded' }] })
    .mockResolvedValueOnce({ rowCount: 1, rows: [{ recommender_amount_cents: '1000', merchant_amount_cents: '5500', platform_fee_cents: '500', status: 'completed' }] })
    .mockResolvedValueOnce({ rowCount: 1, rows: [{ net_total_cents: factNet, merchant_cents: '5500', platform_cents: '500', recommender_total_cents: '1000' }] })
}

beforeEach(() => vi.resetAllMocks())

describe('验收报告必须基于可核实的事实', () => {
  it('拒绝缺失/未知环境及其他 runId 账号，不能跳过数据库后宣称通过', () => {
    for (const environment of [undefined, '', 'production', 'isolated-stak']) {
      expect(validateManifest({ ...manifest, environment })).toContainEqual(expect.objectContaining({ status: 'FAIL' }))
    }
    expect(validateManifest({ ...manifest, accounts: [{ email: 'another-run@example.invalid' }] }))
      .toContainEqual(expect.objectContaining({ status: 'FAIL' }))
    expect(validateManifest(manifest).every((item) => item.status === 'PASS')).toBe(true)
  })

  it('按 pg 的 bigint 字符串正确核对 100/30/70 与三方分账', async () => {
    orderRows()
    const results = await checkDatabaseFacts({ ...manifest, flows: { order: { orderRef: 'order-1' } } }, 'fixture-unused')
    for (const id of ['order-payment', 'order-split', 'settlement-fact']) {
      expect(results.find((item) => item.id === id)?.status).toBe('PASS')
    }
    expect(database.end).toHaveBeenCalledOnce()
  })

  it('超过实付的退款不能按字符串字典序被误判通过', async () => {
    orderRows('9000', '10000')
    const results = await checkDatabaseFacts({ ...manifest, flows: { order: { orderRef: 'order-1' } } }, 'fixture-unused')
    expect(results.find((item) => item.id === 'order-payment')?.status).toBe('FAIL')
  })

  it('事实投影自身守恒但与 Finance 净额不一致也必须失败', async () => {
    orderRows('10000', '2000')
    const results = await checkDatabaseFacts({ ...manifest, flows: { order: { orderRef: 'order-1' } } }, 'fixture-unused')
    expect(results.find((item) => item.id === 'settlement-fact')?.status).toBe('FAIL')
  })

  it('恢复仍在等待或资金腿未知，不能记为完成通过', async () => {
    database.query.mockResolvedValueOnce({ rowCount: 1, rows: [{ kind: 'no_fault', state: 'retry_wait' }] })
      .mockResolvedValueOnce({ rowCount: 1, rows: [{ leg_kind: 'bounty_release', amount_cents: '1000', state: 'unknown' }] })
    const results = await checkDatabaseFacts({ ...manifest, flows: { exit: { applicationId: 'app-1' } } }, 'fixture-unused')
    expect(results.find((item) => item.id === 'exit-operation')?.status).toBe('FAIL')
    expect(results.find((item) => item.id === 'exit-fund-legs')?.status).toBe('FAIL')
  })

  it('注销被阻塞或缺准备回执不能通过，retention 与成功回执才通过', async () => {
    for (const [status, rows, expected] of [
      ['blocked', [], 'FAIL'],
      ['retention', [], 'FAIL'],
      ['retention', [{ domain: 'intelligence', step: 'prepare', state: 'succeeded' }], 'PASS'],
    ] as const) {
      database.query.mockResolvedValueOnce({ rowCount: 1, rows: [{ status }] })
        .mockResolvedValueOnce({ rowCount: rows.length, rows })
      const results = await checkDatabaseFacts({ ...manifest, flows: { closure: { closureRequestId: 'closure-1' } } }, 'fixture-unused')
      expect(results.find((item) => item.id === 'closure-steps')?.status).toBe(expected)
      if (status === 'blocked') expect(results.find((item) => item.id === 'closure-request')?.status).toBe('FAIL')
    }
  })

  it('查询失败向上传播并释放连接，不产生通过报告', async () => {
    database.query.mockRejectedValueOnce(new Error('unavailable'))
    await expect(checkDatabaseFacts({ ...manifest, flows: { order: { orderRef: 'order-1' } } }, 'fixture-unused'))
      .rejects.toThrow('unavailable')
    expect(database.end).toHaveBeenCalledOnce()
  })
})
