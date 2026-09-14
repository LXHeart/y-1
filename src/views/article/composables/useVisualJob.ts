import { computed, ref } from 'vue'
import type { VisualJob, VisualJobItem, VisualQuote, StudioPage, ImageCapabilities } from '../../../types/creation-studio'
import type { useVisualPlan } from './useVisualPlan'
import { studioPost, studioRequest, studioErrorMessage, useStudioActivity, useStudioGuard } from '../../../lib/creation-studio-http'

const terminal = (state: string) => ['succeeded', 'partial', 'failed', 'cancelled', 'unknown'].includes(state)

export function useVisualJob(options: {
  plan: () => ReturnType<typeof useVisualPlan>['current']['value']
  beforeGenerate?: () => Promise<boolean>
  onError?: (message: string) => void
}) {
  const current = ref<VisualJob | null>(null)
  const history = ref<VisualJob[]>([])
  const historyNextCursor = ref<string | null>(null)
  const historyLoading = ref(false)
  const quote = ref<VisualQuote | null>(null)
  const capabilities = ref<ImageCapabilities | null>(null)
  const quoting = ref(false)
  const creating = ref(false)
  const cancelling = ref(false)
  const error = ref('')
  const polling = ref(false)
  const pollTimedOut = ref(false)
  const selectedCandidate = ref<{ itemId: string; artifactId: string } | null>(null)
  const guard = useStudioGuard(() => {
    const plan = options.plan()
    return plan ? plan.id + ':' + plan.revision : ''
  })
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let startedAt = 0
  let readSequence = 0
  let paused = false
  let pendingCreate: { key: string; body: Record<string, unknown> } | null = null
  let pendingCancel: { key: string; requestId: string } | null = null

  function stopPolling(): void {
    if (pollTimer) clearTimeout(pollTimer)
    pollTimer = null
    polling.value = false
  }
  function reset(): void {
    readSequence += 1
    stopPolling()
    current.value = null; history.value = []; historyNextCursor.value = null
    quote.value = null; capabilities.value = null; error.value = ''
    selectedCandidate.value = null; pendingCreate = null; pendingCancel = null
    quoting.value = false; creating.value = false; cancelling.value = false; historyLoading.value = false
    pollTimedOut.value = false; startedAt = 0
  }
  guard.onInvalidate(reset)

  function remember(job: VisualJob): void {
    assertJob(job)
    const plan = options.plan()
    if (plan && (job.plan.id !== plan.id || job.plan.revision !== plan.revision)) return
    const index = history.value.findIndex(entry => entry.id === job.id)
    if (index >= 0) history.value.splice(index, 1, job)
    else history.value.unshift(job)
  }
  function assertJob(job: VisualJob): void {
    const plan = options.plan()
    if (!job?.id || !job.plan?.id || !Array.isArray(job.items) || !Number.isInteger(job.version) || job.version < 1
      || (plan && (job.draftId !== plan.draftId || job.plan.id !== plan.id || job.plan.revision !== plan.revision))) {
      throw new Error('视觉任务响应与当前计划不一致')
    }
  }
  const allJobs = computed(() => {
    const jobs = current.value ? [current.value, ...history.value.filter(job => job.id !== current.value!.id)] : history.value
    const plan = options.plan()
    return jobs.filter(job => !plan || (job.plan.id === plan.id && job.plan.revision === plan.revision))
  })
  const itemStates = computed<VisualJobItem[]>(() => {
    const seen = new Map<string, VisualJobItem>()
    for (const job of allJobs.value) for (const item of job.items) if (!seen.has(item.itemId)) seen.set(item.itemId, item)
    return [...seen.values()].sort((a, b) => a.position - b.position)
  })
  const candidates = computed<VisualJobItem[]>(() => {
    const seen = new Map<string, VisualJobItem>()
    for (const job of allJobs.value) for (const item of job.items) {
      if (item.state === 'succeeded' && item.artifact) seen.set(item.artifact.id, item)
    }
    return [...seen.values()].sort((a, b) => a.position - b.position)
  })

  async function loadCapabilities(): Promise<void> {
    const plan = options.plan()
    if (!plan) return
    const valid = guard.capture()
    try {
      const result = await studioRequest<{ image: ImageCapabilities }>(
        '/api/creation-studio/drafts/' + plan.draftId + '/capabilities')
      if (valid()) capabilities.value = result.image
    } catch (failure) { if (valid()) error.value = studioErrorMessage(failure) }
  }

  async function estimate(selectedItemIds: string[], consistencyMode: 'prompt-only' | 'reference-image' = 'prompt-only',
    anchorArtifactId?: string): Promise<VisualQuote | null> {
    if (quoting.value || creating.value) return null
    const valid = guard.capture()
    quoting.value = true; error.value = ''; quote.value = null
    try {
      if (options.beforeGenerate && !await options.beforeGenerate()) return null
      const plan = options.plan()
      if (!valid() || !plan || plan.stale || plan.confirmedRevision !== plan.revision) {
        if (valid()) error.value = '请先保存并确认当前计划'
        return null
      }
      const result = await studioPost<VisualQuote>('/api/creation-studio/visual-plans/' + plan.id + '/estimate', {
        requestId: crypto.randomUUID(), expectedRevision: plan.revision, selectedItemIds, consistencyMode,
        ...(anchorArtifactId ? { anchorArtifactId } : {}),
      })
      if (!valid()) return null
      quote.value = result
      return result
    } catch (failure) { if (valid()) error.value = studioErrorMessage(failure); return null }
    finally { if (valid()) quoting.value = false }
  }

  async function create(input: {
    selectedItemIds: string[]
    consistencyMode?: 'prompt-only' | 'reference-image'
    anchorArtifactId?: string
    quoteId: string
    acknowledgedUnknownAttemptIds?: string[]
  }): Promise<VisualJob | null> {
    const plan = options.plan()
    if (!plan || creating.value) return null
    const valid = guard.capture()
    creating.value = true; error.value = ''
    const payload = { plan: { id: plan.id, revision: plan.revision }, quoteId: input.quoteId,
      selectedItemIds: input.selectedItemIds, consistencyMode: input.consistencyMode ?? 'prompt-only',
      ...(input.anchorArtifactId ? { anchorArtifactId: input.anchorArtifactId } : {}),
      ...(input.acknowledgedUnknownAttemptIds?.length
        ? { acknowledgedUnknownAttemptIds: input.acknowledgedUnknownAttemptIds } : {}) }
    const key = JSON.stringify(payload)
    if (pendingCreate?.key !== key) pendingCreate = { key, body: { ...payload, requestId: crypto.randomUUID() } }
    try {
      const job = await studioPost<VisualJob>('/api/creation-studio/visual-jobs', pendingCreate.body)
      if (!valid()) return null
      remember(job)
      current.value = job
      startedAt = Date.now()
      pollTimedOut.value = false
      scheduleNext()
      return job
    } catch (failure) { if (valid()) error.value = studioErrorMessage(failure); return null }
    finally { if (valid()) creating.value = false }
  }

  function scheduleNext(): void {
    stopPolling()
    if (paused || (typeof document !== 'undefined' && document.hidden) || !current.value || terminal(current.value.state)) return
    if (Date.now() - startedAt >= 30 * 60_000) { pollTimedOut.value = true; return }
    polling.value = true
    pollTimer = setTimeout(() => { void tick() }, Date.now() - startedAt >= 60_000 ? 5000 : 2000)
  }

  async function tick(): Promise<void> {
    if (!current.value || paused || !activity.isActive()) return
    if (typeof document !== 'undefined' && document.hidden) { stopPolling(); return }
    const id = current.value.id
    const valid = guard.capture()
    const sequence = ++readSequence
    try {
      const job = await studioRequest<VisualJob>('/api/creation-studio/visual-jobs/' + id, { method: 'GET' })
      if (!valid() || sequence !== readSequence || paused) return
      remember(job); current.value = job
      scheduleNext()
    } catch (failure) {
      if (valid() && sequence === readSequence) {
        stopPolling()
        error.value = studioErrorMessage(failure)
      }
    }
  }

  function pause(): void { paused = true; readSequence += 1; stopPolling() }
  function resume(): void {
    paused = false
    if (!current.value || polling.value || terminal(current.value.state) || pollTimedOut.value) return
    void tick()
  }
  const activity = useStudioActivity(pause, resume)

  async function refresh(jobId?: string): Promise<void> {
    const id = jobId ?? current.value?.id
    if (!id) return
    const valid = guard.capture()
    const sequence = ++readSequence
    stopPolling()
    try {
      const job = await studioRequest<VisualJob>('/api/creation-studio/visual-jobs/' + id, { method: 'GET' })
      if (!valid() || sequence !== readSequence) return
      remember(job); current.value = job
      error.value = ''; pollTimedOut.value = false; startedAt = Date.now()
      scheduleNext()
    } catch (failure) { if (valid() && sequence === readSequence) error.value = studioErrorMessage(failure) }
  }

  async function loadHistory(append = false): Promise<void> {
    const draftId = options.plan()?.draftId ?? current.value?.draftId
    if (!draftId || historyLoading.value) return
    const valid = guard.capture()
    historyLoading.value = true
    try {
      const query = new URLSearchParams({ draftId, limit: '50' })
      if (append && historyNextCursor.value) query.set('cursor', historyNextCursor.value)
      const page = await studioRequest<StudioPage<VisualJob>>('/api/creation-studio/visual-jobs?' + query)
      if (!valid()) return
      const known = new Set(history.value.map(job => job.id))
      for (const job of page.items) if (!known.has(job.id)) history.value.push(job)
      historyNextCursor.value = page.nextCursor
    } catch (failure) { if (valid()) error.value = studioErrorMessage(failure) }
    finally { if (valid()) historyLoading.value = false }
  }

  async function restore(jobId: string): Promise<void> { await refresh(jobId); await loadHistory() }
  async function cancel(): Promise<boolean> {
    const job = current.value
    if (!job || cancelling.value) return false
    const valid = guard.capture()
    const key = JSON.stringify([job.id, job.version])
    if (pendingCancel?.key !== key) pendingCancel = { key, requestId: crypto.randomUUID() }
    cancelling.value = true; error.value = ''
    try {
      const result = await studioPost<VisualJob>('/api/creation-studio/visual-jobs/' + job.id + '/cancel', {
        requestId: pendingCancel.requestId, expectedVersion: job.version,
      })
      if (!valid() || current.value?.id !== job.id) return false
      remember(result); current.value = result; scheduleNext()
      return true
    } catch (failure) { if (valid()) error.value = studioErrorMessage(failure); return false }
    finally { if (valid()) cancelling.value = false }
  }
  async function redo(selectedItemIds: string[], acknowledgedUnknownAttemptIds: string[] = []): Promise<VisualJob | null> {
    const fresh = await estimate(selectedItemIds)
    if (!fresh) return null
    return create({ selectedItemIds, quoteId: fresh.id, acknowledgedUnknownAttemptIds })
  }
  function selectCandidate(itemId: string, artifactId: string): void {
    selectedCandidate.value = { itemId, artifactId }
  }

  return { current, history, historyNextCursor, historyLoading, itemStates, candidates, capabilities,
    quote, quoting, creating, cancelling, error, polling, pollTimedOut, selectedCandidate,
    estimate, create, refresh, restore, loadHistory, loadCapabilities, cancel, redo, resume, pause,
    selectCandidate, dismiss: guard.invalidate }
}

export type VisualJobController = ReturnType<typeof useVisualJob>
