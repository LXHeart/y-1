import { computed, getCurrentScope, onScopeDispose, ref, shallowRef, watch } from 'vue'
import { GrasslandHttpError, request } from '../../../composables/grassland-http'
import type { AccountSessionPort } from '../../../stores/account-session'

/**
 * 数字人治理台状态源（任务书 #105G C105G-04 / 共享契约 K10 ADMIN01～06）。
 *
 * - 轮询：仅面板 active ∧ 页面可见 ∧ 非匿名账号时存在；每轮 GET config/sessions/invocations
 *   三读并发（GET 无自动副作用），**前一轮完成后**再排 5,000ms 单次 setTimeout（无 setInterval，
 *   不重叠）。轮次失败保留既有数据、显示错误并撤销续排（#106 F07 同语义）——手动刷新、
 *   重新激活或页面恢复可见时立即重查。
 * - 写入闸（迟到回包不露旧数据）：代次（generation）+ 账号票据（capture/isCurrent）双查后才
 *   写 state；失活/换号 → 代次+1、abort 在途只读、清 timer 与全部状态。
 * - 版本冲突（tc105g_04_02）：PUT 409 dh_version_conflict 不抛散——draft（表单可编辑副本）原样
 *   保留、置 conflict 提示重新加载；服务端真相由轮询/refresh 更新，draft 不被静默覆盖，
 *   仅显式 reloadDraft() 才重抄。
 * - 动作锁：terminate/reconcile 同对象在途不重入（重复点击只发一次请求）；成功后以服务端
 *   回读为准（refresh），不 optimistic 地把 unknown 显示为已结算。
 * - requestId：同一次确认动作的重试复用同键（K01 幂等），成功/换目标后重置。
 */

const POLL_INTERVAL_MS = 5000

export type DhSessionState =
  'preparing' | 'queued' | 'connecting' | 'ready' | 'listening' | 'responding'
  | 'paused' | 'reconnecting' | 'ending' | 'ended' | 'failed'

export type DhInvocationState =
  'reserved' | 'preparing' | 'prepared' | 'dispatched' | 'succeeded' | 'failed'
  | 'cancelled' | 'unknown'

export type DhSettlementState = 'not_required' | 'pending' | 'settled' | 'failed'

export interface DhStateToggle {
  id: string
  enabled: boolean
}

/** ADMIN01 AdminConfig（K10 camelCase；dh_catalog 投影）。 */
export interface DhAdminConfig {
  version: number
  enabled: boolean
  newSessionsAllowed: boolean
  recordingEnabled: boolean
  customAvatarEnabled: boolean
  maxSessionsGlobal: number
  maxQueuedGlobal: number
  allowedBackendIds: string[]
  presetAvatarStates: DhStateToggle[]
  voiceStates: DhStateToggle[]
  billingNoticeVersion: string
}

/** ADMIN03 AdminSessionView：仅脱敏元数据（无正文/转写内容）。 */
export interface DhAdminSessionRow {
  id: string
  profileId: string
  profileNameAtCreation: string
  state: DhSessionState
  createdAt: string
  endedAt: string | null
  hasSavedTranscript: boolean
  recordingCount: number
  savedAssetCount: number
  billing: {
    confirmedCents: number
    platformCostCents: number
    subsidizedCents: number
    pendingCount: number
    priceTableVersion: number
  }
  workerId: string | null
  errorCode: string | null
  cleanupPending: boolean
  phaseMetrics: {
    phase: string
    lastDurationMs: number | null
    p50Ms: number | null
    p95Ms: number | null
    sampleCount: number
  }[]
}

/** K08 UsageUnits：未知用量 = null + quality pending，不得填 0 冒充。 */
export interface DhUsageUnits {
  inputTokens: number | null
  outputTokens: number | null
  audioInputMs: number | null
  audioOutputMs: number | null
  textCodePoints: number | null
  renderMs: number | null
  providerRequestId: string | null
  quality: 'confirmed' | 'pending'
}

