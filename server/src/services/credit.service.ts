import { queryDb } from '../lib/db.js'
import { logger } from '../lib/logger.js'
import { AppError } from '../lib/errors.js'

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

  const result = await queryDb<CreditRow>(
    `UPDATE user_credits
     SET balance = balance + $2,
         total_earned = total_earned + $2,
         updated_at = now()
     WHERE user_id = $1
     RETURNING *`,
    [userId, amount],
  )

  if (result.rows.length === 0) {
    throw new AppError('积分账户不存在', 500)
  }

  await queryDb(
    `INSERT INTO credit_transactions (user_id, amount, balance_after, type, note)
     VALUES ($1, $2, $3, 'reward', $4)`,
    [userId, amount, result.rows[0].balance, note],
  )

  logger.info({ userId, amount, note }, 'Credits awarded')
}

export async function consumeCredit(
  userId: string,
  feature: string,
): Promise<void> {
  const result = await queryDb<CreditRow>(
    `UPDATE user_credits
     SET balance = balance - 1,
         total_spent = total_spent + 1,
         updated_at = now()
     WHERE user_id = $1 AND balance >= 1
     RETURNING *`,
    [userId],
  )

  if (result.rows.length === 0) {
    const current = await getCreditBalance(userId)
    if (current.balance < 1) {
      throw new AppError('积分不足', 402)
    }
    throw new AppError('积分扣减失败', 500)
  }

  await queryDb(
    `INSERT INTO credit_transactions (user_id, amount, balance_after, type, feature)
     VALUES ($1, -1, $2, 'consume', $3)`,
    [userId, result.rows[0].balance, feature],
  )

  logger.info({ userId, feature, balance: result.rows[0].balance }, 'Credit consumed')
}

export async function refundCredit(
  userId: string,
  amount: number,
  feature: string,
  note: string,
): Promise<void> {
  const result = await queryDb<CreditRow>(
    `UPDATE user_credits
     SET balance = balance + $2,
         total_spent = total_spent - $2,
         updated_at = now()
     WHERE user_id = $1
     RETURNING *`,
    [userId, amount],
  )

  if (result.rows.length === 0) {
    throw new AppError('积分账户不存在', 500)
  }

  await queryDb(
    `INSERT INTO credit_transactions (user_id, amount, balance_after, type, feature, note)
     VALUES ($1, $2, $3, 'refund', $4, $5)`,
    [userId, amount, result.rows[0].balance, feature, note],
  )

  logger.info({ userId, amount, feature }, 'Credit refunded')
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
