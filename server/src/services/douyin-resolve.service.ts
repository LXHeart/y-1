import * as cheerio from 'cheerio'
import { fetchPageWithBrowser } from '../lib/browser.js'
import { isAllowedDouyinVideoHost } from '../lib/douyin-hosts.js'
import { env } from '../lib/env.js'
import { AppError } from '../lib/errors.js'
import { fetchText } from '../lib/http.js'
import { logger } from '../lib/logger.js'
import { getPersistedDouyinStorageStatePath, markDouyinSessionExpired, markDouyinSessionUsed } from './douyin-session.service.js'

const mobileShareUserAgent = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1'

export interface DouyinSourceMaterial {
  sourceUrl: string
  resolvedUrl: string
  videoId?: string
  author?: string
  title?: string
  coverUrl?: string
  durationSeconds?: number
  metaDescription?: string
  visibleText: string
  pageJsonSnippets: string[]
  browserJsonSnippets: string[]
  networkJsonSnippets: string[]
  mediaUrls: string[]
  isChallengePage: boolean
  challengeHints: string[]
  fetchMode: 'anonymous' | 'storage_state'
  fetchStage: 'desktop_http' | 'mobile_http' | 'browser'
  usedSession: boolean
  attemptedSession: boolean
}

export interface DouyinVideoAsset {
  sourceUrl: string
  resolvedUrl: string
  videoId?: string
  coverUrl?: string
  playableVideoUrl: string
  requestHeaders?: Record<string, string>
  usedSession: boolean
  fetchStage: 'page_json' | 'browser_json' | 'browser_network'
  snippetRank: number
}

