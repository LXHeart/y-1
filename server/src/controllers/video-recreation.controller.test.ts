import { describe, expect, it, vi, beforeEach } from 'vitest'
import { EventEmitter } from 'node:events'
import type { NextFunction, Request, Response } from 'express'

const {
  getAuthenticatedUserMock,
  adaptVideoContentMock,
  generateSceneImageMock,
  generateAllSceneImagesMock,
  generateAssetImageMock,
  generateAllAssetImagesMock,
} = vi.hoisted(() => ({
  getAuthenticatedUserMock: vi.fn(),
  adaptVideoContentMock: vi.fn(),
  generateSceneImageMock: vi.fn(),
  generateAllSceneImagesMock: vi.fn(),
  generateAssetImageMock: vi.fn(),
  generateAllAssetImagesMock: vi.fn(),
}))

vi.mock('../lib/auth.js', () => ({
  getAuthenticatedUser: getAuthenticatedUserMock,
}))

vi.mock('../services/video-recreation-image.service.js', () => ({
  generateSceneImage: generateSceneImageMock,
  generateAllSceneImages: generateAllSceneImagesMock,
  generateAssetImage: generateAssetImageMock,
  generateAllAssetImages: generateAllAssetImagesMock,
}))

vi.mock('../services/video-recreation-adaptation.service.js', () => ({
  adaptVideoContent: adaptVideoContentMock,
}))

const {
  adaptContentHandler,
  generateSceneImageHandler,
  generateAllSceneImagesHandler,
  generateAssetImageHandler,
  generateAllAssetImagesHandler,
} = await import('./video-recreation.controller.js')

function createRequestMock(body: Request['body']): Request {
  const req = new EventEmitter() as Request & EventEmitter
  req.body = body
  return req as Request
}

function createResponseMock(): Response {
  const res = new EventEmitter() as Response & EventEmitter
  res.json = vi.fn()
  return res as Response
}

const validScene = {
  shotDescription: 'Close-up of a sunrise over mountains',
  characterDescription: 'A lone hiker standing on a peak',
  actionMovement: 'Slowly raising arms toward the sky',
  dialogueVoiceover: '',
  sceneEnvironment: 'Snow-capped peaks at golden hour',
}

beforeEach(() => {
  getAuthenticatedUserMock.mockReset()
  adaptVideoContentMock.mockReset()
  generateSceneImageMock.mockReset()
  generateAllSceneImagesMock.mockReset()
  generateAssetImageMock.mockReset()
  generateAllAssetImagesMock.mockReset()

  getAuthenticatedUserMock.mockReturnValue({
    id: 'user-1',
    email: 'user@example.com',
    role: 'user',
  })

  adaptVideoContentMock.mockResolvedValue({
    adaptedSummary: '改编摘要',
    characterSheets: [{
      id: 'character-1',
      name: '阿明',
      description: '黑色短发，白色卫衣',
      threeViewPrompt: '角色三视图，正侧背完整设定图',
    }],
    sceneCards: [],
    propCards: [],
    runId: 'adapt-run-1',
  })

  generateSceneImageMock.mockResolvedValue({
    imageUrl: 'https://images.example.com/scene-1.png',
    revisedPrompt: 'Revised prompt for scene 1',
  })

  generateAllSceneImagesMock.mockResolvedValue([
    { imageUrl: 'https://images.example.com/scene-1.png' },
    { imageUrl: 'https://images.example.com/scene-2.png' },
  ])

  generateAssetImageMock.mockResolvedValue({
    imageUrl: 'https://images.example.com/asset-1.png',
    revisedPrompt: 'Revised prompt for asset 1',
  })

  generateAllAssetImagesMock.mockResolvedValue([
    { imageUrl: 'https://images.example.com/asset-1.png' },
    { imageUrl: 'https://images.example.com/asset-2.png' },
  ])
})

