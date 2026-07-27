import type { NextFunction, Request, Response } from 'express'
import { consumeCredit, ensureCreditAccount } from '../services/credit.service.js'
import { AppError } from '../lib/errors.js'

/**
 * 内部扣费端点（草场 intelligence → legacy credits）。复用 {@link consumeCredit} 的原子扣减，
 * 单一真相源——不重写扣减逻辑，避免 legacy 与草场两份逻辑漂移。
 *
 * 仅经 {@code requireInternalKey} 鉴权后可达；不要求 cookie session（内部服务调用）。
 * 响应保持 legacy 信封 `{success,data}` / `{success:false,error}`。402=积分不足。
 */
export async function consumeCreditsHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { accountId, feature } = (req.body ?? {}) as { accountId?: unknown; feature?: unknown }
    if (!accountId || typeof accountId !== 'string') {
      throw new AppError('缺少 accountId', 400)
    }
    if (!feature || typeof feature !== 'string') {
      throw new AppError('缺少 feature', 400)
    }

    await ensureCreditAccount(accountId)
    await consumeCredit(accountId, feature)
    res.json({ success: true, data: { consumed: true } })
  } catch (error) {
    if (error instanceof AppError) {
      res.status(error.statusCode).json({ success: false, error: error.message })
      return
    }
    next(error)
  }
}
