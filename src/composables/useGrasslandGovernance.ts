/**
 * 草场 governance 域 —— 争议/审判、运营处置台、KYB 商家资料、内容素材库、管理审核、财务对账。
 */
import type { RunFn } from './grassland-http'
import { request, putToPresignedUrl } from './grassland-http'
import type {
  DisputeCase, DeferredDisputeRequest, AdjudicationSnapshot, OpenDisputeResult,
  Judge, JudgeVote, VoteChoice, AdminJudge, AdminJudgePage, UpdateJudgeAdmissionInput,
  OpsCase, OpsCaseStatus, OpsCaseDetail, OpsCaseAction, OpsActionKind, OpsDltMessage,
  OpsPendingVerification,
  MerchantProfile, CreateMerchantProfileInput, MerchantAttachment, MerchantAttachmentType,
  MediaUploadTicket, MediaMetadata,
  ContentAsset, ContentAssetCategory, ContentAssetGrant, ContentAssetVersion, ContentLibraryType,
  CreateContentAssetInput, UpdateContentAssetInput,
  ContentAssetRecommendationInput, ContentAssetRecommendationResult,
  SpeechLanguage, SpeechTranscription,
  WithdrawalAccount, CreateWithdrawalAccountInput,
  StoreProfile, CreateStoreProfileInput,
  KybVerificationRequest, KybVerificationDetail, KybAttachmentDownload,
  RecommenderVerificationRequest, Task,
  RiskCase, RiskCaseAction, RiskCaseDetail, RiskCaseQuery, RiskSignal, RiskSignalQuery,
  AnalyticsQuery, BusinessAnalyticsReport, RecommenderAnalyticsReport,
} from '../types/grassland'

