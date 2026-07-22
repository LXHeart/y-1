import { Router } from 'express'
import { requireAuthenticatedUser, requireAdmin } from '../lib/auth.js'
import { listUsersHandler, adjustCreditsHandler } from '../controllers/admin.controller.js'

export const adminRouter = Router()

adminRouter.use(requireAuthenticatedUser, requireAdmin)
adminRouter.get('/users', listUsersHandler)
adminRouter.post('/adjust-credits', adjustCreditsHandler)
