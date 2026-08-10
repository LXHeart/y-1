import { ref } from 'vue'
import type {
  CommercePackage,
  CommercePackageInput,
  ConsumerOrder,
  ConsumerReview,
} from '../types/commerce'

interface Envelope<T> { success: boolean; data?: T; error?: string }

async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(url, {
    credentials: 'include',
    ...init,
    headers: init.body
      ? { 'Content-Type': 'application/json', ...(init.headers || {}) }
      : init.headers,
  })
  const body = await response.json().catch(() => null) as Envelope<T> | null
  if (!response.ok || !body?.success) {
    throw new Error(body?.error || `请求失败（${response.status}）`)
  }
  return body.data as T
}

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
  const createOrder = (packageId: string, recommenderAccountId?: string) => run(() => request<ConsumerOrder>('/api/v2/orders', {
    method: 'POST',
    body: JSON.stringify({ packageId, ...(recommenderAccountId ? { recommenderAccountId } : {}) }),
  }))
  const listOrders = () => run(() => request<ConsumerOrder[]>('/api/v2/orders'))
  const refundOrder = (id: string, reason = 'consumer_request') => run(() => request<ConsumerOrder>(
    `/api/v2/orders/${encodeURIComponent(id)}/refund`, {
      method: 'POST', body: JSON.stringify({ reason }),
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
    getPackage, createOrder, listOrders, refundOrder, reviewOrder,
    listMerchantPackages, createPackage, revisePackage, publishPackage, offSalePackage,
    listMerchantOrders, redeem, listAdminOrders, listAdminRedemptions,
  }
}
