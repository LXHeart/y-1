import type { Request, Response, NextFunction } from 'express'
import { adjustCreditsSchema } from '../schemas/admin.js'
import * as adminService from '../services/admin.service.js'

export async function listUsersHandler(
  _req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const users = await adminService.listUsersWithCredits()
    res.json({ users })
  } catch (error: unknown) {
    next(error)
  }
}

export async function adjustCreditsHandler(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const { userId, amount, note } = adjustCreditsSchema.parse(req.body)
    await adminService.adjustCredits(userId, amount, note)
    res.json({ success: true })
  } catch (error: unknown) {
    next(error)
  }
}
