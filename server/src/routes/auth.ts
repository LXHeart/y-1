import { Router } from 'express'
import { captchaHandler, loginHandler, logoutHandler, meHandler, registerHandler, sendCodeHandler } from '../controllers/auth.controller.js'
import { requireAuthenticatedUser } from '../lib/auth.js'

export const authRouter = Router()

authRouter.post('/login', loginHandler)
authRouter.post('/register', registerHandler)
authRouter.get('/captcha', captchaHandler)
authRouter.post('/send-code', sendCodeHandler)
authRouter.post('/logout', requireAuthenticatedUser, logoutHandler)
authRouter.get('/me', requireAuthenticatedUser, meHandler)
