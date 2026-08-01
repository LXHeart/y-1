import { queryDb, withDbTransaction, type DbTransaction } from '../lib/db.js'
import { logger } from '../lib/logger.js'
import { AppError } from '../lib/errors.js'

/** Postgres unique_violation —— 幂等键撞车（并发重试同一 operation_id）。 */
const PG_UNIQUE_VIOLATION = '23505'

interface CreditRow {
  user_id: string
  balance: number
  total_earned: number
  total_spent: number
}

interface CreditTransactionRow {
  id: string
  amount: number
  balance_after: number
  type: string
  feature: string | null
  note: string | null
  created_at: string
}

export interface CreditBalance {
  balance: number
  totalEarned: number
  totalSpent: number
}

export interface CreditHistoryItem {
  id: string
  amount: number
  balanceAfter: number
  type: string
  feature: string | null
  note: string | null
  createdAt: string
}

/** 一次积分写入的结果。`deduplicated` 为 true 表示命中既有 operation_id，本次未再改余额。 */
export interface CreditMutationResult {
  balance: number
  transactionId: string
  deduplicated: boolean
}

interface ExistingTransactionRow {
  id: string
  balance_after: number
}

/**
 * 幂等键预检：命中既有流水则返回它，调用方据此短路而不重复写账。
 * 必须在事务内、且在改余额之前调用。
 */
async function findExistingOperation(
  tx: DbTransaction,
  operationId: string,
): Promise<CreditMutationResult | null> {
  const existing = await tx.query<ExistingTransactionRow>(
    'SELECT id, balance_after FROM credit_transactions WHERE operation_id = $1 LIMIT 1',
    [operationId],
  )

  if (existing.rows.length === 0) {
    return null
  }

  return {
    balance: existing.rows[0].balance_after,
    transactionId: existing.rows[0].id,
    deduplicated: true,
  }
}

function isUniqueViolation(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as { code?: string }).code === PG_UNIQUE_VIOLATION
}

/**
 * 并发重试兜底：预检与插入之间另一请求已写入同一 operation_id 时，唯一索引会抛 23505。
 * 事务已 rollback（本次余额变更未生效），此处读回胜出者的流水当作幂等成功。
 */
async function readOperationAfterConflict(operationId: string): Promise<CreditMutationResult> {
  const existing = await queryDb<ExistingTransactionRow>(
    'SELECT id, balance_after FROM credit_transactions WHERE operation_id = $1 LIMIT 1',
    [operationId],
  )

  if (existing.rows.length === 0) {
    throw new AppError('积分操作冲突，请稍后重试', 409)
  }

  return {
    balance: existing.rows[0].balance_after,
    transactionId: existing.rows[0].id,
    deduplicated: true,
  }
}

export async function ensureCreditAccount(userId: string): Promise<void> {
  await queryDb(
    `INSERT INTO user_credits (user_id, balance, total_earned, total_spent)
     VALUES ($1, 0, 0, 0)
     ON CONFLICT (user_id) DO NOTHING`,
    [userId],
  )
}

export async function getCreditBalance(userId: string): Promise<CreditBalance> {
  const result = await queryDb<CreditRow>(
    'SELECT user_id, balance, total_earned, total_spent FROM user_credits WHERE user_id = $1',
    [userId],
  )

  if (result.rows.length === 0) {
    return { balance: 0, totalEarned: 0, totalSpent: 0 }
  }

  const row = result.rows[0]
  return {
    balance: row.balance,
    totalEarned: row.total_earned,
    totalSpent: row.total_spent,
  }
}

export async function awardFreeCredits(
  userId: string,
  amount: number,
  note: string,
): Promise<void> {
  await ensureCreditAccount(userId)

  const balance = await withDbTransaction(async (tx) => {
    const updated = await tx.query<CreditRow>(
      `UPDATE user_credits
       SET balance = balance + $2,
           total_earned = total_earned + $2,
           updated_at = now()
       WHERE user_id = $1
       RETURNING *`,
      [userId, amount],
    )

    if (updated.rows.length === 0) {
      throw new AppError('积分账户不存在', 500)
    }

    await tx.query(
      `INSERT INTO credit_transactions (user_id, amount, balance_after, type, note)
       VALUES ($1, $2, $3, 'reward', $4)`,
      [userId, amount, updated.rows[0].balance, note],
    )

    return updated.rows[0].balance
  })

  logger.info({ userId, amount, note, balance }, 'Credits awarded')
}

/**
 * 扣 1 积分并写 consume 流水，余额与流水同事务（GL-P0-CRED-001）。
 *
 * 传 `operationId` 即获得幂等：同一 key 重复投递只扣一次，重复调用返回首次结果。
 * 内部 bridge 的重试必须复用同一 key，否则重试即双扣。
 */
