import { ref } from 'vue'
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

interface ApiResponse<T> {
  success: boolean
  data?: T
  error?: string
}

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

async function readApiError(response: Response, fallbackMessage: string): Promise<string> {
  const contentType = response.headers.get('content-type') || ''
  if (contentType.includes('application/json')) {
    const body = await response.json() as ApiResponse<unknown>
    return body.error || fallbackMessage
  }

  const text = await response.text()
  return text.trim() || fallbackMessage
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
  let requestEpoch = 0

  async function loadHotItems(nextFilters: HomepageHotFilters = filters.value): Promise<void> {
    const requestId = ++requestEpoch
    const normalizedFilters = normalizeFilters(nextFilters)
    filters.value = normalizedFilters
    loading.value = true
    error.value = ''

    try {
      const response = await fetch(buildHomepageHotItemsUrl(normalizedFilters))
      if (!response.ok) {
        throw new Error(await readApiError(response, `加载全网热点失败（${response.status}）`))
      }

      const body = await response.json() as ApiResponse<unknown>
      const normalizedData = normalizePayload(body.data)
      if (!body.success || !normalizedData) {
        throw new Error(body.error || '加载全网热点失败')
      }
      if (requestId !== requestEpoch) return

      items.value = normalizedData.items
      groups.value = normalizedData.groups ?? []
      provider.value = normalizedData.provider
      fetchedAt.value = normalizedData.fetchedAt ?? ''
      taxonomy.value = normalizedData.taxonomy ?? null
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

  return {
    items,
    groups,
    provider,
    fetchedAt,
    taxonomy,
    filters,
    loading,
    error,
    loadHotItems,
  }
}
