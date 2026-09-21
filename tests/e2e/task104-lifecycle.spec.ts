import { expect, test } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import {
  accountIdOf, accountsFor, emptyManifest, envelopeData, newRunId, query, registerAndLogin, seedOrganizations,
} from './fixtures/task-104'
import type { RunManifest } from './fixtures/task-103'

/**
 * 任务书 #104 C104-10（TC104-10-01）：组织清理真实栈闭环（R01/R02）。
 *
 * 全链真实：组织密钥与个人密钥经真实 intelligence BYOK API（栈内 KEK）；注销经真实
 * /api/me/compliance/account-closure；retention 到期按仓库既定 fixture 口径 DB 拨秒
 * （ComplianceProperties piiRetentionDays 下限 1 天，环境无法压缩；此后由真实
 * ComplianceWorker/intelligence 清理 worker 推进，不伪造任何快照）。
 * 断言：A 个人内容清空、O1 组织密钥保留、B（O1 现任所有者）仍可维护 A 创建的组织密钥、
 * C 与其他组织不受影响、越权拒绝。A 不持有任何组织（注销资格 ORGANIZATION_OWNERSHIP 挡 owner）。
 */

// ci-e2e.sh 在宿主侧运行 playwright：DB 走映射端口（E2E_DATABASE_URL），应用走 BASE_URL。
const DATABASE_URL = process.env.E2E_DATABASE_URL ?? ''
const PASSWORD = 'test-password-104'

