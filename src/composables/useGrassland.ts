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
  CreatePermissionRequestInput,
  InvitationAcceptResult,
  Membership,
  MyInvitation,
  Organization,
  OrgInvitation,
  OrganizationQuota,
  PermissionRequest,
  PermissionTier,
  ReservationOutcome,
  ReviewDecision,
  Store,
  StoreMembership,
  StoreRole,
  TaskUsage,
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

  // ---------- identity：额度 + 商家权限升级审核流（D-05）----------

  /**
   * 组织额度**上限**。
   *
   * ⚠️ 线上返回是嵌套的 `{tier, quota:{...}}`，这里拍平成 {@link OrganizationQuota} 再给 UI——
   * 拍平点只此一处，避免每个调用方各拆一次。
   */
  const getQuota = async (orgId: string): Promise<OrganizationQuota | null> =>
    run(async () => {
      const raw = await request<{ tier: PermissionTier; quota: Omit<OrganizationQuota, 'tier'> }>(
        `/api/organizations/${orgId}/quota`)
      return { tier: raw.tier, ...raw.quota }
    })

  /** 发布用量（额度的「已用」侧，来自 marketplace；与 {@link getQuota} 的上限合并展示）。 */
  const getUsage = (orgId: string) =>
    run(() => request<TaskUsage>(`/api/tasks/usage?organizationId=${encodeURIComponent(orgId)}`))

  /**
   * 提交权限升级申请（须 org OWNER）。
   *
   * 后端校验：`requestedTier` 须**高于**当前 tier（否则 409）；材料按 tier+行业必填，缺料 400。
   */
  const createPermissionRequest = (orgId: string, input: CreatePermissionRequestInput) =>
    run(() => request<PermissionRequest>(`/api/organizations/${orgId}/permission-requests`, {
      method: 'POST',
      body: JSON.stringify(input),
    }))

  /** 列本组织的申请（含历史与申诉件，须 org MEMBER+）。 */
  const listPermissionRequests = (orgId: string) =>
    run(() => request<PermissionRequest[]>(`/api/organizations/${orgId}/permission-requests`))

  /** 申诉（须 org OWNER，且原申请为 rejected）→ 新建一条 pending 走同一审核队列。 */
  const appealPermissionRequest = (orgId: string, id: string, materials: Record<string, string>, note?: string) =>
    run(() => request<PermissionRequest>(
      `/api/organizations/${orgId}/permission-requests/${id}/appeal`, {
        method: 'POST',
        body: JSON.stringify(note ? { materials, note } : { materials }),
      }))

  /** 平台 admin：待审队列（`app_users.role=='admin'`，否则 403）。 */
  const listPendingPermissionRequests = () =>
    run(() => request<PermissionRequest[]>('/api/admin/permission-requests'))

  /** 平台 admin：审核。approve → 升级 org tier；reject → tier 不变。终态再审 409。 */
  const reviewPermissionRequest = (id: string, decision: ReviewDecision, note?: string) =>
    run(() => request<PermissionRequest>(`/api/admin/permission-requests/${id}/review`, {
      method: 'POST',
      body: JSON.stringify(note ? { decision, note } : { decision }),
    }))

  // ---------- identity：组织成员 / 门店 / 门店成员（Slice 2F/2G/2J）----------

  /** 列组织成员（需 org MEMBER+）。 */
  const listMemberships = (orgId: string) =>
    run(() => request<Membership[]>(`/api/organizations/${orgId}/memberships`))

  /**
   * 加组织成员（需 org **OWNER**）。role 仅 admin/member——
   * 后端显式拒绝经此端点授予 owner；重复添加 409。
   */
  const addMembership = (orgId: string, accountId: string, role: 'admin' | 'member') =>
    run(() => request<Membership>(`/api/organizations/${orgId}/memberships`, {
      method: 'POST',
      body: JSON.stringify({ accountId, role }),
    }))

  /** 移除组织成员（需 org OWNER）。移除最后一个 owner → 409（last-owner 守卫）。 */
  const removeMembership = (orgId: string, accountId: string) =>
    run(() => request<unknown>(`/api/organizations/${orgId}/memberships/${accountId}`, { method: 'DELETE' }))

  // ---------- identity：按邮箱邀请成员 ----------

  /**
   * 按邮箱邀请成员（需 org **OWNER**）。
   *
   * ⚠️ 请求体字段是 `email` + `role`（后端 `CreateInvitationRequest(email, role)`），
   * 与直接加成员的 `accountId` 不同；role 仅 admin/member。
   *
   * 后端**不回答该邮箱是否已注册**——存在与否都返回 201（防账号枚举）。
   * 响应的 `emailSent` 表示是否真的发出了通知邮件（本地未配 SMTP 时为 false，需邀请人自行告知对方）。
   */
  const inviteMember = (orgId: string, email: string, role: 'admin' | 'member') =>
    run(() => request<OrgInvitation>(`/api/organizations/${orgId}/invitations`, {
      method: 'POST',
      body: JSON.stringify({ email, role }),
    }))

  /** 列本组织的邀请（含终态，需 org MEMBER+）。列表项**不带** `emailSent`。 */
  const listInvitations = (orgId: string) =>
    run(() => request<OrgInvitation[]>(`/api/organizations/${orgId}/invitations`))

  /** 撤销待接受邀请（需 org OWNER）。已被接受/谢绝/撤销 → 409。 */
  const revokeInvitation = (orgId: string, invitationId: string) =>
    run(() => request<unknown>(
      `/api/organizations/${orgId}/invitations/${invitationId}`, { method: 'DELETE' }))

  /** 列发给「我这个邮箱」的待接受邀请。响应带 `organizationName`，**不带** email/status。 */
  const listMyInvitations = () =>
    run(() => request<MyInvitation[]>('/api/me/invitations'))

  /** 接受邀请（无请求体）。本就是成员时不报错，返回 `alreadyMember: true`。 */
  const acceptInvitation = (invitationId: string) =>
    run(() => request<InvitationAcceptResult>(
      `/api/me/invitations/${invitationId}/accept`, { method: 'POST' }))

  /** 谢绝邀请（无请求体）。 */
  const declineInvitation = (invitationId: string) =>
    run(() => request<unknown>(`/api/me/invitations/${invitationId}/decline`, { method: 'POST' }))

  /** 列门店（需 org MEMBER+）。 */
  const listStores = (orgId: string) =>
    run(() => request<Store[]>(`/api/organizations/${orgId}/stores`))

  /** 建门店（需 org ADMIN+）。 */
  const createStore = (orgId: string, name: string) =>
    run(() => request<Store>(`/api/organizations/${orgId}/stores`, {
      method: 'POST',
      body: JSON.stringify({ name }),
    }))

  /** 列门店成员（需门店 STAFF+；org OWNER/ADMIN 隐式为门店 MANAGER）。 */
  const listStoreMemberships = (orgId: string, storeId: string) =>
    run(() => request<StoreMembership[]>(`/api/organizations/${orgId}/stores/${storeId}/memberships`))

  /**
   * 加门店成员。授权分档（Slice 2J）：
   * 任命 `manager` 需 **org ADMIN+**；加 `staff` 只需**门店 MANAGER+**（店长可自管本店员工）。
   */
  const addStoreMembership = (orgId: string, storeId: string, accountId: string, role: StoreRole) =>
    run(() => request<StoreMembership>(
      `/api/organizations/${orgId}/stores/${storeId}/memberships`, {
        method: 'POST',
        body: JSON.stringify({ accountId, role }),
      }))

  /** 移除门店成员（需门店 MANAGER+）。移除唯一经理 → 409（末位 MANAGER 守卫）。 */
  const removeStoreMembership = (orgId: string, storeId: string, accountId: string) =>
    run(() => request<unknown>(
      `/api/organizations/${orgId}/stores/${storeId}/memberships/${accountId}`, { method: 'DELETE' }))

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

  /**
   * 轮询资金预留结局，直到到达**终态**（accepted / compensated）或超时。
   *
   * ⚠️ 判据必须是「到达终态」而非「脱离 reserving」。accept 返回 202 后，
   * Saga 的 `beginAcceptance`（pending→reserving）还要几百毫秒才执行，
   * 这段窗口内后端回的是 `pending`——按「≠reserving 即终态」会在竞态窗口里**提前收工**，
   * UI 永久停在「处理中…」（浏览器实测：首次轮询即拿到 pending，全程只发了 1 次请求就退出，
   * 而后端其实已正确 accepted + 预留 ¥300）。
   */
  async function pollReservation(taskId: string, appId: string): Promise<ReservationOutcome | null> {
    return run(async () => {
      for (let attempt = 0; attempt < POLL_MAX_ATTEMPTS; attempt += 1) {
        const outcome = await request<ReservationOutcome>(
          `/api/tasks/${taskId}/applications/${appId}/reservation`)
        if (outcome.status === 'accepted' || outcome.status === 'compensated') {
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

  /**
   * 轮询结算结局，直到到达**终态**（settled / held）或超时。
   *
   * ⚠️ 与 {@link pollReservation} 同款竞态：confirm 返回 202 后 `confirmed_at` 落库前，
   * 后端回 `not_confirmed`（见 `ApplicationController.settlementOutcome`）。
   * 它和 `settling` 一样属于在途，按「≠settling 即终态」会让 UI 卡在「尚未确认履约」。
   */
  async function pollSettlement(taskId: string, appId: string): Promise<SettlementOutcome | null> {
    return run(async () => {
      for (let attempt = 0; attempt < POLL_MAX_ATTEMPTS; attempt += 1) {
        const outcome = await request<SettlementOutcome>(
          `/api/tasks/${taskId}/applications/${appId}/settlement`)
        if (outcome.status === 'settled' || outcome.status === 'held') {
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
   * 客服终审（覆盖面板判决）。**已打通并实测**。
   *
   * 前置：① 账号 `role` 为 `customer_service` 或 `admin`（平台角色，非业务身份）；
   * ② 5 分钟内调过 {@link reauthenticate}（MFA 近期性）。任一不满足 → 403。
   * 范围：争议须为 appealed 或 escalated-voting 态，否则 409。
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
    // identity：额度 + 权限升级审核流（D-05）
    getQuota,
    getUsage,
    createPermissionRequest,
    listPermissionRequests,
    appealPermissionRequest,
    listPendingPermissionRequests,
    reviewPermissionRequest,
    // identity：组织成员 / 门店 / 门店成员
    listMemberships,
    addMembership,
    removeMembership,
    inviteMember,
    listInvitations,
    revokeInvitation,
    listMyInvitations,
    acceptInvitation,
    declineInvitation,
    listStores,
    createStore,
    listStoreMemberships,
    addStoreMembership,
    removeStoreMembership,
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
