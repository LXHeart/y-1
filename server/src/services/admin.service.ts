import { queryDb } from '../lib/db.js'
import { logger } from '../lib/logger.js'
import { ensureCreditAccount, awardFreeCredits, refundCredit } from './credit.service.js'

interface UserWithCreditsRow {
  id: string
  email: string
  display_name: string | null
  role: string
  status: string
  created_at: string
  balance: number | null
  total_earned: number | null
  total_spent: number | null
}

export interface UserWithCredits {
  id: string
  email: string
  displayName: string | null
  role: string
  status: string
  createdAt: string
  balance: number
  totalEarned: number
  totalSpent: number
}

export async function listUsersWithCredits(): Promise<UserWithCredits[]> {
  const result = await queryDb<UserWithCreditsRow>(
    `SELECT u.id, u.email, u.display_name, u.role, u.status, u.created_at,
            uc.balance, uc.total_earned, uc.total_spent
     FROM app_users u
     LEFT JOIN user_credits uc ON uc.user_id = u.id
     ORDER BY u.created_at DESC`,
  )

  return result.rows.map((row) => ({
    id: row.id,
    email: row.email,
    displayName: row.display_name,
    role: row.role,
    status: row.status,
    createdAt: typeof row.created_at === 'string' ? row.created_at : new Date(row.created_at as string | number).toISOString(),
    balance: row.balance ?? 0,
    totalEarned: row.total_earned ?? 0,
    totalSpent: row.total_spent ?? 0,
  }))
}

export async function adjustCredits(
  userId: string,
  amount: number,
  note: string,
): Promise<void> {
  await ensureCreditAccount(userId)

  if (amount >= 0) {
    await awardFreeCredits(userId, amount, note)
  } else {
    await refundCredit(userId, Math.abs(amount), 'admin_adjust', note)
  }

  logger.info({ userId, amount, note }, 'Admin adjusted credits')
}
