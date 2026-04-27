import { describe, expect, it, vi, beforeEach } from 'vitest'
import { EventEmitter } from 'node:events'
import type { NextFunction, Request, Response } from 'express'

const {
  getSessionUserMock,
  generateTitlesMock,
  streamOutlineMock,
  streamContentMock,
  recommendImagesMock,
  searchImagesMock,
  generateImageMock,
} = vi.hoisted(() => ({
  getSessionUserMock: vi.fn(),
  generateTitlesMock: vi.fn(),
  streamOutlineMock: vi.fn(),
  streamContentMock: vi.fn(),
  recommendImagesMock: vi.fn(),
  searchImagesMock: vi.fn(),
  generateImageMock: vi.fn(),
}))

vi.mock('../lib/auth.js', () => ({
  getSessionUser: getSessionUserMock,
}))

vi.mock('../services/article-generation-dispatch.service.js', () => ({
  generateTitles: generateTitlesMock,
  streamOutline: streamOutlineMock,
  streamContent: streamContentMock,
}))

vi.mock('../services/article-image.service.js', () => ({
  recommendImages: recommendImagesMock,
  searchImages: searchImagesMock,
  generateImage: generateImageMock,
}))

const {
  recommendImagesHandler,
  searchImagesHandler,
  generateImageHandler,
} = await import('./article-generation.controller.js')

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

describe('article generation controller', () => {
  beforeEach(() => {
    getSessionUserMock.mockReset()
    generateTitlesMock.mockReset()
    streamOutlineMock.mockReset()
    streamContentMock.mockReset()
    recommendImagesMock.mockReset()
    searchImagesMock.mockReset()
    generateImageMock.mockReset()

    getSessionUserMock.mockReturnValue({
      id: 'user-1',
      email: 'user@example.com',
      role: 'admin',
    })

    recommendImagesMock.mockResolvedValue({
      recommendedCount: 2,
      placements: [
        {
          position: '开头',
          description: '文章主题封面图',
          searchKeywords: '职场沟通 插画',
          prompt: '一张表现职场沟通主题的现代感插画，简洁、专业',
        },
      ],
    })
    searchImagesMock.mockResolvedValue([
      {
        url: 'https://images.example.com/full-1.jpg',
        thumbnailUrl: 'https://images.example.com/thumb-1.jpg',
        sourceUrl: 'https://example.com/article-1',
        description: '示例图片',
        width: 1200,
        height: 800,
      },
    ])
    generateImageMock.mockResolvedValue({
      imageUrl: 'https://images.example.com/generated-1.png',
      revisedPrompt: '优化后的提示词',
    })
  })

  it('recommends images using the current user', async () => {
    const req = createRequestMock({
      content: '这是一篇关于职场沟通技巧的文章正文，内容足够长，可以用于推荐配图位置。',
      outline: '## 开头\n## 主体\n## 结尾',
      platform: 'wechat',
    })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await recommendImagesHandler(req, res, next)

    expect(recommendImagesMock).toHaveBeenCalledWith(expect.objectContaining({
      content: '这是一篇关于职场沟通技巧的文章正文，内容足够长，可以用于推荐配图位置。',
      outline: '## 开头\n## 主体\n## 结尾',
      platform: 'wechat',
      userId: 'user-1',
      signal: expect.any(AbortSignal),
    }))
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: {
        recommendedCount: 2,
        placements: [
          {
            position: '开头',
            description: '文章主题封面图',
            searchKeywords: '职场沟通 插画',
            prompt: '一张表现职场沟通主题的现代感插画，简洁、专业',
          },
        ],
      },
    })
    expect(next).not.toHaveBeenCalled()
  })

  it('searches images using the current user', async () => {
    const req = createRequestMock({
      keywords: '职场沟通 插画',
      count: 3,
    })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await searchImagesHandler(req, res, next)

    expect(searchImagesMock).toHaveBeenCalledWith(expect.objectContaining({
      keywords: '职场沟通 插画',
      count: 3,
      userId: 'user-1',
      signal: expect.any(AbortSignal),
    }))
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: {
        images: [
          {
            url: 'https://images.example.com/full-1.jpg',
            thumbnailUrl: 'https://images.example.com/thumb-1.jpg',
            sourceUrl: 'https://example.com/article-1',
            description: '示例图片',
            width: 1200,
            height: 800,
          },
        ],
      },
    })
    expect(next).not.toHaveBeenCalled()
  })

  it('generates images using the current user', async () => {
    const req = createRequestMock({
      prompt: '一张表现职场沟通主题的现代感插画，简洁、专业',
      size: '1024x1024',
    })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await generateImageHandler(req, res, next)

    expect(generateImageMock).toHaveBeenCalledWith(expect.objectContaining({
      prompt: '一张表现职场沟通主题的现代感插画，简洁、专业',
      size: '1024x1024',
      userId: 'user-1',
      signal: expect.any(AbortSignal),
    }))
    expect(res.json).toHaveBeenCalledWith({
      success: true,
      data: {
        imageUrl: 'https://images.example.com/generated-1.png',
        revisedPrompt: '优化后的提示词',
      },
    })
    expect(next).not.toHaveBeenCalled()
  })

  it('forwards invalid image recommendation payloads as app errors', async () => {
    const req = createRequestMock({
      content: '太短',
    })
    const res = createResponseMock()
    const next = vi.fn() as NextFunction

    await recommendImagesHandler(req, res, next)

    expect(recommendImagesMock).not.toHaveBeenCalled()
    expect(next).toHaveBeenCalledWith(expect.objectContaining({
      name: 'ZodError',
    }))
  })
})
