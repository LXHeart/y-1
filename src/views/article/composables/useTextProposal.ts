import { onScopeDispose, ref } from 'vue'
import type { CreationProject } from '../../../types/creation'
import type { TextProposal } from '../../../types/creation-studio'

/**
 * 任务书 #101 C101-04：文本建议 API 客户端（API101-05～07）。
 *
 * prepare 同键重试沿用 requestId（断线重放安全）；apply 经 runExternalMutation 与共享保存队列
 * 互斥执行。迟到响应用 epoch 丢弃；卸载后全部失效。
 */
export function useTextProposal(options: {
  draftId: () => string | null
  draftVersion: () => number
  sourceDocumentId: () => string | null
  runExternalMutation: <T>(action: (expectedVersion: number) =>
    Promise<{ project: CreationProject; value: T } | null>) => Promise<T | null>
  onApplied: (project: CreationProject) => void
}) {
  const preparing = ref(false)
  const applying = ref(false)
  const error = ref('')
  const current = ref<TextProposal | null>(null)
  let epoch = 0
  let pendingPrepareKey: string | null = null

  onScopeDispose(() => { epoch += 1 })

  async function request<T>(url: string, init: RequestInit): Promise<T | null> {
    const response = await fetch(url, init)
    const body = await response.json().catch(() => null) as { success?: boolean; data?: T; error?: string } | null
    if (!response.ok || !body?.success) {
      error.value = body?.error || '请求失败，请重试'
      return null
    }
    return body.data ?? null
  }

  async function prepare(action: 'adapt-body' | 'suggest-metadata', instructions = ''): Promise<void> {
    const draftId = options.draftId()
    if (!draftId || preparing.value) return
    const requestEpoch = ++epoch
    preparing.value = true
    error.value = ''
    const key = `${action}:${instructions}:${draftId}:${options.draftVersion()}`
    // 同一意图沿用 requestId（服务端 preparing 幂等，重复 preparing 不再请求模型）。
    if (pendingPrepareKey !== key) {
      pendingPrepareKey = key
      prepareRequestId = crypto.randomUUID()
    }
    try {
      const source = options.sourceDocumentId()
      const data = await request<TextProposal>('/api/creation-studio/text-proposals', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          requestId: prepareRequestId,
          draftId,
          expectedDraftVersion: options.draftVersion(),
          action,
          ...(source ? { source: { id: source, contentHash: sourceContentHash.value } } : {}),
          instructions,
        }),
      })
      if (requestEpoch !== epoch) return
      if (data) current.value = data
    } finally {
      if (requestEpoch === epoch) preparing.value = false
    }
  }

  let prepareRequestId = crypto.randomUUID()
  const sourceContentHash = ref('')

  function bindSource(contentHash: string): void {
    sourceContentHash.value = contentHash
  }

  async function refresh(id: string): Promise<void> {
    const requestEpoch = ++epoch
    const data = await request<TextProposal>(`/api/creation-studio/text-proposals/${id}`, { method: 'GET' })
    if (requestEpoch !== epoch || !data) return
    current.value = data
  }

  async function apply(id: string, fields: Array<'title' | 'body' | 'summary'>): Promise<boolean> {
    if (applying.value) return false
    applying.value = true
    error.value = ''
    try {
      const result = await options.runExternalMutation<boolean>(async (expectedVersion) => {
        const data = await request<{ project: CreationProject; appliedVersion: number; alreadyApplied: boolean }>(
          `/api/creation-studio/text-proposals/${id}/apply`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ requestId: crypto.randomUUID(), expectedVersion, fields }),
          })
        if (!data) return null
        return { project: data.project, value: data.alreadyApplied }
      })
      if (result == null) return false
      const refreshed = await request<TextProposal>(`/api/creation-studio/text-proposals/${id}`, { method: 'GET' })
      if (refreshed) current.value = refreshed
      return true
    } finally {
      applying.value = false
    }
  }

  function dismiss(): void {
    epoch += 1
    current.value = null
    error.value = ''
    pendingPrepareKey = null
  }

  return { preparing, applying, error, current, prepare, refresh, apply, dismiss, bindSource }
}
