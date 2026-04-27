import { Router } from 'express'
import {
  analyzeDouyinVideoHandler,
  downloadDouyinAudioHandler,
  downloadDouyinVideoHandler,
  extractDouyinVideoHandler,
  getDouyinHotItemsHandler,
  getDouyinSessionHandler,
  logoutDouyinSessionHandler,
  pollDouyinSessionHandler,
  proxyDouyinVideoHandler,
  serveDouyinAnalysisMediaHandler,
  startDouyinSessionHandler,
} from '../controllers/douyin.controller.js'

const douyinRouter = Router()

douyinRouter.get('/hot-items', getDouyinHotItemsHandler)
douyinRouter.post('/extract-video', extractDouyinVideoHandler)
douyinRouter.post('/analyze-video', analyzeDouyinVideoHandler)
douyinRouter.get('/analysis-media/:id', serveDouyinAnalysisMediaHandler)
douyinRouter.get('/proxy/:token', proxyDouyinVideoHandler)
douyinRouter.get('/download/:token', downloadDouyinVideoHandler)
douyinRouter.get('/audio/:token', downloadDouyinAudioHandler)
douyinRouter.get('/session', getDouyinSessionHandler)
douyinRouter.post('/session/start', startDouyinSessionHandler)
douyinRouter.get('/session/poll', pollDouyinSessionHandler)
douyinRouter.post('/session/logout', logoutDouyinSessionHandler)

export { douyinRouter }
