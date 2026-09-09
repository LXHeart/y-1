<template>
  <section class="commerce-admin">
    <header>
      <div><h3>消费订单与核销监控</h3><p>查看支付、退款、核销分账卡点；Sandbox 流水也会进入财务双录账本。</p></div>
      <button type="button" :disabled="commerce.loading.value" @click="load">刷新</button>
    </header>
    <div class="filters">
      <select v-model="status" @change="onStatusChange">
        <option value="">全部状态</option><option value="pending_payment">支付处理中</option>
        <option value="paid">待核销</option><option value="redeeming">分账中</option><option value="splitting">结算中</option>
        <option value="redeemed">已核销</option><option value="refund_pending">退款中</option>
        <option value="partially_refunded">部分退款</option>
        <option value="refunded">已退款</option><option value="after_sales_disputed">售后争议</option>
        <option value="payment_failed">支付失败</option><option value="cancelled">已取消</option>
      </select>
      <span>共 {{ ordersTotal }} 笔</span>
    </div>
    <p v-if="commerce.error.value" class="error-msg">{{ commerce.error.value }}</p>
    <div class="table-wrap">
      <table>
        <thead><tr><th>订单/套餐</th><th>消费者</th><th>组织/门店</th><th>金额</th><th>状态</th><th>支付/核销时间</th><th>异常</th></tr></thead>
        <tbody>
          <tr v-for="order in orders" :key="order.id">
            <td><strong>{{ order.packageTitle }}</strong><code>{{ short(order.id) }} · v{{ order.packageVersion }}{{ order.slotStart ? ` · 时段 ${format(order.slotStart)}` : '' }}</code></td>
            <td><code>{{ short(order.consumerAccountId) }}</code></td>
            <td><code>{{ short(order.organizationId) }}</code><small>{{ order.storeId ? short(order.storeId) : '组织级' }}</small></td>
            <td>¥{{ (order.priceCents / 100).toFixed(2) }}<small v-if="(order.refundedAmountCents ?? 0) > 0">已退 {{ money(order.refundedAmountCents ?? 0) }}</small><small>推 {{ money(order.recommenderAmountCents) }} / 商 {{ money(order.merchantAmountCents) }} / 平 {{ money(order.platformFeeCents) }}</small></td>
            <td><span :class="['status', order.status]">{{ statusLabel(order.status) }}</span></td>
            <td><small>支付 {{ format(order.paidAt) }}</small><small>核销 {{ format(order.redeemedAt) }}</small></td>
            <td :class="{ problem: order.lastError }">{{ order.lastError || '—' }}</td>
          </tr>
          <tr v-if="orders.length === 0"><td colspan="7" class="empty">暂无订单</td></tr>
        </tbody>
      </table>
    </div>
    <OpsPagination v-if="ordersTotal > 0" :total="ordersTotal" :limit="ordersLimit" :offset="ordersOffset"
      @change="changeOrdersPage" @change-limit="changeOrdersLimit" />
    <div class="section-head">
      <div><h4>核销与分账流水</h4><p>单独展示核销处理中和已核销订单，便于定位分账重试。</p></div>
      <span>共 {{ redemptionsTotal }} 笔</span>
    </div>
    <div class="table-wrap">
      <table>
        <thead><tr><th>订单/套餐</th><th>门店</th><th>核销状态</th><th>核销时间</th><th>分账结果</th></tr></thead>
        <tbody>
          <tr v-for="order in redemptions" :key="order.id">
            <td><strong>{{ order.packageTitle }}</strong><code>{{ short(order.id) }} · v{{ order.packageVersion }}</code></td>
            <td><code>{{ order.storeId ? short(order.storeId) : short(order.organizationId) }}</code></td>
            <td><span :class="['status', order.status]">{{ statusLabel(order.status) }}</span></td>
            <td>{{ format(order.redeemedAt) }}</td>
            <td :class="{ problem: order.lastError }">{{ order.lastError || (order.status === 'redeemed' ? '已完成三方分账' : '分账处理中') }}</td>
          </tr>
          <tr v-if="redemptions.length === 0"><td colspan="5" class="empty">暂无核销流水</td></tr>
        </tbody>
      </table>
    </div>
    <OpsPagination v-if="redemptionsTotal > 0" :total="redemptionsTotal" :limit="redemptionsLimit" :offset="redemptionsOffset"
      @change="changeRedemptionsPage" @change-limit="changeRedemptionsLimit" />
    <div class="section-head">
      <div><h4>归因申诉队列</h4><p>消费者主张的归因由平台审核：通过=按订单冻结规则改绑（客服/财务/风控），驳回=保持原归因。买家不能直接改分成。</p></div>
      <div class="filters">
        <select v-model="appealStatus" @change="onAppealStatusChange">
          <option value="open">待处理</option><option value="applied">已改绑</option>
          <option value="rejected">已驳回</option><option value="all">全部</option>
        </select>
        <span>共 {{ appealsTotal }} 条</span>
      </div>
    </div>
    <p v-if="appealError" class="error-msg">{{ appealError }}</p>
    <div class="table-wrap">
      <table>
        <thead><tr><th>订单 / 主张推荐官</th><th>消费者</th><th>申诉说明</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="appeal in appeals" :key="appeal.id">
            <td><code>{{ short(appeal.orderId) }}</code><small v-if="appeal.id">提交 {{ format(appeal.createdAt) }}</small></td>
            <td><code>{{ short(appeal.consumerAccountId) }}</code></td>
            <td class="reason"><code>{{ short(appeal.claimedRecommenderAccountId) }}</code><small>{{ appeal.reason }}</small></td>
            <td><span :class="['status', appeal.status]">{{ appealStatusLabel(appeal.status) }}</span>
              <small v-if="appeal.resolutionNote">{{ appeal.resolutionNote }}</small></td>
            <td>
              <template v-if="appeal.status === 'open'">
                <input v-model="correctionDrafts[appeal.id]!.reason" placeholder="处置说明（必填）" />
                <button type="button" :disabled="commerce.loading.value"
                  @click="applyAppeal(appeal)">按冻结规则改绑</button>
                <button type="button" class="secondary" :disabled="commerce.loading.value"
                  @click="rejectAppeal(appeal)">驳回</button>
              </template>
              <small v-else>{{ format(appeal.reviewedAt) }}</small>
            </td>
          </tr>
          <tr v-if="appeals.length === 0"><td colspan="5" class="empty">暂无申诉</td></tr>
        </tbody>
      </table>
    </div>
    <OpsPagination v-if="appealsTotal > 0" :total="appealsTotal" :limit="appealsLimit" :offset="appealsOffset"
      @change="changeAppealsPage" @change-limit="changeAppealsLimit" />

    <div class="section-head">
      <div><h4>推广链接生命周期</h4><p>按不透明推广链接 ID（rlid）查全链路：发放、触达记录、归因订单与失效原因（客服/财务/风控）。</p></div>
    </div>
    <div class="link-lookup">
      <input v-model="lifecycleQuery" placeholder="推广链接 ID（rlid）" data-testid="referral-link-query"
        @keyup.enter="lookupLifecycle" />
      <button type="button" :disabled="commerce.loading.value" @click="lookupLifecycle">查询生命周期</button>
    </div>
    <p v-if="lifecycleError" class="error-msg">{{ lifecycleError }}</p>
    <div v-if="lifecycle" class="lifecycle-result" data-testid="referral-lifecycle">
      <div class="lifecycle-meta">
        <span :class="['status', lifecycle.link.status]">{{ lifecycleStatusLabel(lifecycle.link.status) }}</span>
        <span>链接 <code>{{ lifecycle.link.referralLinkId }}</code></span>
        <span v-if="lifecycleEndReason">失效原因 {{ lifecycleEndReason }}</span>
        <span>发放 {{ format(lifecycle.link.createdAt) }}</span>
        <span>有效期至 {{ format(lifecycle.link.expiresAt) }}</span>
        <span>触达 {{ lifecycle.touchCount }} 次</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>归因订单</th><th>状态</th><th>金额</th><th>推荐官佣金</th><th>下单时间</th></tr></thead>
          <tbody>
            <tr v-for="row in lifecycle.orders" :key="row.orderId">
              <td><code>{{ short(row.orderId) }}</code></td>
              <td><span :class="['status', row.status]">{{ statusLabel(row.status as ConsumerOrder['status']) }}</span></td>
              <td>{{ money(row.priceCents) }}</td>
              <td>{{ money(row.recommenderAmountCents) }}</td>
              <td>{{ format(row.createdAt) }}</td>
            </tr>
            <tr v-if="lifecycle.orders.length === 0"><td colspan="5" class="empty">暂无归因订单</td></tr>
          </tbody>
        </table>
      </div>
      <p class="touch-note" v-if="lifecycle.recentTouches.length">
        最近触达：{{ lifecycle.recentTouches.slice(0, 5).map(t => `${format(t.touchedAt)}${t.consumerAccountId ? '' : '（未登录）'}`).join(' · ') }}
      </p>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useCommerce } from '../composables/useCommerce'
