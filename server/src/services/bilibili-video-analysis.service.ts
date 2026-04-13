import { AppError } from '../lib/errors.js'
import {
  buildPublicBilibiliAnalysisMediaUrl,
  createBilibiliAnalysisMediaSession,
  deleteBilibiliAnalysisMediaSession,
} from './bilibili-analysis-media.service.js'
import {
  cleanupBilibiliMediaFile,
  createBilibiliMediaClips,
  prepareBilibiliMediaFile,
  type BilibiliMediaClip,
} from './bilibili-media.service.js'
import { logger } from '../lib/logger.js'
import { buildPublicBilibiliProxyUrl, parseBilibiliProxyToken } from './bilibili-proxy.service.js'
import { analyzeVideoContent, type VideoAnalysisResult } from './video-analysis.service.js'

const maxAnalysisDurationSeconds = 10 * 60
const segmentedAnalysisClipDurationSeconds = 30

function extractTokenFromProxyUrl(proxyVideoUrl: string): string {
  let parsedUrl: URL

  try {
    parsedUrl = new URL(proxyVideoUrl, 'http://localhost')
  } catch {
    throw new AppError('视频代理地址无效', 400)
  }

  const match = parsedUrl.pathname.match(/\/api\/bilibili\/proxy\/([^/]+)$/)
  if (!match?.[1]) {
    throw new AppError('视频代理地址无效', 400)
  }

  return decodeURIComponent(match[1])
}

function assertBilibiliAnalysisDuration(durationSeconds: number | undefined): number {
  if (!durationSeconds) {
    throw new AppError('未能识别视频时长，请重新提取后再分析', 422)
  }

  if (durationSeconds > maxAnalysisDurationSeconds) {
    throw new AppError('当前仅支持分析 10 分钟以内的 B 站视频，建议选择 30 秒到 2 分钟的视频', 422)
  }

  return durationSeconds
}

function assertBilibiliAnalysisMediaPublicUrlAvailable(): void {
  buildPublicBilibiliAnalysisMediaUrl('bilibili-analysis-media-preflight')
}

function throwIfAborted(signal: AbortSignal | undefined): void {
  if (signal?.aborted) {
    throw new AppError('分析请求已取消', 499)
  }
}

