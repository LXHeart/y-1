<template>
  <section class="commerce-view">
    <header class="commerce-hero">
      <div>
        <p class="eyebrow">推荐官到店消费</p>
        <h2>套餐、支付与核销</h2>
        <p>当前支付通道为 Sandbox；订单、库存、退款与分账按真实状态机记录。</p>
      </div>
      <button v-if="isAuthenticated" type="button" :disabled="commerce.loading.value" @click="loadOrders">刷新订单</button>
    </header>

    <p v-if="commerce.error.value" class="notice error" role="alert">{{ commerce.error.value }}</p>
    <p v-if="notice" class="notice ok">{{ notice }}</p>

    <article class="panel landing-panel">
      <div class="lookup-row">
        <input v-model="packageId" placeholder="输入套餐 ID，或打开推荐官分享链接" @keyup.enter="loadPackage" />
        <button type="button" :disabled="!packageId.trim() || commerce.loading.value" @click="loadPackage">查看套餐</button>
      </div>

      <div v-if="offer" class="offer-card">
        <div class="offer-main">
          <span class="status-chip">{{ offer.status === 'published' ? '可购买' : offer.status }}</span>
          <h3>{{ offer.title }}</h3>
          <p>{{ offer.description || '门店到店套餐' }}</p>
          <dl>
            <div><dt>价格</dt><dd>¥{{ yuan(offer.priceCents) }}</dd></div>
            <div><dt>库存</dt><dd>{{ offer.remainingStock }} / {{ offer.totalStock }}</dd></div>
            <div><dt>版本</dt><dd>v{{ offer.version }}</dd></div>
            <div><dt>有效期</dt><dd>{{ validity(offer) }}</dd></div>
          </dl>
          <div v-if="offer.inventorySlots?.length" class="slot-picker">
            <p class="slot-title">选择到店时段（分时段库存独立核算）</p>
            <label v-for="slot in offer.inventorySlots" :key="slot.id" :class="{ soldout: slot.remainingStock <= 0 }">
              <input v-model="selectedSlotId" type="radio" name="slot" :value="slot.id" :disabled="slot.remainingStock <= 0" />
              <span>{{ slotRange(slot) }} · 余 {{ slot.remainingStock }}</span>
            </label>
          </div>
        </div>
        <div class="buy-box">
          <p v-if="recommenderAccountId">推荐归因已锁定<br><code>{{ short(recommenderAccountId) }}</code></p>
          <p v-else>当前为门店自然流量订单</p>
          <button type="button" :disabled="!canBuy || commerce.loading.value" @click="buy">
            {{ offer.remainingStock <= 0 ? '已售罄' : offer.inventorySlots?.length && !selectedSlotId ? '请先选择时段' : 'Sandbox 支付下单' }}
          </button>
        </div>
      </div>
    </article>

    <article class="panel">
      <header class="panel-head"><div><h3>我的消费订单</h3><p>支持部分退款、售后争议与推荐归因修订；退款会回补对应时段或版本库存。</p></div></header>
      <p v-if="!isAuthenticated" class="empty">登录后可下单并查看订单。</p>
      <p v-else-if="orders.length === 0" class="empty">暂无消费订单。</p>
      <div v-else class="order-list">
        <section v-for="order in orders" :key="order.id" class="order-card">
          <header>
            <div><strong>{{ order.packageTitle }}</strong><span>{{ statusLabel(order.status) }}</span></div>
            <b>¥{{ yuan(order.priceCents) }}</b>
          </header>
          <p class="meta">
            订单 {{ short(order.id) }} · 套餐版本 v{{ order.packageVersion }} · {{ formatTime(order.createdAt) }}
            <template v-if="order.slotStart"> · 预约 {{ formatTime(order.slotStart) }} ~ {{ formatTime(order.slotEnd ?? order.slotStart) }}</template>
            <template v-if="(order.refundedAmountCents ?? 0) > 0"> · 已退 ¥{{ yuan(order.refundedAmountCents ?? 0) }}</template>
          </p>
          <!-- 任务书 #41：待支付单显示支付截止；超时单显示已关闭（last_error=payment_timeout 不再当「处理中」展示） -->
          <p v-if="order.status === 'pending_payment' && order.paymentDeadline" class="payment-hint">
            请在 {{ formatTime(order.paymentDeadline) }} 前完成支付，超时订单将自动关闭并释放库存。
          </p>
          <p v-if="order.status === 'cancelled'" class="inline-error">
            订单已超时关闭（未在截止时间前完成支付），占用的库存已释放。
          </p>
          <div v-if="order.redeemCode" class="redeem-box">
            <img v-if="qrByOrder[order.id]" :src="qrByOrder[order.id]" alt="核销码二维码" />
            <div><small>到店出示核销码</small><code>{{ order.redeemCode }}</code><small>有效至 {{ formatTime(order.redeemDeadline) }}</small></div>
          </div>
          <p v-if="order.lastError && order.status !== 'cancelled'" class="inline-error">处理暂未完成：{{ order.lastError }}</p>

          <div v-if="disputes[order.id]" class="dispute-box" :class="disputes[order.id].status">
            <p><strong>售后争议 · {{ disputeStatusLabel(disputes[order.id].status) }}</strong></p>
            <p>原因：{{ disputes[order.id].reason }}</p>
            <p v-if="disputes[order.id].status !== 'open'">
              裁定：{{ disputes[order.id].resolution === 'refund' ? `退款 ¥${yuan(disputes[order.id].resolutionAmountCents ?? 0)}` : '驳回' }}
              <template v-if="disputes[order.id].resolutionReason">（{{ disputes[order.id].resolutionReason }}）</template>
            </p>
          </div>

          <div v-if="order.recommenderAccountId" class="attribution-line">
            <span>归因推荐官 {{ short(order.recommenderAccountId) }} · 分成 ¥{{ yuan(order.recommenderAmountCents) }}</span>
            <button v-if="canRebind(order)" type="button" class="linklike" @click="toggle(order.id, 'attribution')">修改归因</button>
          </div>

          <div class="actions">
            <button v-if="canRefund(order)" type="button" @click="toggle(order.id, 'refund')">
              {{ (order.refundedAmountCents ?? 0) > 0 ? '继续退款' : '申请退款' }}
            </button>
            <button v-if="canDispute(order)" type="button" @click="toggle(order.id, 'dispute')">
              申请售后争议
            </button>
          </div>

          <div v-if="expanded[order.id] === 'refund' && canRefund(order)" class="subform">
            <p>可退余额 ¥{{ yuan(refundableRemainder(order)) }}；留空按全额退，可多次部分退款。</p>
            <div class="subform-row">
              <input v-model="refundDrafts[order.id]" inputmode="decimal" :placeholder="`退款金额（元，≤ ${yuan(refundableRemainder(order))}）`" />
              <button type="button" :disabled="commerce.loading.value" @click="requestRefund(order)">提交退款</button>
            </div>
          </div>

          <div v-if="expanded[order.id] === 'dispute' && canDispute(order) && !disputes[order.id]" class="subform">
            <textarea v-model="disputeDrafts[order.id]" rows="3" maxlength="500" placeholder="描述到店后遇到的问题（必填），商家将据此裁定退款或驳回"></textarea>
            <div class="subform-row">
              <button type="button" :disabled="commerce.loading.value" @click="openDispute(order)">提交售后争议</button>
            </div>
          </div>

          <div v-if="expanded[order.id] === 'attribution' && canRebind(order)" class="subform">
            <p>改绑后按新归因执行分账；核销或全额退款后不可修改。</p>
            <div class="subform-row">
              <input v-model="attributionDrafts[order.id]!.recommenderAccountId" placeholder="实际带客的推荐官账号 ID" />
              <input v-model="attributionDrafts[order.id]!.percent" inputmode="decimal" class="pct" placeholder="分成 %" />
              <button type="button" :disabled="commerce.loading.value" @click="rebind(order)">确认改绑</button>
            </div>
          </div>

          <div v-if="order.status === 'redeemed'" class="review-box">
            <label>评分
              <select v-model.number="reviewDrafts[order.id].rating"><option v-for="score in 5" :key="score" :value="score">{{ score }} 星</option></select>
            </label>
            <input v-model="reviewDrafts[order.id].comment" maxlength="500" placeholder="说说这次到店体验" />
            <button type="button" :disabled="commerce.loading.value || reviewed.has(order.id)" @click="submitReview(order.id)">
              {{ reviewed.has(order.id) ? '已评价' : '提交评价' }}
            </button>
          </div>
        </section>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import QRCode from 'qrcode'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useAuth } from '../../composables/useAuth'
