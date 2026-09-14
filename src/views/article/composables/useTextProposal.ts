import { ref } from 'vue'
import type { CreationProject } from '../../../types/creation'
import type { SourceDocument, TextProposal } from '../../../types/creation-studio'
import { studioPost, studioRequest, studioErrorMessage, useStudioActivity, useStudioGuard } from '../../../lib/creation-studio-http'

export function useTextProposal(options: {
  draftId: () => string | null
  draftVersion: () => number
  sourceDocumentId: () => string | null
  runExternalMutation: <T>(action: (expectedVersion: number) =>
    Promise<{ project: CreationProject; value: T } | null>) => Promise<T | null>
  onApplied: (project: CreationProject) => void
  beforePrepare?: () => Promise<boolean>
  onPrepared?: (proposal: TextProposal) => void
}) {
  const preparing = ref(false)
  const applying = ref(false)
  const error = ref('')
  const current = ref<TextProposal | null>(null)
  const source = ref<SourceDocument | null>(null)
  const selectedBlockIds = ref<string[]>([])
  const guard = useStudioGuard(options.draftId)
  let pendingPrepare: { key: string; requestId: string } | null = null
  let pendingApply: { key: string; requestId: string; version: number } | null = null
  let requestSequence = 0
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let pollCount = 0
  function stopPolling(): void { if (pollTimer) clearTimeout(pollTimer); pollTimer = null }
  const activity = useStudioActivity(stopPolling, () => {
    if (current.value && !preparing.value && !applying.value) void refresh(current.value.id)
  })
  guard.onInvalidate(() => {
    stopPolling()
    requestSequence += 1
    current.value = null
    source.value = null
    selectedBlockIds.value = []
    error.value = ''
    preparing.value = false
    applying.value = false
    pendingPrepare = null
    pendingApply = null
    pollCount = 0
  })

  function bindSource(document: SourceDocument, ids = document.blocks.map(block => block.id)): void {
    source.value = document
    selectedBlockIds.value = [...ids]
  }

  function poll(): void {
    stopPolling()
    if (!activity.isActive() || current.value?.status !== 'preparing') return
    if (pollCount >= 60) { error.value = '建议生成耗时较长，请刷新状态核实'; return }
    pollTimer = setTimeout(() => { pollCount += 1; void refresh(current.value!.id) }, 2000)
  }

  async function prepare(action: 'adapt-body' | 'suggest-metadata', instructions = ''): Promise<void> {
    if (preparing.value || applying.value) return
    preparing.value = true
    error.value = ''
    const isCurrent = guard.capture()
    try {
      if (options.beforePrepare && !await options.beforePrepare()) return
      if (!isCurrent()) return
      const draftId = options.draftId()
      const document = source.value
      if (!draftId || !document || !selectedBlockIds.value.length) {
        error.value = '请先保存原稿并选择处理范围'
        return
      }
      const payload = { draftId, expectedDraftVersion: options.draftVersion(), action,
        source: { id: document.id, contentHash: document.contentHash },
        selectedBlockIds: [...selectedBlockIds.value], instructions }
      const key = JSON.stringify(payload)
      if (pendingPrepare?.key !== key) pendingPrepare = { key, requestId: crypto.randomUUID() }
      const data = await studioPost<TextProposal>('/api/creation-studio/text-proposals',
        { ...payload, requestId: pendingPrepare.requestId }, 120_000)
      if (!await activity.whenActive() || !isCurrent()) return
      current.value = data
      options.onPrepared?.(data)
      if (data.status === 'failed') pendingPrepare = null
      pollCount = 0
      poll()
    } catch (failure) {
      if (await activity.whenActive() && isCurrent()) error.value = studioErrorMessage(failure)
    } finally { if (isCurrent()) preparing.value = false }
  }

  async function refresh(id: string): Promise<void> {
    if (!activity.isActive()) return
    const isCurrent = guard.capture()
    const isActive = activity.capture()
    const sequence = ++requestSequence
    try {
      const data = await studioRequest<TextProposal>('/api/creation-studio/text-proposals/' + id)
      if (!isCurrent() || !isActive() || sequence !== requestSequence) return
      current.value = data
      error.value = ''
      poll()
      if (data.source?.id && source.value?.id !== data.source.id) {
        const document = await studioRequest<SourceDocument>('/api/creation-studio/sources/' + data.source.id)
        if (isCurrent() && isActive() && sequence === requestSequence) bindSource(document)
      }
    } catch (failure) { if (isCurrent() && isActive() && sequence === requestSequence) error.value = studioErrorMessage(failure) }
  }

  async function apply(id: string, fields: Array<'title' | 'body' | 'summary'>): Promise<boolean> {
    if (applying.value || current.value?.status !== 'ready') return false
    applying.value = true
    error.value = ''
    const isCurrent = guard.capture()
    try {
      const project = await options.runExternalMutation<CreationProject>(async expectedVersion => {
        if (!isCurrent()) return null
        const key = JSON.stringify([id, fields])
        if (pendingApply?.key !== key) pendingApply = { key, requestId: crypto.randomUUID(), version: expectedVersion }
        const data = await studioPost<{ project: CreationProject; appliedVersion: number; alreadyApplied: boolean }>(
          '/api/creation-studio/text-proposals/' + id + '/apply', {
            requestId: pendingApply.requestId, expectedDraftVersion: pendingApply.version, fields,
          })
        if (!isCurrent()) return null
        return { project: data.project, value: data.project }
      })
      if (!await activity.whenActive() || !isCurrent() || project == null) return false
      options.onApplied(project)
      await refresh(id)
      return true
    } catch (failure) {
      if (await activity.whenActive() && isCurrent()) error.value = studioErrorMessage(failure)
      return false
    } finally { if (isCurrent()) applying.value = false }
  }

  return { preparing, applying, error, current, source, selectedBlockIds,
    prepare, refresh, apply, dismiss: guard.invalidate, bindSource }
}
