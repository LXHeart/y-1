import { afterEach, describe, expect, test, vi } from 'vitest'
import { useGrassland } from './useGrassland'
import { parsePermissionMaterials } from '../types/grassland'

/**
 * 请求契约回归测试。
 *
 * 背景：e2e 联调发现 `openIdentity`/`activateIdentity` 曾把字段写成 `identityType`，
 * 而 identity-service 的 `OpenIdentityRequest`/`ActivateIdentityRequest` 要求 **`type`** → 400。
 * 这类字段名不匹配 **typecheck 抓不到**（两个名字都是合法 TS），只能靠断言实际请求体锁死。
 *
 * 注意请求/响应字段不对称：请求用 `type`，响应返回 `identityType`。
 */

function mockFetchOk(): ReturnType<typeof vi.fn> {
  const spy = vi.fn().mockResolvedValue({
    ok: true,
    headers: { get: () => 'application/json' },
    json: async () => ({ success: true, data: {} }),
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

function bodyOf(spy: ReturnType<typeof vi.fn>, callIndex = 0): Record<string, unknown> {
  const init = spy.mock.calls[callIndex][1] as RequestInit
  return JSON.parse(init.body as string) as Record<string, unknown>
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('身份端点请求契约', () => {
  test('listIdentities 读取 identityType 响应字段并携带 cookie', async () => {
    const spy = vi.fn().mockResolvedValue({
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => ({
        success: true,
        data: [{ id: 'identity-1', identityType: 'recommender', organizationId: null, status: 'active' }],
      }),
    })
    vi.stubGlobal('fetch', spy)
    const { listIdentities } = useGrassland()

    await expect(listIdentities()).resolves.toEqual([
      { id: 'identity-1', identityType: 'recommender', organizationId: null, status: 'active' },
    ])

    expect(spy.mock.calls[0][0]).toBe('/api/me/identities')
    expect((spy.mock.calls[0][1] as RequestInit).credentials).toBe('include')
  })

  test('openIdentity 发送 type 而非 identityType', async () => {
    const spy = mockFetchOk()
    const { openIdentity } = useGrassland()

    await openIdentity('merchant', 'org-1')

    const body = bodyOf(spy)
    expect(body.type).toBe('merchant')
    expect(body.organizationId).toBe('org-1')
    expect(body).not.toHaveProperty('identityType')  // 写成 identityType 后端会 400
  })

  test('openIdentity 无 org 时不带 organizationId（推荐官）', async () => {
    const spy = mockFetchOk()
    const { openIdentity } = useGrassland()

    await openIdentity('recommender')

    const body = bodyOf(spy)
    expect(body.type).toBe('recommender')
    expect(body).not.toHaveProperty('organizationId')
  })

  test('activateIdentity 发送 type', async () => {
    const spy = mockFetchOk()
    const { activateIdentity } = useGrassland()

    await activateIdentity('merchant')

    const body = bodyOf(spy)
    expect(body.type).toBe('merchant')
    expect(body).not.toHaveProperty('identityType')
  })

  test('请求携带 cookie（BFF 靠 session 换发内部断言）', async () => {
    const spy = mockFetchOk()
    const { activateIdentity } = useGrassland()

    await activateIdentity('merchant')

    const init = spy.mock.calls[0][1] as RequestInit
    expect(init.credentials).toBe('include')
  })
})

describe('商家 contest 请求契约', () => {
  test('发送拒绝理由到 marketplace contest 端点', async () => {
    const spy = vi.fn().mockResolvedValue({
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => ({
        success: true,
        data: {
          applicationId: 'app-1', status: 'contested', reason: '证据不符', disputeId: 'dispute-1',
        },
      }),
    })
    vi.stubGlobal('fetch', spy)
    const { contestEngagement } = useGrassland()

    await contestEngagement('task-1', 'app-1', '证据不符')

    expect(spy.mock.calls[0][0]).toBe('/api/tasks/task-1/applications/app-1/contest')
    expect((spy.mock.calls[0][1] as RequestInit).method).toBe('POST')
    expect(bodyOf(spy)).toEqual({ reason: '证据不符' })
  })
})

describe('materials 解析（响应是 JSON 字符串，不是对象）', () => {
  // 浏览器实测缺陷：审核卡片直接 Object.entries(req.materials)，而后端按 materials::text
  // 返回的是 JSON **字符串** → 被逐字符展开，界面显示成一列单字（0:'{', 1:'"', …）。
  // 请求侧收对象、响应侧回字符串，是 P0-1 同类的请求/响应不对称。
  test('把 JSON 字符串解析成材料表', () => {
    const raw = '{"business_license":"91310000TEST","contact_info":"13800000000"}'

    expect(parsePermissionMaterials(raw)).toEqual({
      business_license: '91310000TEST',
      contact_info: '13800000000',
    })
  })

  test('逐字符展开的老写法与正确解析结果不同（锁住回归）', () => {
    const raw = '{"business_license":"x"}'

    // 老写法：Object.entries 对字符串 → 索引到字符
    expect(Object.entries(raw)[0]).toEqual(['0', '{'])
    // 正确写法
    expect(Object.keys(parsePermissionMaterials(raw))).toEqual(['business_license'])
  })

  test('null 与坏 JSON 返回空对象，不抛（审核界面不因脏数据整块炸掉）', () => {
    expect(parsePermissionMaterials(null)).toEqual({})
    expect(parsePermissionMaterials('not json')).toEqual({})
    expect(parsePermissionMaterials('[1,2]')).toEqual({})
  })
})

describe('成员邀请请求契约', () => {
  function mockFetchData(data: unknown): ReturnType<typeof vi.fn> {
    const spy = vi.fn().mockResolvedValue({
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: true, data }),
    })
    vi.stubGlobal('fetch', spy)
    return spy
  }

  test('inviteMember 发送 email 而非 accountId', async () => {
    const spy = mockFetchData({})
    const { inviteMember } = useGrassland()

    await inviteMember('org-1', 'someone@example.com', 'admin')

    expect(spy.mock.calls[0][0]).toBe('/api/organizations/org-1/invitations')
    const body = bodyOf(spy)
    expect(body.email).toBe('someone@example.com')
    expect(body.role).toBe('admin')
    // 与 addMembership 的请求体不同——写成 accountId 后端会 400
    expect(body).not.toHaveProperty('accountId')
  })

  test('accept / decline 打到 /api/me/invitations 且不带请求体', async () => {
    const spy = mockFetchData({ organizationId: 'org-1', role: 'member', alreadyMember: false })
    const { acceptInvitation, declineInvitation } = useGrassland()

    await acceptInvitation('inv-1')
    await declineInvitation('inv-2')

    expect(spy.mock.calls[0][0]).toBe('/api/me/invitations/inv-1/accept')
    expect((spy.mock.calls[0][1] as RequestInit).method).toBe('POST')
    expect((spy.mock.calls[0][1] as RequestInit).body).toBeUndefined()
    expect(spy.mock.calls[1][0]).toBe('/api/me/invitations/inv-2/decline')
  })

  /** 两个视角字段不对称：org 侧回 email/status，被邀请人侧回 organizationName 且没有 email。 */
  test('被邀请人侧列表读 organizationName（org 侧的 email/status 在这里不存在）', async () => {
    mockFetchData([{
      id: 'inv-1', organizationId: 'org-1', organizationName: '示例商家',
      role: 'member', expiresAt: '2026-08-03T10:00:00Z', createdAt: '2026-07-27T10:00:00Z',
    }])
    const { listMyInvitations } = useGrassland()

    const list = await listMyInvitations()

    expect(list?.[0].organizationName).toBe('示例商家')
    expect(list?.[0]).not.toHaveProperty('email')
  })

  test('撤销邀请用 DELETE 打到组织侧路径', async () => {
    const spy = mockFetchData(null)
    const { revokeInvitation } = useGrassland()

    await revokeInvitation('org-1', 'inv-9')

    expect(spy.mock.calls[0][0]).toBe('/api/organizations/org-1/invitations/inv-9')
    expect((spy.mock.calls[0][1] as RequestInit).method).toBe('DELETE')
  })
})

describe('权限升级审核流 + 额度请求契约', () => {
  function mockFetchData(data: unknown): ReturnType<typeof vi.fn> {
    const spy = vi.fn().mockResolvedValue({
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: true, data }),
    })
    vi.stubGlobal('fetch', spy)
    return spy
  }

  test('createPermissionRequest 发送 requestedTier/materials/industry', async () => {
    const spy = mockFetchData({})
    const { createPermissionRequest } = useGrassland()

    await createPermissionRequest('org-1', {
      requestedTier: 'finance_transaction',
      materials: { business_license: '照号 123', contact_info: '13800000000' },
      industry: 'catering',
    })

    expect(spy.mock.calls[0][0]).toBe('/api/organizations/org-1/permission-requests')
    const body = bodyOf(spy)
    expect(body.requestedTier).toBe('finance_transaction')
    expect(body.industry).toBe('catering')
    expect(body.materials).toEqual({ business_license: '照号 123', contact_info: '13800000000' })
    // 后端 CreatePermissionRequest 的字段名就是这三个，写错任何一个都是 400
    expect(body).not.toHaveProperty('tier')
  })

  test('reviewPermissionRequest 发送 decision（approve/reject）', async () => {
    const spy = mockFetchData({})
    const { reviewPermissionRequest } = useGrassland()

    await reviewPermissionRequest('req-1', 'approve', '材料齐全')

    expect(spy.mock.calls[0][0]).toBe('/api/admin/permission-requests/req-1/review')
    expect(bodyOf(spy)).toEqual({ decision: 'approve', note: '材料齐全' })
  })

  test('reviewPermissionRequest 不带 note 时不发该字段', async () => {
    const spy = mockFetchData({})
    const { reviewPermissionRequest } = useGrassland()

    await reviewPermissionRequest('req-1', 'reject')

    expect(bodyOf(spy)).toEqual({ decision: 'reject' })
  })

  test('getQuota 把嵌套的 {tier,quota:{...}} 拍平给 UI', async () => {
    // 线上格式是嵌套的；拍平点只在 composable 里，UI 直接拿扁平结构
    mockFetchData({ tier: 'basic_publish', quota: { maxActiveTasks: 5, maxMonthlyTasks: 20, maxTxAmountCents: 0 } })
    const { getQuota } = useGrassland()

    const quota = await getQuota('org-1')

    expect(quota).toEqual({
      tier: 'basic_publish', maxActiveTasks: 5, maxMonthlyTasks: 20, maxTxAmountCents: 0,
    })
  })

  test('getUsage 命中 marketplace 的 /api/tasks/usage 并带 organizationId', async () => {
    const spy = mockFetchData({ organizationId: 'org-1', activeTasks: 2, monthlyTasks: 3 })
    const { getUsage } = useGrassland()

    const usage = await getUsage('org-1')

    expect(spy.mock.calls[0][0]).toBe('/api/tasks/usage?organizationId=org-1')
    expect(usage).toEqual({ organizationId: 'org-1', activeTasks: 2, monthlyTasks: 3 })
  })

  test('appealPermissionRequest 打到原申请的 appeal 子路径', async () => {
    const spy = mockFetchData({})
    const { appealPermissionRequest } = useGrassland()

    await appealPermissionRequest('org-1', 'req-9', { business_license: '补正后的照号' }, '已补材料')

    expect(spy.mock.calls[0][0]).toBe('/api/organizations/org-1/permission-requests/req-9/appeal')
    expect(bodyOf(spy)).toEqual({ materials: { business_license: '补正后的照号' }, note: '已补材料' })
  })
})