/** ADMIN05/06 InvocationSummary：不含 inputHash/输入/密钥；confirmedCents 为原 run 实际消耗。 */
export interface DhInvocationRow {
  id: string
  sessionId: string | null
  stage: 'stt' | 'llm' | 'tts' | 'preview'
  state: DhInvocationState
  settlementState: DhSettlementState
  version: number
  providerModelLabel: string
  createdAt: string
  deadlineAt: string | null
  usage: DhUsageUnits | null
  confirmedCents: number | null
  errorCode: string | null
}

/** 表单可编辑副本（allowedBackendIds 以逗号分隔文本编辑；reason 为本次变更原因）。 */
export interface DhAdminConfigDraft {
  enabled: boolean
  newSessionsAllowed: boolean
  recordingEnabled: boolean
  customAvatarEnabled: boolean
  maxSessionsGlobal: number
  maxQueuedGlobal: number
  allowedBackendIds: string
  presetAvatarStates: DhStateToggle[]
  voiceStates: DhStateToggle[]
  billingNoticeVersion: string
  reason: string
}

export interface DhReconcileForm {
  outcome: 'succeeded' | 'failed'
  providerEvidenceRef: string
  /** succeeded 时至少一项实量非空且全非负；failed 必须为 null（K10）。 */
  confirmedUsage: {
    inputTokens: number | null
    outputTokens: number | null
    audioInputMs: number | null
    audioOutputMs: number | null
    textCodePoints: number | null
    renderMs: number | null
  } | null
  reason: string
}

export interface DhPage<T> {
  items: T[]
  nextCursor: string | null
}

const TERMINAL_SESSION_STATES: DhSessionState[] = ['ended', 'failed']

/** succeeded 用量门禁：至少一项实量非空且全非负（K08：未知是 null 不是 0）。 */
export function isUsageComplete(usage: NonNullable<DhReconcileForm['confirmedUsage']>): boolean {
  const values = [usage.inputTokens, usage.outputTokens, usage.audioInputMs, usage.audioOutputMs,
    usage.textCodePoints, usage.renderMs]
  return values.some((value) => value != null) && values.every((value) => value == null || value >= 0)
}

/**
 * 核对证据门禁（K10 镜像；后端仍强校验——客户端禁用不替代服务端约束）：
 * 第三方证据与原因必填；failed 不得携带用量；succeeded 用量至少一项实量且全非负。
 */
export function isReconcileFormSubmittable(form: DhReconcileForm): boolean {
  if (!form.providerEvidenceRef.trim() || !form.reason.trim()) return false
  if (form.outcome === 'failed') return form.confirmedUsage == null
  return form.confirmedUsage != null && isUsageComplete(form.confirmedUsage)
}

function cloneConfigDraft(config: DhAdminConfig): DhAdminConfigDraft {
  return {
    enabled: config.enabled,
    newSessionsAllowed: config.newSessionsAllowed,
    recordingEnabled: config.recordingEnabled,
    customAvatarEnabled: config.customAvatarEnabled,
    maxSessionsGlobal: config.maxSessionsGlobal,
    maxQueuedGlobal: config.maxQueuedGlobal,
    allowedBackendIds: config.allowedBackendIds.join(', '),
    presetAvatarStates: config.presetAvatarStates.map((state) => ({ ...state })),
    voiceStates: config.voiceStates.map((state) => ({ ...state })),
    billingNoticeVersion: config.billingNoticeVersion,
    reason: '',
  }
}

function isAbort(caught: unknown): boolean {
  return caught instanceof Error && caught.name === 'AbortError'
}

