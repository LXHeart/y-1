import { createBilibiliMediaReadStream } from '../services/bilibili-media.service.js'
import { getBilibiliAnalysisMediaSession } from '../services/bilibili-analysis-media.service.js'
import type { NextFunction, Request, Response } from 'express'
import { buildBilibiliDownloadFilename } from '../lib/bilibili-filename.js'
import {
  analysisBilibiliMediaRequestParams,
  analyzeBilibiliVideoRequest,
  extractBilibiliVideoRequest,
  proxyBilibiliVideoRequestParams,
} from '../schemas/bilibili.js'
import { parseBilibiliProxyToken } from '../services/bilibili-proxy.service.js'
import { downloadBilibiliMedia, proxyBilibiliMedia } from '../services/bilibili-stream.service.js'
import { analyzeBilibiliVideoByProxyUrl } from '../services/bilibili-video-analysis.service.js'
import { extractBilibiliVideo } from '../services/bilibili-video.service.js'

function buildContentDisposition(filename: string): string {
  return `attachment; filename*=UTF-8''${encodeURIComponent(filename)}`
}

export async function extractBilibiliVideoHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { input } = extractBilibiliVideoRequest.parse(req.body)
    const data = await extractBilibiliVideo(input)

    res.json({
      success: true,
      data,
    })
  } catch (error: unknown) {
    next(error)
  }
}

export async function analyzeBilibiliVideoHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { proxyVideoUrl } = analyzeBilibiliVideoRequest.parse(req.body)
    const data = await analyzeBilibiliVideoByProxyUrl(proxyVideoUrl)

    res.json({
      success: true,
      data,
    })
  } catch (error: unknown) {
    next(error)
  }
}

export async function serveBilibiliAnalysisMediaHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { id } = analysisBilibiliMediaRequestParams.parse(req.params)
    const media = await getBilibiliAnalysisMediaSession(id)
    const mediaStream = await createBilibiliMediaReadStream(media.filePath)

    mediaStream.on('error', (error) => {
      if (res.headersSent) {
        res.destroy(error)
        return
      }

      next(error)
    })

    res.setHeader('Cache-Control', 'no-store, private')
    res.setHeader('Content-Type', media.mimeType)
    res.setHeader('Content-Length', String(media.fileSize))
    res.status(200)
    mediaStream.pipe(res)
  } catch (error: unknown) {
    next(error)
  }
}

export async function proxyBilibiliVideoHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { token } = proxyBilibiliVideoRequestParams.parse(req.params)
    const target = parseBilibiliProxyToken(token)

    await proxyBilibiliMedia(req, res, target)
  } catch (error: unknown) {
    next(error)
  }
}

export async function downloadBilibiliVideoHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { token } = proxyBilibiliVideoRequestParams.parse(req.params)
    const target = parseBilibiliProxyToken(token)
    const contentDisposition = buildContentDisposition(target.filename || buildBilibiliDownloadFilename({}))

    await downloadBilibiliMedia(req, res, target, contentDisposition)
  } catch (error: unknown) {
    next(error)
  }
}