// ---------------------------------------------------------------------------
// adaptContentHandler
// ---------------------------------------------------------------------------
describe('adaptContentHandler', () => {
  it('returns adapted content data on success', async () => {
    const req = createRequestMock({
      platform: 'douyin',
      proxyVideoUrl: '/api/douyin/proxy/token-1',
      extractedContent: {
        videoCaptions: '字幕 1',
        sceneDescription: '场景 1',
      },
    })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await adaptContentHandler(req, res, next)

    expect(adaptVideoContentMock).toHaveBeenCalledWith({
      platform: 'douyin',
      proxyVideoUrl: '/api/douyin/proxy/token-1',
      extractedContent: {
        videoCaptions: '字幕 1',
        sceneDescription: '场景 1',
      },
      userId: 'user-1',
      signal: expect.any(AbortSignal),
    })
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: expect.objectContaining({
        adaptedSummary: '改编摘要',
        runId: 'adapt-run-1',
      }),
    })
    expect(next).not.toHaveBeenCalled()
  })

  it('forwards auth errors when user is not authenticated', async () => {
    const error = new Error('请先登录')
    getAuthenticatedUserMock.mockImplementation(() => {
      throw error
    })

    const req = createRequestMock({
      platform: 'douyin',
      proxyVideoUrl: '/api/douyin/proxy/token-1',
      extractedContent: {
        videoCaptions: '字幕 1',
      },
    })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await adaptContentHandler(req, res, next)

    expect(adaptVideoContentMock).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledWith(error)
  })

  it('forwards ZodError to next for invalid request body', async () => {
    const req = createRequestMock({
      platform: 'douyin',
      proxyVideoUrl: '/api/douyin/proxy/token-1',
      extractedContent: {},
    })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await adaptContentHandler(req, res, next)

    expect(adaptVideoContentMock).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledWith(expect.objectContaining({ name: 'ZodError' }))
  })
})

// ---------------------------------------------------------------------------
// generateSceneImageHandler
// ---------------------------------------------------------------------------
describe('generateSceneImageHandler', () => {
  it('returns generated image data on success', async () => {
    const req = createRequestMock({
      scene: validScene,
      overallStyle: 'Cinematic',
      size: '1024x1792',
    })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await generateSceneImageHandler(req, res, next)

    expect(generateSceneImageMock).toHaveBeenCalledWith({
      scene: validScene,
      overallStyle: 'Cinematic',
      size: '1024x1792',
      userId: 'user-1',
      signal: expect.any(AbortSignal),
    })
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: {
        imageUrl: 'https://images.example.com/scene-1.png',
        revisedPrompt: 'Revised prompt for scene 1',
      },
    })
    expect(next).not.toHaveBeenCalled()
  })

  it('forwards auth errors when user is not authenticated', async () => {
    const error = new Error('请先登录')
    getAuthenticatedUserMock.mockImplementation(() => {
      throw error
    })

    const req = createRequestMock({ scene: validScene })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await generateSceneImageHandler(req, res, next)

    expect(generateSceneImageMock).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledWith(error)
  })

  it('uses default size when not provided in request body', async () => {
    const req = createRequestMock({ scene: validScene })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await generateSceneImageHandler(req, res, next)

    expect(generateSceneImageMock).toHaveBeenCalledWith(
      expect.objectContaining({
        size: '1024x1792', // default from schema
      }),
    )
  })

  it('forwards ZodError to next for invalid request body', async () => {
    const req = createRequestMock({
      // missing required 'scene' field
      overallStyle: 'Cinematic',
    })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await generateSceneImageHandler(req, res, next)

    expect(generateSceneImageMock).not.toHaveBeenCalled()
    expect(res.json).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledWith(expect.objectContaining({
      name: 'ZodError',
    }))
  })

  it('forwards ZodError to next for invalid scene fields', async () => {
    const req = createRequestMock({
      scene: {
        // shotDescription missing
        characterDescription: 'A character',
        actionMovement: '',
        dialogueVoiceover: '',
        sceneEnvironment: 'An environment',
      },
    })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await generateSceneImageHandler(req, res, next)

    expect(generateSceneImageMock).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledWith(expect.objectContaining({
      name: 'ZodError',
    }))
  })

  it('forwards service errors to next', async () => {
    const error = new Error('Image generation service unavailable')
    generateSceneImageMock.mockRejectedValueOnce(error)

    const req = createRequestMock({ scene: validScene })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await generateSceneImageHandler(req, res, next)

    expect(res.json).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledWith(error)
  })

  it('removes abort listeners from request and response on completion', async () => {
    const req = createRequestMock({ scene: validScene })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    const removeListenerSpyReq = vi.spyOn(req, 'removeListener')
    const removeListenerSpyRes = vi.spyOn(res, 'removeListener')

    await generateSceneImageHandler(req, res, next)

    expect(removeListenerSpyReq).toHaveBeenCalledWith('aborted', expect.any(Function))
    expect(removeListenerSpyRes).toHaveBeenCalledWith('close', expect.any(Function))
  })

  it('removes abort listeners even when service throws', async () => {
    const error = new Error('Service failure')
    generateSceneImageMock.mockRejectedValueOnce(error)

    const req = createRequestMock({ scene: validScene })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    const removeListenerSpyReq = vi.spyOn(req, 'removeListener')
    const removeListenerSpyRes = vi.spyOn(res, 'removeListener')

    await generateSceneImageHandler(req, res, next)

    expect(removeListenerSpyReq).toHaveBeenCalledWith('aborted', expect.any(Function))
    expect(removeListenerSpyRes).toHaveBeenCalledWith('close', expect.any(Function))
    expect(next).toHaveBeenCalledWith(error)
  })
})

