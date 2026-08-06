import { Router } from 'express'
import { getAnalysisSettingsHandler, updateAnalysisSettingsHandler, listModelsHandler, verifyModelHandler, getHomepageSettingsHandler, updateHomepageSettingsHandler } from '../controllers/settings.controller.js'
import { requireAuthenticatedUser } from '../lib/auth.js'

/**
 * 已迁 intelligence-service（Legacy 迁移收尾）。
 * `/api/settings/analysis`（GET/PUT）、`/analysis/models`、`/analysis/verify-model`、`/homepage`
 * 现由 intelligence 的 {@code SettingsController} 承载（读写同一张 `user_settings` 表），
 * edge-bff 经 {@code EDGE_ROUTE_SETTINGS_INTELLIGENCE}（默认 true）切流。
 * 本 router 仅作回滚路径保留（flag=false 即回落 legacy）；未删以保持与 admin/credits 迁移同口径的回滚安全。
 *
 * ⚠️ 后端 AI 调用链（`resolveFeatureProviderConfig`）仍读 legacy 侧同一张表，故两侧行为一致；
 * 待 legacy AI 端点全部切到 intelligence 后再统一。
 */
export const settingsRouter = Router()

settingsRouter.use(requireAuthenticatedUser)
settingsRouter.get('/analysis', getAnalysisSettingsHandler)
settingsRouter.put('/analysis', updateAnalysisSettingsHandler)
settingsRouter.post('/analysis/models', listModelsHandler)
settingsRouter.post('/analysis/verify-model', verifyModelHandler)
settingsRouter.get('/homepage', getHomepageSettingsHandler)
settingsRouter.put('/homepage', updateHomepageSettingsHandler)
