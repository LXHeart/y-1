import { Router } from 'express'
import { getHomepageHotItemsHandler } from '../controllers/homepage.controller.js'

export const homepageRouter = Router()

homepageRouter.get('/hot-items', getHomepageHotItemsHandler)
