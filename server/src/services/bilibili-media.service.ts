import { execFile } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { createReadStream, createWriteStream } from 'node:fs'
import { request as httpsRequest } from 'node:https'
import { mkdir, rm, stat } from 'node:fs/promises'
import type { ReadStream } from 'node:fs'
import path from 'node:path'
import { pipeline } from 'node:stream/promises'
import { promisify } from 'node:util'
import { AppError } from '../lib/errors.js'
import { env } from '../lib/env.js'
import { logger } from '../lib/logger.js'
import type { BilibiliDashMediaTarget, BilibiliMediaTarget, BilibiliProgressiveMediaTarget } from './bilibili.types.js'
import {
  assertAllowedBilibiliMediaUrl,
  getSingleHeaderValue,
  resolveBilibiliMediaRedirectUrl,
  shouldFollowBilibiliMediaRedirect,
} from './bilibili-media-http.service.js'

const execFileAsync = promisify(execFile)
const maxUpstreamRedirects = 2
const maxDownloadBytes = 400 * 1024 * 1024
const videoMimeType = 'video/mp4'
const acceptedUpstreamContentTypes = new Set(['application/octet-stream', 'video/mp4', 'audio/mp4'])

export interface BilibiliMediaFile {
  filePath: string
  fileSize: number
  filename: string
  mimeType: string
  createReadStream: () => ReadStream
}


interface BilibiliMediaPaths {
  videoTrackFilePath: string
  audioTrackFilePath: string
  outputFilePath: string
}

interface BilibiliMediaDependencies {
  ensureTempDir: (dirPath: string) => Promise<void>
  createTempPaths: (dirPath: string) => BilibiliMediaPaths
  downloadUpstreamFile: (input: {
    url: string
    requestHeaders?: Record<string, string>
    expectedContentTypePrefix: 'video/' | 'audio/'
    targetFilePath: string
  }) => Promise<void>
  runCommand: (command: string, args: string[], timeoutMs: number) => Promise<void>
  statFile: (filePath: string) => Promise<{ size: number }>
  cleanupFile: (filePath: string) => Promise<void>
}


function mapDownloadError(error: unknown): AppError {
  if (error instanceof AppError) {
    return error
  }

  const message = error instanceof Error ? error.message : 'Unknown download error'
  if (/timed out/i.test(message)) {
    return new AppError('上游 B 站媒体下载超时，请稍后重试', 504)
  }

  return new AppError('上游 B 站媒体下载失败，请稍后重试', 502)
}

async function runCommand(command: string, args: string[], timeoutMs: number): Promise<void> {
  try {
    await execFileAsync(command, args, {
      timeout: timeoutMs,
      maxBuffer: 10 * 1024 * 1024,
    })
  } catch (error: unknown) {
    logger.warn({
      command,
      args,
      timeoutMs,
      err: error instanceof Error
        ? { name: error.name, message: error.message }
        : { message: 'Unknown error' },
    }, 'Bilibili media command failed')

    if (error && typeof error === 'object' && 'code' in error && error.code === 'ENOENT') {
      throw new AppError('ffmpeg 不可用，请先安装并配置后再处理 B 站视频', 500)
    }

    if (error instanceof Error && /timed out/i.test(error.message)) {
      throw new AppError('B 站音视频合成超时，请稍后重试', 504)
    }

    throw new AppError('B 站音视频合成失败，请稍后重试', 502)
  }
}

function validateBilibiliTrackContentType(input: {
  contentType: string
  expectedContentTypePrefix: 'video/' | 'audio/'
}): boolean {
  const normalizedContentType = input.contentType.split(';')[0]?.trim().toLowerCase() || ''
  const isExpectedMediaType = input.expectedContentTypePrefix === 'video/'
    ? normalizedContentType.startsWith('video/') || normalizedContentType === 'audio/mp4'
    : normalizedContentType.startsWith('audio/') || normalizedContentType === 'video/mp4'

  return isExpectedMediaType || acceptedUpstreamContentTypes.has(normalizedContentType)
}