test.describe.serial('TC104-10-01 组织清理真实栈（R01/R02）', () => {
  const runId = newRunId()
  const manifest: RunManifest = emptyManifest(runId)
  const evidenceDir = process.env.TASK104_EVIDENCE_DIR ?? 'test-artifacts/task-104/e2e-runs'
  const state: {
    orgId?: string
    otherOrgId?: string
    accountIdA?: string
    accountIdB?: string
    accountIdC?: string
    orgKeyId?: string
    personalKeyId?: string
    closureRequestId?: string
  } = {}

  test('准备：账号/组织/成员 + A 创建组织密钥与个人密钥（真实 API）', async () => {
    test.setTimeout(180_000)
    expect(DATABASE_URL, 'E2E_DATABASE_URL 须由 ci-e2e.sh 提供').toBeTruthy()
    const [a, b, c] = accountsFor(runId)
    manifest.accounts.push(a, b, c)
    // 建号（三个账号都要真实可登录；邮箱标识全小写——identity 登录按小写归一查询）。
    const ctxA = await registerAndLogin(baseURL(), a, PASSWORD, DATABASE_URL)
    const ctxB = await registerAndLogin(baseURL(), b, PASSWORD, DATABASE_URL)
    const ctxC = await registerAndLogin(baseURL(), c, PASSWORD, DATABASE_URL)
    state.accountIdA = await accountIdOf(DATABASE_URL, a.email)
    state.accountIdB = await accountIdOf(DATABASE_URL, b.email)
    state.accountIdC = await accountIdOf(DATABASE_URL, c.email)
    const orgs = await seedOrganizations(DATABASE_URL, state.accountIdA, state.accountIdB,
      state.accountIdC, runId)
    state.orgId = orgs.organizationId
    state.otherOrgId = orgs.otherOrganizationId

    // 活动身份切 merchant（BYOK 端点要求 merchant 身份断言）。
    for (const ctx of [ctxA, ctxB, ctxC]) {
      const identity = await ctx.post('/api/me/active-identity', { data: { type: 'merchant' } })
      expect(identity.status(), await identity.text()).toBe(200)
    }

    // A 创建 O1 组织密钥（真实控制器+KEK 信封加密）与个人密钥。
    const orgKey = await ctxA.post(`/api/ai/organizations/${state.orgId}/keys`, {
      data: { capability: 'text', provider: 'openai-compatible', baseUrl: 'https://api.openai.com', model: 'gpt-4o', apiKey: 'test-api-key-104-org-fixture' },
    })
    expect(orgKey.status(), await orgKey.text()).toBe(201)
    state.orgKeyId = (await envelopeData<{ id: string }>(orgKey, 'create org key')).id

    const personalKey = await ctxA.post('/api/ai/keys', {
      data: { capability: 'text', provider: 'openai-compatible', baseUrl: 'https://api.openai.com', apiKey: 'test-api-key-104-personal-fixture' },
    })
    expect(personalKey.status(), await personalKey.text()).toBe(201)
    state.personalKeyId = (await envelopeData<{ id: string }>(personalKey, 'create personal key')).id

    // A 的其他个人域数据：intelligence 草稿（DB 直插合成正文行）。
    await query(DATABASE_URL,
      'INSERT INTO creation_draft(id, owner_account_id, title, source_type, created_at, updated_at)'
      + " VALUES (gen_random_uuid(), $1, 'T104 待清理草稿', 'independent', now(), now())", [state.accountIdA])

    await ctxA.dispose(); await ctxB.dispose(); await ctxC.dispose()
  })

  test('R02 前置：B 已可维护 A 创建的组织密钥（活动期）', async () => {
    const [, b] = accountsFor(runId)
    const ctxB = await registerAndLogin(baseURL(), b, PASSWORD, DATABASE_URL)
    await ctxB.post('/api/me/active-identity', { data: { type: 'merchant' } })
    const update = await ctxB.put(`/api/ai/organizations/${state.orgId}/keys/${state.orgKeyId}`, {
      data: { baseUrl: 'https://api.openai.com', model: 'gpt-4o-mini' },
    })
    expect(update.status(), await update.text()).toBe(200)
    await ctxB.dispose()
  })

  test('R01：A 发起注销并推进到 retention，拨秒后由真实 worker 清理至完成', async () => {
    test.setTimeout(420_000)
    const [a] = accountsFor(runId)
    const ctxA = await registerAndLogin(baseURL(), a, PASSWORD, DATABASE_URL)
    await ctxA.post('/api/me/active-identity', { data: { type: 'merchant' } })
    const closure = await ctxA.post('/api/me/compliance/account-closure')
    expect(closure.status(), await closure.text()).toBe(202)
    state.closureRequestId = (await envelopeData<{ id: string }>(closure, 'closure')).id
    manifest.flows.closure = { accountId: state.accountIdA, closureRequestId: state.closureRequestId }

    // 真实推进到 retention（屏障/导出等阶段由 identity worker 完成）。
    await expect.poll(async () =>
      (await query<{ status: string }>(DATABASE_URL,
        'SELECT status FROM account_closure_request WHERE id = $1', [state.closureRequestId!]))[0]?.status,
    { timeout: 120_000 }).toBe('retention')

    // 保留期下限 1 天（环境不可压）：按仓库 fixture 口径 DB 拨秒，此后清理仍由真实 worker 推进。
    await query(DATABASE_URL,
      'UPDATE account_closure_request SET retention_until = now() - interval \'1 second\' WHERE id = $1',
      [state.closureRequestId!])

    // intelligence 清理（erasing→erased + PersonalDataErasure manifest）由真实跨服务流推进。
    await expect.poll(async () =>
      (await query<{ status: string }>(DATABASE_URL,
        'SELECT status FROM account_closure_request WHERE id = $1', [state.closureRequestId!]))[0]?.status,
    { timeout: 300_000, intervals: [3_000, 5_000, 8_000] }).toBe('completed')
    await ctxA.dispose()
  })

  test('R01 断言：A 个人内容清空、组织密钥保留、C 不受影响', async () => {
    const [a, , c] = accountsFor(runId)
    const drafts = await query(DATABASE_URL,
      'SELECT count(*)::int AS n FROM creation_draft WHERE owner_account_id = $1', [state.accountIdA!])
    expect(drafts[0]?.n, 'A 的个人草稿应被清理').toBe(0)
    const personalKeys = await query(DATABASE_URL,
      'SELECT count(*)::int AS n FROM ai_provider_key WHERE owner_account_id = $1 AND organization_id IS NULL',
      [state.accountIdA!])
    expect(personalKeys[0]?.n, 'A 的个人密钥应被清理').toBe(0)
    const orgKeys = await query<{ n: number; owner: string; enabled: boolean; model: string }>(DATABASE_URL,
      'SELECT count(*)::int AS n, min(owner_account_id) AS owner, bool_or(enabled) AS enabled, min(model) AS model'
      + ' FROM ai_provider_key WHERE organization_id = $1', [state.orgId!])
    expect(orgKeys[0]?.n, 'O1 组织密钥须保留（组织内容不随创建者注销删除）').toBe(1)
    expect(String(orgKeys[0]?.owner)).toBe(state.accountIdA!)
    expect(orgKeys[0]?.enabled).toBe(true)
    // C 及其组织不受影响。
    const otherOrg = await query<{ n: number }>(DATABASE_URL,
      'SELECT count(*)::int AS n FROM organization WHERE id = $2 AND owner_account_id = $1',
      [state.accountIdC!, state.otherOrgId!])
    expect(otherOrg[0]?.n).toBe(1)
    const cAccount = await query<{ status: string }>(DATABASE_URL,
      'SELECT status FROM app_users WHERE id = $1', [state.accountIdC!])
    expect(cAccount[0]?.status).toBe('active')
    void a; void c
    manifest.notes.push('R01 closed: personal erased, org retained, C untouched')
  })

  test('R02 断言：A 注销完成后 B 仍可维护 O1 组织密钥；越权拒绝', async ({ request }) => {
    const [, b, c] = accountsFor(runId)
    const ctxB = await registerAndLogin(baseURL(), b, PASSWORD, DATABASE_URL)
    await ctxB.post('/api/me/active-identity', { data: { type: 'merchant' } })
    const update = await ctxB.put(`/api/ai/organizations/${state.orgId}/keys/${state.orgKeyId}`, {
      data: { baseUrl: 'https://api.openai.com', model: 'gpt-4o' },
    })
    expect(update.status(), `B 维护应成功：${await update.text()}`).toBe(200)
    const rotate = await ctxB.put(`/api/ai/organizations/${state.orgId}/keys/${state.orgKeyId}/key`, {
      data: { apiKey: 'test-api-key-104-rotated-fixture' },
    })
    expect(rotate.status(), await rotate.text()).toBe(200)
    // creator 不因维护变更。
    const row = await query<{ owner: string }>(DATABASE_URL,
      'SELECT owner_account_id AS owner FROM ai_provider_key WHERE id = $1::uuid', [state.orgKeyId!])
    expect(String(row[0]?.owner)).toBe(state.accountIdA!)
    // 越权：C（他组织）对 O1 密钥统一 404；匿名 401。
    const ctxC = await registerAndLogin(baseURL(), c, PASSWORD, DATABASE_URL)
    await ctxC.post('/api/me/active-identity', { data: { type: 'merchant' } })
    const foreign = await ctxC.put(`/api/ai/organizations/${state.orgId}/keys/${state.orgKeyId}`, {
      data: { baseUrl: 'https://api.openai.com', model: 'gpt-4o' },
    })
    expect(foreign.status()).toBe(404)
    const anonymous = await request.put(`/api/ai/organizations/${state.orgId}/keys/${state.orgKeyId}`, {
      data: { baseUrl: 'https://api.openai.com', model: 'gpt-4o' },
    })
    expect(anonymous.status()).toBe(401)
    await ctxB.dispose(); await ctxC.dispose()
    manifest.notes.push('R02 closed: B maintains post-closure, creator unchanged, foreign/anonymous rejected')
    mkdirSync(evidenceDir, { recursive: true })
    writeFileSync(path.join(evidenceDir, `manifest-${runId}.json`),
      JSON.stringify(manifest, null, 2) + '\n')
  })
})

function baseURL(): string {
  return process.env.BASE_URL ?? 'http://localhost:18081'
}
