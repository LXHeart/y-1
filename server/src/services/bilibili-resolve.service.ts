import { createHash } from 'node:crypto'
import { env } from '../lib/env.js'
import { isAllowedBilibiliPageHost, isAllowedBilibiliVideoHost } from '../lib/bilibili-hosts.js'
import { AppError } from '../lib/errors.js'
import type { BilibiliSourceMaterial } from './bilibili.types.js'

const trailingBilibiliUrlPunctuationPattern = /[),.;!?，。！？；：、“”'’）\]】》〉」』]+$/g
const BILIBILI_WBI_MIXIN_KEY_TABLE = [
  46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
  27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
  37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
  22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
] as const

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

function readNumberCandidate(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : undefined
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

function extractDurationSeconds(initialState: Record<string, unknown>, playInfo: Record<string, unknown>): number | undefined {
  const videoData = getNestedRecord(initialState, 'videoData')
  const directDurationSeconds = readNumberCandidate(videoData?.duration)
  if (directDurationSeconds) {
    return Math.ceil(directDurationSeconds)
  }

  const playInfoData = getNestedRecord(playInfo, 'data')
  const timelengthMs = readNumberCandidate(playInfoData?.timelength)
  if (timelengthMs) {
    return Math.ceil(timelengthMs / 1000)
  }

  return undefined
}

function extractAid(initialState: Record<string, unknown>): number | undefined {
  const directAid = readNumberCandidate(initialState.aid)
  if (directAid) {
    return directAid
  }

  const videoData = getNestedRecord(initialState, 'videoData')
  return readNumberCandidate(videoData?.aid)
}

function extractRequestedPageNumber(initialState: Record<string, unknown>, resolvedUrl: string): number {
  try {
    const parsed = new URL(resolvedUrl)
    const pageParam = Number.parseInt(parsed.searchParams.get('p') ?? '', 10)
    if (Number.isInteger(pageParam) && pageParam > 0) {
      return pageParam
    }
  } catch {
    // ignore malformed URL and fall through to page-state defaults
  }

  return readNumberCandidate(initialState.p) ?? 1
}

function extractCid(initialState: Record<string, unknown>, resolvedUrl: string): number | undefined {
  const directCid = readNumberCandidate(initialState.cid)
  if (directCid) {
    return directCid
  }

  const videoData = getNestedRecord(initialState, 'videoData')
  const pages = videoData ? getNestedArray(videoData, 'pages') : []
  const requestedPageNumber = extractRequestedPageNumber(initialState, resolvedUrl)
  let firstCid: number | undefined

  for (const page of pages) {
    if (typeof page !== 'object' || page === null || Array.isArray(page)) {
      continue
    }

    const record = page as Record<string, unknown>
    const cid = readNumberCandidate(record.cid)
    if (!cid) {
      continue
    }

    firstCid ??= cid

    if (readNumberCandidate(record.page) === requestedPageNumber) {
      return cid
    }
  }

  return firstCid
}

function extractWbiKeys(initialState: Record<string, unknown>): { imgKey: string, subKey: string } | undefined {
  const defaultWbiKey = getNestedRecord(initialState, 'defaultWbiKey')
  const imgKey = readTextCandidate(defaultWbiKey?.wbiImgKey)
  const subKey = readTextCandidate(defaultWbiKey?.wbiSubKey)

  if (!imgKey || !subKey) {
    return undefined
  }

  return { imgKey, subKey }
}

function buildWbiMixinKey(imgKey: string, subKey: string): string {
  return BILIBILI_WBI_MIXIN_KEY_TABLE
    .map((index) => `${imgKey}${subKey}`[index])
    .join('')
    .slice(0, 32)
}

function buildSignedPlayurlQuery(params: Record<string, string | number>, imgKey: string, subKey: string): string {
  const sanitizedEntries = Object.entries({
    ...params,
    wts: Math.floor(Date.now() / 1000),
  })
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, value]) => [key, String(value).replace(/[!'()*]/g, '')] as [string, string])

  const query = new URLSearchParams(sanitizedEntries)
  const mixinKey = buildWbiMixinKey(imgKey, subKey)
  const wRid = createHash('md5').update(query.toString() + mixinKey).digest('hex')
  query.set('w_rid', wRid)
  return query.toString()
}

async function fetchPlayInfoByApi(
  initialState: Record<string, unknown>,
  referer: string,
): Promise<Record<string, unknown>> {
  const aid = extractAid(initialState)
  const bvid = extractVideoId(initialState, referer)
  const cid = extractCid(initialState, referer)
  const wbiKeys = extractWbiKeys(initialState)

  if (!aid || !bvid || !cid || !wbiKeys) {
    throw new AppError('未能从 B 站页面中解析到可预览视频信息', 502)
  }

  const query = buildSignedPlayurlQuery({
    avid: aid,
    bvid,
    cid,
    qn: 0,
    fnver: 0,
    fnval: 16,
    fourk: 1,
  }, wbiKeys.imgKey, wbiKeys.subKey)

  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), env.BILIBILI_FETCH_TIMEOUT_MS)

  try {
    const response = await fetch(`https://api.bilibili.com/x/player/wbi/playurl?${query}`, {
      method: 'GET',
      headers: {
        'User-Agent': env.BILIBILI_USER_AGENT,
        Referer: referer,
        Origin: 'https://www.bilibili.com',
        Accept: 'application/json, text/plain, */*',
      },
      signal: controller.signal,
    })

    if (!response.ok) {
      throw new AppError('请求 B 站播放信息失败', response.status === 412 ? 502 : response.status)
    }

    const payload = await response.json() as unknown
    if (typeof payload !== 'object' || payload === null || Array.isArray(payload)) {
      throw new AppError('B 站播放信息返回了无效数据', 502)
    }

    const record = payload as Record<string, unknown>
    const code = typeof record.code === 'number' ? record.code : undefined
    const data = getNestedRecord(record, 'data')

    if (code !== 0 || !data) {
      throw new AppError('请求 B 站播放信息失败', 502)
    }

    return record
  } catch (error: unknown) {
    if (error instanceof AppError) {
      throw error
    }

    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new AppError('请求 B 站播放信息超时', 504)
    }

    throw new AppError('请求 B 站播放信息失败', 502)
  } finally {
    clearTimeout(timeout)
  }
}

