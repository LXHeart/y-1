import { Router } from 'express'
import {
  analyzeBilibiliVideoHandler,
  downloadBilibiliVideoHandler,
  extractBilibiliVideoHandler,
  proxyBilibiliVideoHandler,
} from '../controllers/bilibili.controller.js'

const bilibiliRouter = Router()

bilibiliRouter.post('/extract-video', extractBilibiliVideoHandler)
bilibiliRouter.post('/analyze-video', analyzeBilibiliVideoHandler)
bilibiliRouter.get('/proxy/:token', proxyBilibiliVideoHandler)
bilibiliRouter.get('/download/:token', downloadBilibiliVideoHandler)

export { bilibiliRouter }
