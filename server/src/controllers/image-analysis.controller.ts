import type { NextFunction, Request, Response } from 'express'
import { getSessionUser, getAuthenticatedUser, requireAuthenticatedUser } from '../lib/auth.js'
import { AppError } from '../lib/errors.js'
import { logger } from '../lib/logger.js'
import { imageReviewGenerationRequestSchema, imageReviewStepRequestSchema, imageReviewStylePreferencesUpdateSchema, imageReviewStylePreferencesOptimizeSchema, imageReviewStyleSaveRequestSchema, uploadedImageListSchema } from '../schemas/image-analysis.js'
import { imageAnalysisExportRequestSchema } from '../schemas/image-analysis-export.js'
import { analyzeUploadedImages } from '../services/image-analysis.service.js'
import { exportToFeishu } from '../services/feishu-export.service.js'
import { loadSettingsForUser } from '../services/analysis-settings.service.js'
import { loadImageReviewStylePreferences, saveImageReviewStyleFromEdits, saveImageReviewStylePreferences } from '../services/image-review-style.service.js'
import { resolveQwenConfig, draftImageReview, optimizeImageReview, styleRefineImageReview, optimizeImageReviewStylePreferences } from '../services/image-analysis-dispatch.service.js'
import type { ImageReviewGenerationInput, ProviderImageInput } from '../schemas/image-analysis.js'

export async function analyzeImageContentHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  const abortController = new AbortController()
  const abortAnalysis = (): void => {
    abortController.abort()
  }

  req.once('aborted', abortAnalysis)
  res.once('close', abortAnalysis)

  try {
    const files = Array.isArray(req.files) ? req.files : []
    const requestInput = imageReviewGenerationRequestSchema.parse({
      reviewLength: req.body?.reviewLength,
      feelings: req.body?.feelings,
      platform: req.body?.platform,
    })

    res.setHeader('Content-Type', 'text/event-stream')
    res.setHeader('Cache-Control', 'no-cache')
    res.setHeader('Connection', 'keep-alive')
    res.setHeader('X-Accel-Buffering', 'no')

    const writeEvent = (event: Record<string, unknown>): void => {
      res.write(`data: ${JSON.stringify(event)}\n\n`)
    }

    try {
      const data = await analyzeUploadedImages(files.map((file) => ({
        originalName: file.originalname,
        mimeType: file.mimetype,
        size: file.size,
        buffer: file.buffer,
      })), {
        ...requestInput,
        signal: abortController.signal,
        userId: getSessionUser(req)?.id,
        onProgress: (event) => {
          if (abortController.signal.aborted) {
            return
          }
          writeEvent({ type: 'progress', ...event })
        },
      })

      if (!abortController.signal.aborted) {
        writeEvent({ type: 'result', data })
        res.write('data: [DONE]\n\n')
      }
    } catch (error: unknown) {
      if (abortController.signal.aborted) {
        return
      }

      const message = error instanceof AppError ? error.message : '评价生成失败，请稍后重试'
      logger.error({ err: error }, 'Image analysis streaming error')
      writeEvent({ type: 'error', error: message })
    } finally {
      res.end()
    }
  } catch (error: unknown) {
    if (res.headersSent) {
      logger.error({ err: error }, 'Image analysis streaming setup error after headers sent')
      res.end()
    } else {
      next(error)
    }
  } finally {
    req.off('aborted', abortAnalysis)
    res.off('close', abortAnalysis)
  }
}

export async function exportToFeishuHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const user = getSessionUser(req)
    if (!user) {
      throw new AppError('请先登录后再导出到飞书', 401)
    }

    const files = Array.isArray(req.files) ? req.files : []
    const parsed = imageAnalysisExportRequestSchema.parse({
      review: req.body?.review,
      title: req.body?.title,
      tags: req.body?.tags,
      runId: req.body?.runId,
      platform: req.body?.platform,
      reviewLength: req.body?.reviewLength,
      feelings: req.body?.feelings,
    })

    const settings = await loadSettingsForUser(user.id)
    const feishu = settings.integrations?.feishu

    if (!feishu?.appId || !feishu?.appSecret) {
      throw new AppError('飞书应用凭证未配置，请在设置中填写 App ID 和 App Secret', 400)
    }

    const result = await exportToFeishu({
      feishu,
      review: parsed.review,
      title: parsed.title,
      tags: parsed.tags,
      images: files.map((file) => ({
        buffer: file.buffer,
        mimeType: file.mimetype,
        originalName: file.originalname,
      })),
      platform: parsed.platform,
      reviewLength: parsed.reviewLength,
      feelings: parsed.feelings,
      runId: parsed.runId,
    })

    res.json({ success: true, data: result })
  } catch (error: unknown) {
    if (error instanceof AppError) {
      next(error)
      return
    }

    const message = error instanceof Error ? error.message : '导出到飞书失败'
    logger.error({ err: error }, 'Feishu export error')
    next(new AppError(message, 502))
  }
}

export async function saveStyleMemoryHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const user = getAuthenticatedUser(req)
    const parsed = imageReviewStyleSaveRequestSchema.parse(req.body)

    const config = await resolveQwenConfig(user.id)
    const preferences = await saveImageReviewStyleFromEdits(
      user.id,
      parsed.original,
      parsed.edited,
      config,
    )

    res.json({
      success: true,
      data: {
        preferences,
        updatedAt: new Date().toISOString(),
      },
    })
  } catch (error: unknown) {
    if (error instanceof AppError) {
      next(error)
      return
    }

    const message = error instanceof Error ? error.message : '保存风格偏好失败'
    logger.error({ err: error }, 'Save style memory error')
    next(new AppError(message, 500))
  }
}

