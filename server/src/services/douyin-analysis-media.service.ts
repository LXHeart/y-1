import { randomUUID } from 'node:crypto'
import { env } from '../lib/env.js'
import { AppError } from '../lib/errors.js'
import { logger } from '../lib/logger.js'
import { cleanupDouyinMediaFile, cleanupDouyinMediaFileStrict } from './douyin-media.service.js'

const analysisMediaTtlMs = 15 * 60 * 1000
const analysisMediaCleanupRetryMs = 60 * 1000

export interface DouyinAnalysisMediaSession {
  id: string
  filePath: string
  fileSize: number
  filename: string
  mimeType: string
  expiresAt: number
}

const sessions = new Map<string, DouyinAnalysisMediaSession>()
const cleanupTimers = new Map<string, ReturnType<typeof setTimeout>>()

function clearDouyinAnalysisMediaCleanupTimer(id: string): void {
  const timer = cleanupTimers.get(id)
  if (!timer) {
    return
  }

  clearTimeout(timer)
  cleanupTimers.delete(id)
}

function scheduleDouyinAnalysisMediaCleanup(session: DouyinAnalysisMediaSession, delayMs?: number): void {
  clearDouyinAnalysisMediaCleanupTimer(session.id)

  const timer = setTimeout(() => {
    void cleanupExpiredDouyinAnalysisMediaSessions(Date.now()).catch((error: unknown) => {
      logger.warn({
        sessionId: session.id,
        err: error instanceof Error ? { name: error.name, message: error.message } : { message: 'Unknown cleanup error' },
      }, 'Douyin analysis media cleanup timer failed')
    })
  }, delayMs ?? Math.max(session.expiresAt - Date.now(), 0))

  cleanupTimers.set(session.id, timer)
}

async function deleteDouyinAnalysisMediaSessionInternal(id: string): Promise<void> {
  const session = sessions.get(id)
  if (!session) {
    clearDouyinAnalysisMediaCleanupTimer(id)
    return
  }

  clearDouyinAnalysisMediaCleanupTimer(id)

  try {
    await cleanupDouyinMediaFileStrict(session.filePath)
    sessions.delete(id)
  } catch (error: unknown) {
    scheduleDouyinAnalysisMediaCleanup(session, analysisMediaCleanupRetryMs)
    throw error
  }
}

async function cleanupExpiredDouyinAnalysisMediaSessions(now: number): Promise<void> {
  const expiredSessions = Array.from(sessions.values()).filter((session) => session.expiresAt <= now)

  for (const session of expiredSessions) {
    try {
      await deleteDouyinAnalysisMediaSessionInternal(session.id)
    } catch (error: unknown) {
      logger.warn({
        sessionId: session.id,
        err: error instanceof Error ? { name: error.name, message: error.message } : { message: 'Unknown cleanup error' },
      }, 'Failed to clean up expired Douyin analysis media session')
    }
  }
}

export async function createDouyinAnalysisMediaSession(input: {
  filePath: string
  fileSize: number
  filename: string
  mimeType: string
}): Promise<DouyinAnalysisMediaSession> {
  const now = Date.now()
  await cleanupExpiredDouyinAnalysisMediaSessions(now)

  const session: DouyinAnalysisMediaSession = {
    id: randomUUID(),
    filePath: input.filePath,
    fileSize: input.fileSize,
    filename: input.filename,
    mimeType: input.mimeType,
    expiresAt: now + analysisMediaTtlMs,
  }

  sessions.set(session.id, session)
  scheduleDouyinAnalysisMediaCleanup(session)
  return session
}

export async function getDouyinAnalysisMediaSession(id: string): Promise<DouyinAnalysisMediaSession> {
  const now = Date.now()
  await cleanupExpiredDouyinAnalysisMediaSessions(now)

  const session = sessions.get(id)
  if (!session) {
    throw new AppError('分析视频文件不存在或已过期', 404)
  }

  return session
}

export async function deleteDouyinAnalysisMediaSession(id: string): Promise<void> {
  const now = Date.now()
  await cleanupExpiredDouyinAnalysisMediaSessions(now)
  await deleteDouyinAnalysisMediaSessionInternal(id)
}

export async function deleteDouyinAnalysisMediaSessions(ids: string[]): Promise<void> {
  for (const id of ids) {
    await deleteDouyinAnalysisMediaSession(id)
  }
}

export function buildPublicDouyinAnalysisMediaUrl(id: string): string {
  if (!env.PUBLIC_BACKEND_ORIGIN) {
    throw new AppError('未配置 PUBLIC_BACKEND_ORIGIN，第三方分析服务无法访问分析视频文件地址', 500)
  }

  return `${env.PUBLIC_BACKEND_ORIGIN}/api/douyin/analysis-media/${encodeURIComponent(id)}`
}

export { cleanupDouyinMediaFile }