function buildBilibiliMediaFileResult(input: {
  filePath: string
  fileSize: number
  filename?: string
}): BilibiliMediaFile {
  return {
    filePath: input.filePath,
    fileSize: input.fileSize,
    filename: input.filename || 'bilibili-video.mp4',
    mimeType: videoMimeType,
    createReadStream: () => createReadStream(input.filePath),
  }
}

async function muxBilibiliTracks(input: {
  videoTrackFilePath: string
  audioTrackFilePath: string
  outputFilePath: string
  dependencies: BilibiliMediaDependencies
}): Promise<void> {
  await input.dependencies.runCommand(env.FFMPEG_PATH, [
    '-y',
    '-i',
    input.videoTrackFilePath,
    '-i',
    input.audioTrackFilePath,
    '-c',
    'copy',
    '-movflags',
    '+faststart',
    input.outputFilePath,
  ], env.DOUYIN_MEDIA_PROCESS_TIMEOUT_MS)
}

async function downloadBilibiliTrackToFile(
  input: {
    url: string
    requestHeaders?: Record<string, string>
    expectedContentTypePrefix: 'video/' | 'audio/'
    targetFilePath: string
  },
): Promise<void> {
  assertAllowedBilibiliMediaUrl(input.url)

  await new Promise<void>((resolve, reject) => {
    const upstreamHeaders = {
      Accept: '*/*',
      'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
      ...input.requestHeaders,
    }

    let settled = false
    const finishOnce = (handler: () => void): void => {
      if (settled) {
        return
      }

      settled = true
      handler()
    }

    const rejectWithMappedError = (error: unknown): void => {
      finishOnce(() => reject(mapDownloadError(error)))
    }

    const startRequest = (targetUrl: string, redirectCount: number): void => {
      const upstreamReq = httpsRequest(new URL(targetUrl), {
        method: 'GET',
        headers: upstreamHeaders,
        timeout: 30000,
      }, (upstreamRes) => {
        const statusCode = upstreamRes.statusCode ?? 502

        if (shouldFollowBilibiliMediaRedirect(statusCode)) {
          upstreamRes.resume()

          if (redirectCount >= maxUpstreamRedirects) {
            finishOnce(() => reject(new AppError('Bilibili upstream redirected too many times', 502)))
            return
          }

          try {
            const redirectUrl = resolveBilibiliMediaRedirectUrl(targetUrl, upstreamRes.headers.location)
            startRequest(redirectUrl, redirectCount + 1)
          } catch (error: unknown) {
            rejectWithMappedError(error)
          }
          return
        }

        if (statusCode >= 400) {
          upstreamRes.resume()
          finishOnce(() => reject(new AppError(`Bilibili upstream media request failed with status ${statusCode}`, 502)))
          return
        }

        const contentType = getSingleHeaderValue(upstreamRes.headers['content-type'])
        if (!validateBilibiliTrackContentType({
          contentType,
          expectedContentTypePrefix: input.expectedContentTypePrefix,
        })) {
          upstreamRes.resume()
          finishOnce(() => reject(new AppError(`Bilibili upstream did not return expected media stream (${contentType || 'unknown'})`, 502)))
          return
        }

        const contentLengthHeader = getSingleHeaderValue(upstreamRes.headers['content-length'])
        const contentLength = Number(contentLengthHeader)
        if (Number.isFinite(contentLength) && contentLength > maxDownloadBytes) {
          upstreamRes.resume()
          finishOnce(() => reject(new AppError('上游 B 站媒体文件过大，暂不支持处理', 413)))
          return
        }

        void pipeline(upstreamRes, createWriteStream(input.targetFilePath))
          .then(() => {
            finishOnce(resolve)
          })
          .catch((error: unknown) => {
            rejectWithMappedError(error)
          })
      })

      upstreamReq.on('timeout', () => {
        upstreamReq.destroy(new Error('Bilibili upstream media request timed out'))
      })
      upstreamReq.on('error', (error) => {
        rejectWithMappedError(error)
      })
      upstreamReq.end()
    }

    startRequest(input.url, 0)
  })
}

