import { request as playwrightRequest, type APIRequestContext, type APIResponse } from '@playwright/test'
import bcrypt from 'bcryptjs'
import { Client } from 'pg'

/**
 * 任务书 #103 C103-24：runId 作用域 fixture 构建器。
 *
 * 每次验收用自己的 runId 创建账号/合作/订单等数据，并登记到 manifest（供
 * scripts/acceptance/task-103-api-check.ts 只读核对）；禁止全库 truncate 或无 scope 清理——
 * 隔离栈的生命周期由 ci-e2e.sh 的 dc down --volumes 负责，本模块只登记可清理 ID。
 *
 * 邮箱使用 example.invalid 合成域（§12.1），不复制真实账号；不落任何密钥。
 * 账号创建走 scripts/e2e-seed.ts 同款 DB 直插配方（bcrypt 哈希，注册 API 的邮箱验证码
 * 流不适合无邮箱收件环境的 e2e），登录走真实 API。
 */

export interface FixtureAccount {
  email: string
  role: 'merchant' | 'recommender' | 'consumer' | 'admin'
  displayName: string
}

export interface RunManifest {
  runId: string
  environment: 'isolated-stack' | 'fixture'
  generatedAt: string
  accounts: FixtureAccount[]
  /** 各流程登记的事实引用（只放 ID/引用，金额核对走 api-check 的只读查询）。 */
  flows: {
    exit?: { taskId?: string; applicationId?: string; exitRequestId?: string }
    order?: { orderRef?: string; orderId?: string }
    closure?: { accountId?: string; closureRequestId?: string }
    notification?: { notificationIds?: string[] }
    member?: { organizationId?: string; storeId?: string }
  }
  notes: string[]
}

/** runId：时间戳 + 随机后缀，唯一且可排序。 */
export function newRunId(): string {
  const stamp = new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14)
  const rand = Math.random().toString(36).slice(2, 8)
  return `t103-${stamp}-${rand}`
}

/** runId 作用域邮箱：example.invalid 合成域。 */
export function scopedEmail(runId: string, role: FixtureAccount['role'], seq: number): string {
  return `${runId}-${role}-${seq}@example.invalid`
}

export function fixtureAccounts(runId: string, count = 3): FixtureAccount[] {
  const roles: FixtureAccount['role'][] = ['merchant', 'recommender', 'consumer']
  return Array.from({ length: count }, (_, index) => ({
    email: scopedEmail(runId, roles[index % roles.length], index + 1),
    role: roles[index % roles.length],
    displayName: `T103 ${roles[index % roles.length]} ${index + 1}`,
  }))
}

export function emptyManifest(runId: string, environment: RunManifest['environment'] = 'isolated-stack'): RunManifest {
  return {
    runId,
    environment,
    generatedAt: new Date().toISOString(),
    accounts: [],
    flows: {},
    notes: [],
  }
}

/** 创建并登录一个 runId 作用域账号：DB 直插（e2e-seed 同款 bcrypt 配方，幂等）+ 真实登录 API。 */
export async function registerAndLogin(
  baseURL: string,
  account: FixtureAccount,
  password: string,
  databaseUrl: string,
): Promise<APIRequestContext> {
  const client = new Client({ connectionString: databaseUrl })
  await client.connect()
  try {
    const hash = bcrypt.hashSync(password, 10)
    const existing = await client.query('SELECT id FROM app_users WHERE email = $1', [account.email])
    if (existing.rowCount === 0) {
      await client.query(
        "INSERT INTO app_users(id, email, password_hash, status, role, display_name)"
          + " VALUES (gen_random_uuid(), $1, $2, 'active', 'user', $3)",
        [account.email, hash, account.displayName])
    } else {
      await client.query('UPDATE app_users SET password_hash = $2 WHERE email = $1', [account.email, hash])
    }
  } finally {
    await client.end()
  }

  const context = await playwrightRequest.newContext({
    baseURL,
    timeout: 30_000,
    extraHTTPHeaders: { Origin: baseURL },
  })
  const login = await context.post('/api/auth/login', { data: { email: account.email, password } })
  if (login.status() !== 200) {
    throw new Error(`login ${account.email} failed: ${login.status()} ${await login.text()}`)
  }
  return context
}

/** manifest 序列化（顺序稳定，便于重放比对与 api-check 消费）。 */
export function serializeManifest(manifest: RunManifest): string {
  const ordered: RunManifest = {
    runId: manifest.runId,
    environment: manifest.environment,
    generatedAt: manifest.generatedAt,
    accounts: [...manifest.accounts].sort((a, b) => a.email.localeCompare(b.email)),
    flows: manifest.flows,
    notes: manifest.notes,
  }
  return `${JSON.stringify(ordered, null, 2)}\n`
}

/** 从 APIResponse 信封解 data（与 task98 同款口径）。 */
export async function envelopeData<T>(response: APIResponse): Promise<T> {
  const body = await response.json() as { success: boolean; data: T; error?: string }
  if (!body.success) {
    throw new Error(`API envelope failure: ${response.status()} ${body.error}`)
  }
  return body.data
}
