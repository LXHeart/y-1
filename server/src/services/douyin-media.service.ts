import { execFile } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { createReadStream, createWriteStream } from 'node:fs'
import type { ReadStream } from 'node:fs'
import { mkdir, rm, stat } from 'node:fs/promises'
import { request as httpsRequest } from 'node:https'
import path from 'node:path'
import { Transform } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { promisify } from 'node:util'
import { isAllowedDouyinVideoHost } from '../lib/douyin-hosts.js'
import { AppError } from '../lib/errors.js'
import { env } from '../lib/env.js'
import { logger } from '../lib/logger.js'

const execFileAsync = promisify(execFile)
const upstreamRedirectStatusCodes = new Set([301, 302, 303, 307, 308])
const maxUpstreamRedirects = 2
const maxDownloadBytes = 400 * 1024 * 1024
const videoMimeType = 'video/mp4'
const defaultClipDurationSeconds = 30

export interface DouyinMediaTarget {
  playableVideoUrl: string
  requestHeaders?: Record<string, string>
  filename?: string
  durationSeconds?: number
}

export interface DouyinMediaFile {
  filePath: string
  fileSize: number
  filename: string
  mimeType: string
  createReadStream: () => ReadStream
}

export interface DouyinMediaClip extends DouyinMediaFile {
  clipIndex: number
  startSeconds: number
  endSeconds: number
}

interface DouyinMediaDependencies {
  ensureTempDir: (dirPath: string) => Promise<void>
  createTempPath: (dirPath: string) => string
  downloadVideoToFile: (input: DouyinMediaTarget, targetFilePath: string) => Promise<void>
  runCommand: (command: string, args: string[], timeoutMs: number) => Promise<void>
  statFile: (filePath: string) => Promise<{ size: number }>
  cleanupFile: (filePath: string) => Promise<void>
}

interface DouyinMediaClipDependencies {
  ensureTempDir: (dirPath: string) => Promise<void>
  runCommand: (command: string, args: string[], timeoutMs: number) => Promise<void>
  statFile: (filePath: string) => Promise<{ size: number }>
  cleanupFile: (filePath: string) => Promise<void>
}

function assertAllowedVideoUrl(url: string): void {
  const parsed = new URL(url)
  if (parsed.protocol !== 'https:' || !isAllowedDouyinVideoHost(parsed.hostname)) {
    throw new AppError('视频地址不受信任', 400)
  }
}

function shouldFollowUpstreamRedirect(statusCode: number): boolean {
  return upstreamRedirectStatusCodes.has(statusCode)
}

function getHeaderValue(value: string | string[] | undefined): string {
  return Array.isArray(value) ? value[0] || '' : value || ''
}

function resolveUpstreamRedirectUrl(currentUrl: string, locationHeader: string | string[] | undefined): string {
  const location = Array.isArray(locationHeader) ? locationHeader[0] : locationHeader
  if (!location) {
    throw new AppError('Douyin upstream returned redirect without location', 502)
  }

  const redirectUrl = new URL(location, currentUrl).toString()
  assertAllowedVideoUrl(redirectUrl)
  return redirectUrl
}

function mapDownloadError(error: unknown): AppError {
  if (error instanceof AppError) {
    return error
  }

  const message = error instanceof Error ? error.message : 'Unknown download error'
  if (/timed out/i.test(message)) {
    return new AppError('上游视频下载超时，请稍后重试', 504)
  }

  return new AppError('上游视频下载失败，请稍后重试', 502)
}

function buildDownloadSizeGuard(maxBytes: number): Transform {
  let bytesRead = 0

  return new Transform({
    transform(chunk, _encoding, callback) {
      const normalizedChunk = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
      bytesRead += normalizedChunk.length

      if (bytesRead > maxBytes) {
        callback(new AppError('上游视频文件过大，暂不支持处理', 413))
        return
      }

      callback(null, normalizedChunk)
    },
  })
}

function buildDouyinMediaFileResult(input: {
  filePath: string
  fileSize: number
  filename?: string
}): DouyinMediaFile {
  return {
    filePath: input.filePath,
    fileSize: input.fileSize,
    filename: input.filename || 'douyin-video.mp4',
    mimeType: videoMimeType,
    createReadStream: () => createReadStream(input.filePath),
  }
}

function buildDouyinMediaClipFilename(filename: string | undefined, clipIndex: number): string {
  const parsed = path.parse(filename || 'douyin-video.mp4')
  const extension = parsed.ext || '.mp4'
  const baseName = parsed.name || 'douyin-video'
  return `${baseName}-clip-${clipIndex + 1}${extension}`
}

