import { ref } from 'vue'
import type { ApiResponse } from '../types/douyin'
import type { DouyinHotItem, DouyinHotItemsPayload } from '../types/douyin-hot'

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

function normalizeHotItem(value: unknown): DouyinHotItem | null {
  if (!isPlainObject(value) || typeof value.rank !== 'number' || typeof value.title !== 'string' || value.source !== '60sapi') {
    return null
  }

  return {
    rank: value.rank,
    title: value.title,
    hotValue: normalizeOptionalString(value.hotValue),
    url: normalizeOptionalString(value.url),
    cover: normalizeOptionalString(value.cover),
    source: '60sapi',
  }
}

function normalizeHotItemsPayload(value: unknown): DouyinHotItemsPayload | null {
  if (!isPlainObject(value) || !Array.isArray(value.items)) {
    return null
  }

  const items = value.items
    .map((item) => normalizeHotItem(item))
    .filter((item): item is DouyinHotItem => item !== null)

  return { items }
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

export function useDouyinHotItems() {
  const items = ref<DouyinHotItem[]>([])
  const loading = ref(false)
  const error = ref('')

  async function loadHotItems(): Promise<void> {
    loading.value = true
    error.value = ''

    try {
      const response = await fetch('/api/douyin/hot-items')
      if (!response.ok) {
        throw new Error(await readApiError(response, `加载抖音热点失败（${response.status}）`))
      }

      const body = await response.json() as ApiResponse<unknown>
      const normalizedData = normalizeHotItemsPayload(body.data)
      if (!body.success || !normalizedData) {
        throw new Error(body.error || '加载抖音热点失败')
      }

      items.value = normalizedData.items
    } catch (requestError: unknown) {
      items.value = []
      error.value = requestError instanceof Error ? requestError.message : '加载抖音热点失败'
    } finally {
      loading.value = false
    }
  }

  return {
    items,
    loading,
    error,
    loadHotItems,
  }
}
