import { describe, expect, it, vi, beforeEach } from 'vitest'
import type { NextFunction, Request, Response } from 'express'

const {
  getSessionUserMock,
  requireCreditMock,
  refundMock,
  generateVideoMock,
  isVideoGenerationAvailableMock,
  streamVideoScriptMock,
} = vi.hoisted(() => ({
  getSessionUserMock: vi.fn(),
  requireCreditMock: vi.fn(),
  refundMock: vi.fn(),
  generateVideoMock: vi.fn(),
  isVideoGenerationAvailableMock: vi.fn(),
  streamVideoScriptMock: vi.fn(),
}))

vi.mock('../lib/auth.js', () => ({
  getSessionUser: getSessionUserMock,
}))

vi.mock('../lib/credits.js', () => ({
  requireCredit: requireCreditMock,
}))

vi.mock('../services/video-production.service.js', () => ({
  generateVideo: generateVideoMock,
  isVideoGenerationAvailable: isVideoGenerationAvailableMock,
  VIDEO_GENERATION_UNAVAILABLE_REASON: '视频生成服务暂未上线',
  streamVideoScript: streamVideoScriptMock,
}))

const { generateVideoHandler, getCapabilitiesHandler, generateScriptHandler } = await import('./video-production.controller.js')

type TestResponse = Response & { jsonPayload: unknown }

function createResponse(): TestResponse {
  const res = {
    jsonPayload: undefined as unknown,
    json(payload: unknown) {
      this.jsonPayload = payload
      return this
    },
    headersSent: false,
  }
  return res as unknown as TestResponse
}

type SseResponse = Response & { written: string[] }

function createSseResponse(): SseResponse {
  const res = {
    written: [] as string[],
    headersSent: false,
    setHeader: vi.fn(),
    write(chunk: string) {
      this.written.push(chunk)
      return true
    },
    end: vi.fn(),
    on: vi.fn(),
  }
  return res as unknown as SseResponse
}

function createSseRequest(body: unknown): Request {
  return { body, on: vi.fn(), removeListener: vi.fn() } as unknown as Request
}

const VALID_BODY = {
  script: '【镜头1】(3秒) 画面：门店外景，旁白：欢迎光临本店，这里有最地道的风味。',
  images: ['aGVsbG8='],
  videoStyle: '烟火纪实' as const,
  shopName: '测试小馆',
}

const SCRIPT_BODY = {
  images: ['aGVsbG8='],
  shopName: '测试小馆',
  industryType: '餐饮' as const,
  targetPlatform: 'xiaohongshu' as const,
  videoStyle: '烟火纪实' as const,
}

describe('generateVideoHandler credit gating', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getSessionUserMock.mockReturnValue({ id: 'user-1' })
    requireCreditMock.mockResolvedValue({
      userId: 'user-1',
      feature: 'video_production_video',
      operationId: 'op-1',
      refund: refundMock,
    })
    refundMock.mockResolvedValue(undefined)
  })

  it('能力不可用时抛 501 且绝不扣积分', async () => {
    isVideoGenerationAvailableMock.mockReturnValue(false)

    const req = { body: VALID_BODY } as Request

    await expect(
      generateVideoHandler(req, createResponse(), (() => {}) as NextFunction),
    ).rejects.toMatchObject({ statusCode: 501, message: '视频生成服务暂未上线' })

    expect(requireCreditMock).not.toHaveBeenCalled()
    expect(generateVideoMock).not.toHaveBeenCalled()
  })

  it('请求参数无效时不扣积分', async () => {
    isVideoGenerationAvailableMock.mockReturnValue(false)

    const req = { body: { ...VALID_BODY, script: '太短' } } as Request

    await expect(
      generateVideoHandler(req, createResponse(), (() => {}) as NextFunction),
    ).rejects.toBeDefined()

    expect(requireCreditMock).not.toHaveBeenCalled()
  })

  it('能力可用时才扣积分并调用服务', async () => {
    isVideoGenerationAvailableMock.mockReturnValue(true)
    generateVideoMock.mockResolvedValue({ videoUrl: 'https://cdn.example.com/v.mp4', taskId: 't-1' })

    const res = createResponse()
    await generateVideoHandler({ body: VALID_BODY } as Request, res, (() => {}) as NextFunction)

    expect(requireCreditMock).toHaveBeenCalledWith('user-1', 'video_production_video')
    expect(generateVideoMock).toHaveBeenCalledOnce()
    expect(res.jsonPayload).toMatchObject({
      success: true,
      data: { videoUrl: 'https://cdn.example.com/v.mp4' },
    })
  })
})

