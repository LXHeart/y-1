import type { Request, Response, NextFunction } from 'express'
import { getSessionUser } from '../lib/auth.js'
import { AppError } from '../lib/errors.js'
import { logger } from '../lib/logger.js'
import { requireCredit } from '../lib/credits.js'
import { generateScriptRequestSchema, generateVideoRequestSchema } from '../schemas/video-production.js'
import * as videoProduction from '../services/video-production.service.js'

export async function generateScriptHandler(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const { images, shopName, industryType, targetPlatform, shopAddress, shopDescription, videoStyle, customPrompt } = generateScriptRequestSchema.parse(req.body)

    const charge = await requireCredit(getSessionUser(req)!.id, 'video_production_script')

    res.setHeader('Content-Type', 'text/event-stream')
    res.setHeader('Cache-Control', 'no-cache')
    res.setHeader('Connection', 'keep-alive')
    res.setHeader('X-Accel-Buffering', 'no')

    const controller = new AbortController()
    const abortOnClose = (): void => controller.abort()
    req.on('aborted', abortOnClose)
    res.on('close', () => {
      controller.abort()
      req.removeListener('aborted', abortOnClose)
    })

    try {
      for await (const chunk of videoProduction.streamVideoScript(
        images, shopName, industryType, targetPlatform, videoStyle,
        shopAddress, shopDescription, customPrompt,
        {
          signal: controller.signal,
          userId: getSessionUser(req)?.id,
        },
      )) {
        if (controller.signal.aborted) break
        res.write(`data: ${JSON.stringify({ content: chunk })}\n\n`)
      }

      if (!controller.signal.aborted) {
        res.write('data: [DONE]\n\n')
      }
    } catch (error: unknown) {
      if (controller.signal.aborted) return
      // 上游失败：退回已扣积分（GL-P0-BILL-002）
      await charge.refund('视频脚本生成失败自动退回')
      const message = error instanceof AppError ? error.message : '视频脚本生成失败'
      logger.error({ err: error }, 'Video production script streaming error')
      res.write(`data: ${JSON.stringify({ error: message })}\n\n`)
    } finally {
      res.end()
    }
  } catch (error: unknown) {
    if (res.headersSent) {
      logger.error({ err: error }, 'Video production script setup error after headers sent')
      res.end()
    } else {
      next(error)
    }
  }
}

export function getCapabilitiesHandler(_req: Request, res: Response): void {
  const available = videoProduction.isVideoGenerationAvailable()

  res.json({
    success: true,
    data: {
      videoGeneration: {
        available,
        reason: available ? null : videoProduction.VIDEO_GENERATION_UNAVAILABLE_REASON,
      },
    },
  })
}

export async function generateVideoHandler(
  req: Request,
  res: Response,
  _next: NextFunction,
): Promise<void> {
  const { script, images, videoStyle, shopName, shopAddress } = generateVideoRequestSchema.parse(req.body)

  // 能力未就绪时必须在扣积分之前拦截（GL-P0-BILL-001）
  if (!videoProduction.isVideoGenerationAvailable()) {
    throw new AppError(videoProduction.VIDEO_GENERATION_UNAVAILABLE_REASON, 501)
  }

  const charge = await requireCredit(getSessionUser(req)!.id, 'video_production_video')

  try {
    const result = await videoProduction.generateVideo(script, images, videoStyle, shopName, shopAddress)

    if (!result.videoUrl) {
      throw new AppError('视频生成服务暂未上线', 501)
    }

    res.json({ success: true, data: result })
  } catch (error: unknown) {
    // 上游失败：退回已扣积分（GL-P0-BILL-002）
    await charge.refund('视频生成失败自动退回')
    if (error instanceof AppError) throw error
    logger.error({ err: error }, 'Video generation error')
    throw new AppError('视频生成失败，请稍后重试', 502)
  }
}