import OpsPagination from '../ops/admin/components/OpsPagination.vue'
import type { AttributionAppeal, ConsumerOrder, ReferralLifecycle } from '../types/commerce'

/** orders 与 redemptions 是两个独立分页列表（任务 #5），各自持 offset/limit/total 真源。 */
const ordersLimit = ref(10)
const redemptionsLimit = ref(10)
const commerce = useCommerce()
const orders = ref<ConsumerOrder[]>([])
const redemptions = ref<ConsumerOrder[]>([])
const status = ref('')
const ordersOffset = ref(0)
const ordersTotal = ref(0)
const redemptionsOffset = ref(0)
const redemptionsTotal = ref(0)
/** 归因申诉队列（2026-09-07 业务审查 C01）：open 默认，通过=运营纠错按冻结规则改绑。 */
const appeals = ref<AttributionAppeal[]>([])
const appealStatus = ref('open')
const appealsLimit = ref(10)
const appealsOffset = ref(0)
const appealsTotal = ref(0)
const appealError = ref('')
const correctionDrafts = reactive<Record<string, { reason: string }>>({})
onMounted(load)
async function load(): Promise<void> {
  await Promise.all([loadOrders(), loadRedemptions(), loadAppeals()])
}
async function loadOrders(): Promise<void> {
  const result = await commerce.listAdminOrders(status.value || undefined, { limit: ordersLimit.value, offset: ordersOffset.value })
  if (!result) return
  orders.value = result.items
  ordersTotal.value = result.total
}
async function loadRedemptions(): Promise<void> {
  const result = await commerce.listAdminRedemptions({ limit: redemptionsLimit.value, offset: redemptionsOffset.value })
  if (!result) return
  redemptions.value = result.items
  redemptionsTotal.value = result.total
}
async function loadAppeals(): Promise<void> {
  const result = await commerce.listAdminAttributionAppeals(
    appealStatus.value === 'all' ? undefined : appealStatus.value,
    { limit: appealsLimit.value, offset: appealsOffset.value })
  if (!result) return
  appeals.value = result.items
  appealsTotal.value = result.total
  for (const appeal of result.items) correctionDrafts[appeal.id] ||= { reason: '' }
}
function onAppealStatusChange(): void {
  appealsOffset.value = 0
  void loadAppeals()
}
function changeAppealsPage(next: number): void {
  appealsOffset.value = next
  void loadAppeals()
}
function changeAppealsLimit(limit: number): void {
  appealsLimit.value = limit
  appealsOffset.value = 0
  void loadAppeals()
}
/** 通过申诉=运营纠错：目标推荐官取申诉主张，金额由服务端按订单冻结规则重算，前端不传分成。 */
async function applyAppeal(appeal: AttributionAppeal): Promise<void> {
  const reason = (correctionDrafts[appeal.id]?.reason || '').trim()
  if (!reason) {
    appealError.value = '处置说明必填（审计留痕）'
    return
  }
  appealError.value = ''
  const updated = await commerce.correctAttribution(appeal.orderId, appeal.claimedRecommenderAccountId, reason, appeal.id)
  if (!updated) return
  await loadAppeals()
}
async function rejectAppeal(appeal: AttributionAppeal): Promise<void> {
  const reason = (correctionDrafts[appeal.id]?.reason || '').trim()
  if (!reason) {
    appealError.value = '驳回说明必填（回显给消费者）'
    return
  }
  appealError.value = ''
  const updated = await commerce.rejectAttributionAppeal(appeal.id, reason)
  if (!updated) return
  await loadAppeals()
}
function appealStatusLabel(status: AttributionAppeal['status']): string {
  return ({ open: '待处理', applied: '已改绑', rejected: '已驳回' })[status]
}

