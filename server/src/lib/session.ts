import type { RequestHandler } from 'express'
import session from 'express-session'
import connectPgSimple from 'connect-pg-simple'
import { env } from './env.js'
import { getDbPool } from './db.js'
import { isAuthConfigured } from './auth.js'

const PGStore = connectPgSimple(session)
const DEFAULT_SESSION_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000

let sessionMiddleware: RequestHandler | null = null

export function createSessionMiddleware(): RequestHandler {
  if (!isAuthConfigured()) {
    return (_req, _res, next) => {
      next()
    }
  }

  if (sessionMiddleware) {
    return sessionMiddleware
  }

  sessionMiddleware = session({
    name: env.SESSION_COOKIE_NAME,
    secret: env.SESSION_SECRET!,
    store: new PGStore({
      pool: getDbPool(),
      tableName: 'session',
      createTableIfMissing: false,
    }),
    resave: false,
    saveUninitialized: false,
    rolling: true,
    unset: 'destroy',
    cookie: {
      httpOnly: true,
      sameSite: 'lax',
      secure: env.NODE_ENV === 'production' ? 'auto' : false,
      maxAge: env.SESSION_COOKIE_MAX_AGE_MS ?? DEFAULT_SESSION_MAX_AGE_MS,
    },
  })

  return sessionMiddleware
}