describe('202 异步轮询终态判据', () => {
  /** 按序返回若干轮询响应；用尽后固定返回最后一个。 */
  function mockFetchSequence(statuses: Array<Record<string, unknown>>): ReturnType<typeof vi.fn> {
    let call = 0
    const spy = vi.fn().mockImplementation(async () => {
      const data = statuses[Math.min(call, statuses.length - 1)]
      call += 1
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
    })
    vi.stubGlobal('fetch', spy)
    return spy
  }

  test('pollReservation 不把 pending 当终态（accept 202 后的竞态窗口）', async () => {
    // 浏览器实测缺陷：Saga 的 beginAcceptance(pending→reserving) 执行前，后端回 pending。
    // 旧判据「≠reserving 即终态」只轮询一次就收工，UI 永久停在「处理中…」。
    const spy = mockFetchSequence([
      { status: 'pending' },
      { status: 'reserving' },
      { status: 'accepted' },
    ])
    const { pollReservation } = useGrassland()

    const outcome = await pollReservation('task-1', 'app-1')

    expect(outcome).toEqual({ status: 'accepted' })
    expect(spy).toHaveBeenCalledTimes(3)  // pending 与 reserving 都必须继续轮询
  })

  test('pollReservation 在 compensated 终态停止并带出 reason', async () => {
    const spy = mockFetchSequence([
      { status: 'pending' },
      { status: 'compensated', reason: 'insufficient_funds' },
    ])
    const { pollReservation } = useGrassland()

    const outcome = await pollReservation('task-1', 'app-1')

    expect(outcome).toEqual({ status: 'compensated', reason: 'insufficient_funds' })
    expect(spy).toHaveBeenCalledTimes(2)
  })

  test('pollSettlement 不把 not_confirmed 当终态（confirm 202 后的同款竞态）', async () => {
    const spy = mockFetchSequence([
      { status: 'not_confirmed' },
      { status: 'settling' },
      { status: 'settled' },
    ])
    const { pollSettlement } = useGrassland()

    const outcome = await pollSettlement('task-1', 'app-1')

    expect(outcome).toEqual({ status: 'settled' })
    expect(spy).toHaveBeenCalledTimes(3)
  })

  test('pollSettlement 在 held 终态停止（存在未终局争议）', async () => {
    const spy = mockFetchSequence([
      { status: 'settling' },
      { status: 'held', reason: 'open_dispute' },
    ])
    const { pollSettlement } = useGrassland()

    const outcome = await pollSettlement('task-1', 'app-1')

    expect(outcome).toEqual({ status: 'held', reason: 'open_dispute' })
    expect(spy).toHaveBeenCalledTimes(2)
  })
})

