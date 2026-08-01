import type { NextFunction, Request, Response } from 'express'
import { beforeEach, describe, expect, it, vi } from 'vitest'

// env holder 必须用 vi.hoisted——vi.mock 工厂被提升到文件顶部，普通 const 会因 TDZ 报错。
const { envMock } = vi.hoisted(() => ({
  envMock: { INTERNAL_API_KEY: 'shared-secret' } as { INTERNAL_API_KEY: string | undefined },
}))
vi.mock('../lib/env.js', () => ({ env: envMock }))

const { ensureCreditAccountMock, consumeCreditMock, refundCreditMock } = vi.hoisted(() => ({
  ensureCreditAccountMock: vi.fn(),
  consumeCreditMock: vi.fn(),
  refundCreditMock: vi.fn(),
}))
vi.mock('../services/credit.service.js', () => ({
  ensureCreditAccount: ensureCreditAccountMock,
  consumeCredit: consumeCreditMock,
  refundCredit: refundCreditMock,
}))

const { requireInternalKey, rejectForwardedRequest } = await import('../lib/internal-auth.js')
const { consumeCreditsHandler, refundCreditsHandler } = await import('./internal-credits.controller.js')
const { AppError } = await import('../lib/errors.js')

function mockRes(): Response {
  return {
    status: vi.fn().mockReturnThis(),
    json: vi.fn().mockReturnThis(),
  } as unknown as Response
}

function mockReq(body: unknown, internalKey?: string, headers: Record<string, string> = {}): Request {
  const lookup: Record<string, string | undefined> = {}
  for (const [key, value] of Object.entries({ ...headers, 'X-Internal-Key': internalKey })) {
    lookup[key.toLowerCase()] = value
  }
  return {
    body,
    header: vi.fn((name: string) => lookup[name.toLowerCase()]),
  } as unknown as Request
}

describe('requireInternalKey', () => {
  beforeEach(() => {
    envMock.INTERNAL_API_KEY = 'shared-secret'
  })

  it('密钥未配置 → fail-closed 503', () => {
    envMock.INTERNAL_API_KEY = undefined
    expect(() => requireInternalKey(mockReq(undefined, undefined), mockRes(), vi.fn())).toThrow(AppError)
    try {
      requireInternalKey(mockReq(undefined, undefined), mockRes(), vi.fn())
    } catch (error) {
      expect(error).toBeInstanceOf(AppError)
      expect((error as { statusCode: number }).statusCode).toBe(503)
    }
  })

  it('密钥不匹配 → 401', () => {
    expect(() => requireInternalKey(mockReq(undefined, 'wrong'), mockRes(), vi.fn())).toThrow(AppError)
  })

  it('密钥匹配 → 放行（next 调用）', () => {
    const next = vi.fn()
    requireInternalKey(mockReq(undefined, 'shared-secret'), mockRes(), next)
    expect(next).toHaveBeenCalledTimes(1)
  })
})

// GL-P0-CRED-001：内部 bridge 不得经公网代理到达
describe('rejectForwardedRequest', () => {
  it.each(['X-Forwarded-For', 'X-Forwarded-Host', 'X-Forwarded-Proto', 'Forwarded'])(
    '带 %s → 404（判定为经代理跳数）',
    (header) => {
      expect(() =>
        rejectForwardedRequest(mockReq(undefined, 'shared-secret', { [header]: 'x' }), mockRes(), vi.fn()),
      ).toThrow(AppError)
    },
  )

  it('无 forwarded 头（容器网络直连）→ 放行', () => {
    const next = vi.fn()
    rejectForwardedRequest(mockReq(undefined, 'shared-secret'), mockRes(), next)
    expect(next).toHaveBeenCalledTimes(1)
  })
})

