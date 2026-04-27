import { env } from '../lib/env.js'
import { AppError } from '../lib/errors.js'
import { isDatabaseConfigured, queryDb } from '../lib/db.js'
import { logger } from '../lib/logger.js'
import { loadHomepageSettings, loadHomepageSettingsForUser } from './analysis-settings.service.js'
import { loadMultiPlatformHotTopics } from './hot-topics-60s.service.js'
import type { HotItemsProvider } from '../schemas/settings.js'

export interface HomepageHotItem {
  rank: number
  title: string
  hotValue?: string
  url?: string
  cover?: string
  sourceLabel?: string
}

export interface HomepageHotItemGroup {
  platform: string
  label: string
  items: HomepageHotItem[]
}

export interface HomepageHotItemsResult {
  provider: HotItemsProvider
  items: HomepageHotItem[]
  groups?: HomepageHotItemGroup[]
}

const MAX_HOMEPAGE_ITEMS = 100
const HOT_TOPICS_CACHE_TTL_MS = 2 * 60 * 60 * 1000
const HOT_TOPICS_CACHE_PROVIDER = '60s'
const ALAPI_SITE_PATH = '/api/tophub/site'
const ALAPI_TOPHUB_PATH = '/api/tophub'
const ALAPI_ALLOWED_SITE_IDS = new Set(['douyin', 'weibo', 'weixin', 'xiaohongshu'])
const ALAPI_CACHE_TTL_MS = 5 * 60 * 1000

const alapiHotItemsCache = new Map<string, {
  expiresAt: number
  items: HomepageHotItem[]
}>()

interface AlapiEnvelope<T> {
  success?: unknown
  code?: unknown
  message?: unknown
  data?: T
}

interface AlapiSiteEntry {
  id?: unknown
  site?: unknown
  title?: unknown
  category?: unknown
}

interface AlapiTophubItem {
  title?: unknown
  link?: unknown
  image?: unknown
  other?: unknown
}

interface AlapiTophubData {
  name?: unknown
  list?: unknown
}

interface AlapiSiteInfo {
  id: string
  site?: string
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

function parseEnvelope<T>(body: AlapiEnvelope<T>): T {
  if (body.code !== 200) {
    logger.warn({ upstreamCode: body.code, upstreamMessage: body.message }, 'ALAPI tophub upstream returned non-success code')

    const upstreamMessage = normalizeOptionalString(body.message)
    throw new AppError(upstreamMessage ?? '获取全网热点失败，请检查 ALAPI 配置', 502)
  }

  if (typeof body.data === 'undefined') {
    throw new AppError('ALAPI 热点服务返回了无效数据', 502)
  }

  return body.data
}

function normalizeSiteEntry(entry: AlapiSiteEntry): AlapiSiteInfo | null {
  const id = normalizeOptionalString(entry.id)
  if (!id) {
    return null
  }

  return {
    id,
    site: normalizeOptionalString(entry.site),
  }
}

function normalizeSiteEntries(rawEntries: AlapiSiteEntry[]): AlapiSiteInfo[] {
  return rawEntries
    .map((entry) => normalizeSiteEntry(entry))
    .filter((entry): entry is AlapiSiteInfo => entry !== null)
}

function normalizeTophubItem(
  item: AlapiTophubItem,
  sourceLabel: string | undefined,
): HomepageHotItem | null {
  const title = normalizeOptionalString(item.title)
  if (!title) {
    return null
  }

  return {
    rank: 0,
    title,
    hotValue: normalizeHotValue(item.other),
    url: normalizeOpenUrl(item.link),
    cover: normalizeOpenUrl(item.image),
    sourceLabel,
  }
}

function normalizeTophubItems(
  rawItems: AlapiTophubItem[],
  sourceLabel: string | undefined,
): HomepageHotItem[] {
  return rawItems
    .map((item) => normalizeTophubItem(item, sourceLabel))
    .filter((item): item is HomepageHotItem => item !== null)
}

function buildAlapiUrl(pathname: string): string {
  return new URL(pathname, env.ALAPI_BASE_URL).toString()
}

async function fetchAlapiJson<T>(
  pathname: string,
  token: string,
  body?: Record<string, unknown>,
  signal?: AbortSignal,
): Promise<AlapiEnvelope<T>> {
  const response = await fetch(buildAlapiUrl(pathname), {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      token,
    },
    body: JSON.stringify(body ?? {}),
    signal,
  })

  if (!response.ok) {
    logger.warn({ statusCode: response.status, pathname }, 'ALAPI tophub upstream returned non-OK response')
    throw new AppError('获取全网热点失败，请稍后再试', 502)
  }

  return await response.json() as AlapiEnvelope<T>
}