export function useDigitalHumanAdmin(account: AccountSessionPort) {
  // ---- 服务端投影（轮询回写；迟到回包经代次+票据双查） ----
  const config = shallowRef<DhAdminConfig | null>(null)
  const sessions = shallowRef<DhAdminSessionRow[]>([])
  const invocations = shallowRef<DhInvocationRow[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  // ---- 表单与动作状态 ----
  const configDraft = ref<DhAdminConfigDraft | null>(null)
  const draftBaseVersion = shallowRef<number | null>(null)
  const updating = ref(false)
  const updateConflict = ref<string | null>(null)
  const updateError = ref<string | null>(null)
  const updateNotice = ref<string | null>(null)
  const terminating = shallowRef<string | null>(null)
  const terminateError = ref<string | null>(null)
  const terminateNotice = ref<string | null>(null)
  const reconciling = shallowRef<string | null>(null)
  const reconcileError = ref<string | null>(null)
  const lastReconciled = shallowRef<DhInvocationRow | null>(null)

  /** 待核对数量（unknown 队列；badge/会话区提示共用）。 */
  const pendingReconcileCount = computed(() => invocations.value.filter((row) => row.state === 'unknown').length)

  let disposed = false
  let generation = 0
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let pollController: AbortController | null = null
  let polling = false
  let active = false
  let documentVisible = typeof document === 'undefined' ? true : !document.hidden
  let updateRequestId: string | null = null
  const terminateRequestIds = new Map<string, string>()
  const reconcileRequestIds = new Map<string, string>()

  function operational(): boolean {
    return !disposed && active && documentVisible
  }

  function clearTimer(): void {
    if (pollTimer) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  /** 失活/换号：代次+1、abort 在途只读、清 timer；clearData 时状态全清（不露旧账号数据）。 */
  function pause(clearData: boolean): void {
    generation += 1
    pollController?.abort()
    pollController = null
    clearTimer()
    polling = false
    loading.value = false
    if (clearData) {
      config.value = null
      configDraft.value = null
      draftBaseVersion.value = null
      sessions.value = []
      invocations.value = []
      lastReconciled.value = null
      error.value = null
      updateConflict.value = null
      updateError.value = null
      updateNotice.value = null
      terminateError.value = null
      terminateNotice.value = null
      reconcileError.value = null
    }
  }

  /** 轮询回写闸：代次 + 票据 + 仍处活动可见（隐藏/失活期间迟到回包不写）。 */
  function canApply(run: number, ticket: ReturnType<AccountSessionPort['capture']>): boolean {
    if (disposed || run !== generation || !account.isCurrent(ticket)) return false
    return operational()
  }

  /** 动作（PUT/POST）回写闸：只查账号票据——写结果属于已提交动作，隐藏不吞通知。 */
  function isTicketCurrent(ticket: ReturnType<AccountSessionPort['capture']>): boolean {
    return !disposed && account.isCurrent(ticket)
  }

  /** 每轮三读并发；前一轮完成后才续排下一个 5s timer（串行，不重叠）。 */
  async function poll(): Promise<void> {
    if (disposed || polling || !operational()) return
    const ticket = account.capture()
    if (ticket.accountId == null) return // 匿名不请求（治理面在登录后）
    const run = generation
    polling = true
    loading.value = true
    error.value = null
    const controller = new AbortController()
    pollController = controller
    clearTimer()
    try {
      const [nextConfig, nextSessions, nextInvocations] = await Promise.all([
        request<DhAdminConfig>('/api/admin/digital-human/config', { signal: controller.signal }),
        request<DhPage<DhAdminSessionRow>>('/api/admin/digital-human/sessions?limit=20', { signal: controller.signal }),
        request<DhPage<DhInvocationRow>>('/api/admin/digital-human/invocations?limit=20', { signal: controller.signal }),
      ])
      if (!canApply(run, ticket)) return
      config.value = nextConfig
      if (configDraft.value == null || draftBaseVersion.value == null) {
        // 首次到达才初始化表单；此后 draft 只在显式 reloadDraft() 时重抄（防静默覆盖）。
        configDraft.value = cloneConfigDraft(nextConfig)
        draftBaseVersion.value = nextConfig.version
      }
      sessions.value = nextSessions.items
      invocations.value = nextInvocations.items
    } catch (caught: unknown) {
      // F07 同语义：失败保留既有数据、显示错误并停止自动轮询（手动刷新/重新激活恢复）。
      if (canApply(run, ticket) && !isAbort(caught)) {
        error.value = caught instanceof Error && caught.message ? caught.message : '数字人治理数据读取失败'
      }
      return
    } finally {
      if (run === generation) {
        polling = false
        loading.value = false
        pollController = null
      }
    }
    scheduleNext(run)
  }

  function scheduleNext(run: number): void {
    clearTimer()
    if (run !== generation || !operational()) return
    pollTimer = setTimeout(() => {
      pollTimer = null
      void poll()
    }, POLL_INTERVAL_MS)
  }

  /** 手动/动作后刷新（立即重查一轮；成功后自动续排）。 */
  async function refresh(): Promise<void> {
    await poll()
  }

  // ---- ADMIN02：配置更新（版本冲突保表单） ----

  function reloadDraft(): void {
    if (config.value != null) {
      configDraft.value = cloneConfigDraft(config.value)
      draftBaseVersion.value = config.value.version
      updateConflict.value = null
      updateError.value = null
      updateRequestId = null
    }
  }

  async function updateConfig(): Promise<boolean> {
    const draft = configDraft.value
    const base = draftBaseVersion.value
    if (draft == null || base == null || updating.value) return false
    if (!draft.reason.trim()) {
      updateError.value = '请填写变更原因（审计必填）。'
      return false
    }
    updating.value = true
    updateError.value = null
    updateConflict.value = null
    updateNotice.value = null
    const ticket = account.capture()
    // 同一草稿的重试复用同 requestId（K01 幂等）；保存成功或重抄后重置。
    const requestId = updateRequestId ?? crypto.randomUUID()
    updateRequestId = requestId
    try {
      const saved = await request<DhAdminConfig>('/api/admin/digital-human/config', {
        method: 'PUT',
        body: JSON.stringify({
          enabled: draft.enabled,
          newSessionsAllowed: draft.newSessionsAllowed,
          recordingEnabled: draft.recordingEnabled,
          customAvatarEnabled: draft.customAvatarEnabled,
          maxSessionsGlobal: draft.maxSessionsGlobal,
          maxQueuedGlobal: draft.maxQueuedGlobal,
          allowedBackendIds: draft.allowedBackendIds.split(',').map((id) => id.trim()).filter(Boolean),
          presetAvatarStates: draft.presetAvatarStates,
          voiceStates: draft.voiceStates,
          billingNoticeVersion: draft.billingNoticeVersion,
          expectedVersion: base,
          requestId,
          reason: draft.reason.trim(),
        }),
      })
      if (!isTicketCurrent(ticket)) return false
      config.value = saved
      draftBaseVersion.value = saved.version
      updateRequestId = null
      updateNotice.value = `已保存，当前版本 ${saved.version}。`
      void refresh() // 重新读真实状态（配置生效面以服务端为准）
      return true
    } catch (caught: unknown) {
      if (!isTicketCurrent(ticket)) return false
      if (caught instanceof GrasslandHttpError && caught.status === 409
        && caught.code === 'dh_version_conflict') {
        // 表单（draft）原样保留；提示重新加载，无静默覆盖。服务端真相由轮询更新。
        updateConflict.value = `${caught.message} 表单已保留，请核对最新配置后重新加载再提交。`
      } else {
        updateError.value = caught instanceof Error && caught.message ? caught.message : '配置保存失败。'
      }
      return false
    } finally {
      if (isTicketCurrent(ticket)) updating.value = false
    }
  }

  // ---- ADMIN04：紧急终止（显式确认在组件层；此处提交锁+服务端终态回读） ----

  async function terminateSession(sessionId: string, reason: string): Promise<boolean> {
    if (terminating.value != null || disposed) return false
    if (!reason.trim()) {
      terminateError.value = '请填写终止原因（审计必填）。'
      return false
    }
    terminating.value = sessionId
    terminateError.value = null
    terminateNotice.value = null
    const ticket = account.capture()
    const requestId = terminateRequestIds.get(sessionId) ?? crypto.randomUUID()
    terminateRequestIds.set(sessionId, requestId)
    try {
      const ended = await request<{ state: DhSessionState }>(
        `/api/admin/digital-human/sessions/${sessionId}/terminate`, {
          method: 'POST',
          body: JSON.stringify({ requestId, reason: reason.trim() }),
        })
      if (!isTicketCurrent(ticket)) return false
      // 仅服务端终态显示完成（202/ending 等中间态不冒充成功）。
      terminateNotice.value = TERMINAL_SESSION_STATES.includes(ended.state)
        ? '会话已终止（服务端已确认终态）。'
        : '终止指令已受理，等待服务端回收确认（列表随后自动刷新）。'
      terminateRequestIds.delete(sessionId)
      await refresh() // 成功重新读真实状态，不 optimistic
      return true
    } catch (caught: unknown) {
      if (isTicketCurrent(ticket)) {
        terminateError.value = caught instanceof Error && caught.message ? caught.message : '终止请求失败。'
      }
      return false
    } finally {
      if (isTicketCurrent(ticket)) terminating.value = null
    }
  }

  // ---- ADMIN06：unknown 人工核对（证据齐全才可提交；金额不可改） ----

  async function reconcile(invocationId: string, expectedVersion: number, form: DhReconcileForm): Promise<boolean> {
    if (reconciling.value != null || disposed) return false
    if (!isReconcileFormSubmittable(form)) {
      reconcileError.value = '请补齐第三方证据与确认用量（failed 时不能携带用量）。'
      return false
    }
    reconciling.value = invocationId
    reconcileError.value = null
    lastReconciled.value = null
    const ticket = account.capture()
    const requestId = reconcileRequestIds.get(invocationId) ?? crypto.randomUUID()
    reconcileRequestIds.set(invocationId, requestId)
    try {
      const summary = await request<DhInvocationRow>(
        `/api/admin/digital-human/invocations/${invocationId}/reconcile`, {
          method: 'POST',
          body: JSON.stringify({
            requestId,
            expectedVersion,
            outcome: form.outcome,
            providerEvidenceRef: form.providerEvidenceRef.trim(),
            confirmedUsage: form.confirmedUsage == null ? null : {
              ...form.confirmedUsage,
              providerRequestId: null,
              quality: 'confirmed',
            },
            reason: form.reason.trim(),
          }),
        })
      if (!isTicketCurrent(ticket)) return false
      // 成功以服务端回读为准（真实 state/settlementState），不 optimistic 地把 unknown 变已结算。
      lastReconciled.value = summary
      reconcileRequestIds.delete(invocationId)
      await refresh()
      return true
    } catch (caught: unknown) {
      if (isTicketCurrent(ticket)) {
        reconcileError.value = caught instanceof Error && caught.message ? caught.message : '核对请求失败。'
      }
      return false
    } finally {
      if (isTicketCurrent(ticket)) reconciling.value = null
    }
  }

  // ---- 生命周期（面板 onMounted/onActivated ↔ onDeactivated/onUnmounted；测试可直调） ----

  function notifyActivated(): void {
    if (disposed) return
    active = true
    void poll() // 重新激活立即查一轮；是否续排由结果决定
  }

  function notifyDeactivated(): void {
    active = false
    pause(true) // 失活：abort 在途并清状态（不露旧数据；恢复需重新激活）
  }

  function notifyVisibility(visible: boolean): void {
    if (documentVisible === visible) return
    documentVisible = visible
    if (!visible) {
      clearTimer()
      pollController?.abort()
      return
    }
    if (operational() && !polling) void poll()
  }

  // 账号变化（含换号/注销 epoch 递增）：旧票全失效、旧状态不可信——停一切并全清。
  const stopAccountWatch = getCurrentScope()
    ? watch(() => {
      const ticket = account.capture()
      return `${ticket.accountId ?? 'anon'}#${ticket.epoch}`
    }, () => {
      if (disposed) return
      pause(true)
    }, { flush: 'sync' })
    : null

  const onVisibilityChange = (): void => notifyVisibility(!document.hidden)
  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', onVisibilityChange)
  }

  function dispose(): void {
    if (disposed) return
    disposed = true
    pause(true)
    stopAccountWatch?.()
    if (typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }

  if (getCurrentScope()) onScopeDispose(dispose)

  return {
    // 服务端投影
    config, sessions, invocations, loading, error, pendingReconcileCount,
    // 配置表单
    configDraft, draftBaseVersion, updating, updateConflict, updateError, updateNotice,
    updateConfig, reloadDraft,
    // 终止
    terminating, terminateError, terminateNotice, terminateSession,
    // 核对
    reconciling, reconcileError, lastReconciled, reconcile,
    // 生命周期
    refresh, notifyActivated, notifyDeactivated, notifyVisibility, dispose,
  }
}

export type DigitalHumanAdminState = ReturnType<typeof useDigitalHumanAdmin>
