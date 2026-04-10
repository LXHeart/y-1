import { Buffer } from 'node:buffer'
import { createHmac, timingSafeEqual } from 'node:crypto'
import { env } from '../lib/env.js'
import { AppError } from '../lib/errors.js'

const payloadVersion = 1
const proxyTokenTtlMs = 15 * 60 * 1000
const defaultDownloadFilename = 'douyin-video.mp4'
const invalidFilenameCharsPattern = /[<>:"/\\|?*\x00-\x1F]+/g
const whitespacePattern = /\s+/g
const repeatedDashPattern = /-+/g
const allowedProxyRequestHeaderNames = new Set(['referer', 'user-agent'])

interface DouyinProxyPayload {
  v: number
  exp: number
  playableVideoUrl: string
  requestHeaders?: Record<string, string>
  filename?: string
}

function signPayload(encodedPayload: string): string {
  return createHmac('sha256', env.DOUYIN_PROXY_TOKEN_SECRET)
    .update(encodedPayload)
    .digest('base64url')
}

function encodePayload(payload: DouyinProxyPayload): string {
  return Buffer.from(JSON.stringify(payload), 'utf8').toString('base64url')
}

function sanitizeFilenamePart(value: string | undefined): string | undefined {
  if (!value) {
    return undefined
  }

  const normalized = value
    .replace(invalidFilenameCharsPattern, '-')
    .replace(whitespacePattern, '-')
    .replace(repeatedDashPattern, '-')
    .replace(/^-|-$/g, '')
    .trim()

  return normalized || undefined
}

function ensureMp4Extension(filename: string): string {
  return filename.toLowerCase().endsWith('.mp4') ? filename : `${filename}.mp4`
}

function truncateFilenameBase(base: string, maxLength = 80): string {
  if (base.length <= maxLength) {
    return base
  }

  return base.slice(0, maxLength).replace(/-+$/g, '').trim() || 'douyin-video'
}

export function buildDownloadFilename(input: {
  title?: string
  author?: string
  videoId?: string
}): string {
  const parts = [input.title, input.author, input.videoId]
    .map(sanitizeFilenamePart)
    .filter((value): value is string => Boolean(value))

  if (parts.length === 0) {
    return defaultDownloadFilename
  }

  return ensureMp4Extension(truncateFilenameBase(parts.join('-')))
}

function sanitizeProxyRequestHeaders(input: unknown): Record<string, string> | undefined {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) {
    return undefined
  }

  const sanitizedEntries = Object.entries(input)
    .filter((entry): entry is [string, string] => {
      return typeof entry[1] === 'string' && allowedProxyRequestHeaderNames.has(entry[0].toLowerCase())
    })

  if (sanitizedEntries.length === 0) {
    return undefined
  }

  return Object.fromEntries(sanitizedEntries)
}

function decodePayload(encodedPayload: string): DouyinProxyPayload {
  let parsed: unknown

  try {
    parsed = JSON.parse(Buffer.from(encodedPayload, 'base64url').toString('utf8')) as unknown
  } catch {
    throw new AppError('视频代理凭证无效', 400)
  }

  if (typeof parsed !== 'object' || parsed === null) {
    throw new AppError('视频代理凭证无效', 400)
  }

  const payload = parsed as Record<string, unknown>

  if (payload.v !== payloadVersion || typeof payload.exp !== 'number' || typeof payload.playableVideoUrl !== 'string') {
    throw new AppError('视频代理凭证无效', 400)
  }

  const requestHeaders = sanitizeProxyRequestHeaders(payload.requestHeaders)

  return {
    v: payload.v,
    exp: payload.exp,
    playableVideoUrl: payload.playableVideoUrl,
    requestHeaders,
    filename: typeof payload.filename === 'string' ? payload.filename : undefined,
  }
}

export function createDouyinProxyToken(input: {
  playableVideoUrl: string
  requestHeaders?: Record<string, string>
  filename?: string
}): string {
  const payload: DouyinProxyPayload = {
    v: payloadVersion,
    exp: Date.now() + proxyTokenTtlMs,
    playableVideoUrl: input.playableVideoUrl,
    requestHeaders: sanitizeProxyRequestHeaders(input.requestHeaders),
    filename: sanitizeFilenamePart(input.filename) ? ensureMp4Extension(sanitizeFilenamePart(input.filename) as string) : undefined,
  }

  const encodedPayload = encodePayload(payload)
  const signature = signPayload(encodedPayload)

  return `${encodedPayload}.${signature}`
}

export function parseDouyinProxyToken(token: string): {
  playableVideoUrl: string
  requestHeaders?: Record<string, string>
  filename?: string
} {
  const [encodedPayload, signature] = token.split('.')

  if (!encodedPayload || !signature) {
    throw new AppError('视频代理凭证无效', 400)
  }

  const expectedSignature = signPayload(encodedPayload)
  const expectedBuffer = Buffer.from(expectedSignature, 'utf8')
  const actualBuffer = Buffer.from(signature, 'utf8')

  if (expectedBuffer.length !== actualBuffer.length || !timingSafeEqual(expectedBuffer, actualBuffer)) {
    throw new AppError('视频代理凭证无效', 403)
  }

  const payload = decodePayload(encodedPayload)

  if (payload.exp < Date.now()) {
    throw new AppError('视频代理凭证已过期', 410)
  }

  const parsedUrl = new URL(payload.playableVideoUrl)
  if (parsedUrl.protocol !== 'https:') {
    throw new AppError('视频代理凭证无效', 400)
  }

  return {
    playableVideoUrl: payload.playableVideoUrl,
    requestHeaders: payload.requestHeaders,
    filename: payload.filename,
  }
}
