import type { NextFunction, Request, RequestHandler, Response } from 'express'
import { queryDb } from './db.js'
import { AppError } from './errors.js'
import { env } from './env.js'
import { logger } from './logger.js'

export interface SessionUser {
  id: string
  email: string
  displayName?: string
  role: string
}

export type LoginAttemptOutcome = 'success' | 'auth_failure' | 'other_failure'

const AUTH_UNAVAILABLE_MESSAGE = '用户系统未配置，请先完成 PostgreSQL 与会话配置'

export function isAuthConfigured(): boolean {
  return Boolean(env.DATABASE_URL && env.SESSION_SECRET)
}

export function assertAuthConfigured(): void {
  if (!isAuthConfigured()) {
    throw new AppError(AUTH_UNAVAILABLE_MESSAGE, 503)
  }
}

export function getSessionUser(req: Request): SessionUser | undefined {
  return req.session?.user
}

export function getAuthenticatedUser(req: Request): SessionUser {
  assertAuthConfigured()

  const user = req.authUser ?? getSessionUser(req)
  if (!user) {
    throw new AppError('请先登录', 401)
  }

  req.authUser = user
  return user
}

export function getSessionOrThrow(req: Request) {
  assertAuthConfigured()

  if (!req.session) {
    throw new AppError(AUTH_UNAVAILABLE_MESSAGE, 503)
  }

  return req.session
}

export function setLoginAttemptOutcome(req: Request, outcome: LoginAttemptOutcome): void {
  req.loginAttemptOutcome = outcome
  req.notifyLoginAttemptOutcomeSet?.()
}

export const attachAuthenticatedUser: RequestHandler = (req, _res, next) => {
  req.authUser = getSessionUser(req)
  next()
}

export function requireAuthenticatedUser(req: Request, _res: Response, next: NextFunction): void {
  try {
    req.authUser = getAuthenticatedUser(req)
    next()
  } catch (error: unknown) {
    next(error)
  }
}

export async function requireAdmin(req: Request, _res: Response, next: NextFunction): Promise<void> {
  try {
    const user = getAuthenticatedUser(req)
    let allowed = false
    try {
      const result = await queryDb<{ allowed: boolean }>(
        `SELECT EXISTS (
           SELECT 1
             FROM backend_role
            WHERE account_id = CAST($1 AS uuid)
              AND role = 'platform_admin'
         ) AS allowed`,
        [user.id],
      )
      allowed = result.rows[0]?.allowed === true
    } catch (error: unknown) {
      logger.error({ err: error, accountId: user.id }, 'Failed to verify authoritative admin role')
      throw new AppError('权限校验暂时不可用', 503)
    }
    if (!allowed) {
      throw new AppError('权限不足', 403)
    }
    next()
  } catch (error: unknown) {
    next(error)
  }
}
