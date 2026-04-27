import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppError } from '../lib/errors.js'

const {
  analyzeVideoContentMock,
  buildPublicBilibiliAnalysisMediaUrlMock,
  cleanupBilibiliMediaFileMock,
  createBilibiliAnalysisMediaSessionMock,
  createBilibiliMediaClipsMock,
  deleteBilibiliAnalysisMediaSessionMock,
  prepareBilibiliMediaFileMock,
} = vi.hoisted(() => ({
  analyzeVideoContentMock: vi.fn(),
  buildPublicBilibiliAnalysisMediaUrlMock: vi.fn<(id: string) => string>(),
  cleanupBilibiliMediaFileMock: vi.fn<(filePath: string) => Promise<void>>(),
  createBilibiliAnalysisMediaSessionMock: vi.fn<(input: {
    filePath: string
    fileSize: number
    filename: string
    mimeType: string
  }) => Promise<{ id: string }>>(),
  createBilibiliMediaClipsMock: vi.fn(),
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
  createBilibiliMediaClips: createBilibiliMediaClipsMock,
  prepareBilibiliMediaFile: prepareBilibiliMediaFileMock,
}))

import { env } from '../lib/env.js'
import { createBilibiliProxyToken } from './bilibili-proxy.service.js'
import { analyzeBilibiliVideoByProxyUrl } from './bilibili-video-analysis.service.js'