describe('争议 deferred 请求契约', () => {
  function mockFetchData(data: unknown): ReturnType<typeof vi.fn> {
    const spy = vi.fn().mockResolvedValue({
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: true, data }),
    })
    vi.stubGlobal('fetch', spy)
    return spy
  }

  test('即时开案返回 dispute 判别支，不混入 deferred request', async () => {
    mockFetchData({
      id: 'dispute-1', engagementRef: 'app-1', organizationId: 'org-1',
      openedByAccountId: 'rec-1', openedByRole: 'recommender', status: 'open', kind: 'standard',
      reason: '理由', decision: null, decidedAt: null, round: 0, version: 1,
      appealState: 'none', finalDecision: null, createdAt: null,
    })
    const { openDispute } = useGrassland()

    const result = await openDispute('app-1', '理由')

    expect(result).toEqual(expect.objectContaining({
      kind: 'dispute', dispute: expect.objectContaining({ id: 'dispute-1', kind: 'standard' }),
    }))
  })

  test('deferred 响应返回 request 判别支，requestId 不会成为 dispute id', async () => {
    mockFetchData({
      status: 'pending', requestId: 'request-1', engagementRef: 'app-1', reason: '逐字理由',
      disputeId: '', workflowId: '',
    })
    const { openDispute } = useGrassland()

    const result = await openDispute('app-1', '逐字理由')

    expect(result).toEqual({ kind: 'deferred', request: {
      status: 'pending', requestId: 'request-1', engagementRef: 'app-1', reason: '逐字理由',
      disputeId: '', workflowId: '',
    } })
    expect(result && result.kind === 'deferred' ? result.request.disputeId : 'wrong').toBe('')
  })

  test('getDisputeRequest 查询状态端点并编码 requestId', async () => {
    const spy = mockFetchData({
      status: 'promoted', requestId: 'request / 1', engagementRef: 'app-1', reason: '理由',
      disputeId: 'dispute-2', workflowId: 'adjudicate-dispute-2',
    })
    const { getDisputeRequest } = useGrassland()

    const result = await getDisputeRequest('request / 1')

    expect(spy.mock.calls[0][0]).toBe('/api/trust/dispute-requests/request%20%2F%201')
    expect(result?.disputeId).toBe('dispute-2')
  })
})