export async function getStylePreferencesHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const user = getSessionUser(req)
    if (!user) {
      res.json({ success: true, data: { preferences: [] } })
      return
    }

    const preferences = await loadImageReviewStylePreferences(user.id)
    res.json({ success: true, data: { preferences } })
  } catch (error: unknown) {
    if (error instanceof AppError) {
      next(error)
      return
    }

    logger.error({ err: error }, 'Get style preferences error')
    next(new AppError('获取风格偏好失败', 500))
  }
}

export async function draftStepHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  const abortController = new AbortController()
  const abortAnalysis = (): void => { abortController.abort() }
  req.once('aborted', abortAnalysis)
  res.once('close', abortAnalysis)

  try {
    const files = Array.isArray(req.files) ? req.files : []
    const requestInput = imageReviewGenerationRequestSchema.parse({
      reviewLength: req.body?.reviewLength,
      feelings: req.body?.feelings,
      platform: req.body?.platform,
    })

    res.setHeader('Content-Type', 'text/event-stream')
    res.setHeader('Cache-Control', 'no-cache')
    res.setHeader('Connection', 'keep-alive')
    res.setHeader('X-Accel-Buffering', 'no')

    const writeEvent = (event: Record<string, unknown>): void => {
      res.write(`data: ${JSON.stringify(event)}\n\n`)
    }

    try {
      const normalizedImages = uploadedImageListSchema.parse(
        files.map((file) => ({
          originalName: file.originalname,
          mimeType: file.mimetype,
          size: file.size,
          buffer: file.buffer,
        })),
      )
      const providerImages: ProviderImageInput[] = normalizedImages.map((img) => ({
        mimeType: img.mimeType,
        dataUrl: `data:${img.mimeType};base64,${img.buffer.toString('base64')}`,
      }))
      const promptInput: ImageReviewGenerationInput = requestInput

      const data = await draftImageReview(providerImages, promptInput, {
        signal: abortController.signal,
        userId: getSessionUser(req)?.id,
        onProgress: (event) => {
          if (abortController.signal.aborted) return
          writeEvent({ type: 'progress', ...event })
        },
      })

      if (!abortController.signal.aborted) {
        const result = { ...data, imageCount: normalizedImages.length }
        writeEvent({ type: 'result', data: result })
        res.write('data: [DONE]\n\n')
      }
    } catch (error: unknown) {
      if (abortController.signal.aborted) return
      const message = error instanceof AppError ? error.message : '初稿生成失败，请稍后重试'
      logger.error({ err: error }, 'Draft step streaming error')
      writeEvent({ type: 'error', error: message })
    } finally {
      res.end()
    }
  } catch (error: unknown) {
    if (res.headersSent) {
      logger.error({ err: error }, 'Draft step setup error after headers sent')
      res.end()
    } else {
      next(error)
    }
  } finally {
    req.off('aborted', abortAnalysis)
    res.off('close', abortAnalysis)
  }
}

export async function optimizeStepHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const parsed = imageReviewStepRequestSchema.parse(req.body)
    const promptInput: ImageReviewGenerationInput = {
      reviewLength: parsed.reviewLength,
      feelings: parsed.feelings,
      platform: parsed.platform,
    }
    const previousReview = parsed.review

    const data = await optimizeImageReview(previousReview, promptInput, {
      userId: getSessionUser(req)?.id,
    })

    res.json({ success: true, data })
  } catch (error: unknown) {
    if (error instanceof AppError) { next(error); return }
    logger.error({ err: error }, 'Optimize step error')
    next(new AppError('润色优化失败，请稍后重试', 500))
  }
}

export async function styleRefineStepHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const parsed = imageReviewStepRequestSchema.parse(req.body)
    const promptInput: ImageReviewGenerationInput = {
      reviewLength: parsed.reviewLength,
      feelings: parsed.feelings,
      platform: parsed.platform,
    }
    const previousReview = parsed.review

    const data = await styleRefineImageReview(previousReview, promptInput, {
      userId: getSessionUser(req)?.id,
    })

    res.json({ success: true, data })
  } catch (error: unknown) {
    if (error instanceof AppError) { next(error); return }
    logger.error({ err: error }, 'Style refine step error')
    next(new AppError('风格偏好优化失败，请稍后重试', 500))
  }
}

export async function updateStylePreferencesHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const user = getAuthenticatedUser(req)
    const parsed = imageReviewStylePreferencesUpdateSchema.parse(req.body)
    const preferences = await saveImageReviewStylePreferences(user.id, parsed.preferences)

    res.json({ success: true, data: { preferences } })
  } catch (error: unknown) {
    if (error instanceof AppError) { next(error); return }
    logger.error({ err: error }, 'Update style preferences error')
    next(new AppError('更新风格偏好失败', 500))
  }
}

export async function optimizeStylePreferencesHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const user = getAuthenticatedUser(req)
    const parsed = imageReviewStylePreferencesOptimizeSchema.parse(req.body)
    const optimized = await optimizeImageReviewStylePreferences(parsed.preferences, user.id)

    res.json({ success: true, data: { preferences: optimized } })
  } catch (error: unknown) {
    if (error instanceof AppError) { next(error); return }
    logger.error({ err: error }, 'Optimize style preferences error')
    next(new AppError('风格偏好优化失败', 500))
  }
}
