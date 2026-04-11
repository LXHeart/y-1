import cors from 'cors'
import express from 'express'
import path from 'path'
import { fileURLToPath } from 'url'
import { pinoHttp } from 'pino-http'
import { ZodError } from 'zod'
import { env } from './lib/env.js'
import { AppError } from './lib/errors.js'
import { logger } from './lib/logger.js'
import { bilibiliRouter } from './routes/bilibili.js'
import { douyinRouter } from './routes/douyin.js'

interface RateLimitEntry {
  count: number
  resetAt: number
}

interface RateLimitOptions {
  id: string
  max: number
  windowMs: number
  message: string
  methods?: string[]
}

function applyRateLimitHeaders(res: express.Response, entry: RateLimitEntry, max: number, now: number): void {
  res.setHeader('RateLimit-Limit', String(max))
  res.setHeader('RateLimit-Remaining', String(Math.max(0, max - entry.count)))
  res.setHeader('RateLimit-Reset', String(Math.ceil((entry.resetAt - now) / 1000)))
}

function createRateLimit(options: RateLimitOptions): express.RequestHandler {
  const entries = new Map<string, RateLimitEntry>()
  const limitedMethods = options.methods ? new Set(options.methods) : null

  return (req, res, next) => {
    if (limitedMethods && !limitedMethods.has(req.method)) {
      next()
      return
    }

    const now = Date.now()
    const key = `${options.id}:${req.ip || req.socket.remoteAddress || 'unknown'}`
    const current = entries.get(key)

    if (!current || current.resetAt <= now) {
      const nextEntry = {
        count: 1,
        resetAt: now + options.windowMs,
      }
      entries.set(key, nextEntry)
      applyRateLimitHeaders(res, nextEntry, options.max, now)
      next()
      return
    }

    if (current.count >= options.max) {
      applyRateLimitHeaders(res, current, options.max, now)
      res.status(429).json({
        success: false,
        error: options.message,
      })
      return
    }

    current.count += 1
    applyRateLimitHeaders(res, current, options.max, now)
    next()
  }
}

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const distDir = path.resolve(__dirname, '../../dist')

export function createApp() {
  const app = express()

  if (env.TRUST_PROXY === '1') {
    app.set('trust proxy', 1)
  }

  app.use(pinoHttp({ logger }))
  app.use(cors({ origin: env.CORS_ORIGIN }))
  app.use(express.json({ limit: '1mb' }))
  app.use('/api/douyin/extract-video', createRateLimit({
    id: 'douyin-extract',
    max: 20,
    windowMs: 60 * 1000,
    methods: ['POST'],
    message: '提取请求过于频繁，请稍后再试。',
  }))
  app.use('/api/douyin/analyze-video', createRateLimit({
    id: 'douyin-analyze',
    max: 20,
    windowMs: 60 * 1000,
    methods: ['POST'],
    message: '视频内容提取请求过于频繁，请稍后再试。',
  }))
  app.use('/api/douyin/proxy', createRateLimit({
    id: 'douyin-proxy',
    max: 120,
    windowMs: 60 * 1000,
    methods: ['GET'],
    message: '视频预览请求过于频繁，请稍后再试。',
  }))
  app.use('/api/douyin/download', createRateLimit({
    id: 'douyin-download',
    max: 30,
    windowMs: 60 * 1000,
    methods: ['GET'],
    message: '视频下载请求过于频繁，请稍后再试。',
  }))
  app.use('/api/douyin/audio', createRateLimit({
    id: 'douyin-audio',
    max: 20,
    windowMs: 60 * 1000,
    methods: ['GET'],
    message: '音频提取请求过于频繁，请稍后再试。',
  }))
  app.use('/api/bilibili/extract-video', createRateLimit({
    id: 'bilibili-extract',
    max: 20,
    windowMs: 60 * 1000,
    methods: ['POST'],
    message: '提取请求过于频繁，请稍后再试。',
  }))
  app.use('/api/bilibili/proxy', createRateLimit({
    id: 'bilibili-proxy',
    max: 120,
    windowMs: 60 * 1000,
    methods: ['GET'],
    message: '视频预览请求过于频繁，请稍后再试。',
  }))
  app.use('/api/bilibili/download', createRateLimit({
    id: 'bilibili-download',
    max: 30,
    windowMs: 60 * 1000,
    methods: ['GET'],
    message: '视频下载请求过于频繁，请稍后再试。',
  }))

  app.get('/health', (_req, res) => {
    res.json({ success: true })
  })

  app.use('/api/bilibili', bilibiliRouter)
  app.use('/api/douyin', douyinRouter)

  if (env.NODE_ENV === 'production') {
    app.use(express.static(distDir))
    app.get(/^(?!\/api\/).*/, (_req, res) => {
      res.sendFile(path.join(distDir, 'index.html'))
    })
  }

  app.use((err: unknown, _req: express.Request, res: express.Response, next: express.NextFunction) => {
    if (res.headersSent) {
      next(err)
      return
    }

    if (err instanceof AppError) {
      res.status(err.statusCode).json({
        success: false,
        error: err.message,
      })
      return
    }

    if (err instanceof ZodError) {
      const firstIssue = err.issues[0]
      res.status(400).json({
        success: false,
        error: firstIssue?.message || '请求参数无效',
      })
      return
    }

    logger.error({ err }, 'Unhandled server error')
    res.status(500).json({
      success: false,
      error: '服务暂时不可用，请稍后重试',
    })
  })

  return app
}
