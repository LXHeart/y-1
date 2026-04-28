import type { Request, Response, NextFunction } from 'express'
import { getAuthenticatedUser } from '../lib/auth.js'
import {
  adaptContentRequestSchema,
  generateAssetImageRequestSchema,
  generateAllAssetImagesRequestSchema,
  generateSceneImageRequestSchema,
  generateAllSceneImagesRequestSchema,
} from '../schemas/video-recreation.js'
import { adaptVideoContent } from '../services/video-recreation-adaptation.service.js'
import * as videoRecreationImageService from '../services/video-recreation-image.service.js'
import type { ProviderImageInput } from '../schemas/image-analysis.js'

function createAbortContext(req: Request, res: Response): {
  signal: AbortSignal
  cleanup: () => void
} {
  const controller = new AbortController()
  const abortOnClose = (): void => controller.abort()

  req.on('aborted', abortOnClose)
  res.on('close', abortOnClose)

  return {
    signal: controller.signal,
    cleanup: () => {
      req.removeListener('aborted', abortOnClose)
      res.removeListener('close', abortOnClose)
    },
  }
}

export async function adaptContentHandler(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const files = Array.isArray(req.files) ? req.files : []

    let bodyToParse = req.body
    if (typeof req.body?.extractedContent === 'string') {
      try {
        bodyToParse = { ...req.body, extractedContent: JSON.parse(req.body.extractedContent) }
      } catch {
        bodyToParse = req.body
      }
    }
    if (typeof req.body?.userInstructions === 'string') {
      try {
        bodyToParse = { ...bodyToParse, userInstructions: JSON.parse(req.body.userInstructions) }
      } catch {
        bodyToParse = { ...bodyToParse, userInstructions: undefined }
      }
    }

    const { platform, proxyVideoUrl, extractedContent, userInstructions } = adaptContentRequestSchema.parse(bodyToParse)

    const images: ProviderImageInput[] = files.map((file) => ({
      mimeType: file.mimetype,
      dataUrl: `data:${file.mimetype};base64,${file.buffer.toString('base64')}`,
    }))

    const abortContext = createAbortContext(req, res)

    try {
      const data = await adaptVideoContent({
        platform,
        proxyVideoUrl,
        extractedContent,
        userInstructions,
        images: images.length > 0 ? images : undefined,
        userId: getAuthenticatedUser(req).id,
        signal: abortContext.signal,
      })

      res.json({ success: true, data })
    } finally {
      abortContext.cleanup()
    }
  } catch (error: unknown) {
    next(error)
  }
}

export async function generateAssetImageHandler(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const parsed = generateAssetImageRequestSchema.parse(req.body)
    const abortContext = createAbortContext(req, res)

    try {
      const data = await videoRecreationImageService.generateAssetImage({
        ...parsed,
        userId: getAuthenticatedUser(req).id,
        signal: abortContext.signal,
      })

      res.json({ success: true, data })
    } finally {
      abortContext.cleanup()
    }
  } catch (error: unknown) {
    next(error)
  }
}

export async function generateAllAssetImagesHandler(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const parsed = generateAllAssetImagesRequestSchema.parse(req.body)
    const abortContext = createAbortContext(req, res)

    try {
      const images = await videoRecreationImageService.generateAllAssetImages({
        ...parsed,
        userId: getAuthenticatedUser(req).id,
        signal: abortContext.signal,
      })

      res.json({ success: true, data: { images } })
    } finally {
      abortContext.cleanup()
    }
  } catch (error: unknown) {
    next(error)
  }
}

export async function generateSceneImageHandler(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const { scene, overallStyle, size } = generateSceneImageRequestSchema.parse(req.body)
    const abortContext = createAbortContext(req, res)

    try {
      const result = await videoRecreationImageService.generateSceneImage({
        scene,
        overallStyle,
        size,
        userId: getAuthenticatedUser(req).id,
        signal: abortContext.signal,
      })

      res.json({ success: true, data: result })
    } finally {
      abortContext.cleanup()
    }
  } catch (error: unknown) {
    next(error)
  }
}

export async function generateAllSceneImagesHandler(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const { scenes, overallStyle, size } = generateAllSceneImagesRequestSchema.parse(req.body)
    const abortContext = createAbortContext(req, res)

    try {
      const images = await videoRecreationImageService.generateAllSceneImages({
        scenes,
        overallStyle,
        size,
        userId: getAuthenticatedUser(req).id,
        signal: abortContext.signal,
      })

      res.json({ success: true, data: { images } })
    } finally {
      abortContext.cleanup()
    }
  } catch (error: unknown) {
    next(error)
  }
}