// ---------------------------------------------------------------------------
// generateAssetImageHandler
// ---------------------------------------------------------------------------
describe('generateAssetImageHandler', () => {
  it('returns generated asset image data on success', async () => {
    const req = createRequestMock({
      assetType: 'scene',
      visualStyle: 'Anime style',
      asset: {
        id: 'scene-1',
        title: '教室门口',
        description: '黄昏下的学校走廊',
        imagePrompt: '黄昏学校走廊，电影感镜头',
      },
    })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await generateAssetImageHandler(req, res, next)

    expect(generateAssetImageMock).toHaveBeenCalledWith({
      assetType: 'scene',
      visualStyle: 'Anime style',
      size: '1024x1792',
      asset: {
        id: 'scene-1',
        title: '教室门口',
        description: '黄昏下的学校走廊',
        imagePrompt: '黄昏学校走廊，电影感镜头',
      },
      userId: 'user-1',
      signal: expect.any(AbortSignal),
    })
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: {
        imageUrl: 'https://images.example.com/asset-1.png',
        revisedPrompt: 'Revised prompt for asset 1',
      },
    })
    expect(next).not.toHaveBeenCalled()
  })

  it('forwards auth errors when user is not authenticated', async () => {
    const error = new Error('请先登录')
    getAuthenticatedUserMock.mockImplementation(() => {
      throw error
    })

    const req = createRequestMock({
      assetType: 'scene',
      asset: {
        id: 'scene-1',
        description: '黄昏下的学校走廊',
        imagePrompt: '黄昏学校走廊，电影感镜头',
      },
    })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await generateAssetImageHandler(req, res, next)

    expect(generateAssetImageMock).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledWith(error)
  })
})

// ---------------------------------------------------------------------------
// generateAllSceneImagesHandler
// ---------------------------------------------------------------------------
describe('generateAllSceneImagesHandler', () => {
  it('returns all generated image data on success', async () => {
    const scenes = [validScene, {
      shotDescription: 'Wide shot of a city skyline at night',
      characterDescription: 'A couple walking down a neon-lit street',
      actionMovement: 'Walking hand in hand',
      dialogueVoiceover: '',
      sceneEnvironment: 'Busy downtown with glowing billboards',
    }]

    generateAllSceneImagesMock.mockResolvedValue([
      { imageUrl: 'https://images.example.com/scene-1.png', revisedPrompt: 'Prompt 1' },
      { imageUrl: 'https://images.example.com/scene-2.png', revisedPrompt: 'Prompt 2' },
    ])

    const req = createRequestMock({
      scenes,
      overallStyle: 'Anime style',
      size: '1024x1024',
    })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await generateAllSceneImagesHandler(req, res, next)

    expect(generateAllSceneImagesMock).toHaveBeenCalledWith({
      scenes,
      overallStyle: 'Anime style',
      size: '1024x1024',
      userId: 'user-1',
      signal: expect.any(AbortSignal),
    })
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: {
        images: [
          { imageUrl: 'https://images.example.com/scene-1.png', revisedPrompt: 'Prompt 1' },
          { imageUrl: 'https://images.example.com/scene-2.png', revisedPrompt: 'Prompt 2' },
        ],
      },
    })
    expect(next).not.toHaveBeenCalled()
  })

  it('forwards auth errors when user is not authenticated', async () => {
    const error = new Error('请先登录')
    getAuthenticatedUserMock.mockImplementation(() => {
      throw error
    })

    const req = createRequestMock({
      scenes: [validScene],
    })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await generateAllSceneImagesHandler(req, res, next)

    expect(generateAllSceneImagesMock).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledWith(error)
  })

  it('forwards ZodError to next for empty scenes array', async () => {
    const req = createRequestMock({ scenes: [] })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await generateAllSceneImagesHandler(req, res, next)

    expect(generateAllSceneImagesMock).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledWith(expect.objectContaining({
      name: 'ZodError',
    }))
  })

  it('forwards ZodError to next for missing scenes field', async () => {
    const req = createRequestMock({ overallStyle: 'Cinematic' })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await generateAllSceneImagesHandler(req, res, next)

    expect(generateAllSceneImagesMock).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledWith(expect.objectContaining({
      name: 'ZodError',
    }))
  })

  it('forwards ZodError to next for more than 20 scenes', async () => {
    const tooManyScenes = Array.from({ length: 21 }, (_, i) => ({
      shotDescription: `Scene ${i + 1}`,
      characterDescription: `Character ${i + 1}`,
      actionMovement: '',
      dialogueVoiceover: '',
      sceneEnvironment: `Environment ${i + 1}`,
    }))

    const req = createRequestMock({ scenes: tooManyScenes })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await generateAllSceneImagesHandler(req, res, next)

    expect(generateAllSceneImagesMock).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledWith(expect.objectContaining({
      name: 'ZodError',
    }))
  })

  it('forwards ZodError to next for invalid scene within the array', async () => {
    const req = createRequestMock({
      scenes: [
        {
          // missing required fields
          actionMovement: 'Walking',
          dialogueVoiceover: '',
        },
      ],
    })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await generateAllSceneImagesHandler(req, res, next)

    expect(generateAllSceneImagesMock).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledWith(expect.objectContaining({
      name: 'ZodError',
    }))
  })

  it('forwards service errors to next', async () => {
    const error = new Error('Batch image generation failed')
    generateAllSceneImagesMock.mockRejectedValueOnce(error)

    const req = createRequestMock({ scenes: [validScene] })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await generateAllSceneImagesHandler(req, res, next)

    expect(res.json).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledWith(error)
  })

  it('removes abort listeners from request and response on completion', async () => {
    const req = createRequestMock({ scenes: [validScene] })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    const removeListenerSpyReq = vi.spyOn(req, 'removeListener')
    const removeListenerSpyRes = vi.spyOn(res, 'removeListener')

    await generateAllSceneImagesHandler(req, res, next)

    expect(removeListenerSpyReq).toHaveBeenCalledWith('aborted', expect.any(Function))
    expect(removeListenerSpyRes).toHaveBeenCalledWith('close', expect.any(Function))
  })

  it('removes abort listeners even when service throws', async () => {
    const error = new Error('Service failure')
    generateAllSceneImagesMock.mockRejectedValueOnce(error)

    const req = createRequestMock({ scenes: [validScene] })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    const removeListenerSpyReq = vi.spyOn(req, 'removeListener')
    const removeListenerSpyRes = vi.spyOn(res, 'removeListener')

    await generateAllSceneImagesHandler(req, res, next)

    expect(removeListenerSpyReq).toHaveBeenCalledWith('aborted', expect.any(Function))
    expect(removeListenerSpyRes).toHaveBeenCalledWith('close', expect.any(Function))
    expect(next).toHaveBeenCalledWith(error)
  })

  it('strips unknown fields from request body via schema', async () => {
    const req = createRequestMock({
      scenes: [validScene],
      overallStyle: 'Cinematic',
      extraField: 'should be removed',
    })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await generateAllSceneImagesHandler(req, res, next)

    expect(generateAllSceneImagesMock).toHaveBeenCalledWith(
      expect.objectContaining({
        scenes: [validScene],
        overallStyle: 'Cinematic',
      }),
    )
    expect(generateAllSceneImagesMock).not.toHaveBeenCalledWith(
      expect.objectContaining({
        extraField: expect.anything(),
      }),
    )
  })
})

