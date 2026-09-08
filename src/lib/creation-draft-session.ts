import { effectScope, getCurrentInstance, watch } from 'vue'
import type { Pinia } from 'pinia'
import { useCreationDraft } from '../composables/useCreationDraft'
import { useAccountSessionStore } from '../stores/account-session'
import type { CreationDraft } from '../types/creation-assistant'
import type { CreationProject } from '../types/creation'

type DraftSession = ReturnType<typeof useCreationDraft>
const sessions = new WeakMap<Pinia, Map<string, DraftSession>>()

/** The workspace and assistant editing the same draft share one version and write queue. */
export function useCreationDraftSessions() {
  const pinia = getCurrentInstance()?.appContext.config.globalProperties.$pinia as Pinia | undefined
  const account = pinia ? useAccountSessionStore(pinia) : undefined
  let pool = pinia ? sessions.get(pinia) : undefined
  if (pinia && !pool) {
    pool = new Map()
    sessions.set(pinia, pool)
    const cache = pool
    effectScope(true).run(() => {
      if (account) watch(() => account.epoch, () => cache.clear(), { flush: 'sync' })
    })
  }
  return (id?: string): DraftSession => {
  const existing = id ? pool?.get(id) : undefined
  if (existing) return existing
  const scope = effectScope(true)
  const store = scope.run(() => {
    const session = useCreationDraft({ autosaveDelayMs: 800, persistent: true, account })
    watch(() => session.draft.value?.id, (next, previous) => {
      if (previous && pool?.get(previous) === session) pool.delete(previous)
      if (next) pool?.set(next, session)
    }, { flush: 'sync' })
    return session
  })!
  if (id) pool?.set(id, store)
  return store
  }
}

export function projectAsDraft(project: CreationProject): CreationDraft {
  return {
    ...project,
    sourceType: (project.sourceType || 'independent') as CreationDraft['sourceType'],
    createdAt: project.createdAt || project.updatedAt,
  }
}

export function draftAsProject(draft: CreationDraft): CreationProject {
  return {
    ...draft,
    capability: draft.capability ?? 'article',
    workspace: draft.workspace ?? {},
    resultAssetIds: draft.resultAssetIds ?? [],
    runIds: draft.runIds ?? [],
  }
}