function extractVideoId(url: string): string | undefined {
  const match = url.match(/video\/(\d+)/) || url.match(/modal_id=(\d+)/) || url.match(/aweme_id=(\d+)/)
  return match?.[1]
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function readNumberCandidate(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : undefined
}

function normalizeDurationSeconds(value: unknown): number | undefined {
  const duration = readNumberCandidate(value)
  if (!duration) {
    return undefined
  }

  return duration >= 1000 ? Math.ceil(duration / 1000) : Math.ceil(duration)
}

function getNestedRecord(value: unknown, key: string): Record<string, unknown> | undefined {
  if (!isRecord(value)) {
    return undefined
  }

  const nestedValue = value[key]
  return isRecord(nestedValue) ? nestedValue : undefined
}

function extractDurationSecondsFromStructuredData(value: unknown): number | undefined {
  const candidates = [
    value,
    getNestedRecord(value, 'data'),
    getNestedRecord(value, 'aweme_detail'),
    getNestedRecord(value, 'awemeDetail'),
    getNestedRecord(value, 'itemInfo'),
    getNestedRecord(getNestedRecord(value, 'itemInfo'), 'itemStruct'),
    getNestedRecord(value, 'videoDetail'),
    getNestedRecord(getNestedRecord(value, 'app'), 'videoDetail'),
  ]

  for (const candidate of candidates) {
    const videoRecord = getNestedRecord(candidate, 'video')
    const durationSeconds = normalizeDurationSeconds(
      videoRecord?.duration_ms
      ?? videoRecord?.durationMs
      ?? videoRecord?.duration,
    )
    if (durationSeconds) {
      return durationSeconds
    }
  }

  return undefined
}

function extractDurationSecondsFromSnippet(content: string): number | undefined {
  const normalizedContent = normalizeEscapedUrlContent(content)
  const directMatch = normalizedContent.match(/"video"\s*:\s*\{[\s\S]{0,1600}?"duration(?:_ms|Ms)?"\s*:\s*(\d+)/i)?.[1]
  const directDurationSeconds = normalizeDurationSeconds(directMatch ? Number(directMatch) : undefined)
  if (directDurationSeconds) {
    return directDurationSeconds
  }

  const jsonStartIndex = normalizedContent.indexOf('{')
  const jsonEndIndex = normalizedContent.lastIndexOf('}')
  if (jsonStartIndex === -1 || jsonEndIndex <= jsonStartIndex) {
    return undefined
  }

  try {
    const parsed = JSON.parse(normalizedContent.slice(jsonStartIndex, jsonEndIndex + 1)) as unknown
    return extractDurationSecondsFromStructuredData(parsed)
  } catch {
    return undefined
  }
}

function extractDurationSecondsFromSnippets(input: {
  pageJsonSnippets: string[]
  browserJsonSnippets: string[]
  networkJsonSnippets: string[]
}): number | undefined {
  const snippets = [
    ...input.networkJsonSnippets,
    ...input.browserJsonSnippets,
    ...input.pageJsonSnippets,
  ]

  for (const snippet of snippets) {
    const durationSeconds = extractDurationSecondsFromSnippet(snippet)
    if (durationSeconds) {
      return durationSeconds
    }
  }

  return undefined
}

function collectJsonSnippets(html: string): string[] {
  const scriptMatches = html.match(/<script[^>]*>([\s\S]*?)<\/script>/gi) || []
  return scriptMatches
    .map((script) => script.replace(/<script[^>]*>|<\/script>/gi, '').trim())
    .filter((content) => {
      return content.startsWith('{')
        || content.startsWith('window.__')
        || content.includes('SIGI_STATE')
        || content.includes('__INITIAL_STATE__')
        || content.includes('__NEXT_DATA__')
        || content.includes('RENDER_DATA')
        || content.includes('aweme')
        || content.includes('videoDetail')
    })
    .slice(0, 20)
}

function extractMetaContent($: cheerio.CheerioAPI, selectors: string[]): string | undefined {
  for (const selector of selectors) {
    const value = $(selector).attr('content')?.trim()
    if (value) {
      return value
    }
  }
  return undefined
}

function detectChallengePage(html: string, visibleText: string): { isChallengePage: boolean, challengeHints: string[] } {
  const hints = [
    'Please wait',
    'waf_js',
    '_wafchallengeid',
    'captcha',
    'verify',
    '安全验证',
    '验证中',
    'window.WAFJS',
    'argus-csp-token',
    'verifyCenter',
    'secsdk',
    'bdms',
  ].filter((token) => html.includes(token) || visibleText.includes(token))

  return {
    isChallengePage: hints.length > 0,
    challengeHints: hints,
  }
}

function parseShareMetaDescription(metaDescription?: string): {
  title?: string
  author?: string
  rawText?: string
} {
  if (!metaDescription) {
    return {}
  }

  const cleaned = metaDescription.replace(/\s+/g, ' ').trim()
  const parts = cleaned.split(/\s+-\s+/)
  const contentPart = parts[0]?.trim() || undefined
  const authorPart = parts[1]?.match(/(.+?)于\d{8}发布在抖音/)?.[1]?.trim()

  return {
    title: contentPart,
    author: authorPart,
    rawText: contentPart,
  }
}

function normalizeVisibleText(text: string): string {
  const normalized = text.replace(/\s+/g, ' ').trim()
  const scriptStart = normalized.search(/(?:!function\(|window\.|var\s+[a-zA-Z_$][\w$]*\s*=\s*function\()/)
  const trimmed = scriptStart >= 0 ? normalized.slice(0, scriptStart).trim() : normalized
  return trimmed.slice(0, 6000)
}

const trailingDouyinUrlPunctuationPattern = /[),.;!?，。！？；：、“”'’）\]】》〉」』]+$/g

export function extractDouyinEntryUrl(input: string): string {
  const matches = input.match(/https:\/\/[^\s]+/g) || []

  for (const match of matches) {
    const normalized = match.replace(trailingDouyinUrlPunctuationPattern, '')

    try {
      const parsed = new URL(normalized)
      const hostname = parsed.hostname.toLowerCase()
      const isAllowedHost = hostname === 'douyin.com'
        || hostname === 'www.douyin.com'
        || hostname === 'v.douyin.com'
        || hostname === 'iesdouyin.com'
        || hostname === 'www.iesdouyin.com'

      if (parsed.protocol === 'https:' && isAllowedHost) {
        return normalized
      }
    } catch {
      continue
    }
  }

  throw new AppError('未能从分享文本中提取有效的抖音链接', 400)
}

function buildRequestHeaders(userAgent: string, referer: string): Record<string, string> {
  return {
    'User-Agent': userAgent,
    Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
    'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
    'Cache-Control': 'no-cache',
    Pragma: 'no-cache',
    Referer: referer,
  }
}

function logFetchAttempt(event: string, payload: Record<string, unknown>): void {
  logger.info(payload, event)
}

function serializeError(error: unknown): Record<string, unknown> {
  if (error instanceof AppError) {
    return {
      name: error.name,
      message: error.message,
      statusCode: error.statusCode,
    }
  }

  if (error instanceof Error) {
    return {
      name: error.name,
      message: error.message,
    }
  }

  return {
    message: 'Unknown error',
  }
}

function logFetchFailure(event: string, payload: Record<string, unknown>, error: unknown): void {
  logger.warn({ ...payload, err: serializeError(error) }, event)
}

function durationSince(startedAt: number): number {
  return Date.now() - startedAt
}

function summarizeResolvedUrl(url: string): { host: string, path: string } {
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

function summarizeMaterialForLog(material: DouyinSourceMaterial): Record<string, unknown> {
  return {
    resolvedTarget: summarizeResolvedUrl(material.resolvedUrl),
    videoId: material.videoId,
    durationSeconds: material.durationSeconds,
    fetchMode: material.fetchMode,
    fetchStage: material.fetchStage,
    usedSession: material.usedSession,
    attemptedSession: material.attemptedSession,
    isChallengePage: material.isChallengePage,
    challengeHints: material.challengeHints,
    isSharePage: material.resolvedUrl.includes('iesdouyin.com/share/video/'),
    assetResolvable: canResolveDouyinVideoAsset(material),
    pageJsonSnippetCount: material.pageJsonSnippets.length,
    browserJsonSnippetCount: material.browserJsonSnippets.length,
    networkJsonSnippetCount: material.networkJsonSnippets.length,
    visibleTextLength: material.visibleText.length,
    hasTitle: Boolean(material.title),
    hasAuthor: Boolean(material.author),
    hasMetaDescription: Boolean(material.metaDescription),
    hasCoverUrl: Boolean(material.coverUrl),
  }
}

function logFetchStageTiming(
  requestUrl: string,
  fetchMode: DouyinSourceMaterial['fetchMode'],
  fetchStage: DouyinSourceMaterial['fetchStage'],
  startedAt: number,
  material?: DouyinSourceMaterial,
): void {
  logger.info({
    requestTarget: summarizeResolvedUrl(requestUrl),
    ...(material ? summarizeMaterialForLog(material) : {}),
    fetchMode,
    fetchStage,
    durationMs: durationSince(startedAt),
  }, 'Douyin fetch stage timing')
}

function logResolveTiming(requestUrl: string, startedAt: number, material: DouyinSourceMaterial, attemptedSession: boolean): void {
  logger.info({
    requestTarget: summarizeResolvedUrl(requestUrl),
    resolvedTarget: summarizeResolvedUrl(material.resolvedUrl),
    durationMs: durationSince(startedAt),
    finalStage: material.fetchStage,
    finalMode: material.fetchMode,
    usedSession: material.usedSession,
    attemptedSession,
    isChallengePage: material.isChallengePage,
    assetResolvable: canResolveDouyinVideoAsset(material),
  }, 'Douyin resolve timing')
}

function logResolveFailure(requestUrl: string, startedAt: number, attemptedSession: boolean, error: unknown): void {
  logger.warn({
    requestTarget: summarizeResolvedUrl(requestUrl),
    durationMs: durationSince(startedAt),
    attemptedSession,
    err: serializeError(error),
  }, 'Douyin resolve failed')
}

function logSelectedMaterial(material: DouyinSourceMaterial): void {
  logger.info(summarizeMaterialForLog(material), 'Douyin resolve selected material')
}

function shouldPreferMaterial(candidate: DouyinSourceMaterial, current: DouyinSourceMaterial): boolean {
  if (candidate.isChallengePage !== current.isChallengePage) {
    return !candidate.isChallengePage
  }

  const candidateCanResolveAsset = canResolveDouyinVideoAsset(candidate)
  const currentCanResolveAsset = canResolveDouyinVideoAsset(current)
  if (candidateCanResolveAsset !== currentCanResolveAsset) {
    return candidateCanResolveAsset
  }

  const candidateHasPlayableHints = candidate.networkJsonSnippets.length > 0 || candidate.browserJsonSnippets.length > 0
  const currentHasPlayableHints = current.networkJsonSnippets.length > 0 || current.browserJsonSnippets.length > 0
  if (candidateHasPlayableHints !== currentHasPlayableHints) {
    return candidateHasPlayableHints
  }

  const candidateHasTitle = Boolean(candidate.title)
  const currentHasTitle = Boolean(current.title)
  if (candidateHasTitle !== currentHasTitle) {
    return candidateHasTitle
  }

  const candidateIsSharePage = candidate.resolvedUrl.includes('iesdouyin.com/share/video/')
  const currentIsSharePage = current.resolvedUrl.includes('iesdouyin.com/share/video/')
  if (candidateIsSharePage !== currentIsSharePage) {
    return candidateIsSharePage
  }

  return candidate.visibleText.length > current.visibleText.length
}

function isCanonicalDesktopVideoUrl(url: string, videoId: string): boolean {
  try {
    const parsed = new URL(url)
    const hostname = parsed.hostname.toLowerCase()

    return (hostname === 'www.douyin.com' || hostname === 'douyin.com')
      && new RegExp(`^/video/${videoId}/?$`).test(parsed.pathname)
  } catch {
    return false
  }
}

function shouldRetryDesktopVideoPage(source: DouyinSourceMaterial): boolean {
  return source.fetchMode === 'anonymous'
    && !source.isChallengePage
    && !canResolveDouyinVideoAsset(source)
    && Boolean(source.videoId)
    && !source.resolvedUrl.includes('/share/video/')
    && !isCanonicalDesktopVideoUrl(source.resolvedUrl, source.videoId as string)
}

function buildCanonicalDesktopVideoUrl(videoId: string): string {
  return `https://www.douyin.com/video/${videoId}`
}

function mergeJsonSnippets(primary: string[], extra: string[] = []): string[] {
  return [
    ...primary.map((content, index) => ({
      content,
      rank: rankJsonSnippet(content),
      sourcePriority: 0,
      index,
    })),
    ...extra.map((content, index) => ({
      content,
      rank: rankJsonSnippet(content),
      sourcePriority: 1,
      index,
    })),
  ]
    .sort((left, right) => {
      if (right.rank !== left.rank) {
        return right.rank - left.rank
      }
      if (left.sourcePriority !== right.sourcePriority) {
        return left.sourcePriority - right.sourcePriority
      }
      return left.index - right.index
    })
    .map((item) => item.content)
    .filter((content, index, array) => array.indexOf(content) === index)
    .slice(0, 20)
}

function mergeMaterialFields(preferred: DouyinSourceMaterial, fallback: DouyinSourceMaterial): DouyinSourceMaterial {
  return {
    ...preferred,
    videoId: preferred.videoId || fallback.videoId,
    author: preferred.author || fallback.author,
    title: preferred.title || fallback.title,
    coverUrl: preferred.coverUrl || fallback.coverUrl,
    durationSeconds: preferred.durationSeconds || fallback.durationSeconds,
    metaDescription: preferred.metaDescription || fallback.metaDescription,
    visibleText: preferred.visibleText || fallback.visibleText,
    pageJsonSnippets: mergeJsonSnippets(preferred.pageJsonSnippets, fallback.pageJsonSnippets),
    browserJsonSnippets: mergeJsonSnippets(preferred.browserJsonSnippets, fallback.browserJsonSnippets),
    networkJsonSnippets: mergeJsonSnippets(preferred.networkJsonSnippets, fallback.networkJsonSnippets),
    challengeHints: preferred.challengeHints.length > 0 ? preferred.challengeHints : fallback.challengeHints,
  }
}

function selectPreferredMaterial(current: DouyinSourceMaterial, candidate: DouyinSourceMaterial | undefined): DouyinSourceMaterial {
  if (!candidate) {
    return current
  }

  const preferred = shouldPreferMaterial(candidate, current) ? candidate : current
  const fallback = preferred === candidate ? current : candidate
  return mergeMaterialFields(preferred, fallback)
}

function rankJsonSnippet(content: string): number {
  let score = 0

  if (/aweme|aweme_detail|detail|videoDetail|itemInfo/i.test(content)) {
    score += 6
  }
  if (/desc|caption|author|nickname|unique_id/i.test(content)) {
    score += 4
  }
  if (/__INITIAL_STATE__|SIGI_STATE|__NEXT_DATA__|RENDER_DATA/i.test(content)) {
    score += 3
  }

  return score
}

function parseSourceMaterial(
  sourceUrl: string,
  finalUrl: string,
  body: string,
  fetchMode: 'anonymous' | 'storage_state',
  fetchStage: 'desktop_http' | 'mobile_http' | 'browser',
  extraPageJsonSnippets: string[] = [],
  extraNetworkJsonSnippets: string[] = [],
  extraMediaUrls: string[] = [],
): DouyinSourceMaterial {
  const parsedUrl = new URL(finalUrl)
  const isSharePage = parsedUrl.hostname.toLowerCase().endsWith('iesdouyin.com')
  const $ = cheerio.load(body)
  const pageTitle = $('title').text().trim() || undefined
  const title = extractMetaContent($, [
    'meta[property="og:title"]',
    'meta[name="title"]',
    'meta[name="twitter:title"]',
  ]) || pageTitle
  const metaDescription = extractMetaContent($, [
    'meta[name="description"]',
    'meta[property="og:description"]',
    'meta[name="twitter:description"]',
  ])
  const coverUrl = extractMetaContent($, [
    'meta[property="og:image"]',
    'meta[name="twitter:image"]',
  ])
  const author = extractMetaContent($, [
    'meta[name="author"]',
    'meta[property="og:site_name"]',
  ])
  const visibleText = normalizeVisibleText($('body').text())
  const normalizedTitle = title === '抖音-记录美好生活' ? undefined : title
  const normalizedMetaDescription = metaDescription === '抖音，记录美好生活' ? undefined : metaDescription
  const shareMeta = parseShareMetaDescription(normalizedMetaDescription)
  const hasVideoId = Boolean(extractVideoId(finalUrl))
  const looksLikeShareMetadata = isSharePage && (Boolean(normalizedMetaDescription) || hasVideoId)
  const contentText = [shareMeta.rawText, visibleText].filter(Boolean).join(' ')
  const pageJsonSnippets = mergeJsonSnippets(collectJsonSnippets(body), extraPageJsonSnippets)
  const browserJsonSnippets = fetchStage === 'browser' ? mergeJsonSnippets(extraPageJsonSnippets) : []
  const networkJsonSnippets = fetchStage === 'browser' ? mergeJsonSnippets(extraNetworkJsonSnippets) : []
  const durationSeconds = extractDurationSecondsFromSnippets({
    pageJsonSnippets,
    browserJsonSnippets,
    networkJsonSnippets,
  })
  const { isChallengePage, challengeHints } = detectChallengePage(body, visibleText)

  if (!looksLikeShareMetadata && !contentText && !normalizedTitle && !normalizedMetaDescription && pageJsonSnippets.length === 0) {
    throw new AppError('未能从抖音页面提取有效内容', 502)
  }

  return {
    sourceUrl,
    resolvedUrl: finalUrl,
    videoId: extractVideoId(finalUrl),
    author: shareMeta.author || author,
    title: shareMeta.title || normalizedTitle,
    coverUrl,
    durationSeconds,
    metaDescription: normalizedMetaDescription,
    visibleText: contentText.slice(0, 6000),
    pageJsonSnippets,
    browserJsonSnippets,
    networkJsonSnippets,
    mediaUrls: Array.from(new Set(extraMediaUrls)),
    isChallengePage,
    challengeHints,
    fetchMode,
    fetchStage,
    usedSession: fetchMode !== 'anonymous',
    attemptedSession: fetchMode !== 'anonymous',
  }
}

function shouldSkipBrowserMobileRetry(bestMaterial: DouyinSourceMaterial, shouldPreferMobileBrowser: boolean): boolean {
  return !shouldPreferMobileBrowser
    && !bestMaterial.isChallengePage
    && bestMaterial.fetchStage === 'desktop_http'
    && bestMaterial.resolvedUrl.includes('www.douyin.com/video/')
}

function shouldSkipMobileHttpForDirectVideoUrl(requestUrl: string, bestMaterial: DouyinSourceMaterial): boolean {
  return isCanonicalDesktopVideoUrl(requestUrl, bestMaterial.videoId as string)
    && !bestMaterial.isChallengePage
    && bestMaterial.fetchStage === 'desktop_http'
    && !canResolveDouyinVideoAsset(bestMaterial)
}

async function resolveDouyinSourceWithMode(
  url: string,
  fetchMode: 'anonymous' | 'storage_state',
  desktopUserAgent: string,
  storageStatePath?: string,
): Promise<DouyinSourceMaterial> {
  logFetchAttempt('Douyin fetch start', {
    requestTarget: summarizeResolvedUrl(url),
    fetchMode,
    sessionConfigured: fetchMode === 'storage_state' ? Boolean(storageStatePath) : false,
    desktopUserAgent,
  })

  if (fetchMode === 'storage_state') {
    if (!storageStatePath) {
      throw new AppError('未找到可复用的抖音登录态', 502)
    }

    const browserStageStartedAt = Date.now()

    try {
      const browserResponse = await fetchPageWithBrowser(url, {
        desktopUserAgent,
        storageStatePath,
      })
      const browserMaterial = parseSourceMaterial(
        url,
        browserResponse.finalUrl,
        browserResponse.html,
        fetchMode,
        'browser',
        browserResponse.pageJsonSnippets,
        browserResponse.networkJsonSnippets,
        browserResponse.mediaUrls,
      )

      logFetchAttempt('Douyin fetch stage result', summarizeMaterialForLog(browserMaterial))
      logFetchStageTiming(url, fetchMode, 'browser', browserStageStartedAt, browserMaterial)
      return browserMaterial
    } catch (error: unknown) {
      logFetchFailure('Douyin browser fetch failed', {
        requestTarget: summarizeResolvedUrl(url),
        fetchMode,
        fetchStage: 'browser',
        durationMs: durationSince(browserStageStartedAt),
      }, error)
      throw error
    }
  }

  const desktopStageStartedAt = Date.now()
  let initialMaterial: DouyinSourceMaterial

  try {
    const initialResponse = await fetchText(
      url,
      {
        headers: buildRequestHeaders(desktopUserAgent, 'https://www.douyin.com/'),
      },
      env.DOUYIN_FETCH_TIMEOUT_MS,
    )

    initialMaterial = parseSourceMaterial(url, initialResponse.finalUrl, initialResponse.body, fetchMode, 'desktop_http')
  } catch (error: unknown) {
    logFetchFailure('Douyin desktop fetch failed', {
      requestTarget: summarizeResolvedUrl(url),
      fetchMode,
      fetchStage: 'desktop_http',
      durationMs: durationSince(desktopStageStartedAt),
    }, error)
    throw error
  }

  logFetchAttempt('Douyin fetch stage result', summarizeMaterialForLog(initialMaterial))
  logFetchStageTiming(url, fetchMode, 'desktop_http', desktopStageStartedAt, initialMaterial)

  if (canResolveDouyinVideoAsset(initialMaterial)) {
    return initialMaterial
  }

  let bestMaterial = initialMaterial

  if (shouldRetryDesktopVideoPage(initialMaterial)) {
    const canonicalDesktopUrl = buildCanonicalDesktopVideoUrl(initialMaterial.videoId as string)
    const canonicalDesktopStageStartedAt = Date.now()

    try {
      const canonicalDesktopResponse = await fetchText(
        canonicalDesktopUrl,
        {
          headers: buildRequestHeaders(desktopUserAgent, 'https://www.douyin.com/'),
        },
        env.DOUYIN_FETCH_TIMEOUT_MS,
      )

      const canonicalDesktopMaterial = parseSourceMaterial(
        url,
        canonicalDesktopResponse.finalUrl,
        canonicalDesktopResponse.body,
        fetchMode,
        'desktop_http',
      )

      logFetchAttempt('Douyin canonical desktop fetch stage result', summarizeMaterialForLog(canonicalDesktopMaterial))
      logFetchStageTiming(canonicalDesktopUrl, fetchMode, 'desktop_http', canonicalDesktopStageStartedAt, canonicalDesktopMaterial)

      if (canResolveDouyinVideoAsset(canonicalDesktopMaterial)) {
        return canonicalDesktopMaterial
      }

      bestMaterial = selectPreferredMaterial(bestMaterial, canonicalDesktopMaterial)
    } catch (error: unknown) {
      logFetchFailure('Douyin canonical desktop fetch failed', {
        requestTarget: summarizeResolvedUrl(canonicalDesktopUrl),
        fetchMode,
        fetchStage: 'desktop_http',
        durationMs: durationSince(canonicalDesktopStageStartedAt),
      }, error)
    }
  }

  let shouldPreferMobileBrowser = false
  const skippedMobileHttpForDirectVideoUrl = shouldSkipMobileHttpForDirectVideoUrl(url, bestMaterial)

  if (!skippedMobileHttpForDirectVideoUrl) {
    const mobileStageStartedAt = Date.now()

    try {
      const mobileShareResponse = await fetchText(
        url,
        {
          headers: buildRequestHeaders(mobileShareUserAgent, 'https://www.iesdouyin.com/'),
        },
        env.DOUYIN_FETCH_TIMEOUT_MS,
      )

      const mobileShareMaterial = parseSourceMaterial(url, mobileShareResponse.finalUrl, mobileShareResponse.body, fetchMode, 'mobile_http')

      logFetchAttempt('Douyin fetch stage result', summarizeMaterialForLog(mobileShareMaterial))
      logFetchStageTiming(url, fetchMode, 'mobile_http', mobileStageStartedAt, mobileShareMaterial)

      if (canResolveDouyinVideoAsset(mobileShareMaterial)) {
        return mobileShareMaterial
      }

      shouldPreferMobileBrowser = mobileShareMaterial.resolvedUrl.includes('iesdouyin.com/share/video/')
      bestMaterial = selectPreferredMaterial(bestMaterial, mobileShareMaterial)

      if (canResolveDouyinVideoAsset(bestMaterial)) {
        return bestMaterial
      }
    } catch (error: unknown) {
      logFetchFailure('Douyin mobile fetch failed', {
        requestTarget: summarizeResolvedUrl(url),
        fetchMode,
        fetchStage: 'mobile_http',
        durationMs: durationSince(mobileStageStartedAt),
      }, error)
    }
  }

  const browserStageStartedAt = Date.now()

  try {
    const allowMobileRetry = skippedMobileHttpForDirectVideoUrl
      || !shouldSkipBrowserMobileRetry(bestMaterial, shouldPreferMobileBrowser)
    const browserResponse = await fetchPageWithBrowser(url, {
      desktopUserAgent,
      preferMobile: shouldPreferMobileBrowser,
      allowMobileRetry,
    })
    const browserMaterial = parseSourceMaterial(
      url,
      browserResponse.finalUrl,
      browserResponse.html,
      fetchMode,
      'browser',
      browserResponse.pageJsonSnippets,
      browserResponse.networkJsonSnippets,
      browserResponse.mediaUrls,
    )

    logFetchAttempt('Douyin fetch stage result', summarizeMaterialForLog(browserMaterial))
    logFetchStageTiming(url, fetchMode, 'browser', browserStageStartedAt, browserMaterial)

    return selectPreferredMaterial(bestMaterial, browserMaterial)
  } catch (error: unknown) {
    logFetchFailure('Douyin browser fetch failed', {
      requestTarget: summarizeResolvedUrl(url),
      fetchMode,
      fetchStage: 'browser',
      durationMs: durationSince(browserStageStartedAt),
    }, error)

    return bestMaterial
  }
}

function normalizeEscapedUrlContent(content: string): string {
  return content
    .replace(/\\u002F/g, '/')
    .replace(/\\u0026/g, '&')
    .replace(/\\\//g, '/')
}

function isPlayableVideoMediaUrl(url: string): boolean {
  try {
    const parsed = new URL(url)
    return isAllowedDouyinVideoHost(parsed.hostname)
  } catch {
    return false
  }
}

function extractPlayAddressFromSnippet(content: string): string | undefined {
  const normalizedContent = normalizeEscapedUrlContent(content)
  const matches = [
    normalizedContent.match(/https?:\/\/[^"'\\]+playwm[^"'\\]+/i),
    normalizedContent.match(/https?:\/\/[^"'\\]+play(?:\/|\?)[^"'\\]*/i),
    normalizedContent.match(/https?:\/\/[^"'\\]+download[^"'\\]+/i),
  ]

  const directMatch = matches.find(Boolean)?.[0]
  if (directMatch && isPlayableVideoMediaUrl(directMatch)) {
    return directMatch
  }

  try {
    const parsed = JSON.parse(content) as Record<string, unknown>
    const serialized = normalizeEscapedUrlContent(JSON.stringify(parsed))
    const matchedUrl = serialized.match(/https?:\/\/[^"'\\]+(?:playwm|play(?:\/|\?)|download)[^"'\\]*/i)?.[0]
    return matchedUrl && isPlayableVideoMediaUrl(matchedUrl) ? matchedUrl : undefined
  } catch {
    return undefined
  }
}

function summarizePlayableVideoUrl(url: string): { host: string, path: string } {
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

function buildVideoAsset(
  source: DouyinSourceMaterial,
  playableVideoUrl: string,
  fetchStage: DouyinVideoAsset['fetchStage'],
  snippetRank: number,
): DouyinVideoAsset {
  return {
    sourceUrl: source.sourceUrl,
    resolvedUrl: source.resolvedUrl,
    videoId: source.videoId,
    coverUrl: source.coverUrl,
    playableVideoUrl,
    requestHeaders: {
      'User-Agent': env.DOUYIN_USER_AGENT,
      Referer: source.resolvedUrl,
    },
    usedSession: source.usedSession,
    fetchStage,
    snippetRank,
  }
}

function findPlayableVideoCandidate(source: DouyinSourceMaterial): {
  playableVideoUrl: string
  fetchStage: DouyinVideoAsset['fetchStage']
  snippetRank: number
  selectedBy: 'browser_media_response' | 'json_snippet'
} | undefined {
  for (const [index, mediaUrl] of source.mediaUrls.entries()) {
    if (!isPlayableVideoMediaUrl(mediaUrl)) {
      continue
    }

    return {
      playableVideoUrl: mediaUrl,
      fetchStage: 'browser_network',
      snippetRank: index,
      selectedBy: 'browser_media_response',
    }
  }

  const snippetGroups: Array<{ snippets: string[], fetchStage: DouyinVideoAsset['fetchStage'] }> = [
    { snippets: source.networkJsonSnippets, fetchStage: 'browser_network' },
    { snippets: source.browserJsonSnippets, fetchStage: 'browser_json' },
    { snippets: source.pageJsonSnippets, fetchStage: 'page_json' },
  ]

  for (const group of snippetGroups) {
    for (const [index, snippet] of group.snippets.entries()) {
      const playableVideoUrl = extractPlayAddressFromSnippet(snippet)
      if (!playableVideoUrl) {
        continue
      }

      return {
        playableVideoUrl,
        fetchStage: group.fetchStage,
        snippetRank: index,
        selectedBy: 'json_snippet',
      }
    }
  }

  return undefined
}

function canResolveDouyinVideoAsset(source: DouyinSourceMaterial): boolean {
  return !source.isChallengePage && Boolean(findPlayableVideoCandidate(source))
}

export function resolveDouyinVideoAsset(source: DouyinSourceMaterial): DouyinVideoAsset {
  const candidate = findPlayableVideoCandidate(source)
  if (!candidate) {
    throw new AppError('未能从抖音页面或浏览器响应中解析到可下载视频地址', 502)
  }

  const asset = buildVideoAsset(source, candidate.playableVideoUrl, candidate.fetchStage, candidate.snippetRank)
  logger.info({
    resolvedTarget: summarizeResolvedUrl(source.resolvedUrl),
    videoId: source.videoId,
    fetchStage: asset.fetchStage,
    snippetRank: asset.snippetRank,
    playableTarget: summarizePlayableVideoUrl(asset.playableVideoUrl),
    selectedBy: candidate.selectedBy,
  }, 'Douyin video asset selected')
  return asset
}

export async function resolveDouyinSource(url: string): Promise<DouyinSourceMaterial> {
  const resolveStartedAt = Date.now()
  const storageStatePath = await getPersistedDouyinStorageStatePath()

  logFetchAttempt('Douyin resolve request', {
    requestTarget: summarizeResolvedUrl(url),
    hasPersistedSession: Boolean(storageStatePath),
    desktopUserAgent: env.DOUYIN_COOKIE_USER_AGENT || env.DOUYIN_USER_AGENT,
    mobileUserAgent: mobileShareUserAgent,
  })

  let attemptedSession = false
  let sessionBackedMaterial: DouyinSourceMaterial | undefined

  if (storageStatePath) {
    attemptedSession = true

    try {
      const storageMaterial = await resolveDouyinSourceWithMode(
        url,
        'storage_state',
        env.DOUYIN_COOKIE_USER_AGENT || env.DOUYIN_USER_AGENT,
        storageStatePath,
      )

      if (!storageMaterial.isChallengePage) {
        await markDouyinSessionUsed()
        logSelectedMaterial(storageMaterial)
        logResolveTiming(url, resolveStartedAt, storageMaterial, attemptedSession)
        return storageMaterial
      }

      sessionBackedMaterial = storageMaterial
      await markDouyinSessionExpired('当前登录态仍命中抖音校验页，请重新扫码登录。')
      logFetchAttempt('Douyin persisted session returned challenge page', {
        requestTarget: summarizeResolvedUrl(url),
        resolvedTarget: summarizeResolvedUrl(storageMaterial.resolvedUrl),
        challengeHints: storageMaterial.challengeHints,
        fetchStage: storageMaterial.fetchStage,
      })
    } catch (error: unknown) {
      logFetchFailure('Douyin persisted session fetch failed', {
        requestTarget: summarizeResolvedUrl(url),
      }, error)
    }
  }

  try {
    const anonymousMaterial = await resolveDouyinSourceWithMode(url, 'anonymous', env.DOUYIN_USER_AGENT)
    const selectedMaterial = attemptedSession
      ? {
          ...anonymousMaterial,
          attemptedSession: true,
        }
      : anonymousMaterial

    logSelectedMaterial(selectedMaterial)
    logResolveTiming(url, resolveStartedAt, selectedMaterial, attemptedSession)
    return selectedMaterial
  } catch (error: unknown) {
    if (sessionBackedMaterial) {
      logFetchFailure('Douyin anonymous fallback failed after session attempt', {
        requestTarget: summarizeResolvedUrl(url),
      }, error)

      logSelectedMaterial(sessionBackedMaterial)
      logResolveTiming(url, resolveStartedAt, sessionBackedMaterial, attemptedSession)
      return sessionBackedMaterial
    }

    logResolveFailure(url, resolveStartedAt, attemptedSession, error)
    throw error
  }
}
