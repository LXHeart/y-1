import express from 'express'
import request from 'supertest'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { NextFunction, Request, Response } from 'express'

interface RouteLike {
  path?: string
  methods?: Record<string, boolean>
}

interface RouterLayerLike {
  route?: RouteLike
}

const {
  requireAuthenticatedUserMock,
  adaptContentHandlerMock,
} = vi.hoisted(() => ({
  requireAuthenticatedUserMock: vi.fn((req: Request, res: Response, next: NextFunction) => {
    if (req.headers['x-auth'] === 'ok' || req.headers['x-auth'] === 'other') {
      req.authUser = {
        id: req.headers['x-auth'] === 'other' ? 'user-2' : 'user-1',
        email: req.headers['x-auth'] === 'other' ? 'user2@example.com' : 'user1@example.com',
        role: 'user',
      }
      next()
      return
    }

    res.status(401).json({
      success: false,
      error: '请先登录',
    })
  }),
  adaptContentHandlerMock: vi.fn((_req: Request, res: Response) => {
    res.json({ success: true, data: { adaptedSummary: 'ok' } })
  }),
}))

vi.mock('../controllers/video-recreation.controller.js', () => ({
  adaptContentHandler: adaptContentHandlerMock,
  generateAssetImageHandler: vi.fn((_req: Request, res: Response) => {
    res.json({ success: true, data: { imageUrl: 'one' } })
  }),
  generateAllAssetImagesHandler: vi.fn((_req: Request, res: Response) => {
    res.json({ success: true, data: { images: [{ imageUrl: 'batch' }] } })
  }),
  generateSceneImageHandler: vi.fn((_req: Request, res: Response) => {
    res.json({ success: true, data: { imageUrl: 'scene' } })
  }),
  generateAllSceneImagesHandler: vi.fn((_req: Request, res: Response) => {
    res.json({ success: true, data: { images: [{ imageUrl: 'scene-batch' }] } })
  }),
}))

vi.mock('../lib/auth.js', () => ({
  requireAuthenticatedUser: requireAuthenticatedUserMock,
}))

describe('video recreation routes', () => {
  beforeEach(() => {
    vi.resetModules()
    requireAuthenticatedUserMock.mockClear()
    adaptContentHandlerMock.mockClear()
  })

  it('registers POST /adapt-content', async () => {
    const { videoRecreationRouter } = await import('./video-recreation.js')
    const layer = (videoRecreationRouter.stack as RouterLayerLike[]).find((entry) => {
      return entry.route?.path === '/adapt-content' && entry.route.methods?.post === true
    })

    expect(layer?.route?.path).toBe('/adapt-content')
  })

  it('limits authenticated adapt-content requests', async () => {
    const { videoRecreationRouter } = await import('./video-recreation.js')
    const app = express()
    app.use(videoRecreationRouter)

    for (let attempt = 0; attempt < 10; attempt += 1) {
      const response = await request(app)
        .post('/adapt-content')
        .set('x-auth', 'ok')
        .send({ platform: 'douyin' })

      expect(response.status).toBe(200)
    }

    const response = await request(app)
      .post('/adapt-content')
      .set('x-auth', 'ok')
      .send({ platform: 'douyin' })

    expect(response.status).toBe(429)
    expect(response.headers['ratelimit-limit']).toBe('10')
    expect(response.headers['ratelimit-remaining']).toBe('0')
    expect(response.body).toEqual({
      success: false,
      error: '视频改编请求过于频繁，请稍后再试。',
    })
  })

  it('does not spend the rate-limit budget on unauthenticated requests', async () => {
    const { videoRecreationRouter } = await import('./video-recreation.js')
    const app = express()
    app.use(videoRecreationRouter)

    for (let attempt = 0; attempt < 11; attempt += 1) {
      const response = await request(app)
        .post('/adapt-content')
        .send({ platform: 'douyin' })

      expect(response.status).toBe(401)
    }

    const authenticatedResponse = await request(app)
      .post('/adapt-content')
      .set('x-auth', 'ok')
      .send({ platform: 'douyin' })

    expect(authenticatedResponse.status).toBe(200)
  })

  it('limits authenticated batch asset generation requests more aggressively', async () => {
    const { videoRecreationRouter } = await import('./video-recreation.js')
    const app = express()
    app.use(videoRecreationRouter)

    for (let attempt = 0; attempt < 2; attempt += 1) {
      const response = await request(app)
        .post('/generate-all-asset-images')
        .set('x-auth', 'ok')
        .send({ assetType: 'scene' })

      expect(response.status).toBe(200)
    }

    const response = await request(app)
      .post('/generate-all-asset-images')
      .set('x-auth', 'ok')
      .send({ assetType: 'scene' })

    expect(response.status).toBe(429)
    expect(response.body).toEqual({
      success: false,
      error: '批量出图请求过于频繁，请稍后再试。',
    })
  })

  it('tracks authenticated quotas per user instead of per shared ip', async () => {
    const { videoRecreationRouter } = await import('./video-recreation.js')
    const app = express()
    app.use(videoRecreationRouter)

    for (let attempt = 0; attempt < 10; attempt += 1) {
      const response = await request(app)
        .post('/adapt-content')
        .set('x-auth', 'ok')
        .send({ platform: 'douyin' })

      expect(response.status).toBe(200)
    }

    const otherUserResponse = await request(app)
      .post('/adapt-content')
      .set('x-auth', 'other')
      .send({ platform: 'douyin' })

    expect(otherUserResponse.status).toBe(200)
  })
})
