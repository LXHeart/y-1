import { randomUUID } from 'node:crypto'
import bcrypt from 'bcryptjs'
import { queryDb } from '../lib/db.js'

async function main() {
  const passwordHash = bcrypt.hashSync('1', 12)
  const email = '1@1.com'
  const displayName = '爸爸'

  const result = await queryDb<{ id: string; email: string; display_name: string; role: string }>(
    `INSERT INTO app_users (id, email, password_hash, display_name, role, status)
     VALUES ($1, $2, $3, $4, 'admin', 'active')
     ON CONFLICT (email) DO UPDATE SET
       password_hash = EXCLUDED.password_hash,
       display_name = EXCLUDED.display_name,
       role = EXCLUDED.role,
       status = EXCLUDED.status
     RETURNING id, email, display_name, role`,
    [randomUUID(), email, passwordHash, displayName],
  )
  console.log('User:', result.rows[0])

  // 积分账户已迁入 finance 域的 credits_account 表（GL-P3-AI-001 下属切片）；seed 直写同库新表。
  await queryDb(
    `INSERT INTO credits_account (account_id, balance, total_earned, total_spent)
     SELECT id, 1000, 1000, 0 FROM app_users
     ON CONFLICT (account_id) DO UPDATE SET balance = 1000, total_earned = 1000, updated_at = now()`,
  )
  console.log('All users credits set to 1000')

  process.exit(0)
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
