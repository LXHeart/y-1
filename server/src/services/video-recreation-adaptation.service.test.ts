import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppError } from '../lib/errors.js'

const {
  getProviderMock,
  loadSettingsMock,
  loadSettingsForUserMock,
  resolveProviderConfigMock,
  loggerInfoMock,
} = vi.hoisted(() => ({
  getProviderMock: vi.fn(),
  loadSettingsMock: vi.fn(),
  loadSettingsForUserMock: vi.fn(),
  resolveProviderConfigMock: vi.fn(),
  loggerInfoMock: vi.fn(),
}))

vi.mock('./providers/index.js', () => ({
  getProvider: getProviderMock,
}))

vi.mock('./analysis-settings.service.js', () => ({
  loadSettings: loadSettingsMock,
  loadSettingsForUser: loadSettingsForUserMock,
}))

vi.mock('./video-analysis.service.js', async () => {
  const actual = await vi.importActual<typeof import('./video-analysis.service.js')>('./video-analysis.service.js')
  return {
    ...actual,
    resolveProviderConfig: resolveProviderConfigMock,
  }
})

vi.mock('../lib/logger.js', () => ({
  logger: {
    info: loggerInfoMock,
    warn: vi.fn(),
    error: vi.fn(),
  },
}))

describe('adaptVideoContent', () => {
  beforeEach(() => {
    vi.resetModules()
    process.env.VIDEO_ANALYSIS_API_TIMEOUT_MS = '180000'
    loggerInfoMock.mockReset()
    loadSettingsMock.mockReset()
    loadSettingsForUserMock.mockReset()
    getProviderMock.mockReset()
    resolveProviderConfigMock.mockReset()

    loadSettingsMock.mockReturnValue({
      features: {
        video: { provider: 'qwen', baseUrl: 'https://video.example.com/v1', apiKey: 'video-key', model: 'qwen-plus' },
        image: {},
        article: {},
        imageGeneration: {},
      },
    })

    loadSettingsForUserMock.mockResolvedValue({
      features: {
        video: { provider: 'qwen', baseUrl: 'https://video.example.com/v1', apiKey: 'video-key', model: 'qwen-plus' },
        image: {},
        article: {},
        imageGeneration: {},
      },
    })

    resolveProviderConfigMock.mockResolvedValue({
      baseUrl: 'https://video.example.com/v1',
      apiKey: 'video-key',
      model: 'qwen-plus',
    })
  })

  it('dispatches adaptation to the active provider', async () => {
    const adaptVideoContentMock = vi.fn(async () => ({
      adaptedSummary: '改编摘要',
      characterSheets: [],
      sceneCards: [],
      propCards: [],
      runId: 'adapt-run-1',
    }))

    getProviderMock.mockReturnValue({
      id: 'qwen',
      label: 'Qwen',
      supportsModelListing: true,
      analyze: vi.fn(),
      adaptVideoContent: adaptVideoContentMock,
    })

    const { adaptVideoContent } = await import('./video-recreation-adaptation.service.js')
    const controller = new AbortController()

    const result = await adaptVideoContent({
      platform: 'douyin',
      proxyVideoUrl: '/api/douyin/proxy/token-1',
      extractedContent: {
        videoCaptions: '字幕 1',
        sceneDescription: '场景 1',
      },
      userId: 'user-1',
      signal: controller.signal,
    })

    expect(resolveProviderConfigMock).toHaveBeenCalledWith('qwen', undefined, expect.objectContaining({
      provider: 'qwen',
    }))
    expect(adaptVideoContentMock).toHaveBeenCalledWith({
      platform: 'douyin',
      proxyVideoUrl: '/api/douyin/proxy/token-1',
      extractedContent: {
        videoCaptions: '字幕 1',
        sceneDescription: '场景 1',
      },
    }, {
      baseUrl: 'https://video.example.com/v1',
      apiKey: 'video-key',
      model: 'qwen-plus',
    }, {
      signal: controller.signal,
      timeoutMs: 180000,
    })
    expect(result).toEqual(expect.objectContaining({
      adaptedSummary: '改编摘要',
      runId: 'adapt-run-1',
    }))
  })

  it('rejects providers that do not support adaptation', async () => {
    getProviderMock.mockReturnValue({
      id: 'coze',
      label: 'Coze',
      supportsModelListing: false,
      analyze: vi.fn(),
    })

    const { adaptVideoContent } = await import('./video-recreation-adaptation.service.js')

    await expect(adaptVideoContent({
      platform: 'bilibili',
      proxyVideoUrl: '/api/bilibili/proxy/token-1',
      extractedContent: {
        videoScript: '脚本 1',
      },
    })).rejects.toMatchObject({
      statusCode: 400,
      message: '当前视频分析服务不支持内容改编，请切换到 Qwen 后重试',
    } satisfies Partial<AppError>)
  })
})
