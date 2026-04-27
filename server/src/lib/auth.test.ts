import { describe, expect, it, vi, beforeEach } from 'vitest'
import type { NextFunction, Request } from 'express'

const { envMock } = vi.hoisted(() => ({
  envMock: {
    DATABASE_URL: 'postgres://test' as string | undefined,
    SESSION_SECRET: '12345678901234567890123456789012' as string | undefined,
  },
}))

vi.mock('./env.js', () => ({
  env: envMock,
}))

const {
  attachAuthenticatedUser,
  getAuthenticatedUser,
  getSessionOrThrow,
  getSessionUser,
  requireAuthenticatedUser,
} = await import('./auth.js')

describe('auth helpers', () => {
  beforeEach(() => {
    envMock.DATABASE_URL = 'postgres://test'
    envMock.SESSION_SECRET = '12345678901234567890123456789012'
  })

  it('returns session user when present', () => {
    const req = {
      session: {
        user: {
          id: 'user-1',
          email: 'user@example.com',
          role: 'admin',
        },
      },
    } as Request

    expect(getSessionUser(req)).toEqual({
      id: 'user-1',
      email: 'user@example.com',
      role: 'admin',
    })
  })

  it('prefers req.authUser when resolving authenticated user', () => {
    const req = {
      authUser: {
        id: 'user-1',
        email: 'auth@example.com',
        role: 'admin',
      },
      session: {
        user: {
          id: 'user-2',
          email: 'session@example.com',
          role: 'admin',
        },
      },
    } as Request

    expect(getAuthenticatedUser(req)).toEqual({
      id: 'user-1',
      email: 'auth@example.com',
      role: 'admin',
    })
  })

  it('falls back to session user and assigns req.authUser', () => {
    const req = {
      session: {
        user: {
          id: 'user-1',
          email: 'user@example.com',
          role: 'admin',
        },
      },
    } as Request

    const user = getAuthenticatedUser(req)

    expect(user).toEqual({
      id: 'user-1',
      email: 'user@example.com',
      role: 'admin',
    })
    expect(req.authUser).toEqual(user)
  })

  it('throws 401 when no authenticated user exists', () => {
    const req = {
      session: {},
    } as Request

    expect(() => getAuthenticatedUser(req)).toThrow(expect.objectContaining({
      statusCode: 401,
      message: '请先登录',
    }))
  })

  it('throws 503 when auth is not configured', () => {
    envMock.DATABASE_URL = undefined
    const req = {
      session: {},
    } as Request

    expect(() => getAuthenticatedUser(req)).toThrow(expect.objectContaining({
      statusCode: 503,
      message: '用户系统未配置，请先完成 PostgreSQL 与会话配置',
    }))
  })

  it('returns session from getSessionOrThrow', () => {
    const session = { id: 'session-1' }
    const req = { session } as unknown as Request

    expect(getSessionOrThrow(req)).toBe(session)
  })

  it('throws 503 when session is missing', () => {
    const req = {} as Request

    expect(() => getSessionOrThrow(req)).toThrow(expect.objectContaining({
      statusCode: 503,
      message: '用户系统未配置，请先完成 PostgreSQL 与会话配置',
    }))
  })

  it('attaches session user onto req.authUser', () => {
    const req = {
      session: {
        user: {
          id: 'user-1',
          email: 'user@example.com',
          role: 'admin',
        },
      },
    } as Request
    const next = vi.fn() as NextFunction

    attachAuthenticatedUser(req, {} as never, next)

    expect(req.authUser).toEqual({
      id: 'user-1',
      email: 'user@example.com',
      role: 'admin',
    })
    expect(next).toHaveBeenCalledWith()
  })

  it('forwards auth errors through requireAuthenticatedUser', () => {
    envMock.DATABASE_URL = undefined
    const req = { session: {} } as Request
    const next = vi.fn() as NextFunction

    requireAuthenticatedUser(req, {} as never, next)

    expect(next).toHaveBeenCalledWith(expect.objectContaining({
      statusCode: 503,
    }))
  })
})
