import { ref } from 'vue'
import { request, GrasslandHttpError } from './grassland-http'
import { toPagedArray } from '../types/grassland'
import type { PagedArrayCompat, PagedResult, PageQuery } from '../types/grassland'
import type {
  AfterSalesDispute,
  AttributionAppeal,
  CommercePackage,
  CommercePackageInput,
  ConsumerOrder,
  ConsumerReview,
  ReferralLink,
} from '../types/commerce'

export function useCommerce() {
  const loading = ref(false)
  const error = ref('')
  /** 最近一次失败操作的 HTTP 状态码（GrasslandHttpError 时记录）——422 归因分支等按状态分叉用。 */
  const errorStatus = ref<number | null>(null)
  let pendingOperations = 0

  async function run<T>(operation: () => Promise<T>): Promise<T | null> {
    pendingOperations += 1
    loading.value = true
    error.value = ''
    errorStatus.value = null
    try {
      return await operation()
    } catch (caught: unknown) {
      error.value = caught instanceof Error ? caught.message : '请求失败'
      errorStatus.value = caught instanceof GrasslandHttpError ? caught.status : null
      return null
    } finally {
      pendingOperations -= 1
      loading.value = pendingOperations > 0
    }
  }

  const getPackage = (id: string) => run(() => request<CommercePackage>(`/api/v2/packages/${encodeURIComponent(id)}`))
  /**
   * 下单归因参数二选一（任务书 #98 D98-01）：referralLinkId（服务端解析，链接级失效 422 可解释）
   * 与旧 recommenderAccountId（兼容期保留，前端不再生成）。
   */
  const createOrder = (packageId: string, options: {
    referralLinkId?: string
    recommenderAccountId?: string
    inventorySlotId?: string
  } = {}) => run(() => request<ConsumerOrder>('/api/v2/orders', {
    method: 'POST',
    body: JSON.stringify({
      packageId,
      ...(options.referralLinkId ? { referralLinkId: options.referralLinkId } : {}),
      ...(options.recommenderAccountId ? { recommenderAccountId: options.recommenderAccountId } : {}),
      ...(options.inventorySlotId ? { inventorySlotId: options.inventorySlotId } : {}),
    }),
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
  /** 归因申诉（2026-09-07 业务审查 C01）：只主张推荐官，不提交分成比例。 */
  const submitAttributionAppeal = (id: string, claimedRecommenderAccountId: string, reason: string) =>
    run(() => request<AttributionAppeal>(`/api/v2/orders/${encodeURIComponent(id)}/attribution-appeals`, {
      method: 'POST', body: JSON.stringify({ claimedRecommenderAccountId, reason }),
    }))
  const getAttributionAppeal = (id: string) => run(() => request<AttributionAppeal | null>(
    `/api/v2/orders/${encodeURIComponent(id)}/attribution-appeals`))
  /** 运营归因纠错：按订单冻结规则重算金额（客服/财务/风控角色）。 */
  const correctAttribution = (id: string, recommenderAccountId: string, reason: string, appealId?: string) =>
    run(() => request<ConsumerOrder>(`/api/admin/commerce/orders/${encodeURIComponent(id)}/attribution-correction`, {
      method: 'POST', body: JSON.stringify({ recommenderAccountId, reason, ...(appealId ? { appealId } : {}) }),
    }))
  const rejectAttributionAppeal = (appealId: string, note: string) =>
    run(() => request<AttributionAppeal>(`/api/admin/commerce/attribution-appeals/${encodeURIComponent(appealId)}/reject`, {
      method: 'POST', body: JSON.stringify({ note }),
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

  // ---------- 任务书 #75：套餐推广任务化 ----------

  /** 推荐官「我的推广」：本人 accepted 的套餐推广任务 + 归因订单漏斗（卡 B6）。 */
  const listMyPromotions = () => run(() => request<RecommenderPromotion[]>('/api/v2/recommender/promotions'))

  // ---------- 任务书 #98 C98-01：不透明推广链接（rlid） ----------

  /** 生成（幂等：同任务返回现行 active 链接）；url 为站内相对路径，调用方拼接 origin。 */
  const issuePromotionLink = (taskId: string) => run(() => request<ReferralLink>('/api/v2/promotion/links', {
    method: 'POST', body: JSON.stringify({ taskId }),
  }))
  /** 我的链接列表（含生效状态与失效原因）。 */
  const listMyReferralLinks = () => run(() => request<ReferralLink[]>('/api/v2/promotion/links'))
  /** 本人失效链接（重复终止幂等回显）。 */
  const endReferralLink = (referralLinkId: string) => run(() => request<ReferralLink>(
    `/api/v2/promotion/links/${encodeURIComponent(referralLinkId)}/end`, { method: 'POST' }))

  /** 商家推广统计：本主体（可选门店）全部套餐推广任务漏斗（卡 D2）。 */
  const listMerchantPromotions = (organizationId: string, storeId?: string) =>
    run(() => request<MerchantPromotion[]>(
      `/api/v2/merchant/promotions?organizationId=${encodeURIComponent(organizationId)}`
      + (storeId ? `&storeId=${encodeURIComponent(storeId)}` : '')))

  /** 任务 #3：分页信封，保留 status 筛选位参；默认 limit=50/offset=0。 */
  const listAdminOrders = (
    status?: string,
    { limit = 50, offset = 0 }: PageQuery = {},
  ) => run(async (): Promise<PagedArrayCompat<ConsumerOrder>> => {
    const qs = new URLSearchParams()
    if (status) qs.set('status', status)
    qs.set('limit', String(limit))
    qs.set('offset', String(offset))
    return toPagedArray(await request<PagedResult<ConsumerOrder>>(`/api/admin/commerce/orders?${qs}`))
  })
  const listAdminRedemptions = ({ limit = 50, offset = 0 }: PageQuery = {}) =>
    run(async (): Promise<PagedArrayCompat<ConsumerOrder>> =>
      toPagedArray(await request<PagedResult<ConsumerOrder>>(
        `/api/admin/commerce/redemptions?limit=${limit}&offset=${offset}`)))
  /** 归因申诉队列（运营）：status 缺省 open，all 看全量。 */
  const listAdminAttributionAppeals = (
    status?: string,
    { limit = 50, offset = 0 }: PageQuery = {},
  ) => run(async (): Promise<PagedArrayCompat<AttributionAppeal>> => {
    const qs = new URLSearchParams()
    if (status) qs.set('status', status)
    qs.set('limit', String(limit))
    qs.set('offset', String(offset))
    return toPagedArray(await request<PagedResult<AttributionAppeal>>(
      `/api/admin/commerce/attribution-appeals?${qs}`))
  })

  return {
    loading, error, errorStatus,
    getPackage, createOrder, listOrders, cancelOrder, refundOrder, openAfterSalesDispute, getAfterSalesDispute,
    submitAttributionAppeal, getAttributionAppeal, correctAttribution, rejectAttributionAppeal, listAttributionAllocations,
    resolveAfterSalesDispute, reviewOrder,
    listMerchantPackages, createPackage, revisePackage, publishPackage, offSalePackage,
    listMerchantOrders, redeem, listAdminOrders, listAdminRedemptions, listAdminAttributionAppeals,
    listMyPromotions, listMerchantPromotions,
    issuePromotionLink, listMyReferralLinks, endReferralLink,
  }
}

// ---------- 任务书 #75：套餐推广 promotions 端点响应 ----------

/** 佣金形态（D2）：form='fixed'（每单固定分）或 'ratio'（bps 比例），二者互斥。 */
export interface PromotionCommission {
  form: 'ratio' | 'fixed'
  shareBps: number
  fixedCents?: number
}

export interface PromotionStats {
  orderCount: number
  redeemedCount: number
  /** 已核销未满冷静期（未分账）的佣金（分）。 */
  pendingSettleCents: number
  /** 已分账入账的佣金（分）。 */
  settledCents: number
  refundedCount?: number
}

/** 推荐官「我的推广」行（卡 B6）。 */
export interface RecommenderPromotion {
  taskId: string
  taskTitle: string
  taskStatus: string
  promotionEnded?: boolean
  packageId: string
  packageTitle: string
  priceCents: number
  commission: PromotionCommission
  stats: PromotionStats
}

/** 商家推广统计行（卡 D2）。 */
export interface MerchantPromotion {
  taskId: string
  taskTitle: string
  taskStatus: string
  packageId: string
  packageTitle: string
  priceCents: number
  stats: PromotionStats
}
