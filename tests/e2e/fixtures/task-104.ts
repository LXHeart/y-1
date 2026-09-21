import { Client } from 'pg'
import { registerAndLogin as loginTask103, type FixtureAccount, type RunManifest } from './task-103'

/**
 * 任务书 #104 C104-10 fixture：合成组织注销/BYOK 场景构建器。
 *
 * 沿用 task-103 的 runId 作用域口径（example.invalid 合成域、DB 直插+真实登录 API、
 * 禁止全库 truncate）。组织与成员行直接插入（与账号同款幂等配方）：O1 owner=B
 * （现任）、A 为 O1 管理员兼组织密钥创建者、C 属另一组织——用于 R01（组织内容
 * 保留）/R02（B 维护 A 创建的组织密钥）真实栈闭环。不落任何密钥/真实用户标识。
 */

export interface OrgFixture {
  organizationId: string
  otherOrganizationId: string
}

export function newRunId(): string {
  const stamp = new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14)
  const rand = Math.random().toString(36).slice(2, 8)
  return `t104-${stamp}-${rand}`
}

export function accountsFor(runId: string): Array<FixtureAccount & { roleAccount: 'A' | 'B' | 'C' }> {
  return [
    { email: `${runId}-owner@example.invalid`, role: 'merchant', displayName: `T104 owner ${runId}`, roleAccount: 'A' },
    { email: `${runId}-admin@example.invalid`, role: 'merchant', displayName: `T104 admin ${runId}`, roleAccount: 'B' },
    { email: `${runId}-other@example.invalid`, role: 'merchant', displayName: `T104 other ${runId}`, roleAccount: 'C' },
  ]
}

/** 幂等插入组织与成员：O1(owner=B, admin=A) 与 O2(owner=C)；返回组织 ID。
 * O1 所有者是 B（现任）：A 仅以管理员身份创建组织密钥（creator），注销资格检查
 * （organization.owner_account_id 计数）不会被 A 挡住——R01/R02 的「原创建者注销」才走得通。 */
export async function seedOrganizations(databaseUrl: string, accountIdA: string, accountIdB: string,
  accountIdC: string, runId: string): Promise<OrgFixture> {
  const client = new Client({ connectionString: databaseUrl })
  await client.connect()
  try {
    const org = await client.query(
      'INSERT INTO organization(id, owner_account_id, name, account_prefix)'
      + " VALUES (gen_random_uuid(), $1, $2, $3) RETURNING id::text", [accountIdB, `T104 O1 ${runId}`, `t104o${runId.slice(-6)}`.slice(0, 20)])
    const other = await client.query(
      'INSERT INTO organization(id, owner_account_id, name, account_prefix)'
      + " VALUES (gen_random_uuid(), $1, $2, $3) RETURNING id::text", [accountIdC, `T104 O2 ${runId}`, `t104p${runId.slice(-6)}`.slice(0, 20)])
    await client.query(
      'INSERT INTO organization_membership(id, organization_id, account_id, role) VALUES'
      + " (gen_random_uuid(), $1, $2, 'owner'), (gen_random_uuid(), $1, $3, 'admin'), (gen_random_uuid(), $4, $5, 'owner')",
      [org.rows[0].id, accountIdB, accountIdA, other.rows[0].id, accountIdC])
    // 商家身份须先开通（identity_profile），否则 /api/me/active-identity 400「未开通该身份」。
    await client.query(
      'INSERT INTO identity_profile(id, account_id, identity_type, organization_id, status) VALUES'
      + " (gen_random_uuid(), $1, 'merchant', $2, 'active'), (gen_random_uuid(), $3, 'merchant', $2, 'active'),"
      + " (gen_random_uuid(), $4, 'merchant', $5, 'active')"
      + " ON CONFLICT (account_id, identity_type) DO UPDATE SET organization_id = EXCLUDED.organization_id, status = 'active'",
      [accountIdA, org.rows[0].id, accountIdB, accountIdC, other.rows[0].id])
    return { organizationId: String(org.rows[0].id), otherOrganizationId: String(other.rows[0].id) }
  } finally {
    await client.end()
  }
}

export async function accountIdOf(databaseUrl: string, email: string): Promise<string> {
  const client = new Client({ connectionString: databaseUrl })
  await client.connect()
  try {
    const rows = await client.query('SELECT id::text AS id FROM app_users WHERE email = $1', [email])
    if (rows.rowCount !== 1) throw new Error(`账号未落库：${email}`)
    return String(rows.rows[0].id)
  } finally {
    await client.end()
  }
}

export async function query<T = Record<string, unknown>>(databaseUrl: string, sql: string,
  params: unknown[] = []): Promise<T[]> {
  const client = new Client({ connectionString: databaseUrl })
  await client.connect()
  try {
    const result = await client.query(sql, params)
    return result.rows as T[]
  } finally {
    await client.end()
  }
}

export { loginTask103 as registerAndLogin }

export async function envelopeData<T>(response: { status(): number; json(): Promise<unknown> }, label: string): Promise<T> {
  if (response.status() >= 300) {
    throw new Error(`${label} failed: ${response.status()}`)
  }
  const body = await response.json() as { success?: boolean; data?: T; error?: string }
  // identity 合规族是 {success,data} 信封；intelligence BYOK 成功响应为裸 JSON（仅错误体带 success:false）。
  if (body !== null && typeof body === 'object' && 'success' in body) {
    if (!body.success) {
      throw new Error(`${label} envelope failure: ${body.error}`)
    }
    return body.data as T
  }
  return body as T
}

export function emptyManifest(runId: string): RunManifest {
  return {
    runId,
    environment: 'isolated-stack',
    generatedAt: new Date().toISOString(),
    accounts: [],
    flows: {},
    notes: [],
  }
}