describe('consumeCreditsHandler', () => {
  beforeEach(() => {
    ensureCreditAccountMock.mockReset()
    consumeCreditMock.mockReset()
    ensureCreditAccountMock.mockResolvedValue(undefined)
    consumeCreditMock.mockResolvedValue({ balance: 9, transactionId: 'tx-1', deduplicated: false })
  })

  it('合法请求 → 复用 consumeCredit 原子扣减，返回 consumed:true', async () => {
    const res = mockRes()
    await consumeCreditsHandler(
      mockReq({ accountId: 'acc-1', feature: 'comedy_generation' }, 'shared-secret'),
      res,
      vi.fn(),
    )
    expect(ensureCreditAccountMock).toHaveBeenCalledWith('acc-1')
    expect(consumeCreditMock).toHaveBeenCalledWith('acc-1', 'comedy_generation', undefined)
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: { consumed: true, balance: 9, deduplicated: false },
    })
  })

  it('operationId 透传为幂等键；重复投递回报 deduplicated', async () => {
    consumeCreditMock.mockResolvedValue({ balance: 9, transactionId: 'tx-1', deduplicated: true })
    const res = mockRes()
    await consumeCreditsHandler(
      mockReq({ accountId: 'acc-1', feature: 'comedy_generation', operationId: 'op-1' }, 'shared-secret'),
      res,
      vi.fn(),
    )
    expect(consumeCreditMock).toHaveBeenCalledWith('acc-1', 'comedy_generation', 'op-1')
    expect(res.json).toHaveBeenCalledWith(
      expect.objectContaining({ data: expect.objectContaining({ deduplicated: true }) }),
    )
  })

  it('operationId 为空串 → 400（不静默退化成非幂等）', async () => {
    const res = mockRes()
    await consumeCreditsHandler(
      mockReq({ accountId: 'acc-1', feature: 'comedy_generation', operationId: '  ' }, 'shared-secret'),
      res,
      vi.fn(),
    )
    expect(res.status).toHaveBeenCalledWith(400)
    expect(consumeCreditMock).not.toHaveBeenCalled()
  })

  it('缺 accountId → 400', async () => {
    const res = mockRes()
    await consumeCreditsHandler(mockReq({ feature: 'comedy_generation' }, 'shared-secret'), res, vi.fn())
    expect(res.status).toHaveBeenCalledWith(400)
    expect(res.json).toHaveBeenCalledWith(expect.objectContaining({ success: false }))
  })

  it('缺 feature → 400', async () => {
    const res = mockRes()
    await consumeCreditsHandler(mockReq({ accountId: 'acc-1' }, 'shared-secret'), res, vi.fn())
    expect(res.status).toHaveBeenCalledWith(400)
  })

  it('积分不足（consumeCredit 抛 402）→ 透传 402 信封', async () => {
    consumeCreditMock.mockRejectedValue(new AppError('积分不足', 402))
    const res = mockRes()
    await consumeCreditsHandler(mockReq({ accountId: 'acc-1', feature: 'video_analysis' }, 'shared-secret'), res, vi.fn())
    expect(res.status).toHaveBeenCalledWith(402)
    expect(res.json).toHaveBeenCalledWith({ success: false, error: '积分不足' })
  })

  it('非 AppError 异常 → 交给 next（由全局错误处理）', async () => {
    consumeCreditMock.mockRejectedValue(new Error('db down'))
    const next = vi.fn()
    await consumeCreditsHandler(mockReq({ accountId: 'acc-1', feature: 'video_analysis' }, 'shared-secret'), mockRes(), next)
    expect(next).toHaveBeenCalledWith(expect.any(Error))
  })
})

// GL-P0-BILL-002 的 Java 半边：草场上游失败后经此端点退回
describe('refundCreditsHandler', () => {
  beforeEach(() => {
    ensureCreditAccountMock.mockReset()
    refundCreditMock.mockReset()
    ensureCreditAccountMock.mockResolvedValue(undefined)
    refundCreditMock.mockResolvedValue({ balance: 10, transactionId: 'tx-r1', deduplicated: false })
  })

  it('缺 operationId → 400（退款没有幂等键会在重试中重复入账）', async () => {
    const res = mockRes()
    await refundCreditsHandler(mockReq({ accountId: 'acc-1', feature: 'comedy_generation' }, 'shared-secret'), res, vi.fn())
    expect(res.status).toHaveBeenCalledWith(400)
    expect(refundCreditMock).not.toHaveBeenCalled()
  })

  it('带 operationId → 退 1 积分并透传幂等键', async () => {
    const res = mockRes()
    await refundCreditsHandler(
      mockReq(
        { accountId: 'acc-1', feature: 'comedy_generation', operationId: 'op-1', note: '上游失败' },
        'shared-secret',
      ),
      res,
      vi.fn(),
    )
    expect(refundCreditMock).toHaveBeenCalledWith('acc-1', 1, 'comedy_generation', '上游失败', 'op-1')
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: { refunded: true, balance: 10, deduplicated: false },
    })
  })

  it('note 缺失时用默认说明', async () => {
    await refundCreditsHandler(
      mockReq({ accountId: 'acc-1', feature: 'comedy_generation', operationId: 'op-1' }, 'shared-secret'),
      mockRes(),
      vi.fn(),
    )
    expect(refundCreditMock).toHaveBeenCalledWith('acc-1', 1, 'comedy_generation', '草场上游失败自动退回', 'op-1')
  })
})
