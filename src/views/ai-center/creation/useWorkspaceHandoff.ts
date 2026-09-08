import { onScopeDispose, shallowRef, watch } from 'vue'
import type { CreationHandoff, CreationSource, CreationTargetView } from '../../../types/ai-creation'
import type { CreationProject } from '../../../types/creation'
import type { useWorkspaceAutosave } from './useWorkspaceAutosave'

/** Keep the applied source stable while the previous project's final save is in flight. */
export function useWorkspaceSource() {
  const source = shallowRef<CreationSource>({ type: 'independent' })
  const contextSnapshotId = shallowRef<string | undefined>()
  const contentForm = shallowRef('graphic')
  const locked = shallowRef(false)
  const mustInclude = shallowRef<string[]>([])
  const questionLocked = shallowRef(false)

  function accept(handoff: CreationHandoff): void {
    source.value = { ...handoff.source }
    contextSnapshotId.value = handoff.contextSnapshotId
    contentForm.value = handoff.contentFormId
    locked.value = true
    questionLocked.value = handoff.platformId === 'zhihu' && Boolean(handoff.taskContext?.questionText?.trim())
    const terms = handoff.taskContext?.requirements?.mustInclude
    mustInclude.value = Array.isArray(terms) ? terms.filter((term): term is string => typeof term === 'string') : []
  }

  function restore(project: CreationProject): void {
    const inputs = project.workspace?.inputs ?? {}
    const saved = inputs.sourceContext as { source?: CreationSource; locked?: boolean; questionLocked?: boolean; mustInclude?: string[] } | undefined
    source.value = saved?.source ?? (project.sourceType === 'task'
      ? { type: 'task', taskId: project.taskId ?? '', taskVersion: project.taskVersion }
      : project.sourceType === 'store' ? { type: 'store', storeId: project.storeId ?? '', organizationId: '' }
        : { type: 'independent' })
    contextSnapshotId.value = typeof inputs.contextSnapshotId === 'string' ? inputs.contextSnapshotId : undefined
    contentForm.value = project.contentForm ?? 'graphic'
    locked.value = saved?.locked ?? project.sourceType === 'task'
    questionLocked.value = saved?.questionLocked ?? (project.sourceType === 'task' && project.contentMode === 'answer')
    mustInclude.value = saved?.mustInclude ?? []
  }

  function collectInputs() {
    return { contextSnapshotId: contextSnapshotId.value,
      sourceContext: { source: source.value, locked: locked.value, questionLocked: questionLocked.value, mustInclude: mustInclude.value } }
  }

  function collectSource() {
    return { sourceType: source.value.type,
      taskId: source.value.type === 'task' ? source.value.taskId : undefined,
      taskVersion: source.value.type === 'task' ? source.value.taskVersion : undefined,
      storeId: source.value.type === 'store' ? source.value.storeId : undefined }
  }
  return { source, contextSnapshotId, contentForm, locked, mustInclude, questionLocked, accept, restore, collectInputs, collectSource }
}

export function useWorkspaceHandoff(options: {
  handoff: () => CreationHandoff | null | undefined
  target: CreationTargetView
  autosave: ReturnType<typeof useWorkspaceAutosave>
  apply: (handoff: CreationHandoff) => void
  cancel: () => void
}) {
  let seen: number | null = null
  let firstRun = true
  let pending: CreationHandoff | null = null
  let switching = false
  let disposed = false

  async function drain(): Promise<void> {
    if (!pending || switching || disposed) return
    switching = true
    options.cancel()
    try {
      if (!await options.autosave.startNew() || disposed) return
      const next = pending
      pending = null
      if (next) options.apply(next)
    } finally { switching = false }
  }

  watch(options.handoff, (handoff) => {
    const initial = firstRun
    firstRun = false
    if (!handoff || handoff.targetView !== options.target || handoff.revision === seen) return
    seen = handoff.revision
    if (initial) {
      if (!options.autosave.restoredProjectId.value && !options.autosave.isRestoring()) options.apply(handoff)
      return
    }
    pending = JSON.parse(JSON.stringify(handoff)) as CreationHandoff
    void drain()
  }, { immediate: true })
  // A failed save keeps both the editor and the requested destination until the user resolves it.
  watch(options.autosave.saveState, (state) => { if (state === 'saved') void drain() })
  onScopeDispose(() => { disposed = true; pending = null })
}
