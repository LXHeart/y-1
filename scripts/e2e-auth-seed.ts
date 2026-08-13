/** Seed isolated merchant and administrator accounts for public Edge E2E flows. */
import bcrypt from 'bcryptjs'
import { closeDbPool, queryDb } from './lib/db.js'

const email = process.env.E2E_EMAIL?.trim().toLowerCase()
const password = process.env.E2E_PASSWORD
const displayName = process.env.E2E_DISPLAY_NAME || 'CI E2E User'
const adminEmail = process.env.E2E_ADMIN_EMAIL?.trim().toLowerCase()
const adminPassword = process.env.E2E_ADMIN_PASSWORD
const adminDisplayName = process.env.E2E_ADMIN_DISPLAY_NAME || 'CI E2E Admin'

if (!email || !password || !adminEmail || !adminPassword) {
  throw new Error('E2E_EMAIL, E2E_PASSWORD, E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD are required')
}

async function upsertAccount(
  targetEmail: string,
  targetPassword: string,
  targetDisplayName: string,
  role: 'user' | 'admin',
): Promise<void> {
  const passwordHash = bcrypt.hashSync(targetPassword, 10)
  await queryDb(
    `INSERT INTO app_users (id, email, password_hash, display_name, role, status)
   VALUES (gen_random_uuid(), $1, $2, $3, $4, 'active')
   ON CONFLICT (email) DO UPDATE SET
     password_hash = EXCLUDED.password_hash,
     display_name = EXCLUDED.display_name,
     role = $4,
     status = 'active',
     updated_at = now()` ,
    [targetEmail, passwordHash, targetDisplayName, role],
  )
}

try {
  await upsertAccount(email, password, displayName, 'user')
  await upsertAccount(adminEmail, adminPassword, adminDisplayName, 'admin')

  // GL-P2-ADMIN-001：admin 账号补 backend_role 行（platform_admin 超集）。
  await queryDb(
    `INSERT INTO backend_role (account_id, role)
     SELECT id, 'platform_admin' FROM app_users WHERE email = $1
     ON CONFLICT (account_id, role) DO NOTHING`,
    [adminEmail],
  )

  console.log(`[e2e-auth-seed] ready: ${email}, ${adminEmail}`)
} finally {
  await closeDbPool()
}
