import type { NextFunction, Request, Response } from 'express'
import { beforeEach, describe, expect, it, vi } from 'vitest'

// env holder 必须用 vi.hoisted——vi.mock 工厂被提升到文件顶部，普通 const 会因 TDZ 报错。
const { envMock } = vi.hoisted(() => ({
  envMock: { INTERNAL_API_KEY: 'shared-secret' } as { INTERNAL_API_KEY: string | undefined },
}))
vi.mock('../lib/env.js', () => ({ env: envMock }))

const { ensureCreditAccountMock, consumeCreditMock } = vi.hoisted(() => ({
  ensureCreditAccountMock: vi.fn(),
  consumeCreditMock: vi.fn(),
}))
vi.mock('../services/credit.service.js', () => ({
  ensureCreditAccount: ensureCreditAccountMock,
  consumeCredit: consumeCreditMock,
}))

const { requireInternalKey } = await import('../lib/internal-auth.js')
const { consumeCreditsHandler } = await import('./internal-credits.controller.js')
const { AppError } = await import('../lib/errors.js')

function mockRes(): Response {
  return {
    status: vi.fn().mockReturnThis(),
    json: vi.fn().mockReturnThis(),
  } as unknown as Response
}

function mockReq(body: unknown, internalKey?: string): Request {
  return {
    body,
    header: vi.fn((name: string) => (name === 'X-Internal-Key' ? internalKey : undefined)),
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

describe('consumeCreditsHandler', () => {
  beforeEach(() => {
    ensureCreditAccountMock.mockReset()
    consumeCreditMock.mockReset()
    ensureCreditAccountMock.mockResolvedValue(undefined)
    consumeCreditMock.mockResolvedValue(undefined)
  })

  it('合法请求 → 复用 consumeCredit 原子扣减，返回 consumed:true', async () => {
    const res = mockRes()
    await consumeCreditsHandler(
      mockReq({ accountId: 'acc-1', feature: 'comedy_generation' }, 'shared-secret'),
      res,
      vi.fn(),
    )
    expect(ensureCreditAccountMock).toHaveBeenCalledWith('acc-1')
    expect(consumeCreditMock).toHaveBeenCalledWith('acc-1', 'comedy_generation')
    expect(res.json).toHaveBeenCalledWith({ success: true, data: { consumed: true } })
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
