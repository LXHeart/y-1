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
  Membership, LoginSession, OrgInvitation, MyInvitation,
  InvitationAcceptResult, Store, StoreMembership, StoreRole, StorePublicProfile, StorePublicMedia,
  MediaUploadTicket, MediaMetadata, BrandProfile, SaveBrandProfileInput,
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
  /** 门店级邀请传 storeId（角色为 staff/manager）；缺省为组织级邀请（admin/member）。 */
  const inviteMember = (orgId: string, email: string, role: 'admin' | 'member' | 'staff' | 'manager',
    storeId?: string) =>
    run(() => request<OrgInvitation>(`/api/organizations/${orgId}/invitations`, {
      method: 'POST',
      body: JSON.stringify({ email, role, ...(storeId ? { storeId } : {}) }),
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

  /**
   * 门店公开资料（任务书 #24）：登录即可看、未登录也放行；门店/组织非 active 或无资料 → 后端 404。
   * 响应白名单不含 KYB 审核列与组织内部字段。
   */
  const getStorePublicProfile = (storeId: string) =>
    run(() => request<StorePublicProfile>(`/api/stores/${storeId}/public-profile`))

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
    openIdentity, activateIdentity, reauthenticate,
    getQuota, getUsage,
    createPermissionRequest, listPermissionRequests, appealPermissionRequest,
    listPendingPermissionRequests, claimPermissionRequest, listPermissionRequestAudit,
    reviewPermissionRequest,
    listMemberships, addMembership, removeMembership,
    listMySessions, revokeOtherSessions, revokeSession,
    inviteMember, listInvitations, revokeInvitation,
    listMyInvitations, acceptInvitation, declineInvitation,
    listStores, createStore, getStorePublicProfile, getStorePublicMedia, listStoreMemberships, addStoreMembership,
    removeStoreMembership,
    getBrandProfile, updateBrandProfile, uploadBrandLogo,
  }
}
