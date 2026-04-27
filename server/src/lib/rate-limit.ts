import type express from 'express'

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

function getClientIp(req: express.Request): string {
  return req.ip || req.socket.remoteAddress || 'unknown'
}

function getRateLimitKey(req: express.Request, id: string): string {
  const userId = req.authUser?.id?.trim()
  if (userId) {
    return `${id}:user:${userId}`
  }

  return `${id}:ip:${getClientIp(req)}`
}

function applyRateLimitHeaders(res: express.Response, entry: RateLimitEntry, max: number, now: number): void {
  res.setHeader('RateLimit-Limit', String(max))
  res.setHeader('RateLimit-Remaining', String(Math.max(0, max - entry.count)))
  res.setHeader('RateLimit-Reset', String(Math.ceil((entry.resetAt - now) / 1000)))
}

function cleanupExpiredEntries(entries: Map<string, RateLimitEntry>, now: number): void {
  for (const [key, entry] of entries.entries()) {
    if (entry.resetAt <= now) {
      entries.delete(key)
    }
  }
}

export function createRateLimit(options: RateLimitOptions): express.RequestHandler {
  const entries = new Map<string, RateLimitEntry>()
  const limitedMethods = options.methods ? new Set(options.methods) : null

  return (req, res, next) => {
    if (limitedMethods && !limitedMethods.has(req.method)) {
      next()
      return
    }

    const now = Date.now()
    cleanupExpiredEntries(entries, now)

    const key = getRateLimitKey(req, options.id)
    const current = entries.get(key)

    if (!current) {
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
