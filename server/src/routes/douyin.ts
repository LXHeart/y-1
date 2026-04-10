import { Router } from 'express'
import {
  downloadDouyinAudioHandler,
  downloadDouyinVideoHandler,
  extractDouyinVideoHandler,
  getDouyinSessionHandler,
  logoutDouyinSessionHandler,
  pollDouyinSessionHandler,
  proxyDouyinVideoHandler,
  startDouyinSessionHandler,
} from '../controllers/douyin.controller.js'

const douyinRouter = Router()

douyinRouter.post('/extract-video', extractDouyinVideoHandler)
douyinRouter.get('/proxy/:token', proxyDouyinVideoHandler)
douyinRouter.get('/download/:token', downloadDouyinVideoHandler)
douyinRouter.get('/audio/:token', downloadDouyinAudioHandler)
douyinRouter.get('/session', getDouyinSessionHandler)
douyinRouter.post('/session/start', startDouyinSessionHandler)
douyinRouter.get('/session/poll', pollDouyinSessionHandler)
douyinRouter.post('/session/logout', logoutDouyinSessionHandler)

export { douyinRouter }
