import { ref, computed } from 'vue'

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

const balance = ref<CreditBalance | null>(null)
const loading = ref(false)
const error = ref('')

export function useCredits() {
  const currentBalance = computed(() => balance.value?.balance ?? 0)

  async function loadBalance(): Promise<void> {
    try {
      loading.value = true
      error.value = ''
      const response = await fetch('/api/credits/balance', { credentials: 'include' })
      if (!response.ok) {
        if (response.status === 401) return
        throw new Error('获取积分失败')
      }
      balance.value = await response.json() as CreditBalance
    } catch {
      error.value = '获取积分失败'
    } finally {
      loading.value = false
    }
  }

  async function loadHistory(): Promise<CreditHistoryItem[]> {
    try {
      const response = await fetch('/api/credits/history', { credentials: 'include' })
      if (!response.ok) return []
      const data = await response.json() as { history: CreditHistoryItem[] }
      return data.history ?? []
    } catch {
      return []
    }
  }

  return {
    balance,
    currentBalance,
    loading,
    error,
    loadBalance,
    loadHistory,
  }
}