// ---------- 任务书 #98 C98-02：推广链接生命周期查询（按 rlid） ----------
const lifecycleQuery = ref('')
const lifecycleError = ref('')
const lifecycle = ref<ReferralLifecycle | null>(null)
async function lookupLifecycle(): Promise<void> {
  const query = lifecycleQuery.value.trim()
  if (!query) {
    lifecycleError.value = '请输入推广链接 ID（rlid）'
    return
  }
  lifecycleError.value = ''
  const result = await commerce.adminReferralLifecycle(query)
  lifecycle.value = result
}
function lifecycleStatusLabel(status: ReferralLifecycle['link']['status']): string {
  return ({ active: '生效中', ended: '已终止', expired: '已过期' })[status]
}
const lifecycleEndReason = computed(() => {
  const link = lifecycle.value?.link
  if (!link) return ''
  if (link.status === 'ended') return link.endedReason === 'manual' ? '本人终止' : (link.endedReason || '—')
  if (link.status === 'expired') return '超过有效期'
  return ''
})
/** 筛选变化：状态切换时 orders offset 归零重载（任务 #3 分页契约）。 */
function onStatusChange(): void {
  ordersOffset.value = 0
  void loadOrders()
}
function changeOrdersPage(next: number): void {
  ordersOffset.value = next
  void loadOrders()
}
function changeOrdersLimit(limit: number): void {
  ordersLimit.value = limit
  ordersOffset.value = 0
  void loadOrders()
}
function changeRedemptionsPage(next: number): void {
  redemptionsOffset.value = next
  void loadRedemptions()
}
function changeRedemptionsLimit(limit: number): void {
  redemptionsLimit.value = limit
  redemptionsOffset.value = 0
  void loadRedemptions()
}
function short(value: string): string { return value.length > 12 ? `${value.slice(0, 8)}…` : value }
function money(cents: number): string { return `¥${(cents / 100).toFixed(2)}` }
function format(value?: string): string { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function statusLabel(value: ConsumerOrder['status']): string { return ({ pending_payment: '支付处理中', paid: '待核销', redeeming: '分账中', redeemed: '已核销', splitting: '结算中', refund_pending: '退款中', partially_refunded: '部分退款', refunded: '已退款', after_sales_disputed: '售后争议', payment_failed: '支付失败', cancelled: '已取消' })[value] }
</script>

<style scoped>
.commerce-admin { display: grid; gap: 12px; }.commerce-admin > header, .filters, .section-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.commerce-admin h3, .commerce-admin h4, .commerce-admin p { margin: 0; }.commerce-admin header p, .section-head p { font-size: 13px; opacity: .7; }
button, select { min-height: 36px; padding: 7px 10px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); color: var(--color-text); }.table-wrap { overflow: auto; max-height: min(520px, 64vh); border: 1px solid var(--color-border); border-radius: var(--radius-lg); }table { width: 100%; border-collapse: collapse; font-size: 12px; }th, td { padding: 10px; border-bottom: 1px solid var(--color-border); text-align: left; vertical-align: top; }th { position: sticky; top: 0; z-index: 1; background: var(--color-surface); }td code, td small { display: block; margin-top: 4px; opacity: .68; }.status { display: inline-flex; padding: 3px 7px; border-radius: var(--radius-pill); background: color-mix(in srgb, var(--color-accent) 12%, transparent); }.status.redeeming, .status.splitting, .status.refund_pending, .status.pending_payment, .status.open { color: var(--color-warning); }.status.redeemed, .status.applied { color: var(--color-success); }.status.rejected { color: var(--color-danger); }.problem, .error-msg { color: var(--color-danger); }.empty { text-align: center; opacity: .65; }
td.reason small { max-width: 320px; white-space: normal; }
td input { width: 100%; min-height: 30px; margin-bottom: 6px; padding: 4px 8px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); font-size: 12px; }
button.secondary { opacity: .8; }
.link-lookup { display: flex; gap: 8px; }
.link-lookup input { flex: 1; min-height: 36px; padding: 7px 10px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); color: var(--color-text); }
.lifecycle-result { display: grid; gap: 8px; }
.lifecycle-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; font-size: 12px; opacity: .85; }
.status.active { color: var(--color-success); }
.status.ended, .status.expired { color: var(--color-text-secondary); }
.touch-note { margin: 0; font-size: 12px; opacity: .68; }
</style>