// GL-P0-BILL-002：上游失败必须退回已扣积分，用户不为失败调用付费
describe('失败注入：积分退回', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getSessionUserMock.mockReturnValue({ id: 'user-1' })
    requireCreditMock.mockResolvedValue({
      userId: 'user-1',
      feature: 'video_production_video',
      operationId: 'op-1',
      refund: refundMock,
    })
    refundMock.mockResolvedValue(undefined)
  })

  it('generate-video 上游失败时退回积分', async () => {
    isVideoGenerationAvailableMock.mockReturnValue(true)
    generateVideoMock.mockRejectedValue(new Error('provider down'))

    await expect(
      generateVideoHandler({ body: VALID_BODY } as Request, createResponse(), (() => {}) as NextFunction),
    ).rejects.toBeDefined()

    expect(refundMock).toHaveBeenCalledOnce()
  })

  it('generate-video 成功时不退积分', async () => {
    isVideoGenerationAvailableMock.mockReturnValue(true)
    generateVideoMock.mockResolvedValue({ videoUrl: 'https://cdn.example.com/v.mp4', taskId: 't-1' })

    await generateVideoHandler({ body: VALID_BODY } as Request, createResponse(), (() => {}) as NextFunction)

    expect(refundMock).not.toHaveBeenCalled()
  })

  it('generate-video 返回空 videoUrl（501）时也退积分', async () => {
    isVideoGenerationAvailableMock.mockReturnValue(true)
    generateVideoMock.mockResolvedValue({ videoUrl: null })

    await expect(
      generateVideoHandler({ body: VALID_BODY } as Request, createResponse(), (() => {}) as NextFunction),
    ).rejects.toMatchObject({ statusCode: 501 })

    expect(refundMock).toHaveBeenCalledOnce()
  })

  it('generate-script SSE 上游失败时退回积分并发 error 帧', async () => {
    streamVideoScriptMock.mockImplementation(async function* () {
      yield '【镜头1】'
      throw new Error('provider down')
    })

    const res = createSseResponse()
    await generateScriptHandler(createSseRequest(SCRIPT_BODY), res, (() => {}) as NextFunction)

    expect(refundMock).toHaveBeenCalledOnce()
    expect(res.written.some((chunk) => chunk.includes('"error"'))).toBe(true)
  })

  it('generate-script SSE 正常完成时不退积分', async () => {
    streamVideoScriptMock.mockImplementation(async function* () {
      yield '【镜头1】'
    })

    const res = createSseResponse()
    await generateScriptHandler(createSseRequest(SCRIPT_BODY), res, (() => {}) as NextFunction)

    expect(refundMock).not.toHaveBeenCalled()
    expect(res.written.some((chunk) => chunk.includes('[DONE]'))).toBe(true)
    expect(streamVideoScriptMock).toHaveBeenCalledWith(
      SCRIPT_BODY.images,
      SCRIPT_BODY.shopName,
      SCRIPT_BODY.industryType,
      SCRIPT_BODY.targetPlatform,
      SCRIPT_BODY.videoStyle,
      undefined,
      undefined,
      undefined,
      expect.objectContaining({ userId: 'user-1' }),
    )
  })
})

describe('getCapabilitiesHandler', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('不可用时返回 available:false 与原因', () => {
    isVideoGenerationAvailableMock.mockReturnValue(false)

    const res = createResponse()
    getCapabilitiesHandler({} as Request, res)

    expect(res.jsonPayload).toEqual({
      success: true,
      data: {
        videoGeneration: {
          available: false,
          reason: '视频生成服务暂未上线',
        },
      },
    })
  })

  it('可用时返回 available:true 且 reason 为 null', () => {
    isVideoGenerationAvailableMock.mockReturnValue(true)

    const res = createResponse()
    getCapabilitiesHandler({} as Request, res)

    expect(res.jsonPayload).toEqual({
      success: true,
      data: {
        videoGeneration: {
          available: true,
          reason: null,
        },
      },
    })
  })
})

describe('video-production.service 真实 gate', () => {
  it('Seedance 未接入时 isVideoGenerationAvailable 为 false 且 generateVideo 抛 501', async () => {
    vi.doUnmock('../services/video-production.service.js')
    vi.resetModules()

    const actual = await vi.importActual<typeof import('../services/video-production.service.js')>(
      '../services/video-production.service.js',
    )

    expect(actual.isVideoGenerationAvailable()).toBe(false)
    await expect(
      actual.generateVideo(VALID_BODY.script, VALID_BODY.images, VALID_BODY.videoStyle, VALID_BODY.shopName),
    ).rejects.toMatchObject({ statusCode: 501 })
  })
})
