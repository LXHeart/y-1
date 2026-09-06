import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { request, GrasslandHttpError } from '../composables/grassland-http'
import { normalizeAccountId, useAccountSessionStore, type AccountTicket } from './account-session'
import { useAuthStore } from './auth'

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
  createdAt: string | null
}

export interface CreditHistory {
  history: CreditHistoryItem[]
}

/**
 * 积分 store（任务书 #79 C79-02 按账号隔离）：
 * - balance=null 表示未加载/失败，与成功 0 不同（§4.2）；账号变化同步清空，不显示上一账号数值；
 * - 401 不留旧余额；旧账号迟到结果（含 catch/finally）一律静默丢弃；
 * - 余额/历史经统一信封 `request()` 消费（任务书 #87）；200 裸形态=格式错误（拒绝旧协议残局）。
 */
export const useCreditsStore = defineStore('credits', () => {
  const session = useAccountSessionStore()
  const auth = useAuthStore()
  const balance = ref<CreditBalance | null>(null)
  const loading = ref(false)
  const error = ref('')
  /** 当前数据归属账号的镜像（公开可验证，任务书 #82 C82-02）：null = 匿名。 */
  const ownerAccountId = ref<string | null>(null)
  let pendingBalance: Promise<void> | null = null

  const currentBalance = computed(() => balance.value?.balance ?? 0)

  /** 账号边界（D79-02）：同步清空余额与请求标记；仅重置，不发网络。 */
  function resetForAccount(accountId: string | null): void {
    ownerAccountId.value = accountId
    pendingBalance = null
    balance.value = null
    loading.value = false
    error.value = ''
  }

  watch(
    () => normalizeAccountId(auth.currentUser?.id),
    (accountId) => { resetForAccount(accountId) },
    { flush: 'sync', immediate: true },
  )

  /** 单次余额加载（票据守卫）：401 不留旧余额；迟到请求静默丢弃。
   * request 内部 json() 解析也是 await 的一部分——解析期间换号同样不得写入（任务书 #82 C82-02）。 */
  async function loadBalanceOnce(ticket: AccountTicket): Promise<void> {
    loading.value = true
    error.value = ''
    try {
      const data = await request<CreditBalance>(
        '/api/credits/balance',
        { signal: ticket.signal },
        { fallbackError: '获取积分失败' },
      )
      if (!session.isCurrent(ticket)) return
      balance.value = data
    } catch (cause) {
      if (!session.isCurrent(ticket)) return
      if (cause instanceof GrasslandHttpError && cause.status === 401) {
        balance.value = null // 401 不留旧余额，也不写错误文案
        return
      }
      balance.value = null
      error.value = '获取积分失败'
    } finally {
      if (session.isCurrent(ticket)) loading.value = false
    }
  }

  async function loadBalance(): Promise<void> {
    if (pendingBalance) return pendingBalance
    if (!ownerAccountId.value) return // 匿名不发私有初始化请求
    const ticket = session.capture()
    const attempt = loadBalanceOnce(ticket).finally(() => {
      if (pendingBalance === attempt) pendingBalance = null
    })
    pendingBalance = attempt
    return attempt
  }

  async function loadHistory(): Promise<CreditHistoryItem[]> {
    if (!ownerAccountId.value) return []
    const ticket = session.capture()
    try {
      const data = await request<CreditHistory>('/api/credits/history', { signal: ticket.signal })
      if (!session.isCurrent(ticket)) return []
      return data.history ?? []
    } catch {
      return []
    }
  }

  return {
    ownerAccountId,
    balance,
    currentBalance,
    loading,
    error,
    loadBalance,
    loadHistory,
    resetForAccount,
  }
})
