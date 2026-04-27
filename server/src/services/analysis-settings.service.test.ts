import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { updateAnalysisSettingsRequest } from '../schemas/settings.js'

const loggerInfoMock = vi.fn()
const loggerWarnMock = vi.fn()

vi.mock('../lib/logger.js', () => ({
  logger: {
    info: loggerInfoMock,
    warn: loggerWarnMock,
    error: vi.fn(),
  },
}))

describe('analysis-settings.service', () => {
  const originalSettingsPath = process.env.ANALYSIS_SETTINGS_PATH

  beforeEach(() => {
    loggerInfoMock.mockReset()
    loggerWarnMock.mockReset()
  })

  afterEach(() => {
    if (originalSettingsPath === undefined) {
      delete process.env.ANALYSIS_SETTINGS_PATH
    } else {
      process.env.ANALYSIS_SETTINGS_PATH = originalSettingsPath
    }
    vi.resetModules()
  })

  it('throws when persisted settings file exists but cannot be parsed', async () => {
    const { writeFileSync } = await import('node:fs')
    const { tmpdir } = await import('node:os')
    const { join } = await import('node:path')

    const settingsPath = join(tmpdir(), `analysis-settings-${Date.now()}.json`)
    process.env.ANALYSIS_SETTINGS_PATH = settingsPath
    writeFileSync(settingsPath, '{not-json', 'utf-8')

    const { loadSettings } = await import('./analysis-settings.service.js')

    expect(() => loadSettings()).toThrowError('分析设置文件不可用')
  })

  it('maps legacy provider and shared qwen model into feature-owned settings on load', async () => {
    const { writeFileSync } = await import('node:fs')
    const { tmpdir } = await import('node:os')
    const { join } = await import('node:path')

    const settingsPath = join(tmpdir(), `analysis-settings-legacy-${Date.now()}.json`)
    process.env.ANALYSIS_SETTINGS_PATH = settingsPath
    writeFileSync(settingsPath, JSON.stringify({
      provider: 'qwen',
      providers: {
        qwen: {
          baseUrl: 'https://dashscope.example.com/compatible-mode/v1',
          apiKey: 'qwen-key',
          model: 'qwen-plus',
        },
      },
    }), 'utf-8')

    const { loadSettings } = await import('./analysis-settings.service.js')

    expect(loadSettings()).toEqual({
      integrations: {
        feishu: {},
      },
      features: {
        video: {
          provider: 'qwen',
          baseUrl: 'https://dashscope.example.com/compatible-mode/v1',
          apiKey: 'qwen-key',
          model: 'qwen-plus',
        },
        image: {
          baseUrl: 'https://dashscope.example.com/compatible-mode/v1',
          apiKey: 'qwen-key',
          model: 'qwen-plus',
        },
        article: {
          baseUrl: 'https://dashscope.example.com/compatible-mode/v1',
          apiKey: 'qwen-key',
          model: 'qwen-plus',
        },
        imageGeneration: {},
      },
    })
  })

  it('preserves existing secrets when update omits them', async () => {
    const { mergeAnalysisSettings } = await import('./analysis-settings.service.js')

    const merged = mergeAnalysisSettings({
      features: {
        video: {
          provider: 'qwen',
          baseUrl: 'https://video.example.com/v1',
          apiKey: 'video-key',
          model: 'qwen-plus',
        },
        image: {
          baseUrl: 'https://image.example.com/v1',
          apiKey: 'image-key',
          model: 'qwen-vl-max',
        },
        article: {
          baseUrl: 'https://article.example.com/v1',
          apiKey: 'article-key',
          model: 'qwen-max',
        },
        imageGeneration: {
          baseUrl: 'https://image-generation.example.com/v1',
          apiKey: 'image-generation-key',
          model: 'wanx2.1-t2i-turbo',
        },
      },
    }, {
      features: {
        video: { model: 'qwen3.5-flash' },
        image: { baseUrl: 'https://image.example.com/new-v1' },
      },
    })

    expect(merged).toEqual({
      integrations: {
        feishu: {},
      },
      features: {
        video: {
          provider: 'qwen',
          baseUrl: 'https://video.example.com/v1',
          apiKey: 'video-key',
          model: 'qwen3.5-flash',
        },
        image: {
          baseUrl: 'https://image.example.com/new-v1',
          apiKey: 'image-key',
          model: 'qwen-vl-max',
        },
        article: {
          baseUrl: 'https://article.example.com/v1',
          apiKey: 'article-key',
          model: 'qwen-max',
        },
        imageGeneration: {
          baseUrl: 'https://image-generation.example.com/v1',
          apiKey: 'image-generation-key',
          model: 'wanx2.1-t2i-turbo',
        },
      },
    })
  })

  it('treats masked secrets as unchanged during merge', async () => {
    const { mergeAnalysisSettings } = await import('./analysis-settings.service.js')

    const merged = mergeAnalysisSettings({
      features: {
        video: {
          provider: 'coze',
          baseUrl: 'https://coze.example.com/run',
          apiToken: 'real-secret',
        },
        image: {},
        article: {},
        imageGeneration: {},
      },
    }, {
      features: {
        video: {
          apiToken: '****cret',
        },
      },
    })

    expect(merged.features.video.apiToken).toBe('real-secret')
  })

  it('allows clearing persisted secrets explicitly', async () => {
    const { mergeAnalysisSettings } = await import('./analysis-settings.service.js')

    const merged = mergeAnalysisSettings({
      features: {
        video: {
          provider: 'qwen',
          baseUrl: 'https://video.example.com/v1',
          apiKey: 'video-key',
          model: 'qwen-plus',
        },
        image: {
          baseUrl: 'https://image.example.com/v1',
          apiKey: 'image-key',
          model: 'qwen-vl-max',
        },
        article: {
          baseUrl: 'https://article.example.com/v1',
          apiKey: 'article-key',
          model: 'qwen-max',
        },
        imageGeneration: {
          baseUrl: 'https://image-generation.example.com/v1',
          apiKey: 'image-generation-key',
          model: 'wanx2.1-t2i-turbo',
        },
      },
    }, {
      features: {
        video: { apiKey: '' },
        image: { apiKey: '' },
      },
    })

    expect(merged).toEqual({
      integrations: {
        feishu: {},
      },
      features: {
        video: {
          provider: 'qwen',
          baseUrl: 'https://video.example.com/v1',
          model: 'qwen-plus',
        },
        image: {
          baseUrl: 'https://image.example.com/v1',
          model: 'qwen-vl-max',
        },
        article: {
          baseUrl: 'https://article.example.com/v1',
          apiKey: 'article-key',
          model: 'qwen-max',
        },
        imageGeneration: {
          baseUrl: 'https://image-generation.example.com/v1',
          apiKey: 'image-generation-key',
          model: 'wanx2.1-t2i-turbo',
        },
      },
    })
  })

  it('allows clearing persisted non-secret feature fields', async () => {
    const { mergeAnalysisSettings } = await import('./analysis-settings.service.js')

    const payload = updateAnalysisSettingsRequest.parse({
      features: {
        video: {
          provider: 'coze',
          baseUrl: '',
          model: '',
        },
        image: {
          baseUrl: '',
          model: '',
        },
        article: {
          model: '',
        },
      },
    })

    const merged = mergeAnalysisSettings({
      features: {
        video: {
          provider: 'qwen',
          baseUrl: 'https://video.example.com/v1',
          apiKey: 'video-key',
          model: 'qwen-plus',
        },
        image: {
          baseUrl: 'https://image.example.com/v1',
          apiKey: 'image-key',
          model: 'qwen-vl-max',
        },
        article: {
          baseUrl: 'https://article.example.com/v1',
          apiKey: 'article-key',
          model: 'qwen-max',
        },
        imageGeneration: {
          baseUrl: 'https://image-generation.example.com/v1',
          apiKey: 'image-generation-key',
          model: 'wanx2.1-t2i-turbo',
        },
      },
    }, payload)

    expect(merged).toEqual({
      integrations: {
        feishu: {
          appId: undefined,
          appSecret: undefined,
          folderToken: undefined,
        },
      },
      features: {
        video: {
          provider: 'coze',
        },
        image: {
          apiKey: 'image-key',
        },
        article: {
          baseUrl: 'https://article.example.com/v1',
          apiKey: 'article-key',
        },
        imageGeneration: {
          baseUrl: 'https://image-generation.example.com/v1',
          apiKey: 'image-generation-key',
          model: 'wanx2.1-t2i-turbo',
        },
      },
    })
  })

  it('masks secrets in API responses', async () => {
    const { maskSettingsSecrets } = await import('./analysis-settings.service.js')

    const masked = maskSettingsSecrets({
      integrations: {
        feishu: {
          appId: 'cli_test_app',
          appSecret: 'feishu-secret-key',
          folderToken: 'fld_test_folder',
        },
      },
      features: {
        video: {
          provider: 'qwen',
          baseUrl: 'https://video.example.com/v1',
          apiKey: 'video-secret',
          model: 'qwen3.5-flash',
        },
        image: {
          baseUrl: 'https://image.example.com/v1',
          apiKey: 'image-secret',
          model: 'qwen-vl-max',
        },
        article: {
          baseUrl: 'https://article.example.com/v1',
          apiKey: 'article-secret',
          model: 'qwen-max',
        },
        imageGeneration: {
          baseUrl: 'https://image-generation.example.com/v1',
          apiKey: 'image-generation-secret',
          model: 'wanx2.1-t2i-turbo',
        },
      },
    })

    expect(masked).toEqual({
      integrations: {
        feishu: {
          appId: 'cli_test_app',
          appSecret: '****-key',
          folderToken: 'fld_test_folder',
        },
      },
      features: {
        video: {
          provider: 'qwen',
          baseUrl: 'https://video.example.com/v1',
          apiKey: '****cret',
          model: 'qwen3.5-flash',
        },
        image: {
          baseUrl: 'https://image.example.com/v1',
          apiKey: '****cret',
          model: 'qwen-vl-max',
        },
        article: {
          baseUrl: 'https://article.example.com/v1',
          apiKey: '****cret',
          model: 'qwen-max',
        },
        imageGeneration: {
          baseUrl: 'https://image-generation.example.com/v1',
          apiKey: '****cret',
          model: 'wanx2.1-t2i-turbo',
        },
      },
    })
  })

  it('rejects non-http(s) persisted base urls', async () => {
    const { mergeAnalysisSettings } = await import('./analysis-settings.service.js')

    expect(() => mergeAnalysisSettings({
      features: {
        video: { provider: 'coze' },
        image: {},
        article: {},
        imageGeneration: {},
      },
    }, {
      features: {
        image: {
          baseUrl: 'ftp://insecure.example.com/run',
        },
      },
    })).toThrowError('分析服务地址必须是有效的 HTTP(S) URL，且不能包含用户名或密码')
  })

  it('rejects persisted base urls with embedded credentials', async () => {
    const { mergeAnalysisSettings } = await import('./analysis-settings.service.js')

    expect(() => mergeAnalysisSettings({
      features: {
        video: { provider: 'coze' },
        image: {},
        article: {},
        imageGeneration: {},
      },
    }, {
      features: {
        article: {
          baseUrl: 'https://user:secret@dashscope.example.com/compatible-mode/v1',
        },
      },
    })).toThrowError('分析服务地址必须是有效的 HTTP(S) URL，且不能包含用户名或密码')
  })
})