describe('错误处理', () => {
  test('后端 {success:false,error} 被提取为 error 消息', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: false, error: '当前等级不可发布任务' }),
    }))
    const { createTask, error } = useGrassland()

    const result = await createTask({ organizationId: 'o', title: 't' })

    expect(result).toBeNull()
    expect(error.value).toBe('当前等级不可发布任务')
  })
})

describe('履约附件三步上传请求契约（Slice 11）', () => {
  function mockFetchData(data: unknown): ReturnType<typeof vi.fn> {
    const spy = vi.fn().mockResolvedValue({
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: true, data }),
    })
    vi.stubGlobal('fetch', spy)
    return spy
  }

  test('createMediaUploadTicket 打到 /api/media/upload-tickets（复数）', async () => {
    const spy = mockFetchData({})
    const { createMediaUploadTicket } = useGrassland()

    await createMediaUploadTicket({
      contentType: 'image/png', purpose: 'engagement_attachment', sizeBytes: 1234,
    })

    // 后端路径是 upload-tickets 而非 upload-ticket，写成单数 404
    expect(spy.mock.calls[0][0]).toBe('/api/media/upload-tickets')
    expect(bodyOf(spy)).toEqual({
      contentType: 'image/png', purpose: 'engagement_attachment', sizeBytes: 1234,
    })
  })

  test('confirmMediaUpload POST 到 /confirm 且不带请求体', async () => {
    const spy = mockFetchData({ id: 'media-1', status: 'active' })
    const { confirmMediaUpload } = useGrassland()

    await confirmMediaUpload('media-1')

    expect(spy.mock.calls[0][0]).toBe('/api/media/media-1/confirm')
    expect((spy.mock.calls[0][1] as RequestInit).method).toBe('POST')
    expect((spy.mock.calls[0][1] as RequestInit).body).toBeUndefined()
  })

  test('runVerificationChecks POST 到 /verification/checks 且不带请求体（Slice 11 Verification）', async () => {
    const spy = mockFetchData({
      submissionId: 's-1', status: 'passed',
      checks: [{ type: 'link_reachability', status: 'passed', detail: 'HTTP 200', checkedAt: null }],
      lastCheckedAt: null,
    })
    const { runVerificationChecks } = useGrassland()

    const result = await runVerificationChecks('t-1', 'a-1', 's-1')

    expect(spy.mock.calls[0][0]).toBe('/api/tasks/t-1/applications/a-1/submissions/s-1/verification/checks')
    expect((spy.mock.calls[0][1] as RequestInit).method).toBe('POST')
    expect((spy.mock.calls[0][1] as RequestInit).body).toBeUndefined()
    expect(result).toEqual(expect.objectContaining({ submissionId: 's-1', status: 'passed' }))
  })

  test('submitDeliverable 带 mediaIds', async () => {
    const spy = mockFetchData({})
    const { submitDeliverable } = useGrassland()

    await submitDeliverable('t-1', 'a-1', 'https://x.test/p', '说明', ['m-1', 'm-2'])

    expect(bodyOf(spy)).toEqual({
      contentUrl: 'https://x.test/p', note: '说明', mediaIds: ['m-1', 'm-2'],
    })
  })

  test('submitDeliverable 附件为空数组时不发 mediaIds（少一处 400 风险）', async () => {
    const spy = mockFetchData({})
    const { submitDeliverable } = useGrassland()

    await submitDeliverable('t-1', 'a-1', 'https://x.test/p', undefined, [])

    expect(bodyOf(spy)).toEqual({ contentUrl: 'https://x.test/p' })
  })

  test('getAttachmentDownloadUrl 走 marketplace 嵌套路径（不直连 intelligence）', async () => {
    // 附件 owner 是推荐官，商家在 intelligence 侧是无权第三方——必须经 marketplace 服务断言中转
    const spy = mockFetchData({ downloadUrl: 'https://minio.test/signed', expiresAt: null })
    const { getAttachmentDownloadUrl } = useGrassland()

    const result = await getAttachmentDownloadUrl('t-1', 'a-1', 's-1', 'm-1')

    expect(spy.mock.calls[0][0])
      .toBe('/api/tasks/t-1/applications/a-1/submissions/s-1/attachments/m-1/download-url')
    expect(result?.downloadUrl).toBe('https://minio.test/signed')
  })

  test('uploadEngagementAttachment 三步串起来，PUT 用 ticket 的 url/method/headers', async () => {
    const ticket = {
      id: 'media-9',
      objectKey: 'media/engagement_attachment/media-9',
      uploadUrl: 'http://localhost:9002/grassland/tmp/media-9',
      method: 'PUT',
      headers: { 'Content-Type': 'image/png' },
      expiresAt: null,
    }
    let call = 0
    const spy = vi.fn().mockImplementation(async (url: string) => {
      call += 1
      if (url === ticket.uploadUrl) return { ok: true, status: 200 }
      const data = call === 1 ? ticket : { id: 'media-9', status: 'active' }
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
    })
    vi.stubGlobal('fetch', spy)
    const { uploadEngagementAttachment } = useGrassland()
    const file = new File([new Uint8Array(3)], 'shot.png', { type: 'image/png' })

    const mediaId = await uploadEngagementAttachment(file)

    expect(mediaId).toBe('media-9')
    expect(spy.mock.calls[0][0]).toBe('/api/media/upload-tickets')
    expect(bodyOf(spy)).toEqual({
      contentType: 'image/png', purpose: 'engagement_attachment', sizeBytes: 3,
    })
    // 第二步：直传 MinIO
    const put = spy.mock.calls[1][1] as RequestInit
    expect(spy.mock.calls[1][0]).toBe(ticket.uploadUrl)
    expect(put.method).toBe('PUT')
    expect(put.headers).toEqual({ 'Content-Type': 'image/png' })
    expect(put.body).toBe(file)
    // 第三步：confirm
    expect(spy.mock.calls[2][0]).toBe('/api/media/media-9/confirm')
  })

  test('直传 MinIO 时不带 cookie（credentials 会要求 ACAC 头，nginx 未发 → 请求被拦）', async () => {
    const ticket = {
      id: 'media-9', objectKey: 'k', uploadUrl: 'http://localhost:9002/b/k',
      method: 'PUT', headers: {}, expiresAt: null,
    }
    let call = 0
    const spy = vi.fn().mockImplementation(async (url: string) => {
      call += 1
      if (url === ticket.uploadUrl) return { ok: true, status: 200 }
      return {
        ok: true, headers: { get: () => 'application/json' },
        json: async () => ({ success: true, data: call === 1 ? ticket : { id: 'media-9' } }),
      }
    })
    vi.stubGlobal('fetch', spy)
    const { uploadEngagementAttachment } = useGrassland()

    await uploadEngagementAttachment(new File(['x'], 'a.bin'))

    const put = spy.mock.calls[1][1] as RequestInit
    expect(put.credentials).toBeUndefined()
    // 本站请求仍必须带 cookie
    expect((spy.mock.calls[0][1] as RequestInit).credentials).toBe('include')
  })

  test('直传失败时不 confirm，错误落到 error（不留半成品 active 资产）', async () => {
    const ticket = {
      id: 'media-9', objectKey: 'k', uploadUrl: 'http://localhost:9002/b/k',
      method: 'PUT', headers: {}, expiresAt: null,
    }
    const spy = vi.fn().mockImplementation(async (url: string) => {
      if (url === ticket.uploadUrl) return { ok: false, status: 403 }
      return {
        ok: true, headers: { get: () => 'application/json' },
        json: async () => ({ success: true, data: ticket }),
      }
    })
    vi.stubGlobal('fetch', spy)
    const { uploadEngagementAttachment, error } = useGrassland()

    const mediaId = await uploadEngagementAttachment(new File(['x'], 'a.bin'))

    expect(mediaId).toBeNull()
    expect(error.value).toContain('403')
    expect(spy).toHaveBeenCalledTimes(2)  // ticket + PUT，没有第三步
    expect(spy.mock.calls.some((c) => String(c[0]).includes('/confirm'))).toBe(false)
  })
})