// ---------------------------------------------------------------------------
// generateAllAssetImagesHandler
// ---------------------------------------------------------------------------
describe('generateAllAssetImagesHandler', () => {
  it('returns all generated asset images on success', async () => {
    const req = createRequestMock({
      assetType: 'prop',
      assets: [
        {
          id: 'prop-1',
          name: '旧相机',
          description: '银黑色胶片相机',
          imagePrompt: '银黑色胶片相机产品设定图',
        },
        {
          id: 'prop-2',
          name: '旧皮箱',
          description: '棕色旧皮箱',
          imagePrompt: '棕色旧皮箱产品设定图',
        },
      ],
    })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await generateAllAssetImagesHandler(req, res, next)

    expect(generateAllAssetImagesMock).toHaveBeenCalledWith({
      assetType: 'prop',
      size: '1024x1792',
      visualStyle: undefined,
      assets: [
        {
          id: 'prop-1',
          name: '旧相机',
          description: '银黑色胶片相机',
          imagePrompt: '银黑色胶片相机产品设定图',
        },
        {
          id: 'prop-2',
          name: '旧皮箱',
          description: '棕色旧皮箱',
          imagePrompt: '棕色旧皮箱产品设定图',
        },
      ],
      userId: 'user-1',
      signal: expect.any(AbortSignal),
    })
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: {
        images: [
          { imageUrl: 'https://images.example.com/asset-1.png' },
          { imageUrl: 'https://images.example.com/asset-2.png' },
        ],
      },
    })
    expect(next).not.toHaveBeenCalled()
  })

  it('forwards ZodError to next for empty assets array', async () => {
    const req = createRequestMock({
      assetType: 'scene',
      assets: [],
    })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await generateAllAssetImagesHandler(req, res, next)

    expect(generateAllAssetImagesMock).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledWith(expect.objectContaining({ name: 'ZodError' }))
  })
})
