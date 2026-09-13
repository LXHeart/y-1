import { computed, getCurrentInstance, nextTick, onActivated, onDeactivated, onMounted, onScopeDispose, ref, watch } from 'vue'
import type { Ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { normalizeCanvasRoute, type CanvasRouteKey } from '../useVideoCanvasUrlState'

export interface CanvasProjectIdentity {
  accountEpoch: number
  draftId: string
  storyboardId: string
  generation: number
}
interface ProjectConsumer { activate: () => void; deactivate: () => void; reset: () => void }

interface Options {
  key: Ref<CanvasRouteKey | null>
  accountEpoch: () => number
  authenticated: () => boolean
  boundDraftId: () => string
  bind: (key: CanvasRouteKey) => Promise<boolean>
  load: (storyboardId: string) => Promise<void>
  syncDraft: (draftId: string) => void
  reset: () => void
  suspend?: () => void
  resume?: () => void
  afterLoad?: () => void
  queues: Array<() => Promise<boolean>>
  hasPending: () => boolean
  focusError?: () => void
  onKeydown?: (event: KeyboardEvent) => void
}

/** One lifetime for route identity, binding, reads and the four ordered save queues. */
export function useCanvasProjectSession(options: Options) {
  const generation = ref(0)
  const active = ref(true)
  const saveError = ref('')
  const identity = ref<CanvasProjectIdentity | null>(null)
  let loading: Promise<void> | null = null
  let flushing: Promise<boolean> | null = null
  const consumers = new Set<ProjectConsumer>()
  function registerConsumer(consumer: ProjectConsumer): () => void {
    consumers.add(consumer)
    if (active.value) consumer.activate()
    return () => { consumer.deactivate(); consumers.delete(consumer) }
  }

  function capture(): CanvasProjectIdentity | null {
    return identity.value ? { ...identity.value } : null
  }
  function isCurrent(ticket: CanvasProjectIdentity | null): boolean {
    return !!ticket && active.value && ticket.generation === generation.value
      && ticket.accountEpoch === options.accountEpoch()
      && ticket.storyboardId === options.key.value?.storyboard
  }
  function isSameProject(key: CanvasRouteKey | null): boolean {
    const current = identity.value
    return !!key && !!current && current.accountEpoch === options.accountEpoch()
      && key.storyboard === current.storyboardId
      && (key.draft ?? options.boundDraftId()) === current.draftId
  }

  async function ensure(): Promise<void> {
    if (!active.value || !options.authenticated()) return
    const key = options.key.value
    if (!key) return
    if (isSameProject(key) && loading) return loading
    if (!isSameProject(key)) {
      generation.value += 1
      consumers.forEach(consumer => consumer.reset())
      options.reset()
      identity.value = { accountEpoch: options.accountEpoch(), storyboardId: key.storyboard,
        draftId: key.draft ?? '', generation: generation.value }
    }
    const ticket = capture()
    const run = (async () => {
      if (!(await options.bind(key)) || !isCurrent(ticket)) return
      if (identity.value) identity.value.draftId = options.boundDraftId()
      options.syncDraft(options.boundDraftId())
      await options.load(key.storyboard)
      if (isCurrent(ticket)) options.afterLoad?.()
    })()
    loading = run
    try { await run } finally { if (loading === run) loading = null }
  }

  function flushBeforeLeave(): Promise<boolean> {
    if (flushing) return flushing
    const ticket = capture()
    const run = (async () => {
      saveError.value = ''
      try {
        for (const flush of options.queues) {
          if (!(await flush())) throw new Error('修改尚未保存，请处理错误后重试')
          if (ticket && !isCurrent(ticket)) return false
        }
        return true
      } catch (error) {
        if (!ticket || isCurrent(ticket)) {
          saveError.value = error instanceof Error ? error.message : '保存失败，请重试'
          await nextTick()
          options.focusError?.()
        }
        return false
      }
    })()
    flushing = run
    void run.finally(() => { if (flushing === run) flushing = null })
    return run
  }

  function beforeUnload(event: BeforeUnloadEvent): void {
    if (!options.hasPending()) return
    event.preventDefault()
    event.returnValue = ''
  }
  function installListeners(): void {
    if (options.onKeydown) window.addEventListener('keydown', options.onKeydown)
    window.addEventListener('beforeunload', beforeUnload)
  }
  function removeListeners(): void {
    if (options.onKeydown) window.removeEventListener('keydown', options.onKeydown)
    window.removeEventListener('beforeunload', beforeUnload)
  }
  function deactivate(): void {
    if (!active.value) return
    active.value = false
    generation.value += 1
    loading = null
    removeListeners()
    consumers.forEach(consumer => consumer.deactivate())
    options.suspend?.()
  }
  function activate(): void {
    if (!active.value) {
      active.value = true
      if (identity.value) identity.value.generation = generation.value
      options.resume?.()
      consumers.forEach(consumer => consumer.activate())
      void ensure()
    }
    installListeners()
  }
  function resetForAccount(): void {
    generation.value += 1
    identity.value = null
    loading = null
    flushing = null
    saveError.value = ''
    consumers.forEach(consumer => consumer.reset())
    options.reset()
    void ensure()
  }

  watch(() => options.accountEpoch(), resetForAccount, { flush: 'sync' })
  watch(options.key, key => {
    if (!isSameProject(key)) void ensure()
  }, { flush: 'sync', immediate: true })

  if (getCurrentInstance()) {
    onBeforeRouteLeave(() => flushBeforeLeave())
    onBeforeRouteUpdate(to => isSameProject(normalizeCanvasRoute(to)) ? true : flushBeforeLeave())
    onMounted(activate)
    onActivated(activate)
    onDeactivated(deactivate)
  }
  onScopeDispose(deactivate)
  return { identity: computed(() => identity.value), generation, active, saveError,
    capture, isCurrent, ensure, activate, deactivate, resetForAccount, flushBeforeLeave, registerConsumer }
}
