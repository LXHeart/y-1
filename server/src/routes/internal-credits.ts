import { Router } from 'express'
import { requireInternalKey, rejectForwardedRequest } from '../lib/internal-auth.js'
import { consumeCreditsHandler, refundCreditsHandler } from '../controllers/internal-credits.controller.js'

/**
 * 内部接口路由（草场 intelligence → legacy credits 过渡通道）。
 *
 * 挂载于 `/internal/credits`——刻意在公共 `/api` 树之外（GL-P0-CRED-001），
 * 因为 nginx 只把 `/api/` 反代到 backend，`/internal/` 另有显式 deny。
 * 不进公网，仅容器网络/localhost。
 */
export const internalCreditsRouter: Router = Router()

internalCreditsRouter.use(rejectForwardedRequest, requireInternalKey)
internalCreditsRouter.post('/consume', consumeCreditsHandler)
internalCreditsRouter.post('/refund', refundCreditsHandler)
