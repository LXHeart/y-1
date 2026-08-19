import { ref } from 'vue'
import { request } from './grassland-http'
import type {
  CreationGenerationDetail,
  CreationGenerationKind,
  CreationGenerationPage,
  CreationGenerationSummary,
} from '../types/grassland/creation-generation'

const LOAD_FALLBACK = '生成记录加载失败'

export async function listCreationGenerations(input: {
  kind?: CreationGenerationKind
  limit?: number
  before?: string
} = {}): Promise<CreationGenerationPage> {
  const query = new URLSearchParams()
  if (input.kind) query.set('kind', input.kind)
  if (input.limit != null) query.set('limit', String(input.limit))
  if (input.before) query.set('before', input.before)
  const suffix = query.size ? `?${query}` : ''
  const page = await request<CreationGenerationPage>(`/api/creation-generations${suffix}`, {}, {
    fallbackError: LOAD_FALLBACK,
  })
  if (page == null) {
    // 原实现对 data==null 也按加载失败处理，保留该契约。
    throw new Error(LOAD_FALLBACK)
  }
  return page
}

export async function getCreationGeneration(id: string): Promise<CreationGenerationDetail> {
  const detail = await request<CreationGenerationDetail>(`/api/creation-generations/${encodeURIComponent(id)}`, {}, {
    fallbackError: LOAD_FALLBACK,
  })
  if (detail == null) {
    throw new Error(LOAD_FALLBACK)
  }
  return detail
}

export function useCreationGenerations(kind?: CreationGenerationKind) {
  const items = ref<CreationGenerationSummary[]>([])
  const nextBefore = ref<string | null>(null)
  const loading = ref(false)
  const error = ref('')

  async function load(reset = true): Promise<void> {
    if (loading.value) return
    loading.value = true
    error.value = ''
    try {
      const page = await listCreationGenerations({
        kind,
        limit: 20,
        before: reset ? undefined : nextBefore.value || undefined,
      })
      const pageItems = Array.isArray(page.items) ? page.items : []
      items.value = reset ? pageItems : [...items.value, ...pageItems]
      nextBefore.value = typeof page.nextBefore === 'string' ? page.nextBefore : null
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '生成记录加载失败'
    } finally {
      loading.value = false
    }
  }

  return { items, nextBefore, loading, error, load }
}
