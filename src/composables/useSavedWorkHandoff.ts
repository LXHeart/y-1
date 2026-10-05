import { onScopeDispose, ref, watch } from 'vue'
import { useAccountSessionStore } from '../stores/account-session'
import { fetchApi } from './grassland-http'
import { workspaceOf } from '../lib/creation-workspace'
import type { CreationProject } from '../types/creation'

/** Server-saved revisions only; retries reuse an id and stale account replies cannot navigate. */
export function useSavedWorkHandoff(accept: (project: CreationProject) => void) {
  const session = useAccountSessionStore()
  const pendingId = ref('')
  const error = ref('')
  const requests = new Map<string, string>()
  let active = true
  watch(() => session.epoch, () => {
    requests.clear()
    pendingId.value = ''
    error.value = ''
  }, { flush: 'sync' })
  onScopeDispose(() => { active = false })
  async function create(source: CreationProject, target: 'video' | 'article') {
    if (pendingId.value) return
    const ticket = session.capture()
    if (!ticket.accountId) { error.value = '请登录后交接作品'; return }
    const key = `${source.id}:${source.version}:${target}`
    const requestId = requests.get(key) ?? crypto.randomUUID()
    requests.set(key, requestId)
    pendingId.value = source.id
    error.value = ''
    try {
      const response = await fetchApi(`/api/creation-drafts/${encodeURIComponent(source.id)}/handoffs`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ requestId, version: source.version, target }),
      })
      const body = await response.json()
      if (!response.ok || !body.success || !body.data?.id) throw new Error(body.error || '交接失败，请重试')
      if (!active || !session.isCurrent(ticket)) return
      const project = body.data as CreationProject
      accept({ ...project, capability: workspaceOf(project).capability ?? target })
    } catch (cause) {
      if (active && session.isCurrent(ticket)) error.value = cause instanceof Error ? cause.message : '交接失败，请重试'
    } finally {
      if (active && session.isCurrent(ticket)) pendingId.value = ''
    }
  }
  return { pendingId, error, create }
}