export function useGrasslandGovernance(run: RunFn) {
  const listRiskCases = (input: RiskCaseQuery = {}) => {
    const qs = new URLSearchParams()
    if (input.status) qs.set('status', input.status)
    if (input.severity) qs.set('severity', input.severity)
    if (input.subjectKind) qs.set('subjectKind', input.subjectKind)
    if (input.subjectRef) qs.set('subjectRef', input.subjectRef)
    qs.set('limit', String(input.limit ?? 100))
    return run(() => request<RiskCase[]>(`/api/trust/risk/cases?${qs}`))
  }

  const getRiskCase = (id: string) =>
    run(() => request<RiskCaseDetail>(`/api/trust/risk/cases/${encodeURIComponent(id)}`))

  const actOnRiskCase = (id: string, action: RiskCaseAction, note?: string) =>
    run(() => request<RiskCase>(`/api/trust/risk/cases/${encodeURIComponent(id)}/actions`, {
      method: 'POST', body: JSON.stringify({ action, note: note || '' }),
    }))

  const listRiskSignals = (input: RiskSignalQuery = {}) => {
    const qs = new URLSearchParams()
    if (input.status) qs.set('status', input.status)
    if (input.subjectKind) qs.set('subjectKind', input.subjectKind)
    if (input.subjectRef) qs.set('subjectRef', input.subjectRef)
    qs.set('limit', String(input.limit ?? 100))
    return run(() => request<RiskSignal[]>(`/api/trust/risk/signals?${qs}`))
  }

  const getAdminBusinessAnalytics = (input: AnalyticsQuery) => {
    const qs = analyticsParams(input)
    return run(() => request<BusinessAnalyticsReport>(`/api/admin/analytics/business?${qs}`))
  }

  const getAdminRecommenderAnalytics = (input: AnalyticsQuery) => {
    const qs = analyticsParams(input)
    return run(() => request<RecommenderAnalyticsReport[]>(`/api/admin/analytics/recommenders?${qs}`))
  }

  function analyticsParams(input: AnalyticsQuery): URLSearchParams {
    const qs = new URLSearchParams({ organizationId: input.organizationId })
    if (input.storeId) qs.set('storeId', input.storeId)
    if (input.from) qs.set('from', input.from)
    if (input.to) qs.set('to', input.to)
    return qs
  }

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

  // ---------- trust：审判官运营后台 ----------

  const listAdminJudges = (input: { cursor?: string; accountId?: string; limit?: number } = {}) => {
    const params = new URLSearchParams()
    if (input.cursor) params.set('cursor', input.cursor)
    if (input.accountId) params.set('accountId', input.accountId)
    if (input.limit != null) params.set('limit', String(input.limit))
    const query = params.toString()
    return run(() => request<AdminJudgePage>(
      `/api/admin/trust/judges${query ? `?${query}` : ''}`))
  }

  const getAdminJudge = (accountId: string) =>
    run(() => request<AdminJudge>(
      `/api/admin/trust/judges/${encodeURIComponent(accountId)}`))

  const updateJudgeAdmission = (accountId: string, input: UpdateJudgeAdmissionInput) =>
    run(() => request<AdminJudge>(
      `/api/admin/trust/judges/${encodeURIComponent(accountId)}/admission`, {
        method: 'PUT',
        body: JSON.stringify(input),
      }))

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
   * ② 5 分钟内调过 reauthenticate（MFA 近期性）。任一不满足 → 403。
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

  /** 「待判定」核验队列（GL-P2-ADMIN-004：尚未人工改判的 inconclusive）。 */
  const listOpsPendingVerifications = () =>
    run(() => request<OpsPendingVerification[]>('/api/ops/pending-verifications'))

  /** 人工改判 inconclusive 核验；自动核验真相不变，服务端写 verification_override。 */
  const overrideOpsVerification = (submissionId: string, status: 'passed' | 'failed', note: string) =>
    run(() => request<Record<string, unknown>>(
      `/api/ops/pending-verifications/${encodeURIComponent(submissionId)}/override`,
      { method: 'POST', body: JSON.stringify({ status, note }) },
    ))

  // ---------- KYB：商家资料（GL-P3-MERCHANT-001）----------

  /** 获取本组织的商家资料；尚未创建时后端返回 200 data:null。 */
  const getMerchantProfile = (orgId: string) =>
    request<MerchantProfile | null>(`/api/organizations/${orgId}/merchant-profile`)

  /** 创建商家资料。更新必须走 PUT，避免把"尚未创建"和"已存在"混为一条契约。 */
  const createMerchantProfile = (orgId: string, input: CreateMerchantProfileInput) =>
    run(() => request<MerchantProfile>(`/api/organizations/${orgId}/merchant-profile`, {
      method: 'POST',
      body: JSON.stringify(input),
    }))

  /** 更新已有商家资料；后端仅允许 draft/rejected 状态。 */
  const updateMerchantProfile = (orgId: string, input: CreateMerchantProfileInput) =>
    run(() => request<MerchantProfile>(`/api/organizations/${orgId}/merchant-profile`, {
      method: 'PUT',
      body: JSON.stringify(input),
    }))

  /** 提交商家资料审核。仅 draft 态可提交；提交后状态变为 pending，进入审核队列。 */
  const submitMerchantProfile = (orgId: string) =>
    run(() => request<MerchantProfile>(
      `/api/organizations/${orgId}/merchant-profile/submit`, { method: 'POST' }))

  /** 列出本组织的商家附件。 */
  const listMerchantAttachments = (orgId: string) =>
    run(() => request<MerchantAttachment[]>(`/api/organizations/${orgId}/merchant-attachments`))

  /** 上传商家附件（三步合一）。封装：申请凭据 → 直传 → confirm → 创建附件记录。 */
  const uploadMerchantAttachment = async (
    orgId: string, file: File, attachmentType: MerchantAttachmentType,
  ) =>
    run(async () => {
      if (!file.type) {
        throw new Error('无法识别文件类型，请选择图片或 PDF 文件')
      }
      const ticket = await request<MediaUploadTicket>(
        `/api/organizations/${orgId}/merchant-attachments/upload-ticket`, {
          method: 'POST',
          body: JSON.stringify({
            contentType: file.type,
            sizeBytes: file.size,
            attachmentType,
          }),
        })
      await putToPresignedUrl(ticket, file)
      const confirmed = await request<MediaMetadata>(
        `/api/media/${ticket.id}/confirm`, { method: 'POST' })
      const attachment = await request<MerchantAttachment>(
        `/api/organizations/${orgId}/merchant-attachments`, {
          method: 'POST',
          body: JSON.stringify({ attachmentType, mediaReferenceId: confirmed.id }),
        })
      return attachment
    })

  /** 删除商家附件（软删除，不删媒体文件）。 */
  const deleteMerchantAttachment = (orgId: string, attachmentId: string) =>
    run(() => request<unknown>(
      `/api/organizations/${orgId}/merchant-attachments/${attachmentId}`, { method: 'DELETE' }))

  // ---------- 内容素材库（PRD §4.8 / Slice 14）----------

  /** 三步合一上传素材资产（purpose=content_asset），返回 mediaId 供挂接。 */
  const uploadContentAssetFile = (file: File) =>
    run(async () => {
      const ticket = await request<MediaUploadTicket>('/api/media/upload-tickets', {
        method: 'POST',
        body: JSON.stringify({
          contentType: file.type || 'application/octet-stream',
          purpose: 'content_asset',
          sizeBytes: file.size,
        }),
      })
      await putToPresignedUrl(ticket, file)
      const confirmed = await request<MediaMetadata>(
        `/api/media/${ticket.id}/confirm`, { method: 'POST' })
      return confirmed.id
    })

  /** 列素材（按 libraryType 分流：personal/merchant 自有，public 全员只读，merchant+granted 被授权）。 */
  const listContentAssets = (params: {
    libraryType: ContentLibraryType
    category?: ContentAssetCategory
    granted?: boolean
    organizationId?: string
    storeId?: string
  }) => {
    const qs = new URLSearchParams({ libraryType: params.libraryType })
    if (params.category) qs.set('category', params.category)
    if (params.granted) qs.set('granted', 'true')
    if (params.organizationId) qs.set('organizationId', params.organizationId)
    if (params.storeId) qs.set('storeId', params.storeId)
    return run(() => request<{ items: ContentAsset[] }>(`/api/content-assets?${qs}`))
  }

  /**
   * 智能素材推荐（PRD §4.8「按任务和平台智能推荐」）。任务模式传 applicationId+taskId
   * （服务端拉权威任务上下文提词，不信任前端任务 JSON）；独立模式传 platform/contentForm/keywords。
   * 候选只含本人可访问素材，推荐只重排不越权。
   */
  const recommendContentAssets = (input: ContentAssetRecommendationInput) => {
    const qs = new URLSearchParams()
    if (input.applicationId) qs.set('applicationId', input.applicationId)
    if (input.taskId) qs.set('taskId', input.taskId)
    if (input.platform) qs.set('platform', input.platform)
    if (input.contentForm) qs.set('contentForm', input.contentForm)
    if (input.category) qs.set('category', input.category)
    if (input.keywords?.length) qs.set('keywords', input.keywords.join(','))
    const query = input.query?.trim()
    if (query) qs.set('query', query)
    if (input.limit != null) qs.set('limit', String(input.limit))
    const suffix = qs.size > 0 ? `?${qs}` : ''
    return run(() => request<ContentAssetRecommendationResult>(
      `/api/content-assets/recommendations${suffix}`))
  }

  // ---------- 语音转写（任务书 #33）----------

  /** 语音音频三步上传（purpose=speech_audio），返回 mediaId 供转写引用。 */
  const uploadSpeechAudio = (file: File) =>
    run(async () => {
      const ticket = await request<MediaUploadTicket>('/api/media/upload-tickets', {
        method: 'POST',
        body: JSON.stringify({
          contentType: file.type,
          purpose: 'speech_audio',
          sizeBytes: file.size,
        }),
      })
      await putToPresignedUrl(ticket, file)
      const confirmed = await request<MediaMetadata>(
        `/api/media/${ticket.id}/confirm`, { method: 'POST' })
      return confirmed.id
    })

  /** 创建转写（服务端同步完成 Sandbox 转写后返回终态记录）。 */
  const createSpeechTranscription = (mediaId: string, language: SpeechLanguage = 'auto') =>
    run(() => request<SpeechTranscription>('/api/speech/transcriptions', {
      method: 'POST',
      body: JSON.stringify({ mediaId, language }),
    }))

  /** 查询转写记录（owner 范围；id 做路径编码）。 */
  const getSpeechTranscription = (id: string) =>
    run(() => request<SpeechTranscription>(
      `/api/speech/transcriptions/${encodeURIComponent(id)}`))

  /** 创建素材条目（挂接已 confirm 的 mediaId）。 */
  const createContentAsset = (input: CreateContentAssetInput) =>
    run(() => request<ContentAsset>('/api/content-assets', {
      method: 'POST',
      body: JSON.stringify(input),
    }))

  /** 素材详情。 */
  const getContentAsset = (id: string) =>
    run(() => request<ContentAsset>(`/api/content-assets/${id}`))

  /** 列素材历史快照（PRD §4.8「更新不覆盖历史快照」）。 */
  const listContentAssetVersions = (id: string) =>
    run(() => request<{ items: ContentAssetVersion[] }>(`/api/content-assets/${id}/versions`))

  /** 编辑素材（落新 version 快照 + 乐观锁）。 */
  const updateContentAsset = (id: string, input: UpdateContentAssetInput) =>
    run(() => request<ContentAsset>(`/api/content-assets/${id}`, {
      method: 'PUT',
      body: JSON.stringify(input),
    }))

  /** 软删素材。 */
  const deleteContentAsset = (id: string) =>
    run(() => request<{ deleted: boolean }>(`/api/content-assets/${id}`, { method: 'DELETE' }))

  /** 素材下载签名 URL（短时 presigned GET，owner/org/grant 任一可访问）。 */
  const getContentAssetDownloadUrl = (id: string) =>
    run(() => request<{ downloadUrl: string; expiresIn: number }>(
      `/api/content-assets/${id}/download-url`))

  /** 商家授权某素材给推荐官（PRD §4.8「商家可指定哪些素材允许推荐官使用」）。 */
  const grantContentAsset = (id: string, granteeAccountId: string) =>
    run(() => request<ContentAssetGrant>(`/api/content-assets/${id}/grants`, {
      method: 'POST',
      body: JSON.stringify({ granteeAccountId }),
    }))

  /** 列某素材的全部授权（商家管理用）。 */
  const listContentAssetGrants = (id: string) =>
    run(() => request<{ items: ContentAssetGrant[] }>(`/api/content-assets/${id}/grants`))

  /** 撤销授权。 */
  const revokeContentAssetGrant = (id: string, granteeAccountId: string) =>
    run(() => request<{ revoked: boolean }>(
      `/api/content-assets/${id}/grants/${granteeAccountId}`, { method: 'DELETE' }))

  /**
   * 组织级 legacy 素材批量迁移到门店（Slice 14 收尾）。响应含 moved 计数与逐项结果；
   * 非 movable 项（别家 org/个人库/已在门店）统一 moved:false，服务端不区分原因。
   */
  const migrateContentAssetsToStore = (input: { storeId: string; assetIds: string[] }) =>
    run(() => request<{ moved: number; items: Array<{ id: string; moved: boolean }> }>(
      '/api/content-assets/store-migration', {
        method: 'POST',
        body: JSON.stringify(input),
      }))

  // ---------- KYB：收款账户 ----------

  /** 列出本组织的收款账户。 */
  const listWithdrawalAccounts = (orgId: string) =>
    run(() => request<WithdrawalAccount[]>(`/api/organizations/${orgId}/withdrawal-accounts`))

  /** 创建收款账户。创建后状态为 pending，需经审核。 */
  const createWithdrawalAccount = (orgId: string, input: CreateWithdrawalAccountInput) =>
    run(() => request<WithdrawalAccount>(`/api/organizations/${orgId}/withdrawal-accounts`, {
      method: 'POST',
      body: JSON.stringify(input),
    }))

  /** 更新仍可编辑的收款账户。 */
  const updateWithdrawalAccount = (
    orgId: string, accountId: string, input: CreateWithdrawalAccountInput,
  ) => run(() => request<WithdrawalAccount>(
    `/api/organizations/${orgId}/withdrawal-accounts/${accountId}`, {
      method: 'PUT',
      body: JSON.stringify(input),
    }))

  /** 将 pending/rejected 收款账户提交到审核队列。 */
  const submitWithdrawalAccount = (orgId: string, accountId: string) =>
    run(() => request<WithdrawalAccount>(
      `/api/organizations/${orgId}/withdrawal-accounts/${accountId}/submit`, { method: 'POST' }))

  /** 设置默认收款账户。仅 approved 态的账户可设默认。 */
  const setDefaultWithdrawalAccount = (orgId: string, accountId: string) =>
    run(() => request<WithdrawalAccount>(
      `/api/organizations/${orgId}/withdrawal-accounts/${accountId}/set-default`, { method: 'POST' }))

  /** 删除收款账户（软删除）。 */
  const deleteWithdrawalAccount = (orgId: string, accountId: string) =>
    run(() => request<unknown>(
      `/api/organizations/${orgId}/withdrawal-accounts/${accountId}`, { method: 'DELETE' }))

  // ---------- KYB：门店资料 ----------

  /** 获取门店资料；尚未创建时后端返回 200 data:null。 */
  const getStoreProfile = (orgId: string, storeId: string) =>
    request<StoreProfile | null>(`/api/organizations/${orgId}/stores/${storeId}/profile`)

  /** 创建/更新门店资料。后端用 POST 实现 upsert。 */
  const createStoreProfile = (orgId: string, storeId: string, input: CreateStoreProfileInput) =>
    run(() => request<StoreProfile>(
      `/api/organizations/${orgId}/stores/${storeId}/profile`, {
        method: 'POST',
        body: JSON.stringify(input),
      }))

  /** 提交门店资料进入统一 KYB 审核队列。 */
  const submitStoreProfile = (orgId: string, storeId: string) =>
    run(() => request<StoreProfile>(
      `/api/organizations/${orgId}/stores/${storeId}/profile/submit`, { method: 'POST' }))

  // ---------- KYB：审核申请（平台管理员）----------

  /** 列出所有 KYB 审核申请（管理员专用）。 */
  const listKybVerifications = () =>
    run(() => request<KybVerificationRequest[]>('/api/admin/kyb-requests'))

  const getKybVerificationDetail = (verificationId: string) =>
    run(() => request<KybVerificationDetail>(`/api/admin/kyb-requests/${verificationId}`))

  const getKybAttachmentDownload = (verificationId: string, attachmentId: string) =>
    run(() => request<KybAttachmentDownload>(
      `/api/admin/kyb-requests/${verificationId}/attachments/${attachmentId}/download-url`))

  /** 审核 KYB 申请（管理员专用）。approve → approved；reject → rejected。终态再审 → 409。 */
  const reviewKybVerification = (verificationId: string, decision: 'approve' | 'reject', note?: string) =>
    run(() => request<KybVerificationRequest>(`/api/admin/kyb-requests/${verificationId}/${decision}`, {
      method: 'POST',
      body: JSON.stringify(note ? { note } : {}),
    }))

  // ---------- 推荐官平台认证审核（GL-P2-ADMIN-002）----------

  const listRecommenderVerifications = () =>
    run(() => request<RecommenderVerificationRequest[]>('/api/admin/recommender-requests'))

  const reviewRecommenderVerification = (
    verificationId: string,
    decision: 'approve' | 'reject',
    note?: string,
  ) => run(() => request<RecommenderVerificationRequest>(
    `/api/admin/recommender-requests/${encodeURIComponent(verificationId)}/${decision}`,
    { method: 'POST', body: JSON.stringify(note ? { note } : {}) },
  ))

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

  // ---------- 任务内容审核（GL-P2-ADMIN-003 全审政策）----------

  const listPendingReviewTasks = () =>
    run(() => request<Task[]>('/api/admin/tasks/review'))

  const approveTaskReview = (taskId: string, expectedVersion: number) =>
    run(() => request<Task>(`/api/admin/tasks/${encodeURIComponent(taskId)}/review/approve`, {
      method: 'POST', body: JSON.stringify({ expectedVersion }),
    }))

  const rejectTaskReview = (taskId: string, expectedVersion: number, note: string) =>
    run(() => request<Task>(`/api/admin/tasks/${encodeURIComponent(taskId)}/review/reject`, {
      method: 'POST', body: JSON.stringify({ expectedVersion, note }),
    }))

  // ---------- 财务对账台（GL-P2-ADMIN-006）----------

  const listFinanceJournals = (params?: { organizationId?: string; from?: string; to?: string; limit?: number }) =>
    run(() => {
      const qs = new URLSearchParams()
      if (params?.organizationId) qs.set('organizationId', params.organizationId)
      if (params?.from) qs.set('from', params.from)
      if (params?.to) qs.set('to', params.to)
      qs.set('limit', String(params?.limit ?? 50))
      return request<Record<string, unknown>[]>(`/api/admin/finance/journals?${qs}`)
    })

  const getJournalPostings = (journalId: string) =>
    run(() => request<Record<string, unknown>[]>(`/api/admin/finance/journals/${encodeURIComponent(journalId)}/postings`))

  const reconcileEscrow = (orgId: string) =>
    run(() => request<Record<string, unknown>>(`/api/admin/finance/reconcile/escrow/${encodeURIComponent(orgId)}`))

  const reconcileWallet = (accountId: string) =>
    run(() => request<Record<string, unknown>>(`/api/admin/finance/reconcile/wallet/${encodeURIComponent(accountId)}`))

  return {
    listRiskCases, getRiskCase, actOnRiskCase, listRiskSignals,
    getAdminBusinessAnalytics, getAdminRecommenderAnalytics,
    openDispute, getDisputeRequest, startAdjudication, getAdjudication, appealDispute,
    enrollAsJudge, getMyJudgeStatus, leaveJudgePool,
    listAdminJudges, getAdminJudge, updateJudgeAdmission, castVote, finalDecision,
    listOpsCases, getOpsCase, submitOpsCase, decideOpsCase, resolveOpsCase,
    executeOpsAction, listOpsDlt, executeOpsDltAction,
    listOpsPendingVerifications, overrideOpsVerification,
    getMerchantProfile, createMerchantProfile, updateMerchantProfile, submitMerchantProfile,
    listMerchantAttachments, uploadMerchantAttachment, deleteMerchantAttachment,
    uploadContentAssetFile, listContentAssets, recommendContentAssets, createContentAsset, getContentAsset,
    listContentAssetVersions, updateContentAsset, deleteContentAsset, getContentAssetDownloadUrl,
    grantContentAsset, listContentAssetGrants, revokeContentAssetGrant, migrateContentAssetsToStore,
    uploadSpeechAudio, createSpeechTranscription, getSpeechTranscription,
    listWithdrawalAccounts, createWithdrawalAccount, updateWithdrawalAccount,
    submitWithdrawalAccount, setDefaultWithdrawalAccount, deleteWithdrawalAccount,
    getStoreProfile, createStoreProfile, submitStoreProfile,
    listKybVerifications, getKybVerificationDetail, getKybAttachmentDownload, reviewKybVerification,
    listRecommenderVerifications, reviewRecommenderVerification,
    newOperationId,
    listPendingReviewTasks, approveTaskReview, rejectTaskReview,
    listFinanceJournals, getJournalPostings, reconcileEscrow, reconcileWallet,
  }
}
