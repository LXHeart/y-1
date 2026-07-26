import { ref } from 'vue'
import type {
  AdjudicationSnapshot,
  CreateTaskInput,
  DisputeCase,
  FinanceAccount,
  GrasslandResponse,
  IdentityType,
  Judge,
  JudgeVote,
  Organization,
  ReservationOutcome,
  SettlementOutcome,
  Task,
  TaskApplication,
  VoteChoice,
} from '../types/grassland'

/**
 * 草场 Java 域请求封装（经 edge-bff）。
 *
 * 与旧 Express composable 的差异：
 * - 资金型 accept / confirm 返回 **202**，真实结果需轮询（{@link pollReservation} / {@link pollSettlement}）。
 * - 身份靠 cookie session → edge-bff 换发内部断言，故所有请求须 `credentials: 'include'`。
 * - 后端错误信封为 `{success:false, error}`，与 legacy 一致。
 */

/** 轮询上限：Saga 经 Temporal + 跨服务 HTTP，本地通常 <2s；给 30 次 × 1s 容错。 */
const POLL_MAX_ATTEMPTS = 30
const POLL_INTERVAL_MS = 1000

async function readError(response: Response, fallback: string): Promise<string> {
  const contentType = response.headers.get('content-type') || ''
  if (contentType.includes('application/json')) {
    const body = await response.json() as { error?: string }
    return body.error || fallback
  }
  const text = await response.text()
  return text.trim() || fallback
}

/** 统一请求：注入 cookie、解信封、非 2xx 抛带后端消息的 Error。 */
async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(url, {
    credentials: 'include',
    ...init,
    headers: init.body
      ? { 'Content-Type': 'application/json', ...(init.headers || {}) }
      : init.headers || {},
  })

  if (!response.ok) {
    throw new Error(await readError(response, `请求失败（${response.status}）`))
  }

  const body = await response.json() as GrasslandResponse<T>
  if (!body.success) {
    throw new Error(body.error || '请求失败')
  }
  return body.data as T
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

