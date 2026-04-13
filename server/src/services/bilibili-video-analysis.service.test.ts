import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppError } from '../lib/errors.js'

const {
  analyzeVideoContentMock,
  buildPublicBilibiliAnalysisMediaUrlMock,
  createBilibiliAnalysisMediaSessionMock,
  prepareBilibiliMediaFileMock,
} = vi.hoisted(() => ({
  analyzeVideoContentMock: vi.fn<(videoUrl: string) => Promise<{ runId?: string }>>(),
  buildPublicBilibiliAnalysisMediaUrlMock: vi.fn<(id: string) => string>(),
  createBilibiliAnalysisMediaSessionMock: vi.fn<(input: {
    filePath: string
    fileSize: number
    filename: string
    mimeType: string
  }) => Promise<{ id: string }>>(),
  prepareBilibiliMediaFileMock: vi.fn(),
}))

vi.mock('./video-analysis.service.js', () => ({
  analyzeVideoContent: analyzeVideoContentMock,
}))

vi.mock('./bilibili-analysis-media.service.js', () => ({
  buildPublicBilibiliAnalysisMediaUrl: buildPublicBilibiliAnalysisMediaUrlMock,
  createBilibiliAnalysisMediaSession: createBilibiliAnalysisMediaSessionMock,
}))

vi.mock('./bilibili-media.service.js', () => ({
  prepareBilibiliMediaFile: prepareBilibiliMediaFileMock,
}))

import { env } from '../lib/env.js'
import { createBilibiliProxyToken } from './bilibili-proxy.service.js'
import { analyzeBilibiliVideoByProxyUrl } from './bilibili-video-analysis.service.js'

describe('analyzeBilibiliVideoByProxyUrl', () => {
  beforeEach(() => {
    analyzeVideoContentMock.mockReset()
    analyzeVideoContentMock.mockResolvedValue({ runId: 'run_bilibili_123' })
    buildPublicBilibiliAnalysisMediaUrlMock.mockReset()
    buildPublicBilibiliAnalysisMediaUrlMock.mockImplementation((id) => `https://backend.example.com/api/bilibili/analysis-media/${id}`)
    createBilibiliAnalysisMediaSessionMock.mockReset()
    createBilibiliAnalysisMediaSessionMock.mockResolvedValue({ id: 'analysis-media-id' })
    prepareBilibiliMediaFileMock.mockReset()
    prepareBilibiliMediaFileMock.mockResolvedValue({
      filePath: '/tmp/bilibili-analysis.mp4',
      fileSize: 321,
      filename: 'analysis.mp4',
      mimeType: 'video/mp4',
    })
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
    expect(prepareBilibiliMediaFileMock).not.toHaveBeenCalled()
    expect(result).toEqual({ runId: 'run_bilibili_123' })
  })

  it('prepares DASH media and sends a temporary analysis URL to the analyzer', async () => {
    const token = createBilibiliProxyToken({
      kind: 'dash',
      videoTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      audioTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
    })

    const result = await analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`)

    expect(prepareBilibiliMediaFileMock).toHaveBeenCalledWith({
      kind: 'dash',
      videoTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      audioTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
      requestHeaders: undefined,
      filename: undefined,
    })
    expect(createBilibiliAnalysisMediaSessionMock).toHaveBeenCalledWith({
      filePath: '/tmp/bilibili-analysis.mp4',
      fileSize: 321,
      filename: 'analysis.mp4',
      mimeType: 'video/mp4',
    })
    expect(buildPublicBilibiliAnalysisMediaUrlMock).toHaveBeenCalledWith('analysis-media-id')
    expect(analyzeVideoContentMock).toHaveBeenCalledWith('https://backend.example.com/api/bilibili/analysis-media/analysis-media-id')
    expect(result).toEqual({ runId: 'run_bilibili_123' })
  })

  it('does not delete prepared DASH media immediately when analysis fails', async () => {
    const token = createBilibiliProxyToken({
      kind: 'dash',
      videoTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      audioTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
    })
    const error = new AppError('视频内容提取失败', 502)
    analyzeVideoContentMock.mockRejectedValueOnce(error)

    await expect(analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`)).rejects.toThrow(error)
  })

  it('rejects invalid tokens before calling the analyzer', async () => {
    await expect(analyzeBilibiliVideoByProxyUrl('/api/bilibili/proxy/not-a-valid-token')).rejects.toThrow(AppError)
    expect(analyzeVideoContentMock).not.toHaveBeenCalled()
  })
})