const defaultDependencies: BilibiliMediaDependencies = {
  ensureTempDir: async (dirPath) => {
    await mkdir(dirPath, { recursive: true, mode: 0o700 })
  },
  createTempPaths: (dirPath) => {
    const tempId = randomUUID()
    return {
      videoTrackFilePath: path.join(dirPath, `${tempId}-video.m4s`),
      audioTrackFilePath: path.join(dirPath, `${tempId}-audio.m4s`),
      outputFilePath: path.join(dirPath, `${tempId}-muxed.mp4`),
    }
  },
  downloadUpstreamFile: downloadBilibiliTrackToFile,
  runCommand,
  statFile: stat,
  cleanupFile: async (filePath) => {
    await rm(filePath, { force: true })
  },
}

async function cleanupTempFile(filePath: string, cleanupFileImpl: BilibiliMediaDependencies['cleanupFile']): Promise<void> {
  try {
    await cleanupFileImpl(filePath)
  } catch (error: unknown) {
    logger.warn({
      filePath,
      err: error instanceof Error ? { name: error.name, message: error.message } : { message: 'Unknown cleanup error' },
    }, 'Bilibili media temp file cleanup failed')
  }
}

export async function cleanupBilibiliMediaFile(filePath: string): Promise<void> {
  await cleanupTempFile(filePath, defaultDependencies.cleanupFile)
}

export async function prepareBilibiliMediaFile(
  input: BilibiliMediaTarget,
  dependencies: BilibiliMediaDependencies = defaultDependencies,
): Promise<BilibiliMediaFile> {
  const tempDirPath = env.BILIBILI_MEDIA_TEMP_DIR
  await dependencies.ensureTempDir(tempDirPath)

  if (input.kind === 'progressive') {
    const { outputFilePath } = dependencies.createTempPaths(tempDirPath)

    try {
      await dependencies.downloadUpstreamFile({
        url: input.playableVideoUrl,
        requestHeaders: input.requestHeaders,
        expectedContentTypePrefix: 'video/',
        targetFilePath: outputFilePath,
      })

      const outputFile = await dependencies.statFile(outputFilePath)
      return buildBilibiliMediaFileResult({
        filePath: outputFilePath,
        fileSize: outputFile.size,
        filename: input.filename,
      })
    } catch (error: unknown) {
      await cleanupTempFile(outputFilePath, dependencies.cleanupFile)

      if (error instanceof AppError) {
        throw error
      }

      throw new AppError('B 站视频处理失败，请稍后重试', 502)
    }
  }

  assertAllowedBilibiliMediaUrl(input.videoTrackUrl)
  assertAllowedBilibiliMediaUrl(input.audioTrackUrl)

  const { videoTrackFilePath, audioTrackFilePath, outputFilePath } = dependencies.createTempPaths(tempDirPath)

  try {
    await dependencies.downloadUpstreamFile({
      url: input.videoTrackUrl,
      requestHeaders: input.requestHeaders,
      expectedContentTypePrefix: 'video/',
      targetFilePath: videoTrackFilePath,
    })
    await dependencies.downloadUpstreamFile({
      url: input.audioTrackUrl,
      requestHeaders: input.requestHeaders,
      expectedContentTypePrefix: 'audio/',
      targetFilePath: audioTrackFilePath,
    })

    await muxBilibiliTracks({
      videoTrackFilePath,
      audioTrackFilePath,
      outputFilePath,
      dependencies,
    })

    const outputFile = await dependencies.statFile(outputFilePath)
    return buildBilibiliMediaFileResult({
      filePath: outputFilePath,
      fileSize: outputFile.size,
      filename: input.filename,
    })
  } catch (error: unknown) {
    await cleanupTempFile(outputFilePath, dependencies.cleanupFile)

    if (error instanceof AppError) {
      throw error
    }

    throw new AppError('B 站视频处理失败，请稍后重试', 502)
  } finally {
    await cleanupTempFile(videoTrackFilePath, dependencies.cleanupFile)
    await cleanupTempFile(audioTrackFilePath, dependencies.cleanupFile)
  }
}

export async function createBilibiliMediaReadStream(filePath: string, options?: {
  start?: number
  end?: number
}) {
  return createReadStream(filePath, options)
}
