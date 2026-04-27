import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createServer, request as httpRequest } from 'node:http'
import type { Request, Response } from 'express'
import request from 'supertest'
import { Router } from 'express'

const { loginHandlerMock, registerHandlerMock } = vi.hoisted(() => ({
  loginHandlerMock: vi.fn((req, res) => {
    if (req.body?.password === 'password123') {
      req.loginAttemptOutcome = 'success'
      req.notifyLoginAttemptOutcomeSet?.()
      res.json({ success: true, data: { loggedIn: true } })
      return
    }

    if (req.body?.password === 'inactivepass1') {
      req.loginAttemptOutcome = 'auth_failure'
      req.notifyLoginAttemptOutcomeSet?.()
      res.status(403).json({
        success: false,
        error: '当前账号不可用',
      })
      return
    }

    req.loginAttemptOutcome = 'auth_failure'
    req.notifyLoginAttemptOutcomeSet?.()
    res.status(401).json({
      success: false,
      error: '邮箱或密码错误',
    })
  }),
  registerHandlerMock: vi.fn((_req, res) => {
    res.status(201).json({ success: true, data: { registered: true } })
  }),
}))

vi.mock('./routes/auth.js', () => {
  const authRouter = Router()
  authRouter.post('/login', loginHandlerMock)
  authRouter.post('/register', registerHandlerMock)
  authRouter.post('/logout', (_req, res) => {
    res.json({ success: true, data: { loggedOut: true } })
  })
  authRouter.get('/me', (_req, res) => {
    res.json({ success: true, data: { user: null } })
  })

  return { authRouter }
})

vi.mock('./routes/bilibili.js', () => ({ bilibiliRouter: Router() }))
vi.mock('./routes/douyin.js', () => ({ douyinRouter: Router() }))
vi.mock('./routes/image-analysis.js', () => ({ imageAnalysisRouter: Router() }))
vi.mock('./routes/article-generation.js', () => ({ articleGenerationRouter: Router() }))
vi.mock('./routes/video-recreation.js', () => {
  const videoRecreationRouter = Router()
  videoRecreationRouter.use((_req, _res, next) => {
    next()
  })
  videoRecreationRouter.post('/adapt-content', (_req, res) => {
    res.json({ success: true, data: { adaptedSummary: 'ok' } })
  })

  return { videoRecreationRouter }
})
vi.mock('./routes/settings.js', () => ({ settingsRouter: Router() }))
vi.mock('./routes/homepage.js', () => ({ homepageRouter: Router() }))

