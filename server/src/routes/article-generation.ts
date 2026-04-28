import multer from 'multer'
import { Router } from 'express'
import {
  generateTitlesHandler,
  streamOutlineHandler,
  streamContentHandler,
  recommendImagesHandler,
  searchImagesHandler,
  generateImageHandler,
  serveGeneratedImageHandler,
} from '../controllers/article-generation.controller.js'
import { requireAuthenticatedUser } from '../lib/auth.js'
import { filterUploadedImageFile } from './image-analysis.js'

const GEN_IMAGE_MAX_FILES = 4
const GEN_IMAGE_MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024

const genImageUpload = multer({
  storage: multer.memoryStorage(),
  fileFilter: filterUploadedImageFile,
  limits: {
    files: GEN_IMAGE_MAX_FILES,
    fileSize: GEN_IMAGE_MAX_FILE_SIZE_BYTES,
    fieldSize: 32 * 1024,
    fields: 8,
    parts: GEN_IMAGE_MAX_FILES + 8,
  },
})

const articleGenerationRouter = Router()

articleGenerationRouter.post('/titles', generateTitlesHandler)
articleGenerationRouter.post('/outline', streamOutlineHandler)
articleGenerationRouter.post('/content', streamContentHandler)
articleGenerationRouter.post('/image-recommendations', requireAuthenticatedUser, recommendImagesHandler)
articleGenerationRouter.post('/search-images', requireAuthenticatedUser, searchImagesHandler)
articleGenerationRouter.post('/generate-image', requireAuthenticatedUser, genImageUpload.array('images', GEN_IMAGE_MAX_FILES), generateImageHandler)
articleGenerationRouter.get('/generated-images/:id', serveGeneratedImageHandler)

export { articleGenerationRouter }