export function useGrassland() {
  const loading = ref(false)
  const error = ref('')

  function clearError(): void {
    error.value = ''
  }

  /** 包装：统一 loading / error 处理，失败返回 null（调用方按 null 判定，不需 try-catch）。 */
  async function run<T>(operation: () => Promise<T>): Promise<T | null> {
    loading.value = true
    error.value = ''
    try {
      return await operation()
    } catch (caught: unknown) {
      error.value = caught instanceof Error ? caught.message : '未知错误'
      return null
    } finally {
      loading.value = false
    }
  }

  // ---------- identity：组织 + 活动身份 ----------

  const listOrganizations = () => run(() => request<Organization[]>('/api/organizations'))

  const createOrganization = (name: string, industry?: string) =>
    run(() => request<Organization>('/api/organizations', {
      method: 'POST',
      body: JSON.stringify(industry ? { name, industry } : { name }),
    }))

  /**
   * 开通身份（商家须带 org；推荐官不需要）。
   *
   * ⚠️ 请求字段名是 `type`（后端 `OpenIdentityRequest(type, organizationId)`），
   * 但**响应**返回的是 `identityType`——请求/响应字段名不对称，e2e 联调时踩过（写成 identityType 会 400）。
   */
  const openIdentity = (type: IdentityType, organizationId?: string) =>
    run(() => request<unknown>('/api/me/identities', {
      method: 'POST',
      body: JSON.stringify(organizationId ? { type, organizationId } : { type }),
    }))

  /** 切换当前 session 的活动身份（多设备互不影响）。请求字段同为 `type`。 */
  const activateIdentity = (type: IdentityType) =>
    run(() => request<unknown>('/api/me/active-identity', {
      method: 'POST',
      body: JSON.stringify({ type }),
    }))

  /**
   * MFA 重认证（HLD §11.2）：校验密码 → 写入当前 session 的重认证时刻，
   * 使后续断言带 `authStrength=level2` + `reauthenticatedAt`，满足敏感操作的近期性要求。
   * 按 session 记录——一个设备重认证不提升另一设备权限。
   */
  const reauthenticate = (password: string) =>
    run(() => request<{ authStrength: string; reauthenticatedAt: string | null }>(
      '/api/me/reauthenticate', { method: 'POST', body: JSON.stringify({ password }) }))

  // ---------- marketplace：任务 + 报名 ----------

  const listTasks = (organizationId: string, status = 'published') =>
    run(() => request<Task[]>(
      `/api/tasks?organizationId=${encodeURIComponent(organizationId)}&status=${encodeURIComponent(status)}`))

  const createTask = (input: CreateTaskInput) =>
    run(() => request<Task>('/api/tasks', { method: 'POST', body: JSON.stringify(input) }))

  const listApplications = (taskId: string) =>
    run(() => request<TaskApplication[]>(`/api/tasks/${taskId}/applications`))

  const applyToTask = (taskId: string, note?: string) =>
    run(() => request<TaskApplication>(`/api/tasks/${taskId}/applications`, {
      method: 'POST',
      body: JSON.stringify(note ? { note } : {}),
    }))

  /**
   * 商家接受报名。**资金型任务返回 202**（预留 Saga 异步执行），非资金型直接 200。
   * 两种情况都返回后立即用 {@link pollReservation} 取最终结果。
   */
  async function acceptApplication(taskId: string, appId: string): Promise<boolean> {
    const result = await run(async () => {
      const response = await fetch(`/api/tasks/${taskId}/applications/${appId}/accept`, {
        method: 'POST',
        credentials: 'include',
      })
      if (!response.ok) {
        throw new Error(await readError(response, `接受失败（${response.status}）`))
      }
      return true
    })
    return result === true
  }

  const rejectApplication = (taskId: string, appId: string) =>
    run(() => request<TaskApplication>(`/api/tasks/${taskId}/applications/${appId}/reject`, { method: 'POST' }))

  /** 轮询资金预留结局，直到脱离 reserving 中间态或超时。 */
  async function pollReservation(taskId: string, appId: string): Promise<ReservationOutcome | null> {
    return run(async () => {
      for (let attempt = 0; attempt < POLL_MAX_ATTEMPTS; attempt += 1) {
        const outcome = await request<ReservationOutcome>(
          `/api/tasks/${taskId}/applications/${appId}/reservation`)
        if (outcome.status !== 'reserving') {
          return outcome
        }
        await sleep(POLL_INTERVAL_MS)
      }
      throw new Error('预留结果轮询超时，请稍后刷新查看')
    })
  }

  /** 商家确认履约 → 启动结算窗口 workflow（202）。 */
  async function confirmEngagement(taskId: string, appId: string): Promise<boolean> {
    const result = await run(async () => {
      const response = await fetch(`/api/tasks/${taskId}/applications/${appId}/confirm`, {
        method: 'POST',
        credentials: 'include',
      })
      if (!response.ok) {
        throw new Error(await readError(response, `确认失败（${response.status}）`))
      }
      return true
    })
    return result === true
  }

  /** 轮询结算结局，直到 settled/held 或超时（settling 为中间态）。 */
  async function pollSettlement(taskId: string, appId: string): Promise<SettlementOutcome | null> {
    return run(async () => {
      for (let attempt = 0; attempt < POLL_MAX_ATTEMPTS; attempt += 1) {
        const outcome = await request<SettlementOutcome>(
          `/api/tasks/${taskId}/applications/${appId}/settlement`)
        if (outcome.status !== 'settling') {
          return outcome
        }
        await sleep(POLL_INTERVAL_MS)
      }
      throw new Error('结算结果轮询超时，请稍后刷新查看')
    })
  }

  // ---------- finance：账户 + sandbox 充值 ----------

  const provisionAccount = () =>
    run(() => request<FinanceAccount>('/api/finance/accounts', { method: 'POST' }))

  const getAccount = (orgId: string) =>
    run(() => request<FinanceAccount>(`/api/finance/accounts/${orgId}`))

  /** sandbox 充值（非生产资金流；真实充值走 payment-intent，尚未接入）。 */
  const creditAccount = (orgId: string, amountCents: number) =>
    run(() => request<FinanceAccount>(`/api/finance/accounts/${orgId}/credit`, {
      method: 'POST',
      body: JSON.stringify({ amountCents }),
    }))

  // ---------- trust：争议 + 审判 ----------

  /** 开争议（engagementRef = marketplace applicationId）。每 engagement 至多一个活跃争议（幂等）。 */
  const openDispute = (engagementRef: string, reason?: string) =>
    run(() => request<DisputeCase>('/api/trust/disputes', {
      method: 'POST',
      body: JSON.stringify(reason ? { engagementRef, reason } : { engagementRef }),
    }))

  /** 启动审判（抽 7 官面板 + 启 workflow）。无可用审判官时后端返回 503。 */
  const startAdjudication = (disputeId: string) =>
    run(() => request<AdjudicationSnapshot>(`/api/trust/disputes/${disputeId}/adjudicate`, { method: 'POST' }))

  const getAdjudication = (disputeId: string) =>
    run(() => request<AdjudicationSnapshot>(`/api/trust/disputes/${disputeId}/adjudication`))

  /** 当事方上诉（须处于 decided 态，即在上诉窗口内）。 */
  const appealDispute = (disputeId: string, note?: string) =>
    run(() => request<AdjudicationSnapshot>(`/api/trust/disputes/${disputeId}/appeal`, {
      method: 'POST',
      body: JSON.stringify(note ? { note } : {}),
    }))

  // ---------- trust：审判官池 + 投票 ----------

  /** 推荐官报名成为审判官（幂等；商家会 403——不得自任裁判）。入池后才可能被抽进面板。 */
  const enrollAsJudge = () =>
    run(() => request<Judge>('/api/trust/judges', { method: 'POST' }))

  /** 查本人入池状态；未入池后端返回 404 → 此处转为 null（不当作错误）。 */
  async function getMyJudgeStatus(): Promise<Judge | null> {
    try {
      return await request<Judge>('/api/trust/judges/me')
    } catch {
      return null  // 404 = 尚未入池，属正常状态
    }
  }

  const leaveJudgePool = () =>
    run(() => request<Judge>('/api/trust/judges/me', { method: 'DELETE' }))

  /** 审判官投票（每官每轮一票，不可改；非面板成员 403）。字段名 `vote`/`rationale`。 */
  const castVote = (disputeId: string, vote: VoteChoice, rationale?: string) =>
    run(() => request<JudgeVote>(`/api/trust/disputes/${disputeId}/votes`, {
      method: 'POST',
      body: JSON.stringify(rationale ? { vote, rationale } : { vote }),
    }))

  /**
   * 客服终审（覆盖面板判决）。
   *
   * ⚠️ **当前仍会 403，但卡点已从 MFA 转移到身份**：
   * - MFA 侧已打通：调 {@link reauthenticate} 后断言会带 `authStrength=level2` + `reauthenticatedAt`（已实测）
   * - 剩余阻塞：trust 的 `requireCustomerService` 要求断言 `activeIdentityType=customer_service`，
   *   但 identity 的 `IdentityType` 只有 merchant/recommender——客服身份无法获得（与 judge 同类问题）。
   *   拟改为按 `app_users.role` 判定，但断言当前**不携带 role**，需先扩展共享断言契约。
   */
  const finalDecision = (disputeId: string, decision: string) =>
    run(() => request<AdjudicationSnapshot>(`/api/trust/disputes/${disputeId}/final-decision`, {
      method: 'POST',
      body: JSON.stringify({ decision }),
    }))

  return {
    loading,
    error,
    clearError,
    // identity
    listOrganizations,
    createOrganization,
    openIdentity,
    activateIdentity,
    reauthenticate,
    // marketplace
    listTasks,
    createTask,
    listApplications,
    applyToTask,
    acceptApplication,
    rejectApplication,
    pollReservation,
    confirmEngagement,
    pollSettlement,
    // finance
    provisionAccount,
    getAccount,
    creditAccount,
    // trust
    openDispute,
    startAdjudication,
    getAdjudication,
    appealDispute,
    // trust：审判官池 + 投票
    enrollAsJudge,
    getMyJudgeStatus,
    leaveJudgePool,
    castVote,
    finalDecision,
  }
}