import { useCommerce } from '../../composables/useCommerce'
import type { AfterSalesDispute, CommercePackage, ConsumerOrder, InventorySlot } from '../../types/commerce'

const emit = defineEmits<{ 'request-login': [] }>()
const commerce = useCommerce()
const { isAuthenticated } = useAuth()
const query = new URLSearchParams(window.location.search)
const packageId = ref(query.get('package') || '')
const recommenderAccountId = ref(query.get('recommender') || '')
const offer = ref<CommercePackage | null>(null)
const selectedSlotId = ref('')
const orders = ref<ConsumerOrder[]>([])
const notice = ref('')
const qrByOrder = reactive<Record<string, string>>({})
const reviewDrafts = reactive<Record<string, { rating: number; comment: string }>>({})
const reviewed = reactive(new Set<string>())
const expanded = reactive<Record<string, 'refund' | 'dispute' | 'attribution' | ''>>({})
const refundDrafts = reactive<Record<string, string>>({})
const disputeDrafts = reactive<Record<string, string>>({})
const disputes = reactive<Record<string, AfterSalesDispute>>({})
const attributionDrafts = reactive<Record<string, { recommenderAccountId: string; percent: string }>>({})

const canBuy = computed(() => {
  if (!offer.value || offer.value.remainingStock <= 0) return false
  if (offer.value.inventorySlots?.length) return Boolean(selectedSlotId.value)
  return true
})

