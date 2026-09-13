import { computed, getCurrentScope, onScopeDispose, ref, watch, type Ref } from 'vue'
import { request, GrasslandHttpError } from '../../../composables/grassland-http'
import type { ApplyCanvasPlanResult, CanvasPlanResult, CreateCanvasPlanRequest } from '../../../types/video-canvas'

export interface UseCanvasAgentOptions {
  draftId: Ref<string>
  storyboardId: Ref<string>
  epoch: Ref<number | string>
  onApplied: (result: ApplyCanvasPlanResult) => Promise<void> | void
}
type PlanInput = Omit<CreateCanvasPlanRequest, 'operationId' | 'draftId' | 'storyboardId'>
const POLL_INTERVAL_MS = 2000
const POLL_DEADLINE_MS = 120_000
const FAILURE_LABELS: Record<string, string> = {
  CANVAS_VERSION_CONFLICT: '项目版本已变化，请按新版本重新提问',
  CANVAS_RESOURCE_LOCKED: '项目或任务当前阶段不允许此操作',
  CANVAS_PLAN_EXPIRED: '计划已过期，请重新提出修改要求',
  CANVAS_OPERATION_CONFLICT: '上次操作与当前请求不一致，请恢复原请求',
  CANVAS_MEDIA_UNAVAILABLE: '引用素材已失效，请重新选择',
  CANVAS_AGENT_INVALID_PLAN: '模型返回的计划不合法，请调整要求后重试',
  CANVAS_AGENT_TIMEOUT: '计划生成超时，保留上次请求供查询',
}

