import { Router } from 'express'
import { requireInternalKey } from '../lib/internal-auth.js'
import { consumeCreditsHandler } from '../controllers/internal-credits.controller.js'

/**
 * 内部接口路由（草场 intelligence → legacy credits 过渡通道）。
 * 挂载于 `/api/internal/credits`；不进公网，仅容器网络/localhost。
 */
export const internalCreditsRouter: Router = Router()
internalCreditsRouter.post('/consume', requireInternalKey, consumeCreditsHandler)
