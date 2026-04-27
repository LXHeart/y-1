import { describe, expect, it, vi, beforeEach } from 'vitest'
import type { NextFunction, Request, Response } from 'express'

const {
  assertAuthConfiguredMock,
  authenticateUserMock,
  getAuthUserProfileMock,
  getAuthenticatedUserMock,
  getSessionOrThrowMock,
  loginRequestSchemaMock,
  registerRequestSchemaMock,
  registerUserMock,
  setLoginAttemptOutcomeMock,
  verifyCodeMock,
} = vi.hoisted(() => ({
  assertAuthConfiguredMock: vi.fn(),
  authenticateUserMock: vi.fn(),
  getAuthUserProfileMock: vi.fn(),
  getAuthenticatedUserMock: vi.fn(),
  getSessionOrThrowMock: vi.fn(),
  loginRequestSchemaMock: {
    safeParse: vi.fn(),
  },
  registerRequestSchemaMock: {
    safeParse: vi.fn(),
  },
  registerUserMock: vi.fn(),
  setLoginAttemptOutcomeMock: vi.fn((req, outcome) => {
    req.loginAttemptOutcome = outcome
    req.notifyLoginAttemptOutcomeSet?.()
  }),
  verifyCodeMock: vi.fn(),
}))

vi.mock('../lib/auth.js', () => ({
  assertAuthConfigured: assertAuthConfiguredMock,
  getAuthenticatedUser: getAuthenticatedUserMock,
  getSessionOrThrow: getSessionOrThrowMock,
  setLoginAttemptOutcome: setLoginAttemptOutcomeMock,
}))

vi.mock('../schemas/auth.js', () => ({
  loginRequestSchema: loginRequestSchemaMock,
  registerRequestSchema: registerRequestSchemaMock,
}))

vi.mock('../services/user.service.js', () => ({
  authenticateUser: authenticateUserMock,
  getAuthUserProfile: getAuthUserProfileMock,
  registerUser: registerUserMock,
}))

vi.mock('../services/email-verification.service.js', () => ({
  sendVerificationCode: vi.fn(),
  verifyCode: verifyCodeMock,
}))

const {
  loginHandler,
  registerHandler,
  logoutHandler,
  meHandler,
} = await import('./auth.controller.js')

function createResponseMock(): Response {
  return {
    clearCookie: vi.fn(),
    json: vi.fn(),
  } as unknown as Response
}

