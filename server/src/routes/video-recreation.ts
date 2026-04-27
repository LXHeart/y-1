import { Router } from 'express'
import {
  adaptContentHandler,
  generateAssetImageHandler,
  generateAllAssetImagesHandler,
  generateSceneImageHandler,
  generateAllSceneImagesHandler,
} from '../controllers/video-recreation.controller.js'
import { requireAuthenticatedUser } from '../lib/auth.js'
import { createRateLimit } from '../lib/rate-limit.js'

const videoRecreationRouter = Router()
const videoRecreationRateLimit = createRateLimit({
  id: 'video-recreation',
  max: 10,
  windowMs: 60 * 1000,
  methods: ['POST'],
  message: '视频改编请求过于频繁，请稍后再试。',
})
const videoRecreationBatchRateLimit = createRateLimit({
  id: 'video-recreation-batch',
  max: 2,
  windowMs: 60 * 1000,
  methods: ['POST'],
  message: '批量出图请求过于频繁，请稍后再试。',
})

videoRecreationRouter.use(requireAuthenticatedUser)
videoRecreationRouter.use(videoRecreationRateLimit)
videoRecreationRouter.post('/adapt-content', adaptContentHandler)
videoRecreationRouter.post('/generate-asset-image', generateAssetImageHandler)
videoRecreationRouter.post('/generate-all-asset-images', videoRecreationBatchRateLimit, generateAllAssetImagesHandler)
videoRecreationRouter.post('/generate-scene-image', generateSceneImageHandler)
videoRecreationRouter.post('/generate-all-scene-images', videoRecreationBatchRateLimit, generateAllSceneImagesHandler)

export { videoRecreationRouter }
