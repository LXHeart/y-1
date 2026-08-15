import { ref } from 'vue'
import { request } from './grassland-http'

/**
 * 积分套餐（AI 套餐 v1，平台内闭环）。
 *
 * 用户侧：active SKU 列表、购买（Sandbox 支付即时生效）、本人购买记录。
 * 购买成功返回新余额（null = 失败），调用方据此刷新徽标。
 */

export interface CreditsPackage {
  id: string
  name: string
  description: string
  priceCents: number
  creditsAmount: number
}

export interface CreditsPurchaseOrder {
  id: string
  packageId: string
  priceCents: number
  creditsAmount: number
  status: string
}

interface PurchaseOutcome {
  orderId: string
  status: string
  creditsAmount: number
  balance: number | ''
}

export function useCreditsPackages() {
  const packages = ref<CreditsPackage[]>([])
  const orders = ref<CreditsPurchaseOrder[]>([])
  const loading = ref(false)
  const purchasing = ref(false)
  const error = ref('')

  async function loadPackages(): Promise<void> {
    loading.value = true
    error.value = ''
    try {
      packages.value = await request<CreditsPackage[]>('/api/credits/packages')
    } catch (err: unknown) {
      error.value = err instanceof Error ? err.message : '积分套餐加载失败'
    } finally {
      loading.value = false
    }
  }

  async function purchase(packageId: string): Promise<number | null> {
    if (purchasing.value) return null
    purchasing.value = true
    error.value = ''
    try {
      const outcome = await request<PurchaseOutcome>('/api/credits/purchase-orders', {
        method: 'POST',
        body: JSON.stringify({ packageId }),
      })
      return outcome.balance === '' ? null : outcome.balance
    } catch (err: unknown) {
      error.value = err instanceof Error ? err.message : '购买失败，请稍后重试'
      return null
    } finally {
      purchasing.value = false
    }
  }

  async function loadOrders(): Promise<void> {
    loading.value = true
    error.value = ''
    try {
      orders.value = await request<CreditsPurchaseOrder[]>('/api/credits/purchase-orders')
    } catch (err: unknown) {
      error.value = err instanceof Error ? err.message : '购买记录加载失败'
    } finally {
      loading.value = false
    }
  }

  return {
    packages, orders, loading, purchasing, error,
    loadPackages, purchase, loadOrders,
  }
}

/** 分 → 元 显示（最多两位小数，去尾零：990 → "9.9"，4900 → "49"）。 */
export function formatPrice(priceCents: number): string {
  return (priceCents / 100).toFixed(2).replace(/\.?0+$/, '')
}
