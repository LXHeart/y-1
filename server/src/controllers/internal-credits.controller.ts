import type { NextFunction, Request, Response } from 'express'
import { consumeCredit, ensureCreditAccount, refundCredit } from '../services/credit.service.js'
import { AppError } from '../lib/errors.js'

/**
 * 内部扣费/退款端点（草场 intelligence → legacy credits）。复用 {@link consumeCredit} /
 * {@link refundCredit} 的同事务原子写入，单一真相源——不重写扣减逻辑，避免两份逻辑漂移。
 *
 * 仅经 {@code requireInternalKey} 鉴权 + {@code rejectForwardedRequest} 后可达；
 * 挂载于 `/internal/credits`（公共 `/api` 树之外，nginx 层另有 deny）。
 * 响应保持 legacy 信封。402=积分不足。
 */

interface ParsedBody {
  accountId: string
  feature: string
  operationId?: string
}

/**
 * `operationId` 是幂等键：调用方重试时必须复用同一值，否则重试即双扣（GL-P0-CRED-001）。
 * 为兼容尚未升级的调用方保持可选；缺失时退化为非幂等语义。
 */
function parseBody(body: unknown): ParsedBody {
  const { accountId, feature, operationId } = (body ?? {}) as {
    accountId?: unknown
    feature?: unknown
    operationId?: unknown
  }

  if (!accountId || typeof accountId !== 'string') {
    throw new AppError('缺少 accountId', 400)
  }
  if (!feature || typeof feature !== 'string') {
    throw new AppError('缺少 feature', 400)
  }
  if (operationId !== undefined && (typeof operationId !== 'string' || operationId.trim() === '')) {
    throw new AppError('operationId 无效', 400)
  }

  return { accountId, feature, operationId: operationId as string | undefined }
}

export async function consumeCreditsHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { accountId, feature, operationId } = parseBody(req.body)

    await ensureCreditAccount(accountId)
    const result = await consumeCredit(accountId, feature, operationId)

    res.json({
      success: true,
      data: { consumed: true, balance: result.balance, deduplicated: result.deduplicated },
    })
  } catch (error) {
    if (error instanceof AppError) {
      res.status(error.statusCode).json({ success: false, error: error.message })
      return
    }
    next(error)
  }
}

/**
 * 内部退款端点：草场侧上游失败后退回已扣积分（GL-P0-BILL-002 的 Java 半边）。
 * `operationId` 必填——退款没有幂等键就会在重试中重复入账。
 */
export async function refundCreditsHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { accountId, feature, operationId } = parseBody(req.body)
    if (!operationId) {
      throw new AppError('退款必须携带 operationId', 400)
    }

    const { note } = (req.body ?? {}) as { note?: unknown }
    await ensureCreditAccount(accountId)
    const result = await refundCredit(
      accountId,
      1,
      feature,
      typeof note === 'string' && note.trim() !== '' ? note : '草场上游失败自动退回',
      operationId,
    )

    res.json({
      success: true,
      data: { refunded: true, balance: result.balance, deduplicated: result.deduplicated },
    })
  } catch (error) {
    if (error instanceof AppError) {
      res.status(error.statusCode).json({ success: false, error: error.message })
      return
    }
    next(error)
  }
}
