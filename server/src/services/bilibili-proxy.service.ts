import { Buffer } from 'node:buffer'
import { createHmac, timingSafeEqual } from 'node:crypto'
import { normalizeBilibiliDownloadFilename } from '../lib/bilibili-filename.js'
import { env } from '../lib/env.js'
import { AppError } from '../lib/errors.js'
import type { BilibiliMediaTarget } from './bilibili.types.js'

const payloadVersion = 1
const proxyTokenTtlMs = 15 * 60 * 1000
const allowedProxyRequestHeaderNames = new Set(['referer', 'user-agent', 'origin'])

interface BilibiliProgressiveProxyPayload {
  v: number
  exp: number
  kind: 'progressive'
  playableVideoUrl: string
  requestHeaders?: Record<string, string>
  filename?: string
  durationSeconds?: number
}

interface BilibiliDashProxyPayload {
  v: number
  exp: number
  kind: 'dash'
  videoTrackUrl: string
  audioTrackUrl: string
  requestHeaders?: Record<string, string>
  filename?: string
  durationSeconds?: number
}

type BilibiliProxyPayload = BilibiliProgressiveProxyPayload | BilibiliDashProxyPayload

function signPayload(encodedPayload: string): string {
  return createHmac('sha256', env.BILIBILI_PROXY_TOKEN_SECRET)
    .update(encodedPayload)
    .digest('base64url')
}

function encodePayload(payload: BilibiliProxyPayload): string {
  return Buffer.from(JSON.stringify(payload), 'utf8').toString('base64url')
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

function readDurationSeconds(value: unknown): number | undefined {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) {
    return undefined
  }

  return Math.ceil(value)
}

function decodePayload(encodedPayload: string): BilibiliProxyPayload {
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
  const requestHeaders = sanitizeProxyRequestHeaders(payload.requestHeaders)

  if (payload.v !== payloadVersion || typeof payload.exp !== 'number' || typeof payload.kind !== 'string') {
    throw new AppError('视频代理凭证无效', 400)
  }

  if (payload.kind === 'progressive' && typeof payload.playableVideoUrl === 'string') {
    return {
      v: payload.v,
      exp: payload.exp,
      kind: 'progressive',
      playableVideoUrl: payload.playableVideoUrl,
      requestHeaders,
      filename: typeof payload.filename === 'string' ? payload.filename : undefined,
      durationSeconds: readDurationSeconds(payload.durationSeconds),
    }
  }

  if (
    payload.kind === 'dash'
    && typeof payload.videoTrackUrl === 'string'
    && typeof payload.audioTrackUrl === 'string'
  ) {
    return {
      v: payload.v,
      exp: payload.exp,
      kind: 'dash',
      videoTrackUrl: payload.videoTrackUrl,
      audioTrackUrl: payload.audioTrackUrl,
      requestHeaders,
      filename: typeof payload.filename === 'string' ? payload.filename : undefined,
      durationSeconds: readDurationSeconds(payload.durationSeconds),
    }
  }

  throw new AppError('视频代理凭证无效', 400)
}

export function createBilibiliProxyToken(input: BilibiliMediaTarget): string {
  const sharedPayload = {
    v: payloadVersion,
    exp: Date.now() + proxyTokenTtlMs,
    requestHeaders: sanitizeProxyRequestHeaders(input.requestHeaders),
    filename: normalizeBilibiliDownloadFilename(input.filename),
    durationSeconds: readDurationSeconds(input.durationSeconds),
  }

  const payload: BilibiliProxyPayload = input.kind === 'progressive'
    ? {
        ...sharedPayload,
        kind: 'progressive',
        playableVideoUrl: input.playableVideoUrl,
      }
    : {
        ...sharedPayload,
        kind: 'dash',
        videoTrackUrl: input.videoTrackUrl,
        audioTrackUrl: input.audioTrackUrl,
      }

  const encodedPayload = encodePayload(payload)
  const signature = signPayload(encodedPayload)

  return `${encodedPayload}.${signature}`
}

export function buildPublicBilibiliProxyUrl(token: string): string {
  if (!env.PUBLIC_BACKEND_ORIGIN) {
    throw new AppError('未配置 PUBLIC_BACKEND_ORIGIN，当前大模型需要服务端可公网访问的视频代理地址', 500)
  }

  return `${env.PUBLIC_BACKEND_ORIGIN}/api/bilibili/proxy/${encodeURIComponent(token)}`
}

export function parseBilibiliProxyToken(token: string): BilibiliMediaTarget {
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

  if (payload.kind === 'progressive') {
    const parsedUrl = new URL(payload.playableVideoUrl)
    if (parsedUrl.protocol !== 'https:') {
      throw new AppError('视频代理凭证无效', 400)
    }

    return {
      kind: 'progressive',
      playableVideoUrl: payload.playableVideoUrl,
      requestHeaders: payload.requestHeaders,
      filename: payload.filename,
      durationSeconds: payload.durationSeconds,
    }
  }

  const videoTrackUrl = new URL(payload.videoTrackUrl)
  const audioTrackUrl = new URL(payload.audioTrackUrl)
  if (videoTrackUrl.protocol !== 'https:' || audioTrackUrl.protocol !== 'https:') {
    throw new AppError('视频代理凭证无效', 400)
  }

  return {
    kind: 'dash',
    videoTrackUrl: payload.videoTrackUrl,
    audioTrackUrl: payload.audioTrackUrl,
    requestHeaders: payload.requestHeaders,
    filename: payload.filename,
    durationSeconds: payload.durationSeconds,
  }
}