export async function resolveBilibiliSource(url: string): Promise<BilibiliSourceMaterial> {
  const entryUrl = extractBilibiliEntryUrl(url)
  const { finalUrl, body } = await fetchHtml(entryUrl)

  const initialStateBlock = extractFirstJsonBlock(body, 'window.__INITIAL_STATE__=')
  const playInfoBlock = extractFirstJsonBlock(body, 'window.__playinfo__=')

  if (!initialStateBlock) {
    throw new AppError('未能从 B 站页面中解析到可预览视频信息', 502)
  }

  let initialState: Record<string, unknown>

  try {
    initialState = JSON.parse(initialStateBlock) as Record<string, unknown>
  } catch {
    throw new AppError('B 站页面返回了无法解析的视频数据', 502)
  }

  let playInfo: Record<string, unknown>

  if (playInfoBlock) {
    try {
      playInfo = JSON.parse(playInfoBlock) as Record<string, unknown>
    } catch {
      throw new AppError('B 站页面返回了无法解析的视频数据', 502)
    }
  } else {
    playInfo = await fetchPlayInfoByApi(initialState, finalUrl)
  }

  const progressiveUrl = extractProgressiveUrl(playInfo)
  const requestHeaders = buildMediaRequestHeaders(finalUrl)
  const durationSeconds = extractDurationSeconds(initialState, playInfo)

  if (progressiveUrl) {
    return {
      sourceUrl: entryUrl,
      resolvedUrl: finalUrl,
      videoId: extractVideoId(initialState, finalUrl),
      author: extractAuthor(initialState),
      title: extractTitle(initialState),
      coverUrl: extractCoverUrl(initialState),
      durationSeconds,
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
    durationSeconds,
    videoTrackUrl,
    audioTrackUrl,
    requestHeaders,
    playbackMode: 'dash',
  }
}
