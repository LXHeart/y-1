import { computed, nextTick, onScopeDispose, ref, shallowRef, watch } from 'vue'
import type { Ref } from 'vue'
import { useCreationWorkspace } from '../../../lib/creation-workspace'
import { draftAsProject, projectAsDraft, useCreationDraftSessions } from '../../../lib/creation-draft-session'
import { creationDraftFingerprint } from '../../../lib/creation-draft-json'
import { creationDeclarations } from '../../../lib/creation-delivery'
import type { CreateDraftInput, AutosaveState } from '../../../types/creation-assistant'
import type {
  CreationBrief, CreationDeliveryContract, CreationProject, CreationProjectCapability, CreationProjectFields,
  CreationProjectStatus, CreationResultRef, CreationWorkspacePayload,
} from '../../../types/creation'

export type WorkspaceSaveState = AutosaveState

export interface WorkspaceAutosaveOptions {
  capability: CreationProjectCapability
  workflow?: string
  steps: readonly string[]
  currentStep: Ref<string>
  collectInputs: () => Record<string, unknown>
  omitInputKeys?: readonly string[]
  collectDraftFields?: () => CreationProjectFields
  collectSource?: () => Pick<CreateDraftInput, 'sourceType' | 'taskId' | 'taskVersion' | 'storeId'>
  applyInputs: (inputs: Record<string, unknown>) => void
  applyProject?: (project: CreationProject) => void
  isValidInput: () => boolean
  deriveTitle: () => string
  collectResultAssetIds?: () => string[]
  collectBrief?: () => CreationBrief | undefined
  collectResultRefs?: () => CreationResultRef[]
  collectDelivery?: () => Partial<CreationDeliveryContract>
  collectRunIds?: () => string[]
  collectStatus?: () => CreationProjectStatus
  delivery?: boolean
  restoreRouteDraftId?: () => string | null
  engage: () => boolean
}

