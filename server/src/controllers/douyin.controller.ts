import { createReadStream } from 'node:fs'
import { request as httpsRequest } from 'node:https'
import type { IncomingHttpHeaders } from 'node:http'
import type { NextFunction, Request, Response } from 'express'
import { isAllowedDouyinVideoHost } from '../lib/douyin-hosts.js'
import { extractDouyinVideoRequest, proxyDouyinVideoRequestParams } from '../schemas/douyin.js'
import { cleanupDouyinAudioFile, extractDouyinAudio } from '../services/douyin-audio.service.js'
import { extractDouyinVideo } from '../services/douyin-video.service.js'
import { parseDouyinProxyToken } from '../services/douyin-proxy.service.js'
import {
  getDouyinSessionSnapshot,
  logoutDouyinSession,
  pollDouyinSession,
  startDouyinSession,
} from '../services/douyin-session.service.js'
import { AppError } from '../lib/errors.js'
import { logger } from '../lib/logger.js'

export function isAllowedVideoHost(hostname: string): boolean {
  return isAllowedDouyinVideoHost(hostname)
}

export function assertAllowedVideoUrl(url: string): void {
  const parsed = new URL(url)
  if (parsed.protocol !== 'https:' || !isAllowedDouyinVideoHost(parsed.hostname)) {
    throw new AppError('视频地址不受信任', 400)
  }
}

function setSessionNoStore(res: Response): void {
  res.setHeader('Cache-Control', 'no-store, private')
  res.setHeader('Pragma', 'no-cache')
}

function summarizeProxyTarget(url: string): { host: string, path: string } {
  try {
    const parsed = new URL(url)
    return {
      host: parsed.hostname,
      path: parsed.pathname,
    }
  } catch {
    return {
      host: 'unknown',
      path: 'unknown',
    }
  }
}

function applyProxyVideoHeaders(res: Response, headers: IncomingHttpHeaders, statusCode: number): void {
  res.status(statusCode)
  res.setHeader('Cache-Control', 'no-store, private')

  const headerNames = [
    'content-type',
    'content-length',
    'content-range',
    'accept-ranges',
    'etag',
    'last-modified',
  ] as const

  for (const headerName of headerNames) {
    const value = headers[headerName]
    if (typeof value === 'string') {
      res.setHeader(headerName, value)
    }
  }
}

function buildContentDisposition(filename: string): string {
  return `attachment; filename*=UTF-8''${encodeURIComponent(filename)}`
}

const upstreamRedirectStatusCodes = new Set([301, 302, 303, 307, 308])
const maxUpstreamRedirects = 2

function shouldFollowUpstreamRedirect(statusCode: number): boolean {
  return upstreamRedirectStatusCodes.has(statusCode)
}

export function resolveUpstreamRedirectUrl(currentUrl: string, locationHeader: string | string[] | undefined): string {
  const location = Array.isArray(locationHeader) ? locationHeader[0] : locationHeader
  if (!location) {
    throw new AppError('Douyin upstream returned redirect without location', 502)
  }

  const redirectUrl = new URL(location, currentUrl).toString()
  assertAllowedVideoUrl(redirectUrl)
  return redirectUrl
}

