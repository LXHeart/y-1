import { env } from '../lib/env.js'
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
import { analyzeVideoContent, analyzeVideoForRecreation, type VideoAnalysisRequestConfig, type VideoAnalysisResult, type VideoRecreationResult } from './video-analysis.service.js'

const maxAnalysisDurationSeconds = 10 * 60
const segmentedAnalysisClipDurationSeconds = 30
const maxAdjacentOverlapLines = 3
const minimumComparableTextLength = 6
const minimumAdjacentOverlapLength = 3
const redundantTrailingSegmentMaxDurationSeconds = 12

interface SummaryEntry {
  content: string
  normalized: string
  key?: string
  value: string
}

function extractTokenFromProxyUrl(proxyVideoUrl: string): string {
  let parsedUrl: URL

  try {
    parsedUrl = new URL(proxyVideoUrl, 'http://localhost')
  } catch {
    throw new AppError('视频代理地址无效', 400)
  }

  if (parsedUrl.origin !== 'http://localhost') {
    if (!env.PUBLIC_BACKEND_ORIGIN) {
      throw new AppError('视频代理地址无效', 400)
    }

    let publicBackendUrl: URL
    try {
      publicBackendUrl = new URL(env.PUBLIC_BACKEND_ORIGIN)
    } catch {
      throw new AppError('视频代理地址无效', 400)
    }

    if (parsedUrl.origin !== publicBackendUrl.origin) {
      throw new AppError('视频代理地址无效', 400)
    }
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

function normalizeMergeText(text: string): string {
  return text.replace(/\s+/g, ' ').trim()
}

function splitTranscriptLineParts(line: string): { prefix: string, body: string } {
  const trimmedLine = line.trim()
  const match = trimmedLine.match(/^(\[[^\]]+\])\s*(.*)$/)
  if (!match) {
    return {
      prefix: '',
      body: trimmedLine,
    }
  }

  return {
    prefix: match[1],
    body: match[2] || '',
  }
}

function buildTranscriptLine(prefix: string, body: string): string {
  const trimmedBody = body.trim()
  if (!trimmedBody) {
    return ''
  }

  return prefix ? `${prefix} ${trimmedBody}` : trimmedBody
}

function normalizeTranscriptLine(text: string): string {
  return normalizeMergeText(splitTranscriptLineParts(text).body)
}

function normalizeDescriptionEntryText(text: string): string {
  return normalizeMergeText(text).replace(/^[，。；;、,\s]+|[，。；;、,\s]+$/g, '')
}

function splitTranscriptLines(content: string): string[] {
  return content
    .split(/\r?\n+/)
    .map((line) => line.trim())
    .filter(Boolean)
}

function splitDescriptionEntries(content: string): string[] {
  return content
    .split(/\r?\n+/)
    .flatMap((line) => line.split(/[；;]/))
    .map(normalizeDescriptionEntryText)
    .filter(Boolean)
}

function trimLeadingOverlapText(text: string, overlap: string): string {
  const { prefix, body } = splitTranscriptLineParts(text)
  const normalizedBody = normalizeMergeText(body)
  const trimmedOverlap = overlap.trim()
  if (!normalizedBody.startsWith(trimmedOverlap)) {
    return buildTranscriptLine(prefix, body)
  }

  const trimmedBody = normalizedBody.slice(trimmedOverlap.length).replace(/^[，。；;、,\s]+/, '').trim()
  return buildTranscriptLine(prefix, trimmedBody)
}

function findTextOverlapLength(previousText: string, nextText: string): number {
  const maxLength = Math.min(previousText.length, nextText.length)

  for (let overlapLength = maxLength; overlapLength >= minimumAdjacentOverlapLength; overlapLength -= 1) {
    if (previousText.slice(-overlapLength) === nextText.slice(0, overlapLength)) {
      return overlapLength
    }
  }

  return 0
}

function trimAdjacentOverlap(previousLines: string[], nextLines: string[]): string[] {
  const maxOverlap = Math.min(maxAdjacentOverlapLines, previousLines.length, nextLines.length)
  let trimmedLines = [...nextLines]

  for (let overlapLength = maxOverlap; overlapLength >= 1; overlapLength -= 1) {
    const previousTail = previousLines.slice(-overlapLength).map(normalizeTranscriptLine)
    const nextHead = trimmedLines.slice(0, overlapLength).map(normalizeTranscriptLine)

    if (previousTail.every((line, index) => line === nextHead[index])) {
      trimmedLines = trimmedLines.slice(overlapLength)
      break
    }
  }

  if (!trimmedLines.length || !previousLines.length) {
    return trimmedLines
  }

  const lastPreviousLine = previousLines[previousLines.length - 1]
  const firstNextLine = trimmedLines[0]
  const normalizedPreviousLine = normalizeTranscriptLine(lastPreviousLine)
  const normalizedNextLine = normalizeTranscriptLine(firstNextLine)

  if (!normalizedPreviousLine || !normalizedNextLine) {
    return trimmedLines
  }

  if (
    normalizedNextLine.length >= minimumComparableTextLength
    && normalizedPreviousLine.includes(normalizedNextLine)
  ) {
    return trimmedLines.slice(1)
  }

  if (
    normalizedPreviousLine.length >= minimumComparableTextLength
    && normalizedNextLine.startsWith(normalizedPreviousLine)
  ) {
    const trimmedFirstLine = trimLeadingOverlapText(firstNextLine, normalizedPreviousLine)
    if (!trimmedFirstLine) {
      return trimmedLines.slice(1)
    }

    return [trimmedFirstLine, ...trimmedLines.slice(1)]
  }

  const overlapLength = findTextOverlapLength(normalizedPreviousLine, normalizedNextLine)
  if (overlapLength === 0) {
    return trimmedLines
  }

  const trimmedFirstLine = normalizedNextLine.slice(overlapLength).replace(/^[，。；;、,\s]+/, '').trim()
  if (!trimmedFirstLine) {
    return trimmedLines.slice(1)
  }

  return [trimmedFirstLine, ...trimmedLines.slice(1)]
}

function extractDescriptionEntryParts(entry: string): { key?: string, value: string } {
  const normalizedEntry = normalizeDescriptionEntryText(entry)
  const match = normalizedEntry.match(/^([^：:]{1,30})[：:](.+)$/)
  if (!match?.[1] || !match[2]) {
    return {
      value: normalizedEntry,
    }
  }

  return {
    key: normalizeMergeText(match[1]),
    value: normalizeDescriptionEntryText(match[2]),
  }
}

function choosePreferredEntry(currentEntry: SummaryEntry, nextEntry: SummaryEntry): SummaryEntry {
  return nextEntry.normalized.length > currentEntry.normalized.length ? nextEntry : currentEntry
}

function createSummaryEntry(content: string): SummaryEntry | null {
  const normalizedContent = normalizeDescriptionEntryText(content)
  if (!normalizedContent) {
    return null
  }

  const { key, value } = extractDescriptionEntryParts(normalizedContent)

  return {
    content: normalizedContent,
    normalized: normalizedContent,
    key,
    value,
  }
}

function matchesSummaryEntry(currentEntry: SummaryEntry, nextEntry: SummaryEntry): boolean {
  if (currentEntry.normalized === nextEntry.normalized) {
    return true
  }

  if (currentEntry.key && nextEntry.key && currentEntry.key === nextEntry.key) {
    if (currentEntry.value === nextEntry.value) {
      return true
    }

    if (currentEntry.value.length >= minimumComparableTextLength && currentEntry.value.includes(nextEntry.value)) {
      return true
    }

    return nextEntry.value.length >= minimumComparableTextLength && nextEntry.value.includes(currentEntry.value)
  }

  return false
}

function mergeSummaryEntry(entries: SummaryEntry[], nextEntry: SummaryEntry): SummaryEntry[] {
  const matchedIndex = entries.findIndex((entry) => matchesSummaryEntry(entry, nextEntry))
  if (matchedIndex === -1) {
    return [...entries, nextEntry]
  }

  const preferredEntry = choosePreferredEntry(entries[matchedIndex], nextEntry)
  return entries.map((entry, index) => index === matchedIndex ? preferredEntry : entry)
}

function shouldDropRedundantTrailingSegment(
  clip: Pick<BilibiliMediaClip, 'startSeconds' | 'endSeconds'>,
  trimmedLines: string[],
  isLastClip: boolean,
): boolean {
  if (!isLastClip) {
    return false
  }

  return clip.endSeconds - clip.startSeconds <= redundantTrailingSegmentMaxDurationSeconds && trimmedLines.length === 0
}

function mergeSegmentedScriptField(
  clips: Array<Pick<BilibiliMediaClip, 'clipIndex' | 'startSeconds' | 'endSeconds'>>,
  results: VideoAnalysisResult[],
): string | undefined {
  let mergedLines: string[] = []
  const sections = clips.flatMap((clip, index) => {
    const lines = splitTranscriptLines(results[index]?.videoScript || '')
    if (!lines.length) {
      return []
    }

    const dedupedLines = mergedLines.length ? trimAdjacentOverlap(mergedLines, lines) : lines
    if (shouldDropRedundantTrailingSegment(clip, dedupedLines, index === clips.length - 1) || dedupedLines.length === 0) {
      return []
    }

    mergedLines = [...mergedLines, ...dedupedLines]
    return [[formatSegmentLabel(clip), ...dedupedLines].join('\n')]
  })

  return sections.length ? sections.join('\n\n') : undefined
}

function mergeSegmentedCaptionsField(results: VideoAnalysisResult[]): string | undefined {
  const mergedLines = results.reduce<string[]>((currentLines, result) => {
    const lines = splitTranscriptLines(result.videoCaptions || '')
    if (!lines.length) {
      return currentLines
    }

    const dedupedLines = currentLines.length ? trimAdjacentOverlap(currentLines, lines) : lines
    return dedupedLines.length ? [...currentLines, ...dedupedLines] : currentLines
  }, [])

  return mergedLines.length ? mergedLines.join('\n') : undefined
}

function mergeDistinctSummaryField(
  results: VideoAnalysisResult[],
  selector: (result: VideoAnalysisResult) => string | undefined,
): string | undefined {
  const entries = results.reduce<SummaryEntry[]>((currentEntries, result) => {
    const content = selector(result)?.trim()
    if (!content) {
      return currentEntries
    }

    return splitDescriptionEntries(content)
      .map(createSummaryEntry)
      .filter((entry): entry is SummaryEntry => entry !== null)
      .reduce((mergedEntries, entry) => mergeSummaryEntry(mergedEntries, entry), currentEntries)
  }, [])

  return entries.length ? entries.map((entry) => entry.content).join('\n') : undefined
}

function mergeSegmentedAnalysisResults(clips: BilibiliMediaClip[], results: VideoAnalysisResult[]): VideoAnalysisResult {
  const runIds = Array.from(new Set(results.flatMap((result) => result.runId ? [result.runId] : [])))

  return {
    segmented: true,
    clipCount: clips.length,
    runIds: runIds.length ? runIds : undefined,
    videoCaptions: mergeSegmentedCaptionsField(results),
    videoScript: mergeSegmentedScriptField(clips, results),
    charactersDescription: mergeDistinctSummaryField(results, (result) => result.charactersDescription),
    voiceDescription: mergeDistinctSummaryField(results, (result) => result.voiceDescription),
    propsDescription: mergeDistinctSummaryField(results, (result) => result.propsDescription),
    sceneDescription: mergeDistinctSummaryField(results, (result) => result.sceneDescription),
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
  options: {
    signal?: AbortSignal
    analysisConfig?: VideoAnalysisRequestConfig
  }
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
  options: {
    signal?: AbortSignal
    analysisConfig?: VideoAnalysisRequestConfig
    userId?: string
  } = {},
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

function mergeSegmentedRecreationResults(results: VideoRecreationResult[]): VideoRecreationResult {
  const allScenes = results.flatMap((r) => r.scenes)
  const runIds = Array.from(new Set(results.flatMap((r) => r.runId ? [r.runId] : [])))

  return {
    scenes: allScenes,
    overallStyle: results[0]?.overallStyle,
    runId: runIds[0],
    runIds: runIds.length > 1 ? runIds : undefined,
    segmented: true,
    clipCount: results.length,
  }
}

async function analyzeBilibiliSegmentedForRecreation(input: {
  mediaFile: {
    filePath: string
    fileSize: number
    filename: string
    mimeType: string
  }
  durationSeconds: number
  signal?: AbortSignal
  options: {
    signal?: AbortSignal
    analysisConfig?: VideoAnalysisRequestConfig
    userId?: string
  }
}): Promise<VideoRecreationResult> {
  const clips = await createBilibiliMediaClips({
    sourceFilePath: input.mediaFile.filePath,
    durationSeconds: input.durationSeconds,
    filename: input.mediaFile.filename,
    clipDurationSeconds: segmentedAnalysisClipDurationSeconds,
  })
  const results: VideoRecreationResult[] = []

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
        const result = await analyzeVideoForRecreation(analysisMediaUrl, input.options)
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
          }, 'Failed to clean up Bilibili analysis media session after segmented recreation analysis')
        }
      }
    }

    return mergeSegmentedRecreationResults(results)
  } finally {
    await cleanupBilibiliMediaClips(clips)
  }
}

