import { describe, expect, it, vi, beforeEach } from 'vitest'
import type { NextFunction, Request, Response } from 'express'

const {
  getAuthenticatedUserMock,
  loadSettingsForUserMock,
  saveSettingsForUserMock,
  maskSettingsSecretsMock,
  mergeAnalysisSettingsMock,
  loadHomepageSettingsForUserMock,
  saveHomepageSettingsForUserMock,
  mergeHomepageSettingsMock,
  maskHomepageSettingsSecretsMock,
  resolveFeatureProviderConfigMock,
  getProviderMock,
} = vi.hoisted(() => ({
  getAuthenticatedUserMock: vi.fn(),
  loadSettingsForUserMock: vi.fn(),
  saveSettingsForUserMock: vi.fn(),
  maskSettingsSecretsMock: vi.fn(),
  mergeAnalysisSettingsMock: vi.fn(),
  loadHomepageSettingsForUserMock: vi.fn(),
  saveHomepageSettingsForUserMock: vi.fn(),
  mergeHomepageSettingsMock: vi.fn(),
  maskHomepageSettingsSecretsMock: vi.fn(),
  resolveFeatureProviderConfigMock: vi.fn(),
  getProviderMock: vi.fn(),
}))

vi.mock('../lib/auth.js', () => ({
  getAuthenticatedUser: getAuthenticatedUserMock,
}))

vi.mock('../services/analysis-settings.service.js', () => ({
  loadSettingsForUser: loadSettingsForUserMock,
  saveSettingsForUser: saveSettingsForUserMock,
  maskSettingsSecrets: maskSettingsSecretsMock,
  mergeAnalysisSettings: mergeAnalysisSettingsMock,
  loadHomepageSettingsForUser: loadHomepageSettingsForUserMock,
  saveHomepageSettingsForUser: saveHomepageSettingsForUserMock,
  mergeHomepageSettings: mergeHomepageSettingsMock,
  maskHomepageSettingsSecrets: maskHomepageSettingsSecretsMock,
}))

vi.mock('../services/video-analysis.service.js', () => ({
  resolveFeatureProviderConfig: resolveFeatureProviderConfigMock,
}))

vi.mock('../services/providers/index.js', () => ({
  getProvider: getProviderMock,
}))

const {
  getAnalysisSettingsHandler,
  getHomepageSettingsHandler,
  listModelsHandler,
  updateAnalysisSettingsHandler,
  updateHomepageSettingsHandler,
  verifyModelHandler,
} = await import('./settings.controller.js')

function createResponseMock(): Response {
  return {
    json: vi.fn(),
  } as unknown as Response
}

