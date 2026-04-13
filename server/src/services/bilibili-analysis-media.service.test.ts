import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const { cleanupBilibiliMediaFileMock } = vi.hoisted(() => ({
  cleanupBilibiliMediaFileMock: vi.fn<(filePath: string) => Promise<void>>(),
}))

vi.mock('./bilibili-media.service.js', () => ({
  cleanupBilibiliMediaFile: cleanupBilibiliMediaFileMock,
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

    await expect(getBilibiliAnalysisMediaSession(session.id)).rejects.toThrow('分析视频文件不存在或已过期')
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
    expect(cleanupBilibiliMediaFileMock).toHaveBeenCalledWith('/tmp/expired-analysis.mp4')
  })
})
