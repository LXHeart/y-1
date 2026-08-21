import { ref } from 'vue'
import { request } from './grassland-http'
import type {
  AfterSalesDispute,
  CommercePackage,
  CommercePackageInput,
  ConsumerOrder,
  ConsumerReview,
} from '../types/commerce'

export function useCommerce() {
  const loading = ref(false)
  const error = ref('')
  let pendingOperations = 0

  async function run<T>(operation: () => Promise<T>): Promise<T | null> {
    pendingOperations += 1
    loading.value = true
    error.value = ''
    try {
      return await operation()
    } catch (caught: unknown) {
      error.value = caught instanceof Error ? caught.message : '请求失败'
      return null
    } finally {
      pendingOperations -= 1
      loading.value = pendingOperations > 0
    }
  }

  const getPackage = (id: string) => run(() => request<CommercePackage>(`/api/v2/packages/${encodeURIComponent(id)}`))
  const createOrder = (packageId: string, recommenderAccountId?: string, inventorySlotId?: string, allocations?: Array<{ recommenderAccountId: string; shareBps: number }>) => run(() => request<ConsumerOrder>('/api/v2/orders', {
    method: 'POST',
    body: JSON.stringify({ packageId, ...(recommenderAccountId ? { recommenderAccountId } : {}), ...(inventorySlotId ? { inventorySlotId } : {}), ...(allocations?.length ? { allocations } : {}) }),
  }))
  const listOrders = () => run(() => request<ConsumerOrder[]>('/api/v2/orders'))
  /** 消费者主动取消未支付订单：仅待支付（pending_payment）可取消。 */
  const cancelOrder = (id: string) => run(() => request<ConsumerOrder>(
    `/api/v2/orders/${encodeURIComponent(id)}/cancel`, { method: 'POST' }))
  const refundOrder = (id: string, reason = 'consumer_request', amountCents?: number) => run(() => request<ConsumerOrder>(
    `/api/v2/orders/${encodeURIComponent(id)}/refund`, {
      method: 'POST', body: JSON.stringify({ reason, ...(amountCents == null ? {} : { amountCents }) }),
    }))
  const openAfterSalesDispute = (id: string, reason: string) => run(() => request<ConsumerOrder>(
    `/api/v2/orders/${encodeURIComponent(id)}/after-sales-dispute`, {
      method: 'POST', body: JSON.stringify({ reason }),
    }))
  const getAfterSalesDispute = (id: string) => run(() => request<AfterSalesDispute>(
    `/api/v2/orders/${encodeURIComponent(id)}/after-sales-dispute`))
  const rebindAttribution = (id: string, allocations: Array<{ recommenderAccountId: string; shareBps: number }>, reason = 'manual') => run(() => request<ConsumerOrder>(
    `/api/v2/orders/${encodeURIComponent(id)}/attribution`, {
      method: 'POST', body: JSON.stringify({ allocations, source: 'manual', reason }),
    }))
  const listAttributionAllocations = (id: string) => run(() => request<Array<{ recommenderAccountId: string; shareBps: number; amountCents: number }>>(
    `/api/v2/orders/${encodeURIComponent(id)}/attribution`))
  const resolveAfterSalesDispute = (id: string, resolution: 'refund' | 'reject', amountCents?: number, reason = 'reviewed') => run(() => request<ConsumerOrder>(
    `/api/v2/orders/${encodeURIComponent(id)}/after-sales-dispute/resolve`, {
      method: 'POST', body: JSON.stringify({ resolution, ...(amountCents == null ? {} : { amountCents }), reason }),
    }))
  const reviewOrder = (id: string, rating: number, comment: string) => run(() => request<ConsumerReview>(
    `/api/v2/orders/${encodeURIComponent(id)}/review`, {
      method: 'POST', body: JSON.stringify({ rating, comment }),
    }))

  const listMerchantPackages = (organizationId: string, storeId?: string) => run(() => request<CommercePackage[]>(
    `/api/v2/merchant/packages?organizationId=${encodeURIComponent(organizationId)}`
      + (storeId ? `&storeId=${encodeURIComponent(storeId)}` : '')))
  const createPackage = (input: CommercePackageInput) => run(() => request<CommercePackage>('/api/v2/merchant/packages', {
    method: 'POST', body: JSON.stringify(input),
  }))
  const revisePackage = (id: string, input: CommercePackageInput) => run(() => request<CommercePackage>(
    `/api/v2/merchant/packages/${encodeURIComponent(id)}`, {
      method: 'PUT', body: JSON.stringify(input),
    }))
  const publishPackage = (id: string) => run(() => request<CommercePackage>(
    `/api/v2/merchant/packages/${encodeURIComponent(id)}/publish`, { method: 'POST' }))
  const offSalePackage = (id: string) => run(() => request<CommercePackage>(
    `/api/v2/merchant/packages/${encodeURIComponent(id)}/off-sale`, { method: 'POST' }))
  const listMerchantOrders = (organizationId: string, storeId?: string) => run(() => request<ConsumerOrder[]>(
    `/api/v2/merchant/orders?organizationId=${encodeURIComponent(organizationId)}`
      + (storeId ? `&storeId=${encodeURIComponent(storeId)}` : '')))
  const redeem = (code: string) => run(() => request<ConsumerOrder>('/api/v2/merchant/redemptions', {
    method: 'POST', body: JSON.stringify({ code }),
  }))
  const listAdminOrders = (status?: string) => run(() => request<ConsumerOrder[]>(
    `/api/admin/commerce/orders${status ? `?status=${encodeURIComponent(status)}` : ''}`))
  const listAdminRedemptions = () => run(() => request<ConsumerOrder[]>(
    '/api/admin/commerce/redemptions'))

  return {
    loading, error,
    getPackage, createOrder, listOrders, cancelOrder, refundOrder, openAfterSalesDispute, getAfterSalesDispute, rebindAttribution, listAttributionAllocations, resolveAfterSalesDispute, reviewOrder,
    listMerchantPackages, createPackage, revisePackage, publishPackage, offSalePackage,
    listMerchantOrders, redeem, listAdminOrders, listAdminRedemptions,
  }
}
