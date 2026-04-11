import type { IncomingHttpHeaders } from 'node:http'
import { request as httpsRequest } from 'node:https'
import type { Request, Response } from 'express'
import { AppError } from '../lib/errors.js'
import { logger } from '../lib/logger.js'
import { cleanupBilibiliMediaFile, prepareBilibiliMediaFile } from './bilibili-media.service.js'
import type { BilibiliDashMediaTarget, BilibiliMediaTarget, BilibiliProgressiveMediaTarget } from './bilibili.types.js'
import {
  assertAllowedBilibiliMediaUrl,
  getSingleHeaderValue,
  resolveBilibiliMediaRedirectUrl,
  shouldFollowBilibiliMediaRedirect,
} from './bilibili-media-http.service.js'

const maxUpstreamRedirects = 2

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

function applyMuxedMediaHeaders(res: Response, input: {
  fileSize: number
  contentDisposition?: string
}): void {
  res.status(200)
  res.setHeader('Cache-Control', 'no-store, private')
  res.setHeader('Content-Type', 'video/mp4')
  res.setHeader('Content-Length', String(input.fileSize))
  if (input.contentDisposition) {
    res.setHeader('Content-Disposition', input.contentDisposition)
  }
}

function streamBilibiliProgressiveVideo(req: Request, res: Response, input: BilibiliProgressiveMediaTarget & {
  contentDisposition?: string
}): Promise<void> {
  assertAllowedBilibiliMediaUrl(input.playableVideoUrl)

  return new Promise((resolve, reject) => {
    const upstreamHeaders = {
      Accept: '*/*',
      'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
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
      }, 'Bilibili proxy failed')

      finishOnce(() => reject(error instanceof AppError ? error : new AppError(error instanceof Error ? error.message : 'Bilibili proxy stream failed', 502)))
    }

    const startUpstreamRequest = (targetUrl: string, redirectCount: number): void => {
      const upstreamReq = httpsRequest(new URL(targetUrl), {
        method: 'GET',
        headers: upstreamHeaders,
        timeout: 30000,
      }, (upstreamRes) => {
        const statusCode = upstreamRes.statusCode ?? 502
        const contentType = getSingleHeaderValue(upstreamRes.headers['content-type'])

        if (shouldFollowBilibiliMediaRedirect(statusCode)) {
          upstreamRes.resume()

          if (redirectCount >= maxUpstreamRedirects) {
            finishOnce(() => reject(new AppError('Bilibili upstream redirected too many times', 502)))
            return
          }

          try {
            const redirectUrl = resolveBilibiliMediaRedirectUrl(targetUrl, upstreamRes.headers.location)
            startUpstreamRequest(redirectUrl, redirectCount + 1)
          } catch (error: unknown) {
            rejectWithContext(error, 'response', targetUrl)
          }
          return
        }

        if (statusCode >= 400) {
          upstreamRes.resume()
          finishOnce(() => reject(new AppError(`Bilibili upstream video request failed with status ${statusCode}`, 502)))
          return
        }

        if (!contentType.startsWith('video/') && contentType !== 'application/octet-stream') {
          upstreamRes.resume()
          finishOnce(() => reject(new AppError(`Bilibili upstream did not return a video stream (${contentType || 'unknown'})`, 502)))
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
            rejectWithContext(new Error('Bilibili proxy client connection closed before stream finished'), 'stream', targetUrl)
            return
          }

          finishOnce(resolve)
        })
        upstreamRes.pipe(res)
      })

      upstreamReq.on('timeout', () => {
        upstreamReq.destroy(new Error('Bilibili upstream video request timed out'))
      })
      upstreamReq.on('error', (error) => {
        rejectWithContext(error, 'request', targetUrl)
      })
      upstreamReq.end()
    }

    startUpstreamRequest(input.playableVideoUrl, 0)
  })
}

async function streamMuxedBilibiliVideo(res: Response, input: BilibiliDashMediaTarget, contentDisposition?: string): Promise<void> {
  const mediaFile = await prepareBilibiliMediaFile(input)

  try {
    applyMuxedMediaHeaders(res, {
      fileSize: mediaFile.fileSize,
      contentDisposition,
    })

    await new Promise<void>((resolve, reject) => {
      let settled = false
      const finishOnce = (handler: () => void): void => {
        if (settled) {
          return
        }

        settled = true
        handler()
      }

      const fileStream = mediaFile.createReadStream()

      fileStream.on('error', (error) => {
        finishOnce(() => reject(error instanceof Error ? error : new Error('B 站视频输出失败')))
      })
      res.on('error', (error) => {
        finishOnce(() => reject(error instanceof Error ? error : new Error('B 站视频输出失败')))
      })
      res.on('finish', () => {
        finishOnce(resolve)
      })
      res.on('close', () => {
        if (!res.writableEnded) {
          finishOnce(() => reject(new Error('Bilibili proxy client connection closed before muxed stream finished')))
        }
      })

      fileStream.pipe(res)
    })
  } finally {
    await cleanupBilibiliMediaFile(mediaFile.filePath)
  }
}

export async function proxyBilibiliMedia(req: Request, res: Response, target: BilibiliMediaTarget): Promise<void> {
  if (target.kind === 'progressive') {
    await streamBilibiliProgressiveVideo(req, res, target)
    return
  }

  await streamMuxedBilibiliVideo(res, target)
}

export async function downloadBilibiliMedia(req: Request, res: Response, target: BilibiliMediaTarget, contentDisposition: string): Promise<void> {
  if (target.kind === 'progressive') {
    await streamBilibiliProgressiveVideo(req, res, {
      ...target,
      contentDisposition,
    })
    return
  }

  await streamMuxedBilibiliVideo(res, target, contentDisposition)
}