onMounted(async () => {
  if (packageId.value) await loadPackage()
  if (isAuthenticated.value) await loadOrders()
})

watch(isAuthenticated, async (authenticated) => {
  if (authenticated) await loadOrders()
  else orders.value = []
})

async function loadPackage(): Promise<void> {
  notice.value = ''
  const value = await commerce.getPackage(packageId.value.trim())
  if (value) {
    offer.value = value
    selectedSlotId.value = ''
  }
}

async function buy(): Promise<void> {
  if (!offer.value) return
  if (!isAuthenticated.value) {
    emit('request-login')
    return
  }
  const order = await commerce.createOrder(
    offer.value.id, recommenderAccountId.value || undefined, selectedSlotId.value || undefined)
  if (!order) return
  // 先刷新订单与套餐（loadPackage 会清 notice），最后落下单提示——否则提示被冲掉。
  await Promise.all([loadOrders(), loadPackage()])
  // 任务书 #41：重试文案只对未过期的失败单出现（新单必然未到截止）；超时单由关单流程收口为 cancelled。
  notice.value = order.status === 'paid'
    ? 'Sandbox 支付成功，核销码已生成。'
    : order.paymentDeadline
      ? `订单已创建，支付正在后台重试——请在 ${formatTime(order.paymentDeadline)} 前完成支付，超时将自动关闭。`
      : '订单已创建，支付正在后台重试。'
}

async function loadOrders(): Promise<void> {
  const values = await commerce.listOrders()
  if (!values) return
  orders.value = values
  for (const order of values) {
    reviewDrafts[order.id] ||= { rating: 5, comment: '' }
    attributionDrafts[order.id] ||= { recommenderAccountId: order.recommenderAccountId || '', percent: '' }
    if (order.redeemCode) {
      qrByOrder[order.id] = await QRCode.toDataURL(order.redeemCode, { width: 160, margin: 1 })
    }
    if (order.status === 'after_sales_disputed' && !disputes[order.id]) {
      const detail = await commerce.getAfterSalesDispute(order.id)
      if (detail) disputes[order.id] = detail
    }
  }
}

function toggle(orderId: string, panel: 'refund' | 'dispute' | 'attribution'): void {
  expanded[orderId] = expanded[orderId] === panel ? '' : panel
}

function canRefund(order: ConsumerOrder): boolean {
  return order.status === 'paid' || order.status === 'partially_refunded'
}

function canDispute(order: ConsumerOrder): boolean {
  // 后端只允许 redeemed / partially_refunded 发起，且一单终身一条争议记录（UNIQUE(order_id)）。
  return (order.status === 'redeemed' || order.status === 'partially_refunded') && !disputes[order.id]
}

function canRebind(order: ConsumerOrder): boolean {
  return order.status === 'paid' || order.status === 'partially_refunded'
}

function refundableRemainder(order: ConsumerOrder): number {
  return order.priceCents - (order.refundedAmountCents ?? 0)
}

