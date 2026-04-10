import { execFile } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { createWriteStream } from 'node:fs'
import { request as httpsRequest } from 'node:https'
import { mkdir, rm, stat } from 'node:fs/promises'
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
const audioMimeType = 'audio/mpeg'
const maxDownloadBytes = 200 * 1024 * 1024
const maxAudioBytes = 50 * 1024 * 1024

export interface DouyinAudioFile {
  filePath: string
  fileSize: number
  filename: string
  mimeType: string
}

interface DouyinAudioTarget {
  playableVideoUrl: string
  requestHeaders?: Record<string, string>
  filename?: string
}

interface DouyinAudioPaths {
  videoFilePath: string
  audioFilePath: string
}

interface DouyinAudioDependencies {
  ensureTempDir: (dirPath: string) => Promise<void>
  createTempPaths: (dirPath: string) => DouyinAudioPaths
  downloadVideoToFile: (input: DouyinAudioTarget, targetFilePath: string) => Promise<void>
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

function buildAudioDownloadFilename(filename?: string): string {
  const normalizedBase = filename
    ? filename.replace(/\.[^.]+$/u, '').trim()
    : 'douyin-video'

  return `${normalizedBase || 'douyin-video'}.mp3`
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
        callback(new AppError('上游视频文件过大，暂不支持提取音频', 413))
        return
      }

      callback(null, normalizedChunk)
    },
  })
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
    }, 'Douyin audio command failed')

    if (error && typeof error === 'object' && 'code' in error && error.code === 'ENOENT') {
      throw new AppError('ffmpeg 不可用，请先安装并配置后再提取音频', 500)
    }

    if (error instanceof Error && /timed out/i.test(error.message)) {
      throw new AppError('音频提取超时，请稍后重试', 504)
    }

    throw new AppError('音频提取失败，请稍后重试', 502)
  }
}

async function downloadVideoToFile(input: DouyinAudioTarget, targetFilePath: string): Promise<void> {
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
          finishOnce(() => reject(new AppError('上游视频文件过大，暂不支持提取音频', 413)))
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

const defaultDependencies: DouyinAudioDependencies = {
  ensureTempDir: async (dirPath) => {
    await mkdir(dirPath, { recursive: true, mode: 0o700 })
  },
  createTempPaths: (dirPath) => {
    const tempId = randomUUID()
    return {
      videoFilePath: path.join(dirPath, `${tempId}.mp4`),
      audioFilePath: path.join(dirPath, `${tempId}.mp3`),
    }
  },
  downloadVideoToFile,
  runCommand,
  statFile: stat,
  cleanupFile: async (filePath) => {
    await rm(filePath, { force: true })
  },
}

async function cleanupTempFile(filePath: string, cleanupFileImpl: DouyinAudioDependencies['cleanupFile']): Promise<void> {
  try {
    await cleanupFileImpl(filePath)
  } catch (error: unknown) {
    logger.warn({
      filePath,
      err: error instanceof Error ? { name: error.name, message: error.message } : { message: 'Unknown cleanup error' },
    }, 'Douyin audio temp file cleanup failed')
  }
}

export async function cleanupDouyinAudioFile(filePath: string): Promise<void> {
  await cleanupTempFile(filePath, defaultDependencies.cleanupFile)
}

export async function extractDouyinAudio(
  input: DouyinAudioTarget,
  dependencies: DouyinAudioDependencies = defaultDependencies,
): Promise<DouyinAudioFile> {
  assertAllowedVideoUrl(input.playableVideoUrl)

  const tempDirPath = env.DOUYIN_MEDIA_TEMP_DIR
  await dependencies.ensureTempDir(tempDirPath)

  const { videoFilePath, audioFilePath } = dependencies.createTempPaths(tempDirPath)

  try {
    await dependencies.downloadVideoToFile(input, videoFilePath)
    await dependencies.runCommand(env.FFMPEG_PATH, [
      '-y',
      '-i',
      videoFilePath,
      '-vn',
      '-c:a',
      'libmp3lame',
      '-b:a',
      '192k',
      audioFilePath,
    ], env.DOUYIN_MEDIA_PROCESS_TIMEOUT_MS)

    const audioFile = await dependencies.statFile(audioFilePath)
    if (audioFile.size > maxAudioBytes) {
      throw new AppError('提取后的音频文件过大，暂不支持下载', 413)
    }

    return {
      filePath: audioFilePath,
      fileSize: audioFile.size,
      filename: buildAudioDownloadFilename(input.filename),
      mimeType: audioMimeType,
    }
  } catch (error: unknown) {
    await cleanupTempFile(audioFilePath, dependencies.cleanupFile)

    if (error instanceof AppError) {
      throw error
    }

    throw new AppError('音频提取失败，请稍后重试', 502)
  } finally {
    await cleanupTempFile(videoFilePath, dependencies.cleanupFile)
  }
}

export { buildAudioDownloadFilename }