export async function consumeCredit(
  userId: string,
  feature: string,
  operationId?: string,
): Promise<CreditMutationResult> {
  try {
    const result = await withDbTransaction(async (tx) => {
      if (operationId) {
        const existing = await findExistingOperation(tx, operationId)
        if (existing) {
          return existing
        }
      }

      const updated = await tx.query<CreditRow>(
        `UPDATE user_credits
         SET balance = balance - 1,
             total_spent = total_spent + 1,
             updated_at = now()
         WHERE user_id = $1 AND balance >= 1
         RETURNING *`,
        [userId],
      )

      if (updated.rows.length === 0) {
        const current = await tx.query<CreditRow>(
          'SELECT user_id, balance, total_earned, total_spent FROM user_credits WHERE user_id = $1',
          [userId],
        )
        if (current.rows.length === 0 || current.rows[0].balance < 1) {
          throw new AppError('积分不足', 402)
        }
        throw new AppError('积分扣减失败', 500)
      }

      const balance = updated.rows[0].balance
      const inserted = await tx.query<{ id: string }>(
        `INSERT INTO credit_transactions (user_id, amount, balance_after, type, feature, operation_id)
         VALUES ($1, -1, $2, 'consume', $3, $4)
         RETURNING id`,
        [userId, balance, feature, operationId ?? null],
      )

      return { balance, transactionId: inserted.rows[0].id, deduplicated: false }
    })

    logger.info(
      { userId, feature, balance: result.balance, operationId, deduplicated: result.deduplicated },
      result.deduplicated ? 'Credit consume deduplicated' : 'Credit consumed',
    )
    return result
  } catch (error: unknown) {
    if (operationId && isUniqueViolation(error)) {
      const existing = await readOperationAfterConflict(operationId)
      logger.info({ userId, feature, operationId }, 'Credit consume deduplicated after unique conflict')
      return existing
    }
    throw error
  }
}

/**
 * 退还积分并写 refund 流水，余额与流水同事务。
 *
 * 传 `operationId` 即获得幂等——失败退款路径可能被重复触发（重试、多处 catch），
 * 同一 key 只退一次。上游失败退款请用 {@link refundOperationId} 由扣减 key 派生。
 */
export async function refundCredit(
  userId: string,
  amount: number,
  feature: string,
  note: string,
  operationId?: string,
): Promise<CreditMutationResult> {
  try {
    const result = await withDbTransaction(async (tx) => {
      if (operationId) {
        const existing = await findExistingOperation(tx, operationId)
        if (existing) {
          return existing
        }
      }

      const updated = await tx.query<CreditRow>(
        `UPDATE user_credits
         SET balance = balance + $2,
             total_spent = total_spent - $2,
             updated_at = now()
         WHERE user_id = $1
         RETURNING *`,
        [userId, amount],
      )

      if (updated.rows.length === 0) {
        throw new AppError('积分账户不存在', 500)
      }

      const balance = updated.rows[0].balance
      const inserted = await tx.query<{ id: string }>(
        `INSERT INTO credit_transactions (user_id, amount, balance_after, type, feature, note, operation_id)
         VALUES ($1, $2, $3, 'refund', $4, $5, $6)
         RETURNING id`,
        [userId, amount, balance, feature, note, operationId ?? null],
      )

      return { balance, transactionId: inserted.rows[0].id, deduplicated: false }
    })

    logger.info(
      { userId, amount, feature, operationId, deduplicated: result.deduplicated },
      result.deduplicated ? 'Credit refund deduplicated' : 'Credit refunded',
    )
    return result
  } catch (error: unknown) {
    if (operationId && isUniqueViolation(error)) {
      const existing = await readOperationAfterConflict(operationId)
      logger.info({ userId, feature, operationId }, 'Credit refund deduplicated after unique conflict')
      return existing
    }
    throw error
  }
}

/** 由扣减 operation_id 派生退款 key，保证「一次扣减最多一次退款」。 */
export function refundOperationId(consumeOperationId: string): string {
  return `refund:${consumeOperationId}`
}

export async function getCreditHistory(
  userId: string,
  limit = 50,
): Promise<CreditHistoryItem[]> {
  const result = await queryDb<CreditTransactionRow>(
    `SELECT id, amount, balance_after, type, feature, note, created_at
     FROM credit_transactions
     WHERE user_id = $1
     ORDER BY created_at DESC
     LIMIT $2`,
    [userId, limit],
  )

  return result.rows.map((row) => ({
    id: row.id,
    amount: row.amount,
    balanceAfter: row.balance_after,
    type: row.type,
    feature: row.feature,
    note: row.note,
    createdAt: row.created_at,
  }))
}