export async function analyzeBilibiliVideoForRecreation(
  proxyVideoUrl: string,
  options: {
    signal?: AbortSignal
    analysisConfig?: VideoAnalysisRequestConfig
    userId?: string
  } = {},
): Promise<VideoRecreationResult> {
  throwIfAborted(options.signal)

  const token = extractTokenFromProxyUrl(proxyVideoUrl)
  const target = parseBilibiliProxyToken(token)
  const durationSeconds = assertBilibiliAnalysisDuration(target.durationSeconds)

  if (durationSeconds <= segmentedAnalysisClipDurationSeconds) {
    if (target.kind === 'progressive') {
      return analyzeVideoForRecreation(buildPublicBilibiliProxyUrl(token), options)
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
      return await analyzeVideoForRecreation(analysisMediaUrl, options)
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
          }, 'Failed to clean up Bilibili analysis media session after recreation analysis error')
        }
      }
    }
  }

  assertBilibiliAnalysisMediaPublicUrlAvailable()
  throwIfAborted(options.signal)

  const mediaFile = await prepareBilibiliMediaFile(target)
  throwIfAborted(options.signal)

  try {
    return await analyzeBilibiliSegmentedForRecreation({
      mediaFile,
      durationSeconds,
      signal: options.signal,
      options,
    })
  } finally {
    await cleanupBilibiliMediaFile(mediaFile.filePath)
  }
}
