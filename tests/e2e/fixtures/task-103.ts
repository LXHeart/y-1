import { request as playwrightRequest, type APIRequestContext, type APIResponse } from '@playwright/test'

/**
 * 任务书 #103 C103-24：runId 作用域 fixture 构建器。
 *
 * 每次验收用自己的 runId 创建账号/合作/订单等数据，并登记到 manifest（供
 * scripts/acceptance/task-103-api-check.ts 只读核对）；禁止全库 truncate 或无 scope 清理——
 * 隔离栈的生命周期由 ci-e2e.sh 的 dc down --volumes 负责，本模块只登记可清理 ID。
 *
 * 邮箱使用 example.invalid 合成域（§12.1），不复制真实账号；不落任何密钥。
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

/** 通过真实 API 注册并登录一个 runId 作用域账号（隔离栈真实路由，非 browser route 伪造）。 */
export async function registerAndLogin(
  baseURL: string,
  account: FixtureAccount,
  password: string,
): Promise<APIRequestContext> {
  const context = await playwrightRequest.newContext({
    baseURL,
    timeout: 30_000,
    extraHTTPHeaders: { Origin: baseURL },
  })
  const register = await context.post('/api/auth/register', {
    data: { email: account.email, password, displayName: account.displayName },
  })
  // 201 = 新建；409 = 同 runId 重放（幂等继续登录）
  if (register.status() !== 201 && register.status() !== 409) {
    throw new Error(`register ${account.email} failed: ${register.status()} ${await register.text()}`)
  }
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
