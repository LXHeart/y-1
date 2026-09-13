import { computed } from 'vue'
import type { CreationDeliveryContract, CreationResultRef } from '../../../types/creation'
import type { useCreationDraftSessions } from '../../../lib/creation-draft-session'

/** Delivery and explicit media choices share the draft's existing serialized, versioned save queue. */
export function queueDeliverySave(
  getDraftSession: ReturnType<typeof useCreationDraftSessions>,
  draftId: () => string,
  currentDelivery: () => Partial<CreationDeliveryContract>,
  platform: () => string,
  options: { prepareReferences?: () => boolean; flushProject?: () => Promise<boolean> } = {},
) {
  const session = computed(() => draftId() ? getDraftSession(draftId()) : null)
  const state = computed(() => session.value?.autosaveState.value ?? 'idle')
  const error = computed(() => session.value?.error.value ?? '')
  function queue(next: Partial<CreationDeliveryContract>): void {
    const target = session.value
    const current = target?.draft.value
    if (!target || !current || current.status === 'archived' || target.readonly.value) return
    const workspace = current.workspace ?? {}
    const resultRefs = [...((workspace.resultRefs ?? []) as CreationResultRef[])]
    if ('coverRef' in next) {
      const withoutCover = resultRefs.filter(ref => ref.role !== 'cover')
      if (next.coverRef && !withoutCover.some(ref => ref.refType === next.coverRef?.refType && ref.id === next.coverRef.id))
        withoutCover.push(next.coverRef)
      resultRefs.splice(0, resultRefs.length, ...withoutCover)
    }
    target.queueSave({ workspace: { ...workspace, ...('coverRef' in next ? { resultRefs } : {}),
      delivery: { version: 1, platform: platform(), contentForm: 'video', ...currentDelivery(), ...next } } })
  }
  async function flush(): Promise<boolean> {
    const id = draftId(); const target = session.value
    if (!id || !target) return true
    return await target.flush() && draftId() === id
  }
  async function beforeExport(): Promise<number | false> {
    const id = draftId()
    if (!id || (options.prepareReferences && !options.prepareReferences())) return false
    const saved = options.flushProject ? await options.flushProject() : await flush()
    if (!saved || draftId() !== id) return false
    return session.value?.draft.value?.version ?? false
  }
  return Object.assign(queue, { queue, flush, beforeExport, state, error })
}
