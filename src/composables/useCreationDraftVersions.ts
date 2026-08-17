import { ref } from 'vue'
import type {
  CreationDraftVersion,
  CreationDraftVersionPage,
} from '../types/creation-assistant'

interface Envelope<T> {
  success: boolean
  data?: T
  error?: string
}

async function request<T>(url: string): Promise<T> {
  const response = await fetch(url, { credentials: 'include' })
  const raw = await response.text()
  let body: Envelope<T> | null = null
  try {
    body = raw ? JSON.parse(raw) as Envelope<T> : null
  } catch {
    body = null
  }
  if (!response.ok || !body?.success) {
    throw new Error(body?.error || `请求失败（${response.status}）`)
  }
  return body.data as T
}

/** 草稿历史只读 API；比较和恢复编排留在前端。 */
export function useCreationDraftVersions() {
  const versions = ref<CreationDraftVersionPage['items']>([])
  const snapshots = ref<Record<number, CreationDraftVersion>>({})
  const nextCursor = ref<string | null>(null)
  const loading = ref(false)
  const error = ref('')
  let activeDraftId = ''

  function reset(draftId = ''): void {
    activeDraftId = draftId
    versions.value = []
    snapshots.value = {}
    nextCursor.value = null
    error.value = ''
  }

  async function listVersions(draftId: string, append = false): Promise<CreationDraftVersionPage | null> {
    if (!append || activeDraftId !== draftId) reset(draftId)
    const requestedDraftId = draftId
    loading.value = true
    error.value = ''
    try {
      const cursor = append && nextCursor.value
        ? `&cursor=${encodeURIComponent(nextCursor.value)}` : ''
      const page = await request<CreationDraftVersionPage>(
        `/api/creation-drafts/${draftId}/versions?limit=20${cursor}`)
      if (activeDraftId !== requestedDraftId) return null
      versions.value = append ? [...versions.value, ...(page.items ?? [])] : (page.items ?? [])
      nextCursor.value = page.nextCursor ?? null
      return page
    } catch (err: unknown) {
      if (activeDraftId === requestedDraftId) {
        error.value = err instanceof Error ? err.message : '版本历史加载失败'
      }
      return null
    } finally {
      if (activeDraftId === requestedDraftId) loading.value = false
    }
  }

  async function getVersion(draftId: string, version: number): Promise<CreationDraftVersion | null> {
    if (activeDraftId !== draftId) reset(draftId)
    if (snapshots.value[version]) return snapshots.value[version]
    const requestedDraftId = draftId
    loading.value = true
    error.value = ''
    try {
      const snapshot = await request<CreationDraftVersion>(
        `/api/creation-drafts/${draftId}/versions/${version}`)
      if (activeDraftId !== requestedDraftId) return null
      snapshots.value = { ...snapshots.value, [version]: snapshot }
      return snapshot
    } catch (err: unknown) {
      if (activeDraftId === requestedDraftId) {
        error.value = err instanceof Error ? err.message : '草稿版本加载失败'
      }
      return null
    } finally {
      if (activeDraftId === requestedDraftId) loading.value = false
    }
  }

  return { versions, snapshots, nextCursor, loading, error, reset, listVersions, getVersion }
}
