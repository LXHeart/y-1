import { afterEach, describe, expect, it, vi } from 'vitest'
import express from 'express'
import request from 'supertest'

/**
 * GL-P0-AUTH-001：安全响应头与 HSTS 的双条件（开关 + 本次请求确实是 HTTPS）。
 */
async function buildApp(envOverrides: Record<string, unknown>, trustProxy = false) {
  vi.resetModules()
  vi.doMock('./env.js', () => ({
    env: {
      NODE_ENV: 'production',
      SECURITY_HSTS_ENABLED: '0',
      SECURITY_HSTS_MAX_AGE_SECONDS: 15552000,
      ...envOverrides,
    },
  }))

  const { createSecurityHeaders } = await import('./security-headers.js')
  const app = express()
  if (trustProxy) {
    app.set('trust proxy', 1)
  }
  app.use(createSecurityHeaders())
  app.get('/api/auth/me', (_req, res) => {
    res.json({ ok: true })
  })
  app.get('/api/credits/balance', (_req, res) => {
    res.json({ ok: true })
  })
  return app
}

describe('createSecurityHeaders', () => {
  afterEach(() => {
    vi.resetModules()
    vi.doUnmock('./env.js')
  })

  it('sets baseline hardening headers', async () => {
    const app = await buildApp({})
    const response = await request(app).get('/api/credits/balance')

    expect(response.headers['x-content-type-options']).toBe('nosniff')
    expect(response.headers['x-frame-options']).toBe('DENY')
    expect(response.headers['referrer-policy']).toBe('strict-origin-when-cross-origin')
    expect(response.headers['cross-origin-opener-policy']).toBe('same-origin')
    expect(response.headers['permissions-policy']).toContain('camera=()')
    expect(response.headers['x-powered-by']).toBeUndefined()
  })

  it('omits HSTS when the switch is off even on HTTPS', async () => {
    const app = await buildApp({ SECURITY_HSTS_ENABLED: '0' }, true)
    const response = await request(app).get('/api/credits/balance').set('X-Forwarded-Proto', 'https')

    expect(response.headers['strict-transport-security']).toBeUndefined()
  })

  it('omits HSTS on plain HTTP even when the switch is on', async () => {
    // 关键：HTTP 部署上发 HSTS 会被浏览器记住，把站点锁进 https 而实际不可达。
    const app = await buildApp({ SECURITY_HSTS_ENABLED: '1' })
    const response = await request(app).get('/api/credits/balance')

    expect(response.headers['strict-transport-security']).toBeUndefined()
  })

  it('sends HSTS when switched on and the request is HTTPS', async () => {
    const app = await buildApp({ SECURITY_HSTS_ENABLED: '1' })
    const response = await request(app).get('/api/credits/balance').set('X-Forwarded-Proto', 'https')

    expect(response.headers['strict-transport-security']).toBe('max-age=15552000; includeSubDomains')
  })

  it('uses the leftmost hop of a multi-value X-Forwarded-Proto', async () => {
    const app = await buildApp({ SECURITY_HSTS_ENABLED: '1' })

    const secure = await request(app).get('/api/credits/balance').set('X-Forwarded-Proto', 'https, http')
    expect(secure.headers['strict-transport-security']).toBeDefined()

    const insecure = await request(app).get('/api/credits/balance').set('X-Forwarded-Proto', 'http, https')
    expect(insecure.headers['strict-transport-security']).toBeUndefined()
  })

  it('honours a configured HSTS max-age', async () => {
    const app = await buildApp({ SECURITY_HSTS_ENABLED: '1', SECURITY_HSTS_MAX_AGE_SECONDS: 300 })
    const response = await request(app).get('/api/credits/balance').set('X-Forwarded-Proto', 'https')

    expect(response.headers['strict-transport-security']).toBe('max-age=300; includeSubDomains')
  })

  it('marks auth responses no-store but leaves other routes alone', async () => {
    const app = await buildApp({})

    const auth = await request(app).get('/api/auth/me')
    expect(auth.headers['cache-control']).toBe('no-store')

    const other = await request(app).get('/api/credits/balance')
    expect(other.headers['cache-control']).toBeUndefined()
  })
})
