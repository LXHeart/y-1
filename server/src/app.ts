import cors from 'cors'
import express from 'express'
import multer from 'multer'
import path from 'path'
import { fileURLToPath } from 'url'
import { pinoHttp } from 'pino-http'
import { ZodError } from 'zod'
import { env } from './lib/env.js'
import { AppError } from './lib/errors.js'
import { logger } from './lib/logger.js'
import { bilibiliRouter } from './routes/bilibili.js'
import { douyinRouter } from './routes/douyin.js'
import { imageAnalysisRouter } from './routes/image-analysis.js'
import { articleGenerationRouter } from './routes/article-generation.js'
import { videoRecreationRouter } from './routes/video-recreation.js'
import { comedyGenerationRouter } from './routes/comedy-generation.js'
import { settingsRouter } from './routes/settings.js'
import { homepageRouter } from './routes/homepage.js'
import { authRouter } from './routes/auth.js'
import { createSessionMiddleware } from './lib/session.js'
import { attachAuthenticatedUser } from './lib/auth.js'
import { createRateLimit } from './lib/rate-limit.js'

interface RateLimitEntry {
  count: number
  resetAt: number
}

interface RateLimitConstraint {
  max: number
  entry?: RateLimitEntry
}

const LOGIN_RATE_LIMIT_WINDOW_MS = 60 * 1000
const LOGIN_RATE_LIMIT_MAX_PER_IP = 10
const LOGIN_RATE_LIMIT_MAX_PER_ACCOUNT_IP = 5
const LOGIN_RATE_LIMIT_MESSAGE = '登录请求过于频繁，请稍后再试。'
const REGISTER_RATE_LIMIT_WINDOW_MS = 60 * 1000
const REGISTER_RATE_LIMIT_MAX_PER_IP = 5
const REGISTER_RATE_LIMIT_MESSAGE = '注册请求过于频繁，请稍后再试。'

function getClientIp(req: express.Request): string {
  return req.ip || req.socket.remoteAddress || 'unknown'
}

function cleanupExpiredEntries(entries: Map<string, RateLimitEntry>, now: number): void {
  for (const [key, entry] of entries.entries()) {
    if (entry.resetAt <= now) {
      entries.delete(key)
    }
  }
}

function getOrCreateRateLimitEntry(
  entries: Map<string, RateLimitEntry>,
  key: string,
  now: number,
  windowMs: number,
): RateLimitEntry {
  const current = entries.get(key)
  if (current && current.resetAt > now) {
    return current
  }

  const nextEntry = {
    count: 0,
    resetAt: now + windowMs,
  }
  entries.set(key, nextEntry)
  return nextEntry
}

function normalizeLoginIdentifier(value: unknown): string | undefined {
  if (typeof value !== 'string') {
    return undefined
  }

  const normalizedValue = value.trim().toLowerCase()
  return normalizedValue || undefined
}

function selectRateLimitConstraint(constraints: RateLimitConstraint[]): RateLimitConstraint {
  return constraints.reduce((selected, current) => {
    const selectedRemaining = Math.max(0, selected.max - (selected.entry?.count ?? 0))
    const currentRemaining = Math.max(0, current.max - (current.entry?.count ?? 0))

    if (currentRemaining < selectedRemaining) {
      return current
    }

    if (currentRemaining === selectedRemaining && current.max < selected.max) {
      return current
    }

    return selected
  })
}

function applyRateLimitHeaders(res: express.Response, entry: RateLimitEntry, max: number, now: number): void {
  res.setHeader('RateLimit-Limit', String(max))
  res.setHeader('RateLimit-Remaining', String(Math.max(0, max - entry.count)))
  res.setHeader('RateLimit-Reset', String(Math.ceil((entry.resetAt - now) / 1000)))
}

function applyConstraintHeaders(res: express.Response, constraints: RateLimitConstraint[], now: number, windowMs: number): void {
  const selected = selectRateLimitConstraint(constraints)
  applyRateLimitHeaders(res, selected.entry ?? {
    count: 0,
    resetAt: now + windowMs,
  }, selected.max, now)
}

function decrementRateLimitEntry(entries: Map<string, RateLimitEntry>, key: string | undefined): void {
  if (!key) {
    return
  }

  const entry = entries.get(key)
  if (!entry) {
    return
  }

  entry.count = Math.max(0, entry.count - 1)
  if (entry.count === 0) {
    entries.delete(key)
  }
}

