import { randomUUID } from 'node:crypto'
import { AppError } from '../lib/errors.js'
import { queryDb, withDbTransaction } from '../lib/db.js'
import { hashPassword, verifyPassword } from '../lib/password.js'
import { ensureCreditAccount, awardFreeCredits } from './credit.service.js'
import { env } from '../lib/env.js'

export interface AuthUserProfile {
  id: string
  email: string
  displayName?: string
  role: string
  status: string
  createdAt: string
  lastLoginAt?: string
}

interface UserRow {
  id: string
  email: string
  password_hash: string
  display_name: string | null
  role: string
  status: string
  created_at: Date | string
  last_login_at: Date | string | null
}

type UserRole = 'admin' | 'user'

interface CreateUserInput {
  email: string
  password: string
  displayName?: string
  role?: UserRole
}

interface RegisterUserInput {
  email: string
  password: string
  displayName?: string
  initialIdentity: 'merchant' | 'recommender'
}

interface UserWithPassword extends AuthUserProfile {
  passwordHash: string
}

function normalizeEmail(email: string): string {
  return email.trim().toLowerCase()
}

function toIsoString(value: Date | string | null): string | undefined {
  if (!value) {
    return undefined
  }

  return value instanceof Date ? value.toISOString() : new Date(value).toISOString()
}

function mapUserRow(row: UserRow): UserWithPassword {
  return {
    id: row.id,
    email: row.email,
    displayName: row.display_name ?? undefined,
    role: row.role,
    status: row.status,
    createdAt: toIsoString(row.created_at)!,
    lastLoginAt: toIsoString(row.last_login_at),
    passwordHash: row.password_hash,
  }
}

function toAuthUserProfile(user: UserWithPassword): AuthUserProfile {
  return {
    id: user.id,
    email: user.email,
    displayName: user.displayName,
    role: user.role,
    status: user.status,
    createdAt: user.createdAt,
    lastLoginAt: user.lastLoginAt,
  }
}

async function setupRegistrationCredits(userId: string): Promise<void> {
  try {
    await ensureCreditAccount(userId)
    if (env.FREE_CREDITS_ON_REGISTER > 0) {
      await awardFreeCredits(userId, env.FREE_CREDITS_ON_REGISTER, '新用户注册赠送')
    }
  } catch {
    // Credits setup failure should not block registration
  }
}

export async function findUserByEmail(email: string): Promise<UserWithPassword | null> {
  const normalizedEmail = normalizeEmail(email)
  const result = await queryDb<UserRow>(
    `select id, email, password_hash, display_name, role, status, created_at, last_login_at
       from app_users
      where email = $1
      limit 1`,
    [normalizedEmail],
  )

  const row = result.rows[0]
  return row ? mapUserRow(row) : null
}

export async function findUserById(userId: string): Promise<UserWithPassword | null> {
  const result = await queryDb<UserRow>(
    `select id, email, password_hash, display_name, role, status, created_at, last_login_at
       from app_users
      where id = $1
      limit 1`,
    [userId],
  )

  const row = result.rows[0]
  return row ? mapUserRow(row) : null
}

export async function createUser(input: CreateUserInput): Promise<AuthUserProfile> {
  const passwordHash = await hashPassword(input.password)
  const result = await queryDb<UserRow>(
    `insert into app_users (id, email, password_hash, display_name, role, status)
     values ($1, $2, $3, $4, $5, 'active')
     on conflict (email) do nothing
     returning id, email, password_hash, display_name, role, status, created_at, last_login_at`,
    [
      randomUUID(),
      normalizeEmail(input.email),
      passwordHash,
      input.displayName ?? null,
      input.role ?? 'user',
    ],
  )

  const row = result.rows[0]
  if (!row) {
    throw new AppError('该邮箱已存在', 409)
  }

  const profile = toAuthUserProfile(mapUserRow(row))

  await setupRegistrationCredits(profile.id)

  return profile
}

export async function registerUser(input: RegisterUserInput): Promise<AuthUserProfile> {
  const passwordHash = await hashPassword(input.password)
  const profile = await withDbTransaction(async (tx) => {
    const result = await tx.query<UserRow>(
      `insert into app_users (id, email, password_hash, display_name, role, status)
       values ($1, $2, $3, $4, 'user', 'active')
       on conflict (email) do nothing
       returning id, email, password_hash, display_name, role, status, created_at, last_login_at`,
      [randomUUID(), normalizeEmail(input.email), passwordHash, input.displayName ?? null],
    )
    const row = result.rows[0]
    if (!row) {
      throw new AppError('该邮箱已存在', 409)
    }

    await tx.query(
      `insert into identity_profile (id, account_id, identity_type, organization_id, status)
       values ($1, $2, $3, null, 'active')`,
      [randomUUID(), row.id, input.initialIdentity],
    )
    return toAuthUserProfile(mapUserRow(row))
  })

  await setupRegistrationCredits(profile.id)
  return profile
}

export async function recordUserLogin(userId: string): Promise<void> {
  await queryDb(
    `update app_users
        set last_login_at = now(),
            updated_at = now()
      where id = $1`,
    [userId],
  )
}

export async function authenticateUser(email: string, password: string): Promise<AuthUserProfile> {
  const user = await findUserByEmail(email)
  if (!user) {
    throw new AppError('邮箱或密码错误', 401)
  }

  if (user.status !== 'active') {
    throw new AppError('邮箱或密码错误', 401)
  }

  const isPasswordValid = await verifyPassword(password, user.passwordHash)
  if (!isPasswordValid) {
    throw new AppError('邮箱或密码错误', 401)
  }

  await recordUserLogin(user.id)
  const refreshedUser = await findUserById(user.id)
  return toAuthUserProfile(refreshedUser ?? user)
}

export async function getAuthUserProfile(userId: string): Promise<AuthUserProfile> {
  const user = await findUserById(userId)
  if (!user) {
    throw new AppError('用户不存在', 401)
  }

  if (user.status !== 'active') {
    throw new AppError('当前账号不可用', 403)
  }

  return toAuthUserProfile(user)
}
