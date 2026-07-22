import { Router } from 'express'
import { getBalanceHandler, getHistoryHandler } from '../controllers/credits.controller.js'
import { requireAuthenticatedUser } from '../lib/auth.js'

export const creditsRouter = Router()

creditsRouter.get('/balance', requireAuthenticatedUser, getBalanceHandler)
creditsRouter.get('/history', requireAuthenticatedUser, getHistoryHandler)