/** A request key belongs to one immutable request. Every async landing also belongs to a generation. */
export function useCanvasAgent(options: UseCanvasAgentOptions) {
  const plan = ref<CanvasPlanResult | null>(null)
  const appliedResult = ref<ApplyCanvasPlanResult | null>(null)
  const error = ref('')
  const errorCode = ref('')
  const submitting = ref(false)
  const applying = ref(false)
  const querying = ref(false)
  const applyUnknown = ref(false)
  const pendingRequest = ref<CreateCanvasPlanRequest | null>(null)
  const active = ref(true)
  let generation = 0
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let pollDeadline = 0
  const controllers = new Set<AbortController>()
  const retryWaits = new Set<() => void>()
  const delivered = new Set<string>()
  const capture = () => ({ generation, epoch: options.epoch.value, storyboard: options.storyboardId.value })
  type Ticket = ReturnType<typeof capture>
  const current = (ticket: Ticket) => active.value && ticket.generation === generation
    && ticket.epoch === options.epoch.value && ticket.storyboard === options.storyboardId.value

  function stopPolling(): void {
    if (pollTimer != null) clearTimeout(pollTimer)
    pollTimer = null
  }
  function invalidate(): void {
    generation++; stopPolling()
    controllers.forEach(controller => controller.abort()); controllers.clear()
    retryWaits.forEach(cancel => cancel()); retryWaits.clear()
    submitting.value = false; applying.value = false; querying.value = false
  }
  function reset(): void {
    invalidate(); plan.value = null; appliedResult.value = null; pendingRequest.value = null
    pollDeadline = 0; error.value = ''; errorCode.value = ''; applyUnknown.value = false; delivered.clear()
  }
  function deactivate(): void {
    if (!active.value) return
    if (applying.value) { applyUnknown.value = true; error.value = '应用结果尚未确认，返回后可恢复结果' }
    else if (submitting.value && pendingRequest.value) { error.value = '上次请求结果尚未确认，可重试上次请求'; errorCode.value = 'CANVAS_PENDING_UNKNOWN' }
    active.value = false; invalidate()
  }
  function activate(): void {
    if (active.value) return
    active.value = true
    if (plan.value) void refreshPlan()
  }
  function setFailure(failure: unknown): void {
    const status = (failure as { status?: number }).status
    const code = (failure as { code?: string }).code ?? ''
    const message = failure instanceof Error ? failure.message : '请求失败，请重试'
    if (status === 401 || status === 404) reset()
    errorCode.value = code
    error.value = FAILURE_LABELS[code] || (status === 409 ? message + '，请确认状态后重新提问' : message)
  }

  async function call<T>(url: string, init: RequestInit = {}, timeout = 30_000): Promise<T> {
    const controller = new AbortController(); controllers.add(controller)
    const timer = setTimeout(() => controller.abort(), timeout)
    try { return await request<T>(url, { ...init, signal: controller.signal }) }
    finally { clearTimeout(timer); controllers.delete(controller) }
  }
  function delay(ms: number, ticket: Ticket): Promise<boolean> {
    return new Promise(resolve => {
      const cancel = () => { clearTimeout(timer); retryWaits.delete(cancel); resolve(false) }
      const timer = setTimeout(() => { retryWaits.delete(cancel); resolve(current(ticket)) }, ms)
      retryWaits.add(cancel)
    })
  }
  function accept(result: CanvasPlanResult, ticket: Ticket): boolean {
    if (!current(ticket)) return false
    if (!result?.id || result.storyboardId !== ticket.storyboard) throw new Error('计划响应与当前项目不匹配')
    plan.value = result
    stopPolling()
    if (result.status === 'preparing') {
      if (!pollDeadline) pollDeadline = Date.now() + POLL_DEADLINE_MS
      schedulePoll(result.id, ticket)
    } else {
      pendingRequest.value = null
      if (result.status === 'failed' || result.status === 'expired') {
        errorCode.value = result.errorCode || (result.status === 'expired' ? 'CANVAS_PLAN_EXPIRED' : '')
        error.value = FAILURE_LABELS[errorCode.value] || '计划生成失败，请调整要求后重试'
      }
    }
    return true
  }

  async function sendPending(): Promise<boolean> {
    if (!active.value || submitting.value || !pendingRequest.value) return false
    const ticket = capture()
    const body = JSON.stringify(pendingRequest.value)
    submitting.value = true; error.value = ''; errorCode.value = ''
    try {
      const result = await call<CanvasPlanResult>('/api/creation-assistant/canvas/plans', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body,
      }, 120_000)
      return accept(result, ticket)
    } catch (failure) {
      if (current(ticket)) {
        setFailure(failure)
        const status = (failure as { status?: number }).status
        // These responses conclusively rejected this request before accepting a paid plan.
        if (status != null && status >= 400 && status < 500 && status !== 408) pendingRequest.value = null
      }
      return false
    } finally { if (current(ticket)) submitting.value = false }
  }

  async function submit(input: PlanInput): Promise<boolean> {
    if (!active.value || submitting.value) return false
    if (pendingRequest.value) {
      const { selectedNodeIds, instruction, expectedEditVersion, expectedCanvasRevision } = pendingRequest.value
      if (JSON.stringify({ selectedNodeIds, instruction, expectedEditVersion, expectedCanvasRevision })
        !== JSON.stringify({ selectedNodeIds: input.selectedNodeIds, instruction: input.instruction,
          expectedEditVersion: input.expectedEditVersion, expectedCanvasRevision: input.expectedCanvasRevision })) {
        error.value = '上次请求的结果尚未确认，请先查询或重试上次请求；当前修改要求会保留'
        errorCode.value = 'CANVAS_PENDING_UNKNOWN'; return false
      }
      return sendPending()
    }
    pendingRequest.value = { operationId: crypto.randomUUID(), draftId: options.draftId.value,
      storyboardId: options.storyboardId.value, selectedNodeIds: [...input.selectedNodeIds], instruction: input.instruction,
      expectedEditVersion: input.expectedEditVersion, expectedCanvasRevision: input.expectedCanvasRevision }
    pollDeadline = 0; appliedResult.value = null; applyUnknown.value = false
    return sendPending()
  }

  async function retryPending(input?: PlanInput): Promise<boolean> {
    if (pendingRequest.value) return sendPending()
    return input ? submit(input) : false
  }

  function schedulePoll(planId: string, ticket: Ticket): void {
    stopPolling()
    if (!current(ticket)) return
    pollTimer = setTimeout(() => {
      pollTimer = null
      if (!current(ticket) || plan.value?.id !== planId) return
      if (Date.now() >= pollDeadline) {
        errorCode.value = 'CANVAS_AGENT_TIMEOUT'; error.value = FAILURE_LABELS.CANVAS_AGENT_TIMEOUT!; return
      }
      void refreshPlan()
    }, POLL_INTERVAL_MS)
  }

  async function refreshPlan(): Promise<boolean> {
    if (!active.value || !plan.value || querying.value) return false
    const ticket = capture(); const id = plan.value.id
    querying.value = true; error.value = ''; errorCode.value = ''
    try {
      for (let attempt = 0; attempt <= 2; attempt++) {
        try {
          const result = await call<CanvasPlanResult>('/api/creation-assistant/canvas/plans/' + encodeURIComponent(id))
          if (!current(ticket) || plan.value?.id !== id) return false
          return accept(result, ticket)
        } catch (failure) {
          if (!current(ticket) || plan.value?.id !== id) return false
          const status = (failure as { status?: number }).status
          if (attempt === 2 || (status != null && status < 500) || !(await delay((attempt + 1) * 1000, ticket))) throw failure
        }
      }
      return false
    } catch (failure) { if (current(ticket)) setFailure(failure); return false }
    finally { if (current(ticket)) querying.value = false }
  }

  async function apply(recover = false): Promise<boolean> {
    if (!active.value || !plan.value || applying.value || (plan.value.status !== 'ready'
      && !(recover && applyUnknown.value && plan.value.status === 'applied'))) return false
    const ticket = capture(); const original = plan.value; const id = original.id
    if (!recover && Date.parse(original.expiresAt) <= Date.now()) {
      errorCode.value = 'CANVAS_PLAN_EXPIRED'; error.value = FAILURE_LABELS.CANVAS_PLAN_EXPIRED!; return false
    }
    applying.value = true; error.value = ''; errorCode.value = ''
    try {
      const result = await call<ApplyCanvasPlanResult>('/api/creation-assistant/canvas/plans/' + encodeURIComponent(id) + '/apply', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
      })
      if (!current(ticket) || plan.value?.id !== id) return false
      if (result.planId !== id) throw new GrasslandHttpError(502, '应用结果与当前计划不匹配')
      appliedResult.value = result; applyUnknown.value = false; plan.value = { ...original, status: 'applied' }; stopPolling()
      if (!delivered.has(id)) { delivered.add(id); await options.onApplied(result) }
      return true
    } catch (failure) {
      if (current(ticket)) {
        const status = (failure as { status?: number }).status
        if (status == null || status >= 500 || status === 408) applyUnknown.value = true
        setFailure(failure)
      }
      return false
    }
    finally { if (current(ticket)) applying.value = false }
  }

  watch(options.epoch, reset, { flush: 'sync' })
  if (getCurrentScope()) onScopeDispose(deactivate)
  return { plan, appliedResult, error, errorCode, submitting, applying, querying, pendingRequest,
    canRetryPending: computed(() => !!pendingRequest.value), active, submit, retryPending, apply, refreshPlan,
    applyUnknown, recoverApply: () => apply(true), reset, stopPolling, deactivate, activate }
}
