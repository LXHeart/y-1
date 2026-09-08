<template>
  <section class="commerce-view gl-field">
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
          <span class="badge" :class="offer.status === 'published' ? 'badge-success' : 'badge-neutral'">{{ offer.status === 'published' ? '可购买' : offer.status }}</span>
          <h3>{{ offer.title }}</h3>
          <p>{{ offer.description || '门店到店套餐' }}</p>
          <dl>
            <div><dt>价格</dt><dd>{{ yuan(offer.priceCents) }}</dd></div>
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
          <p v-if="referralLinkNote" data-testid="referral-note">{{ referralLinkNote }}</p>
          <p v-else-if="referralLinkId">推广链接归因已锁定<br><code>{{ short(referralLinkId) }}</code></p>
          <p v-else-if="recommenderAccountId">推荐归因已锁定<br><code>{{ short(recommenderAccountId) }}</code></p>
          <p v-else>当前为门店自然流量订单</p>
          <button type="button" :disabled="!canBuy || commerce.loading.value" @click="buy">
            {{ offer.remainingStock <= 0 ? '已售罄' : offer.inventorySlots?.length && !selectedSlotId ? '请先选择时段' : 'Sandbox 支付下单' }}
          </button>
        </div>
      </div>
    </article>

    <article class="panel">
      <header class="panel-head"><div><h3>我的消费订单</h3><p>支持部分退款、售后争议与归因申诉（分成按订单冻结规则计算）；退款会回补对应时段或版本库存。</p></div></header>
      <p v-if="!isAuthenticated" class="empty">登录后可下单并查看订单。</p>
      <p v-else-if="orders.length === 0" class="empty">暂无消费订单。</p>
      <div v-else class="order-list">
        <section v-for="order in orders" :key="order.id" class="order-card">
          <header>
            <div><strong>{{ order.packageTitle }}</strong><span class="badge" :class="statusClass(order.status)">{{ statusLabel(order.status) }}</span></div>
            <b>{{ yuan(order.priceCents) }}</b>
          </header>
          <p class="meta">
            订单 {{ short(order.id) }} · 套餐版本 v{{ order.packageVersion }} · {{ formatTime(order.createdAt) }}
            <template v-if="order.slotStart"> · 预约 {{ formatTime(order.slotStart) }} ~ {{ formatTime(order.slotEnd ?? order.slotStart) }}</template>
            <template v-if="(order.refundedAmountCents ?? 0) > 0"> · 已退 {{ yuan(order.refundedAmountCents ?? 0) }}</template>
          </p>
          <!-- 任务书 #41：待支付单显示支付截止；超时单显示已关闭（last_error=payment_timeout 不再当「处理中」展示） -->
          <p v-if="order.status === 'pending_payment' && order.paymentDeadline" class="payment-hint">
            请在 {{ formatTime(order.paymentDeadline) }} 前完成支付，超时订单将自动关闭并释放库存。
          </p>
          <p v-if="order.status === 'cancelled'" class="inline-error">
            订单已取消（{{ order.lastError === 'consumer_cancelled' ? '主动取消' : '超时自动关闭' }}），占用的库存已释放。
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
              裁定：{{ disputes[order.id].resolution === 'refund' ? `退款 ${yuan(disputes[order.id].resolutionAmountCents ?? 0)}` : '驳回' }}
              <template v-if="disputes[order.id].resolutionReason">（{{ disputes[order.id].resolutionReason }}）</template>
            </p>
          </div>

          <div v-if="order.recommenderAccountId" class="attribution-line">
            <span>归因推荐官 {{ short(order.recommenderAccountId) }} · 分成 {{ yuan(order.recommenderAmountCents) }}（按下单时套餐规则冻结）</span>
            <button v-if="canAppeal(order)" type="button" class="linklike" @click="toggle(order.id, 'attribution')">归因有误？申诉</button>
          </div>
          <div v-else-if="canAppeal(order)" class="attribution-line">
            <span>该订单为自然流量归因（无推荐官佣金）</span>
            <button type="button" class="linklike" @click="toggle(order.id, 'attribution')">我是经推荐官链接购买的</button>
          </div>
          <div v-if="appeals[order.id]" class="appeal-box" :class="appeals[order.id]!.status">
            <p><strong>归因申诉 · {{ appealStatusLabel(appeals[order.id]!.status) }}</strong></p>
            <p>主张推荐官 {{ short(appeals[order.id]!.claimedRecommenderAccountId) }}：{{ appeals[order.id]!.reason }}</p>
            <p v-if="appeals[order.id]!.status !== 'open' && appeals[order.id]!.resolutionNote">
              平台处理：{{ appeals[order.id]!.resolutionNote }}
            </p>
          </div>

          <div class="actions">
            <button v-if="order.status === 'pending_payment'" type="button" class="secondary"
              :disabled="commerce.loading.value" @click="cancel(order.id)">
              取消订单
            </button>
            <button v-if="canRefund(order) || refundBlocked(order)" type="button" :disabled="refundBlocked(order)"
              @click="toggle(order.id, 'refund')">
              {{ (order.refundedAmountCents ?? 0) > 0 ? '继续退款' : '申请退款' }}
            </button>
            <button v-if="canDispute(order)" type="button" @click="toggle(order.id, 'dispute')">
              申请售后争议
            </button>
          </div>

          <!-- 任务书 #97：已结算退款闸门——服务端 refundBlockedReason 驱动禁用态，前端不推断。 -->
          <p v-if="refundBlocked(order)" class="inline-error">订单佣金已结算，不支持退款；售后申请须在售后窗口内提出。</p>

          <div v-if="expanded[order.id] === 'refund' && canRefund(order)" class="subform">
            <p>可退余额 {{ yuan(refundableRemainder(order)) }}；留空按全额退，可多次部分退款。</p>
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

          <div v-if="expanded[order.id] === 'attribution' && canAppeal(order)" class="subform">
            <p>分成比例由订单冻结的套餐规则计算，不接受自行指定；提交后由平台审核，审核通过按冻结规则改绑。</p>
            <div class="subform-row">
              <input v-model="appealDrafts[order.id]!.recommenderAccountId" placeholder="实际带客的推荐官账号 ID" />
            </div>
            <textarea v-model="appealDrafts[order.id]!.reason" rows="3" maxlength="500" placeholder="申诉说明（必填，5-500 字）：例如经哪位推荐官的链接购买、为何当前归因有误"></textarea>
            <div class="subform-row">
              <button type="button" :disabled="commerce.loading.value" @click="appeal(order)">提交申诉</button>
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
import { formatYuan } from '../../lib/money'
import type { AfterSalesDispute, AttributionAppeal, CommercePackage, ConsumerOrder, InventorySlot } from '../../types/commerce'