export function createFailedLoginRateLimit(): express.RequestHandler {
  const ipEntries = new Map<string, RateLimitEntry>()
  const accountEntries = new Map<string, RateLimitEntry>()

  return (req, res, next) => {
    if (req.method !== 'POST') {
      next()
      return
    }

    const now = Date.now()
    cleanupExpiredEntries(ipEntries, now)
    cleanupExpiredEntries(accountEntries, now)

    const ipKey = getClientIp(req)
    const identifier = normalizeLoginIdentifier(req.body?.email)
    const accountKey = identifier ? `${ipKey}:${identifier}` : undefined
    const ipEntry = getOrCreateRateLimitEntry(ipEntries, ipKey, now, LOGIN_RATE_LIMIT_WINDOW_MS)
    const accountEntry = accountKey
      ? getOrCreateRateLimitEntry(accountEntries, accountKey, now, LOGIN_RATE_LIMIT_WINDOW_MS)
      : undefined

    if (ipEntry.count >= LOGIN_RATE_LIMIT_MAX_PER_IP || (accountEntry && accountEntry.count >= LOGIN_RATE_LIMIT_MAX_PER_ACCOUNT_IP)) {
      applyConstraintHeaders(res, [
        { max: LOGIN_RATE_LIMIT_MAX_PER_IP, entry: ipEntry },
        ...(accountEntry ? [{ max: LOGIN_RATE_LIMIT_MAX_PER_ACCOUNT_IP, entry: accountEntry }] : []),
      ], now, LOGIN_RATE_LIMIT_WINDOW_MS)
      res.status(429).json({
        success: false,
        error: LOGIN_RATE_LIMIT_MESSAGE,
      })
      return
    }

    ipEntry.count += 1
    if (accountEntry) {
      accountEntry.count += 1
    }

    applyConstraintHeaders(res, [
      { max: LOGIN_RATE_LIMIT_MAX_PER_IP, entry: ipEntry },
      ...(accountEntry ? [{ max: LOGIN_RATE_LIMIT_MAX_PER_ACCOUNT_IP, entry: accountEntry }] : []),
    ], now, LOGIN_RATE_LIMIT_WINDOW_MS)

    let reservationHandled = false
    let connectionClosed = false
    let shouldFinalizeAfterOutcome = false

    const finalizeReservation = (shouldKeepReservation: boolean) => {
      if (reservationHandled) {
        return
      }

      reservationHandled = true
      req.notifyLoginAttemptOutcomeSet = undefined
      const completedAt = Date.now()

      if (!shouldKeepReservation) {
        decrementRateLimitEntry(ipEntries, ipKey)
        decrementRateLimitEntry(accountEntries, accountKey)
      }

      cleanupExpiredEntries(ipEntries, completedAt)
      cleanupExpiredEntries(accountEntries, completedAt)
    }

    const shouldKeepReservation = () => {
      if (req.loginAttemptOutcome === 'auth_failure') {
        return true
      }

      if (req.loginAttemptOutcome === 'success' || req.loginAttemptOutcome === 'other_failure') {
        return false
      }

      return undefined
    }

    req.notifyLoginAttemptOutcomeSet = () => {
      if (!shouldFinalizeAfterOutcome) {
        return
      }

      const nextDecision = shouldKeepReservation()
      if (nextDecision !== undefined) {
        finalizeReservation(nextDecision)
      }
    }

    res.once('finish', () => {
      const nextDecision = shouldKeepReservation()
      finalizeReservation(nextDecision ?? false)
    })
    res.once('close', () => {
      connectionClosed = true
      const nextDecision = shouldKeepReservation()

      if (nextDecision !== undefined) {
        finalizeReservation(nextDecision)
        return
      }

      if (connectionClosed) {
        shouldFinalizeAfterOutcome = true
      }
    })

    next()
  }
}

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const distDir = path.resolve(__dirname, '../../dist')

