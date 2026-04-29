import { Router } from 'express'
import { streamComedyScriptHandler } from '../controllers/comedy-generation.controller.js'
import { requireAuthenticatedUser } from '../lib/auth.js'
import { createRateLimit } from '../lib/rate-limit.js'

const comedyGenerationRouter = Router()

comedyGenerationRouter.use(requireAuthenticatedUser)
comedyGenerationRouter.use(createRateLimit({
  id: 'comedy-generation',
  max: 10,
  windowMs: 60 * 1000,
  methods: ['POST'],
  message: '脱口秀创作请求过于频繁，请稍后再试。',
}))
comedyGenerationRouter.post('/generate-script', streamComedyScriptHandler)

export { comedyGenerationRouter }
