import { Router } from 'express'
import { getAnalysisSettingsHandler, updateAnalysisSettingsHandler, listModelsHandler, verifyModelHandler, getHomepageSettingsHandler, updateHomepageSettingsHandler } from '../controllers/settings.controller.js'
import { requireAuthenticatedUser } from '../lib/auth.js'

export const settingsRouter = Router()

settingsRouter.use(requireAuthenticatedUser)
settingsRouter.get('/analysis', getAnalysisSettingsHandler)
settingsRouter.put('/analysis', updateAnalysisSettingsHandler)
settingsRouter.post('/analysis/models', listModelsHandler)
settingsRouter.post('/analysis/verify-model', verifyModelHandler)
settingsRouter.get('/homepage', getHomepageSettingsHandler)
settingsRouter.put('/homepage', updateHomepageSettingsHandler)
