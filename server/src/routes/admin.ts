import { Router } from 'express'
import { requireAuthenticatedUser, requireAdmin } from '../lib/auth.js'
import { listUsersHandler, adjustCreditsHandler } from '../controllers/admin.controller.js'

/**
 * 已迁 identity-service（Legacy 迁移收尾）。
 * 端点 {@code GET /api/admin/users} + {@code POST /api/admin/adjust-credits} 现由 identity 的
 * {@code AdminUserController} 承载，edge-bff 经 {@code EDGE_ROUTE_ADMIN_USERS_IDENTITY}（默认 true）切流。
 * 本 router 仅作回滚路径保留（flag=false 即回落 legacy）；未删以保持与 credits 迁移同口径的回滚安全。
 */
export const adminRouter = Router()

adminRouter.use(requireAuthenticatedUser, requireAdmin)
adminRouter.get('/users', listUsersHandler)
adminRouter.post('/adjust-credits', adjustCreditsHandler)
