import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppError } from '../lib/errors.js'

const {
  analyzeVideoContentMock,
  buildPublicBilibiliAnalysisMediaUrlMock,
  cleanupBilibiliMediaFileMock,
  createBilibiliAnalysisMediaSessionMock,
  deleteBilibiliAnalysisMediaSessionMock,
  prepareBilibiliMediaFileMock,
} = vi.hoisted(() => ({
  analyzeVideoContentMock: vi.fn<(videoUrl: string) => Promise<{ runId?: string }>>(),
  buildPublicBilibiliAnalysisMediaUrlMock: vi.fn<(id: string) => string>(),
  cleanupBilibiliMediaFileMock: vi.fn<(filePath: string) => Promise<void>>(),
  createBilibiliAnalysisMediaSessionMock: vi.fn<(input: {
    filePath: string
    fileSize: number
    filename: string
    mimeType: string
  }) => Promise<{ id: string }>>(),
  deleteBilibiliAnalysisMediaSessionMock: vi.fn<(id: string) => Promise<void>>(),
  prepareBilibiliMediaFileMock: vi.fn(),
}))

vi.mock('./video-analysis.service.js', () => ({
  analyzeVideoContent: analyzeVideoContentMock,
}))

vi.mock('./bilibili-analysis-media.service.js', () => ({
  buildPublicBilibiliAnalysisMediaUrl: buildPublicBilibiliAnalysisMediaUrlMock,
  createBilibiliAnalysisMediaSession: createBilibiliAnalysisMediaSessionMock,
  deleteBilibiliAnalysisMediaSession: deleteBilibiliAnalysisMediaSessionMock,
}))

vi.mock('./bilibili-media.service.js', () => ({
  cleanupBilibiliMediaFile: cleanupBilibiliMediaFileMock,
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
    cleanupBilibiliMediaFileMock.mockReset()
    cleanupBilibiliMediaFileMock.mockResolvedValue(undefined)
    createBilibiliAnalysisMediaSessionMock.mockReset()
    createBilibiliAnalysisMediaSessionMock.mockResolvedValue({ id: 'analysis-media-id' })
    deleteBilibiliAnalysisMediaSessionMock.mockReset()
    deleteBilibiliAnalysisMediaSessionMock.mockResolvedValue(undefined)
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
      durationSeconds: 118,
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
      durationSeconds: 125,
    })

    const result = await analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`)

    expect(prepareBilibiliMediaFileMock).toHaveBeenCalledWith({
      kind: 'dash',
      videoTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      audioTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
      requestHeaders: undefined,
      filename: undefined,
      durationSeconds: 125,
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

  it('rejects videos longer than 5 minutes before analysis starts', async () => {
    const token = createBilibiliProxyToken({
      kind: 'dash',
      videoTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      audioTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
      durationSeconds: 301,
    })

    await expect(analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`)).rejects.toThrow(
      '当前仅支持分析 5 分钟以内的 B 站视频，建议选择 30 秒到 2 分钟的视频',
    )
    expect(analyzeVideoContentMock).not.toHaveBeenCalled()
    expect(prepareBilibiliMediaFileMock).not.toHaveBeenCalled()
    expect(createBilibiliAnalysisMediaSessionMock).not.toHaveBeenCalled()
  })

  it('rejects videos when duration metadata is missing', async () => {
    const token = createBilibiliProxyToken({
      kind: 'progressive',
      playableVideoUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
    })

    await expect(analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`)).rejects.toThrow(
      '未能识别视频时长，请重新提取后再分析',
    )
    expect(analyzeVideoContentMock).not.toHaveBeenCalled()
  })

  it('fails before preparing DASH media when the public analysis URL cannot be built', async () => {
    const token = createBilibiliProxyToken({
      kind: 'dash',
      videoTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      audioTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
      durationSeconds: 125,
    })

    buildPublicBilibiliAnalysisMediaUrlMock.mockImplementationOnce(() => {
      throw new AppError('未配置 PUBLIC_BACKEND_ORIGIN，第三方分析服务无法访问分析视频文件地址', 500)
    })

    await expect(analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`)).rejects.toThrow(
      '未配置 PUBLIC_BACKEND_ORIGIN，第三方分析服务无法访问分析视频文件地址',
    )
    expect(prepareBilibiliMediaFileMock).not.toHaveBeenCalled()
    expect(createBilibiliAnalysisMediaSessionMock).not.toHaveBeenCalled()
    expect(analyzeVideoContentMock).not.toHaveBeenCalled()
  })

  it('cleans up prepared DASH media when session creation fails', async () => {
    const token = createBilibiliProxyToken({
      kind: 'dash',
      videoTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      audioTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
      durationSeconds: 125,
    })
    const error = new AppError('创建分析会话失败', 502)
    createBilibiliAnalysisMediaSessionMock.mockRejectedValueOnce(error)

    await expect(analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`)).rejects.toThrow(error)

    expect(cleanupBilibiliMediaFileMock).toHaveBeenCalledWith('/tmp/bilibili-analysis.mp4')
    expect(deleteBilibiliAnalysisMediaSessionMock).not.toHaveBeenCalled()
    expect(analyzeVideoContentMock).not.toHaveBeenCalled()
  })

  it('deletes the analysis session when building the DASH analysis media URL fails after session creation', async () => {
    const token = createBilibiliProxyToken({
      kind: 'dash',
      videoTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      audioTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
      durationSeconds: 125,
    })
    const error = new AppError('分析视频地址生成失败', 500)
    buildPublicBilibiliAnalysisMediaUrlMock.mockImplementationOnce(() => 'https://backend.example.com/api/bilibili/analysis-media/bilibili-analysis-media-preflight')
    buildPublicBilibiliAnalysisMediaUrlMock.mockImplementationOnce(() => {
      throw error
    })

    await expect(analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`)).rejects.toThrow(error)

    expect(deleteBilibiliAnalysisMediaSessionMock).toHaveBeenCalledWith('analysis-media-id')
    expect(cleanupBilibiliMediaFileMock).not.toHaveBeenCalled()
    expect(analyzeVideoContentMock).not.toHaveBeenCalled()
  })

  it('deletes the analysis session when DASH analysis fails after session creation', async () => {
    const token = createBilibiliProxyToken({
      kind: 'dash',
      videoTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      audioTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
      durationSeconds: 125,
    })
    const error = new AppError('视频内容提取失败', 502)
    analyzeVideoContentMock.mockRejectedValueOnce(error)

    await expect(analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`)).rejects.toThrow(error)

    expect(deleteBilibiliAnalysisMediaSessionMock).toHaveBeenCalledWith('analysis-media-id')
    expect(cleanupBilibiliMediaFileMock).not.toHaveBeenCalled()
  })

  it('rejects invalid tokens before calling the analyzer', async () => {
    await expect(analyzeBilibiliVideoByProxyUrl('/api/bilibili/proxy/not-a-valid-token')).rejects.toThrow(AppError)
    expect(analyzeVideoContentMock).not.toHaveBeenCalled()
  })
})
