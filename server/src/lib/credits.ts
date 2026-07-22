import { consumeCredit, ensureCreditAccount } from '../services/credit.service.js'

export async function requireCredit(userId: string, feature: string): Promise<void> {
  await ensureCreditAccount(userId)
  await consumeCredit(userId, feature)
}
