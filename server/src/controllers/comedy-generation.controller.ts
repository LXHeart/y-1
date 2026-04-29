import type { Request, Response, NextFunction } from 'express'
import { getSessionUser } from '../lib/auth.js'
import { AppError } from '../lib/errors.js'
import { logger } from '../lib/logger.js'
import { comedyScriptRequestSchema } from '../schemas/comedy.js'
import * as dispatch from '../services/comedy-generation.service.js'

export async function streamComedyScriptHandler(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const { topic, duration } = comedyScriptRequestSchema.parse(req.body)

    res.setHeader('Content-Type', 'text/event-stream')
    res.setHeader('Cache-Control', 'no-cache')
    res.setHeader('Connection', 'keep-alive')
    res.setHeader('X-Accel-Buffering', 'no')

    const controller = new AbortController()
    const abortOnClose = (): void => controller.abort()
    req.on('aborted', abortOnClose)
    res.on('close', () => {
      controller.abort()
      req.removeListener('aborted', abortOnClose)
    })

    try {
      for await (const chunk of dispatch.streamComedy(topic, duration, {
        signal: controller.signal,
        userId: getSessionUser(req)?.id,
      })) {
        if (controller.signal.aborted) break
        res.write(`data: ${JSON.stringify({ content: chunk })}\n\n`)
      }

      if (!controller.signal.aborted) {
        res.write('data: [DONE]\n\n')
      }
    } catch (error: unknown) {
      if (controller.signal.aborted) return
      const message = error instanceof AppError ? error.message : '脱口秀文稿生成失败'
      logger.error({ err: error }, 'Comedy script streaming error')
      res.write(`data: ${JSON.stringify({ error: message })}\n\n`)
    } finally {
      res.end()
    }
  } catch (error: unknown) {
    if (res.headersSent) {
      logger.error({ err: error }, 'Comedy streaming setup error after headers sent')
      res.end()
    } else {
      next(error)
    }
  }
}
