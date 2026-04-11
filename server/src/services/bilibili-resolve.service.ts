import { env } from '../lib/env.js'
import { isAllowedBilibiliPageHost, isAllowedBilibiliVideoHost } from '../lib/bilibili-hosts.js'
import { AppError } from '../lib/errors.js'
import type { BilibiliSourceMaterial } from './bilibili.types.js'

const trailingBilibiliUrlPunctuationPattern = /[),.;!?，。！？；：、“”'’）\]】》〉」』]+$/g


function extractBilibiliUrlCandidates(input: string): string[] {
  return (input.match(/https:\/\/[^\s]+/g) || [])
    .map((value) => value.replace(trailingBilibiliUrlPunctuationPattern, ''))
}

export function extractBilibiliEntryUrl(input: string): string {
  for (const candidate of extractBilibiliUrlCandidates(input)) {
    try {
      const parsed = new URL(candidate)
      if (parsed.protocol === 'https:' && isAllowedBilibiliPageHost(parsed.hostname)) {
        return candidate
      }
    } catch {
      continue
    }
  }

  throw new AppError('未能从分享文本中提取有效的 B 站链接', 400)
}

function buildRequestHeaders(referer: string): Record<string, string> {
  return {
    'User-Agent': env.BILIBILI_USER_AGENT,
    Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
    'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
    'Cache-Control': 'no-cache',
    Pragma: 'no-cache',
    Referer: referer,
  }
}

function buildMediaRequestHeaders(referer: string): Record<string, string> {
  return {
    'User-Agent': env.BILIBILI_USER_AGENT,
    Referer: referer,
    Origin: 'https://www.bilibili.com',
  }
}

async function fetchHtml(url: string): Promise<{ finalUrl: string, body: string }> {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), env.BILIBILI_FETCH_TIMEOUT_MS)

  try {
    let nextUrl = url

    for (let redirectCount = 0; redirectCount < 5; redirectCount += 1) {
      const response = await fetch(nextUrl, {
        method: 'GET',
        headers: buildRequestHeaders(nextUrl),
        redirect: 'manual',
        signal: controller.signal,
      })

      if (response.status >= 300 && response.status < 400) {
        const location = response.headers.get('location')
        if (!location) {
          throw new AppError('B 站链接返回了无效的跳转地址', 502)
        }

        nextUrl = new URL(location, nextUrl).toString()
        const parsed = new URL(nextUrl)
        if (parsed.protocol !== 'https:' || !isAllowedBilibiliPageHost(parsed.hostname)) {
          throw new AppError('B 站页面跳转到了不受信任的目标地址', 502)
        }
        continue
      }

      return {
        finalUrl: nextUrl,
        body: await response.text(),
      }
    }

    throw new AppError('B 站链接跳转次数过多', 502)
  } catch (error: unknown) {
    if (error instanceof AppError) {
      throw error
    }

    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new AppError('请求 B 站页面超时', 504)
    }

    throw new AppError('请求 B 站页面失败', 502)
  } finally {
    clearTimeout(timeout)
  }
}

function extractFirstJsonBlock(html: string, marker: string): string | undefined {
  const markerIndex = html.indexOf(marker)
  if (markerIndex < 0) {
    return undefined
  }

  const scriptStart = html.indexOf('{', markerIndex)
  if (scriptStart < 0) {
    return undefined
  }

  let depth = 0
  let inString = false
  let escaped = false

  for (let index = scriptStart; index < html.length; index += 1) {
    const character = html[index]

    if (inString) {
      if (escaped) {
        escaped = false
        continue
      }

      if (character === '\\') {
        escaped = true
        continue
      }

      if (character === '"') {
        inString = false
      }
      continue
    }

    if (character === '"') {
      inString = true
      continue
    }

    if (character === '{') {
      depth += 1
      continue
    }

    if (character === '}') {
      depth -= 1
      if (depth === 0) {
        return html.slice(scriptStart, index + 1)
      }
    }
  }

  return undefined
}

