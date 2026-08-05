import { env } from '../lib/env.js'
import { logger } from '../lib/logger.js'
import { AppError } from '../lib/errors.js'

/**
 * 积分服务的**薄 HTTP 代理**（GL-P3-AI-001 下属切片）。
 *
 * 积分存储与扣减/退款/赠送逻辑已迁入 finance-service（credits_account / credits_transaction 表 +
 * CreditsService 幂等闭环）。legacy 不再直写库，所有积分写入经此文件代理到 finance 的
 * `/internal/credits/{consume,refund,award}` 与 `/internal/credits/{balance,history}` 读端。
 *
 * <b>保持原导出名与签名不变</b> → 现有调用方零改动：`requireCredit`（douyin/bilibili fallback、video stub
 * 及仍作 feature 回滚的 article/comedy/image/video-script controller）、注册赠送（user.service）、
 * admin 调整（admin.service）、`/api/credits` 读端（credits.controller，回滚可达）。
 *
 * 鉴权：共享密钥 `X-Internal-Key`（两端同 `INTERNAL_API_KEY`，fail-closed 503）；直连 finance 容器，
 * 不带 `X-Forwarded-*`（finance 的 CreditsInternalAuthFilter 对经代理到达的内部请求 404）。
 */
export interface CreditBalance {
  balance: number
  totalEarned: number
  totalSpent: number
}

export interface CreditHistoryItem {
  id: string
  amount: number
  balanceAfter: number
  type: string
  feature: string | null
  note: string | null
  createdAt: string
}

/** 一次积分写入的结果。`deduplicated` 为 true 表示命中既有 operation_id，本次未再改余额。 */
export interface CreditMutationResult {
  balance: number
  transactionId: string
  deduplicated: boolean
}

interface FinanceMutationData {
  balance: number
  transactionId?: string
  deduplicated: boolean
}

interface FinanceEnvelope<T> {
  success?: boolean
  data?: T
  error?: string
}

/**
 * 调 finance 内部端点。402（积分不足）等错误状态透传为 AppError；finance 返回非 success 或 5xx → 502。
 * 不发任何 `X-Forwarded-*` 头（内部端点只许容器网络直连）。
 */
async function callFinance<T>(path: string, method: string, body?: unknown): Promise<T> {
  const base = env.FINANCE_CREDITS_BASE_URL
  const key = env.INTERNAL_API_KEY
  if (!base || !key) {
    throw new AppError('积分服务未配置（FINANCE_CREDITS_BASE_URL / INTERNAL_API_KEY）', 503)
  }
  const response = await fetch(`${base}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      'X-Internal-Key': key,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  const envelope = (await response.json().catch(() => ({}))) as FinanceEnvelope<T>
  if (!response.ok || !envelope.success) {
    // 4xx（含 402 积分不足 / 401 鉴权）按原状态透传；5xx 视为 finance 不可用 → 502；200 但非 success 视为异常 → 502。
    const status = !response.ok ? (response.status >= 500 ? 502 : response.status) : 502
    throw new AppError(envelope.error ?? '积分服务错误', status)
  }
  return envelope.data as T
}

export async function ensureCreditAccount(_userId: string): Promise<void> {
  // finance 在首次 consume/refund/award 时自动建户（upsert ON CONFLICT DO NOTHING），读端对缺失账户返回 0。
  // 故此处无需远程建户——保留导出仅为兼容调用方（requireCredit / admin / bridge 回滚路径）。
}

export async function getCreditBalance(userId: string): Promise<CreditBalance> {
  return callFinance<CreditBalance>(
    `/internal/credits/balance?accountId=${encodeURIComponent(userId)}`,
    'GET',
  )
}

export async function awardFreeCredits(userId: string, amount: number, note: string): Promise<void> {
  await callFinance(`/internal/credits/award`, 'POST', { accountId: userId, amount, note })
  logger.info({ userId, amount, note }, 'Credits awarded (via finance)')
}

/**
 * 扣 1 积分（consume）。传 `operationId` 即获得幂等——同一 key 重复投递只扣一次。
 * 调用方（intelligence FinanceCreditsClient、本进程 requireCredit）自行生成唯一 operationId。
 */
export async function consumeCredit(
  userId: string,
  feature: string,
  operationId?: string,
): Promise<CreditMutationResult> {
  const data = await callFinance<FinanceMutationData>('/internal/credits/consume', 'POST', {
    accountId: userId,
    feature,
    operationId,
  })
  return { balance: data.balance, transactionId: data.transactionId ?? '', deduplicated: data.deduplicated }
}

/**
 * 退还积分（refund）。传 `operationId` 即获得幂等——失败退款路径可能被重复触发，同一 key 只退一次。
 * 上游失败退款请用 {@link refundOperationId} 由扣减 key 派生（finance 按 operation_id 原样存储，
 * consume 行键 X 与退款行键 `refund:X` 不同，partial unique index 保证一次扣减至多一次退款）。
 */
export async function refundCredit(
  userId: string,
  amount: number,
  feature: string,
  note: string,
  operationId?: string,
): Promise<CreditMutationResult> {
  const data = await callFinance<FinanceMutationData>('/internal/credits/refund', 'POST', {
    accountId: userId,
    amount,
    feature,
    note,
    operationId,
  })
  return { balance: data.balance, transactionId: data.transactionId ?? '', deduplicated: data.deduplicated }
}

/** 由扣减 operation_id 派生退款 key，保证「一次扣减最多一次退款」。 */
export function refundOperationId(consumeOperationId: string): string {
  return `refund:${consumeOperationId}`
}

export async function getCreditHistory(userId: string, limit = 50): Promise<CreditHistoryItem[]> {
  const data = await callFinance<{ history: CreditHistoryItem[] }>(
    `/internal/credits/history?accountId=${encodeURIComponent(userId)}&limit=${limit}`,
    'GET',
  )
  return data.history
}