describe('auth controller', () => {
  beforeEach(() => {
    assertAuthConfiguredMock.mockReset()
    authenticateUserMock.mockReset()
    getAuthUserProfileMock.mockReset()
    getAuthenticatedUserMock.mockReset()
    getSessionOrThrowMock.mockReset()
    loginRequestSchemaMock.safeParse.mockReset()
    registerRequestSchemaMock.safeParse.mockReset()
    registerUserMock.mockReset()
    verifyCodeMock.mockReset()

    verifyCodeMock.mockResolvedValue(true)

    loginRequestSchemaMock.safeParse.mockReturnValue({
      success: true,
      data: {
        email: 'user@example.com',
        password: 'password123',
      },
    })
    registerRequestSchemaMock.safeParse.mockReturnValue({
      success: true,
      data: {
        email: 'new@example.com',
        displayName: '新用户',
        password: 'password123',
        confirmPassword: 'password123',
      },
    })
    authenticateUserMock.mockResolvedValue({
      id: 'user-1',
      email: 'user@example.com',
      displayName: '用户',
      role: 'admin',
    })
    registerUserMock.mockResolvedValue({
      id: 'user-2',
      email: 'new@example.com',
      displayName: '新用户',
      role: 'user',
    })
    getAuthUserProfileMock.mockResolvedValue({
      id: 'user-1',
      email: 'user@example.com',
      displayName: '用户',
      role: 'admin',
    })
    getAuthenticatedUserMock.mockReturnValue({
      id: 'user-1',
      email: 'user@example.com',
      role: 'admin',
    })
  })

  it('logs in and persists session user', async () => {
    const session = {
      user: undefined,
      regenerate: vi.fn((callback: (error?: Error) => void) => callback()),
      save: vi.fn((callback: (error?: Error) => void) => callback()),
    }
    getSessionOrThrowMock.mockReturnValue(session)

    const req = {
      body: {
        email: 'user@example.com',
        password: 'password123',
      },
    } as Request
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await loginHandler(req, res, next)

    expect(authenticateUserMock).toHaveBeenCalledWith('user@example.com', 'password123')
    expect(session.regenerate).toHaveBeenCalled()
    expect(session.user).toEqual({
      id: 'user-1',
      email: 'user@example.com',
      displayName: '用户',
      role: 'admin',
    })
    expect(session.save).toHaveBeenCalled()
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: {
        user: {
          id: 'user-1',
          email: 'user@example.com',
          displayName: '用户',
          role: 'admin',
        },
      },
    })
    expect(next).not.toHaveBeenCalled()
  })

  it('stores user on the regenerated session object', async () => {
    const regeneratedSession = {
      user: undefined,
      regenerate: vi.fn((callback: (error?: Error) => void) => callback()),
      save: vi.fn((callback: (error?: Error) => void) => callback()),
    }
    const req = {
      body: {
        email: 'user@example.com',
        password: 'password123',
      },
      session: undefined as unknown,
    }
    const originalSession = {
      user: undefined,
      regenerate: vi.fn((callback: (error?: Error) => void) => {
        req.session = regeneratedSession
        callback()
      }),
      save: vi.fn((callback: (error?: Error) => void) => callback()),
    }
    req.session = originalSession
    getSessionOrThrowMock.mockImplementation(() => req.session)

    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await loginHandler(req as Request, res, next)

    expect(originalSession.regenerate).toHaveBeenCalled()
    expect(req.session).toBe(regeneratedSession)
    expect(regeneratedSession.user).toEqual({
      id: 'user-1',
      email: 'user@example.com',
      displayName: '用户',
      role: 'admin',
    })
    expect(regeneratedSession.save).toHaveBeenCalled()
    expect(next).not.toHaveBeenCalled()
  })

  it('returns validation errors to next on login', async () => {
    loginRequestSchemaMock.safeParse.mockReturnValueOnce({
      success: false,
      error: {
        issues: [{ message: '请输入有效邮箱地址' }],
      },
    })

    const req = { body: {} } as Request
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await loginHandler(req, res, next)

    expect(next).toHaveBeenCalledWith(expect.objectContaining({
      statusCode: 400,
      message: '请输入有效邮箱地址',
    }))
  })

  it('registers a user and persists regenerated session user', async () => {
    const session = {
      user: undefined,
      regenerate: vi.fn((callback: (error?: Error) => void) => callback()),
      save: vi.fn((callback: (error?: Error) => void) => callback()),
    }
    getSessionOrThrowMock.mockReturnValue(session)

    const req = {
      body: {
        email: 'new@example.com',
        displayName: '新用户',
        password: 'password123',
        confirmPassword: 'password123',
      },
    } as Request
    const res = {
      status: vi.fn().mockReturnThis(),
      json: vi.fn(),
    } as unknown as Response
    const next = vi.fn() as NextFunction

    await registerHandler(req, res, next)

    expect(registerUserMock).toHaveBeenCalledWith({
      email: 'new@example.com',
      displayName: '新用户',
      password: 'password123',
    })
    expect(session.regenerate).toHaveBeenCalled()
    expect(session.user).toEqual({
      id: 'user-2',
      email: 'new@example.com',
      displayName: '新用户',
      role: 'user',
    })
    expect(session.save).toHaveBeenCalled()
    expect(res.status).toHaveBeenCalledWith(201)
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: {
        user: {
          id: 'user-2',
          email: 'new@example.com',
          displayName: '新用户',
          role: 'user',
        },
      },
    })
    expect(next).not.toHaveBeenCalled()
  })

  it('returns validation errors to next on register', async () => {
    registerRequestSchemaMock.safeParse.mockReturnValueOnce({
      success: false,
      error: {
        issues: [{ message: '两次输入的密码不一致' }],
      },
    })

    const req = { body: {} } as Request
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await registerHandler(req, res, next)

    expect(next).toHaveBeenCalledWith(expect.objectContaining({
      statusCode: 400,
      message: '两次输入的密码不一致',
    }))
  })

  it('passes duplicate email errors through register handler', async () => {
    registerUserMock.mockRejectedValueOnce(new Error('该邮箱已存在'))

    const req = {
      body: {
        email: 'new@example.com',
        displayName: '新用户',
        password: 'password123',
        confirmPassword: 'password123',
      },
    } as Request
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await registerHandler(req, res, next)

    expect(next).toHaveBeenCalledWith(expect.objectContaining({
      message: '该邮箱已存在',
    }))
  })

  it('rejects registration with invalid verification code', async () => {
    verifyCodeMock.mockResolvedValueOnce(false)

    const req = {
      body: {
        email: 'new@example.com',
        displayName: '新用户',
        password: 'password123',
        confirmPassword: 'password123',
        verificationCode: '000000',
      },
    } as Request
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await registerHandler(req, res, next)

    expect(next).toHaveBeenCalledWith(expect.objectContaining({
      message: '验证码无效或已过期',
      statusCode: 400,
    }))
    expect(registerUserMock).not.toHaveBeenCalled()
  })

  it('destroys session and clears auth cookie on logout', async () => {
    const req = {
      session: {
        destroy: vi.fn((callback: (error?: Error) => void) => callback()),
      },
    } as unknown as Request
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await logoutHandler(req, res, next)

    expect(req.session.destroy).toHaveBeenCalled()
    expect(res.clearCookie).toHaveBeenCalledWith('y1.sid', { path: '/' })
    expect(res.json).toHaveBeenCalledWith({ success: true, data: { loggedOut: true } })
    expect(next).not.toHaveBeenCalled()
  })

  it('returns current user profile from me handler', async () => {
    const req = {} as Request
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await meHandler(req, res, next)

    expect(getAuthenticatedUserMock).toHaveBeenCalledWith(req)
    expect(getAuthUserProfileMock).toHaveBeenCalledWith('user-1')
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: {
        user: {
          id: 'user-1',
          email: 'user@example.com',
          displayName: '用户',
          role: 'admin',
        },
      },
    })
    expect(next).not.toHaveBeenCalled()
  })
})