function readTextCandidate(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

function getNestedRecord(record: Record<string, unknown>, key: string): Record<string, unknown> | undefined {
  const value = record[key]
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : undefined
}

function getNestedArray(record: Record<string, unknown>, key: string): unknown[] {
  const value = record[key]
  return Array.isArray(value) ? value : []
}

function extractProgressiveUrl(playInfo: Record<string, unknown>): string | undefined {
  const data = getNestedRecord(playInfo, 'data')
  const durlEntries = data ? getNestedArray(data, 'durl') : []
  for (const entry of durlEntries) {
    if (typeof entry !== 'object' || entry === null || Array.isArray(entry)) {
      continue
    }

    const url = readTextCandidate((entry as Record<string, unknown>).url)
    if (!url) {
      continue
    }

    try {
      const parsed = new URL(url)
      if (parsed.protocol === 'https:' && isAllowedBilibiliVideoHost(parsed.hostname)) {
        return url
      }
    } catch {
      continue
    }
  }

  return undefined
}

function extractDashTrackUrl(playInfo: Record<string, unknown>, trackType: 'video' | 'audio'): string | undefined {
  const data = getNestedRecord(playInfo, 'data')
  const dash = data ? getNestedRecord(data, 'dash') : undefined
  const entries = dash ? getNestedArray(dash, trackType) : []

  for (const entry of entries) {
    if (typeof entry !== 'object' || entry === null || Array.isArray(entry)) {
      continue
    }

    const record = entry as Record<string, unknown>
    const urlCandidates = [
      readTextCandidate(record.baseUrl),
      readTextCandidate(record.base_url),
      ...getNestedArray(record, 'backupUrl').map(readTextCandidate),
      ...getNestedArray(record, 'backup_url').map(readTextCandidate),
    ].filter((value): value is string => Boolean(value))

    for (const candidate of urlCandidates) {
      try {
        const parsed = new URL(candidate)
        if (parsed.protocol === 'https:' && isAllowedBilibiliVideoHost(parsed.hostname)) {
          return candidate
        }
      } catch {
        continue
      }
    }
  }

  return undefined
}

function extractTitle(initialState: Record<string, unknown>): string | undefined {
  const videoData = getNestedRecord(initialState, 'videoData')
  return readTextCandidate(videoData?.title) || readTextCandidate(initialState.h1Title)
}

function extractAuthor(initialState: Record<string, unknown>): string | undefined {
  const videoData = getNestedRecord(initialState, 'videoData')
  const owner = videoData ? getNestedRecord(videoData, 'owner') : undefined
  return readTextCandidate(owner?.name)
}

function extractCoverUrl(initialState: Record<string, unknown>): string | undefined {
  const videoData = getNestedRecord(initialState, 'videoData')
  return readTextCandidate(videoData?.pic)
}

function extractVideoId(initialState: Record<string, unknown>, resolvedUrl: string): string | undefined {
  const bvid = readTextCandidate(initialState.bvid)
  if (bvid) {
    return bvid
  }

  const videoData = getNestedRecord(initialState, 'videoData')
  const videoDataBvid = readTextCandidate(videoData?.bvid)
  if (videoDataBvid) {
    return videoDataBvid
  }

  const match = resolvedUrl.match(/\/video\/(BV[0-9A-Za-z]+)/)
  return match?.[1]
}

export async function resolveBilibiliSource(url: string): Promise<BilibiliSourceMaterial> {
  const entryUrl = extractBilibiliEntryUrl(url)
  const { finalUrl, body } = await fetchHtml(entryUrl)

  const initialStateBlock = extractFirstJsonBlock(body, 'window.__INITIAL_STATE__=')
  const playInfoBlock = extractFirstJsonBlock(body, 'window.__playinfo__=')

  if (!initialStateBlock || !playInfoBlock) {
    throw new AppError('未能从 B 站页面中解析到可预览视频信息', 502)
  }

  let initialState: Record<string, unknown>
  let playInfo: Record<string, unknown>

  try {
    initialState = JSON.parse(initialStateBlock) as Record<string, unknown>
    playInfo = JSON.parse(playInfoBlock) as Record<string, unknown>
  } catch {
    throw new AppError('B 站页面返回了无法解析的视频数据', 502)
  }

  const progressiveUrl = extractProgressiveUrl(playInfo)
  const requestHeaders = buildMediaRequestHeaders(finalUrl)

  if (progressiveUrl) {
    return {
      sourceUrl: entryUrl,
      resolvedUrl: finalUrl,
      videoId: extractVideoId(initialState, finalUrl),
      author: extractAuthor(initialState),
      title: extractTitle(initialState),
      coverUrl: extractCoverUrl(initialState),
      playableVideoUrl: progressiveUrl,
      requestHeaders,
      playbackMode: 'progressive',
    }
  }

  const videoTrackUrl = extractDashTrackUrl(playInfo, 'video')
  const audioTrackUrl = extractDashTrackUrl(playInfo, 'audio')

  if (!videoTrackUrl || !audioTrackUrl) {
    throw new AppError('当前 B 站视频缺少可用的音视频双轨，暂不支持预览或下载', 422)
  }

  return {
    sourceUrl: entryUrl,
    resolvedUrl: finalUrl,
    videoId: extractVideoId(initialState, finalUrl),
    author: extractAuthor(initialState),
    title: extractTitle(initialState),
    coverUrl: extractCoverUrl(initialState),
    videoTrackUrl,
    audioTrackUrl,
    requestHeaders,
    playbackMode: 'dash',
  }
}
