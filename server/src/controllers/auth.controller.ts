import type { NextFunction, Request, Response } from 'express'
import { AppError } from '../lib/errors.js'
import { env } from '../lib/env.js'
import { assertAuthConfigured, getAuthenticatedUser, getSessionOrThrow, setLoginAttemptOutcome } from '../lib/auth.js'
import { loginRequestSchema, registerRequestSchema, sendCodeRequestSchema } from '../schemas/auth.js'
import { authenticateUser, getAuthUserProfile, registerUser } from '../services/user.service.js'
import { sendVerificationCode, verifyCode } from '../services/email-verification.service.js'
import { generateCaptcha, storeCaptcha, validateCaptcha } from '../services/captcha.service.js'

function toSessionUser(user: Awaited<ReturnType<typeof authenticateUser>>) {
  return {
    id: user.id,
    email: user.email,
    displayName: user.displayName,
    role: user.role,
  }
}

async function persistAuthenticatedSession(req: Request, user: Awaited<ReturnType<typeof authenticateUser>>): Promise<void> {
  const sessionUser = toSessionUser(user)
  const initialSession = getSessionOrThrow(req)

  await new Promise<void>((resolve, reject) => {
    initialSession.regenerate((error) => {
      if (error) {
        reject(error)
        return
      }

      const activeSession = getSessionOrThrow(req)
      activeSession.user = sessionUser
      activeSession.save((saveError) => {
        if (saveError) {
          reject(saveError)
          return
        }

        resolve()
      })
    })
  })
}

function getValidationMessage(error: AppError | { issues: Array<{ message: string }> }): string {
  if (error instanceof AppError) {
    return error.message
  }

  return error.issues.map((issue) => issue.message).join('; ')
}

export async function loginHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    assertAuthConfigured()
    const parsed = loginRequestSchema.safeParse(req.body)
    if (!parsed.success) {
      throw new AppError(getValidationMessage(parsed.error), 400)
    }

    const user = await authenticateUser(parsed.data.email, parsed.data.password)
    await persistAuthenticatedSession(req, user)

    setLoginAttemptOutcome(req, 'success')
    res.json({
      success: true,
      data: {
        user: toSessionUser(user),
      },
    })
  } catch (error: unknown) {
    setLoginAttemptOutcome(
      req,
      error instanceof AppError && (error.statusCode === 401 || error.statusCode === 403)
        ? 'auth_failure'
        : 'other_failure',
    )
    next(error)
  }
}

export async function registerHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    assertAuthConfigured()
    const parsed = registerRequestSchema.safeParse(req.body)
    if (!parsed.success) {
      throw new AppError(getValidationMessage(parsed.error), 400)
    }

    const isCodeValid = await verifyCode(parsed.data.email, parsed.data.verificationCode)
    if (!isCodeValid) {
      throw new AppError('验证码无效或已过期', 400)
    }

    const user = await registerUser({
      email: parsed.data.email,
      password: parsed.data.password,
      displayName: parsed.data.displayName,
    })

    await persistAuthenticatedSession(req, user)

    res.status(201).json({
      success: true,
      data: {
        user: toSessionUser(user),
      },
    })
  } catch (error: unknown) {
    next(error)
  }
}

export async function captchaHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { text, svg } = generateCaptcha()
    storeCaptcha(req.session as unknown as Record<string, unknown>, text)

    await new Promise<void>((resolve, reject) => {
      req.session.save((error: unknown) => {
        if (error) {
          reject(error)
          return
        }
        resolve()
      })
    })

    res.setHeader('Content-Type', 'image/svg+xml')
    res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate')
    res.send(svg)
  } catch (error: unknown) {
    next(error)
  }
}

export async function sendCodeHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const parsed = sendCodeRequestSchema.safeParse(req.body)
    if (!parsed.success) {
      throw new AppError(getValidationMessage(parsed.error), 400)
    }

    if (!validateCaptcha(req.session as unknown as Record<string, unknown>, parsed.data.captchaCode)) {
      throw new AppError('图形验证码错误或已过期', 400)
    }

    await sendVerificationCode(parsed.data.email)

    res.json({
      success: true,
      data: { sent: true },
    })
  } catch (error: unknown) {
    next(error)
  }
}

export async function logoutHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    assertAuthConfigured()

    if (!req.session) {
      res.json({ success: true, data: { loggedOut: true } })
      return
    }

    await new Promise<void>((resolve, reject) => {
      req.session.destroy((error) => {
        if (error) {
          reject(error)
          return
        }

        resolve()
      })
    })

    res.clearCookie(env.SESSION_COOKIE_NAME, { path: '/' })
    res.json({ success: true, data: { loggedOut: true } })
  } catch (error: unknown) {
    next(error)
  }
}

export async function meHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const authUser = getAuthenticatedUser(req)
    const user = await getAuthUserProfile(authUser.id)
    res.json({
      success: true,
      data: {
        user: toSessionUser(user),
      },
    })
  } catch (error: unknown) {
    next(error)
  }
}
