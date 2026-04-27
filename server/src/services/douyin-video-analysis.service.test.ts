import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppError } from '../lib/errors.js'

const {
  analyzeVideoContentMock,
  buildPublicDouyinAnalysisMediaUrlMock,
  cleanupDouyinMediaFileMock,
  createDouyinAnalysisMediaSessionMock,
  createDouyinMediaClipsMock,
  deleteDouyinAnalysisMediaSessionMock,
  prepareDouyinMediaFileMock,
} = vi.hoisted(() => ({
  analyzeVideoContentMock: vi.fn(),
  buildPublicDouyinAnalysisMediaUrlMock: vi.fn<(id: string) => string>(),
  cleanupDouyinMediaFileMock: vi.fn<(filePath: string) => Promise<void>>(),
  createDouyinAnalysisMediaSessionMock: vi.fn<(input: {
    filePath: string
    fileSize: number
    filename: string
    mimeType: string
  }) => Promise<{ id: string }>>(),
  createDouyinMediaClipsMock: vi.fn(),
  deleteDouyinAnalysisMediaSessionMock: vi.fn<(id: string) => Promise<void>>(),
  prepareDouyinMediaFileMock: vi.fn(),
}))

vi.mock('./video-analysis.service.js', () => ({
  analyzeVideoContent: analyzeVideoContentMock,
}))

vi.mock('./douyin-analysis-media.service.js', () => ({
  buildPublicDouyinAnalysisMediaUrl: buildPublicDouyinAnalysisMediaUrlMock,
  createDouyinAnalysisMediaSession: createDouyinAnalysisMediaSessionMock,
  deleteDouyinAnalysisMediaSession: deleteDouyinAnalysisMediaSessionMock,
}))

vi.mock('./douyin-media.service.js', () => ({
  cleanupDouyinMediaFile: cleanupDouyinMediaFileMock,
  createDouyinMediaClips: createDouyinMediaClipsMock,
  prepareDouyinMediaFile: prepareDouyinMediaFileMock,
}))

import { env } from '../lib/env.js'
import { createDouyinProxyToken } from './douyin-proxy.service.js'
import { analyzeDouyinVideoByProxyUrl } from './douyin-video-analysis.service.js'

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

