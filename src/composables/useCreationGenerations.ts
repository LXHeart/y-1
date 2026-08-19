import { ref } from 'vue'
import type {
  CreationGenerationDetail,
  CreationGenerationKind,
  CreationGenerationPage,
  CreationGenerationSummary,
} from '../types/grassland/creation-generation'

interface ApiEnvelope<T> {
  success?: boolean
  data?: T
  error?: string
}

async function request<T>(url: string): Promise<T> {
  const response = await fetch(url, { credentials: 'include' })
  const body = await response.json() as ApiEnvelope<T>
  if (!response.ok || !body.success || body.data == null) {
    throw new Error(body.error || '生成记录加载失败')
  }
  return body.data
}

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
  return request<CreationGenerationPage>(`/api/creation-generations${suffix}`)
}

export function getCreationGeneration(id: string): Promise<CreationGenerationDetail> {
  return request<CreationGenerationDetail>(`/api/creation-generations/${encodeURIComponent(id)}`)
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
