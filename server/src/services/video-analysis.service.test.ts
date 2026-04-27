import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppError } from '../lib/errors.js'

const {
  loggerInfoMock,
  loggerWarnMock,
  loggerErrorMock,
  loadSettingsMock,
  loadSettingsForUserMock,
  lookupMock,
} = vi.hoisted(() => ({
  loggerInfoMock: vi.fn(),
  loggerWarnMock: vi.fn(),
  loggerErrorMock: vi.fn(),
  loadSettingsMock: vi.fn((): any => ({
    features: {
      video: { provider: 'coze' },
      image: {},
      article: {},
    },
  })),
  loadSettingsForUserMock: vi.fn(async (): Promise<any> => ({
    features: {
      video: { provider: 'coze' },
      image: {},
      article: {},
    },
  })),
  lookupMock: vi.fn(),
}))

vi.mock('../lib/logger.js', () => ({
  logger: {
    info: loggerInfoMock,
    warn: loggerWarnMock,
    error: loggerErrorMock,
  },
}))

vi.mock('./analysis-settings.service.js', () => ({
  loadSettings: loadSettingsMock,
  loadSettingsForUser: loadSettingsForUserMock,
  saveSettings: vi.fn(),
  maskSettingsSecrets: vi.fn(),
}))

vi.mock('node:dns/promises', () => ({
  lookup: lookupMock,
}))

