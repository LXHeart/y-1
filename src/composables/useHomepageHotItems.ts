import { ref } from 'vue'
import { request } from './grassland-http'
import type {
  HomepageHotFilters,
  HomepageHotItem,
  HomepageHotItemGroup,
  HomepageHotItemsPayload,
  HomepageHotTags,
  HomepageHotTaxonomy,
  HomepageHotTaxonomyOption,
} from '../types/homepage-hot'
import type { HotItemsProvider } from '../types/settings'

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function normalizeOptionalString(value: unknown): string | undefined {
  if (typeof value !== 'string') {
    return undefined
  }

  const trimmedValue = value.trim()
  return trimmedValue || undefined
}

function normalizeStringList(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return value
    .map((entry) => normalizeOptionalString(entry))
    .filter((entry): entry is string => Boolean(entry))
}

function normalizeTags(value: unknown): HomepageHotTags | undefined {
  if (!isPlainObject(value)) return undefined
  return {
    industries: normalizeStringList(value.industries),
    city: normalizeOptionalString(value.city),
    contentType: normalizeOptionalString(value.contentType),
    taxonomyVersion: normalizeOptionalString(value.taxonomyVersion),
  }
}

function normalizeHotItem(value: unknown): HomepageHotItem | null {
  if (!isPlainObject(value) || typeof value.rank !== 'number' || typeof value.title !== 'string') {
    return null
  }

  return {
    rank: value.rank,
    title: value.title,
    hotValue: normalizeOptionalString(value.hotValue),
    url: normalizeOptionalString(value.url),
    cover: normalizeOptionalString(value.cover),
    sourceLabel: normalizeOptionalString(value.sourceLabel),
    tags: normalizeTags(value.tags),
    validUntil: normalizeOptionalString(value.validUntil),
    expired: typeof value.expired === 'boolean' ? value.expired : undefined,
    occurrences: typeof value.occurrences === 'number' && value.occurrences > 0 ? value.occurrences : undefined,
  }
}

function normalizeTaxonomyOption(value: unknown): HomepageHotTaxonomyOption | null {
  if (!isPlainObject(value)) return null
  const optionValue = normalizeOptionalString(value.value)
  const label = normalizeOptionalString(value.label)
  return optionValue && label ? { value: optionValue, label } : null
}

function normalizeTaxonomy(value: unknown): HomepageHotTaxonomy | undefined {
  if (!isPlainObject(value)) return undefined
  const version = normalizeOptionalString(value.version)
  if (!version || !Array.isArray(value.industries) || !Array.isArray(value.contentTypes)) return undefined
  const industries = value.industries
    .map(normalizeTaxonomyOption)
    .filter((entry): entry is HomepageHotTaxonomyOption => entry !== null)
  const contentTypes = value.contentTypes
    .map(normalizeTaxonomyOption)
    .filter((entry): entry is HomepageHotTaxonomyOption => entry !== null)
  return { version, industries, cities: normalizeStringList(value.cities), contentTypes }
}

function normalizeGroup(value: unknown): HomepageHotItemGroup | null {
  if (!isPlainObject(value) || typeof value.platform !== 'string' || typeof value.label !== 'string') {
    return null
  }

  if (!Array.isArray(value.items)) {
    return null
  }

  const items = value.items
    .map((item: unknown) => normalizeHotItem(item))
    .filter((item): item is HomepageHotItem => item !== null)

  return {
    platform: value.platform,
    label: value.label,
    items,
  }
}

function normalizePayload(value: unknown): HomepageHotItemsPayload | null {
  if (!isPlainObject(value) || !Array.isArray(value.items)) {
    return null
  }

  const provider = value.provider === 'alapi' ? 'alapi' : '60s'
  const items = value.items
    .map((item: unknown) => normalizeHotItem(item))
    .filter((item): item is HomepageHotItem => item !== null)

  const groups = Array.isArray(value.groups)
    ? value.groups
        .map((group: unknown) => normalizeGroup(group))
        .filter((group): group is HomepageHotItemGroup => group !== null)
    : undefined

  return {
    provider,
    items,
    groups,
    fetchedAt: normalizeOptionalString(value.fetchedAt),
    taxonomy: normalizeTaxonomy(value.taxonomy),
  }
}

