import type { Request, Response, NextFunction } from 'express'
import { getSessionUser } from '../lib/auth.js'
import * as creditService from '../services/credit.service.js'

export async function getBalanceHandler(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const user = getSessionUser(req)!
    const balance = await creditService.getCreditBalance(user.id)
    res.json(balance)
  } catch (error: unknown) {
    next(error)
  }
}

export async function getHistoryHandler(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const user = getSessionUser(req)!
    const history = await creditService.getCreditHistory(user.id, 50)
    res.json({ history })
  } catch (error: unknown) {
    next(error)
  }
}