async function loadAlapiHotItems(userId?: string): Promise<HomepageHotItem[]> {
  const settings = userId ? await loadHomepageSettingsForUser(userId) : loadHomepageSettings()
  const token = settings.hotItems.alapiToken

  if (!token) {
    throw new AppError('请先在设置中配置 ALAPI Token', 400)
  }

  const now = Date.now()
  const cached = alapiHotItemsCache.get(token)
  if (cached && cached.expiresAt > now) {
    logger.info({ itemCount: cached.items.length }, 'ALAPI homepage hot items cache hit')
    return cached.items
  }

  logger.info({ ttlMs: ALAPI_CACHE_TTL_MS }, 'ALAPI homepage hot items cache miss')

  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), env.ALAPI_TIMEOUT_MS)

  try {
    const sitesEnvelope = await fetchAlapiJson<AlapiSiteEntry[]>(ALAPI_SITE_PATH, token, {
      page: 1,
      page_size: 100,
    }, controller.signal)
    const sitesData = parseEnvelope(sitesEnvelope)

    if (!Array.isArray(sitesData)) {
      throw new AppError('ALAPI 热点站点列表返回了无效数据', 502)
    }

    const sites = normalizeSiteEntries(sitesData)
      .filter((site) => ALAPI_ALLOWED_SITE_IDS.has(site.id))

    logger.info({ siteIds: sites.map((site) => site.id) }, 'ALAPI homepage hot items selected sites')
    const groupedItems = await Promise.all(sites.map(async (site) => {
      const tophubEnvelope = await fetchAlapiJson<AlapiTophubData>(ALAPI_TOPHUB_PATH, token, {
        id: site.id,
      }, controller.signal)
      const tophubData = parseEnvelope(tophubEnvelope)

      if (typeof tophubData !== 'object' || tophubData === null || !Array.isArray(tophubData.list)) {
        throw new AppError('ALAPI 热点详情返回了无效数据', 502)
      }

      return normalizeTophubItems(tophubData.list as AlapiTophubItem[], site.site)
    }))

    const items = groupedItems
      .flat()
      .slice(0, MAX_HOMEPAGE_ITEMS)
      .map((item, index) => ({
        ...item,
        rank: index + 1,
      }))

    alapiHotItemsCache.set(token, {
      expiresAt: Date.now() + ALAPI_CACHE_TTL_MS,
      items,
    })

    return items
  } catch (error: unknown) {
    if (error instanceof AppError) {
      throw error
    }

    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new AppError('获取全网热点超时，请稍后再试', 504)
    }

    logger.error({ err: error }, 'Failed to load ALAPI hot items')
    throw new AppError('获取全网热点失败，请稍后再试', 502)
  } finally {
    clearTimeout(timeout)
  }
}

interface CachedHotTopicsRow {
  items: HomepageHotItemGroup[]
  fetched_at: Date
}

function isValidGroupsCache(items: unknown): items is HomepageHotItemGroup[] {
  return Array.isArray(items) && items.every((g) => typeof g === 'object' && g !== null && Array.isArray((g as HomepageHotItemGroup).items))
}

async function load60sHotItems(): Promise<{ items: HomepageHotItem[]; groups: HomepageHotItemGroup[] }> {
  const cached = await readCachedHotTopics()

  if (cached && isValidGroupsCache(cached.items)) {
    const age = Date.now() - cached.fetched_at.getTime()
    if (age < HOT_TOPICS_CACHE_TTL_MS) {
      const totalItems = cached.items.reduce((sum, g) => sum + g.items.length, 0)
      logger.info({ groupCount: cached.items.length, totalItems, ageMs: age }, '60s hot topics cache hit')
      return { items: [], groups: cached.items }
    }
  }

  try {
    const result = await loadMultiPlatformHotTopics()

    await persistCachedHotTopics(result.groups)

    return { items: [], groups: result.groups }
  } catch (error: unknown) {
    if (cached) {
      logger.warn({ err: error }, '60s hot topics API failed, returning stale cache')
      return { items: [], groups: cached.items }
    }

    throw error
  }
}

async function readCachedHotTopics(): Promise<CachedHotTopicsRow | null> {
  if (!isDatabaseConfigured()) {
    return null
  }

  try {
    const result = await queryDb<CachedHotTopicsRow>(
      'SELECT items, fetched_at FROM cached_hot_topics WHERE provider = $1 ORDER BY fetched_at DESC LIMIT 1',
      [HOT_TOPICS_CACHE_PROVIDER],
    )

    if (result.rows.length === 0) {
      return null
    }

    return result.rows[0]
  } catch (error: unknown) {
    logger.warn({ err: error }, 'Failed to read cached hot topics from database')
    return null
  }
}

async function persistCachedHotTopics(groups: HomepageHotItemGroup[]): Promise<void> {
  if (!isDatabaseConfigured()) {
    return
  }

  try {
    await queryDb('DELETE FROM cached_hot_topics WHERE provider = $1', [HOT_TOPICS_CACHE_PROVIDER])
    await queryDb(
      'INSERT INTO cached_hot_topics (provider, items) VALUES ($1, $2)',
      [HOT_TOPICS_CACHE_PROVIDER, JSON.stringify(groups)],
    )
  } catch (error: unknown) {
    logger.warn({ err: error }, 'Failed to persist cached hot topics to database')
  }
}

export async function loadHomepageHotItems(userId?: string): Promise<HomepageHotItemsResult> {
  const settings = userId ? await loadHomepageSettingsForUser(userId) : loadHomepageSettings()
  const provider = settings.hotItems.provider

  if (provider === 'alapi') {
    const items = await loadAlapiHotItems(userId)
    return { provider: 'alapi', items }
  }

  const { items, groups } = await load60sHotItems()
  return { provider: '60s', items, groups }
}
