import { Router } from 'express'
import {
  downloadBilibiliVideoHandler,
  extractBilibiliVideoHandler,
  proxyBilibiliVideoHandler,
} from '../controllers/bilibili.controller.js'

const bilibiliRouter = Router()

bilibiliRouter.post('/extract-video', extractBilibiliVideoHandler)
bilibiliRouter.get('/proxy/:token', proxyBilibiliVideoHandler)
bilibiliRouter.get('/download/:token', downloadBilibiliVideoHandler)

export { bilibiliRouter }
