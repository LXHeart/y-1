import { afterEach, describe, expect, it, vi } from 'vitest'
import express from 'express'
import request from 'supertest'

/**
 * GL-P0-AUTH-001：Express 侧 secure/sameSite 解析。
 * 取值必须与 identity-service 的 SESSION_COOKIE_SECURE 同义（两端写同一张 session 表）。
 */
async function loadResolvers(envOverrides: Record<string, unknown>, authConfigured = false) {
  vi.resetModules()
  vi.doMock('./env.js', () => ({
    env: { NODE_ENV: 'development', SESSION_SECRET: 'x'.repeat(32), SESSION_COOKIE_NAME: 'y1.sid', ...envOverrides },
  }))
  vi.doMock('./db.js', () => ({ getDbPool: () => ({}) }))
  vi.doMock('./auth.js', () => ({ isAuthConfigured: () => authConfigured }))
  // connect-pg-simple 会在构造时碰连接池；换成 express-session 自带的 MemoryStore。
  // 注意不能返回 session.Store（抽象基类的 set() 不回调 callback，请求会挂到超时）。
  vi.doMock('connect-pg-simple', () => ({
    default: (session: { MemoryStore: unknown }) => session.MemoryStore,
  }))
  return import('./session.js')
}

/** 起一个真实挂载 session 中间件的 app，断言线上实际发出的 Set-Cookie 属性。 */
async function setCookieFor(envOverrides: Record<string, unknown>, forwardedProto?: string) {
  const { createSessionMiddleware } = await loadResolvers(envOverrides, true)
  const app = express()
  app.set('trust proxy', 1)
  app.use(createSessionMiddleware())
  app.get('/touch', (req, res) => {
    ;(req.session as unknown as Record<string, unknown>).touched = true
    res.json({ ok: true })
  })

  const pending = request(app).get('/touch')
  if (forwardedProto) {
    pending.set('X-Forwarded-Proto', forwardedProto)
  }
  const response = await pending
  return String(response.headers['set-cookie'] ?? '')
}

describe('session cookie resolution', () => {
  afterEach(() => {
    vi.resetModules()
    vi.doUnmock('./env.js')
    vi.doUnmock('./db.js')
    vi.doUnmock('./auth.js')
    vi.doUnmock('connect-pg-simple')
  })

  it('defaults to auto in production and never elsewhere', async () => {
    const prod = await loadResolvers({ NODE_ENV: 'production' })
    expect(prod.resolveSessionCookieSecure()).toBe('auto')

    const dev = await loadResolvers({ NODE_ENV: 'development' })
    expect(dev.resolveSessionCookieSecure()).toBe(false)
  })

  it('maps always/never/auto explicitly', async () => {
    const always = await loadResolvers({ SESSION_COOKIE_SECURE: 'always' })
    expect(always.resolveSessionCookieSecure()).toBe(true)

    const never = await loadResolvers({ NODE_ENV: 'production', SESSION_COOKIE_SECURE: 'never' })
    expect(never.resolveSessionCookieSecure()).toBe(false)

    const auto = await loadResolvers({ SESSION_COOKIE_SECURE: 'auto' })
    expect(auto.resolveSessionCookieSecure()).toBe('auto')
  })

  it('normalizes sameSite with lax as the default', async () => {
    const strict = await loadResolvers({ SESSION_COOKIE_SAME_SITE: 'Strict' })
    expect(strict.resolveSessionCookieSameSite()).toBe('strict')

    const none = await loadResolvers({ SESSION_COOKIE_SAME_SITE: 'none' })
    expect(none.resolveSessionCookieSameSite()).toBe('none')

    const fallback = await loadResolvers({})
    expect(fallback.resolveSessionCookieSameSite()).toBe('lax')
  })
})

describe('session cookie on the wire', () => {
  afterEach(() => {
    vi.resetModules()
    vi.doUnmock('./env.js')
    vi.doUnmock('./db.js')
    vi.doUnmock('./auth.js')
    vi.doUnmock('connect-pg-simple')
  })

  it('emits Secure when SESSION_COOKIE_SECURE=always behind HTTPS', async () => {
    const cookie = await setCookieFor({ SESSION_COOKIE_SECURE: 'always' }, 'https')

    expect(cookie).toContain('y1.sid=')
    expect(cookie).toContain('Secure')
    expect(cookie).toContain('HttpOnly')
    expect(cookie).toMatch(/SameSite=Lax/i)
  })

  it('sends no cookie at all when always is配错在 HTTP 入口上', async () => {
    // express-session 在 secure:true 且连接非 HTTPS 时**整个 Set-Cookie 都不发**（不是发个无 Secure 的）。
    // 后果：HTTP 部署误配 always 会让登录静默失效（200 但没有会话）。
    // 这是「安全但会坏功能」的一侧，运维文档必须写明，故在此钉住行为。
    const cookie = await setCookieFor({ SESSION_COOKIE_SECURE: 'always' })

    expect(cookie).toBe('')
  })

  it('emits Secure in auto mode only when the forwarded proto is https', async () => {
    // auto 依赖 TRUST_PROXY=1（app.set('trust proxy')）才能看见 X-Forwarded-Proto。
    const secure = await setCookieFor({ SESSION_COOKIE_SECURE: 'auto' }, 'https')
    expect(secure).toContain('Secure')

    const insecure = await setCookieFor({ SESSION_COOKIE_SECURE: 'auto' }, 'http')
    expect(insecure).not.toContain('Secure')
  })

  it('omits Secure in never mode even behind HTTPS', async () => {
    const cookie = await setCookieFor({ SESSION_COOKIE_SECURE: 'never' }, 'https')

    expect(cookie).toContain('y1.sid=')
    expect(cookie).not.toContain('Secure')
  })

  it('applies a configured SameSite value', async () => {
    const cookie = await setCookieFor(
      { SESSION_COOKIE_SECURE: 'always', SESSION_COOKIE_SAME_SITE: 'Strict' },
      'https',
    )

    expect(cookie).toMatch(/SameSite=Strict/i)
  })
})