async function requestRefund(order: ConsumerOrder): Promise<void> {
  const draft = (refundDrafts[order.id] || '').trim()
  const amountCents = draft === '' ? undefined : Math.round(Number.parseFloat(draft) * 100)
  if (amountCents != null && (!Number.isFinite(amountCents) || amountCents <= 0)) {
    notice.value = ''
    commerce.error.value = '退款金额不合法'
    return
  }
  const updated = await commerce.refundOrder(order.id, 'consumer_request', amountCents)
  if (!updated) return
  expanded[order.id] = ''
  notice.value = updated.status === 'refunded' ? '退款已完成，库存已回补。' : `部分退款成功，已退 ¥${yuan(updated.refundedAmountCents ?? 0)}。`
  await Promise.all([loadOrders(), offer.value ? loadPackage() : Promise.resolve()])
}

async function openDispute(order: ConsumerOrder): Promise<void> {
  const reason = (disputeDrafts[order.id] || '').trim()
  if (!reason) {
    commerce.error.value = '争议原因不能为空'
    return
  }
  const updated = await commerce.openAfterSalesDispute(order.id, reason)
  if (!updated) return
  expanded[order.id] = ''
  notice.value = '售后争议已提交，等待商家裁定。'
  await loadOrders()
}

async function rebind(order: ConsumerOrder): Promise<void> {
  const draft = attributionDrafts[order.id]!
  const accountId = draft.recommenderAccountId.trim()
  const percent = Number.parseFloat(draft.percent)
  if (!accountId || !Number.isFinite(percent) || percent <= 0 || percent > 100) {
    commerce.error.value = '归因推荐官与分成比例不合法（0-100）'
    return
  }
  const updated = await commerce.rebindAttribution(order.id,
    [{ recommenderAccountId: accountId, shareBps: Math.round(percent * 100) }], 'consumer_rebind')
  if (!updated) return
  expanded[order.id] = ''
  notice.value = '归因已改绑，分账将按新归因执行。'
  await loadOrders()
}

async function submitReview(orderId: string): Promise<void> {
  const draft = reviewDrafts[orderId]
  const result = await commerce.reviewOrder(orderId, draft.rating, draft.comment)
  if (!result) return
  reviewed.add(orderId)
  notice.value = '评价已提交。'
}

function yuan(cents: number): string { return (cents / 100).toFixed(2) }
function short(value: string): string { return value.length > 14 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value }
function formatTime(value: string): string { return new Date(value).toLocaleString() }
function slotRange(slot: InventorySlot): string {
  const start = new Date(slot.slotStart)
  const end = new Date(slot.slotEnd)
  const sameDay = start.toDateString() === end.toDateString()
  return `${start.toLocaleString()} ~ ${sameDay ? end.toLocaleTimeString() : end.toLocaleString()}`
}
function validity(value: CommercePackage): string {
  const parts: string[] = []
  if (value.validDaysAfterPurchase) parts.push(`购买后 ${value.validDaysAfterPurchase} 天`)
  if (value.fixedRedeemDeadline) parts.push(`最晚 ${formatTime(value.fixedRedeemDeadline)}`)
  return parts.join('，')
}
function disputeStatusLabel(status: AfterSalesDispute['status']): string {
  return ({ open: '待商家裁定', resolved: '已裁定退款', rejected: '已驳回' })[status]
}
function statusLabel(status: ConsumerOrder['status']): string {
  return ({ pending_payment: '支付处理中', paid: '待核销', redeeming: '核销分账中', redeemed: '已核销',
    refund_pending: '退款处理中', partially_refunded: '部分退款', refunded: '已退款', after_sales_disputed: '售后争议', payment_failed: '支付失败', cancelled: '已超时关闭' })[status]
}
</script>

