import { Router } from 'express'
import { generateScriptHandler, generateVideoHandler } from '../controllers/video-production.controller.js'
import { requireAuthenticatedUser } from '../lib/auth.js'
import { createRateLimit } from '../lib/rate-limit.js'

const videoProductionRouter = Router()

videoProductionRouter.use(requireAuthenticatedUser)
videoProductionRouter.use(createRateLimit({
  id: 'video-production',
  max: 10,
  windowMs: 60 * 1000,
  methods: ['POST'],
  message: '视频制作请求过于频繁，请稍后再试。',
}))
videoProductionRouter.post('/generate-script', generateScriptHandler)
videoProductionRouter.post('/generate-video', generateVideoHandler)

export { videoProductionRouter }
