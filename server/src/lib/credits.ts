import { randomUUID } from 'node:crypto'
import {
  consumeCredit,
  ensureCreditAccount,
  refundCredit,
  refundOperationId,
} from '../services/credit.service.js'
import { logger } from './logger.js'

/**
 * 一次扣减的句柄。上游失败时调用 {@link CreditCharge.refund} 退回，
 * 避免用户为失败调用付费（GL-P0-BILL-002）。
 */
export interface CreditCharge {
  readonly userId: string
  readonly feature: string
  readonly operationId: string
  /**
   * 退回本次扣减。幂等（退款 key 由扣减 key 派生），重复调用只退一次。
   * 自身失败只记日志、不抛——退款失败不应覆盖用户看到的原始上游错误。
   */
  refund(note: string): Promise<void>
}

function createCharge(userId: string, feature: string, operationId: string): CreditCharge {
  let refunded = false

  return {
    userId,
    feature,
    operationId,
    async refund(note: string): Promise<void> {
      if (refunded) {
        return
      }
      refunded = true

      try {
        await refundCredit(userId, 1, feature, note, refundOperationId(operationId))
      } catch (error: unknown) {
        logger.error({ err: error, userId, feature, operationId }, 'Credit refund failed')
      }
    },
  }
}

/**
 * 扣 1 积分，返回可退款句柄。积分不足抛 402。
 *
 * 调用方在上游失败时必须 `await charge.refund(...)`；成功路径不做任何事。
 * 用户主动断开（abort）不退——内容已产出并已流给用户。
 */
export async function requireCredit(userId: string, feature: string): Promise<CreditCharge> {
  await ensureCreditAccount(userId)
  const operationId = randomUUID()
  await consumeCredit(userId, feature, operationId)
  return createCharge(userId, feature, operationId)
}