function normalizeFilters(value: HomepageHotFilters): HomepageHotFilters {
  return {
    industry: normalizeOptionalString(value.industry),
    city: normalizeOptionalString(value.city),
    contentType: normalizeOptionalString(value.contentType),
    includeExpired: value.includeExpired === true,
  }
}

export function buildHomepageHotItemsUrl(filters: HomepageHotFilters = {}): string {
  const normalized = normalizeFilters(filters)
  const params = new URLSearchParams()
  if (normalized.industry) params.set('industry', normalized.industry)
  if (normalized.city) params.set('city', normalized.city)
  if (normalized.contentType) params.set('contentType', normalized.contentType)
  if (normalized.includeExpired) params.set('includeExpired', 'true')
  const query = params.toString()
  return query ? `/api/homepage/hot-items?${query}` : '/api/homepage/hot-items'
}

export function useHomepageHotItems() {
  const items = ref<HomepageHotItem[]>([])
  const groups = ref<HomepageHotItemGroup[]>([])
  const provider = ref<HotItemsProvider>('60s')
  const fetchedAt = ref('')
  const taxonomy = ref<HomepageHotTaxonomy | null>(null)
  const filters = ref<HomepageHotFilters>({})
  const loading = ref(false)
  const error = ref('')
  /** 最近一次历史查询覆盖的快照数（实时榜为 0；0 = 窗口内尚无归档）。 */
  const snapshotCount = ref(0)
  let requestEpoch = 0

  async function loadHotItems(nextFilters: HomepageHotFilters = filters.value): Promise<void> {
    const requestId = ++requestEpoch
    const normalizedFilters = normalizeFilters(nextFilters)
    filters.value = normalizedFilters
    loading.value = true
    error.value = ''

    try {
      const data = await request<unknown>(buildHomepageHotItemsUrl(normalizedFilters), undefined, {
        fallbackError: '加载全网热点失败',
      })
      const normalizedData = normalizePayload(data)
      if (!normalizedData) {
        throw new Error('加载全网热点失败')
      }
      if (requestId !== requestEpoch) return

      items.value = normalizedData.items
      groups.value = normalizedData.groups ?? []
      provider.value = normalizedData.provider
      fetchedAt.value = normalizedData.fetchedAt ?? ''
      taxonomy.value = normalizedData.taxonomy ?? null
      snapshotCount.value = 0
    } catch (requestError: unknown) {
      if (requestId !== requestEpoch) return
      items.value = []
      groups.value = []
      fetchedAt.value = ''
      error.value = requestError instanceof Error ? requestError.message : '加载全网热点失败'
    } finally {
      if (requestId === requestEpoch) loading.value = false
    }
  }

  /** 历史聚合（缺口清偿之八，PRD §4.3 时间范围）：range=today|week；空归档时 groups 空 + snapshotCount=0。 */
  async function loadHistory(range: 'today' | 'week'): Promise<void> {
    const requestId = ++requestEpoch
    loading.value = true
    error.value = ''

    try {
      const data = await request<unknown>(`/api/homepage/hot-items/history?range=${range}`, undefined, {
        fallbackError: '加载热点历史失败',
      })
      if (!isPlainObject(data) || !Array.isArray(data.groups)) {
        throw new Error('加载热点历史失败')
      }
      if (requestId !== requestEpoch) return

      const historyGroups = data.groups
        .map((group: unknown) => normalizeGroup(group))
        .filter((group): group is HomepageHotItemGroup => group !== null)
      items.value = []
      groups.value = historyGroups
      provider.value = '60s'
      fetchedAt.value = normalizeOptionalString(data.since) ?? ''
      taxonomy.value = null
      snapshotCount.value = typeof data.snapshotCount === 'number' ? data.snapshotCount : 0
    } catch (requestError: unknown) {
      if (requestId !== requestEpoch) return
      items.value = []
      groups.value = []
      fetchedAt.value = ''
      snapshotCount.value = 0
      error.value = requestError instanceof Error ? requestError.message : '加载热点历史失败'
    } finally {
      if (requestId === requestEpoch) loading.value = false
    }
  }

  return {
    items,
    groups,
    provider,
    fetchedAt,
    taxonomy,
    filters,
    loading,
    error,
    snapshotCount,
    loadHotItems,
    loadHistory,
  }
}
