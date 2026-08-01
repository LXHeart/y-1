import type { RequestHandler } from 'express'
import session from 'express-session'
import connectPgSimple from 'connect-pg-simple'
import { env } from './env.js'
import { getDbPool } from './db.js'
import { isAuthConfigured } from './auth.js'

const PGStore = connectPgSimple(session)
const DEFAULT_SESSION_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000

let sessionMiddleware: RequestHandler | null = null

/**
 * 会话 cookie 的 Secure 策略（GL-P0-AUTH-001）。
 *
 * `identity-service` 的 `SESSION_COOKIE_SECURE` 与此同名同义 —— 两端写同一张 session 表，
 * 属性不一致会在 rolling 续期时互相覆盖（Java 写 secure:false 会抹掉 Express 加的 Secure）。
 *
 * - `auto`（express-session 原生语义）：按 `req.secure` 判定，即连接是 HTTPS 或
 *   `trust proxy` 生效时 `X-Forwarded-Proto: https`。**`auto` 依赖 TRUST_PROXY=1**，
 *   否则 nginx 后的 HTTP 跳会一直判为非 Secure。
 * - `always`：恒定加 Secure。**注意 express-session 在 `secure: true` 且 `req.secure` 为假时
 *   整个 Set-Cookie 都不发**（不是发一个没有 Secure 的），所以 `always` 同样需要
 *   `TRUST_PROXY=1` + 上游 `X-Forwarded-Proto: https`；HTTP 入口上误配 `always`
 *   会让登录静默失效（返回 200 但没有会话 cookie）。见 `session-cookie.test.ts`。
 * - `never`：恒不加。仅本地/明确无 TLS 的内网。
 *
 * 未显式配置时保持原行为：production → `auto`，其余 → `never`。
 *
 * Java 侧同名配置在 `always` 下的行为不同：identity-service 会照发带 Secure 的 cookie，
 * 不做「连接是否 HTTPS」的自我否决。两端语义在 `auto` 下一致，这是推荐取值。
 */
export function resolveSessionCookieSecure(): boolean | 'auto' {
  const mode = env.SESSION_COOKIE_SECURE ?? (env.NODE_ENV === 'production' ? 'auto' : 'never')
  if (mode === 'always') {
    return true
  }

  if (mode === 'never') {
    return false
  }

  return 'auto'
}

export function resolveSessionCookieSameSite(): 'lax' | 'strict' | 'none' {
  const raw = env.SESSION_COOKIE_SAME_SITE?.toLowerCase()
  if (raw === 'strict' || raw === 'none') {
    return raw
  }

  return 'lax'
}

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
      sameSite: resolveSessionCookieSameSite(),
      secure: resolveSessionCookieSecure(),
      maxAge: env.SESSION_COOKIE_MAX_AGE_MS ?? DEFAULT_SESSION_MAX_AGE_MS,
    },
  })

  return sessionMiddleware
}