function buildClipOutputFilePath(sourceFilePath: string, clipIndex: number): string {
  const parsed = path.parse(sourceFilePath)
  return path.join(parsed.dir, `${parsed.name}-clip-${clipIndex + 1}.mp4`)
}

function buildDouyinMediaClipResult(input: {
  filePath: string
  fileSize: number
  filename?: string
  clipIndex: number
  startSeconds: number
  endSeconds: number
}): DouyinMediaClip {
  return {
    ...buildDouyinMediaFileResult({
      filePath: input.filePath,
      fileSize: input.fileSize,
      filename: input.filename,
    }),
    clipIndex: input.clipIndex,
    startSeconds: input.startSeconds,
    endSeconds: input.endSeconds,
  }
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
    }, 'Douyin media command failed')

    if (error && typeof error === 'object' && 'code' in error && error.code === 'ENOENT') {
      throw new AppError('ffmpeg 不可用，请先安装并配置后再处理抖音视频', 500)
    }

    if (error instanceof Error && /timed out/i.test(error.message)) {
      throw new AppError('抖音视频处理超时，请稍后重试', 504)
    }

    throw new AppError('抖音视频处理失败，请稍后重试', 502)
  }
}

async function downloadDouyinVideoToFile(input: DouyinMediaTarget, targetFilePath: string): Promise<void> {
  assertAllowedVideoUrl(input.playableVideoUrl)

  await new Promise<void>((resolve, reject) => {
    const upstreamHeaders = {
      Accept: '*/*',
      'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
      Origin: 'https://www.douyin.com',
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

        if (shouldFollowUpstreamRedirect(statusCode)) {
          upstreamRes.resume()

          if (redirectCount >= maxUpstreamRedirects) {
            finishOnce(() => reject(new AppError('Douyin upstream redirected too many times', 502)))
            return
          }

          try {
            const redirectUrl = resolveUpstreamRedirectUrl(targetUrl, upstreamRes.headers.location)
            startRequest(redirectUrl, redirectCount + 1)
          } catch (error: unknown) {
            rejectWithMappedError(error)
          }
          return
        }

        if (statusCode >= 400) {
          upstreamRes.resume()
          finishOnce(() => reject(new AppError(`Douyin upstream video request failed with status ${statusCode}`, 502)))
          return
        }

        const contentType = getHeaderValue(upstreamRes.headers['content-type'])
        if (!contentType.startsWith('video/') && contentType !== 'application/octet-stream') {
          upstreamRes.resume()
          finishOnce(() => reject(new AppError(`Douyin upstream did not return a video stream (${contentType || 'unknown'})`, 502)))
          return
        }

        const contentLengthHeader = getHeaderValue(upstreamRes.headers['content-length'])
        const contentLength = Number(contentLengthHeader)
        if (Number.isFinite(contentLength) && contentLength > maxDownloadBytes) {
          upstreamRes.resume()
          finishOnce(() => reject(new AppError('上游视频文件过大，暂不支持处理', 413)))
          return
        }

        void pipeline(
          upstreamRes,
          buildDownloadSizeGuard(maxDownloadBytes),
          createWriteStream(targetFilePath),
        )
          .then(() => {
            finishOnce(resolve)
          })
          .catch((error: unknown) => {
            rejectWithMappedError(error)
          })
      })

      upstreamReq.on('timeout', () => {
        upstreamReq.destroy(new Error('Douyin upstream video request timed out'))
      })
      upstreamReq.on('error', (error) => {
        rejectWithMappedError(error)
      })
      upstreamReq.end()
    }

    startRequest(input.playableVideoUrl, 0)
  })
}

const defaultDependencies: DouyinMediaDependencies = {
  ensureTempDir: async (dirPath) => {
    await mkdir(dirPath, { recursive: true, mode: 0o700 })
  },
  createTempPath: (dirPath) => {
    const tempId = randomUUID()
    return path.join(dirPath, `${tempId}.mp4`)
  },
  downloadVideoToFile: downloadDouyinVideoToFile,
  runCommand,
  statFile: stat,
  cleanupFile: async (filePath) => {
    await rm(filePath, { force: true })
  },
}

const defaultClipDependencies: DouyinMediaClipDependencies = {
  ensureTempDir: defaultDependencies.ensureTempDir,
  runCommand: defaultDependencies.runCommand,
  statFile: defaultDependencies.statFile,
  cleanupFile: defaultDependencies.cleanupFile,
}

