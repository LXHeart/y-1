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
      article: {
        baseUrl: 'https://article.example.com/v1',
        apiKey: 'article-key',
        model: 'qwen-plus',
      },
      imageGeneration: {
        baseUrl: 'https://images.example.com/v1',
        apiKey: 'image-key',
        model: 'gpt-image-1',
      },
    },
  })),
  loadSettingsForUserMock: vi.fn(async (): Promise<any> => ({
    features: {
      video: { provider: 'coze' },
      image: {},
      article: {
        baseUrl: 'https://article.example.com/v1',
        apiKey: 'article-key',
        model: 'qwen-plus',
      },
      imageGeneration: {
        baseUrl: 'https://images.example.com/v1',
        apiKey: 'image-key',
        model: 'gpt-image-1',
      },
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

describe('article image service', () => {
  const originalFetch = globalThis.fetch

  beforeEach(() => {
    vi.resetModules()
    process.env.QWEN_ANALYSIS_BASE_URL = 'https://dashscope.example.com/compatible-mode/v1'
    process.env.QWEN_ANALYSIS_API_KEY = 'qwen-env-key'
    process.env.QWEN_ANALYSIS_MODEL = 'qwen3.5-flash'
    process.env.IMAGE_GENERATION_BASE_URL = 'https://images.example.com/v1'
    process.env.IMAGE_GENERATION_API_KEY = 'image-env-key'
    process.env.IMAGE_GENERATION_MODEL = 'gpt-image-1'

    loadSettingsMock.mockReset()
    loadSettingsForUserMock.mockReset()
    lookupMock.mockReset()
    loggerInfoMock.mockReset()
    loggerWarnMock.mockReset()
    loggerErrorMock.mockReset()

    lookupMock.mockResolvedValue([{ address: '93.184.216.34', family: 4 }])
    loadSettingsMock.mockReturnValue({
      features: {
        video: { provider: 'coze' },
        image: {},
        article: {
          baseUrl: 'https://article.example.com/v1',
          apiKey: 'article-key',
          model: 'qwen-plus',
        },
        imageGeneration: {
          baseUrl: 'https://images.example.com/v1',
          apiKey: 'image-key',
          model: 'gpt-image-1',
        },
      },
    })
    loadSettingsForUserMock.mockResolvedValue({
      features: {
        video: { provider: 'coze' },
        image: {},
        article: {
          baseUrl: 'https://article.example.com/v1',
          apiKey: 'article-key',
          model: 'qwen-plus',
        },
        imageGeneration: {
          baseUrl: 'https://images.example.com/v1',
          apiKey: 'image-key',
          model: 'gpt-image-1',
        },
      },
    })
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
  })

  it('recommends image placements from article content', async () => {
    globalThis.fetch = vi.fn(async () => new Response(JSON.stringify({
      id: 'chatcmpl-article-images',
      choices: [{
        message: {
          content: JSON.stringify({
            recommendedCount: 2,
            placements: [
              {
                position: '开头',
                description: '封面概念图',
                searchKeywords: '职场沟通 商务 插画',
                prompt: '现代商务风格插画，展示高效沟通场景，蓝白色调',
              },
            ],
          }),
        },
      }],
    }), {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
      },
    }))

    const { recommendImages } = await import('./article-image.service.js')
    const result = await recommendImages({
      content: '这是一篇关于职场沟通技巧的文章正文，内容足够长，可以用于推荐配图位置。',
      outline: '## 开头\n## 主体\n## 结尾',
      platform: 'wechat',
      userId: 'user-1',
    })

    expect(result).toEqual({
      recommendedCount: 2,
      placements: [
        {
          position: '开头',
          description: '封面概念图',
          searchKeywords: '职场沟通 商务 插画',
          prompt: '现代商务风格插画，展示高效沟通场景，蓝白色调',
        },
      ],
    })
  })

  it('generates an image with image-generation config', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      created: 1,
      data: [
        {
          url: 'https://images.example.com/generated-1.png',
          revised_prompt: '优化后的提示词',
        },
      ],
    }), {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
      },
    }))
    globalThis.fetch = fetchMock

    const { generateImage } = await import('./article-image.service.js')
    const result = await generateImage({
      prompt: '现代商务风格插画，展示高效沟通场景，蓝白色调',
      size: '1024x1024',
      userId: 'user-1',
    })

    expect(result).toEqual({
      imageUrl: 'https://images.example.com/generated-1.png',
      revisedPrompt: '优化后的提示词',
    })
    expect(fetchMock).toHaveBeenCalledWith(
      'https://images.example.com/v1/images/generations',
      expect.objectContaining({
        method: 'POST',
        redirect: 'error',
        headers: expect.objectContaining({
          Authorization: 'Bearer image-key',
        }),
      }),
    )
  })

  it('generates an image from b64_json response', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      created: 1,
      data: [
        {
          b64_json: 'iVBORw0KGgoAAAANSUhEUg==',
          revised_prompt: '优化后的提示词',
        },
      ],
    }), {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
      },
    }))
    globalThis.fetch = fetchMock

    const { generateImage } = await import('./article-image.service.js')
    const result = await generateImage({
      prompt: '现代商务风格插画',
      size: '1024x1024',
      userId: 'user-1',
    })

    expect(result.revisedPrompt).toBe('优化后的提示词')
    expect(result.imageUrl).toMatch(/\/api\/article-generation\/generated-images\/[0-9a-f-]{36}/)
  })

  it('falls back to article config when image generation model is missing', async () => {
    process.env.IMAGE_GENERATION_MODEL = ''
    loadSettingsForUserMock.mockResolvedValue({
      features: {
        video: { provider: 'coze' },
        image: {},
        article: {
          baseUrl: 'https://article.example.com/v1',
          apiKey: 'article-key',
          model: 'qwen-plus',
        },
        imageGeneration: {
          baseUrl: 'https://images.example.com/v1',
          apiKey: 'image-key',
        },
      },
    })

    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      created: 1,
      data: [
        {
          url: 'https://article.example.com/generated-fallback.png',
          revised_prompt: 'fallback prompt',
        },
      ],
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))
    globalThis.fetch = fetchMock

    const { generateImage } = await import('./article-image.service.js')
    const result = await generateImage({
      prompt: 'modern office illustration',
      size: '1024x1024',
      userId: 'user-1',
    })

    expect(result).toEqual({
      imageUrl: 'https://article.example.com/generated-fallback.png',
      revisedPrompt: 'fallback prompt',
    })
    expect(fetchMock).toHaveBeenCalledWith(
      'https://article.example.com/v1/images/generations',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          Authorization: 'Bearer article-key',
        }),
      }),
    )
  })

  it('returns parsed image search results from html pages', async () => {
    const fetchMock = vi.fn(async () => new Response(`
      <html>
        <body>
          <div class="imgpt">
            <a href="https://example.com/article-1">
              <img src="https://cdn.example.com/photo-1.jpg" alt="沟通场景插画" width="1200" height="800" />
            </a>
          </div>
          <div class="imgpt">
            <a href="https://example.com/article-2">
              <img src="https://cdn.example.com/photo-2.jpg" alt="办公插画" width="900" height="600" />
            </a>
          </div>
          <img src="https://cdn.example.com/logo.jpg" alt="站点图标" width="32" height="32" />
        </body>
      </html>
    `, {
      status: 200,
      headers: {
        'Content-Type': 'text/html; charset=utf-8',
      },
    }))
    globalThis.fetch = fetchMock

    const { searchImages } = await import('./article-image.service.js')
    const result = await searchImages({
      keywords: '职场沟通 插画',
      count: 2,
      userId: 'user-1',
    })

    expect(fetchMock).toHaveBeenCalledWith(
      'https://www.bing.com/images/search?q=%E8%81%8C%E5%9C%BA%E6%B2%9F%E9%80%9A+%E6%8F%92%E7%94%BB',
      expect.objectContaining({
        method: 'GET',
        redirect: 'follow',
      }),
    )
    expect(result).toEqual([
      {
        url: 'https://cdn.example.com/photo-1.jpg',
        thumbnailUrl: 'https://cdn.example.com/photo-1.jpg',
        sourceUrl: 'https://example.com/article-1',
        description: '沟通场景插画',
        width: 1200,
        height: 800,
      },
      {
        url: 'https://cdn.example.com/photo-2.jpg',
        thumbnailUrl: 'https://cdn.example.com/photo-2.jpg',
        sourceUrl: 'https://example.com/article-2',
        description: '办公插画',
        width: 900,
        height: 600,
      },
    ])
  })

  it('throws when html search page does not contain image results', async () => {
    globalThis.fetch = vi.fn(async () => new Response(`
      <html>
        <body>
          <img src="https://cdn.example.com/logo.jpg" alt="站点图标" width="32" height="32" />
        </body>
      </html>
    `, {
      status: 200,
      headers: {
        'Content-Type': 'text/html; charset=utf-8',
      },
    }))

    const { searchImages } = await import('./article-image.service.js')

    await expect(searchImages({
      keywords: '职场沟通 插画',
      count: 2,
      userId: 'user-1',
    })).rejects.toMatchObject({
      statusCode: 502,
      message: '搜图失败，请稍后重试',
    } satisfies Partial<AppError>)
  })

  it('propagates caller aborts without mapping them to timeouts', async () => {
    globalThis.fetch = vi.fn(async (_input, init) => {
      const signal = init?.signal as AbortSignal
      return await new Promise<Response>((_resolve, reject) => {
        signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
      })
    })

    const controller = new AbortController()
    const { searchImages } = await import('./article-image.service.js')
    const promise = searchImages({
      keywords: '职场沟通 插画',
      count: 2,
      userId: 'user-1',
      signal: controller.signal,
    })

    controller.abort()

    await expect(promise).rejects.toMatchObject({
      message: 'Request aborted',
    })
  })

  it('generates an image with reference images using two-step pipeline', async () => {
    const callLog: Array<{ url: string; body: string }> = []
    const fetchMock = vi.fn(async (input: any, init: any) => {
      const inputStr = String(input)
      callLog.push({ url: inputStr, body: init?.body ?? '' })

      if (inputStr.includes('/chat/completions')) {
        return new Response(JSON.stringify({
          id: 'chatcmpl-describe',
          choices: [{
            message: {
              content: '画面中心是一碗热腾腾的牛肉面，暖色调灯光，木桌纹理可见，背景虚化。',
            },
          }],
        }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }

      return new Response(JSON.stringify({
        created: 1,
        data: [{
          url: 'https://images.example.com/ref-generated.png',
          revised_prompt: '融合参考图的生成结果',
        }],
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })
    globalThis.fetch = fetchMock

    const { generateImage } = await import('./article-image.service.js')
    const result = await generateImage({
      prompt: '一碗面，暖色调',
      size: '1024x1024',
      images: [{
        mimeType: 'image/jpeg',
        dataUrl: 'data:image/jpeg;base64,/9j/4AAQ',
      }],
      userId: 'user-1',
    })

    expect(result).toEqual({
      imageUrl: 'https://images.example.com/ref-generated.png',
      revisedPrompt: '融合参考图的生成结果',
    })

    expect(callLog).toHaveLength(2)
    expect(callLog[0].url).toContain('/chat/completions')
    expect(callLog[1].url).toContain('/images/generations')

    const genBody = JSON.parse(callLog[1].body)
    expect(genBody.prompt).toContain('[参考素材]')
    expect(genBody.prompt).toContain('素材1')
    expect(genBody.prompt).toContain('一碗面，暖色调')
  })

  it('describes each reference image separately', async () => {
    const callLog: Array<{ url: string; body: string }> = []
    const fetchMock = vi.fn(async (input: any, init: any) => {
      callLog.push({ url: String(input), body: init?.body ?? '' })

      if (String(input).includes('/chat/completions')) {
        const body = JSON.parse(init?.body ?? '{}')
        const content = body.messages?.[0]?.content
        const hasTwoImages = Array.isArray(content) && content.filter((c: any) => c.type === 'image_url').length === 1
        const descIndex = callLog.filter(c => c.url.includes('/chat/completions')).length - 1
        return new Response(JSON.stringify({
          id: 'chatcmpl-describe',
          choices: [{
            message: {
              content: hasTwoImages ? `描述${descIndex + 1}` : `描述${descIndex + 1}`,
            },
          }],
        }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }

      return new Response(JSON.stringify({
        created: 1,
        data: [{ url: 'https://images.example.com/multi-ref.png' }],
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })
    globalThis.fetch = fetchMock

    const { generateImage } = await import('./article-image.service.js')
    await generateImage({
      prompt: '融合两张素材',
      size: '1024x1024',
      images: [
        { mimeType: 'image/jpeg', dataUrl: 'data:image/jpeg;base64,img1' },
        { mimeType: 'image/png', dataUrl: 'data:image/png;base64,img2' },
      ],
      userId: 'user-1',
    })

    const chatCalls = callLog.filter(c => c.url.includes('/chat/completions'))
    expect(chatCalls).toHaveLength(2)

    const genBody = JSON.parse(callLog[callLog.length - 1].body)
    expect(genBody.prompt).toContain('素材1')
    expect(genBody.prompt).toContain('素材2')
  })

  it('skips reference image pipeline when images array is empty', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      created: 1,
      data: [{ url: 'https://images.example.com/no-ref.png' }],
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))
    globalThis.fetch = fetchMock

    const { generateImage } = await import('./article-image.service.js')
    await generateImage({
      prompt: '纯文字提示词',
      size: '1024x1024',
      images: [],
      userId: 'user-1',
    })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/images/generations'),
      expect.anything(),
    )
  })
})
