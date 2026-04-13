import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const {
  cleanupBilibiliMediaFileMock,
  cleanupBilibiliMediaFileStrictMock,
} = vi.hoisted(() => ({
  cleanupBilibiliMediaFileMock: vi.fn<(filePath: string) => Promise<void>>(),
  cleanupBilibiliMediaFileStrictMock: vi.fn<(filePath: string) => Promise<void>>(),
}))

vi.mock('./bilibili-media.service.js', () => ({
  cleanupBilibiliMediaFile: cleanupBilibiliMediaFileMock,
  cleanupBilibiliMediaFileStrict: cleanupBilibiliMediaFileStrictMock,
}))

import { env } from '../lib/env.js'
import {
  buildPublicBilibiliAnalysisMediaUrl,
  createBilibiliAnalysisMediaSession,
  deleteBilibiliAnalysisMediaSession,
  getBilibiliAnalysisMediaSession,
} from './bilibili-analysis-media.service.js'

describe('bilibili analysis media sessions', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-04-13T12:00:00.000Z'))
    cleanupBilibiliMediaFileMock.mockReset()
    cleanupBilibiliMediaFileMock.mockResolvedValue(undefined)
    cleanupBilibiliMediaFileStrictMock.mockReset()
    cleanupBilibiliMediaFileStrictMock.mockResolvedValue(undefined)
  })

  afterEach(async () => {
    vi.useRealTimers()
  })

  it('builds a public analysis media URL from the configured backend origin', () => {
    if (!env.PUBLIC_BACKEND_ORIGIN) {
      expect(() => buildPublicBilibiliAnalysisMediaUrl('media id')).toThrow('未配置 PUBLIC_BACKEND_ORIGIN')
      return
    }

    expect(buildPublicBilibiliAnalysisMediaUrl('media id')).toBe(
      `${env.PUBLIC_BACKEND_ORIGIN}/api/bilibili/analysis-media/media%20id`,
    )
  })

  it('creates, reads, and deletes an analysis media session', async () => {
    const session = await createBilibiliAnalysisMediaSession({
      filePath: '/tmp/bilibili-analysis.mp4',
      fileSize: 123,
      filename: 'analysis.mp4',
      mimeType: 'video/mp4',
    })

    await expect(getBilibiliAnalysisMediaSession(session.id)).resolves.toMatchObject({
      id: session.id,
      filePath: '/tmp/bilibili-analysis.mp4',
      fileSize: 123,
      filename: 'analysis.mp4',
      mimeType: 'video/mp4',
    })

    await deleteBilibiliAnalysisMediaSession(session.id)

    expect(cleanupBilibiliMediaFileStrictMock).toHaveBeenCalledWith('/tmp/bilibili-analysis.mp4')
    await expect(getBilibiliAnalysisMediaSession(session.id)).rejects.toThrow('分析视频文件不存在或已过期')
  })

  it('keeps the session when strict deletion cleanup fails', async () => {
    const session = await createBilibiliAnalysisMediaSession({
      filePath: '/tmp/cleanup-failed-analysis.mp4',
      fileSize: 222,
      filename: 'cleanup-failed.mp4',
      mimeType: 'video/mp4',
    })
    cleanupBilibiliMediaFileStrictMock.mockRejectedValueOnce(new Error('rm failed'))

    await expect(deleteBilibiliAnalysisMediaSession(session.id)).rejects.toThrow('rm failed')
    await expect(getBilibiliAnalysisMediaSession(session.id)).resolves.toMatchObject({
      id: session.id,
      filePath: '/tmp/cleanup-failed-analysis.mp4',
    })
  })

  it('does not block unrelated session reads when expired cleanup fails', async () => {
    const expiredSession = await createBilibiliAnalysisMediaSession({
      filePath: '/tmp/expired-cleanup-failed.mp4',
      fileSize: 100,
      filename: 'expired-cleanup-failed.mp4',
      mimeType: 'video/mp4',
    })

    vi.advanceTimersByTime(5 * 60 * 1000)

    const activeSession = await createBilibiliAnalysisMediaSession({
      filePath: '/tmp/active-analysis.mp4',
      fileSize: 200,
      filename: 'active-analysis.mp4',
      mimeType: 'video/mp4',
    })

    cleanupBilibiliMediaFileStrictMock.mockRejectedValueOnce(new Error('rm failed'))
    vi.advanceTimersByTime(10 * 60 * 1000 + 1)

    await expect(getBilibiliAnalysisMediaSession(activeSession.id)).resolves.toMatchObject({
      id: activeSession.id,
      filePath: '/tmp/active-analysis.mp4',
    })
    await expect(getBilibiliAnalysisMediaSession(expiredSession.id)).rejects.toThrow('分析视频文件不存在或已过期')
  })

  it('cleans up expired sessions even without follow-up access', async () => {
    await createBilibiliAnalysisMediaSession({
      filePath: '/tmp/timer-expired-analysis.mp4',
      fileSize: 321,
      filename: 'timer-expired.mp4',
      mimeType: 'video/mp4',
    })

    vi.advanceTimersByTime(15 * 60 * 1000 + 1)
    await vi.runAllTimersAsync()

    expect(cleanupBilibiliMediaFileStrictMock).toHaveBeenCalledWith('/tmp/timer-expired-analysis.mp4')
  })

  it('expires old sessions and cleans up their files', async () => {
    const session = await createBilibiliAnalysisMediaSession({
      filePath: '/tmp/expired-analysis.mp4',
      fileSize: 456,
      filename: 'expired.mp4',
      mimeType: 'video/mp4',
    })

    vi.advanceTimersByTime(15 * 60 * 1000 + 1)

    await expect(getBilibiliAnalysisMediaSession(session.id)).rejects.toThrow('分析视频文件不存在或已过期')
    expect(cleanupBilibiliMediaFileStrictMock).toHaveBeenCalledWith('/tmp/expired-analysis.mp4')
  })
})
