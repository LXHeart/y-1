import type { Request, Response, NextFunction } from 'express'
import { getSessionUser } from '../lib/auth.js'
import { loadHomepageHotItems } from '../services/homepage-hot.service.js'

export async function getHomepageHotItemsHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const data = await loadHomepageHotItems(getSessionUser(req)?.id)
    res.json({ success: true, data })
  } catch (error: unknown) {
    next(error)
  }
}