describe('analyzeDouyinVideoByProxyUrl', () => {
  beforeEach(() => {
    analyzeVideoContentMock.mockReset()
    analyzeVideoContentMock.mockResolvedValue({ runId: 'run_douyin_123' })
    buildPublicDouyinAnalysisMediaUrlMock.mockReset()
    buildPublicDouyinAnalysisMediaUrlMock.mockImplementation((id) => `https://backend.example.com/api/douyin/analysis-media/${id}`)
    cleanupDouyinMediaFileMock.mockReset()
    cleanupDouyinMediaFileMock.mockResolvedValue(undefined)
    createDouyinAnalysisMediaSessionMock.mockReset()
    createDouyinAnalysisMediaSessionMock.mockResolvedValue({ id: 'analysis-media-id' })
    createDouyinMediaClipsMock.mockReset()
    createDouyinMediaClipsMock.mockResolvedValue([])
    deleteDouyinAnalysisMediaSessionMock.mockReset()
    deleteDouyinAnalysisMediaSessionMock.mockResolvedValue(undefined)
    prepareDouyinMediaFileMock.mockReset()
    prepareDouyinMediaFileMock.mockResolvedValue({
      filePath: '/tmp/douyin-analysis.mp4',
      fileSize: 321,
      filename: 'analysis.mp4',
      mimeType: 'video/mp4',
    })
  })

  it('keeps the single upstream call path for videos up to 30 seconds', async () => {
    const token = createDouyinProxyToken({
      playableVideoUrl: 'https://aweme.snssdk.com/aweme/v1/play/?video_id=test',
      requestHeaders: {
        Referer: 'https://www.douyin.com/video/123',
      },
      durationSeconds: 28,
    })

    if (!env.PUBLIC_BACKEND_ORIGIN) {
      await expect(analyzeDouyinVideoByProxyUrl(`/api/douyin/proxy/${encodeURIComponent(token)}`)).rejects.toThrow(
        '未配置 PUBLIC_BACKEND_ORIGIN，当前大模型需要服务端可公网访问的视频代理地址',
      )
      expect(analyzeVideoContentMock).not.toHaveBeenCalled()
      return
    }

    const result = await analyzeDouyinVideoByProxyUrl(`/api/douyin/proxy/${encodeURIComponent(token)}`)

    expect(analyzeVideoContentMock).toHaveBeenCalledWith(
      `${env.PUBLIC_BACKEND_ORIGIN}/api/douyin/proxy/${encodeURIComponent(token)}`,
      {},
    )
    expect(prepareDouyinMediaFileMock).not.toHaveBeenCalled()
    expect(createDouyinMediaClipsMock).not.toHaveBeenCalled()
    expect(result).toEqual({ runId: 'run_douyin_123' })
  })

  it('splits long videos into 30-second clips and merges analysis with field-specific dedupe', async () => {
    const token = createDouyinProxyToken({
      playableVideoUrl: 'https://aweme.snssdk.com/aweme/v1/play/?video_id=test',
      requestHeaders: {
        Referer: 'https://www.douyin.com/video/123',
      },
      filename: 'douyin-analysis.mp4',
      durationSeconds: 65,
    })

    createDouyinMediaClipsMock.mockResolvedValueOnce([
      createClip({ clipIndex: 0, startSeconds: 0, endSeconds: 30, filePath: '/tmp/clip-1.mp4' }),
      createClip({ clipIndex: 1, startSeconds: 30, endSeconds: 60, filePath: '/tmp/clip-2.mp4' }),
      createClip({ clipIndex: 2, startSeconds: 60, endSeconds: 65, filePath: '/tmp/clip-3.mp4' }),
    ])
    createDouyinAnalysisMediaSessionMock
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

    const result = await analyzeDouyinVideoByProxyUrl(`/api/douyin/proxy/${encodeURIComponent(token)}`)

    expect(prepareDouyinMediaFileMock).toHaveBeenCalledWith({
      playableVideoUrl: 'https://aweme.snssdk.com/aweme/v1/play/?video_id=test',
      requestHeaders: {
        Referer: 'https://www.douyin.com/video/123',
      },
      filename: 'douyin-analysis.mp4',
      durationSeconds: 65,
    })
    expect(createDouyinMediaClipsMock).toHaveBeenCalledWith({
      sourceFilePath: '/tmp/douyin-analysis.mp4',
      durationSeconds: 65,
      filename: 'analysis.mp4',
      clipDurationSeconds: 30,
    })
    expect(analyzeVideoContentMock).toHaveBeenNthCalledWith(
      1,
      'https://backend.example.com/api/douyin/analysis-media/analysis-media-1',
      {},
    )
    expect(analyzeVideoContentMock).toHaveBeenNthCalledWith(
      2,
      'https://backend.example.com/api/douyin/analysis-media/analysis-media-2',
      {},
    )
    expect(analyzeVideoContentMock).toHaveBeenNthCalledWith(
      3,
      'https://backend.example.com/api/douyin/analysis-media/analysis-media-3',
      {},
    )
    expect(deleteDouyinAnalysisMediaSessionMock).toHaveBeenCalledWith('analysis-media-1')
    expect(deleteDouyinAnalysisMediaSessionMock).toHaveBeenCalledWith('analysis-media-2')
    expect(deleteDouyinAnalysisMediaSessionMock).toHaveBeenCalledWith('analysis-media-3')
    expect(cleanupDouyinMediaFileMock).toHaveBeenCalledWith('/tmp/douyin-analysis.mp4')
    expect(cleanupDouyinMediaFileMock).toHaveBeenCalledWith('/tmp/clip-1.mp4')
    expect(cleanupDouyinMediaFileMock).toHaveBeenCalledWith('/tmp/clip-2.mp4')
    expect(cleanupDouyinMediaFileMock).toHaveBeenCalledWith('/tmp/clip-3.mp4')
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
    const token = createDouyinProxyToken({
      playableVideoUrl: 'https://aweme.snssdk.com/aweme/v1/play/?video_id=test',
      durationSeconds: 65,
    })
    const analysisConfig = {
      baseUrl: 'https://custom.example.com/run',
      apiToken: 'request-token',
    }

    createDouyinMediaClipsMock.mockResolvedValueOnce([
      createClip({ clipIndex: 0, startSeconds: 0, endSeconds: 30, filePath: '/tmp/clip-1.mp4' }),
      createClip({ clipIndex: 1, startSeconds: 30, endSeconds: 60, filePath: '/tmp/clip-2.mp4' }),
    ])
    createDouyinAnalysisMediaSessionMock
      .mockResolvedValueOnce({ id: 'analysis-media-1' })
      .mockResolvedValueOnce({ id: 'analysis-media-2' })

    await analyzeDouyinVideoByProxyUrl(`/api/douyin/proxy/${encodeURIComponent(token)}`, {
      analysisConfig,
    })

    expect(analyzeVideoContentMock).toHaveBeenNthCalledWith(
      1,
      'https://backend.example.com/api/douyin/analysis-media/analysis-media-1',
      { analysisConfig },
    )
    expect(analyzeVideoContentMock).toHaveBeenNthCalledWith(
      2,
      'https://backend.example.com/api/douyin/analysis-media/analysis-media-2',
      { analysisConfig },
    )
  })

  it('cleans up generated clip files when one clip analysis fails', async () => {
    const token = createDouyinProxyToken({
      playableVideoUrl: 'https://aweme.snssdk.com/aweme/v1/play/?video_id=test',
      durationSeconds: 65,
    })
    const error = new AppError('视频内容提取失败', 502)

    createDouyinMediaClipsMock.mockResolvedValueOnce([
      createClip({ clipIndex: 0, startSeconds: 0, endSeconds: 30, filePath: '/tmp/clip-1.mp4' }),
      createClip({ clipIndex: 1, startSeconds: 30, endSeconds: 60, filePath: '/tmp/clip-2.mp4' }),
      createClip({ clipIndex: 2, startSeconds: 60, endSeconds: 65, filePath: '/tmp/clip-3.mp4' }),
    ])
    createDouyinAnalysisMediaSessionMock
      .mockResolvedValueOnce({ id: 'analysis-media-1' })
      .mockResolvedValueOnce({ id: 'analysis-media-2' })
    analyzeVideoContentMock
      .mockResolvedValueOnce({ runId: 'run_1' })
      .mockRejectedValueOnce(error)

    await expect(analyzeDouyinVideoByProxyUrl(`/api/douyin/proxy/${encodeURIComponent(token)}`)).rejects.toThrow(error)

    expect(cleanupDouyinMediaFileMock).toHaveBeenCalledWith('/tmp/douyin-analysis.mp4')
    expect(cleanupDouyinMediaFileMock).toHaveBeenCalledWith('/tmp/clip-1.mp4')
    expect(cleanupDouyinMediaFileMock).toHaveBeenCalledWith('/tmp/clip-2.mp4')
    expect(cleanupDouyinMediaFileMock).toHaveBeenCalledWith('/tmp/clip-3.mp4')
  })

  it('rejects videos longer than 10 minutes before analysis starts', async () => {
    const token = createDouyinProxyToken({
      playableVideoUrl: 'https://aweme.snssdk.com/aweme/v1/play/?video_id=test',
      durationSeconds: 601,
    })

    await expect(analyzeDouyinVideoByProxyUrl(`/api/douyin/proxy/${encodeURIComponent(token)}`)).rejects.toThrow(
      '当前仅支持分析 10 分钟以内的抖音视频，建议选择 30 秒到 2 分钟的视频',
    )
    expect(analyzeVideoContentMock).not.toHaveBeenCalled()
    expect(prepareDouyinMediaFileMock).not.toHaveBeenCalled()
    expect(createDouyinAnalysisMediaSessionMock).not.toHaveBeenCalled()
  })

  it('rejects videos when duration metadata is missing', async () => {
    const token = createDouyinProxyToken({
      playableVideoUrl: 'https://aweme.snssdk.com/aweme/v1/play/?video_id=test',
    })

    await expect(analyzeDouyinVideoByProxyUrl(`/api/douyin/proxy/${encodeURIComponent(token)}`)).rejects.toThrow(
      '未能识别视频时长，请重新提取后再分析',
    )
    expect(analyzeVideoContentMock).not.toHaveBeenCalled()
  })

  it('passes an AbortSignal through to the analyzer', async () => {
    if (!env.PUBLIC_BACKEND_ORIGIN) {
      return
    }

    const token = createDouyinProxyToken({
      playableVideoUrl: 'https://aweme.snssdk.com/aweme/v1/play/?video_id=test',
      durationSeconds: 28,
    })
    const signal = new AbortController().signal

    await analyzeDouyinVideoByProxyUrl(`/api/douyin/proxy/${encodeURIComponent(token)}`, { signal })

    expect(analyzeVideoContentMock).toHaveBeenCalledWith(
      `${env.PUBLIC_BACKEND_ORIGIN}/api/douyin/proxy/${encodeURIComponent(token)}`,
      { signal },
    )
  })

  it('rejects immediately when the caller signal is already aborted', async () => {
    const token = createDouyinProxyToken({
      playableVideoUrl: 'https://aweme.snssdk.com/aweme/v1/play/?video_id=test',
      durationSeconds: 125,
    })
    const controller = new AbortController()
    controller.abort()

    await expect(
      analyzeDouyinVideoByProxyUrl(`/api/douyin/proxy/${encodeURIComponent(token)}`, { signal: controller.signal }),
    ).rejects.toMatchObject({
      statusCode: 499,
      message: '分析请求已取消',
    } satisfies Partial<AppError>)
    expect(prepareDouyinMediaFileMock).not.toHaveBeenCalled()
    expect(analyzeVideoContentMock).not.toHaveBeenCalled()
  })

  it('rejects proxy urls from a different origin before calling the analyzer', async () => {
    const token = createDouyinProxyToken({
      playableVideoUrl: 'https://aweme.snssdk.com/aweme/v1/play/?video_id=test',
      durationSeconds: 28,
    })

    await expect(
      analyzeDouyinVideoByProxyUrl(`https://evil.example/api/douyin/proxy/${encodeURIComponent(token)}`),
    ).rejects.toThrow('视频代理地址无效')
    expect(analyzeVideoContentMock).not.toHaveBeenCalled()
  })

  it('rejects expired or invalid tokens before calling the analyzer', async () => {
    await expect(analyzeDouyinVideoByProxyUrl('/api/douyin/proxy/not-a-valid-token')).rejects.toThrow(AppError)
    expect(analyzeVideoContentMock).not.toHaveBeenCalled()
  })
})
