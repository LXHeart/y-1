/** Seed one isolated login account for the public Edge E2E smoke. */
import bcrypt from 'bcryptjs'
import { queryDb } from '../server/src/lib/db.js'

const email = process.env.E2E_EMAIL?.trim().toLowerCase()
const password = process.env.E2E_PASSWORD
const displayName = process.env.E2E_DISPLAY_NAME || 'CI E2E User'

if (!email || !password) {
  throw new Error('E2E_EMAIL and E2E_PASSWORD are required')
}

const passwordHash = bcrypt.hashSync(password, 10)

await queryDb(
  `INSERT INTO app_users (id, email, password_hash, display_name, role, status)
   VALUES (gen_random_uuid(), $1, $2, $3, 'user', 'active')
   ON CONFLICT (email) DO UPDATE SET
     password_hash = EXCLUDED.password_hash,
     display_name = EXCLUDED.display_name,
     role = 'user',
     status = 'active',
     updated_at = now()` ,
  [email, passwordHash, displayName],
)

console.log(`[e2e-auth-seed] ready: ${email}`)