describe('app login rate limit', () => {
  beforeEach(() => {
    loginHandlerMock.mockReset()
    registerHandlerMock.mockReset()
    loginHandlerMock.mockImplementation((req, res) => {
      if (req.body?.password === 'password123') {
        req.loginAttemptOutcome = 'success'
        req.notifyLoginAttemptOutcomeSet?.()
        res.json({ success: true, data: { loggedIn: true } })
        return
      }

      if (req.body?.password === 'inactivepass1') {
        req.loginAttemptOutcome = 'auth_failure'
        req.notifyLoginAttemptOutcomeSet?.()
        res.status(403).json({
          success: false,
          error: '当前账号不可用',
        })
        return
      }

      req.loginAttemptOutcome = 'auth_failure'
      req.notifyLoginAttemptOutcomeSet?.()
      res.status(401).json({
        success: false,
        error: '邮箱或密码错误',
      })
    })
    registerHandlerMock.mockImplementation((_req, res) => {
      res.status(201).json({ success: true, data: { registered: true } })
    })
  })

  it('blocks repeated failed POST /api/auth/login requests for the same account on the same client', async () => {
    const { createApp } = await import('./app.js')
    const app = createApp()

    for (let attempt = 0; attempt < 5; attempt += 1) {
      const response = await request(app)
        .post('/api/auth/login')
        .send({ email: 'user@example.com', password: 'wrongpass1' })

      expect(response.status).toBe(401)
    }

    const response = await request(app)
      .post('/api/auth/login')
      .send({ email: 'user@example.com', password: 'wrongpass1' })

    expect(response.status).toBe(429)
    expect(response.body).toEqual({
      success: false,
      error: '登录请求过于频繁，请稍后再试。',
    })
    expect(response.headers['ratelimit-limit']).toBe('5')
    expect(response.headers['ratelimit-remaining']).toBe('0')
    expect(response.headers['ratelimit-reset']).toBeDefined()
  })

  it('does not count successful logins toward the failed-login limit', async () => {
    const { createApp } = await import('./app.js')
    const app = createApp()

    for (let attempt = 0; attempt < 6; attempt += 1) {
      const response = await request(app)
        .post('/api/auth/login')
        .send({ email: 'user@example.com', password: 'password123' })

      expect(response.status).toBe(200)
    }
  })

  it('blocks repeated failed logins across different accounts from the same client IP', async () => {
    const { createApp } = await import('./app.js')
    const app = createApp()

    for (let attempt = 0; attempt < 10; attempt += 1) {
      const response = await request(app)
        .post('/api/auth/login')
        .send({ email: `user${attempt}@example.com`, password: 'wrongpass1' })

      expect(response.status).toBe(401)
    }

    const response = await request(app)
      .post('/api/auth/login')
      .send({ email: 'another@example.com', password: 'wrongpass1' })

    expect(response.status).toBe(429)
    expect(response.headers['ratelimit-limit']).toBe('10')
    expect(response.headers['ratelimit-remaining']).toBe('0')
    expect(response.body).toEqual({
      success: false,
      error: '登录请求过于频繁，请稍后再试。',
    })
  })

  it('counts 403 login failures toward the limiter', async () => {
    const { createApp } = await import('./app.js')
    const app = createApp()

    for (let attempt = 0; attempt < 5; attempt += 1) {
      const response = await request(app)
        .post('/api/auth/login')
        .send({ email: 'inactive@example.com', password: 'inactivepass1' })

      expect(response.status).toBe(403)
    }

    const response = await request(app)
      .post('/api/auth/login')
      .send({ email: 'inactive@example.com', password: 'inactivepass1' })

    expect(response.status).toBe(429)
    expect(response.headers['ratelimit-limit']).toBe('5')
    expect(response.headers['ratelimit-remaining']).toBe('0')
    expect(response.body).toEqual({
      success: false,
      error: '登录请求过于频繁，请稍后再试。',
    })
  })

  it('blocks repeated POST /api/auth/register requests from the same client', async () => {
    const { createApp } = await import('./app.js')
    const app = createApp()

    for (let attempt = 0; attempt < 5; attempt += 1) {
      const response = await request(app)
        .post('/api/auth/register')
        .send({ email: `new${attempt}@example.com`, password: 'password123', confirmPassword: 'password123' })

      expect(response.status).toBe(201)
    }

    const response = await request(app)
      .post('/api/auth/register')
      .send({ email: 'blocked@example.com', password: 'password123', confirmPassword: 'password123' })

    expect(response.status).toBe(429)
    expect(response.headers['ratelimit-limit']).toBe('5')
    expect(response.headers['ratelimit-remaining']).toBe('0')
    expect(response.body).toEqual({
      success: false,
      error: '注册请求过于频繁，请稍后再试。',
    })
  })

  it('blocks concurrent failed logins once the same-account budget is exhausted', async () => {
    let releaseHandler: (() => void) | undefined
    const handlerReady = new Promise<void>((resolve) => {
      releaseHandler = resolve
    })

    loginHandlerMock.mockImplementationOnce(async (_req: Request, res: Response) => {
      await handlerReady
      _req.loginAttemptOutcome = 'auth_failure'
      _req.notifyLoginAttemptOutcomeSet?.()
      res.status(401).json({
        success: false,
        error: '邮箱或密码错误',
      })
    }).mockImplementationOnce(async (_req: Request, res: Response) => {
      await handlerReady
      _req.loginAttemptOutcome = 'auth_failure'
      _req.notifyLoginAttemptOutcomeSet?.()
      res.status(401).json({
        success: false,
        error: '邮箱或密码错误',
      })
    }).mockImplementationOnce(async (_req: Request, res: Response) => {
      await handlerReady
      _req.loginAttemptOutcome = 'auth_failure'
      _req.notifyLoginAttemptOutcomeSet?.()
      res.status(401).json({
        success: false,
        error: '邮箱或密码错误',
      })
    }).mockImplementationOnce(async (_req: Request, res: Response) => {
      await handlerReady
      _req.loginAttemptOutcome = 'auth_failure'
      _req.notifyLoginAttemptOutcomeSet?.()
      res.status(401).json({
        success: false,
        error: '邮箱或密码错误',
      })
    }).mockImplementationOnce(async (_req: Request, res: Response) => {
      await handlerReady
      _req.loginAttemptOutcome = 'auth_failure'
      _req.notifyLoginAttemptOutcomeSet?.()
      res.status(401).json({
        success: false,
        error: '邮箱或密码错误',
      })
    })

    const { createApp } = await import('./app.js')
    const app = createApp()

    const responsePromises = Array.from({ length: 6 }, () => {
      return request(app)
        .post('/api/auth/login')
        .send({ email: 'parallel@example.com', password: 'wrongpass1' })
    })

    await Promise.resolve()
    releaseHandler?.()

    const responses = await Promise.all(responsePromises)
    const blockedResponses = responses.filter((response) => response.status === 429)
    const failedResponses = responses.filter((response) => response.status === 401)

    expect(failedResponses).toHaveLength(5)
    expect(blockedResponses).toHaveLength(1)
    expect(blockedResponses[0]?.headers['ratelimit-limit']).toBe('5')
    expect(blockedResponses[0]?.body).toEqual({
      success: false,
      error: '登录请求过于频繁，请稍后再试。',
    })
  })

  it('counts aborted failed logins toward the limiter budget', async () => {
    const createBlockedLoginHandler = (status: 401 | 200) => {
      let releaseHandler: (() => void) | undefined
      let startedHandler: (() => void) | undefined
      const handlerReady = new Promise<void>((resolve) => {
        releaseHandler = resolve
      })
      const handlerStarted = new Promise<void>((resolve) => {
        startedHandler = resolve
      })

      loginHandlerMock.mockImplementationOnce(async (_req: Request, res: Response) => {
        startedHandler?.()
        await handlerReady

        if (status === 200) {
          _req.loginAttemptOutcome = 'success'
          _req.notifyLoginAttemptOutcomeSet?.()
          res.json({ success: true, data: { loggedIn: true } })
          return
        }

        _req.loginAttemptOutcome = 'auth_failure'
        _req.notifyLoginAttemptOutcomeSet?.()
        res.status(401).json({
          success: false,
          error: '邮箱或密码错误',
        })
      })

      return {
        waitForStart: () => handlerStarted,
        release: () => {
          releaseHandler?.()
        },
      }
    }

    const { createApp } = await import('./app.js')
    const server = createServer(createApp())

    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', () => resolve()))
    const address = server.address()
    const port = typeof address === 'object' && address ? address.port : 0

    const abortLogin = async (status: 401 | 200, payload: { email: string, password: string }) => {
      const blockedHandler = createBlockedLoginHandler(status)

      await new Promise<void>((resolve) => {
        const req = httpRequest({
          host: '127.0.0.1',
          port,
          path: '/api/auth/login',
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
        })

        req.on('error', () => resolve())
        req.write(JSON.stringify(payload))
        req.end()

        void blockedHandler.waitForStart().then(() => {
          req.destroy()
          blockedHandler.release()
        })
      })
    }

    try {
      for (let attempt = 0; attempt < 5; attempt += 1) {
        await abortLogin(401, { email: 'disconnect@example.com', password: 'wrongpass1' })
      }

      const blockedResponse = await request(server)
        .post('/api/auth/login')
        .send({ email: 'disconnect@example.com', password: 'wrongpass1' })

      expect(blockedResponse.status).toBe(429)
      expect(blockedResponse.headers['ratelimit-limit']).toBe('5')
      expect(blockedResponse.headers['ratelimit-remaining']).toBe('0')
      expect(blockedResponse.body).toEqual({
        success: false,
        error: '登录请求过于频繁，请稍后再试。',
      })

      await abortLogin(200, { email: 'success-disconnect@example.com', password: 'password123' })

      const successfulResponse = await request(server)
        .post('/api/auth/login')
        .send({ email: 'success-disconnect@example.com', password: 'wrongpass1' })

      expect(successfulResponse.status).toBe(401)
    } finally {
      await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()))
    }
  })
})
