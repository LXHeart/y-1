import { ref, watch } from 'vue'
import { request } from './grassland-http'
import { useAccountSessionStore } from '../stores/account-session'

/**
 * 积分套餐（AI 套餐 v1，平台内闭环）。
 *
 * 用户侧：active SKU 列表、购买（Sandbox 支付即时生效）、本人购买记录。
 * 购买成功返回新余额（null = 失败），调用方据此刷新徽标。
 *
 * 任务书 #82 C82-04（D82-02）：套餐目录是公共数据可跨账号复用；订单、购买状态、
 * 错误与成功余额是账号私有——本 composable 每弹窗实例一份，随组件 setup 注册账号 watch，
 * 换号清私有态，所有 await 分支验账号票据，旧账号的迟到购买结果不返回余额、不写状态。
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
  const session = useAccountSessionStore()
  const packages = ref<CreditsPackage[]>([])
  const orders = ref<CreditsPurchaseOrder[]>([])
  const loading = ref(false)
  const purchasing = ref(false)
  const error = ref('')
  /** 当前私有数据（订单/购买状态）归属账号的镜像：null = 匿名。 */
  const ownerAccountId = ref<string | null>(null)

  /** 清私有态：订单/错误/loading/购买中；公共套餐目录刻意保留（D82-02）。 */
  function clearPrivateState(): void {
    orders.value = []
    loading.value = false
    purchasing.value = false
    error.value = ''
  }

  /** 换 owner 先清后记；同 owner 直通（幂等）。 */
  function resetForAccount(accountId: string | null): void {
    if (ownerAccountId.value === accountId) return
    ownerAccountId.value = accountId
    clearPrivateState()
  }

  resetForAccount(session.ownerAccountId)
  watch(
    () => session.ownerAccountId,
    (accountId) => { resetForAccount(accountId) },
    { flush: 'sync' },
  )

  async function loadPackages(): Promise<void> {
    const ticket = session.capture()
    loading.value = true
    error.value = ''
    try {
      const data = await request<CreditsPackage[]>('/api/credits/packages')
      if (!session.isCurrent(ticket)) return
      packages.value = data
    } catch (err: unknown) {
      if (!session.isCurrent(ticket)) return
      error.value = err instanceof Error ? err.message : '积分套餐加载失败'
    } finally {
      if (session.isCurrent(ticket)) loading.value = false
    }
  }

  async function purchase(packageId: string): Promise<number | null> {
    if (purchasing.value) return null
    const ticket = session.capture()
    purchasing.value = true
    error.value = ''
    try {
      const outcome = await request<PurchaseOutcome>('/api/credits/purchase-orders', {
        method: 'POST',
        body: JSON.stringify({ packageId }),
      })
      // 旧票静默（§4.2）：迟到的旧账号成功只返回 null，不写任何状态；
      // 调用方（弹窗）另有自己的票据闸，不据此 emit/刷新。
      if (!session.isCurrent(ticket)) return null
      return outcome.balance === '' ? null : outcome.balance
    } catch (err: unknown) {
      if (!session.isCurrent(ticket)) return null
      error.value = err instanceof Error ? err.message : '购买失败，请稍后重试'
      return null
    } finally {
      if (session.isCurrent(ticket)) purchasing.value = false
    }
  }

  async function loadOrders(): Promise<void> {
    const ticket = session.capture()
    loading.value = true
    error.value = ''
    try {
      const data = await request<CreditsPurchaseOrder[]>('/api/credits/purchase-orders')
      if (!session.isCurrent(ticket)) return
      orders.value = data
    } catch (err: unknown) {
      if (!session.isCurrent(ticket)) return
      error.value = err instanceof Error ? err.message : '购买记录加载失败'
    } finally {
      if (session.isCurrent(ticket)) loading.value = false
    }
  }

  return {
    ownerAccountId,
    packages, orders, loading, purchasing, error,
    loadPackages, purchase, loadOrders, resetForAccount,
  }
}

/** 分 → 元 显示（最多两位小数，去尾零：990 → "9.9"，4900 → "49"）。 */
export function formatPrice(priceCents: number): string {
  return (priceCents / 100).toFixed(2).replace(/\.?0+$/, '')
}