async function cleanupTempFile(filePath: string, cleanupFileImpl: DouyinMediaDependencies['cleanupFile']): Promise<void> {
  try {
    await cleanupFileImpl(filePath)
  } catch (error: unknown) {
    logger.warn({
      filePath,
      err: error instanceof Error ? { name: error.name, message: error.message } : { message: 'Unknown cleanup error' },
    }, 'Douyin media temp file cleanup failed')
  }
}

export async function cleanupDouyinMediaFile(filePath: string): Promise<void> {
  await cleanupTempFile(filePath, defaultDependencies.cleanupFile)
}

export async function cleanupDouyinMediaFileStrict(filePath: string): Promise<void> {
  await defaultDependencies.cleanupFile(filePath)
}

export async function createDouyinMediaClips(input: {
  sourceFilePath: string
  durationSeconds: number
  filename?: string
  clipDurationSeconds?: number
}, dependencies: DouyinMediaClipDependencies = defaultClipDependencies): Promise<DouyinMediaClip[]> {
  const clipDurationSeconds = input.clipDurationSeconds || defaultClipDurationSeconds
  const totalDurationSeconds = Math.ceil(input.durationSeconds)

  if (!Number.isInteger(totalDurationSeconds) || totalDurationSeconds <= 0) {
    throw new AppError('未能识别视频时长，请重新提取后再分析', 422)
  }

  await dependencies.ensureTempDir(path.dirname(input.sourceFilePath))

  const clips: DouyinMediaClip[] = []

  try {
    for (let clipIndex = 0, startSeconds = 0; startSeconds < totalDurationSeconds; clipIndex += 1, startSeconds += clipDurationSeconds) {
      const endSeconds = Math.min(startSeconds + clipDurationSeconds, totalDurationSeconds)
      const outputFilePath = buildClipOutputFilePath(input.sourceFilePath, clipIndex)

      await dependencies.runCommand(env.FFMPEG_PATH, [
        '-y',
        '-i',
        input.sourceFilePath,
        '-ss',
        String(startSeconds),
        '-t',
        String(endSeconds - startSeconds),
        '-c',
        'copy',
        '-movflags',
        '+faststart',
        outputFilePath,
      ], env.DOUYIN_MEDIA_PROCESS_TIMEOUT_MS)

      const outputFile = await dependencies.statFile(outputFilePath)
      clips.push(buildDouyinMediaClipResult({
        filePath: outputFilePath,
        fileSize: outputFile.size,
        filename: buildDouyinMediaClipFilename(input.filename, clipIndex),
        clipIndex,
        startSeconds,
        endSeconds,
      }))
    }

    return clips
  } catch (error: unknown) {
    await Promise.all(clips.map(async (clip) => {
      await cleanupTempFile(clip.filePath, dependencies.cleanupFile)
    }))

    const currentOutputFilePath = buildClipOutputFilePath(input.sourceFilePath, clips.length)
    await cleanupTempFile(currentOutputFilePath, dependencies.cleanupFile)

    if (error instanceof AppError) {
      throw error
    }

    throw new AppError('抖音视频切片失败，请稍后重试', 502)
  }
}

export async function prepareDouyinMediaFile(
  input: DouyinMediaTarget,
  dependencies: DouyinMediaDependencies = defaultDependencies,
): Promise<DouyinMediaFile> {
  assertAllowedVideoUrl(input.playableVideoUrl)

  const tempDirPath = env.DOUYIN_MEDIA_TEMP_DIR
  await dependencies.ensureTempDir(tempDirPath)

  const outputFilePath = dependencies.createTempPath(tempDirPath)

  try {
    await dependencies.downloadVideoToFile(input, outputFilePath)

    const outputFile = await dependencies.statFile(outputFilePath)
    return buildDouyinMediaFileResult({
      filePath: outputFilePath,
      fileSize: outputFile.size,
      filename: input.filename,
    })
  } catch (error: unknown) {
    await cleanupTempFile(outputFilePath, dependencies.cleanupFile)

    if (error instanceof AppError) {
      throw error
    }

    throw new AppError('抖音视频处理失败，请稍后重试', 502)
  }
}

export async function createDouyinMediaReadStream(filePath: string, options?: {
  start?: number
  end?: number
}): Promise<ReadStream> {
  return createReadStream(filePath, options)
}
