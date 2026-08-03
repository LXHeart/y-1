import { ref } from 'vue'
import type {
  AdjudicationSnapshot,
  AttachmentDownload,
  CreateMediaUploadTicketInput,
  CreateTaskInput,
  CreateDraftInput,
  DeferredDisputeRequest,
  DisputeCase,
  EngagementRating,
  EngagementSubmission,
  EngagementVerification,
  RecommenderProfile,
  RecommenderReputation,
  UpdateRecommenderProfileInput,
  FinanceAccount,
  GrasslandResponse,
  IdentityProfile,
  IdentityType,
  Judge,
  JudgeVote,
  MediaMetadata,
  MediaUploadTicket,
  OpsActionKind,
  OpsCase,
  OpsCaseAction,
  OpsCaseDetail,
  OpsCaseStatus,
  OpsDltMessage,
  OpsPendingVerification,
  OpenDisputeResult,
  CreatePermissionRequestInput,
  InvitationAcceptResult,
  LoginSession,
  MerchantContestOutcome,
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
  TaskFeedPage,
  TaskFeedQuery,
  UpdateTaskInput,
  ReviseTaskInput,
  Wallet,
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

/**
 * 第二步：把文件直传到 presigned URL。
 *
 * ⚠️ **刻意不走 {@link request}**，三处都不能照抄本站请求的写法：
 * 1. 目标是 MinIO/S3（nginx CORS 反代 `:9002`）而非本站——presigned PUT 的鉴权是签名里的 SigV4，
 *    本就不需要 cookie，故刻意不带 `credentials`；nginx 的 CORS 策略（`ce53cfb` 后唯一来源，故意不回
 *    `Access-Control-Allow-Credentials`）也配合这一点——带了反而被浏览器拦。
 * 2. 只回放 ticket 给的 header。多加任何一个（如 `Authorization`）都不在 SigV4 的 SignedHeaders 里 → 403。
 * 3. 响应体是**空的 / XML 错误**，不是 `{success,data}` 信封——不能拿 `request` 的 json 解析路径去解。
 */
async function putToPresignedUrl(ticket: MediaUploadTicket, file: File): Promise<void> {
  const response = await fetch(ticket.uploadUrl, {
    method: ticket.method || 'PUT',
    headers: ticket.headers || {},
    body: file,
  })
  if (!response.ok) {
    throw new Error(`附件上传失败（${response.status}）——凭据可能已过期，请重试`)
  }
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

  /** 已开通身份列表。响应字段为 identityType（区别于 POST 请求字段 type）。 */
  const listIdentities = () => run(() => request<IdentityProfile[]>('/api/me/identities'))

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

  // ---------- marketplace：履约交付物 ----------

  /**
   * 推荐官提交履约凭证（须本人、已接受的报名）。
   *
   * ⚠️ `contentUrl` 必须是 http(s) 链接，后端会校验；已有待核验的一份时 409（被退回后才可重交）。
   */
  const submitDeliverable = (
    taskId: string, applicationId: string, contentUrl: string, note?: string, mediaIds?: string[],
  ) =>
    run(() => request<EngagementSubmission>(
      `/api/tasks/${taskId}/applications/${applicationId}/submissions`, {
        method: 'POST',
        body: JSON.stringify({
          contentUrl,
          ...(note ? { note } : {}),
          // 空数组也不发：后端 mediaIds 省略与 [] 等价，少发一个字段少一处 400 风险。
          ...(mediaIds && mediaIds.length > 0 ? { mediaIds } : {}),
        }),
      }))

  /**
   * 列交付物（含历史，新的在前）。商家与本人推荐官可见。
   *
   * ⚠️ 响应是 `{ submissions: [...] }`，这里拆开只此一处，调用方直接拿数组。
   */
  const listDeliverables = async (taskId: string, applicationId: string) =>
    run(async () => {
      const raw = await request<{ submissions: EngagementSubmission[] }>(
        `/api/tasks/${taskId}/applications/${applicationId}/submissions`)
      return raw.submissions
    })

  /** 商家退回补交（带原因）。退回后推荐官可修改重交。 */
  const rejectDeliverable = (taskId: string, applicationId: string, submissionId: string, note?: string) =>
    run(() => request<EngagementSubmission>(
      `/api/tasks/${taskId}/applications/${applicationId}/submissions/${submissionId}/reject`, {
        method: 'POST',
        body: JSON.stringify({ note: note || '' }),
      }))

  /**
   * 商家触发履约核验（链接可达性 + AI 视觉核验附件）。返回 tri-state 核验记录：
   * `{submissionId,status,checks:[{type,status,detail,checkedAt}],lastCheckedAt}`。
   * 后端按 submission 内联回 listSubmissions，故此处只下发触发、不传 body。
   */
  const runVerificationChecks = (taskId: string, applicationId: string, submissionId: string) =>
    run(() => request<EngagementVerification>(
      `/api/tasks/${taskId}/applications/${applicationId}/submissions/${submissionId}/verification/checks`,
      { method: 'POST' }))

  // ---------- intelligence：media 直传（三步上传）----------

  /** 第一步：申请上传凭据。会原子预留 owner 配额（默认 20 个 / 400MB）并落一行 pending。 */
  const createMediaUploadTicket = (input: CreateMediaUploadTicketInput) =>
    run(() => request<MediaUploadTicket>('/api/media/upload-tickets', {
      method: 'POST',
      body: JSON.stringify(input),
    }))

  /**
   * 第三步：确认上传。后端按临时 key HEAD 校验字节数与 content-type，再服务端搬到最终 key 并置 active。
   *
   * ⚠️ 不 confirm 的 pending 行会被清理任务回收（默认 1 小时宽限），对象也会被删——
   * 提交交付物前必须 confirm 过，否则挂接时 intelligence 返回 404（附件不可用）。
   */
  const confirmMediaUpload = (mediaId: string) =>
    run(() => request<MediaMetadata>(`/api/media/${mediaId}/confirm`, { method: 'POST' }))

  /**
   * 三步合一：申请凭据 → 直传 → confirm，返回可用于提交交付物的 mediaId。
   *
   * 整个流程包在**一次** `run()` 里，故三步中任何一步失败都只留一条 `error`、loading 只闪一次；
   * 组件不需要自己串联，也不会出现「第二步失败但 loading 已复位」的中间态。
   *
   * ⚠️ `sizeBytes` 取 `file.size` 而不是让调用方传——confirm 时逐字节校验，两处取值必须同源。
   */
  const uploadEngagementAttachment = (file: File) =>
    run(async () => {
      const ticket = await request<MediaUploadTicket>('/api/media/upload-tickets', {
        method: 'POST',
        body: JSON.stringify({
          contentType: file.type || 'application/octet-stream',
          purpose: 'engagement_attachment',
          sizeBytes: file.size,
        }),
      })
      await putToPresignedUrl(ticket, file)
      const confirmed = await request<MediaMetadata>(
        `/api/media/${ticket.id}/confirm`, { method: 'POST' })
      return confirmed.id
    })

  /**
   * 附件下载 URL（商家与提交人均可取）。
   *
   * marketplace 先证该 media 确实挂在这条交付物上（防跨履约越权），再经服务断言向 intelligence 换签名 URL——
   * 浏览器**不能**直接调 intelligence 的 media 端点：附件 owner 是推荐官，商家在那边是无权访问的第三方。
   *
   * 附件已被 owner 删除时后端 404，这里会把错误落到 `error`（「附件已不可用」），返回 null。
   */
  const getAttachmentDownloadUrl = (
    taskId: string, applicationId: string, submissionId: string, mediaId: string,
  ) =>
    run(() => request<AttachmentDownload>(
      `/api/tasks/${taskId}/applications/${applicationId}/submissions/${submissionId}`
      + `/attachments/${mediaId}/download-url`))

  // ---------- 推荐官画像（identity）+ 声誉 / 评分（marketplace）----------

  /** 我的画像。没填过也返回空画像（不是 404），可直接绑到表单上。 */
  const getMyRecommenderProfile = () =>
    run(() => request<RecommenderProfile>('/api/me/recommender-profile'))

  /**
   * 保存我的画像（PUT **整份覆盖**：没带的字段等于清空，不是不改）。
   *
   * ⚠️ `contentTags`/`domainTags`/`socialAccounts` 是**数组**，不是逗号串——
   * 拆分留在输入框那一处做，别让前后端各拆一次。
   */
  const updateMyRecommenderProfile = (input: UpdateRecommenderProfileInput) =>
    run(() => request<RecommenderProfile>('/api/me/recommender-profile', {
      method: 'PUT',
      body: JSON.stringify(input),
    }))

  /** 看某人的画像（商家审核报名时用）。只回画像字段，不含邮箱等账号信息。 */
  const getRecommenderProfile = (accountId: string) =>
    run(() => request<RecommenderProfile>(`/api/recommenders/${encodeURIComponent(accountId)}/profile`))

  /**
   * 看某人的声誉指标与等级（PRD 五/六）。
   *
   * 商家侧要按等级/完成率筛选报名者，故通常是对「本任务的报名者」逐个并发取——
   * 后端没有、也刻意不提供「按条件搜人」的入口（那会把平台变成人肉数据库）。
   */
  const getReputation = (accountId: string) =>
    run(() => request<RecommenderReputation>(`/api/reputation/${encodeURIComponent(accountId)}`))

  /** 商家评分（1-5 星）。**须先确认履约**（否则 409），且一次履约只能评一次（重复 409）。 */
  const rateEngagement = (taskId: string, applicationId: string, score: number, comment?: string) =>
    run(() => request<EngagementRating>(`/api/tasks/${taskId}/applications/${applicationId}/rating`, {
      method: 'POST',
      body: JSON.stringify(comment ? { score, comment } : { score }),
    }))

  /** 查该履约的评分。未评价时后端返回 `data: null`（不是 404），此处如实回 null。 */
  const getEngagementRating = (taskId: string, applicationId: string) =>
    run(() => request<EngagementRating | null>(
      `/api/tasks/${taskId}/applications/${applicationId}/rating`))

  // ---------- finance：推荐官钱包 ----------

  /** 我的钱包（余额 + 最近流水）。accountId 由 BFF 断言决定，只能读到自己的。 */
  const getMyWallet = () => run(() => request<Wallet>('/api/finance/wallets/me'))

  /**
   * 提现（sandbox：立即出账，未接真实支付通道）。余额不足 → 409。
   * 返回提现后的钱包（余额与流水都已更新），调用方直接用返回值刷新即可。
   */
  const withdrawFromWallet = (amountCents: number) =>
    run(() => request<Wallet>('/api/finance/wallets/me/withdrawals', {
      method: 'POST',
      body: JSON.stringify({ amountCents }),
    }))

  // ---------- identity：多设备登录会话（Slice 2I）----------

  /**
   * 列本账号当前有效的登录会话（= 已登录设备）。
   *
   * 没切换过身份的设备也在其中，只是 `activeIdentityType`/`deviceId`/`ipAddress` 为 null。
   */
  const listMySessions = () => run(() => request<LoginSession[]>('/api/me/sessions'))

  /**
   * 撤销某台设备：清掉它的活动身份 **并删除其登录会话（该设备真的登出）**。
   * 撤销 `current: true` 的那条 = 把自己登出。撤销他人 session → 403，不存在 → 404。
   */
  const revokeSession = (sessionToken: string) =>
    run(() => request<unknown>(`/api/me/sessions/${encodeURIComponent(sessionToken)}`, { method: 'DELETE' }))

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

  /** 全局任务大厅 feed（GL-P1-TASK-001 Stage 2）：跨组织、仅 published 且未截止，keyset 游标分页。 */
  const listTaskFeed = (query: TaskFeedQuery = {}) => {
    const params = new URLSearchParams()
    if (query.platform) params.set('platform', query.platform)
    if (query.contentForm) params.set('contentForm', query.contentForm)
    if (query.minBountyCents != null) params.set('minBountyCents', String(query.minBountyCents))
    if (query.cursor) params.set('cursor', query.cursor)
    if (query.limit != null) params.set('limit', String(query.limit))
    const qs = params.toString()
    return run(() => request<TaskFeedPage>(`/api/tasks/feed${qs ? '?' + qs : ''}`))
  }

  /** 创建草稿（草稿 tier 也可建；不占发布额度）。 */
  const createDraft = (input: CreateDraftInput) =>
    run(() => request<Task>('/api/tasks/draft', { method: 'POST', body: JSON.stringify(input) }))

  /** 编辑草稿（仅 draft 态；expectedVersion 乐观锁）。 */
  const updateTask = (id: string, input: UpdateTaskInput) =>
    run(() => request<Task>(`/api/tasks/${id}`, { method: 'PUT', body: JSON.stringify(input) }))

  /** 发布草稿（draft→published；expectedVersion 乐观锁）。 */
  const publishDraft = (id: string, expectedVersion: number) =>
    run(() => request<Task>(`/api/tasks/${id}/publish`, {
      method: 'POST', body: JSON.stringify({ expectedVersion }),
    }))

  /**
   * 修订已发布任务（published→published 出新版本；expectedVersion 乐观锁）。
   * 只传非资金字段——赏金/平台发布后冻结，改了会动已报名履约的条款（见 ReviseTaskInput）。
   */
  const reviseTask = (id: string, input: ReviseTaskInput) =>
    run(() => request<Task>(`/api/tasks/${id}/revise`, { method: 'POST', body: JSON.stringify(input) }))

  /** 关闭报名（published→closed）。 */
  const closeTask = (id: string, expectedVersion: number) =>
    run(() => request<Task>(`/api/tasks/${id}/close`, {
      method: 'POST', body: JSON.stringify({ expectedVersion }),
    }))

  /** 取消任务（draft|published→cancelled）。 */
  const cancelTask = (id: string, expectedVersion: number) =>
    run(() => request<Task>(`/api/tasks/${id}/cancel`, {
      method: 'POST', body: JSON.stringify({ expectedVersion }),
    }))

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

  /** 商家拒绝系统核实通过的履约，先落 marketplace contest 门闩再转客服。 */
  const contestEngagement = (taskId: string, appId: string, reason: string) =>
    run(() => request<MerchantContestOutcome>(
      `/api/tasks/${taskId}/applications/${appId}/contest`, {
        method: 'POST',
        body: JSON.stringify({ reason }),
      }))

  /** 推荐官撤销本人 pending 报名（GL-P1-TASK-001：前端原缺入口，后端早有）。 */
  const withdrawApplication = (taskId: string, appId: string) =>
    run(() => request<TaskApplication>(`/api/tasks/${taskId}/applications/${appId}/withdraw`, { method: 'POST' }))

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

  /**
   * 开争议（engagementRef = marketplace applicationId）。即时案与 deferred request 显式判别，
   * 避免把 requestId 当成 dispute id 挂载审判看板。
   */
  const openDispute = (engagementRef: string, reason?: string) =>
    run(async (): Promise<OpenDisputeResult> => {
      const data = await request<DisputeCase | DeferredDisputeRequest>('/api/trust/disputes', {
        method: 'POST',
        body: JSON.stringify(reason ? { engagementRef, reason } : { engagementRef }),
      })
      if ('requestId' in data) {
        return { kind: 'deferred', request: data }
      }
      return { kind: 'dispute', dispute: data }
    })

  /** 查询 deferred objection；pending 时 disputeId/workflowId 为空，promoted 后指向 standard successor。 */
  const getDisputeRequest = (requestId: string) =>
    run(() => request<DeferredDisputeRequest>(
      `/api/trust/dispute-requests/${encodeURIComponent(requestId)}`))

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

  // ---------- 运营处置台（GL-P1-OPS-001）----------

  /** 处置队列。省略 status → 未终态（open/in_review/approved）；给定值精确筛选（含终态，供回看）。 */
  const listOpsCases = (status?: OpsCaseStatus) =>
    run(() => request<OpsCase[]>(`/api/ops/cases${status ? `?status=${status}` : ''}`))

  /** 详情：单据 + 审计时间线 + 动作台账。 */
  const getOpsCase = (id: string) =>
    run(() => request<OpsCaseDetail>(`/api/ops/cases/${id}`))

  /**
   * 三个流转端点都必须带 `expectedVersion`（乐观锁）——传当前 `case.version`。
   * 版本不符 / 状态已变 → 409，UI 应提示刷新而非重试。
   */
  const submitOpsCase = (id: string, expectedVersion: number, note?: string) =>
    run(() => request<OpsCase>(`/api/ops/cases/${id}/submit`, {
      method: 'POST',
      body: JSON.stringify({ expectedVersion, note }),
    }))

  /** 审批。**审批人不能是提审人**（后端 409）——同一浏览器登录同一账号无法自审自批。 */
  const decideOpsCase = (id: string, expectedVersion: number, approve: boolean, note?: string) =>
    run(() => request<OpsCase>(`/api/ops/cases/${id}/decide`, {
      method: 'POST',
      body: JSON.stringify({ expectedVersion, approve, note }),
    }))

  const resolveOpsCase = (id: string, expectedVersion: number, resolution: string, note?: string) =>
    run(() => request<OpsCase>(`/api/ops/cases/${id}/resolve`, {
      method: 'POST',
      body: JSON.stringify({ expectedVersion, resolution, note }),
    }))

  /**
   * 执行处置动作（须 case 已 approved）。
   *
   * `operationId` 是幂等键，**由调用方生成并在重试时复用**——网络超时后重按不会重复打下游。
   * 返回 status='failed' 时 HTTP 仍 200：动作执行过且失败了，看 `error`。
   */
  const executeOpsAction = (caseId: string, action: OpsActionKind, operationId: string) =>
    run(() => request<OpsCaseAction>(`/api/ops/cases/${caseId}/actions`, {
      method: 'POST',
      body: JSON.stringify({ action, operationId }),
    }))

  /** 死信队列。省略 status → 仅 pending。 */
  const listOpsDlt = (status?: OpsDltMessage['status']) =>
    run(() => request<OpsDltMessage[]>(`/api/ops/dlt${status ? `?status=${status}` : ''}`))

  /** 死信重投（replay=true，回原 topic 保留原 key）或弃置（false，只标记不删）。 */
  const executeOpsDltAction = (messageId: string, replay: boolean, operationId: string) =>
    run(() => request<OpsCaseAction>(`/api/ops/dlt/${messageId}/actions`, {
      method: 'POST',
      body: JSON.stringify({ replay, operationId }),
    }))

  /** 「待判定」核验只读窗（无处置动作——inconclusive 永不阻断结算）。 */
  const listOpsPendingVerifications = () =>
    run(() => request<OpsPendingVerification[]>('/api/ops/pending-verifications'))

  /**
   * 幂等键生成：`crypto.randomUUID` 在 HTTPS 与 localhost 之外不可用（Safari/旧 Chrome），
   * 故带一条时间戳+随机数兜底，避免运营台在 HTTP 内网入口上整个按钮不可用。
   */
  function newOperationId(prefix: string): string {
    const rand = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
    return `${prefix}-${rand}`
  }

  return {
    loading,
    error,
    clearError,
    // identity
    listIdentities,
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
    listMySessions,
    revokeSession,
    getMyWallet,
    withdrawFromWallet,
    // 画像 + 声誉 + 评分
    getMyRecommenderProfile,
    updateMyRecommenderProfile,
    getRecommenderProfile,
    getReputation,
    rateEngagement,
    getEngagementRating,
    submitDeliverable,
    listDeliverables,
    rejectDeliverable,
    runVerificationChecks,
    // intelligence：media 直传
    createMediaUploadTicket,
    confirmMediaUpload,
    uploadEngagementAttachment,
    getAttachmentDownloadUrl,
    listStores,
    createStore,
    listStoreMemberships,
    addStoreMembership,
    removeStoreMembership,
    // marketplace
    listTasks,
    createTask,
    listTaskFeed,
    createDraft,
    updateTask,
    reviseTask,
    publishDraft,
    closeTask,
    cancelTask,
    listApplications,
    applyToTask,
    acceptApplication,
    rejectApplication,
    contestEngagement,
    withdrawApplication,
    pollReservation,
    confirmEngagement,
    pollSettlement,
    // finance
    provisionAccount,
    getAccount,
    creditAccount,
    // trust
    openDispute,
    getDisputeRequest,
    startAdjudication,
    getAdjudication,
    appealDispute,
    // trust：审判官池 + 投票
    enrollAsJudge,
    getMyJudgeStatus,
    leaveJudgePool,
    castVote,
    finalDecision,
    // 运营处置台
    listOpsCases,
    getOpsCase,
    submitOpsCase,
    decideOpsCase,
    resolveOpsCase,
    executeOpsAction,
    listOpsDlt,
    executeOpsDltAction,
    listOpsPendingVerifications,
    newOperationId,
  }
}
