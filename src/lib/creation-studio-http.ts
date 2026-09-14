import { getCurrentInstance, getCurrentScope, onActivated, onDeactivated, onScopeDispose, ref, watch } from 'vue'
import { fetchApi } from '../composables/grassland-http'
import { useAccountSessionStore } from '../stores/account-session'

/** Studio writes are never retried automatically. Callers retain the complete intent and UUID. */
export class StudioHttpError extends Error {
  constructor(message: string, readonly status = 0, readonly code = '') { super(message) }
}

export async function studioRequest<T>(url: string, init: RequestInit = {}, timeoutMs = 30_000): Promise<T> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetchApi(url, { ...init, signal: controller.signal })
    const body = await response.json().catch(() => null) as
      { success?: boolean; data?: T; error?: string; code?: string } | null
    if (!response.ok || !body?.success || body.data == null) {
      throw new StudioHttpError(body?.error || '请求未能完成，请重试', response.status, body?.code)
    }
    return body.data
  } catch (error) {
    if (error instanceof StudioHttpError) throw error
    throw new StudioHttpError('网络异常，结果尚未确认；请恢复原请求或刷新状态')
  } finally { clearTimeout(timer) }
}

export function studioPost<T>(url: string, body: unknown, timeoutMs = 30_000): Promise<T> {
  return studioRequest<T>(url, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body) }, timeoutMs)
}

export function studioErrorMessage(error: unknown): string {
  return error instanceof StudioHttpError ? error.message : '请求未能完成，请重试'
}

/** Visibility and KeepAlive are independent: returning to a tab must not reactivate a cached page. */
export function useStudioActivity(onPause: () => void, onResume: () => void) {
  const mounted = Boolean(getCurrentInstance())
  let hidden = mounted && typeof document !== 'undefined' && document.hidden
  let deactivated = false
  let disposed = false
  let epoch = 0
  const waiters = new Set<(active: boolean) => void>()
  const isActive = () => !hidden && !deactivated && !disposed
  function settle(active: boolean): void { waiters.forEach(resolve => resolve(active)); waiters.clear() }
  function change(update: () => void): void {
    const wasActive = isActive()
    update()
    if (wasActive && !isActive()) { epoch += 1; onPause() }
    else if (!wasActive && isActive()) { settle(true); onResume() }
  }
  if (mounted) {
    onActivated(() => change(() => { deactivated = false }))
    onDeactivated(() => change(() => { deactivated = true }))
    if (typeof document !== 'undefined') {
      const visibility = () => change(() => { hidden = document.hidden })
      document.addEventListener('visibilitychange', visibility)
      onScopeDispose(() => document.removeEventListener('visibilitychange', visibility))
    }
  }
  if (getCurrentScope()) onScopeDispose(() => {
    disposed = true; epoch += 1; onPause(); settle(false)
  })
  return {
    isActive,
    capture: () => { const ticket = epoch; return () => isActive() && ticket === epoch },
    // Keep accepted write results until reactivation; never submit them again to recover the UI.
    whenActive: () => disposed ? Promise.resolve(false) : isActive() ? Promise.resolve(true)
      : new Promise<boolean>(resolve => { waiters.add(resolve) }),
  }
}

/** A -> B -> A, draft switches and scope disposal all invalidate old success AND error responses. */
export function useStudioGuard(context?: () => unknown) {
  const pinia = getCurrentInstance()?.appContext.config.globalProperties.$pinia
  const account = pinia ? useAccountSessionStore(pinia) : null
  const epoch = ref(0)
  let disposed = false
  const listeners: Array<() => void> = []
  function invalidate(): void {
    epoch.value += 1
    listeners.forEach(listener => listener())
  }
  if (account) watch(() => account.epoch, invalidate, { flush: 'sync' })
  if (context) watch(context, invalidate, { flush: 'sync' })
  if (getCurrentScope()) onScopeDispose(() => { disposed = true; invalidate() })
  return {
    epoch,
    invalidate,
    onInvalidate: (listener: () => void) => { listeners.push(listener) },
    capture: () => {
      const revision = epoch.value
      const ticket = account?.capture()
      return () => !disposed && epoch.value === revision && (!ticket || account!.isCurrent(ticket))
    },
  }
}
