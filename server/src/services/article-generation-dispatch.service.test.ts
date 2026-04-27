import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppError } from '../lib/errors.js'

const {
  loggerInfoMock,
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
    warn: vi.fn(),
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

describe('article generation dispatch', () => {
  const originalFetch = globalThis.fetch

  beforeEach(() => {
    vi.resetModules()
    process.env.QWEN_ANALYSIS_BASE_URL = 'https://dashscope.example.com/compatible-mode/v1'
    process.env.QWEN_ANALYSIS_API_KEY = 'qwen-env-key'
    process.env.QWEN_ANALYSIS_MODEL = 'qwen3.5-flash'
    loadSettingsMock.mockReset()
    loadSettingsForUserMock.mockReset()
    lookupMock.mockReset()
    lookupMock.mockResolvedValue([{ address: '93.184.216.34', family: 4 }])
    loadSettingsMock.mockReturnValue({
      features: {
        video: { provider: 'coze' },
        image: {},
        article: {},
      },
    })
    loadSettingsForUserMock.mockResolvedValue({
      features: {
        video: { provider: 'coze' },
        image: {},
        article: {},
      },
    })
    loggerInfoMock.mockReset()
    loggerErrorMock.mockReset()
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
  })

  describe('generateTitles', () => {
    it('returns parsed title options from Qwen', async () => {
      const fetchMock = vi.fn(async () => new Response(JSON.stringify({
        id: 'chatcmpl-titles',
        choices: [{
          message: {
            content: JSON.stringify({
              titles: [
                { title: '职场高情商沟通的 5 个秘诀', hook: '数字列表 + 痛点切入' },
                { title: '为什么你说话总被误解？', hook: '疑问句引发共鸣' },
              ],
            }),
          },
        }],
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }))
      globalThis.fetch = fetchMock

      const { generateTitles } = await import('./article-generation-dispatch.service.js')
      const titles = await generateTitles('职场沟通技巧')

      expect(titles).toEqual([
        { title: '职场高情商沟通的 5 个秘诀', hook: '数字列表 + 痛点切入' },
        { title: '为什么你说话总被误解？', hook: '疑问句引发共鸣' },
      ])

      expect(fetchMock).toHaveBeenCalledWith(
        'https://dashscope.example.com/compatible-mode/v1/chat/completions',
        expect.objectContaining({
          headers: expect.objectContaining({
            Authorization: 'Bearer qwen-env-key',
          }),
        }),
      )

      const fetchCall = fetchMock.mock.calls[0] as unknown as [string, RequestInit | undefined] | undefined
      const requestBody = JSON.parse(String(fetchCall?.[1]?.body)) as {
        model: string
        messages: Array<{ role: string; content: string }>
      }
      expect(requestBody.model).toBe('qwen3.5-flash')
      expect(requestBody.messages[0]?.role).toBe('system')
      expect(requestBody.messages[1]?.content).toContain('职场沟通技巧')
    })

    it('uses the article-owned config even when video provider is coze', async () => {
      const fetchMock = vi.fn(async () => new Response(JSON.stringify({
        id: 'chatcmpl-titles',
        choices: [{
          message: {
            content: JSON.stringify({
              titles: [{ title: 'a', hook: 'b' }],
            }),
          },
        }],
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }))
      globalThis.fetch = fetchMock
      loadSettingsMock.mockReturnValue({
        features: {
          video: { provider: 'coze', baseUrl: 'https://video.example.com/run', apiToken: 'video-token' },
          image: {},
          article: {
            baseUrl: 'https://article.example.com/v1',
            apiKey: 'article-key',
            model: 'qwen-plus',
          },
        },
      })

      const { generateTitles } = await import('./article-generation-dispatch.service.js')
      await generateTitles('test')

      const fetchCall = fetchMock.mock.calls[0] as unknown as [string, RequestInit | undefined] | undefined
      expect(fetchCall?.[0]).toBe('https://article.example.com/v1/chat/completions')
      const requestBody = JSON.parse(String(fetchCall?.[1]?.body)) as { model: string }
      expect(requestBody.model).toBe('qwen-plus')
    })

    it('rejects article-owned configs that target local or private hosts', async () => {
      loadSettingsMock.mockReturnValue({
        features: {
          video: { provider: 'coze' },
          image: {},
          article: {
            baseUrl: 'http://127.0.0.1:8080/v1',
            apiKey: 'article-key',
            model: 'qwen-plus',
          },
        },
      })

      const { generateTitles } = await import('./article-generation-dispatch.service.js')

      await expect(generateTitles('test')).rejects.toMatchObject({
        statusCode: 400,
        message: '文章生成服务地址不能指向本地或私有网络地址',
      } satisfies Partial<AppError>)
    })

    it('rejects user-scoped article configs when DNS resolves to a private host', async () => {
      lookupMock.mockResolvedValue([{ address: '127.0.0.1', family: 4 }])
      loadSettingsForUserMock.mockResolvedValue({
        features: {
          video: { provider: 'coze' },
          image: {},
          article: {
            baseUrl: 'https://article-gateway.example.com/v1',
            apiKey: 'article-key',
            model: 'qwen-plus',
          },
        },
      })

      const { generateTitles } = await import('./article-generation-dispatch.service.js')

      await expect(generateTitles('test', { userId: 'user-1' })).rejects.toMatchObject({
        statusCode: 400,
        message: '文章生成服务地址不能指向本地或私有网络地址',
      } satisfies Partial<AppError>)
    })

    it('rejects when base URL is not configured', async () => {
      process.env.QWEN_ANALYSIS_BASE_URL = ''
      loadSettingsMock.mockReturnValue({
        features: {
          video: { provider: 'coze' },
          image: {},
          article: {},
        },
      })

      const { generateTitles } = await import('./article-generation-dispatch.service.js')

      await expect(generateTitles('test')).rejects.toMatchObject({
        statusCode: 400,
        message: '未配置文章生成服务地址，请先在分析设置中配置文章模型服务',
      } satisfies Partial<AppError>)
    })
  })
})
