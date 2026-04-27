import { isAllowedDouyinPageHost, isAllowedDouyinVideoHost } from '../lib/douyin-hosts.js'
import { env } from '../lib/env.js'
import { AppError } from '../lib/errors.js'
import { logger } from '../lib/logger.js'

export interface DouyinHotItem {
  rank: number
  title: string
  hotValue?: string
  url?: string
  cover?: string
  source: '60sapi'
}

export interface DouyinHotItemsResult {
  items: DouyinHotItem[]
}

interface LoadDouyinHotItemsOptions {
  timeoutMs?: number
  limit?: number
}

interface UpstreamDouyinHotItem {
  title?: unknown
  hot_value?: unknown
  cover?: unknown
  link?: unknown
}

interface UpstreamDouyinHotResponse {
  code?: unknown
  message?: unknown
  data?: unknown
}

function normalizeHotValue(value: unknown): string | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return String(value)
  }

  if (typeof value === 'string') {
    const trimmedValue = value.trim()
    return trimmedValue || undefined
  }

  return undefined
}

function normalizeOptionalString(value: unknown): string | undefined {
  if (typeof value !== 'string') {
    return undefined
  }

  const trimmedValue = value.trim()
  return trimmedValue || undefined
}

function normalizeTrustedUrl(
  value: unknown,
  isAllowedHost: (hostname: string) => boolean,
): string | undefined {
  const normalizedValue = normalizeOptionalString(value)
  if (!normalizedValue) {
    return undefined
  }

  try {
    const url = new URL(normalizedValue)
    if (url.protocol !== 'https:' || !isAllowedHost(url.hostname)) {
      return undefined
    }

    return url.toString()
  } catch {
    return undefined
  }
}

function normalizeDouyinHotItem(item: UpstreamDouyinHotItem, index: number): DouyinHotItem | null {
  const title = normalizeOptionalString(item.title)
  if (!title) {
    return null
  }

  return {
    rank: index + 1,
    title,
    hotValue: normalizeHotValue(item.hot_value),
    url: normalizeTrustedUrl(item.link, isAllowedDouyinPageHost),
    cover: normalizeTrustedUrl(item.cover, isAllowedDouyinVideoHost),
    source: '60sapi',
  }
}

function normalizeResponse(body: UpstreamDouyinHotResponse, limit: number): DouyinHotItemsResult {
  if (body.code !== 200) {
    logger.warn({ upstreamCode: body.code, upstreamMessage: body.message }, 'Douyin hot items upstream returned a non-success business code')
    throw new AppError('获取抖音热点失败，请稍后再试', 502)
  }

  if (!Array.isArray(body.data)) {
    throw new AppError('抖音热点服务返回了无效数据', 502)
  }

  const items = body.data
    .map((entry, index) => normalizeDouyinHotItem(entry as UpstreamDouyinHotItem, index))
    .filter((entry): entry is DouyinHotItem => entry !== null)
    .slice(0, limit)
    .map((entry, index) => ({
      ...entry,
      rank: index + 1,
    }))

  return { items }
}

function buildRequestUrl(): string {
  const url = new URL(env.DOUYIN_HOT_API_BASE_URL)
  if (!url.searchParams.has('encoding')) {
    url.searchParams.set('encoding', 'json')
  }
  return url.toString()
}

export async function loadDouyinHotItems(options: LoadDouyinHotItemsOptions = {}): Promise<DouyinHotItemsResult> {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), options.timeoutMs ?? env.DOUYIN_HOT_API_TIMEOUT_MS)
  const requestUrl = buildRequestUrl()

  try {
    const response = await fetch(requestUrl, {
      method: 'GET',
      headers: {
        Accept: 'application/json',
      },
      signal: controller.signal,
    })

    if (!response.ok) {
      logger.warn({ statusCode: response.status, requestUrl }, 'Douyin hot items upstream returned a non-OK response')
      throw new AppError('获取抖音热点失败，请稍后再试', 502)
    }

    const body = await response.json() as UpstreamDouyinHotResponse
    return normalizeResponse(body, options.limit ?? 10)
  } catch (error: unknown) {
    if (error instanceof AppError) {
      throw error
    }

    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new AppError('获取抖音热点超时，请稍后再试', 504)
    }

    logger.error({ err: error, requestUrl }, 'Failed to load douyin hot items')
    throw new AppError('获取抖音热点失败，请稍后再试', 502)
  } finally {
    clearTimeout(timeout)
  }
}
