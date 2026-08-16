/**
 * 草场 marketplace 域 —— 交付物、media 上传、推荐官画像/声誉、钱包、任务/报名、资金账户。
 */
import type { RunFn } from './grassland-http'
import { request, readError, sleep, putToPresignedUrl, POLL_MAX_ATTEMPTS, POLL_INTERVAL_MS } from './grassland-http'
import type {
  EngagementSubmission, EngagementVerification, EngagementVerificationRun, EngagementRating, TaskContextSnapshot,
  MediaUploadTicket, MediaMetadata, CreateMediaUploadTicketInput, AttachmentDownload,
  RecommenderProfile, UpdateRecommenderProfileInput, RecommenderReputation,
  ReputationPolicy, UpdateReputationPolicyInput, AdminReputation,
  Lv5Admission, UpdateLv5AdmissionInput,
  RecommenderRecommendationPage, TaskRecommenderInvitation,
  Wallet,
  Task, CreateTaskInput, CreateDraftInput, UpdateTaskInput, ReviseTaskInput,
  TaskApplication, TaskFeedPage, TaskFeedQuery,
  ReservationOutcome, SettlementOutcome, MerchantContestOutcome,
  BatchOperationResponse,
  FinanceAccount,
  AnalyticsQuery, MerchantAnalyticsDashboard,
} from '../types/grassland'

export function useGrasslandMarketplace(run: RunFn) {
  const getMerchantAnalytics = (input: AnalyticsQuery) => {
    const qs = new URLSearchParams({ organizationId: input.organizationId })
    if (input.storeId) qs.set('storeId', input.storeId)
    if (input.from) qs.set('from', input.from)
    if (input.to) qs.set('to', input.to)
    return run(() => request<MerchantAnalyticsDashboard>(`/api/tasks/analytics?${qs}`))
  }
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

  const getTaskContext = (taskId: string, applicationId: string) =>
    run(() => request<TaskContextSnapshot>(
        `/api/tasks/${taskId}/applications/${applicationId}/task-context`))

  const createCreationContext = (input: {
    taskId: string
    applicationId: string
    taskVersion?: number
    platformId: string
    contentFormId: string
    materialIds?: string[]
  }) => run(() => request<{ id: string }>(
    '/api/creation-contexts', { method: 'POST', body: JSON.stringify(input) }))

  const listVerificationRuns = async (taskId: string, applicationId: string, submissionId: string) =>
    run(async () => {
      const data = await request<{ runs: EngagementVerificationRun[] }>(
        `/api/tasks/${taskId}/applications/${applicationId}/submissions/${submissionId}/verification/runs`)
      return data.runs
    })

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

  // ---------- marketplace：等级权益后台 ----------

  const getReputationPolicy = () =>
    run(() => request<ReputationPolicy>('/api/admin/reputation-config'))

  const updateReputationPolicy = (input: UpdateReputationPolicyInput) =>
    run(() => request<ReputationPolicy>('/api/admin/reputation-config', {
      method: 'PUT',
      body: JSON.stringify(input),
    }))

  const getAdminReputation = (accountId: string) =>
    run(() => request<AdminReputation>(
      `/api/admin/reputation/${encodeURIComponent(accountId)}`))

  const updateLv5Admission = (accountId: string, input: UpdateLv5AdmissionInput) =>
    run(() => request<Lv5Admission>(
      `/api/admin/reputation/${encodeURIComponent(accountId)}/lv5-admission`, {
        method: 'PUT',
        body: JSON.stringify(input),
      }))

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

  // ---------- marketplace：任务 + 报名 ----------

  const listTasks = (organizationId: string, status = 'published', storeId?: string) => {
    const params = new URLSearchParams({ organizationId, status })
    if (storeId) params.set('storeId', storeId)
    return run(() => request<Task[]>(`/api/tasks?${params}`))
  }

  const getTask = (taskId: string) =>
    run(() => request<Task>(`/api/tasks/${taskId}`))

  const listRecommenderRecommendations = (taskId: string, limit = 50) =>
    run(() => request<RecommenderRecommendationPage>(
      `/api/tasks/${taskId}/recommendations?limit=${Math.max(1, Math.min(limit, 100))}`))

  const inviteRecommender = (taskId: string, accountId: string) =>
    run(() => request<TaskRecommenderInvitation>(
      `/api/tasks/${taskId}/recommendations/${accountId}/invite`, { method: 'POST' }))

  const createTask = (input: CreateTaskInput) =>
    run(() => request<Task>('/api/tasks', { method: 'POST', body: JSON.stringify(input) }))

  /** 全局任务大厅 feed（GL-P1-TASK-001 Stage 2）：跨组织、仅 published 且未截止，keyset 游标分页。 */
  const listTaskFeed = (query: TaskFeedQuery = {}) => {
    const params = new URLSearchParams()
    if (query.platform) params.set('platform', query.platform)
    if (query.contentForm) params.set('contentForm', query.contentForm)
    if (query.minBountyCents != null) params.set('minBountyCents', String(query.minBountyCents))
    if (query.latitude != null) params.set('latitude', String(query.latitude))
    if (query.longitude != null) params.set('longitude', String(query.longitude))
    if (query.maxDistanceKm != null) params.set('maxDistanceKm', String(query.maxDistanceKm))
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

  /** 任务书 #27：批量接受报名（1–50 条）。 */
  const batchAcceptApplications = (taskId: string, applicationIds: string[]) =>
    run(() => request<BatchOperationResponse>(`/api/tasks/${taskId}/applications/batch-accept`, {
      method: 'POST',
      body: JSON.stringify({ applicationIds }),
    }))

  /** 任务书 #27：批量拒绝报名（1–50 条）。 */
  const batchRejectApplications = (taskId: string, applicationIds: string[]) =>
    run(() => request<BatchOperationResponse>(`/api/tasks/${taskId}/applications/batch-reject`, {
      method: 'POST',
      body: JSON.stringify({ applicationIds }),
    }))

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
   * 后端回的是 `not_confirmed`（见 `ApplicationController.settlementOutcome`）。
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

  return {
    getMerchantAnalytics,
    submitDeliverable, listDeliverables, rejectDeliverable, runVerificationChecks,
    getTaskContext, createCreationContext, listVerificationRuns,
    createMediaUploadTicket, confirmMediaUpload, uploadEngagementAttachment, getAttachmentDownloadUrl,
    getMyRecommenderProfile, updateMyRecommenderProfile, getRecommenderProfile, getReputation,
    getReputationPolicy, updateReputationPolicy, getAdminReputation, updateLv5Admission,
    rateEngagement, getEngagementRating,
    getMyWallet, withdrawFromWallet,
    listTasks, getTask, listRecommenderRecommendations, inviteRecommender,
    createTask, listTaskFeed, createDraft, updateTask, publishDraft, reviseTask,
    closeTask, cancelTask,
    listApplications, applyToTask, acceptApplication, rejectApplication, contestEngagement,
    batchAcceptApplications, batchRejectApplications,
    withdrawApplication, pollReservation, confirmEngagement, pollSettlement,
    provisionAccount, getAccount, creditAccount,
  }
}
