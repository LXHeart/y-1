import type { Request, Response, NextFunction } from 'express'
import { createReadStream } from 'node:fs'
import { getSessionUser } from '../lib/auth.js'
import { AppError } from '../lib/errors.js'
import { logger } from '../lib/logger.js'
import {
  generateTitlesRequestSchema,
  generateOutlineRequestSchema,
  generateContentRequestSchema,
  imageRecommendationRequestSchema,
  imageSearchRequestSchema,
  imageGenerateRequestSchema,
} from '../schemas/article-creation.js'
import type { ProviderImageInput } from '../schemas/image-analysis.js'
import * as dispatch from '../services/article-generation-dispatch.service.js'
import * as articleImageService from '../services/article-image.service.js'

export async function generateTitlesHandler(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const { topic, platform } = generateTitlesRequestSchema.parse(req.body)

    const controller = new AbortController()
    const abortOnClose = (): void => controller.abort()
    res.on('close', abortOnClose)

    const titles = await dispatch.generateTitles(topic, {
      signal: controller.signal,
      userId: getSessionUser(req)?.id,
      platform,
    })

    res.json({ success: true, data: { titles } })
  } catch (error: unknown) {
    next(error)
  }
}

export async function streamOutlineHandler(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const { topic, title, platform } = generateOutlineRequestSchema.parse(req.body)

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
      for await (const chunk of dispatch.streamOutline(topic, title, {
        signal: controller.signal,
      userId: getSessionUser(req)?.id,
      platform,
      })) {
        if (controller.signal.aborted) break
        res.write(`data: ${JSON.stringify({ content: chunk })}\n\n`)
      }

      if (!controller.signal.aborted) {
        res.write('data: [DONE]\n\n')
      }
    } catch (error: unknown) {
      if (controller.signal.aborted) return
      const message = error instanceof AppError ? error.message : '大纲生成失败'
      logger.error({ err: error }, 'Outline streaming error')
      res.write(`data: ${JSON.stringify({ error: message })}\n\n`)
    } finally {
      res.end()
    }
  } catch (error: unknown) {
    if (res.headersSent) {
      logger.error({ err: error }, 'Outline streaming setup error after headers sent')
      res.end()
    } else {
      next(error)
    }
  }
}

export async function streamContentHandler(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const { topic, title, outline, platform } = generateContentRequestSchema.parse(req.body)

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
      for await (const chunk of dispatch.streamContent(topic, title, outline, {
        signal: controller.signal,
        userId: getSessionUser(req)?.id,
        platform,
      })) {
        if (controller.signal.aborted) break
        res.write(`data: ${JSON.stringify({ content: chunk })}\n\n`)
      }

      if (!controller.signal.aborted) {
        res.write('data: [DONE]\n\n')
      }
    } catch (error: unknown) {
      if (controller.signal.aborted) return
      const message = error instanceof AppError ? error.message : '正文生成失败'
      logger.error({ err: error }, 'Content streaming error')
      res.write(`data: ${JSON.stringify({ error: message })}\n\n`)
    } finally {
      res.end()
    }
  } catch (error: unknown) {
    if (res.headersSent) {
      logger.error({ err: error }, 'Content streaming setup error after headers sent')
      res.end()
    } else {
      next(error)
    }
  }
}

export async function recommendImagesHandler(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const { content, outline, platform } = imageRecommendationRequestSchema.parse(req.body)
    const controller = new AbortController()
    const abortOnClose = (): void => controller.abort()
    req.on('aborted', abortOnClose)
    res.on('close', abortOnClose)

    try {
      const result = await articleImageService.recommendImages({
        content,
        outline,
        platform,
        userId: getSessionUser(req)?.id,
        signal: controller.signal,
      })

      res.json({ success: true, data: result })
    } finally {
      req.removeListener('aborted', abortOnClose)
      res.removeListener('close', abortOnClose)
    }
  } catch (error: unknown) {
    next(error)
  }
}

export async function searchImagesHandler(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const { keywords, count } = imageSearchRequestSchema.parse(req.body)
    const controller = new AbortController()
    const abortOnClose = (): void => controller.abort()
    req.on('aborted', abortOnClose)
    res.on('close', abortOnClose)

    try {
      const images = await articleImageService.searchImages({
        keywords,
        count,
        userId: getSessionUser(req)?.id,
        signal: controller.signal,
      })

      res.json({ success: true, data: { images } })
    } finally {
      req.removeListener('aborted', abortOnClose)
      res.removeListener('close', abortOnClose)
    }
  } catch (error: unknown) {
    next(error)
  }
}

export async function generateImageHandler(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const { prompt, size } = imageGenerateRequestSchema.parse({
      prompt: req.body?.prompt,
      size: req.body?.size,
    })
    const files = Array.isArray(req.files) ? req.files : []
    const images: ProviderImageInput[] = files.map((file) => ({
      mimeType: file.mimetype,
      dataUrl: `data:${file.mimetype};base64,${file.buffer.toString('base64')}`,
    }))

    const controller = new AbortController()
    const abortOnClose = (): void => controller.abort()
    req.on('aborted', abortOnClose)
    res.on('close', abortOnClose)

    try {
      const result = await articleImageService.generateImage({
        prompt,
        size,
        images: images.length > 0 ? images : undefined,
        userId: getSessionUser(req)?.id,
        signal: controller.signal,
      })

      res.json({ success: true, data: result })
    } finally {
      req.removeListener('aborted', abortOnClose)
      res.removeListener('close', abortOnClose)
    }
  } catch (error: unknown) {
    next(error)
  }
}

const GENERATED_IMAGE_ID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u

export async function serveGeneratedImageHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const id = typeof req.params.id === 'string' ? req.params.id : undefined
    if (!id || !GENERATED_IMAGE_ID_REGEX.test(id)) {
      throw new AppError('图片不存在', 404)
    }

    const filePath = articleImageService.resolveGeneratedImagePath(id)
    if (!filePath) {
      throw new AppError('图片不存在或已过期', 404)
    }

    res.setHeader('Content-Type', 'image/png')
    res.setHeader('Cache-Control', 'private, max-age=1800')

    const stream = createReadStream(filePath)
    stream.on('error', (err) => {
      logger.error({ err, filePath }, 'Image stream error')
      if (!res.headersSent) {
        next(new AppError('图片读取失败', 500))
      } else {
        res.end()
      }
    })
    stream.pipe(res)
  } catch (error: unknown) {
    next(error)
  }
}