describe('settings controller', () => {
  beforeEach(() => {
    getAuthenticatedUserMock.mockReset()
    loadSettingsForUserMock.mockReset()
    saveSettingsForUserMock.mockReset()
    maskSettingsSecretsMock.mockReset()
    mergeAnalysisSettingsMock.mockReset()
    loadHomepageSettingsForUserMock.mockReset()
    saveHomepageSettingsForUserMock.mockReset()
    mergeHomepageSettingsMock.mockReset()
    maskHomepageSettingsSecretsMock.mockReset()
    resolveFeatureProviderConfigMock.mockReset()
    getProviderMock.mockReset()

    getAuthenticatedUserMock.mockReturnValue({
      id: 'user-1',
      email: 'user@example.com',
      role: 'admin',
    })

    loadSettingsForUserMock.mockResolvedValue({
      features: {
        video: { provider: 'qwen', apiKey: 'secret', model: 'qwen-max' },
        image: { apiKey: 'image-secret', model: 'qwen-vl-max' },
        article: { apiKey: 'article-secret', model: 'qwen-plus' },
        imageGeneration: { apiKey: 'image-generation-secret', model: 'wanx2.1-t2i-turbo' },
      },
    })
    maskSettingsSecretsMock.mockImplementation((settings) => ({
      ...settings,
      features: {
        ...settings.features,
        video: { ...settings.features.video, apiKey: '****cret' },
        image: { ...settings.features.image, apiKey: '****cret' },
        article: { ...settings.features.article, apiKey: '****cret' },
        imageGeneration: { ...settings.features.imageGeneration, apiKey: '****cret' },
      },
    }))
    mergeAnalysisSettingsMock.mockImplementation((_current, next) => ({
      features: {
        video: { provider: 'qwen', ...(next.features?.video ?? {}) },
        image: next.features?.image ?? {},
        article: next.features?.article ?? {},
        imageGeneration: next.features?.imageGeneration ?? {},
      },
    }))

    loadHomepageSettingsForUserMock.mockResolvedValue({
      hotItems: {
        provider: 'alapi',
        alapiToken: 'secret-token',
      },
    })
    maskHomepageSettingsSecretsMock.mockImplementation((settings) => ({
      ...settings,
      hotItems: {
        ...settings.hotItems,
        alapiToken: settings.hotItems.alapiToken ? '****oken' : undefined,
      },
    }))
    mergeHomepageSettingsMock.mockImplementation((_current, next) => ({
      hotItems: {
        provider: next.hotItems?.provider ?? '60s',
        alapiToken: next.hotItems?.alapiToken,
      },
    }))

    getProviderMock.mockReturnValue({
      label: 'Qwen',
      supportsModelListing: true,
      listModels: vi.fn(async () => ['qwen-max', 'qwen-plus']),
      verifyModel: vi.fn(async () => undefined),
    })
    resolveFeatureProviderConfigMock.mockResolvedValue({
      baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
      apiKey: 'secret',
      model: 'qwen-max',
    })
  })

  it('loads masked analysis settings for the authenticated user', async () => {
    const req = {} as Request
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await getAnalysisSettingsHandler(req, res, next)

    expect(getAuthenticatedUserMock).toHaveBeenCalledWith(req)
    expect(loadSettingsForUserMock).toHaveBeenCalledWith('user-1')
    expect(res.json).toHaveBeenCalledWith(expect.objectContaining({
      success: true,
      data: expect.objectContaining({
        features: expect.objectContaining({
          video: expect.objectContaining({ apiKey: '****cret' }),
        }),
      }),
    }))
    expect(next).not.toHaveBeenCalled()
  })

  it('saves merged analysis settings for the authenticated user', async () => {
    const req = {
      body: {
        features: {
          video: {
            provider: 'qwen',
            baseUrl: 'https://example.com/v1',
            apiKey: 'next-secret',
            model: 'qwen-turbo',
          },
        },
      },
    } as Request
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await updateAnalysisSettingsHandler(req, res, next)

    expect(loadSettingsForUserMock).toHaveBeenCalledWith('user-1')
    expect(saveSettingsForUserMock).toHaveBeenCalledWith('user-1', {
      features: {
        video: {
          provider: 'qwen',
          baseUrl: 'https://example.com/v1',
          apiKey: 'next-secret',
          model: 'qwen-turbo',
        },
        image: {},
        article: {},
        imageGeneration: {},
      },
    })
    expect(next).not.toHaveBeenCalled()
  })

  it('forwards invalid analysis settings payloads as app errors', async () => {
    const req = {
      body: {
        features: {
          video: {
            baseUrl: 'ftp://invalid.example.com',
          },
        },
      },
    } as Request
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await updateAnalysisSettingsHandler(req, res, next)

    expect(saveSettingsForUserMock).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledWith(expect.objectContaining({
      statusCode: 400,
      message: '分析服务地址必须是有效的 HTTP(S) URL，且不能包含用户名或密码，也不能指向本地或私有网络地址',
    }))
  })

  it('forwards private-network analysis settings payloads as app errors', async () => {
    const req = {
      body: {
        features: {
          video: {
            baseUrl: 'http://127.0.0.1:8080/v1',
          },
        },
      },
    } as Request
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await updateAnalysisSettingsHandler(req, res, next)

    expect(saveSettingsForUserMock).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledWith(expect.objectContaining({
      statusCode: 400,
      message: '分析服务地址必须是有效的 HTTP(S) URL，且不能包含用户名或密码，也不能指向本地或私有网络地址',
    }))
  })

  it('loads masked homepage settings for the authenticated user', async () => {
    const req = {} as Request
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await getHomepageSettingsHandler(req, res, next)

    expect(loadHomepageSettingsForUserMock).toHaveBeenCalledWith('user-1')
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: {
        hotItems: {
          provider: 'alapi',
          alapiToken: '****oken',
        },
      },
    })
    expect(next).not.toHaveBeenCalled()
  })

  it('saves merged homepage settings for the authenticated user', async () => {
    const req = {
      body: {
        hotItems: {
          provider: 'alapi',
          alapiToken: 'fresh-token',
        },
      },
    } as Request
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await updateHomepageSettingsHandler(req, res, next)

    expect(saveHomepageSettingsForUserMock).toHaveBeenCalledWith('user-1', {
      hotItems: {
        provider: 'alapi',
        alapiToken: 'fresh-token',
      },
    })
    expect(next).not.toHaveBeenCalled()
  })

  it('lists models using user-scoped provider config', async () => {
    const provider = {
      label: 'Qwen',
      supportsModelListing: true,
      listModels: vi.fn(async () => ['qwen-max', 'qwen-plus']),
    }
    getProviderMock.mockReturnValue(provider)

    const req = {
      body: {
        feature: 'image',
      },
    } as Request
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await listModelsHandler(req, res, next)

    expect(resolveFeatureProviderConfigMock).toHaveBeenCalledWith('image', 'qwen', 'user-1', {
      requireModel: false,
    })
    expect(provider.listModels).toHaveBeenCalled()
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: { models: ['qwen-max', 'qwen-plus'] },
    })
  })

  it('verifies models using user-scoped provider config', async () => {
    const verifyModel = vi.fn(async () => undefined)
    getProviderMock.mockReturnValue({
      label: 'Qwen',
      supportsModelListing: true,
      listModels: vi.fn(),
      verifyModel,
    })

    const req = {
      body: {
        feature: 'article',
        model: 'qwen-max',
      },
    } as Request
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await verifyModelHandler(req, res, next)

    expect(resolveFeatureProviderConfigMock).toHaveBeenCalledWith('article', 'qwen', 'user-1')
    expect(verifyModel).toHaveBeenCalledWith({
      baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
      apiKey: 'secret',
      model: 'qwen-max',
    }, 'qwen-max', { feature: 'article' })
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: { verified: true, modelId: 'qwen-max' },
    })
  })

  it('lists image-generation models using user-scoped provider config', async () => {
    const provider = {
      label: 'Qwen',
      supportsModelListing: true,
      listModels: vi.fn(async () => ['wanx2.1-t2i-turbo']),
    }
    getProviderMock.mockReturnValue(provider)

    const req = {
      body: {
        feature: 'imageGeneration',
      },
    } as Request
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await listModelsHandler(req, res, next)

    expect(resolveFeatureProviderConfigMock).toHaveBeenCalledWith('imageGeneration', 'qwen', 'user-1', {
      requireModel: false,
    })
    expect(provider.listModels).toHaveBeenCalled()
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: { models: ['wanx2.1-t2i-turbo'] },
    })
  })
})