export function createApp() {
  const app = express()
  const allowedOrigins = env.CORS_ORIGIN.split(',').map((origin) => origin.trim()).filter(Boolean)

  if (env.NODE_ENV === 'development') {
    allowedOrigins.push('http://localhost:5173', 'http://localhost:5174', 'http://127.0.0.1:5173', 'http://127.0.0.1:5174')
  }

  if (env.TRUST_PROXY === '1') {
    app.set('trust proxy', 1)
  }

  app.use(pinoHttp({ logger }))
  app.use(cors({
    origin(origin, callback) {
      if (!origin || allowedOrigins.includes(origin)) {
        callback(null, true)
        return
      }

      callback(new Error(`Not allowed by CORS: ${origin}`))
    },
    credentials: true,
  }))
  app.use(express.json({ limit: '1mb' }))
  app.use(createSessionMiddleware())
  app.use(attachAuthenticatedUser)
  app.use('/api/douyin/hot-items', createRateLimit({
    id: 'douyin-hot-items',
    max: 30,
    windowMs: 60 * 1000,
    methods: ['GET'],
    message: '抖音热点请求过于频繁，请稍后再试。',
  }))
  app.use('/api/homepage/hot-items', createRateLimit({
    id: 'homepage-hot-items',
    max: 30,
    windowMs: 60 * 1000,
    methods: ['GET'],
    message: '全网热点请求过于频繁，请稍后再试。',
  }))
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
  app.use('/api/douyin/analysis-media', createRateLimit({
    id: 'douyin-analysis-media',
    max: 300,
    windowMs: 60 * 1000,
    methods: ['GET'],
    message: '分析视频读取请求过于频繁，请稍后再试。',
  }))
  app.use('/api/bilibili/extract-video', createRateLimit({
    id: 'bilibili-extract',
    max: 20,
    windowMs: 60 * 1000,
    methods: ['POST'],
    message: '提取请求过于频繁，请稍后再试。',
  }))
  app.use('/api/bilibili/analyze-video', createRateLimit({
    id: 'bilibili-analyze',
    max: 20,
    windowMs: 60 * 1000,
    methods: ['POST'],
    message: '视频内容提取请求过于频繁，请稍后再试。',
  }))
  app.use('/api/bilibili/analysis-media', createRateLimit({
    id: 'bilibili-analysis-media',
    max: 300,
    windowMs: 60 * 1000,
    methods: ['GET'],
    message: '分析视频读取请求过于频繁，请稍后再试。',
  }))
  app.use('/api/settings', createRateLimit({
    id: 'settings',
    max: 30,
    windowMs: 60 * 1000,
    message: '设置请求过于频繁，请稍后再试。',
  }))
  app.use('/api/auth/login', createFailedLoginRateLimit())
  app.use('/api/auth/register', createRateLimit({
    id: 'auth-register',
    max: REGISTER_RATE_LIMIT_MAX_PER_IP,
    windowMs: REGISTER_RATE_LIMIT_WINDOW_MS,
    methods: ['POST'],
    message: REGISTER_RATE_LIMIT_MESSAGE,
  }))
  app.use('/api/image-analysis/analyze', createRateLimit({
    id: 'image-analysis',
    max: 20,
    windowMs: 60 * 1000,
    methods: ['POST'],
    message: '图片内容提取请求过于频繁，请稍后再试。',
  }))
  app.use('/api/article-generation', createRateLimit({
    id: 'article-generation',
    max: 10,
    windowMs: 60 * 1000,
    methods: ['POST'],
    message: '文章创作请求过于频繁，请稍后再试。',
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

  app.use('/api/auth', authRouter)
  app.use('/api/bilibili', bilibiliRouter)
  app.use('/api/douyin', douyinRouter)
  app.use('/api/image-analysis', imageAnalysisRouter)
  app.use('/api/article-generation', articleGenerationRouter)
  app.use('/api/video-recreation', videoRecreationRouter)
  app.use('/api/comedy-generation', comedyGenerationRouter)
  app.use('/api/settings', settingsRouter)
  app.use('/api/homepage', homepageRouter)

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

    if (err instanceof multer.MulterError) {
      const errorMessage = err.code === 'LIMIT_FILE_SIZE'
        ? '单张图片不能超过 5 MB'
        : err.code === 'LIMIT_FILE_COUNT'
          ? '最多上传 6 张图片'
          : err.code === 'LIMIT_UNEXPECTED_FILE'
            ? '仅支持上传 images 字段的图片文件'
            : '图片上传失败，请检查文件后重试'

      res.status(400).json({
        success: false,
        error: errorMessage,
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