const emit = defineEmits<{ 'request-login': [] }>()
const commerce = useCommerce()
const { isAuthenticated } = useAuth()
const query = new URLSearchParams(window.location.search)
const packageId = ref(query.get('package') || '')
// 任务书 #98 C98-01：新 rlid 参数（服务端解析）；旧 recommender 参数兼容期仍可解析（前端不再生成）。
// 两者同现时以 rlid 为准（服务端同传会 400，前端择一发送）。
const referralLinkId = ref(query.get('rlid') || '')
const recommenderAccountId = ref(referralLinkId.value ? '' : query.get('recommender') || '')
/** rlid 解析失败（422）后的降级说明：去 rlid 重试成功即普通订单（D98-01「订单仍可无归因创建」）。 */
const referralLinkNote = ref('')
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
const appeals = reactive<Record<string, AttributionAppeal>>({})
const appealDrafts = reactive<Record<string, { recommenderAccountId: string; reason: string }>>({})

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
  let order = await commerce.createOrder(offer.value.id, {
    referralLinkId: referralLinkId.value || undefined,
    recommenderAccountId: referralLinkId.value ? undefined : (recommenderAccountId.value || undefined),
    inventorySlotId: selectedSlotId.value || undefined,
  })
  // 任务书 #98 D98-01：rlid 解析不可归因 → 422 带可解释文案；自动去 rlid 重试一次，
  // 订单以自然流量创建（购买不被失效链接阻断），失败原因落到归因说明。
  if (!order && referralLinkId.value && commerce.errorStatus.value === 422) {
    const reason = commerce.error.value
    referralLinkId.value = ''
    referralLinkNote.value = `${reason}，本次购买将不关联推荐官。`
    order = await commerce.createOrder(offer.value.id, {
      inventorySlotId: selectedSlotId.value || undefined,
    })
  }
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

