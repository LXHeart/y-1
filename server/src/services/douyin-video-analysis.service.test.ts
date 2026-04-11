import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppError } from '../lib/errors.js'

const { analyzeVideoContentMock } = vi.hoisted(() => ({
  analyzeVideoContentMock: vi.fn<(videoUrl: string) => Promise<{ runId?: string }>>(),
}))

vi.mock('./video-analysis.service.js', () => ({
  analyzeVideoContent: analyzeVideoContentMock,
}))

import { env } from '../lib/env.js'
import { createDouyinProxyToken } from './douyin-proxy.service.js'
import { analyzeDouyinVideoByProxyUrl } from './douyin-video-analysis.service.js'

describe('analyzeDouyinVideoByProxyUrl', () => {
  beforeEach(() => {
    analyzeVideoContentMock.mockReset()
    analyzeVideoContentMock.mockResolvedValue({ runId: 'run_123' })
  })

  it('sends the configured public proxy URL to the analyzer when available', async () => {
    const token = createDouyinProxyToken({
      playableVideoUrl: 'https://aweme.snssdk.com/aweme/v1/play/?video_id=test',
      requestHeaders: {
        Referer: 'https://www.douyin.com/video/123',
      },
    })

    if (!env.PUBLIC_BACKEND_ORIGIN) {
      await expect(analyzeDouyinVideoByProxyUrl(`/api/douyin/proxy/${encodeURIComponent(token)}`)).rejects.toThrow(
        '未配置 PUBLIC_BACKEND_ORIGIN',
      )
      expect(analyzeVideoContentMock).not.toHaveBeenCalled()
      return
    }

    const result = await analyzeDouyinVideoByProxyUrl(`/api/douyin/proxy/${encodeURIComponent(token)}`)

    expect(analyzeVideoContentMock).toHaveBeenCalledWith(
      `${env.PUBLIC_BACKEND_ORIGIN}/api/douyin/proxy/${encodeURIComponent(token)}`,
    )
    expect(result).toEqual({ runId: 'run_123' })
  })

  it('rejects expired or invalid tokens before calling the analyzer', async () => {
    await expect(analyzeDouyinVideoByProxyUrl('/api/douyin/proxy/not-a-valid-token')).rejects.toThrow(AppError)
    expect(analyzeVideoContentMock).not.toHaveBeenCalled()
  })
})
