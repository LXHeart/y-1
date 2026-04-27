import { env } from '../lib/env.js'
import { AppError } from '../lib/errors.js'
import { logger } from '../lib/logger.js'

export interface HomepageHotItem {
  rank: number
  title: string
  hotValue?: string
  url?: string
  cover?: string
  sourceLabel?: string
}

export interface HotTopics60sGroup {
  platform: string
  label: string
  items: HomepageHotItem[]
}

export interface HotTopics60sResult {
  groups: HotTopics60sGroup[]
}

interface PlatformConfig {
  key: string
  label: string
  path: string
}

const PLATFORMS: readonly PlatformConfig[] = [
  { key: 'douyin', label: '抖音', path: '/v2/douyin' },
  { key: 'weibo', label: '微博', path: '/v2/weibo' },
  { key: 'zhihu', label: '知乎', path: '/v2/zhihu' },
] as const

const ITEMS_PER_PLATFORM = 20

interface UpstreamHotItem {
  title?: unknown
  hot_value?: unknown
  cover?: unknown
  link?: unknown
}

interface UpstreamHotResponse {
  code?: unknown
  message?: unknown
  data?: unknown
}

function normalizeOptionalString(value: unknown): string | undefined {
  if (typeof value !== 'string') {
    return undefined
  }

  const trimmedValue = value.trim()
  return trimmedValue || undefined
}

function normalizeHotValue(value: unknown): string | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return String(value)
  }

  return normalizeOptionalString(value)
}

function normalizeOpenUrl(value: unknown): string | undefined {
  const normalizedValue = normalizeOptionalString(value)
  if (!normalizedValue) {
    return undefined
  }

  try {
    const parsedUrl = new URL(normalizedValue)
    if (parsedUrl.protocol !== 'https:' && parsedUrl.protocol !== 'http:') {
      return undefined
    }

    return parsedUrl.toString()
  } catch {
    return undefined
  }
}

function buildRequestUrl(path: string): string {
  const baseUrl = new URL(env.DOUYIN_HOT_API_BASE_URL)
  const url = new URL(path, baseUrl.origin)
  if (!url.searchParams.has('encoding')) {
    url.searchParams.set('encoding', 'json')
  }

  return url.toString()
}

function normalizeHotItem(item: UpstreamHotItem, index: number): HomepageHotItem | null {
  const title = normalizeOptionalString(item.title)
  if (!title) {
    return null
  }

  return {
    rank: index + 1,
    title,
    hotValue: normalizeHotValue(item.hot_value),
    url: normalizeOpenUrl(item.link),
    cover: normalizeOpenUrl(item.cover),
  }
}

function normalizeResponse(body: UpstreamHotResponse, label: string, limit: number): HomepageHotItem[] {
  if (body.code !== 200) {
    logger.warn(
      { upstreamCode: body.code, upstreamMessage: body.message, platform: label },
      '60s hot topics upstream returned a non-success business code',
    )
    throw new AppError(`获取${label}热点失败，请稍后再试`, 502)
  }

  if (!Array.isArray(body.data)) {
    throw new AppError(`${label}热点服务返回了无效数据`, 502)
  }

  return body.data
    .map((entry, index) => normalizeHotItem(entry as UpstreamHotItem, index))
    .filter((entry): entry is HomepageHotItem => entry !== null)
    .slice(0, limit)
    .map((entry, index) => ({ ...entry, rank: index + 1 }))
}

async function fetchPlatformHotItems(
  platform: PlatformConfig,
  signal?: AbortSignal,
): Promise<HotTopics60sGroup> {
  const requestUrl = buildRequestUrl(platform.path)

  const response = await fetch(requestUrl, {
    method: 'GET',
    headers: { Accept: 'application/json' },
    signal,
  })

  if (!response.ok) {
    logger.warn(
      { statusCode: response.status, requestUrl, platform: platform.key },
      '60s hot topics upstream returned a non-OK response',
    )
    throw new AppError(`获取${platform.label}热点失败，请稍后再试`, 502)
  }

  const body = await response.json() as UpstreamHotResponse
  const items = normalizeResponse(body, platform.label, ITEMS_PER_PLATFORM)

  return {
    platform: platform.key,
    label: platform.label,
    items,
  }
}

export async function loadMultiPlatformHotTopics(): Promise<HotTopics60sResult> {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), env.DOUYIN_HOT_API_TIMEOUT_MS)

  try {
    const results = await Promise.allSettled(
      PLATFORMS.map((platform) => fetchPlatformHotItems(platform, controller.signal)),
    )

    const groups: HotTopics60sGroup[] = []
    const errors: unknown[] = []

    for (let i = 0; i < results.length; i++) {
      const result = results[i]
      if (result.status === 'fulfilled') {
        groups.push(result.value)
      } else {
        errors.push(result.reason)
        logger.warn(
          { err: result.reason, platform: PLATFORMS[i].key },
          '60s hot topics platform fetch failed',
        )
      }
    }

    if (groups.length === 0) {
      const firstError = errors[0]
      if (firstError instanceof AppError) {
        throw firstError
      }

      if (firstError instanceof DOMException && firstError.name === 'AbortError') {
        throw new AppError('获取热点超时，请稍后再试', 504)
      }

      throw new AppError('获取热点失败，请稍后再试', 502)
    }

    if (errors.length > 0) {
      logger.info(
        { successCount: groups.length, failureCount: errors.length },
        '60s hot topics partial failure, returning available platforms',
      )
    }

    return { groups }
  } catch (error: unknown) {
    if (error instanceof AppError) {
      throw error
    }

    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new AppError('获取热点超时，请稍后再试', 504)
    }

    logger.error({ err: error }, 'Failed to load multi-platform hot topics')
    throw new AppError('获取热点失败，请稍后再试', 502)
  } finally {
    clearTimeout(timeout)
  }
}