/** 消费者主动取消未支付订单：仅待支付可取消（后端 409 fail-closed），成功后刷新列表与套餐库存。 */
async function cancel(orderId: string): Promise<void> {
  const cancelled = await commerce.cancelOrder(orderId)
  if (!cancelled) return
  notice.value = '订单已取消，占用的库存已释放。'
  await Promise.all([loadOrders(), offer.value ? loadPackage() : Promise.resolve()])
}

async function loadOrders(): Promise<void> {
  const values = await commerce.listOrders()
  if (!values) return
  orders.value = values
  for (const order of values) {
    reviewDrafts[order.id] ||= { rating: 5, comment: '' }
    appealDrafts[order.id] ||= { recommenderAccountId: '', reason: '' }
    if (order.redeemCode) {
      qrByOrder[order.id] = await QRCode.toDataURL(order.redeemCode, { width: 160, margin: 1 })
    }
    if (order.status === 'after_sales_disputed' && !disputes[order.id]) {
      const detail = await commerce.getAfterSalesDispute(order.id)
      if (detail) disputes[order.id] = detail
    }
    if (canAppeal(order) || appeals[order.id]) {
      const appeal = await commerce.getAttributionAppeal(order.id)
      if (appeal) appeals[order.id] = appeal
    }
  }
}

function toggle(orderId: string, panel: 'refund' | 'dispute' | 'attribution'): void {
  expanded[orderId] = expanded[orderId] === panel ? '' : panel
}

function canRefund(order: ConsumerOrder): boolean {
  return order.status === 'paid' || order.status === 'partially_refunded'
}

/** 任务书 #97：退款按钮禁用态只读服务端 refundBlockedReason（settled_no_refund 等），前端不推断。 */
function refundBlocked(order: ConsumerOrder): boolean {
  return canRefund(order) && !!order.refundBlockedReason
}

function canDispute(order: ConsumerOrder): boolean {
  // 后端只允许 redeemed / partially_refunded 发起，且一单终身一条争议记录（UNIQUE(order_id)）。
  return (order.status === 'redeemed' || order.status === 'partially_refunded') && !disputes[order.id]
}

/** 申诉窗口：仅 paid/partially_refunded（与后端一致）；已有待处理申诉时入口收起（appeal-box 展示进度）。 */
function canAppeal(order: ConsumerOrder): boolean {
  if (order.status !== 'paid' && order.status !== 'partially_refunded') return false
  return appeals[order.id]?.status !== 'open'
}

async function appeal(order: ConsumerOrder): Promise<void> {
  const draft = appealDrafts[order.id]!
  const accountId = draft.recommenderAccountId.trim()
  const reason = draft.reason.trim()
  if (!accountId) {
    commerce.error.value = '请填写主张的推荐官账号 ID'
    return
  }
  if (reason.length < 5) {
    commerce.error.value = '申诉说明至少 5 个字'
    return
  }
  const submitted = await commerce.submitAttributionAppeal(order.id, accountId, reason)
  if (!submitted) return
  appeals[order.id] = submitted
  expanded[order.id] = ''
  notice.value = '申诉已提交，平台审核通过后将按订单冻结规则改绑归因。'
}

