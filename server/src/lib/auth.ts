import type { NextFunction, Request, RequestHandler, Response } from 'express'
import { AppError } from './errors.js'
import { env } from './env.js'

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