function formatSegmentTimestamp(totalSeconds: number): string {
  const roundedSeconds = Math.max(0, Math.ceil(totalSeconds))
  const minutes = Math.floor(roundedSeconds / 60)
  const seconds = roundedSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

function formatSegmentLabel(clip: Pick<BilibiliMediaClip, 'clipIndex' | 'startSeconds' | 'endSeconds'>): string {
  return `第 ${clip.clipIndex + 1} 段（${formatSegmentTimestamp(clip.startSeconds)}-${formatSegmentTimestamp(clip.endSeconds)}）`
}

function mergeSegmentedAnalysisField(
  clips: Array<Pick<BilibiliMediaClip, 'clipIndex' | 'startSeconds' | 'endSeconds'>>,
  results: VideoAnalysisResult[],
  selector: (result: VideoAnalysisResult) => string | undefined,
): string | undefined {
  const sections = clips.flatMap((clip, index) => {
    const content = selector(results[index] || {})?.trim()
    if (!content) {
      return []
    }

    return `${formatSegmentLabel(clip)}\n${content}`
  })

  if (sections.length === 0) {
    return undefined
  }

  return sections.join('\n\n')
}

function mergeSegmentedAnalysisResults(clips: BilibiliMediaClip[], results: VideoAnalysisResult[]): VideoAnalysisResult {
  const runIds = results.flatMap((result) => result.runId ? [result.runId] : [])

  return {
    segmented: true,
    clipCount: clips.length,
    runIds: runIds.length ? runIds : undefined,
    videoCaptions: mergeSegmentedAnalysisField(clips, results, (result) => result.videoCaptions),
    videoScript: mergeSegmentedAnalysisField(clips, results, (result) => result.videoScript),
    charactersDescription: mergeSegmentedAnalysisField(clips, results, (result) => result.charactersDescription),
    voiceDescription: mergeSegmentedAnalysisField(clips, results, (result) => result.voiceDescription),
    propsDescription: mergeSegmentedAnalysisField(clips, results, (result) => result.propsDescription),
    sceneDescription: mergeSegmentedAnalysisField(clips, results, (result) => result.sceneDescription),
  }
}

async function cleanupBilibiliMediaClips(clips: BilibiliMediaClip[]): Promise<void> {
  await Promise.all(clips.map(async (clip) => {
    await cleanupBilibiliMediaFile(clip.filePath)
  }))
}

async function analyzeBilibiliSegmentedMedia(input: {
  mediaFile: {
    filePath: string
    fileSize: number
    filename: string
    mimeType: string
  }
  durationSeconds: number
  signal?: AbortSignal
  options: { signal?: AbortSignal }
}): Promise<VideoAnalysisResult> {
  const clips = await createBilibiliMediaClips({
    sourceFilePath: input.mediaFile.filePath,
    durationSeconds: input.durationSeconds,
    filename: input.mediaFile.filename,
    clipDurationSeconds: segmentedAnalysisClipDurationSeconds,
  })
  const results: VideoAnalysisResult[] = []

  try {
    for (const clip of clips) {
      throwIfAborted(input.signal)
      const session = await createBilibiliAnalysisMediaSession({
        filePath: clip.filePath,
        fileSize: clip.fileSize,
        filename: clip.filename,
        mimeType: clip.mimeType,
      })

      try {
        const analysisMediaUrl = buildPublicBilibiliAnalysisMediaUrl(session.id)
        const result = await analyzeVideoContent(analysisMediaUrl, input.options)
        results.push(result)
      } finally {
        try {
          await deleteBilibiliAnalysisMediaSession(session.id)
        } catch (cleanupError: unknown) {
          logger.warn({
            sessionId: session.id,
            err: cleanupError instanceof Error
              ? { name: cleanupError.name, message: cleanupError.message }
              : { message: 'Unknown cleanup error' },
          }, 'Failed to clean up Bilibili analysis media session after segmented analysis')
        }
      }
    }

    return mergeSegmentedAnalysisResults(clips, results)
  } finally {
    await cleanupBilibiliMediaClips(clips)
  }
}

export async function analyzeBilibiliVideoByProxyUrl(
  proxyVideoUrl: string,
  options: { signal?: AbortSignal } = {},
): Promise<VideoAnalysisResult> {
  throwIfAborted(options.signal)

  const token = extractTokenFromProxyUrl(proxyVideoUrl)
  const target = parseBilibiliProxyToken(token)
  const durationSeconds = assertBilibiliAnalysisDuration(target.durationSeconds)

  if (durationSeconds <= segmentedAnalysisClipDurationSeconds) {
    if (target.kind === 'progressive') {
      return analyzeVideoContent(buildPublicBilibiliProxyUrl(token), options)
    }

    assertBilibiliAnalysisMediaPublicUrlAvailable()
    throwIfAborted(options.signal)

    const mediaFile = await prepareBilibiliMediaFile(target)
    throwIfAborted(options.signal)
    let analysisMediaSessionId: string | undefined

    try {
      const session = await createBilibiliAnalysisMediaSession({
        filePath: mediaFile.filePath,
        fileSize: mediaFile.fileSize,
        filename: mediaFile.filename,
        mimeType: mediaFile.mimeType,
      })
      analysisMediaSessionId = session.id

      throwIfAborted(options.signal)

      const analysisMediaUrl = buildPublicBilibiliAnalysisMediaUrl(session.id)
      return await analyzeVideoContent(analysisMediaUrl, options)
    } catch (error: unknown) {
      if (!analysisMediaSessionId) {
        await cleanupBilibiliMediaFile(mediaFile.filePath)
      }

      throw error
    } finally {
      if (analysisMediaSessionId) {
        try {
          await deleteBilibiliAnalysisMediaSession(analysisMediaSessionId)
        } catch (cleanupError: unknown) {
          logger.warn({
            sessionId: analysisMediaSessionId,
            err: cleanupError instanceof Error
              ? { name: cleanupError.name, message: cleanupError.message }
              : { message: 'Unknown cleanup error' },
          }, 'Failed to clean up Bilibili analysis media session after analysis error')
        }
      }
    }
  }

  assertBilibiliAnalysisMediaPublicUrlAvailable()
  throwIfAborted(options.signal)

  const mediaFile = await prepareBilibiliMediaFile(target)
  throwIfAborted(options.signal)

  try {
    return await analyzeBilibiliSegmentedMedia({
      mediaFile,
      durationSeconds,
      signal: options.signal,
      options,
    })
  } finally {
    await cleanupBilibiliMediaFile(mediaFile.filePath)
  }
}