function appealStatusLabel(status: AttributionAppeal['status']): string {
  return ({ open: '待平台审核', applied: '已改绑', rejected: '未通过' })[status]
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
  notice.value = updated.status === 'refunded' ? '退款已完成，库存已回补。' : `部分退款成功，已退 ${yuan(updated.refundedAmountCents ?? 0)}。`
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

async function submitReview(orderId: string): Promise<void> {
  const draft = reviewDrafts[orderId]
  const result = await commerce.reviewOrder(orderId, draft.rating, draft.comment)
  if (!result) return
  reviewed.add(orderId)
  notice.value = '评价已提交。'
}

function yuan(cents: number): string { return formatYuan(cents) }
function short(value: string): string { return value.length > 14 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value }
function formatTime(value: string): string { return new Date(value).toLocaleString('zh-CN', { hour12: false }) }
function slotRange(slot: InventorySlot): string {
  const start = new Date(slot.slotStart)
  const end = new Date(slot.slotEnd)
  const sameDay = start.toDateString() === end.toDateString()
  return `${start.toLocaleString('zh-CN', { hour12: false })} ~ ${sameDay ? end.toLocaleTimeString('zh-CN', { hour12: false }) : end.toLocaleString('zh-CN', { hour12: false })}`
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
    refund_pending: '退款处理中', partially_refunded: '部分退款', refunded: '已退款', after_sales_disputed: '售后争议', payment_failed: '支付失败', cancelled: '已取消' })[status]
}

/** 订单状态徽标语义色：完成绿 / 进行中琥珀 / 异常红 / 终态灰。 */
function statusClass(status: ConsumerOrder['status']): string {
  if (status === 'paid' || status === 'redeeming' || status === 'redeemed') return 'badge-success'
  if (status === 'pending_payment' || status === 'refund_pending' || status === 'partially_refunded') return 'badge-warning'
  if (status === 'after_sales_disputed' || status === 'payment_failed' || status === 'cancelled') return 'badge-danger'
  return 'badge-neutral'
}
</script>

