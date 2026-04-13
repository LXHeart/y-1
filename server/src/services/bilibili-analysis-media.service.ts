import { randomUUID } from 'node:crypto'
import { env } from '../lib/env.js'
import { AppError } from '../lib/errors.js'
import { cleanupBilibiliMediaFile } from './bilibili-media.service.js'

const analysisMediaTtlMs = 15 * 60 * 1000

export interface BilibiliAnalysisMediaSession {
  id: string
  filePath: string
  fileSize: number
  filename: string
  mimeType: string
  expiresAt: number
}

const sessions = new Map<string, BilibiliAnalysisMediaSession>()

async function cleanupExpiredBilibiliAnalysisMediaSessions(now: number): Promise<void> {
  const expiredSessions = Array.from(sessions.values()).filter((session) => session.expiresAt <= now)

  for (const session of expiredSessions) {
    sessions.delete(session.id)
    await cleanupBilibiliMediaFile(session.filePath)
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
  sessions.delete(id)
}

export function buildPublicBilibiliAnalysisMediaUrl(id: string): string {
  if (!env.PUBLIC_BACKEND_ORIGIN) {
    throw new AppError('未配置 PUBLIC_BACKEND_ORIGIN，第三方分析服务无法访问分析视频文件地址', 500)
  }

  return `${env.PUBLIC_BACKEND_ORIGIN}/api/bilibili/analysis-media/${encodeURIComponent(id)}`
}