<style scoped>
.commerce-view { display: grid; gap: 16px; }
.commerce-hero, .panel-head, .lookup-row, .offer-card, .order-card > header, .actions, .review-box { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.commerce-hero { padding: 20px; border-radius: 18px; color: white; background: linear-gradient(135deg, #1f7a55, #5865d8); }
.commerce-hero h2, .panel h3, .order-card p { margin: 0; }
.commerce-hero p { margin: 4px 0 0; opacity: .82; }
.eyebrow { font-size: 12px; letter-spacing: .14em; text-transform: uppercase; }
.panel { padding: 18px; border: 1px solid var(--color-border); border-radius: 14px; background: var(--color-surface); }
.lookup-row input { flex: 1; }
input, select, button, textarea { min-height: 38px; border: 1px solid var(--color-border); border-radius: 8px; padding: 7px 10px; background: var(--color-surface); color: var(--color-text); }
button { cursor: pointer; font-weight: 650; }
button:disabled { opacity: .55; cursor: not-allowed; }
button.linklike { min-height: auto; padding: 2px 6px; border: none; background: none; color: var(--color-accent); text-decoration: underline; font-weight: 600; }
.offer-card { align-items: stretch; margin-top: 18px; }
.offer-main { flex: 1; }
.offer-main h3 { margin: 8px 0 4px; font-size: 24px; }
.offer-main dl { display: grid; grid-template-columns: repeat(4, minmax(100px, 1fr)); gap: 8px; margin: 16px 0 0; }
.offer-main dl div { padding: 10px; border-radius: 10px; background: color-mix(in srgb, var(--color-accent) 8%, transparent); }
dt, small, .meta { font-size: 12px; opacity: .68; } dd { margin: 4px 0 0; font-weight: 700; }
.slot-picker { margin-top: 14px; display: grid; gap: 6px; }
.slot-picker .slot-title { margin: 0 0 2px; font-size: 12px; opacity: .7; }
.slot-picker label { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border: 1px solid var(--color-border); border-radius: 9px; font-size: 13px; }
.slot-picker label.soldout { opacity: .45; }
.buy-box { width: 220px; padding: 16px; display: grid; align-content: center; gap: 10px; border-radius: 12px; background: color-mix(in srgb, var(--color-accent) 10%, transparent); }
.status-chip, .order-card header span { display: inline-flex; padding: 3px 8px; margin-left: 8px; border-radius: 999px; font-size: 12px; background: #daf4e7; color: #196644; }
.notice { margin: 0; padding: 10px 14px; border-radius: 10px; }.notice.error, .inline-error { color: var(--color-danger); background: color-mix(in srgb, var(--color-danger) 10%, transparent); }.notice.ok { color: var(--color-success); background: color-mix(in srgb, var(--color-success) 10%, transparent); }
.payment-hint { margin: 0; font-size: 13px; color: #8a6116; background: color-mix(in srgb, #e0a020 12%, transparent); padding: 8px 12px; border-radius: 8px; }
.order-list { display: grid; gap: 12px; margin-top: 14px; }
.order-card { display: grid; gap: 10px; padding: 14px; border: 1px solid var(--color-border); border-radius: 12px; }
.redeem-box { display: flex; gap: 14px; align-items: center; padding: 12px; border-radius: 10px; background: #f7fbf8; color: #173b2d; }
.redeem-box img { width: 96px; height: 96px; }.redeem-box div { display: grid; gap: 5px; }.redeem-box code { font-size: 16px; font-weight: 800; }
.dispute-box { display: grid; gap: 4px; padding: 10px 12px; border-radius: 10px; font-size: 13px; background: color-mix(in srgb, #a05b00 10%, transparent); }
.dispute-box.resolved { background: color-mix(in srgb, var(--color-success) 10%, transparent); }
.dispute-box.rejected { background: color-mix(in srgb, var(--color-danger) 8%, transparent); }
.dispute-box p { margin: 0; }
.attribution-line { display: flex; align-items: center; justify-content: space-between; gap: 10px; font-size: 12px; opacity: .78; }
.subform { display: grid; gap: 8px; padding: 10px 12px; border: 1px dashed var(--color-border); border-radius: 10px; }
.subform > p { margin: 0; font-size: 12px; opacity: .7; }
.subform-row { display: flex; gap: 8px; }
.subform-row input { flex: 1; } .subform-row input.pct { flex: 0 0 96px; }
.subform textarea { resize: vertical; font: inherit; }
.review-box { justify-content: flex-start; flex-wrap: wrap; }.review-box input { flex: 1; min-width: 220px; }
.empty { opacity: .66; }
@media (max-width: 720px) { .offer-card, .commerce-hero { align-items: stretch; flex-direction: column; }.buy-box { width: auto; }.offer-main dl { grid-template-columns: 1fr 1fr; }.lookup-row, .subform-row { align-items: stretch; flex-direction: column; } .subform-row input.pct { flex: 1; } }
</style>
