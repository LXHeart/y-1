import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppError } from '../lib/errors.js'

const { analyzeVideoContentMock } = vi.hoisted(() => ({
  analyzeVideoContentMock: vi.fn<(videoUrl: string) => Promise<{ runId?: string }>>(),
}))

vi.mock('./video-analysis.service.js', () => ({
  analyzeVideoContent: analyzeVideoContentMock,
}))

import { env } from '../lib/env.js'
import { createBilibiliProxyToken } from './bilibili-proxy.service.js'
import { analyzeBilibiliVideoByProxyUrl } from './bilibili-video-analysis.service.js'

describe('analyzeBilibiliVideoByProxyUrl', () => {
  beforeEach(() => {
    analyzeVideoContentMock.mockReset()
    analyzeVideoContentMock.mockResolvedValue({ runId: 'run_bilibili_123' })
  })

  it('sends the configured public Bilibili proxy URL to the analyzer when available', async () => {
    const token = createBilibiliProxyToken({
      kind: 'progressive',
      playableVideoUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      requestHeaders: {
        Referer: 'https://www.bilibili.com/video/BV1test',
      },
    })

    if (!env.PUBLIC_BACKEND_ORIGIN) {
      await expect(analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`)).rejects.toThrow(
        '未配置 PUBLIC_BACKEND_ORIGIN',
      )
      expect(analyzeVideoContentMock).not.toHaveBeenCalled()
      return
    }

    const result = await analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`)

    expect(analyzeVideoContentMock).toHaveBeenCalledWith(
      `${env.PUBLIC_BACKEND_ORIGIN}/api/bilibili/proxy/${encodeURIComponent(token)}`,
    )
    expect(result).toEqual({ runId: 'run_bilibili_123' })
  })

  it('rejects invalid tokens before calling the analyzer', async () => {
    await expect(analyzeBilibiliVideoByProxyUrl('/api/bilibili/proxy/not-a-valid-token')).rejects.toThrow(AppError)
    expect(analyzeVideoContentMock).not.toHaveBeenCalled()
  })
})
