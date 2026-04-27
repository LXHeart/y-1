import { ZodError } from 'zod'
import { AppError } from '../lib/errors.js'
import { imageReviewGenerationRequestSchema, uploadedImageListSchema } from '../schemas/image-analysis.js'
import type { ImageReviewGenerationInput, UploadedImageInput } from '../schemas/image-analysis.js'
import { analyzeImageContent } from './image-analysis-dispatch.service.js'
import type { ImageAnalysisProgressEvent, ImageAnalysisResult } from './providers/types.js'

interface AnalyzeUploadedImagesOptions {
  reviewLength?: number
  feelings?: string
  platform?: 'taobao' | 'dianping'
  signal?: AbortSignal
  userId?: string
  onProgress?: (event: ImageAnalysisProgressEvent) => void
}

export interface UploadedImageAnalysisResult extends ImageAnalysisResult {
  imageCount: number
}

function toDataUrl(image: UploadedImageInput): string {
  return `data:${image.mimeType};base64,${image.buffer.toString('base64')}`
}

export async function analyzeUploadedImages(
  images: UploadedImageInput[],
  options: AnalyzeUploadedImagesOptions = {},
): Promise<UploadedImageAnalysisResult> {
  let normalizedImages: UploadedImageInput[]
  let promptInput: ImageReviewGenerationInput

  try {
    normalizedImages = uploadedImageListSchema.parse(images)
    promptInput = imageReviewGenerationRequestSchema.parse({
      reviewLength: options.reviewLength,
      feelings: options.feelings,
      platform: options.platform,
    })
  } catch (error: unknown) {
    if (error instanceof ZodError) {
      const firstIssue = error.issues[0]
      throw new AppError(firstIssue?.message || '图片上传参数无效', 400)
    }

    throw error
  }

  const providerImages = normalizedImages.map((image) => ({
    mimeType: image.mimeType,
    dataUrl: toDataUrl(image),
  }))

  const result = await analyzeImageContent(providerImages, promptInput, {
    signal: options.signal,
    userId: options.userId,
    ...(options.onProgress ? { onProgress: options.onProgress } : {}),
  })

  return {
    ...result,
    imageCount: normalizedImages.length,
  }
}
