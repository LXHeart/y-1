/**
 * 草场 identity 域 —— 组织、身份、权限升级、成员/门店、会话、邀请。
 */
import type { RunFn } from './grassland-http'
import { request, putToPresignedUrl } from './grassland-http'
import { compressImageToFile } from './compress-image'
import type {
  IdentityProfile, Organization, StoreAccessScope, OrganizationAccessScope, IdentityType,
  PermissionTier, TaskUsage, OrganizationQuota, CreatePermissionRequestInput,
  PermissionRequest, PermissionRequestAudit, ReviewDecision,
  Membership, LoginSession,
  Store, StoreMembership, StoreRole, PublicBrandProfile, StorePublicProfile, StorePublicMedia,
  SubAccountMutationResult,
  MediaUploadTicket, MediaMetadata, BrandProfile, SaveBrandProfileInput,
  AccountClosureCheck, AccountClosureRequest, PersonalDataExport, PiiLifecycleAudit,
} from '../types/grassland'

/** 品牌 Logo 上传前的客户端压缩帽（后端服务端帽 2MB，取 1MB 留余量，同头像模式）。 */
const BRAND_LOGO_COMPRESS_MAX_BYTES = 1024 * 1024

export function useGrasslandIdentity(run: RunFn) {
  // ---------- identity：组织 + 活动身份 ----------

  /** 已开通身份列表。响应字段为 identityType（区别于 POST 请求字段 type）。 */
  const listIdentities = () => run(() => request<IdentityProfile[]>('/api/me/identities'))

  const listOrganizations = () => run(() => request<Organization[]>('/api/organizations'))

  /** 仅列当前账号显式加入的门店，不扩散为同组织的其他门店权限。 */
  const listMyStoreScopes = () => run(() => request<StoreAccessScope[]>('/api/me/store-scopes'))

  /** 当前账号的组织范围与角色（owner/admin/member）；素材库组织级管理入口按 admin 及以上显隐。 */
  const listMyOrganizationScopes = () =>
    run(() => request<OrganizationAccessScope[]>('/api/me/organization-scopes'))

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

  /** 当前 session 的活动身份（per-session；无记录 = 消费者/null）。 */
  const getActiveIdentity = () =>
    run(() => request<{ activeIdentityType: string | null }>('/api/me/active-identity'))

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
  const appealPermissionRequest = (orgId: string, id: string, materials: Record<string, string>, note?: string,
    attachmentIds: string[] = []) =>
    run(() => request<PermissionRequest>(
      `/api/organizations/${orgId}/permission-requests/${id}/appeal`, {
        method: 'POST',
        body: JSON.stringify({ materials, attachmentIds, ...(note ? { note } : {}) }),
      }))

  /** 平台 admin：待审队列（`app_users.role=='admin'`，否则 403）。 */
  const listPendingPermissionRequests = () =>
    run(() => request<PermissionRequest[]>('/api/admin/permission-requests'))

  const claimPermissionRequest = (id: string) =>
    run(() => request<PermissionRequest>(`/api/admin/permission-requests/${id}/claim`, { method: 'POST' }))

  const listPermissionRequestAudit = (id: string) =>
    run(() => request<PermissionRequestAudit[]>(`/api/admin/permission-requests/${id}/audit`))

  /** 平台 admin：审核。approve → 升级 org tier；reject → tier 不变。终态再审 409。 */
  const reviewPermissionRequest = (id: string, decision: ReviewDecision, note?: string, expectedVersion?: number) =>
    run(() => request<PermissionRequest>(`/api/admin/permission-requests/${id}/review`, {
      method: 'POST',
      body: JSON.stringify({ decision, ...(note ? { note } : {}),
        ...(expectedVersion === undefined ? {} : { expectedVersion }) }),
    }))

  // ---------- identity：组织成员 / 门店 / 门店成员（Slice 2F/2G/2J）----------

  /** 主体更名申请（V40 审核+30 天冷却；OWNER/ADMIN）。 */
  const requestOrgRename = (organizationId: string, name: string) =>
    run(() => request<unknown>(`/api/organizations/${organizationId}/rename-requests`, {
      method: 'POST',
      body: JSON.stringify({ name }),
    }))

  /** 主体更名申请历史（含待审；org MEMBER+）。 */
  const listOrgRenameRequests = (organizationId: string) =>
    run(() => request<unknown[]>(`/api/organizations/${organizationId}/rename-requests`))

  /** 平台审核队列：待审更名申请。 */
  const listAdminOrgRenames = () =>
    run(() => request<unknown[]>('/api/admin/org-rename-requests'))

  /** 审核更名申请：approve 生效 / reject 驳回留痕。 */
  const reviewAdminOrgRename = (id: string, decision: 'approve' | 'reject', note?: string) =>
    run(() => request<unknown>(`/api/admin/org-rename-requests/${id}/review`, {
      method: 'POST',
      body: JSON.stringify({ decision, ...(note ? { note } : {}) }),
    }))

  /** 列组织成员（需 org MEMBER+）。 */
  const listMemberships = (orgId: string) =>
    run(() => request<Membership[]>(`/api/organizations/${orgId}/memberships`))

  // 任务书 #49：挂靠端点（POST/DELETE memberships，按 accountId 挂既有账号）已下线——
  // 成员只能经主体直建子账号产生，移除走 /accounts/{id} 删除端点（永久作废）。

  // ---------- identity：成员子账号（任务书 #48；#49 loginName 改造）----------

  /**
   * 主体直建子账号（owner/admin）。一次动作 = 建号 + 赋角色 + 挂门店，免审默认即时生效。
   * #49：入参是登录名（3-24 位小写字母数字），后端拼「主体前缀-登录名」为完整账号；
   * 响应 `initialPassword` 是一次性明文（此后任何接口不可再取），调用方必须立刻展示/转交。
   */
  const createSubAccount = (
    orgId: string,
    input: { role: 'member' | 'manager' | 'staff'; loginName: string; displayName: string; storeId?: string },
  ) =>
    run(() => request<SubAccountMutationResult>(`/api/organizations/${orgId}/accounts`, {
      method: 'POST',
      body: JSON.stringify(input),
    }))

  /** 店长代建本店 staff；组织开了审核开关时返回 status=pending_review。 */
  const createStaffSubAccount = (
    orgId: string,
    storeId: string,
    input: { loginName: string; displayName: string },
  ) =>
    run(() => request<SubAccountMutationResult>(`/api/organizations/${orgId}/stores/${storeId}/accounts`, {
      method: 'POST',
      body: JSON.stringify({ role: 'staff', ...input }),
    }))

  /** 成员账号前缀读（组织成员可读，建号表单预览用）。 */
  const getAccountPrefix = (orgId: string) =>
    run(() => request<{ prefix: string }>(`/api/organizations/${orgId}/account-prefix`))

  /** 成员账号前缀改（ADMIN+；仅 3-24 位字母数字；只影响之后新建的账号）。 */
  const setAccountPrefix = (orgId: string, prefix: string) =>
    run(() => request<{ prefix: string }>(`/api/organizations/${orgId}/account-prefix`, {
      method: 'PATCH',
      body: JSON.stringify({ prefix }),
    }))

  // ---------- identity：子账号绑定邮箱（任务书 #49 D10）----------

  /** 第一步：向目标邮箱发送绑定验证码（登录态；后端有邮箱级 pending 频控 + 账号级限流）。 */
  const sendBindEmailCode = (email: string) =>
    run(() => request<{ sent: boolean }>('/api/me/bind-email/code', {
      method: 'POST',
      body: JSON.stringify({ email }),
    }))

  /** 第二步：验码换绑。错码 4xx / 邮箱被占 409 由 error 条呈现；成功后账号名与邮箱均可登录。 */
  const bindEmail = (email: string, code: string) =>
    run(() => request<{ bound: boolean }>('/api/me/bind-email', {
      method: 'POST',
      body: JSON.stringify({ email, code }),
    }))

  /** 停用成员账号：即时生效，事后站内知会主体（后端守卫报「最后一个店长」等冲突）。 */
  const suspendSubAccount = (orgId: string, accountId: string) =>
    run(() => request<unknown>(`/api/organizations/${orgId}/accounts/${accountId}/suspend`, { method: 'POST' }))

  /**
   * 删除成员（任务书 #49 D8）：解除全部成员关系 + 账号永久作废（逻辑删除留痕）。
   * 不可恢复（restore 对 deleted 409）；UI 必须输入完整账号名强确认后才可调用。
   */
  const deleteSubAccount = (orgId: string, accountId: string) =>
    run(() => request<unknown>(`/api/organizations/${orgId}/accounts/${accountId}`, { method: 'DELETE' }))

  /** 恢复停用成员（仅 suspended 态可恢复；rejected 是终态 → 409）。 */
  const restoreSubAccount = (orgId: string, accountId: string) =>
    run(() => request<unknown>(`/api/organizations/${orgId}/accounts/${accountId}/restore`, { method: 'POST' }))

  /** 店长代建员工的审批决定（approve / reject 终态）。 */
  const reviewSubAccountCreation = (orgId: string, accountId: string, decision: 'approve' | 'reject') =>
    run(() => request<unknown>(`/api/organizations/${orgId}/accounts/${accountId}/review`, {
      method: 'POST',
      body: JSON.stringify({ decision }),
    }))

  /** 管理员重置成员密码：响应含新的一次性 initialPassword。 */
  const resetSubAccountPassword = (orgId: string, accountId: string) =>
    run(() => request<SubAccountMutationResult>(
      `/api/organizations/${orgId}/accounts/${accountId}/reset-password`, { method: 'POST' }))

  /** 成员添加审核开关读/切（切需 org ADMIN+）。 */
  const getMemberReviewRequired = (orgId: string) =>
    run(() => request<{ required: boolean }>(`/api/organizations/${orgId}/member-review-required`))

  const setMemberReviewRequired = (orgId: string, required: boolean) =>
    run(() => request<{ required: boolean }>(`/api/organizations/${orgId}/member-review-required`, {
      method: 'PATCH',
      body: JSON.stringify({ required }),
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

  /** 一键登出其它所有设备（本机保留）；返回被撤销的设备数。 */
  const revokeOtherSessions = () =>
    run(() => request<{ revoked: number }>('/api/me/sessions', { method: 'DELETE' }))

  // ---------- identity：个人数据合规（#38）----------

  const checkAccountClosure = () =>
    run(() => request<AccountClosureCheck>('/api/me/compliance/closure-check'))

  const requestPersonalDataExport = () =>
    run(() => request<PersonalDataExport>('/api/me/compliance/exports', { method: 'POST' }))

  const getPersonalDataExport = (id: string) =>
    run(() => request<PersonalDataExport>(`/api/me/compliance/exports/${encodeURIComponent(id)}`))

  const requestAccountClosure = () =>
    run(() => request<AccountClosureRequest>('/api/me/compliance/account-closure', { method: 'POST' }))

  const listPiiLifecycleAudit = (limit = 20) =>
    run(() => request<{ entries: PiiLifecycleAudit[] }>(
      `/api/me/compliance/audit?limit=${Math.max(1, Math.min(limit, 100))}`))

  // 任务书 #49：邀请流整条下线（inviteMember/listInvitations/revokeInvitation/
  // listMyInvitations/acceptInvitation/declineInvitation 均已移除）。
  // 存量 pending 邀请由 V45 迁移作废；「我的邀请」卡片同步删除。

  /** 列门店（需 org MEMBER+）。 */
  const listStores = (orgId: string) =>
    run(() => request<Store[]>(`/api/organizations/${orgId}/stores`))

  /** 停用门店（可逆）：对外即刻隐藏（公开页/媒体 gate），店内管理与授权不受影响；需 ADMIN+。 */
  const suspendStore = (orgId: string, storeId: string) =>
    run(() => request<unknown>(`/api/organizations/${orgId}/stores/${storeId}/suspend`, { method: 'POST' }))

  /** 恢复停用的门店。 */
  const restoreStore = (orgId: string, storeId: string) =>
    run(() => request<unknown>(`/api/organizations/${orgId}/stores/${storeId}/restore`, { method: 'POST' }))

  /**
   * 删除门店（软删不可逆）：守卫=主体保留至少一家店/店内无成员/店内无任务（冲突 409 由
   * error 条呈现）；删除后从列表与授权中消失。
   */
  const deleteStore = (orgId: string, storeId: string) =>
    run(() => request<unknown>(`/api/organizations/${orgId}/stores/${storeId}`, { method: 'DELETE' }))

  /** 建门店（需 org ADMIN+）。 */
  const createStore = (orgId: string, name: string) =>
    run(() => request<Store>(`/api/organizations/${orgId}/stores`, {
      method: 'POST',
      body: JSON.stringify({ name }),
    }))

  /**
   * 门店公开资料（任务书 #24）：登录即可看、未登录也放行；门店/组织非 active 或无资料 → 后端 404。
   * 响应白名单不含 KYB 审核列与组织内部字段。
   */
  const getStorePublicProfile = (storeId: string) =>
    run(() => request<StorePublicProfile>(`/api/stores/${storeId}/public-profile`))

  /** 组织品牌资料公开消费（缺口清偿之六）：白名单字段，未登录可读。 */
  const getPublicBrandProfile = (organizationId: string) =>
    run(() => request<PublicBrandProfile>(
      `/api/organizations/${organizationId}/public-brand-profile`))

  /**
   * 门店公开媒体画廊（任务书 #42 D4/D5）：未登录也放行；store/org 非 active → 404。
   * `groups` 四类白名单（不含 uploadedBy/organizationId/createdAt）；单项被上游过滤静默缺席；
   * URL 过期可单独重拉本端点换新（public-profile 不受影响）。
   */
  const getStorePublicMedia = (storeId: string) =>
    run(() => request<StorePublicMedia>(`/api/stores/${storeId}/public-media`))

  /** 列门店成员（需门店 STAFF+；org OWNER/ADMIN 隐式为门店 MANAGER）。 */
  const listStoreMemberships = (orgId: string, storeId: string) =>
    run(() => request<StoreMembership[]>(`/api/organizations/${orgId}/stores/${storeId}/memberships`))

  // 任务书 #49：门店挂靠端点（addStoreMembership/removeStoreMembership）已随挂靠通路下线——
  // 门店成员经主体直建（店长建 staff）产生，移除走 /accounts/{id} 删除端点。

  // ---------- identity：组织品牌资料（#32）----------

  /**
   * 组织品牌资料（org MEMBER+ 可读；member 只读）。
   * 无行时后端回 version=0 的全空资料（不是 404），可直接绑表单；`logoUrl` 是短时效预览 URL。
   */
  const getBrandProfile = (orgId: string) =>
    run(() => request<BrandProfile>(`/api/organizations/${orgId}/brand-profile`))

  /**
   * 保存品牌资料（org ADMIN+；PUT 整份覆盖，没带的字段等于清空，显式 null 也要照发）。
   *
   * ⚠️ 409 乐观锁冲突（「品牌资料已变更，请刷新后重试」）：`request` 抛的是带 status 的
   * `GrasslandHttpError(409)`——经 `useGrassland().run` 落 error 通道返回 null（文案在
   * `error` 里）；需要按状态分支（冲突后自动重拉）的调用方以
   * `caught instanceof GrasslandHttpError && caught.status === 409` 判定（照 AiOrgBudgetPanel）。
   */
  const updateBrandProfile = (orgId: string, input: SaveBrandProfileInput) =>
    run(() => request<BrandProfile>(`/api/organizations/${orgId}/brand-profile`, {
      method: 'PUT',
      body: JSON.stringify(input),
    }))

  /**
   * 品牌 Logo 三步上传（org ADMIN+，D6）：压缩 ≤1MB（小图原样直传，不重复编码）→
   * identity **代开**票据（浏览器不能直连 `/api/media/upload-tickets` 申 brand_logo——
   * purpose 黑名单挡的就是它，归属断言只能在服务端做）→ 直传 presigned → confirm，
   * 返回可随 PUT 保存的 mediaId（不建附件记录，换 Logo = 新 id 随整份保存覆盖，D8）。
   *
   * ⚠️ 开票体取**压缩后**文件的 type/size——confirm 按对象 HEAD 逐字节校验，
   * 两处取值必须同源（与头像上传的「压缩在前、票据带真实字节数」约定一致）。
   */
  const uploadBrandLogo = (orgId: string, file: File) =>
    run(async () => {
      const uploadable = file.size > BRAND_LOGO_COMPRESS_MAX_BYTES
        ? await compressImageToFile(file, BRAND_LOGO_COMPRESS_MAX_BYTES)
        : file
      const ticket = await request<MediaUploadTicket>(
        `/api/organizations/${orgId}/brand-profile/logo/upload-ticket`, {
          method: 'POST',
          body: JSON.stringify({
            contentType: uploadable.type,
            sizeBytes: uploadable.size,
          }),
        })
      await putToPresignedUrl(ticket, uploadable)
      const confirmed = await request<MediaMetadata>(
        `/api/media/${ticket.id}/confirm`, { method: 'POST' })
      return confirmed.id
    })

  return {
    listIdentities, listOrganizations, listMyStoreScopes, listMyOrganizationScopes, createOrganization,
    openIdentity, activateIdentity, getActiveIdentity, reauthenticate,
    getQuota, getUsage,
    createPermissionRequest, listPermissionRequests, appealPermissionRequest,
    listPendingPermissionRequests, claimPermissionRequest, listPermissionRequestAudit,
    reviewPermissionRequest,
    listMemberships,
    createSubAccount, createStaffSubAccount,
    getAccountPrefix, setAccountPrefix,
    sendBindEmailCode, bindEmail,
    suspendSubAccount, restoreSubAccount, deleteSubAccount, reviewSubAccountCreation, resetSubAccountPassword,
    getMemberReviewRequired, setMemberReviewRequired,
    listMySessions, revokeOtherSessions, revokeSession,
    checkAccountClosure, requestPersonalDataExport, getPersonalDataExport,
    requestAccountClosure, listPiiLifecycleAudit,
    listStores, createStore, suspendStore, restoreStore, deleteStore, getStorePublicProfile, getPublicBrandProfile, getStorePublicMedia, listStoreMemberships,
    getBrandProfile, updateBrandProfile, uploadBrandLogo,
    requestOrgRename, listOrgRenameRequests, listAdminOrgRenames, reviewAdminOrgRename,
  }
}
