import { randomInt } from 'node:crypto'
import { logger } from '../lib/logger.js'
import { queryDb } from '../lib/db.js'
import { isEmailConfigured, sendVerificationEmail } from '../lib/email.js'

const CODE_LENGTH = 6
const CODE_TTL_MS = 5 * 60 * 1_000
const MAX_PENDING_PER_EMAIL = 5

function generateCode(): string {
  let code = ''
  for (let i = 0; i < CODE_LENGTH; i += 1) {
    code += randomInt(0, 10).toString()
  }
  return code
}

function normalizeEmail(email: string): string {
  return email.trim().toLowerCase()
}

export async function sendVerificationCode(email: string): Promise<void> {
  const normalizedEmail = normalizeEmail(email)

  if (!isEmailConfigured()) {
    throw new Error('邮件服务未配置，无法发送验证码')
  }

  const pendingCount = await queryDb<{ count: string }>(
    `select count(*) as count
       from email_verification_codes
      where email = $1
        and used = false
        and expires_at > now()`,
    [normalizedEmail],
  )

  const count = Number(pendingCount.rows[0]?.count ?? 0)
  if (count >= MAX_PENDING_PER_EMAIL) {
    throw new Error('验证码发送过于频繁，请稍后再试')
  }

  const code = generateCode()
  const expiresAt = new Date(Date.now() + CODE_TTL_MS)

  await queryDb(
    `insert into email_verification_codes (email, code, expires_at)
     values ($1, $2, $3)`,
    [normalizedEmail, code, expiresAt],
  )

  logger.info({ email: normalizedEmail }, 'Verification code generated')

  await sendVerificationEmail(normalizedEmail, code)
}

export async function verifyCode(email: string, code: string): Promise<boolean> {
  const normalizedEmail = normalizeEmail(email)

  const result = await queryDb<{ id: string }>(
    `select id
       from email_verification_codes
      where email = $1
        and code = $2
        and used = false
        and expires_at > now()
      order by created_at desc
      limit 1`,
    [normalizedEmail, code],
  )

  const row = result.rows[0]
  if (!row) {
    return false
  }

  await queryDb(
    `update email_verification_codes
        set used = true
      where id = $1`,
    [row.id],
  )

  return true
}

export async function cleanupExpiredCodes(): Promise<number> {
  const result = await queryDb(
    `delete from email_verification_codes
      where expires_at < now()`,
  )

  return result.rowCount ?? 0
}
