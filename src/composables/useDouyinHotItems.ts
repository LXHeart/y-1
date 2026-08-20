import { ref } from 'vue'
import { request } from './grassland-http'
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

export function normalizeHotItem(value: unknown): DouyinHotItem | null {
  if (!isPlainObject(value) || typeof value.rank !== 'number' || typeof value.title !== 'string') {
    return null
  }

  return {
    rank: value.rank,
    title: value.title,
    hotValue: normalizeOptionalString(value.hotValue),
    url: normalizeOptionalString(value.url),
    cover: normalizeOptionalString(value.cover),
    source: normalizeOptionalString(value.source),
  }
}

export function normalizeHotItemsPayload(value: unknown): DouyinHotItemsPayload | null {
  if (!isPlainObject(value) || !Array.isArray(value.items)) {
    return null
  }

  const items = value.items
    .map((item) => normalizeHotItem(item))
    .filter((item): item is DouyinHotItem => item !== null)

  return { items }
}

/**
 * 抖音实时热点（`GET /api/douyin/hot-items`，公开端点）：作为视频制作的可选选题灵感输入
 * （PRD §4.3），受信链接可带入提取输入框。相对首页聚合端点（60s 缓存、三平台分组），
 * 这里是抖音单平台实时榜，链接经服务端受信主机校验。
 */
export function useDouyinHotItems() {
  const items = ref<DouyinHotItem[]>([])
  const loading = ref(false)
  const error = ref('')

  async function loadHotItems(): Promise<void> {
    if (loading.value) return
    loading.value = true
    error.value = ''

    try {
      const data = await request<DouyinHotItemsPayload | null>(
        '/api/douyin/hot-items', {}, { fallbackError: '加载抖音热点失败，请稍后重试' })
      const normalized = data === null ? null : normalizeHotItemsPayload(data)
      if (!normalized) {
        throw new Error('加载抖音热点失败，请稍后重试')
      }
      items.value = normalized.items
    } catch (requestError: unknown) {
      items.value = []
      error.value = requestError instanceof Error ? requestError.message : '加载抖音热点失败，请稍后重试'
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