function createClip(input: {
  clipIndex: number
  startSeconds: number
  endSeconds: number
  filePath: string
}) {
  return {
    filePath: input.filePath,
    fileSize: 120 + input.clipIndex,
    filename: `analysis-clip-${input.clipIndex + 1}.mp4`,
    mimeType: 'video/mp4',
    clipIndex: input.clipIndex,
    startSeconds: input.startSeconds,
    endSeconds: input.endSeconds,
    createReadStream: vi.fn(),
  }
}

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
    createBilibiliMediaClipsMock.mockReset()
    createBilibiliMediaClipsMock.mockResolvedValue([])
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

  it('keeps the single upstream call path for progressive videos up to 30 seconds', async () => {
    const token = createBilibiliProxyToken({
      kind: 'progressive',
      playableVideoUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      requestHeaders: {
        Referer: 'https://www.bilibili.com/video/BV1test',
      },
      durationSeconds: 28,
    })

    if (!env.PUBLIC_BACKEND_ORIGIN) {
      await expect(analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`)).rejects.toThrow(
        '未配置 PUBLIC_BACKEND_ORIGIN，当前大模型需要服务端可公网访问的视频代理地址',
      )
      expect(analyzeVideoContentMock).not.toHaveBeenCalled()
      return
    }

    const result = await analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`)

    expect(analyzeVideoContentMock).toHaveBeenCalledWith(
      `${env.PUBLIC_BACKEND_ORIGIN}/api/bilibili/proxy/${encodeURIComponent(token)}`,
      {},
    )
    expect(prepareBilibiliMediaFileMock).not.toHaveBeenCalled()
    expect(createBilibiliMediaClipsMock).not.toHaveBeenCalled()
    expect(result).toEqual({ runId: 'run_bilibili_123' })
  })

  it('passes request-scoped analysis config to direct analysis', async () => {
    const token = createBilibiliProxyToken({
      kind: 'progressive',
      playableVideoUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      durationSeconds: 28,
    })
    const analysisConfig = {
      baseUrl: 'https://custom.example.com/run',
      apiToken: 'request-token',
    }

    if (!env.PUBLIC_BACKEND_ORIGIN) {
      await expect(analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`, {
        analysisConfig,
      })).rejects.toThrow('未配置 PUBLIC_BACKEND_ORIGIN，当前大模型需要服务端可公网访问的视频代理地址')
      expect(analyzeVideoContentMock).not.toHaveBeenCalled()
      return
    }

    await analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`, {
      analysisConfig,
    })

    expect(analyzeVideoContentMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/bilibili/proxy/'),
      { analysisConfig },
    )
  })

  it('keeps the single temporary media session path for dash videos up to 30 seconds', async () => {
    const token = createBilibiliProxyToken({
      kind: 'dash',
      videoTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      audioTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
      durationSeconds: 25,
    })

    const result = await analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`)

    expect(prepareBilibiliMediaFileMock).toHaveBeenCalledWith({
      kind: 'dash',
      videoTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      audioTrackUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/audio.m4s',
      requestHeaders: undefined,
      filename: undefined,
      durationSeconds: 25,
    })
    expect(createBilibiliMediaClipsMock).not.toHaveBeenCalled()
    expect(createBilibiliAnalysisMediaSessionMock).toHaveBeenCalledWith({
      filePath: '/tmp/bilibili-analysis.mp4',
      fileSize: 321,
      filename: 'analysis.mp4',
      mimeType: 'video/mp4',
    })
    expect(buildPublicBilibiliAnalysisMediaUrlMock).toHaveBeenCalledWith('analysis-media-id')
    expect(analyzeVideoContentMock).toHaveBeenCalledWith(
      'https://backend.example.com/api/bilibili/analysis-media/analysis-media-id',
      {},
    )
    expect(result).toEqual({ runId: 'run_bilibili_123' })
  })

  it('splits long videos into 30-second clips and merges analysis with field-specific dedupe', async () => {
    const token = createBilibiliProxyToken({
      kind: 'progressive',
      playableVideoUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      durationSeconds: 65,
    })

    createBilibiliMediaClipsMock.mockResolvedValueOnce([
      createClip({ clipIndex: 0, startSeconds: 0, endSeconds: 30, filePath: '/tmp/clip-1.mp4' }),
      createClip({ clipIndex: 1, startSeconds: 30, endSeconds: 60, filePath: '/tmp/clip-2.mp4' }),
      createClip({ clipIndex: 2, startSeconds: 60, endSeconds: 65, filePath: '/tmp/clip-3.mp4' }),
    ])
    createBilibiliAnalysisMediaSessionMock
      .mockResolvedValueOnce({ id: 'analysis-media-1' })
      .mockResolvedValueOnce({ id: 'analysis-media-2' })
      .mockResolvedValueOnce({ id: 'analysis-media-3' })
    analyzeVideoContentMock
      .mockResolvedValueOnce({
        videoScript: '[00:00] 小明走进房间\n[00:10] 小明拿起红伞',
        videoCaptions: '今天测试分段合并。接下来',
        charactersDescription: '小明：年轻男性；李总：管理者',
        voiceDescription: '人声：男声解说；背景音乐：无；音效：无',
        propsDescription: '红伞；窗户',
        sceneDescription: '现代办公室；光线明亮',
        runId: 'run_1',
      })
      .mockResolvedValueOnce({
        videoScript: '[00:00] 小明拿起红伞\n[00:18] 小明打开窗户',
        videoCaptions: '接下来展示结果。',
        charactersDescription: '小明：年轻男性；李总：资深管理者',
        voiceDescription: '人声：男声解说；背景音乐：无；音效：无',
        propsDescription: '红伞；窗户；电脑',
        sceneDescription: '现代办公室；玻璃隔断',
        runId: 'run_2',
      })
      .mockResolvedValueOnce({
        videoScript: '[00:00] 小明打开窗户',
        videoCaptions: '展示结果。',
        charactersDescription: '小明：年轻男性',
        voiceDescription: '人声：男声解说；背景音乐：无；音效：无',
        propsDescription: '红伞',
        sceneDescription: '现代办公室',
        runId: 'run_3',
      })

    const result = await analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`)

    expect(prepareBilibiliMediaFileMock).toHaveBeenCalledWith({
      kind: 'progressive',
      playableVideoUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      requestHeaders: undefined,
      durationSeconds: 65,
    })
    expect(createBilibiliMediaClipsMock).toHaveBeenCalledWith({
      sourceFilePath: '/tmp/bilibili-analysis.mp4',
      durationSeconds: 65,
      filename: 'analysis.mp4',
      clipDurationSeconds: 30,
    })
    expect(analyzeVideoContentMock).toHaveBeenNthCalledWith(
      1,
      'https://backend.example.com/api/bilibili/analysis-media/analysis-media-1',
      {},
    )
    expect(analyzeVideoContentMock).toHaveBeenNthCalledWith(
      2,
      'https://backend.example.com/api/bilibili/analysis-media/analysis-media-2',
      {},
    )
    expect(analyzeVideoContentMock).toHaveBeenNthCalledWith(
      3,
      'https://backend.example.com/api/bilibili/analysis-media/analysis-media-3',
      {},
    )
    expect(deleteBilibiliAnalysisMediaSessionMock).toHaveBeenCalledWith('analysis-media-1')
    expect(deleteBilibiliAnalysisMediaSessionMock).toHaveBeenCalledWith('analysis-media-2')
    expect(deleteBilibiliAnalysisMediaSessionMock).toHaveBeenCalledWith('analysis-media-3')
    expect(cleanupBilibiliMediaFileMock).toHaveBeenCalledWith('/tmp/bilibili-analysis.mp4')
    expect(cleanupBilibiliMediaFileMock).toHaveBeenCalledWith('/tmp/clip-1.mp4')
    expect(cleanupBilibiliMediaFileMock).toHaveBeenCalledWith('/tmp/clip-2.mp4')
    expect(cleanupBilibiliMediaFileMock).toHaveBeenCalledWith('/tmp/clip-3.mp4')
    expect(result).toEqual({
      segmented: true,
      clipCount: 3,
      runIds: ['run_1', 'run_2', 'run_3'],
      videoScript: [
        '第 1 段（00:00-00:30）',
        '[00:00] 小明走进房间',
        '[00:10] 小明拿起红伞',
        '',
        '第 2 段（00:30-01:00）',
        '[00:18] 小明打开窗户',
      ].join('\n'),
      videoCaptions: ['今天测试分段合并。接下来', '展示结果。'].join('\n'),
      charactersDescription: ['小明：年轻男性', '李总：管理者', '李总：资深管理者'].join('\n'),
      voiceDescription: ['人声：男声解说', '背景音乐：无', '音效：无'].join('\n'),
      propsDescription: ['红伞', '窗户', '电脑'].join('\n'),
      sceneDescription: ['现代办公室', '光线明亮', '玻璃隔断'].join('\n'),
    })
  })

  it('passes request-scoped analysis config to every segmented clip analysis', async () => {
    const token = createBilibiliProxyToken({
      kind: 'progressive',
      playableVideoUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      durationSeconds: 65,
    })
    const analysisConfig = {
      baseUrl: 'https://custom.example.com/run',
      apiToken: 'request-token',
    }

    createBilibiliMediaClipsMock.mockResolvedValueOnce([
      createClip({ clipIndex: 0, startSeconds: 0, endSeconds: 30, filePath: '/tmp/clip-1.mp4' }),
      createClip({ clipIndex: 1, startSeconds: 30, endSeconds: 60, filePath: '/tmp/clip-2.mp4' }),
    ])
    createBilibiliAnalysisMediaSessionMock
      .mockResolvedValueOnce({ id: 'analysis-media-1' })
      .mockResolvedValueOnce({ id: 'analysis-media-2' })

    await analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`, {
      analysisConfig,
    })

    expect(analyzeVideoContentMock).toHaveBeenNthCalledWith(
      1,
      'https://backend.example.com/api/bilibili/analysis-media/analysis-media-1',
      { analysisConfig },
    )
    expect(analyzeVideoContentMock).toHaveBeenNthCalledWith(
      2,
      'https://backend.example.com/api/bilibili/analysis-media/analysis-media-2',
      { analysisConfig },
    )
  })

  it('keeps a short trailing script segment when it adds new content', async () => {
    const token = createBilibiliProxyToken({
      kind: 'progressive',
      playableVideoUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      durationSeconds: 65,
    })

    createBilibiliMediaClipsMock.mockResolvedValueOnce([
      createClip({ clipIndex: 0, startSeconds: 0, endSeconds: 30, filePath: '/tmp/clip-1.mp4' }),
      createClip({ clipIndex: 1, startSeconds: 30, endSeconds: 60, filePath: '/tmp/clip-2.mp4' }),
      createClip({ clipIndex: 2, startSeconds: 60, endSeconds: 65, filePath: '/tmp/clip-3.mp4' }),
    ])
    createBilibiliAnalysisMediaSessionMock
      .mockResolvedValueOnce({ id: 'analysis-media-1' })
      .mockResolvedValueOnce({ id: 'analysis-media-2' })
      .mockResolvedValueOnce({ id: 'analysis-media-3' })
    analyzeVideoContentMock
      .mockResolvedValueOnce({
        videoScript: '[00:00] 主播走进房间',
        runId: 'run_1',
      })
      .mockResolvedValueOnce({
        videoScript: '[00:00] 主播继续说明',
        runId: 'run_2',
      })
      .mockResolvedValueOnce({
        videoScript: '[00:00] 主播展示结论',
        runId: 'run_3',
      })

    const result = await analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`)

    expect(result).toMatchObject({
      segmented: true,
      clipCount: 3,
      runIds: ['run_1', 'run_2', 'run_3'],
      videoScript: [
        '第 1 段（00:00-00:30）',
        '[00:00] 主播走进房间',
        '',
        '第 2 段（00:30-01:00）',
        '[00:00] 主播继续说明',
        '',
        '第 3 段（01:00-01:05）',
        '[00:00] 主播展示结论',
      ].join('\n'),
    })
  })

  it('cleans up generated clip files when one clip analysis fails', async () => {
    const token = createBilibiliProxyToken({
      kind: 'progressive',
      playableVideoUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      durationSeconds: 65,
    })
    const error = new AppError('视频内容提取失败', 502)

    createBilibiliMediaClipsMock.mockResolvedValueOnce([
      createClip({ clipIndex: 0, startSeconds: 0, endSeconds: 30, filePath: '/tmp/clip-1.mp4' }),
      createClip({ clipIndex: 1, startSeconds: 30, endSeconds: 60, filePath: '/tmp/clip-2.mp4' }),
      createClip({ clipIndex: 2, startSeconds: 60, endSeconds: 65, filePath: '/tmp/clip-3.mp4' }),
    ])
    createBilibiliAnalysisMediaSessionMock
      .mockResolvedValueOnce({ id: 'analysis-media-1' })
      .mockResolvedValueOnce({ id: 'analysis-media-2' })
    analyzeVideoContentMock
      .mockResolvedValueOnce({ runId: 'run_1' })
      .mockRejectedValueOnce(error)

    await expect(analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`)).rejects.toThrow(error)

    expect(cleanupBilibiliMediaFileMock).toHaveBeenCalledWith('/tmp/bilibili-analysis.mp4')
    expect(cleanupBilibiliMediaFileMock).toHaveBeenCalledWith('/tmp/clip-1.mp4')
    expect(cleanupBilibiliMediaFileMock).toHaveBeenCalledWith('/tmp/clip-2.mp4')
    expect(cleanupBilibiliMediaFileMock).toHaveBeenCalledWith('/tmp/clip-3.mp4')
  })

  it('rejects videos longer than 10 minutes before analysis starts', async () => {
    const token = createBilibiliProxyToken({
      kind: 'progressive',
      playableVideoUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      durationSeconds: 601,
    })

    await expect(analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`)).rejects.toThrow(
      '当前仅支持分析 10 分钟以内的 B 站视频，建议选择 30 秒到 2 分钟的视频',
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

  it('passes an AbortSignal through to the analyzer', async () => {
    if (!env.PUBLIC_BACKEND_ORIGIN) {
      return
    }

    const token = createBilibiliProxyToken({
      kind: 'progressive',
      playableVideoUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      durationSeconds: 28,
    })
    const signal = new AbortController().signal

    await analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`, { signal })

    expect(analyzeVideoContentMock).toHaveBeenCalledWith(
      `${env.PUBLIC_BACKEND_ORIGIN}/api/bilibili/proxy/${encodeURIComponent(token)}`,
      { signal },
    )
  })

  it('rejects immediately when the caller signal is already aborted', async () => {
    const token = createBilibiliProxyToken({
      kind: 'progressive',
      playableVideoUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      durationSeconds: 125,
    })
    const controller = new AbortController()
    controller.abort()

    await expect(
      analyzeBilibiliVideoByProxyUrl(`/api/bilibili/proxy/${encodeURIComponent(token)}`, { signal: controller.signal }),
    ).rejects.toMatchObject({
      statusCode: 499,
      message: '分析请求已取消',
    } satisfies Partial<AppError>)
    expect(prepareBilibiliMediaFileMock).not.toHaveBeenCalled()
    expect(analyzeVideoContentMock).not.toHaveBeenCalled()
  })

  it('rejects proxy urls from a different origin before calling the analyzer', async () => {
    const token = createBilibiliProxyToken({
      kind: 'progressive',
      playableVideoUrl: 'https://upos-sz-mirrorali.bilivideo.com/upgcxcode/video.m4s',
      durationSeconds: 28,
    })

    await expect(
      analyzeBilibiliVideoByProxyUrl(`https://evil.example/api/bilibili/proxy/${encodeURIComponent(token)}`),
    ).rejects.toThrow('视频代理地址无效')
    expect(analyzeVideoContentMock).not.toHaveBeenCalled()
  })

  it('rejects expired or invalid tokens before calling the analyzer', async () => {
    await expect(analyzeBilibiliVideoByProxyUrl('/api/bilibili/proxy/not-a-valid-token')).rejects.toThrow(AppError)
    expect(analyzeVideoContentMock).not.toHaveBeenCalled()
  })
})