<style scoped>
.commerce-view { display: grid; gap: 16px; }
.commerce-hero, .panel-head, .lookup-row, .offer-card, .order-card > header, .actions, .review-box { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.commerce-hero { padding: var(--space-lg); border-radius: var(--radius-lg); color: var(--color-on-accent); background: var(--gradient-accent); box-shadow: var(--shadow-glow); }
.commerce-hero h2, .panel h3, .order-card p { margin: 0; }
.commerce-hero p { margin: 4px 0 0; opacity: .82; }
.eyebrow { font-size: 12px; letter-spacing: .14em; text-transform: uppercase; }
.panel { padding: var(--space-md); border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--surface-card); box-shadow: var(--shadow-card); }
.lookup-row input { flex: 1; }
input, select, textarea { min-height: 34px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); padding: 6px var(--space-sm); background: var(--color-surface); color: var(--color-text); }
button { cursor: pointer; font-weight: 650; }
button:disabled { opacity: .55; cursor: not-allowed; }
button.linklike { min-height: auto; padding: 2px 6px; border: none; background: none; color: var(--color-accent); text-decoration: underline; font-weight: 600; }
.offer-card { align-items: stretch; margin-top: 18px; }
.offer-main { flex: 1; }
.offer-main h3 { margin: 8px 0 4px; font-size: 24px; }
.offer-main dl { display: grid; grid-template-columns: repeat(4, minmax(100px, 1fr)); gap: 8px; margin: 16px 0 0; }
.offer-main dl div { padding: var(--space-sm); border-radius: var(--radius-md); background: color-mix(in srgb, var(--color-accent) 8%, transparent); }
dt, small, .meta { font-size: 12px; opacity: .68; } dd { margin: 4px 0 0; font-weight: 700; }
.slot-picker { margin-top: 14px; display: grid; gap: 6px; }
.slot-picker .slot-title { margin: 0 0 2px; font-size: 12px; opacity: .7; }
.slot-picker label { display: flex; align-items: center; gap: var(--space-xs); padding: 6px var(--space-sm); border: 1px solid var(--color-border); border-radius: var(--radius-sm); font-size: var(--text-sm); }
.slot-picker label.soldout { opacity: .45; }
.buy-box { width: 220px; padding: var(--space-md); display: grid; align-content: center; gap: var(--space-sm); border-radius: var(--radius-md); background: color-mix(in srgb, var(--color-accent) 10%, transparent); }
.order-card header span { margin-left: var(--space-xs); }
.notice { margin: 0; padding: var(--space-sm) var(--space-md); border-radius: var(--radius-md); }.notice.error, .inline-error { color: var(--color-danger); background: color-mix(in srgb, var(--color-danger) 10%, transparent); }.notice.ok { color: var(--color-success); background: color-mix(in srgb, var(--color-success) 10%, transparent); }
.payment-hint { margin: 0; font-size: var(--text-sm); color: var(--color-warning); background: color-mix(in srgb, var(--color-warning) 12%, transparent); padding: var(--space-xs) var(--space-sm); border-radius: var(--radius-sm); }
.order-list { display: grid; gap: 12px; margin-top: 14px; }
.order-card { display: grid; gap: var(--space-sm); padding: var(--space-sm); border-radius: var(--radius-md); background: var(--surface-furrow); }
.redeem-box { display: flex; gap: var(--space-md); align-items: center; padding: var(--space-sm); border-radius: var(--radius-md); background: color-mix(in srgb, var(--color-success) 7%, transparent); color: var(--color-text); }
.redeem-box img { width: 96px; height: 96px; }.redeem-box div { display: grid; gap: 5px; }.redeem-box code { font-size: 16px; font-weight: 800; }
.dispute-box { display: grid; gap: 4px; padding: var(--space-sm) var(--space-md); border-radius: var(--radius-md); font-size: var(--text-sm); background: color-mix(in srgb, var(--color-warning) 10%, transparent); }
.dispute-box.resolved { background: color-mix(in srgb, var(--color-success) 10%, transparent); }
.dispute-box.rejected { background: color-mix(in srgb, var(--color-danger) 8%, transparent); }
.dispute-box p { margin: 0; }
.attribution-line { display: flex; align-items: center; justify-content: space-between; gap: 10px; font-size: 12px; opacity: .78; }
.appeal-box { display: grid; gap: 4px; padding: var(--space-sm) var(--space-md); border-radius: var(--radius-md); font-size: var(--text-sm); background: color-mix(in srgb, var(--color-warning) 10%, transparent); }
.appeal-box.applied { background: color-mix(in srgb, var(--color-success) 10%, transparent); }
.appeal-box.rejected { background: color-mix(in srgb, var(--color-danger) 8%, transparent); }
.appeal-box p { margin: 0; }
.subform { display: grid; gap: var(--space-xs); padding: var(--space-sm) var(--space-md); border: 1px dashed var(--color-border); border-radius: var(--radius-md); }
.subform > p { margin: 0; font-size: 12px; opacity: .7; }
.subform-row { display: flex; gap: 8px; }
.subform-row input { flex: 1; }
.subform textarea { resize: vertical; font: inherit; }
.review-box { justify-content: flex-start; flex-wrap: wrap; }.review-box input { flex: 1; min-width: 220px; }
.empty { opacity: .66; }
@media (max-width: 720px) { .offer-card, .commerce-hero { align-items: stretch; flex-direction: column; }.buy-box { width: auto; }.offer-main dl { grid-template-columns: 1fr 1fr; }.lookup-row, .subform-row { align-items: stretch; flex-direction: column; } }
</style>
