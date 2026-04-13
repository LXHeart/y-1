import { AppError } from '../lib/errors.js'
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

function parseSingleRangeHeader(rangeHeader: string | undefined, fileSize: number): {
  start: number
  end: number
} | null {
  if (!rangeHeader) {
    return null
  }

  const match = rangeHeader.match(/^bytes=(\d*)-(\d*)$/)
  if (!match) {
    throw new AppError('视频范围请求无效', 416)
  }

  const [, rawStart, rawEnd] = match
  if (!rawStart && !rawEnd) {
    throw new AppError('视频范围请求无效', 416)
  }

  if (!rawStart) {
    const suffixLength = Number(rawEnd)
    if (!Number.isInteger(suffixLength) || suffixLength <= 0) {
      throw new AppError('视频范围请求无效', 416)
    }

    const start = Math.max(0, fileSize - suffixLength)
    return {
      start,
      end: fileSize - 1,
    }
  }

  const start = Number(rawStart)
  const end = rawEnd ? Number(rawEnd) : fileSize - 1

  if (!Number.isInteger(start) || !Number.isInteger(end) || start < 0 || end < start || start >= fileSize) {
    throw new AppError('视频范围请求无效', 416)
  }

  return {
    start,
    end: Math.min(end, fileSize - 1),
  }
}

function applyAnalysisMediaHeaders(res: Response, input: {
  mimeType: string
  fileSize: number
  range?: {
    start: number
    end: number
  } | null
}): void {
  res.setHeader('Cache-Control', 'no-store, private')
  res.setHeader('Accept-Ranges', 'bytes')
  res.setHeader('Content-Type', input.mimeType)

  if (!input.range) {
    res.setHeader('Content-Length', String(input.fileSize))
    res.status(200)
    return
  }

  const contentLength = input.range.end - input.range.start + 1
  res.setHeader('Content-Length', String(contentLength))
  res.setHeader('Content-Range', `bytes ${input.range.start}-${input.range.end}/${input.fileSize}`)
  res.status(206)
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
    const range = parseSingleRangeHeader(typeof req.headers.range === 'string' ? req.headers.range : undefined, media.fileSize)
    const mediaStream = await createBilibiliMediaReadStream(media.filePath, range || undefined)

    mediaStream.on('error', (error) => {
      if (res.headersSent) {
        res.destroy(error)
        return
      }

      next(error)
    })

    applyAnalysisMediaHeaders(res, {
      mimeType: media.mimeType,
      fileSize: media.fileSize,
      range,
    })
    mediaStream.pipe(res)
  } catch (error: unknown) {
    if (error instanceof AppError && error.statusCode === 416) {
      const sessionId = typeof req.params?.id === 'string' ? req.params.id : null
      if (sessionId) {
        try {
          const media = await getBilibiliAnalysisMediaSession(sessionId)
          res.setHeader('Content-Range', `bytes */${media.fileSize}`)
        } catch {
          // ignore follow-up session lookup failures for invalid range responses
        }
      }
    }

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
