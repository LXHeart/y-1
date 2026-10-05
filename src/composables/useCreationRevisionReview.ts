import { computed, getCurrentInstance, onScopeDispose, ref, watch, type Ref } from 'vue'
import { getActivePinia } from 'pinia'
import { useAccountSessionStore } from '../stores/account-session'
import type { CreationBrief } from '../types/creation'

/** 内存内的原稿快照。只比较字符，不声称自动验证事实正确。 */
export function useCreationRevisionReview(text: Ref<string>, scope: () => unknown, busy: () => boolean = () => false, brief: () => CreationBrief | null = () => null) {
  const account = getCurrentInstance() && getActivePinia() ? useAccountSessionStore() : null
  const originalBrief = ref<CreationBrief | null>(null)
  const original = ref<string | null>(null)
  const changed = computed(() => original.value !== null && original.value !== text.value)
  watch(() => [scope(), account?.epoch], () => { original.value = null; originalBrief.value = null }, { flush: 'sync' })
  watch([text, busy, scope], ([value, pending], previous) => {
    if (pending) return
    if (original.value === null || previous?.[1]) {
      original.value = value || null
      originalBrief.value = brief() ? JSON.parse(JSON.stringify(brief())) : null
    }
  }, { immediate: true, flush: 'post' })
  function restore() { if (original.value !== null) text.value = original.value }
  function keep() { original.value = text.value }
  onScopeDispose(() => { original.value = null })
  return { original, originalBrief, changed, restore, keep }
}
export function revisionDiff(original: string, edited: string) {
  let start = 0, end = 0
  while (start < original.length && start < edited.length && original[start] === edited[start]) start++
  while (end < original.length - start && end < edited.length - start && original[original.length - end - 1] === edited[edited.length - end - 1]) end++
  return { prefix: original.slice(0, start), removed: original.slice(start, original.length - end), added: edited.slice(start, edited.length - end), suffix: end ? original.slice(-end) : '' }
}
export function reviewFacts(brief: CreationBrief | null | undefined) {
  return [brief?.confirmedExperience, ...(brief?.facts ?? []).map(fact => fact.statement)].filter((value): value is string => !!value)
}
