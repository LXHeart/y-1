import { computed, shallowReactive, watch } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'

type RecoveryKey = 'studioPlan' | 'studioProposal' | 'studioJob'

/** Stable server IDs recover in-flight work without advancing the proposal's base draft version. */
export function useArticleUrlState(draftId: () => string | null) {
  const route = useRoute()
  const router = useRouter()
  let queued = Promise.resolve()
  const pending = shallowReactive<Partial<Record<RecoveryKey, { owner: string; value: string | null }>>>({})
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
  const read = (key: RecoveryKey) => {
    const staged = pending[key]
    if (staged?.owner === draftId()) return staged.value && uuid.test(staged.value) ? staged.value : null
    const value = route?.query[key]
    return route?.query.draft === draftId() && typeof value === 'string' && uuid.test(value) ? value : null
  }
  function enqueue(owner: string | null, change: (query: LocationQueryRaw) => void): Promise<void> {
    const next = queued.then(async () => {
      if (!router || !route || draftId() !== owner) return
      const query: LocationQueryRaw = { ...route.query }
      change(query)
      if (JSON.stringify(query) !== JSON.stringify(route.query)) await router.replace({ query })
    })
    queued = next.catch(() => {})
    return next
  }
  function remember(key: RecoveryKey, id: string | null): Promise<void> {
    const owner = draftId()
    if (!owner) return Promise.resolve()
    // Recovery watchers must see local intent while router.replace is still queued.
    const staged = { owner, value: id }
    pending[key] = staged
    return enqueue(owner, query => {
      query.draft = owner
      if (id) query[key] = id
      else delete query[key]
    }).finally(() => {
      if (pending[key] === staged) delete pending[key]
    })
  }
  watch(draftId, (id, previous) => {
    if (!router || !route || (!id && !previous)) return
    void enqueue(id, query => {
      if (id) query.draft = id
      else delete query.draft
      if (previous && previous !== id) { delete query.studioPlan; delete query.studioProposal; delete query.studioJob }
    })
  })
  return { planId: computed(() => read('studioPlan')), proposalId: computed(() => read('studioProposal')),
    jobId: computed(() => read('studioJob')), remember }
}
