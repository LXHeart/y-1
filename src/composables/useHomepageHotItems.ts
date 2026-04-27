import { ref } from 'vue'
import type { HomepageHotItem, HomepageHotItemGroup, HomepageHotItemsPayload } from '../types/homepage-hot'
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
  }
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

  return { provider, items, groups }
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
  const loading = ref(false)
  const error = ref('')

  async function loadHotItems(): Promise<void> {
    loading.value = true
    error.value = ''

    try {
      const response = await fetch('/api/homepage/hot-items')
      if (!response.ok) {
        throw new Error(await readApiError(response, `加载全网热点失败（${response.status}）`))
      }

      const body = await response.json() as ApiResponse<unknown>
      const normalizedData = normalizePayload(body.data)
      if (!body.success || !normalizedData) {
        throw new Error(body.error || '加载全网热点失败')
      }

      items.value = normalizedData.items
      groups.value = normalizedData.groups ?? []
      provider.value = normalizedData.provider
    } catch (requestError: unknown) {
      items.value = []
      groups.value = []
      error.value = requestError instanceof Error ? requestError.message : '加载全网热点失败'
    } finally {
      loading.value = false
    }
  }

  return {
    items,
    groups,
    provider,
    loading,
    error,
    loadHotItems,
  }
}
