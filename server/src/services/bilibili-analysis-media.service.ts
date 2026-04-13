import { randomUUID } from 'node:crypto'
import { env } from '../lib/env.js'
import { AppError } from '../lib/errors.js'
import { logger } from '../lib/logger.js'
import { cleanupBilibiliMediaFile, cleanupBilibiliMediaFileStrict } from './bilibili-media.service.js'

const analysisMediaTtlMs = 15 * 60 * 1000
const analysisMediaCleanupRetryMs = 60 * 1000

export interface BilibiliAnalysisMediaSession {
  id: string
  filePath: string
  fileSize: number
  filename: string
  mimeType: string
  expiresAt: number
}

const sessions = new Map<string, BilibiliAnalysisMediaSession>()
const cleanupTimers = new Map<string, ReturnType<typeof setTimeout>>()

function clearBilibiliAnalysisMediaCleanupTimer(id: string): void {
  const timer = cleanupTimers.get(id)
  if (!timer) {
    return
  }

  clearTimeout(timer)
  cleanupTimers.delete(id)
}

function scheduleBilibiliAnalysisMediaCleanup(session: BilibiliAnalysisMediaSession, delayMs?: number): void {
  clearBilibiliAnalysisMediaCleanupTimer(session.id)

  const timer = setTimeout(() => {
    void cleanupExpiredBilibiliAnalysisMediaSessions(Date.now()).catch((error: unknown) => {
      logger.warn({
        sessionId: session.id,
        err: error instanceof Error ? { name: error.name, message: error.message } : { message: 'Unknown cleanup error' },
      }, 'Bilibili analysis media cleanup timer failed')
    })
  }, delayMs ?? Math.max(session.expiresAt - Date.now(), 0))

  cleanupTimers.set(session.id, timer)
}

async function deleteBilibiliAnalysisMediaSessionInternal(id: string): Promise<void> {
  const session = sessions.get(id)
  if (!session) {
    clearBilibiliAnalysisMediaCleanupTimer(id)
    return
  }

  clearBilibiliAnalysisMediaCleanupTimer(id)

  try {
    await cleanupBilibiliMediaFileStrict(session.filePath)
    sessions.delete(id)
  } catch (error: unknown) {
    scheduleBilibiliAnalysisMediaCleanup(session, analysisMediaCleanupRetryMs)
    throw error
  }
}

async function cleanupExpiredBilibiliAnalysisMediaSessions(now: number): Promise<void> {
  const expiredSessions = Array.from(sessions.values()).filter((session) => session.expiresAt <= now)

  for (const session of expiredSessions) {
    try {
      await deleteBilibiliAnalysisMediaSessionInternal(session.id)
    } catch (error: unknown) {
      logger.warn({
        sessionId: session.id,
        err: error instanceof Error ? { name: error.name, message: error.message } : { message: 'Unknown cleanup error' },
      }, 'Failed to clean up expired Bilibili analysis media session')
    }
  }
}

export async function createBilibiliAnalysisMediaSession(input: {
  filePath: string
  fileSize: number
  filename: string
  mimeType: string
}): Promise<BilibiliAnalysisMediaSession> {
  const now = Date.now()
  await cleanupExpiredBilibiliAnalysisMediaSessions(now)

  const session: BilibiliAnalysisMediaSession = {
    id: randomUUID(),
    filePath: input.filePath,
    fileSize: input.fileSize,
    filename: input.filename,
    mimeType: input.mimeType,
    expiresAt: now + analysisMediaTtlMs,
  }

  sessions.set(session.id, session)
  scheduleBilibiliAnalysisMediaCleanup(session)
  return session
}

export async function getBilibiliAnalysisMediaSession(id: string): Promise<BilibiliAnalysisMediaSession> {
  const now = Date.now()
  await cleanupExpiredBilibiliAnalysisMediaSessions(now)

  const session = sessions.get(id)
  if (!session) {
    throw new AppError('分析视频文件不存在或已过期', 404)
  }

  return session
}

export async function deleteBilibiliAnalysisMediaSession(id: string): Promise<void> {
  const now = Date.now()
  await cleanupExpiredBilibiliAnalysisMediaSessions(now)
  await deleteBilibiliAnalysisMediaSessionInternal(id)
}

export function buildPublicBilibiliAnalysisMediaUrl(id: string): string {
  if (!env.PUBLIC_BACKEND_ORIGIN) {
    throw new AppError('未配置 PUBLIC_BACKEND_ORIGIN，第三方分析服务无法访问分析视频文件地址', 500)
  }

  return `${env.PUBLIC_BACKEND_ORIGIN}/api/bilibili/analysis-media/${encodeURIComponent(id)}`
}
