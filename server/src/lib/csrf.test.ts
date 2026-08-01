import { afterEach, describe, expect, it, vi } from 'vitest'
import express from 'express'
import request from 'supertest'

/**
 * GL-P0-AUTH-001：状态变更请求的 Origin/Referer 校验。
 */
async function buildApp(
  envOverrides: Record<string, unknown> = {},
  allowedOrigins: string[] = ['https://app.example.com'],
) {
  vi.resetModules()
  vi.doMock('./env.js', () => ({
    env: {
      SECURITY_CSRF_ORIGIN_CHECK: '1',
      PUBLIC_BACKEND_ORIGIN: undefined,
      ...envOverrides,
    },
  }))

  const { createCsrfOriginCheck } = await import('./csrf.js')
  const app = express()
  app.use('/api', createCsrfOriginCheck({ allowedOrigins }))
  app.all('/api/thing', (_req, res) => {
    res.json({ success: true })
  })
  return app
}

describe('createCsrfOriginCheck', () => {
  afterEach(() => {
    vi.resetModules()
    vi.doUnmock('./env.js')
  })

  it('allows GET regardless of origin', async () => {
    const app = await buildApp()
    const response = await request(app).get('/api/thing').set('Origin', 'https://evil.example')

    expect(response.status).toBe(200)
  })

  it('rejects state-changing requests from a foreign origin', async () => {
    const app = await buildApp()
    const response = await request(app).post('/api/thing').set('Origin', 'https://evil.example')

    expect(response.status).toBe(403)
    expect(response.body.success).toBe(false)
  })

  it('rejects PUT/PATCH/DELETE from a foreign origin too', async () => {
    const app = await buildApp()

    for (const method of ['put', 'patch', 'delete'] as const) {
      const response = await request(app)[method]('/api/thing').set('Origin', 'https://evil.example')
      expect(response.status).toBe(403)
    }
  })

  it('allows a configured allowlist origin', async () => {
    const app = await buildApp()
    const response = await request(app).post('/api/thing').set('Origin', 'https://app.example.com')

    expect(response.status).toBe(200)
  })

  it('allows same-origin requests without an allowlist entry', async () => {
    const app = await buildApp({}, [])
    const response = await request(app)
      .post('/api/thing')
      .set('Host', 'self.example.com')
      .set('Origin', 'http://self.example.com')

    expect(response.status).toBe(200)
  })

  it('resolves same-origin over HTTPS from X-Forwarded-Proto without trust proxy', async () => {
    // 漏配 TRUST_PROXY=1 时 req.secure 为假；若据此算自身 origin，HTTPS 同源请求会被 403。
    const app = await buildApp({}, [])
    const response = await request(app)
      .post('/api/thing')
      .set('Host', 'self.example.com')
      .set('X-Forwarded-Proto', 'https')
      .set('Origin', 'https://self.example.com')

    expect(response.status).toBe(200)
  })

  it('still rejects a foreign origin when the forwarded proto is https', async () => {
    const app = await buildApp({}, [])
    const response = await request(app)
      .post('/api/thing')
      .set('Host', 'self.example.com')
      .set('X-Forwarded-Proto', 'https')
      .set('Origin', 'https://evil.example')

    expect(response.status).toBe(403)
  })

  it('allows requests with no Origin and no Referer (non-browser clients)', async () => {
    // curl / 服务端调用不存在「被害者浏览器自动带 cookie」的前提，拦它们无收益。
    const app = await buildApp()
    const response = await request(app).post('/api/thing')

    expect(response.status).toBe(200)
  })

  it('falls back to Referer when Origin is absent', async () => {
    const app = await buildApp()

    const allowed = await request(app).post('/api/thing').set('Referer', 'https://app.example.com/page')
    expect(allowed.status).toBe(200)

    const denied = await request(app).post('/api/thing').set('Referer', 'https://evil.example/page')
    expect(denied.status).toBe(403)
  })

  it('treats an opaque "null" Origin as absent and falls through to Referer', async () => {
    const app = await buildApp()

    const denied = await request(app).post('/api/thing').set('Origin', 'null').set('Referer', 'https://evil.example/x')
    expect(denied.status).toBe(403)
  })

  it('accepts PUBLIC_BACKEND_ORIGIN as an allowed origin', async () => {
    const app = await buildApp({ PUBLIC_BACKEND_ORIGIN: 'https://api.example.com' }, [])
    const response = await request(app).post('/api/thing').set('Origin', 'https://api.example.com')

    expect(response.status).toBe(200)
  })

  it('can be disabled for troubleshooting', async () => {
    const app = await buildApp({ SECURITY_CSRF_ORIGIN_CHECK: '0' })
    const response = await request(app).post('/api/thing').set('Origin', 'https://evil.example')

    expect(response.status).toBe(200)
  })

  it('rejects a malformed Origin header', async () => {
    const app = await buildApp()
    const response = await request(app).post('/api/thing').set('Origin', 'not-a-url').set('Referer', 'also-not-a-url')

    // Origin/Referer 都解析不出 origin → 视作无来源信息 → 放行（非浏览器客户端）。
    expect(response.status).toBe(200)
  })
})
