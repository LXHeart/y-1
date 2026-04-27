import multer from 'multer'
import { Router } from 'express'
import type { NextFunction, Request, Response } from 'express'
import { AppError } from '../lib/errors.js'
import { requireAuthenticatedUser } from '../lib/auth.js'
import { analyzeImageContentHandler, exportToFeishuHandler, saveStyleMemoryHandler, getStylePreferencesHandler, draftStepHandler, optimizeStepHandler, styleRefineStepHandler, updateStylePreferencesHandler, optimizeStylePreferencesHandler } from '../controllers/image-analysis.controller.js'
import { ALLOWED_IMAGE_MIME_TYPES, IMAGE_ANALYSIS_MAX_FILE_SIZE_BYTES, IMAGE_ANALYSIS_MAX_IMAGES } from '../schemas/image-analysis.js'

const IMAGE_ANALYSIS_MAX_REQUEST_SIZE_BYTES = IMAGE_ANALYSIS_MAX_IMAGES * IMAGE_ANALYSIS_MAX_FILE_SIZE_BYTES

const imageAnalysisRouter = Router()

export function filterUploadedImageFile(
  _req: Request,
  file: Express.Multer.File,
  callback: multer.FileFilterCallback,
): void {
  if (!ALLOWED_IMAGE_MIME_TYPES.includes(file.mimetype as typeof ALLOWED_IMAGE_MIME_TYPES[number])) {
    callback(new AppError('仅支持 JPG、PNG、WebP 图片', 400))
    return
  }

  callback(null, true)
}

const upload = multer({
  storage: multer.memoryStorage(),
  fileFilter: filterUploadedImageFile,
  limits: {
    files: IMAGE_ANALYSIS_MAX_IMAGES,
    fileSize: IMAGE_ANALYSIS_MAX_FILE_SIZE_BYTES,
    fieldSize: 16 * 1024,
    fields: 8,
    parts: IMAGE_ANALYSIS_MAX_IMAGES + 8,
  },
})

export function rejectOversizedImageUploadRequest(
  req: Request,
  _res: Response,
  next: NextFunction,
): void {
  const contentLengthHeader = req.headers['content-length']
  const contentLength = typeof contentLengthHeader === 'string'
    ? Number(contentLengthHeader)
    : Array.isArray(contentLengthHeader)
      ? Number(contentLengthHeader[0])
      : NaN

  if (Number.isFinite(contentLength) && contentLength > IMAGE_ANALYSIS_MAX_REQUEST_SIZE_BYTES) {
    next(new AppError('图片上传总大小不能超过 30 MB', 400))
    return
  }

  next()
}

imageAnalysisRouter.post(
  '/analyze',
  rejectOversizedImageUploadRequest,
  upload.array('images', IMAGE_ANALYSIS_MAX_IMAGES),
  analyzeImageContentHandler,
)

imageAnalysisRouter.post(
  '/export-feishu',
  requireAuthenticatedUser,
  rejectOversizedImageUploadRequest,
  upload.array('images', IMAGE_ANALYSIS_MAX_IMAGES),
  exportToFeishuHandler,
)

imageAnalysisRouter.post(
  '/save-style-memory',
  requireAuthenticatedUser,
  saveStyleMemoryHandler,
)

imageAnalysisRouter.get(
  '/style-preferences',
  getStylePreferencesHandler,
)

imageAnalysisRouter.put(
  '/style-preferences',
  requireAuthenticatedUser,
  updateStylePreferencesHandler,
)

imageAnalysisRouter.post(
  '/style-preferences/optimize',
  requireAuthenticatedUser,
  optimizeStylePreferencesHandler,
)

imageAnalysisRouter.post(
  '/step/draft',
  rejectOversizedImageUploadRequest,
  upload.array('images', IMAGE_ANALYSIS_MAX_IMAGES),
  draftStepHandler,
)

imageAnalysisRouter.post(
  '/step/optimize',
  optimizeStepHandler,
)

imageAnalysisRouter.post(
  '/step/style-refine',
  styleRefineStepHandler,
)

export { imageAnalysisRouter }