/** Collect/restore stays workflow-specific; creation, versions and saves use the shared draft queue. */
export function useWorkspaceAutosave(options: WorkspaceAutosaveOptions) {
  const workspace = useCreationWorkspace()
  const getDraftSession = useCreationDraftSessions()
  const session = shallowRef(getDraftSession())
  const draftId = computed(() => session.value.draft.value?.id ?? '')
  const draftVersion = computed(() => session.value.remoteDraft.value?.version ?? session.value.draft.value?.version ?? 0)
  const saveState = computed<WorkspaceSaveState>(() => session.value.autosaveState.value)
  const readonly = computed(() => session.value.readonly.value)
  const conflictNotice = computed(() => readonly.value
    ? '当前工作区版本暂不支持编辑，已有内容可查看'
    : saveState.value === 'conflict' ? '草稿已在其他设备修改；可载入远端版本，或确认保留当前编辑' : '')
  const restoredProjectId = ref('')
  const declarations = ref(creationDeclarations())
  let timer: ReturnType<typeof setTimeout> | null = null
  let flushing: Promise<boolean> | null = null
  let restoring = false
  let restoringWatchers = false
  let collecting = false
  let disposed = false
  let revision = 0
  let lastCollected = ''
  const engaged = options.engage()

  function clearTimer(): void {
    if (timer) clearTimeout(timer)
    timer = null
  }

  function apply(project: CreationProject): void {
    restoring = true
    restoringWatchers = true
    const payload = project.workspace ?? {}
    declarations.value = creationDeclarations(payload.delivery?.declarations)
    options.applyInputs(payload.inputs ?? {})
    options.applyProject?.(project)
    options.currentStep.value = typeof payload.currentStep === 'string' && options.steps.includes(payload.currentStep)
      ? payload.currentStep : options.steps[0]
    restoredProjectId.value = project.id
    lastCollected = creationDraftFingerprint(collect())
    restoring = false
    void nextTick(() => { restoringWatchers = false })
  }

  function adopt(project: CreationProject): void {
    revision += 1
    session.value = getDraftSession(project.id)
    const current = session.value.draft.value
    if (!current || (project.version > current.version && ['idle', 'saved'].includes(session.value.autosaveState.value))) {
      session.value.adopt(projectAsDraft(project))
    }
    apply(draftAsProject(session.value.draft.value!))
    workspace.setCurrentProjectId(project.id)
  }

  function collect() {
    const previous: CreationWorkspacePayload = session.value.draft.value?.workspace ?? {}
    const fields = options.collectDraftFields?.()
    const inputs = { ...previous.inputs, ...options.collectInputs() }
    for (const key of options.omitInputKeys ?? []) delete inputs[key]
    if (options.collectBrief) inputs.brief = options.collectBrief()
    const next: CreationWorkspacePayload = {
      ...previous, schemaVersion: 1, capability: options.capability,
      ...(options.workflow ? { workflow: options.workflow } : {}),
      currentStep: options.currentStep.value, inputs,
      ...(options.collectResultRefs ? { resultRefs: options.collectResultRefs() } : {}),
      ...(options.delivery ? { delivery: { ...previous.delivery, version: 1,
        platform: fields?.platform ?? previous.delivery?.platform ?? '',
        contentForm: fields?.contentForm ?? previous.delivery?.contentForm ?? '',
        ...options.collectDelivery?.(), declarations: declarations.value } } : {}),
    }
    delete next.brief
    return {
      ...fields,
      title: options.deriveTitle() || '未命名草稿',
      capability: options.capability,
      workspace: next,
      ...(options.collectResultAssetIds ? { resultAssetIds: options.collectResultAssetIds() } : {}),
      ...(options.collectRunIds ? { runIds: options.collectRunIds() } : {}),
      ...(options.collectStatus ? { status: options.collectStatus() } : {}),
    }
  }

  if (engaged) {
    const pending = workspace.pendingContinue.value
    if (pending && pending.capability === options.capability) {
      adopt(pending)
      workspace.setPendingContinue(null)
    } else {
      workspace.setCurrentProjectId('')
      const id = options.restoreRouteDraftId?.()
      if (id) {
        restoring = true
        const epoch = revision
        void workspace.loadProject(id).then((project) => {
          if (disposed || epoch !== revision) return
          if (project && project.capability === options.capability) adopt(project)
          else restoring = false
        })
      }
    }
  }

  watch([() => session.value, () => session.value.draft.value], ([store, draft], [previousStore, previous]) => {
    if (!engaged) return
    if (store !== previousStore) return
    if (!draft && previous) {
      revision += 1
      clearTimer()
      workspace.setCurrentProjectId('')
      // Session invalidation must not create a new owner's draft from the previous editor data.
      disposed = true
      return
    }
    if (draft) workspace.setCurrentProjectId(draft.id)
    if (draft && previous && !flushing && !restoring && !collecting) apply(draftAsProject(draft))
  }, { flush: 'sync' })

  function enqueue(): void {
    const payload = collect()
    const key = creationDraftFingerprint(payload)
    if (key === lastCollected && saveState.value !== 'error') return
    collecting = true
    lastCollected = key
    session.value.queueSave(payload)
    collecting = false
  }

  function queueSave(): void {
    if (!engaged || restoring || restoringWatchers || disposed || readonly.value) return
    clearTimer()
    if (draftId.value) enqueue()
    else {
      session.value.autosaveState.value = 'pending'
      timer = setTimeout(() => { void flush() }, 800)
    }
  }

  function flush(): Promise<boolean> {
    clearTimer()
    if (!engaged || restoring || disposed) return Promise.resolve(true)
    if (readonly.value || saveState.value === 'conflict') return Promise.resolve(false)
    if (flushing) return flushing
    if (!draftId.value && !options.isValidInput()) {
      session.value.autosaveState.value = 'idle'
      return Promise.resolve(true)
    }
    const epoch = revision
    const store = session.value
    const operation = async () => {
      if (!draftId.value) {
        if (!options.isValidInput()) { store.autosaveState.value = 'idle'; return true }
        const payload = collect()
        lastCollected = creationDraftFingerprint(payload)
        store.autosaveState.value = 'saving'
        const created = await store.createDraft({ sourceType: 'independent', ...options.collectSource?.(), ...payload })
        if (!created || epoch !== revision) return false
        store.autosaveState.value = 'saved'
        if (payload.status && payload.status !== created.status) store.queueSave({ status: payload.status })
      }
      enqueue()
      const saved = await store.flush()
      if (!saved && store.autosaveState.value === 'conflict') await store.readConflict()
      return saved
    }
    flushing = operation().finally(() => { flushing = null })
    return flushing
  }

  async function retry(): Promise<void> {
    if (readonly.value) return
    if (saveState.value === 'conflict') {
      collecting = true
      const payload = collect()
      lastCollected = creationDraftFingerprint(payload)
      session.value.queueSave(payload)
      collecting = false
      await session.value.keepLocalForConflict()
    } else await flush()
  }

  async function reloadRemote(): Promise<void> {
    const fresh = await session.value.reloadForConflict()
    if (fresh) apply(draftAsProject(fresh))
  }

  async function startNew(): Promise<boolean> {
    const epoch = revision
    if ((!readonly.value && !await flush()) || epoch !== revision || disposed) return false
    revision += 1
    restoring = false
    restoringWatchers = false
    session.value = getDraftSession()
    lastCollected = ''
    restoredProjectId.value = ''
    declarations.value = creationDeclarations()
    workspace.setCurrentProjectId('')
    return true
  }

  onScopeDispose(() => { clearTimer(); void flush(); disposed = true })
  watch(declarations, () => queueSave(), { deep: true })

  return { draftId, draftVersion, saveState, readonly, conflictNotice, restoredProjectId,
    declarations, queueSave, flush, retry, reloadRemote, startNew, isRestoring: () => restoring || restoringWatchers }
}
