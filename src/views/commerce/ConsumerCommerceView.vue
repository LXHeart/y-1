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
        </div>
        <div class="buy-box">
          <p v-if="recommenderAccountId">推荐归因已锁定<br><code>{{ short(recommenderAccountId) }}</code></p>
          <p v-else>当前为门店自然流量订单</p>
          <button type="button" :disabled="offer.remainingStock <= 0 || commerce.loading.value" @click="buy">
            {{ offer.remainingStock <= 0 ? '已售罄' : 'Sandbox 支付下单' }}
          </button>
        </div>
      </div>
    </article>

    <article class="panel">
      <header class="panel-head"><div><h3>我的消费订单</h3><p>支付完成后显示单次核销码；退款会自动回补对应版本库存。</p></div></header>
      <p v-if="!isAuthenticated" class="empty">登录后可下单并查看订单。</p>
      <p v-else-if="orders.length === 0" class="empty">暂无消费订单。</p>
      <div v-else class="order-list">
        <section v-for="order in orders" :key="order.id" class="order-card">
          <header>
            <div><strong>{{ order.packageTitle }}</strong><span>{{ statusLabel(order.status) }}</span></div>
            <b>¥{{ yuan(order.priceCents) }}</b>
          </header>
          <p class="meta">订单 {{ short(order.id) }} · 套餐版本 v{{ order.packageVersion }} · {{ formatTime(order.createdAt) }}</p>
          <div v-if="order.redeemCode" class="redeem-box">
            <img v-if="qrByOrder[order.id]" :src="qrByOrder[order.id]" alt="核销码二维码" />
            <div><small>到店出示核销码</small><code>{{ order.redeemCode }}</code><small>有效至 {{ formatTime(order.redeemDeadline) }}</small></div>
          </div>
          <p v-if="order.lastError" class="inline-error">处理暂未完成：{{ order.lastError }}</p>
          <div class="actions">
            <button v-if="order.status === 'paid'" type="button" :disabled="commerce.loading.value" @click="refund(order.id)">申请全额退款</button>
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
import { onMounted, reactive, ref, watch } from 'vue'
import { useAuth } from '../../composables/useAuth'
import { useCommerce } from '../../composables/useCommerce'
import type { CommercePackage, ConsumerOrder } from '../../types/commerce'

const emit = defineEmits<{ 'request-login': [] }>()
const commerce = useCommerce()
const { isAuthenticated } = useAuth()
const query = new URLSearchParams(window.location.search)
const packageId = ref(query.get('package') || '')
const recommenderAccountId = ref(query.get('recommender') || '')
const offer = ref<CommercePackage | null>(null)
const orders = ref<ConsumerOrder[]>([])
const notice = ref('')
const qrByOrder = reactive<Record<string, string>>({})
const reviewDrafts = reactive<Record<string, { rating: number; comment: string }>>({})
const reviewed = reactive(new Set<string>())

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
  if (value) offer.value = value
}

async function buy(): Promise<void> {
  if (!offer.value) return
  if (!isAuthenticated.value) {
    emit('request-login')
    return
  }
  const order = await commerce.createOrder(offer.value.id, recommenderAccountId.value || undefined)
  if (!order) return
  notice.value = order.status === 'paid'
    ? 'Sandbox 支付成功，核销码已生成。'
    : '订单已创建，支付正在后台重试。'
  await Promise.all([loadOrders(), loadPackage()])
}

async function loadOrders(): Promise<void> {
  const values = await commerce.listOrders()
  if (!values) return
  orders.value = values
  for (const order of values) {
    reviewDrafts[order.id] ||= { rating: 5, comment: '' }
    if (order.redeemCode) {
      qrByOrder[order.id] = await QRCode.toDataURL(order.redeemCode, { width: 160, margin: 1 })
    }
  }
}

async function refund(orderId: string): Promise<void> {
  const order = await commerce.refundOrder(orderId)
  if (!order) return
  notice.value = order.status === 'refunded' ? '退款已完成，库存已回补。' : '退款处理中。'
  await Promise.all([loadOrders(), offer.value ? loadPackage() : Promise.resolve()])
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
function validity(value: CommercePackage): string {
  const parts: string[] = []
  if (value.validDaysAfterPurchase) parts.push(`购买后 ${value.validDaysAfterPurchase} 天`)
  if (value.fixedRedeemDeadline) parts.push(`最晚 ${formatTime(value.fixedRedeemDeadline)}`)
  return parts.join('，')
}
function statusLabel(status: ConsumerOrder['status']): string {
  return ({ pending_payment: '支付处理中', paid: '待核销', redeeming: '核销分账中', redeemed: '已核销',
    refund_pending: '退款处理中', partially_refunded: '部分退款', refunded: '已退款', after_sales_disputed: '售后争议', payment_failed: '支付失败', cancelled: '已取消' })[status]
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
input, select, button { min-height: 38px; border: 1px solid var(--color-border); border-radius: 8px; padding: 7px 10px; background: var(--color-surface); color: var(--color-text); }
button { cursor: pointer; font-weight: 650; }
button:disabled { opacity: .55; cursor: not-allowed; }
.offer-card { align-items: stretch; margin-top: 18px; }
.offer-main { flex: 1; }
.offer-main h3 { margin: 8px 0 4px; font-size: 24px; }
.offer-main dl { display: grid; grid-template-columns: repeat(4, minmax(100px, 1fr)); gap: 8px; margin: 16px 0 0; }
.offer-main dl div { padding: 10px; border-radius: 10px; background: color-mix(in srgb, var(--color-accent) 8%, transparent); }
dt, small, .meta { font-size: 12px; opacity: .68; } dd { margin: 4px 0 0; font-weight: 700; }
.buy-box { width: 220px; padding: 16px; display: grid; align-content: center; gap: 10px; border-radius: 12px; background: color-mix(in srgb, var(--color-accent) 10%, transparent); }
.status-chip, .order-card header span { display: inline-flex; padding: 3px 8px; margin-left: 8px; border-radius: 999px; font-size: 12px; background: #daf4e7; color: #196644; }
.notice { margin: 0; padding: 10px 14px; border-radius: 10px; }.notice.error, .inline-error { color: var(--color-danger); background: color-mix(in srgb, var(--color-danger) 10%, transparent); }.notice.ok { color: var(--color-success); background: color-mix(in srgb, var(--color-success) 10%, transparent); }
.order-list { display: grid; gap: 12px; margin-top: 14px; }
.order-card { display: grid; gap: 10px; padding: 14px; border: 1px solid var(--color-border); border-radius: 12px; }
.redeem-box { display: flex; gap: 14px; align-items: center; padding: 12px; border-radius: 10px; background: #f7fbf8; color: #173b2d; }
.redeem-box img { width: 96px; height: 96px; }.redeem-box div { display: grid; gap: 5px; }.redeem-box code { font-size: 16px; font-weight: 800; }
.review-box { justify-content: flex-start; flex-wrap: wrap; }.review-box input { flex: 1; min-width: 220px; }
.empty { opacity: .66; }
@media (max-width: 720px) { .offer-card, .commerce-hero { align-items: stretch; flex-direction: column; }.buy-box { width: auto; }.offer-main dl { grid-template-columns: 1fr 1fr; }.lookup-row { align-items: stretch; flex-direction: column; } }
</style>
