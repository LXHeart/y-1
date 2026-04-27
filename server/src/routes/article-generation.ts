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

const articleGenerationRouter = Router()

articleGenerationRouter.post('/titles', generateTitlesHandler)
articleGenerationRouter.post('/outline', streamOutlineHandler)
articleGenerationRouter.post('/content', streamContentHandler)
articleGenerationRouter.post('/image-recommendations', requireAuthenticatedUser, recommendImagesHandler)
articleGenerationRouter.post('/search-images', requireAuthenticatedUser, searchImagesHandler)
articleGenerationRouter.post('/generate-image', requireAuthenticatedUser, generateImageHandler)
articleGenerationRouter.get('/generated-images/:id', serveGeneratedImageHandler)

export { articleGenerationRouter }