function streamDouyinVideo(req: Request, res: Response, input: {
  playableVideoUrl: string
  requestHeaders?: Record<string, string>
  contentDisposition?: string
}): Promise<void> {
  assertAllowedVideoUrl(input.playableVideoUrl)

  return new Promise((resolve, reject) => {
    const upstreamHeaders = {
      Accept: '*/*',
      'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
      Origin: 'https://www.douyin.com',
      ...input.requestHeaders,
      ...(typeof req.headers.range === 'string' ? { Range: req.headers.range } : {}),
    }

    let settled = false
    const finishOnce = (handler: () => void): void => {
      if (settled) {
        return
      }

      settled = true
      handler()
    }

    const rejectWithContext = (error: unknown, stage: 'request' | 'response' | 'stream', targetUrl: string): void => {
      logger.error({
        stage,
        upstreamTarget: summarizeProxyTarget(targetUrl),
        err: error instanceof Error
          ? { name: error.name, message: error.message, stack: error.stack }
          : { message: 'Unknown proxy error' },
      }, 'Douyin proxy failed')

      finishOnce(() => reject(error instanceof AppError ? error : new AppError(error instanceof Error ? error.message : 'Douyin proxy stream failed', 502)))
    }

    const startUpstreamRequest = (targetUrl: string, redirectCount: number): void => {
      const upstreamUrl = new URL(targetUrl)

      logger.info({
        upstreamTarget: summarizeProxyTarget(targetUrl),
        hasRange: typeof req.headers.range === 'string',
        forwardedHeaderKeys: Object.keys(upstreamHeaders),
        redirectCount,
      }, 'Douyin proxy request start')

      const upstreamReq = httpsRequest(upstreamUrl, {
        method: 'GET',
        headers: upstreamHeaders,
        timeout: 30000,
      }, (upstreamRes) => {
        const statusCode = upstreamRes.statusCode ?? 502
        const contentTypeHeader = upstreamRes.headers['content-type']
        const contentType = Array.isArray(contentTypeHeader) ? contentTypeHeader[0] || '' : contentTypeHeader || ''

        logger.info({
          upstreamTarget: summarizeProxyTarget(targetUrl),
          statusCode,
          contentType,
          redirectCount,
        }, 'Douyin proxy upstream response')

        if (shouldFollowUpstreamRedirect(statusCode)) {
          upstreamRes.resume()

          if (redirectCount >= maxUpstreamRedirects) {
            finishOnce(() => reject(new AppError('Douyin upstream redirected too many times', 502)))
            return
          }

          try {
            const redirectUrl = resolveUpstreamRedirectUrl(targetUrl, upstreamRes.headers.location)
            logger.info({
              upstreamTarget: summarizeProxyTarget(targetUrl),
              redirectTarget: summarizeProxyTarget(redirectUrl),
              statusCode,
              redirectCount: redirectCount + 1,
            }, 'Douyin proxy following upstream redirect')
            startUpstreamRequest(redirectUrl, redirectCount + 1)
          } catch (error: unknown) {
            rejectWithContext(error, 'response', targetUrl)
          }
          return
        }

        if (statusCode >= 400) {
          upstreamRes.resume()
          finishOnce(() => reject(new AppError(`Douyin upstream video request failed with status ${statusCode}`, 502)))
          return
        }

        if (!contentType.startsWith('video/') && contentType !== 'application/octet-stream') {
          const chunks: Buffer[] = []
          let previewBytes = 0

          upstreamRes.on('data', (chunk) => {
            const normalizedChunk = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
            if (previewBytes >= 2048) {
              return
            }

            const remainingBytes = 2048 - previewBytes
            chunks.push(normalizedChunk.subarray(0, remainingBytes))
            previewBytes += Math.min(normalizedChunk.length, remainingBytes)
          })
          upstreamRes.on('end', () => {
            const bodyPreview = Buffer.concat(chunks).toString('utf8').slice(0, 500)
            logger.warn({
              upstreamTarget: summarizeProxyTarget(targetUrl),
              statusCode,
              contentType,
              bodyPreview,
            }, 'Douyin proxy upstream returned non-video response')
            finishOnce(() => reject(new AppError(`Douyin upstream did not return a video stream (${contentType || 'unknown'})`, 502)))
          })
          upstreamRes.on('error', (error) => {
            rejectWithContext(error, 'response', targetUrl)
          })
          return
        }

        applyProxyVideoHeaders(res, upstreamRes.headers, statusCode)
        if (input.contentDisposition) {
          res.setHeader('Content-Disposition', input.contentDisposition)
        }
        upstreamRes.on('error', (error) => {
          rejectWithContext(error, 'stream', targetUrl)
        })
        res.on('error', (error) => {
          rejectWithContext(error, 'stream', targetUrl)
        })
        res.on('finish', () => {
          finishOnce(resolve)
        })
        res.on('close', () => {
          if (!res.writableEnded) {
            rejectWithContext(new Error('Douyin proxy client connection closed before stream finished'), 'stream', targetUrl)
            return
          }

          finishOnce(resolve)
        })
        upstreamRes.pipe(res)
      })

      upstreamReq.on('timeout', () => {
        upstreamReq.destroy(new Error('Douyin upstream video request timed out'))
      })
      upstreamReq.on('error', (error) => {
        rejectWithContext(error, 'request', targetUrl)
      })
      upstreamReq.end()
    }

    startUpstreamRequest(input.playableVideoUrl, 0)
  })
}

export async function extractDouyinVideoHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { input } = extractDouyinVideoRequest.parse(req.body)
    const data = await extractDouyinVideo(input)

    res.json({
      success: true,
      data,
    })
  } catch (error: unknown) {
    next(error)
  }
}

export async function proxyDouyinVideoHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { token } = proxyDouyinVideoRequestParams.parse(req.params)
    const target = parseDouyinProxyToken(token)
    await streamDouyinVideo(req, res, target)
  } catch (error: unknown) {
    next(error)
  }
}

export async function downloadDouyinVideoHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { token } = proxyDouyinVideoRequestParams.parse(req.params)
    const target = parseDouyinProxyToken(token)
    await streamDouyinVideo(req, res, {
      ...target,
      contentDisposition: buildContentDisposition(target.filename || 'douyin-video.mp4'),
    })
  } catch (error: unknown) {
    next(error)
  }
}

export async function downloadDouyinAudioHandler(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const { token } = proxyDouyinVideoRequestParams.parse(req.params)
    const target = parseDouyinProxyToken(token)
    const audio = await extractDouyinAudio(target)
    const audioStream = createReadStream(audio.filePath)

    let cleanedUp = false
    const cleanup = (): void => {
      if (cleanedUp) {
        return
      }

      cleanedUp = true
      void cleanupDouyinAudioFile(audio.filePath)
    }

    audioStream.on('error', (error) => {
      cleanup()
      if (res.headersSent) {
        res.destroy(error)
        return
      }

      next(error)
    })
    res.on('close', cleanup)
    res.on('finish', cleanup)

    audioStream.on('open', () => {
      res.setHeader('Cache-Control', 'no-store, private')
      res.setHeader('Content-Type', audio.mimeType)
      res.setHeader('Content-Length', String(audio.fileSize))
      res.setHeader('Content-Disposition', buildContentDisposition(audio.filename))
      res.status(200)
      audioStream.pipe(res)
    })
  } catch (error: unknown) {
    next(error)
  }
}

export async function getDouyinSessionHandler(_req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const data = await getDouyinSessionSnapshot()
    setSessionNoStore(res)
    res.json({ success: true, data })
  } catch (error: unknown) {
    next(error)
  }
}

export async function startDouyinSessionHandler(_req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const data = await startDouyinSession()
    setSessionNoStore(res)
    res.json({ success: true, data })
  } catch (error: unknown) {
    next(error)
  }
}

export async function pollDouyinSessionHandler(_req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const data = await pollDouyinSession()
    setSessionNoStore(res)
    res.json({ success: true, data })
  } catch (error: unknown) {
    next(error)
  }
}

export async function logoutDouyinSessionHandler(_req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    const data = await logoutDouyinSession()
    setSessionNoStore(res)
    res.json({ success: true, data })
  } catch (error: unknown) {
    next(error)
  }
}
