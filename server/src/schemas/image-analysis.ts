import { Buffer } from 'node:buffer'
import { z } from 'zod'

export const IMAGE_ANALYSIS_MAX_IMAGES = 6
export const IMAGE_ANALYSIS_MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024
export const IMAGE_REVIEW_MIN_LENGTH = 15
export const IMAGE_REVIEW_MAX_LENGTH = 300
export const IMAGE_REVIEW_FEELINGS_MAX_LENGTH = 200
export const ALLOWED_IMAGE_MIME_TYPES = ['image/jpeg', 'image/png', 'image/webp'] as const

export interface UploadedImageInput {
  originalName: string
  mimeType: string
  size: number
  buffer: Buffer
}

export interface ProviderImageInput {
  mimeType: string
  dataUrl: string
}

export type ReviewPlatform = 'taobao' | 'dianping'

export interface ImageReviewGenerationInput {
  reviewLength: number
  feelings?: string
  platform?: ReviewPlatform
  stylePreferences?: string
}

export const imageReviewStyleSaveRequestSchema = z.object({
  original: z.object({
    review: z.string().trim().min(1),
    title: z.string().trim().max(200).optional(),
    tags: z.array(z.string().trim().min(1)).optional(),
  }),
  edited: z.object({
    review: z.string().trim().min(1),
    title: z.string().trim().max(200).optional(),
    tags: z.array(z.string().trim().min(1)).optional(),
  }),
})

export const imageReviewStepRequestSchema = z.object({
  review: z.string().trim().min(1),
  title: z.string().trim().max(200).optional(),
  tags: z.array(z.string().trim().min(1)).optional(),
  reviewLength: z.coerce.number()
    .int('评价字数必须是整数')
    .refine((v) => v === 0 || (v >= IMAGE_REVIEW_MIN_LENGTH && v <= IMAGE_REVIEW_MAX_LENGTH), `评价字数需在 ${IMAGE_REVIEW_MIN_LENGTH}-${IMAGE_REVIEW_MAX_LENGTH} 之间，或填 0 不限制`)
    .default(0),
  feelings: z.string()
    .trim()
    .max(IMAGE_REVIEW_FEELINGS_MAX_LENGTH, `感受内容不能超过 ${IMAGE_REVIEW_FEELINGS_MAX_LENGTH} 字`)
    .optional()
    .transform((value) => value || undefined),
  platform: z.enum(['taobao', 'dianping'])
    .default('taobao'),
})

export const imageReviewStylePreferencesUpdateSchema = z.object({
  preferences: z.array(z.string().trim().min(1)).min(0).max(100, '风格偏好最多 100 条'),
})

export const imageReviewStylePreferencesOptimizeSchema = z.object({
  preferences: z.array(z.string().trim().min(1)).min(1, '至少需要 1 条风格偏好').max(100),
})

function isJpegBuffer(buffer: Buffer): boolean {
  return buffer.length >= 3
    && buffer[0] === 0xff
    && buffer[1] === 0xd8
    && buffer[2] === 0xff
}

function isPngBuffer(buffer: Buffer): boolean {
  return buffer.length >= 8
    && buffer[0] === 0x89
    && buffer[1] === 0x50
    && buffer[2] === 0x4e
    && buffer[3] === 0x47
    && buffer[4] === 0x0d
    && buffer[5] === 0x0a
    && buffer[6] === 0x1a
    && buffer[7] === 0x0a
}

function isWebpBuffer(buffer: Buffer): boolean {
  return buffer.length >= 12
    && buffer.subarray(0, 4).toString('ascii') === 'RIFF'
    && buffer.subarray(8, 12).toString('ascii') === 'WEBP'
}

function matchesImageBufferSignature(mimeType: string, buffer: Buffer): boolean {
  if (mimeType === 'image/jpeg') {
    return isJpegBuffer(buffer)
  }

  if (mimeType === 'image/png') {
    return isPngBuffer(buffer)
  }

  if (mimeType === 'image/webp') {
    return isWebpBuffer(buffer)
  }

  return false
}

const uploadedImageSchema = z.object({
  originalName: z.string().trim().min(1, '缺少图片文件名'),
  mimeType: z.string().trim().refine((value) => ALLOWED_IMAGE_MIME_TYPES.includes(value as typeof ALLOWED_IMAGE_MIME_TYPES[number]), '仅支持 JPG、PNG、WebP 图片'),
  size: z.number().int().positive().max(IMAGE_ANALYSIS_MAX_FILE_SIZE_BYTES, '单张图片不能超过 5 MB'),
  buffer: z.instanceof(Buffer),
}).superRefine((value, ctx) => {
  if (!matchesImageBufferSignature(value.mimeType, value.buffer)) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      path: ['buffer'],
      message: '图片文件内容与类型不匹配',
    })
  }
})

export const uploadedImageListSchema = z.array(uploadedImageSchema)
  .min(1, '请至少上传 1 张图片')
  .max(IMAGE_ANALYSIS_MAX_IMAGES, '最多上传 6 张图片')

export const imageReviewGenerationRequestSchema = z.object({
  reviewLength: z.coerce.number()
    .int('评价字数必须是整数')
    .refine((v) => v === 0 || (v >= IMAGE_REVIEW_MIN_LENGTH && v <= IMAGE_REVIEW_MAX_LENGTH), `评价字数需在 ${IMAGE_REVIEW_MIN_LENGTH}-${IMAGE_REVIEW_MAX_LENGTH} 之间，或填 0 不限制`)
    .default(0),
  feelings: z.string()
    .trim()
    .max(IMAGE_REVIEW_FEELINGS_MAX_LENGTH, `感受内容不能超过 ${IMAGE_REVIEW_FEELINGS_MAX_LENGTH} 字`)
    .optional()
    .transform((value) => value || undefined),
  platform: z.enum(['taobao', 'dianping'])
    .default('taobao'),
})