describe('analyzeVideoContent', () => {
  const originalFetch = globalThis.fetch

  beforeEach(() => {
    vi.resetModules()
    process.env.VIDEO_ANALYSIS_API_BASE_URL = 'https://analysis.example.com/run'
    process.env.VIDEO_ANALYSIS_API_TIMEOUT_MS = '180000'
    process.env.VIDEO_ANALYSIS_API_TOKEN = ''
    process.env.COZE_ANALYSIS_BASE_URL = ''
    process.env.COZE_ANALYSIS_API_TOKEN = ''
    process.env.QWEN_ANALYSIS_BASE_URL = ''
    process.env.QWEN_ANALYSIS_API_KEY = ''
    process.env.QWEN_ANALYSIS_MODEL = ''
    loadSettingsMock.mockReset()
    loadSettingsForUserMock.mockReset()
    lookupMock.mockReset()
    lookupMock.mockResolvedValue([{ address: '93.184.216.34', family: 4 }])
    loadSettingsMock.mockReturnValue({
      features: {
        video: { provider: 'coze' },
        image: {},
        article: {},
        imageGeneration: {},
      },
    })
    loadSettingsForUserMock.mockResolvedValue({
      features: {
        video: { provider: 'coze' },
        image: {},
        article: {},
        imageGeneration: {},
      },
    })
    loggerInfoMock.mockReset()
    loggerWarnMock.mockReset()
    loggerErrorMock.mockReset()
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
  })

  it('returns normalized analysis data from the upstream response', async () => {
    globalThis.fetch = vi.fn(async () => new Response(JSON.stringify({
      video_captions: 'captions',
      run_id: 'run_123',
    }), {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
      },
    }))

    const { analyzeVideoContent } = await import('./video-analysis.service.js')
    const result = await analyzeVideoContent('https://backend.example.com/api/bilibili/proxy/token')

    expect(result).toEqual({
      videoCaptions: 'captions',
      videoScript: undefined,
      charactersDescription: undefined,
      voiceDescription: undefined,
      propsDescription: undefined,
      sceneDescription: undefined,
      runId: 'run_123',
    })
  })

  it('returns 499 when the caller aborts the analysis request', async () => {
    globalThis.fetch = vi.fn((_, init?: RequestInit) => new Promise((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => {
        reject(new DOMException('The operation was aborted.', 'AbortError'))
      }, { once: true })
    })) as typeof fetch

    const { analyzeVideoContent } = await import('./video-analysis.service.js')
    const controller = new AbortController()
    const analysisPromise = analyzeVideoContent('https://backend.example.com/api/bilibili/analysis-media/id', {
      signal: controller.signal,
    })

    controller.abort()

    await expect(analysisPromise).rejects.toMatchObject({
      statusCode: 499,
      message: '分析请求已取消',
    } satisfies Partial<AppError>)
    expect(loggerWarnMock).toHaveBeenCalled()
  })

  it('returns 499 immediately when the caller signal is already aborted', async () => {
    globalThis.fetch = vi.fn(async () => new Response('{}', {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
      },
    }))

    const { analyzeVideoContent } = await import('./video-analysis.service.js')
    const controller = new AbortController()
    controller.abort()

    await expect(analyzeVideoContent('https://backend.example.com/api/bilibili/proxy/token', {
      signal: controller.signal,
      analysisConfig: {
        baseUrl: 'https://custom.example.com/analyze',
      },
    })).rejects.toMatchObject({
      statusCode: 499,
      message: '分析请求已取消',
    } satisfies Partial<AppError>)
    expect(globalThis.fetch).not.toHaveBeenCalled()
  })

  it('uses request-scoped analysis config with env fallback', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      video_captions: 'captions',
    }), {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
      },
    }))
    globalThis.fetch = fetchMock

    const { analyzeVideoContent } = await import('./video-analysis.service.js')

    await analyzeVideoContent('https://backend.example.com/api/douyin/proxy/token', {
      analysisConfig: {
        baseUrl: 'https://custom.example.com/analyze',
      },
    })

    expect(fetchMock).toHaveBeenCalledWith(
      'https://custom.example.com/analyze',
      expect.objectContaining({
        headers: expect.objectContaining({
          'Content-Type': 'application/json',
        }),
      }),
    )
  })

  it('prefers request-scoped token over env token', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      run_id: 'run_custom',
    }), {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
      },
    }))
    globalThis.fetch = fetchMock
    process.env.VIDEO_ANALYSIS_API_TOKEN = 'env-token'

    const { analyzeVideoContent } = await import('./video-analysis.service.js')

    await analyzeVideoContent('https://backend.example.com/api/douyin/proxy/token', {
      analysisConfig: {
        apiToken: 'request-token',
      },
    })

    expect(fetchMock).toHaveBeenCalledWith(
      'https://analysis.example.com/run',
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer request-token',
        }),
      }),
    )
  })

  it('rejects non-https request-scoped analysis url', async () => {
    globalThis.fetch = vi.fn(async () => new Response('{}', {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
      },
    }))

    const { analyzeVideoContent } = await import('./video-analysis.service.js')

    await expect(analyzeVideoContent('https://backend.example.com/api/douyin/proxy/token', {
      analysisConfig: {
        baseUrl: 'ftp://custom.example.com/analyze',
      },
    })).rejects.toMatchObject({
      statusCode: 400,
      message: '视频分析服务地址必须使用 HTTP 或 HTTPS',
    } satisfies Partial<AppError>)
  })

  it('rejects request-scoped analysis urls with embedded credentials', async () => {
    globalThis.fetch = vi.fn(async () => new Response('{}', {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
      },
    }))

    const { analyzeVideoContent } = await import('./video-analysis.service.js')

    await expect(analyzeVideoContent('https://backend.example.com/api/douyin/proxy/token', {
      analysisConfig: {
        baseUrl: 'https://user:secret@custom.example.com/analyze',
      },
    })).rejects.toMatchObject({
      statusCode: 400,
      message: '视频分析服务地址不能包含用户名或密码',
    } satisfies Partial<AppError>)
  })

  it('rejects request-scoped analysis urls that target local or private hosts', async () => {
    globalThis.fetch = vi.fn(async () => new Response('{}', {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
      },
    }))

    const { analyzeVideoContent } = await import('./video-analysis.service.js')

    await expect(analyzeVideoContent('https://backend.example.com/api/douyin/proxy/token', {
      analysisConfig: {
        baseUrl: 'http://127.0.0.1:8080/analyze',
      },
    })).rejects.toMatchObject({
      statusCode: 400,
      message: '视频分析服务地址不能指向本地或私有网络地址',
    } satisfies Partial<AppError>)
  })

  it('uses provider-specific Qwen env defaults', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      id: 'chatcmpl-qwen',
      choices: [{ message: { content: '{"video_captions":"captions"}' } }],
    }), {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
      },
    }))
    globalThis.fetch = fetchMock
    process.env.QWEN_ANALYSIS_BASE_URL = 'https://dashscope.example.com/compatible-mode/v1'
    process.env.QWEN_ANALYSIS_API_KEY = 'qwen-env-key'
    process.env.QWEN_ANALYSIS_MODEL = 'qwen3.5-flash'

    const { analyzeVideoContent } = await import('./video-analysis.service.js')

    await analyzeVideoContent('https://backend.example.com/api/douyin/proxy/token', {
      analysisConfig: {
        provider: 'qwen',
      },
    })

    expect(fetchMock).toHaveBeenCalledWith(
      'https://dashscope.example.com/compatible-mode/v1/chat/completions',
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer qwen-env-key',
        }),
        body: expect.stringContaining('qwen3.5-flash'),
      }),
    )
  })

  it('uses the saved video-owned qwen config by default', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      id: 'chatcmpl-qwen',
      choices: [{ message: { content: '{"video_captions":"captions"}' } }],
    }), {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
      },
    }))
    globalThis.fetch = fetchMock
    process.env.QWEN_ANALYSIS_BASE_URL = ''
    process.env.QWEN_ANALYSIS_API_KEY = ''
    process.env.QWEN_ANALYSIS_MODEL = ''
    loadSettingsMock.mockReturnValue({
      features: {
        video: {
          provider: 'qwen',
          baseUrl: 'https://video-qwen.example.com/v1',
          apiKey: 'saved-qwen-key',
          model: 'qwen-plus',
        },
        image: {},
        article: {},
      },
    })

    const { analyzeVideoContent } = await import('./video-analysis.service.js')
    await analyzeVideoContent('https://backend.example.com/api/douyin/proxy/token')

    const fetchCall = fetchMock.mock.calls[0] as unknown as [string, RequestInit | undefined] | undefined
    expect(fetchCall?.[0]).toBe('https://video-qwen.example.com/v1/chat/completions')
    expect(String(fetchCall?.[1]?.body)).toContain('qwen-plus')
  })

  it('uses the saved video-owned coze config by default', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      run_id: 'run_coze',
    }), {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
      },
    }))
    globalThis.fetch = fetchMock
    process.env.COZE_ANALYSIS_BASE_URL = ''
    process.env.COZE_ANALYSIS_API_TOKEN = ''
    process.env.VIDEO_ANALYSIS_API_BASE_URL = ''
    process.env.VIDEO_ANALYSIS_API_TOKEN = ''
    loadSettingsMock.mockReturnValue({
      features: {
        video: {
          provider: 'coze',
          baseUrl: 'https://video-coze.example.com/run',
          apiToken: 'saved-coze-token',
        },
        image: {},
        article: {},
      },
    })

    const { analyzeVideoContent } = await import('./video-analysis.service.js')
    await analyzeVideoContent('https://backend.example.com/api/douyin/proxy/token')

    expect(fetchMock).toHaveBeenCalledWith(
      'https://video-coze.example.com/run',
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer saved-coze-token',
        }),
      }),
    )
  })

  it('uses user-scoped video config when userId is provided', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      id: 'chatcmpl-qwen-user',
      choices: [{ message: { content: '{"video_captions":"captions"}' } }],
    }), {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
      },
    }))
    globalThis.fetch = fetchMock
    loadSettingsMock.mockReturnValue({
      features: {
        video: {
          provider: 'coze',
          baseUrl: 'https://global.example.com/run',
          apiToken: 'global-token',
        },
        image: {},
        article: {},
      },
    })
    loadSettingsForUserMock.mockResolvedValue({
      features: {
        video: {
          provider: 'qwen',
          baseUrl: 'https://user-video.example.com/v1',
          apiKey: 'user-qwen-key',
          model: 'qwen-user-model',
        },
        image: {},
        article: {},
      },
    })

    const { analyzeVideoContent, resolveFeatureProviderConfig } = await import('./video-analysis.service.js')

    await analyzeVideoContent('https://backend.example.com/api/douyin/proxy/token', {
      userId: 'user-1',
    })

    expect(fetchMock).toHaveBeenCalledWith(
      'https://user-video.example.com/v1/chat/completions',
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer user-qwen-key',
        }),
        body: expect.stringContaining('qwen-user-model'),
      }),
    )

    await expect(resolveFeatureProviderConfig('video', 'qwen', 'user-1')).resolves.toMatchObject({
      baseUrl: 'https://user-video.example.com/v1',
      apiKey: 'user-qwen-key',
      model: 'qwen-user-model',
      dispatcher: expect.any(Object),
    })
  })

  it('uses dedicated image-generation env defaults and does not reuse text model fallback', async () => {
    process.env.IMAGE_GENERATION_BASE_URL = 'https://images.example.com/v1'
    process.env.IMAGE_GENERATION_API_KEY = 'image-gen-env-key'
    process.env.IMAGE_GENERATION_MODEL = ''

    const { resolveFeatureProviderConfig } = await import('./video-analysis.service.js')

    await expect(resolveFeatureProviderConfig('imageGeneration', 'qwen')).rejects.toMatchObject({
      statusCode: 400,
      message: '未配置图片生成模型，请先在分析设置中配置图片生成模型服务',
    } satisfies Partial<AppError>)

    await expect(resolveFeatureProviderConfig('imageGeneration', 'qwen', undefined, {
      requireModel: false,
    })).resolves.toMatchObject({
      baseUrl: 'https://images.example.com/v1',
      apiKey: 'image-gen-env-key',
      model: undefined,
      dispatcher: expect.any(Object),
    })
  })

  it('resolves image-generation config from user-scoped settings', async () => {
    loadSettingsForUserMock.mockResolvedValue({
      features: {
        video: { provider: 'coze' },
        image: {},
        article: {},
        imageGeneration: {
          baseUrl: 'https://user-images.example.com/v1',
          apiKey: 'user-image-key',
          model: 'wanx2.1-t2i-turbo',
        },
      },
    })

    const { resolveFeatureProviderConfig } = await import('./video-analysis.service.js')

    await expect(resolveFeatureProviderConfig('imageGeneration', 'qwen', 'user-1')).resolves.toMatchObject({
      baseUrl: 'https://user-images.example.com/v1',
      apiKey: 'user-image-key',
      model: 'wanx2.1-t2i-turbo',
      dispatcher: expect.any(Object),
    })
  })

  it('does not fall back to legacy Coze env defaults for Qwen', async () => {
    const fetchMock = vi.fn(async () => new Response('{}', {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
      },
    }))
    globalThis.fetch = fetchMock
    process.env.VIDEO_ANALYSIS_API_BASE_URL = 'https://legacy-coze.example.com/run'
    process.env.VIDEO_ANALYSIS_API_TOKEN = 'legacy-token'

    const { analyzeVideoContent } = await import('./video-analysis.service.js')

    await expect(analyzeVideoContent('https://backend.example.com/api/douyin/proxy/token', {
      analysisConfig: {
        provider: 'qwen',
      },
    })).rejects.toMatchObject({
      statusCode: 500,
      message: '未配置视频分析服务地址',
    } satisfies Partial<AppError>)
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
